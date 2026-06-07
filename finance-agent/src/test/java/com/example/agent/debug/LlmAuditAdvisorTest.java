package com.example.agent.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmAuditAdvisorTest {

    @TempDir
    Path tempDir;

    private Path logFile;
    private LlmAuditAdvisor advisor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        logFile = tempDir.resolve("llm-calls.jsonl");
        advisor = new LlmAuditAdvisor(logFile.toString(), true);
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(advisor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 10);
    }

    @Test
    void shouldReturnCorrectName() {
        assertThat(advisor.getName()).isEqualTo("llmAuditAdvisor");
    }

    @Test
    void shouldNotWriteWhenDisabled() {
        var disabledAdvisor = new LlmAuditAdvisor(logFile.toString(), false);
        var request = mock(ChatClientRequest.class);
        var chain = mock(AdvisorChain.class);
        var response = mock(ChatClientResponse.class);

        disabledAdvisor.before(request, chain);
        disabledAdvisor.after(response, chain);

        assertThat(Files.exists(logFile)).isFalse();
    }

    @Test
    void shouldWriteErrorRecordWhenChatResponseIsNull() throws Exception {
        var request = mock(ChatClientRequest.class);
        var chain = mock(AdvisorChain.class);

        var response = mockResponse(Map.of(
                LlmAuditAdvisor.ADVISOR_PARAM_TRACE_ID, "trace-null",
                LlmAuditAdvisor.ADVISOR_PARAM_AGENT_NAME, "supervisor",
                LlmAuditAdvisor.ADVISOR_PARAM_CALL_TYPE, "classify",
                LlmAuditAdvisor.ADVISOR_PARAM_USER_ID, "user-1"));
        when(response.chatResponse()).thenReturn(null);

        advisor.before(request, chain);
        advisor.after(response, chain);

        String line = Files.readString(logFile).trim();
        assertThat(line).isNotEmpty();
        var record = mapper.readTree(line);
        assertThat(record.get("traceId").asText()).isEqualTo("trace-null");
        assertThat(record.get("agentName").asText()).isEqualTo("supervisor");
        assertThat(record.get("error").asText()).contains("null chatResponse");
    }

    @Test
    void shouldWriteCompleteRecordForSuccessfulCall() throws Exception {
        var request = mockRequest(List.of(
                mockMessage(MessageType.SYSTEM, "You are a helpful assistant."),
                mockMessage(MessageType.USER, "What is my balance?")));
        var chain = mock(AdvisorChain.class);

        var assistantMsg = mock(AssistantMessage.class);
        when(assistantMsg.getText()).thenReturn("Your balance is ¥1,234.56");
        when(assistantMsg.getToolCalls()).thenReturn(List.of());

        var genMetadata = ChatGenerationMetadata.builder()
                .finishReason("STOP")
                .build();
        var generation = new Generation(assistantMsg, genMetadata);

        var usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(Integer.valueOf(500));
        when(usage.getCompletionTokens()).thenReturn(Integer.valueOf(200));
        when(usage.getTotalTokens()).thenReturn(Integer.valueOf(700));

        var metadata = ChatResponseMetadata.builder()
                .model("deepseek-chat")
                .usage(usage)
                .build();

        var chatResponse = ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(metadata)
                .build();

        var response = mockResponse(Map.of(
                LlmAuditAdvisor.ADVISOR_PARAM_TRACE_ID, "trace-001",
                LlmAuditAdvisor.ADVISOR_PARAM_AGENT_NAME, "bookkeeper",
                LlmAuditAdvisor.ADVISOR_PARAM_CALL_TYPE, "execute",
                LlmAuditAdvisor.ADVISOR_PARAM_USER_ID, "user-1"));
        when(response.chatResponse()).thenReturn(chatResponse);

        advisor.before(request, chain);
        Thread.sleep(5);
        advisor.after(response, chain);

        String line = Files.readString(logFile).trim();
        assertThat(line).isNotEmpty();
        var record = mapper.readTree(line);

        assertThat(record.get("traceId").asText()).isEqualTo("trace-001");
        assertThat(record.get("agentName").asText()).isEqualTo("bookkeeper");
        assertThat(record.get("callType").asText()).isEqualTo("execute");
        assertThat(record.get("userId").asText()).isEqualTo("user-1");
        assertThat(record.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(record.get("model").asText()).isEqualTo("deepseek-chat");

        var tokenUsage = record.get("tokenUsage");
        assertThat(tokenUsage.get("inputTokens").asLong()).isEqualTo(500);
        assertThat(tokenUsage.get("outputTokens").asLong()).isEqualTo(200);
        assertThat(tokenUsage.get("totalTokens").asLong()).isEqualTo(700);

        var reqNode = record.get("request");
        assertThat(reqNode.get("systemPrompt").asText()).isEqualTo("You are a helpful assistant.");
        assertThat(reqNode.get("userMessage").asText()).isEqualTo("What is my balance?");

        var respNode = record.get("response");
        assertThat(respNode.get("content").asText()).isEqualTo("Your balance is ¥1,234.56");
        assertThat(respNode.get("finishReason").asText()).isEqualTo("STOP");

        assertThat(record.has("error")).isFalse();
    }

    @Test
    void shouldWriteRecordWithToolCalls() throws Exception {
        var request = mockRequest(List.of(
                mockMessage(MessageType.USER, "Add lunch expense 30 yuan")));
        var chain = mock(AdvisorChain.class);

        var toolCall = mock(AssistantMessage.ToolCall.class);
        when(toolCall.name()).thenReturn("add_transaction");
        when(toolCall.arguments()).thenReturn("{\"amount\":30,\"type\":\"EXPENSE\"}");

        var assistantMsg = mock(AssistantMessage.class);
        when(assistantMsg.getText()).thenReturn("");
        when(assistantMsg.getToolCalls()).thenReturn(List.of(toolCall));

        var genMetadata = ChatGenerationMetadata.builder()
                .finishReason("TOOL_CALLS")
                .build();
        var generation = new Generation(assistantMsg, genMetadata);

        var chatResponse = ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(ChatResponseMetadata.builder().model("deepseek-chat").build())
                .build();

        var response = mockResponse(Map.of(
                LlmAuditAdvisor.ADVISOR_PARAM_TRACE_ID, "trace-tc",
                LlmAuditAdvisor.ADVISOR_PARAM_AGENT_NAME, "bookkeeper",
                LlmAuditAdvisor.ADVISOR_PARAM_CALL_TYPE, "execute",
                LlmAuditAdvisor.ADVISOR_PARAM_USER_ID, "user-1"));
        when(response.chatResponse()).thenReturn(chatResponse);

        advisor.before(request, chain);
        advisor.after(response, chain);

        String line = Files.readString(logFile).trim();
        var record = mapper.readTree(line);

        var toolCalls = record.get("response").get("toolCalls");
        assertThat(toolCalls).isNotNull();
        assertThat(toolCalls.get(0).get("name").asText()).isEqualTo("add_transaction");
        assertThat(toolCalls.get(0).get("arguments").asText()).isEqualTo("{\"amount\":30,\"type\":\"EXPENSE\"}");
        assertThat(record.get("response").get("finishReason").asText()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void shouldUseDefaultValuesWhenContextMissing() throws Exception {
        var request = mockRequest(List.of());
        var chain = mock(AdvisorChain.class);

        var chatResponse = ChatResponse.builder()
                .generations(List.of())
                .metadata(ChatResponseMetadata.builder().build())
                .build();

        var response = mockResponse(Map.of());  // empty context
        when(response.chatResponse()).thenReturn(chatResponse);

        advisor.before(request, chain);
        advisor.after(response, chain);

        String line = Files.readString(logFile).trim();
        var record = mapper.readTree(line);
        assertThat(record.get("traceId").asText()).isEqualTo("unknown");
        assertThat(record.get("agentName").asText()).isEqualTo("unknown");
        assertThat(record.get("callType").asText()).isEqualTo("execute");
    }

    @Test
    void writeRecordShouldAppendToFile() throws Exception {
        var record1 = LlmCallRecord.error("t1", "supervisor", "classify", "u1", 100L, null);
        var record2 = LlmCallRecord.error("t2", "bookkeeper", "execute", "u1", 200L, "error msg");

        advisor.writeRecord(record1);
        advisor.writeRecord(record2);

        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"traceId\":\"t1\"");
        assertThat(lines.get(1)).contains("\"traceId\":\"t2\"");
        assertThat(lines.get(1)).contains("\"error\":\"error msg\"");
    }

    @Test
    void shouldSkipWhenStartTimeNanosIsNull() {
        // after() 在 startTimeNanos 为 null (before() 未被调用或已被消费) 时应跳过
        var response = mockResponse(Map.of(
                LlmAuditAdvisor.ADVISOR_PARAM_TRACE_ID, "trace-001",
                LlmAuditAdvisor.ADVISOR_PARAM_AGENT_NAME, "bookkeeper",
                LlmAuditAdvisor.ADVISOR_PARAM_CALL_TYPE, "execute",
                LlmAuditAdvisor.ADVISOR_PARAM_USER_ID, "user-1"));
        var chain = mock(AdvisorChain.class);

        // 不调用 before()，直接调用 after()
        ChatClientResponse result = advisor.after(response, chain);

        // 应跳过并返回原 response，不写文件
        assertThat(result).isSameAs(response);
        assertThat(Files.exists(logFile)).isFalse();
    }

    @Test
    void writeRecordShouldNotWriteWhenDisabled() {
        var disabledAdvisor = new LlmAuditAdvisor(logFile.toString(), false);
        var record = LlmCallRecord.error("t1", "supervisor", "classify", "u1", 100L, null);
        disabledAdvisor.writeRecord(record);
        assertThat(Files.exists(logFile)).isFalse();
    }

    // --- helpers ---

    private ChatClientResponse mockResponse(Map<String, Object> context) {
        var response = mock(ChatClientResponse.class);
        when(response.context()).thenReturn(context);
        return response;
    }

    private ChatClientRequest mockRequest(List<Message> messages) {
        var prompt = mock(org.springframework.ai.chat.prompt.Prompt.class);
        when(prompt.getInstructions()).thenReturn(messages);

        var request = mock(ChatClientRequest.class);
        when(request.prompt()).thenReturn(prompt);
        return request;
    }

    private Message mockMessage(MessageType type, String text) {
        var msg = mock(Message.class);
        when(msg.getMessageType()).thenReturn(type);
        when(msg.getText()).thenReturn(text);
        return msg;
    }
}
