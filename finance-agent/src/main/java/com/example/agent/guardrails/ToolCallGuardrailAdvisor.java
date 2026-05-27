package com.example.agent.guardrails;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第二层防护 — 工具调用审计 Advisor。
 * <p>
 * 在 LLM 返回结果后审计工具调用记录：
 * <ul>
 *   <li>userId 篡改检测：检查工具参数中的 userId 是否与会话 userId 一致</li>
 *   <li>金额合理性校验：add_transaction 的金额是否在合理范围内</li>
 *   <li>写操作频率监控：单次会话中写操作次数是否过多</li>
 * </ul>
 * <p>
 * 当前阶段以日志告警为主，为后续拦截机制积累数据。
 */
@Slf4j
@Component
public class ToolCallGuardrailAdvisor implements BaseAdvisor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 写操作工具集合 */
    private static final Set<String> WRITE_TOOLS = Set.of("add_transaction");

    /** 单次会话写操作上限 */
    private static final int MAX_WRITE_OPS_PER_SESSION = 5;

    /** 单笔金额上限 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    /** 每个会话（conversationId）的写操作计数 */
    private final Map<String, AtomicInteger> writeCountBySession = new ConcurrentHashMap<>();

    /** 存储在 context 中的 userId key */
    static final String CONTEXT_USER_ID = "guardrail.userId";

    @Override
    public int getOrder() {
        // 在 InputGuardrail 之后、ChatMemory 之后执行
        return Ordered.HIGHEST_PRECEDENCE + 300;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 从 context 中提取 userId（由 ChatController 在调用前写入）
        // 如果 context 中没有，尝试从 System Prompt 中提取
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return response;
        }

        AssistantMessage output = chatResponse.getResult().getOutput();
        if (output == null || !output.hasToolCalls()) {
            return response;
        }

        // 从 context 中获取会话 userId
        String sessionUserId = getSessionUserId(response);

        for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
            auditToolCall(toolCall, sessionUserId);
        }

        return response;
    }

    /**
     * 审计单次工具调用。
     */
    private void auditToolCall(AssistantMessage.ToolCall toolCall, String sessionUserId) {
        String toolName = toolCall.name();
        String arguments = toolCall.arguments();

        // 1. userId 篡改检测
        checkUserIdConsistency(toolName, arguments, sessionUserId);

        // 2. 写操作金额校验
        if (WRITE_TOOLS.contains(toolName)) {
            checkAmountRange(toolName, arguments);
            checkWriteFrequency(sessionUserId, toolName);
        }
    }

    /**
     * 检查工具参数中的 userId 是否与会话 userId 一致。
     */
    private void checkUserIdConsistency(String toolName, String arguments, String sessionUserId) {
        if (sessionUserId == null || arguments == null) {
            return;
        }
        try {
            JsonNode argsNode = MAPPER.readTree(arguments);
            JsonNode userIdNode = argsNode.get("userId");
            if (userIdNode != null && !userIdNode.asText().equals(sessionUserId)) {
                log.warn("ToolCallGuardrail: userId 篡改检测! tool={}, expected={}, actual={}",
                        toolName, sessionUserId, userIdNode.asText());
            }
        } catch (JsonProcessingException e) {
            log.debug("ToolCallGuardrail: 无法解析工具参数 JSON: {}", e.getMessage());
        }
    }

    /**
     * 检查 add_transaction 的金额是否在合理范围内。
     */
    private void checkAmountRange(String toolName, String arguments) {
        if (arguments == null) {
            return;
        }
        try {
            JsonNode argsNode = MAPPER.readTree(arguments);
            JsonNode amountNode = argsNode.get("amount");
            if (amountNode != null) {
                BigDecimal amount = new BigDecimal(amountNode.asText());
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("ToolCallGuardrail: 金额校验异常! tool={}, amount={} (非正数)", toolName, amount);
                }
                if (amount.compareTo(MAX_AMOUNT) > 0) {
                    log.warn("ToolCallGuardrail: 金额校验异常! tool={}, amount={} (超过上限 {})",
                            toolName, amount, MAX_AMOUNT);
                }
            }
        } catch (NumberFormatException | JsonProcessingException e) {
            log.debug("ToolCallGuardrail: 金额解析失败: {}", e.getMessage());
        }
    }

    /**
     * 监控写操作频率。
     */
    private void checkWriteFrequency(String sessionUserId, String toolName) {
        if (sessionUserId == null) {
            return;
        }
        int count = writeCountBySession
                .computeIfAbsent(sessionUserId, k -> new AtomicInteger(0))
                .incrementAndGet();
        if (count > MAX_WRITE_OPS_PER_SESSION) {
            log.warn("ToolCallGuardrail: 写操作频率告警! userId={}, tool={}, count={} (上限 {})",
                    sessionUserId, toolName, count, MAX_WRITE_OPS_PER_SESSION);
        }
    }

    /**
     * 重置指定会话的写操作计数（供清理使用）。
     */
    public void resetWriteCount(String sessionUserId) {
        writeCountBySession.remove(sessionUserId);
    }

    private String getSessionUserId(ChatClientResponse response) {
        Object userId = response.context().get(CONTEXT_USER_ID);
        return userId != null ? userId.toString() : null;
    }
}
