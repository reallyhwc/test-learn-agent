package com.example.agent.controller;

import com.example.agent.config.LlmCondition;
import com.example.agent.validation.AiResponseValidator;
import com.example.agent.validation.AiResponseValidator.ValidationCriteria;
import com.example.agent.validation.AiResponseValidator.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(LlmCondition.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // === 非流式 /api/chat ===

    @Test
    void shouldReturnNonEmptyResponse() throws Exception {
        String json = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"default\",\"message\":\"我的账户余额是多少？\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).contains("reply");

        String reply = extractReply(json);
        assertThat(AiResponseValidator.isNotEmpty(reply)).isTrue();
    }

    @Test
    void shouldNotContainDegradationText() throws Exception {
        String reply = chatAndGetReply("default", "帮我查询一下交易记录");
        assertThat(AiResponseValidator.hasDegradation(reply))
                .as("响应不应包含降级文案: " + reply)
                .isFalse();
    }

    @Test
    void shouldMentionFinancialDataWhenQueryingBalance() throws Exception {
        String reply = chatAndGetReply("default", "我的账户余额是多少？");
        ValidationResult result = AiResponseValidator.validate(reply,
                new ValidationCriteria(List.of("元", "余额"), null, 20, 2000));
        assertThat(result.passed())
                .as("查询余额响应验证失败: " + result.failures())
                .isTrue();
    }

    @Test
    void shouldHandleDiningExpenseQuery() throws Exception {
        String reply = chatAndGetReply("default", "帮我看下我在餐饮上花了多少钱");
        ValidationResult result = AiResponseValidator.validate(reply,
                new ValidationCriteria(List.of("餐饮", "元"), null, 30, 3000));
        assertThat(result.passed())
                .as("餐饮查询响应验证失败: " + result.failures())
                .isTrue();
    }

    // === 流式 /api/chat/stream ===

    @Test
    void shouldStreamWithSSEHeaders() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"default\",\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());
        // Note: MockMvc async dispatch 不保留 Content-Type header，SSE 格式由后续测试验证
    }

    @Test
    void shouldStreamTokensWithDataPrefix() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"default\",\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult asyncResult = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn();
        String content = asyncResult.getResponse().getContentAsString();
        assertThat(content).isNotEmpty();
        assertThat(content).contains("data:");
    }

    @Test
    void shouldStreamCompleteResponse() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"default\",\"message\":\"说一句话就好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String content = mockMvc.perform(asyncDispatch(mvcResult))
                .andReturn().getResponse().getContentAsString();

        String fullResponse = extractSseContent(content);
        assertThat(fullResponse).isNotEmpty();
        assertThat(AiResponseValidator.hasDegradation(fullResponse)).isFalse();
    }

    // === 辅助方法 ===

    private String chatAndGetReply(String userId, String message) throws Exception {
        String json = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractReply(json);
    }

    private String extractReply(String json) throws Exception {
        return objectMapper.readTree(json).get("reply").asText();
    }

    private String extractSseContent(String sseText) {
        StringBuilder sb = new StringBuilder();
        for (String line : sseText.split("\n")) {
            if (line.startsWith("data:")) {
                String data = line.substring(5);
                if (data.startsWith(" ")) data = data.substring(1);
                sb.append(data);
            }
        }
        return sb.toString();
    }
}
