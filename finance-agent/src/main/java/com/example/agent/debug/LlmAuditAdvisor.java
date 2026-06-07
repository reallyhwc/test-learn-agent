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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化 JSONL 审计日志 Advisor — 记录每次 LLM 调用的完整输入/输出/耗时/Agent 归属。
 *
 * <p>与现有 {@link LlmInteractionLogger} 并存，按天切分日志文件。
 * 文件路径 {@code logs/llm-audit/llm-calls-YYYY-MM-DD.jsonl}。
 * 配置键 {@code finance.audit.enabled} 控制开关（默认 true）。
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>ChatClient.Builder.defaultAdvisors(llmAuditAdvisor) — 构建时注入</li>
 *   <li>advisor 链中通过 spec.param() 传入 traceId/agentName/callType/userId</li>
 *   <li>SupervisorAgent.classify() 通过 writeRecord() 直接写入</li>
 * </ul>
 *
 * <h3>跨线程数据传递</h3>
 * <p>流式场景下 before() 和 after() 在不同线程执行，ThreadLocal 不可靠。
 * 改用 {@code request.context()} / {@code response.context()}（Spring AI 保证跨线程共享）。
 */
@Slf4j
@Component
public class LlmAuditAdvisor implements BaseAdvisor {

    public static final String ADVISOR_PARAM_TRACE_ID = "audit_traceId";
    public static final String ADVISOR_PARAM_AGENT_NAME = "audit_agentName";
    public static final String ADVISOR_PARAM_CALL_TYPE = "audit_callType";
    public static final String ADVISOR_PARAM_USER_ID = "audit_userId";

    /** request.context() 中存储捕获数据的 key 前缀，避免与用户 param 冲突 */
    private static final String CTX_START_NANOS = "__audit_startNanos";
    private static final String CTX_SYSTEM_PROMPT = "__audit_systemPrompt";
    private static final String CTX_USER_MESSAGE = "__audit_userMessage";
    private static final String CTX_MESSAGES = "__audit_messages";
    private static final String CTX_TOOLS = "__audit_tools";

    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Path logDir;
    private final boolean enabled;

    public LlmAuditAdvisor(
            @Value("${finance.audit.log-dir:logs/llm-audit}") String logDirPath,
            @Value("${finance.audit.enabled:true}") boolean enabled) {
        this.logDir = Path.of(logDirPath);
        this.enabled = enabled;
        if (enabled) {
            try {
                Files.createDirectories(this.logDir);
                log.info("LlmAuditAdvisor 已启用，日志目录: {}", this.logDir.toAbsolutePath());
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

        Map<String, Object> ctx = request.context();
        ctx.put(CTX_START_NANOS, System.nanoTime());

        // 捕获请求消息
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
            ctx.put(CTX_MESSAGES, msgList);
            if (sysPrompt != null) {
                ctx.put(CTX_SYSTEM_PROMPT, sysPrompt);
            }
            if (userMsg != null) {
                ctx.put(CTX_USER_MESSAGE, userMsg);
            }
        }

        return request;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) {
            return response;
        }

        Map<String, Object> ctx = response.context();

        // 防御: before() 未被调用或 context 被意外清理
        Long startNanos = (Long) ctx.get(CTX_START_NANOS);
        if (startNanos == null) {
            log.debug("LlmAuditAdvisor.after() 跳过：context 中无 startNanos");
            return response;
        }

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

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
            String systemPrompt = (String) ctx.get(CTX_SYSTEM_PROMPT);
            String userMessage = (String) ctx.get(CTX_USER_MESSAGE);
            List<Map<String, String>> messages = (List<Map<String, String>>) ctx.get(CTX_MESSAGES);
            List<String> tools = (List<String>) ctx.get(CTX_TOOLS);

            LlmCallRecord.RequestInfo requestInfo = new LlmCallRecord.RequestInfo(
                    systemPrompt, userMessage, messages, tools);

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
        }

        return response;
    }

    /**
     * 直接写入审计记录 — 供 SupervisorAgent.classify() 等不走 advisor 链的调用路径使用。
     */
    public void writeRecord(LlmCallRecord record) {
        if (!enabled) {
            return;
        }
        try {
            String today = LocalDate.now().format(FILE_DATE_FMT);
            Path file = logDir.resolve("llm-calls-" + today + ".jsonl");
            String json = MAPPER.writeValueAsString(record) + "\n";
            Files.writeString(file, json,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("写入审计记录失败: {}", e.getMessage());
        }
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
}
