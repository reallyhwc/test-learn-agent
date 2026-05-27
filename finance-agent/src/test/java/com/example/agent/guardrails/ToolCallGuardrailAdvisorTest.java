package com.example.agent.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolCallGuardrailAdvisor 单元测试。
 * <p>
 * 测试 userId 篡改检测、金额范围校验、写操作频率监控。
 * 当前 Advisor 仅做日志审计，不阻断请求，所以主要验证不会抛异常。
 */
class ToolCallGuardrailAdvisorTest {

    private ToolCallGuardrailAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ToolCallGuardrailAdvisor();
    }

    @Test
    void shouldPassThroughResponseWithoutToolCalls() {
        ChatClientResponse response = buildResponse("你的余额是 ¥20,273.96", List.of());
        ChatClientResponse result = advisor.after(response, null);
        assertThat(result).isSameAs(response);
    }

    @Test
    void shouldAuditNormalToolCall() {
        var toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "query_balance",
                "{\"userId\":\"test-user\",\"accountId\":\"1\"}");
        ChatClientResponse response = buildResponseWithContext(
                "", List.of(toolCall), "test-user");

        // 不应抛异常
        ChatClientResponse result = advisor.after(response, null);
        assertThat(result).isNotNull();
    }

    @Test
    void shouldDetectUserIdMismatch() {
        var toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "query_balance",
                "{\"userId\":\"hacker\",\"accountId\":\"1\"}");
        ChatClientResponse response = buildResponseWithContext(
                "", List.of(toolCall), "test-user");

        // 应记录告警日志但不抛异常
        ChatClientResponse result = advisor.after(response, null);
        assertThat(result).isNotNull();
    }

    @Test
    void shouldDetectNegativeAmount() {
        var toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "add_transaction",
                "{\"userId\":\"test-user\",\"amount\":\"-100\",\"type\":\"EXPENSE\"}");
        ChatClientResponse response = buildResponseWithContext(
                "", List.of(toolCall), "test-user");

        ChatClientResponse result = advisor.after(response, null);
        assertThat(result).isNotNull();
    }

    @Test
    void shouldDetectExcessiveAmount() {
        var toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "add_transaction",
                "{\"userId\":\"test-user\",\"amount\":\"9999999\",\"type\":\"EXPENSE\"}");
        ChatClientResponse response = buildResponseWithContext(
                "", List.of(toolCall), "test-user");

        ChatClientResponse result = advisor.after(response, null);
        assertThat(result).isNotNull();
    }

    @Test
    void shouldTrackWriteFrequency() {
        advisor.resetWriteCount("freq-user");

        for (int i = 0; i < 6; i++) {
            var toolCall = new AssistantMessage.ToolCall(
                    "call-" + i, "function", "add_transaction",
                    "{\"userId\":\"freq-user\",\"amount\":\"10\",\"type\":\"EXPENSE\"}");
            ChatClientResponse response = buildResponseWithContext(
                    "", List.of(toolCall), "freq-user");
            advisor.after(response, null);
        }
        // 第 6 次应触发频率告警（上限 5），但不抛异常
    }

    @Test
    void shouldHandleNullChatResponse() {
        ChatClientResponse response = ChatClientResponse.builder().build();
        ChatClientResponse result = advisor.after(response, null);
        assertThat(result).isNotNull();
    }

    @Test
    void shouldHandleMalformedArguments() {
        var toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "add_transaction", "not-json");
        ChatClientResponse response = buildResponseWithContext(
                "", List.of(toolCall), "test-user");

        // 不应抛异常
        ChatClientResponse result = advisor.after(response, null);
        assertThat(result).isNotNull();
    }

    @Test
    void shouldIgnoreReadToolsForFrequencyLimit() {
        advisor.resetWriteCount("read-user");

        for (int i = 0; i < 10; i++) {
            var toolCall = new AssistantMessage.ToolCall(
                    "call-" + i, "function", "list_transactions",
                    "{\"userId\":\"read-user\"}");
            ChatClientResponse response = buildResponseWithContext(
                    "", List.of(toolCall), "read-user");
            advisor.after(response, null);
        }
        // 读操作不应触发频率限制
    }

    // ========== Helper ==========

    private ChatClientResponse buildResponse(String text, List<AssistantMessage.ToolCall> toolCalls) {
        AssistantMessage message = AssistantMessage.builder()
                .content(text).toolCalls(toolCalls).build();
        Generation generation = new Generation(message);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        return ChatClientResponse.builder().chatResponse(chatResponse).build();
    }

    private ChatClientResponse buildResponseWithContext(
            String text, List<AssistantMessage.ToolCall> toolCalls, String userId) {
        AssistantMessage message = AssistantMessage.builder()
                .content(text).toolCalls(toolCalls).build();
        Generation generation = new Generation(message);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(ToolCallGuardrailAdvisor.CONTEXT_USER_ID, userId)
                .build();
    }
}
