package com.example.agent.debug;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 不可变审计记录，对应一次 LLM 调用。
 * 序列化为 JSONL 中的一行（logs/llm-audit/llm-calls.jsonl）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmCallRecord(
        String traceId,
        String agentName,
        String callType,
        String userId,
        Instant timestamp,
        long durationMs,
        RequestInfo request,
        ResponseInfo response,
        TokenUsage tokenUsage,
        String model,
        String error) {

    public record RequestInfo(
            String systemPrompt,
            String userMessage,
            List<Map<String, String>> messages,
            List<String> tools) {
    }

    public record ResponseInfo(
            String content,
            List<Map<String, Object>> toolCalls,
            String finishReason) {
    }

    public record TokenUsage(
            long inputTokens,
            long outputTokens,
            long totalTokens) {
    }

    /** LLM 调用抛异常时创建仅含错误信息的审计记录。 */
    public static LlmCallRecord error(String traceId, String agentName, String callType,
                                       String userId, long durationMs, String error) {
        return new LlmCallRecord(
                traceId, agentName, callType, userId, Instant.now(), durationMs,
                null, null, null, null, error);
    }
}
