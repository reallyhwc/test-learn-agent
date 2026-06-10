package com.example.agent.controller;

import com.example.agent.validation.AiResponseValidator;
import com.example.agent.validation.AiResponseValidator.ValidationCriteria;
import com.example.agent.validation.AiResponseValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

class SingleAgentEndpointTest extends ChatEndpointTestBase {

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

    @Test
    void shouldStreamWithSSEHeaders() throws Exception {
        streamAndGetRaw("/api/chat/stream", "default", "你好");
    }

    @Test
    void shouldStreamTokensWithDataPrefix() throws Exception {
        String raw = streamAndGetRaw("/api/chat/stream", "default", "你好");
        assertThat(raw).isNotEmpty();
        assertThat(raw).contains("data:");
    }

    @Test
    void shouldStreamCompleteResponse() throws Exception {
        String content = streamAndGetContent("/api/chat/stream", "default", "说一句话就好");
        assertThat(content).isNotEmpty();
        assertThat(AiResponseValidator.hasDegradation(content)).isFalse();
    }
}
