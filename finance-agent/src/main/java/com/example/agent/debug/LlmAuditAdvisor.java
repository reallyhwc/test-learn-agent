package com.example.agent.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化 JSONL 审计日志 Advisor — 记录每次 LLM 调用的完整输入/输出/耗时/Agent 归属。
 *
 * <p>与现有 {@link LlmInteractionLogger} 并存，写入独立文件 {@code logs/llm-audit/llm-calls.jsonl}。
 * 配置键 {@code finance.audit.enabled} 控制开关（默认 true）。
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>ChatClient.Builder.defaultAdvisors(llmAuditAdvisor) — 构建时注入</li>
 *   <li>advisor 链中通过 spec.param() 传入 traceId/agentName/callType/userId</li>
 *   <li>SupervisorAgent.classify() 通过 writeRecord() 直接写入</li>
 * </ul>
 */
@Slf4j
@Component
public class LlmAuditAdvisor implements BaseAdvisor {

    public static final String ADVISOR_PARAM_TRACE_ID = "audit_traceId";
    public static final String ADVISOR_PARAM_AGENT_NAME = "audit_agentName";
    public static final String ADVISOR_PARAM_CALL_TYPE = "audit_callType";
    public static final String ADVISOR_PARAM_USER_ID = "audit_userId";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Path logFilePath;
    private final boolean enabled;

    /** 跨 before/after 传递：开始时间（纳秒） */
    private final ThreadLocal<Long> startTimeNanos = new ThreadLocal<>();

    /** 跨 before/after 传递：请求消息列表 */
    private final ThreadLocal<List<Map<String, String>>> capturedMessages = new ThreadLocal<>();

    /** 跨 before/after 传递：系统提示词 */
    private final ThreadLocal<String> capturedSystemPrompt = new ThreadLocal<>();

    /** 跨 before/after 传递：用户消息 */
    private final ThreadLocal<String> capturedUserMessage = new ThreadLocal<>();

    /** 跨 before/after 传递：可用工具名列表 */
    private final ThreadLocal<List<String>> capturedTools = new ThreadLocal<>();

    public LlmAuditAdvisor(
            @Value("${finance.audit.log-path:logs/llm-audit/llm-calls.jsonl}") String logPath,
            @Value("${finance.audit.enabled:true}") boolean enabled) {
        this.logFilePath = Path.of(logPath);
        this.enabled = enabled;
        if (enabled) {
            try {
                Files.createDirectories(logFilePath.getParent());
                log.info("LlmAuditAdvisor 已启用，日志文件: {}", logFilePath.toAbsolutePath());
            } catch (IOException e) {
                log.warn("无法创建审计日志目录: {}", e.getMessage());
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public String getName() {
        return "llmAuditAdvisor";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) {
            return request;
        }
        startTimeNanos.set(System.nanoTime());

        // 捕获请求消息（用于 after() 组装完整记录）
        if (request.prompt() != null && request.prompt().getInstructions() != null) {
            List<Message> messages = request.prompt().getInstructions();
            List<Map<String, String>> msgList = new ArrayList<>();
            String sysPrompt = null;
            String userMsg = null;

            for (Message msg : messages) {
                String role = msg.getMessageType().name();
                String content = msg.getText();
                msgList.add(Map.of("role", role, "content", content != null ? content : ""));
                if ("SYSTEM".equals(role) && sysPrompt == null) {
                    sysPrompt = content;
                } else if ("USER".equals(role)) {
                    userMsg = content;
                }
            }
            capturedMessages.set(msgList);
            capturedSystemPrompt.set(sysPrompt);
            capturedUserMessage.set(userMsg);
        }

        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) {
            return response;
        }

        // 防御: before() 未被调用或已被其他 after() 消费时跳过
        if (startTimeNanos.get() == null) {
            log.warn("LlmAuditAdvisor.after() 跳过：startTimeNanos 为 null（可能双重注册或 before() 未执行）");
            return response;
        }

        long durationMs = computeDurationMs();
        Map<String, Object> ctx = response.context();

        String traceId = (String) ctx.getOrDefault(ADVISOR_PARAM_TRACE_ID, "unknown");
        String agentName = (String) ctx.getOrDefault(ADVISOR_PARAM_AGENT_NAME, "unknown");
        String callType = (String) ctx.getOrDefault(ADVISOR_PARAM_CALL_TYPE, "execute");
        String userId = (String) ctx.getOrDefault(ADVISOR_PARAM_USER_ID, "unknown");

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            writeRecord(LlmCallRecord.error(traceId, agentName, callType, userId,
                    durationMs, "null chatResponse"));
            return response;
        }

        try {
            LlmCallRecord.RequestInfo requestInfo = new LlmCallRecord.RequestInfo(
                    capturedSystemPrompt.get(),
                    capturedUserMessage.get(),
                    capturedMessages.get(),
                    capturedTools.get());

            LlmCallRecord.ResponseInfo responseInfo = buildResponseInfo(chatResponse);
            LlmCallRecord.TokenUsage tokenUsage = buildTokenUsage(chatResponse);
            String model = extractModel(chatResponse);

            LlmCallRecord record = new LlmCallRecord(
                    traceId, agentName, callType, userId,
                    Instant.now(), durationMs,
                    requestInfo, responseInfo, tokenUsage, model, null);
            writeRecord(record);
        } catch (Exception e) {
            log.debug("构建审计记录失败: {}", e.getMessage());
            writeRecord(LlmCallRecord.error(traceId, agentName, callType, userId,
                    durationMs, "record build error: " + e.getMessage()));
        } finally {
            clearThreadLocals();
        }

        return response;
    }

    /**
     * 直接写入审计记录 — 供 SuperisorAgent.classify() 等不走 advisor 链的调用路径使用。
     */
    public void writeRecord(LlmCallRecord record) {
        if (!enabled) {
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(record) + "\n";
            Files.writeString(logFilePath, json,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("写入审计记录失败: {}", e.getMessage());
        }
    }

    private long computeDurationMs() {
        Long start = startTimeNanos.get();
        startTimeNanos.remove();
        if (start == null) {
            return 0;
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private LlmCallRecord.ResponseInfo buildResponseInfo(ChatResponse chatResponse) {
        List<Generation> generations = chatResponse.getResults();
        if (generations == null || generations.isEmpty()) {
            return new LlmCallRecord.ResponseInfo("", List.of(), null);
        }

        Generation gen = generations.get(0);
        AssistantMessage msg = gen.getOutput();
        String content = msg.getText();
        String finishReason = null;
        if (gen.getMetadata() != null && gen.getMetadata().getFinishReason() != null) {
            finishReason = gen.getMetadata().getFinishReason();
        }

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<AssistantMessage.ToolCall> rawToolCalls = msg.getToolCalls();
        if (rawToolCalls != null) {
            for (AssistantMessage.ToolCall tc : rawToolCalls) {
                toolCalls.add(Map.of("name", tc.name(), "arguments", tc.arguments()));
            }
        }

        return new LlmCallRecord.ResponseInfo(content, toolCalls, finishReason);
    }

    private LlmCallRecord.TokenUsage buildTokenUsage(ChatResponse chatResponse) {
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            return new LlmCallRecord.TokenUsage(
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens());
        }
        return null;
    }

    private String extractModel(ChatResponse chatResponse) {
        if (chatResponse.getMetadata() != null) {
            return chatResponse.getMetadata().getModel();
        }
        return null;
    }

    private void clearThreadLocals() {
        startTimeNanos.remove();
        capturedMessages.remove();
        capturedSystemPrompt.remove();
        capturedUserMessage.remove();
        capturedTools.remove();
    }
}
