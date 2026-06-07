package com.example.agent.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallRecordTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void shouldSerializeToJsonWithAllFields() throws Exception {
        var record = new LlmCallRecord(
                "trace-123", "supervisor", "classify", "user-1",
                Instant.parse("2026-06-07T10:30:00.123Z"), 1234L,
                new LlmCallRecord.RequestInfo(
                        "You are a classifier.", "what is my balance?",
                        List.of(Map.of("role", "user", "content", "hello")),
                        List.of("list_accounts")),
                new LlmCallRecord.ResponseInfo(
                        "booking", List.of(), "stop"),
                new LlmCallRecord.TokenUsage(500, 200, 700),
                "deepseek-chat", null
        );

        String json = mapper.writeValueAsString(record);

        assertThat(json).contains("\"traceId\":\"trace-123\"");
        assertThat(json).contains("\"agentName\":\"supervisor\"");
        assertThat(json).contains("\"callType\":\"classify\"");
        assertThat(json).contains("\"durationMs\":1234");
        assertThat(json).contains("\"systemPrompt\":\"You are a classifier.\"");
        assertThat(json).contains("\"inputTokens\":500");
    }

    @Test
    void errorRecordShouldHaveNullRequestAndResponse() throws Exception {
        var record = LlmCallRecord.error(
                "trace-456", "analyst", "execute", "user-2", 500L, "timeout");

        String json = mapper.writeValueAsString(record);
        assertThat(json).contains("\"error\":\"timeout\"");
        assertThat(json).contains("\"durationMs\":500");
        // NON_NULL: null request/response fields are omitted, not serialized as "null"
        assertThat(json).doesNotContain("\"request\"");
        assertThat(json).doesNotContain("\"response\"");
    }

    @Test
    void shouldSerializeSingleLineJson() throws Exception {
        var record = LlmCallRecord.error("t1", "bookkeeper", "execute", "u1", 100L, null);
        String json = mapper.writeValueAsString(record);
        assertThat(json).doesNotContain("\n");
    }

    @Test
    void shouldHandleSpecialCharactersInContent() throws Exception {
        var record = new LlmCallRecord(
                "trace-789", "single-agent", "execute", "user-1",
                Instant.now(), 100L,
                new LlmCallRecord.RequestInfo(
                        "System\nwith\"quotes\"", "User\nwith\\backslash",
                        List.of(), List.of()),
                new LlmCallRecord.ResponseInfo(
                        "Response with\nnewlines and \"quotes\"", List.of(), "stop"),
                new LlmCallRecord.TokenUsage(10, 5, 15),
                "deepseek-chat", null
        );

        String json = mapper.writeValueAsString(record);
        // Jackson properly escapes special characters in JSON strings
        assertThat(json).contains("System\\nwith\\\"quotes\\\"");
    }

    @Test
    void toolCallsShouldSerializeCorrectly() throws Exception {
        var response = new LlmCallRecord.ResponseInfo(
                "done",
                List.of(Map.of("name", "add_transaction",
                        "arguments", Map.of("amount", 30, "type", "EXPENSE"))),
                "tool_calls"
        );
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"name\":\"add_transaction\"");
        assertThat(json).contains("\"amount\":30");
        assertThat(json).contains("\"finishReason\":\"tool_calls\"");
    }

    @Test
    void nullErrorFieldShouldBeOmitted() throws Exception {
        var record = new LlmCallRecord(
                "t1", "bookkeeper", "execute", "u1",
                Instant.now(), 100L,
                new LlmCallRecord.RequestInfo("sys", "user", List.of(), List.of()),
                new LlmCallRecord.ResponseInfo("ok", List.of(), "stop"),
                new LlmCallRecord.TokenUsage(1, 1, 2),
                "model", null
        );
        String json = mapper.writeValueAsString(record);
        // Non-null fields present, but no "error": "null" string
        assertThat(json).contains("\"agentName\"");
        assertThat(json).doesNotContain("\"error\":");
    }
}
