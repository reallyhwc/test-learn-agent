package com.example.agent.controller;

import com.example.agent.validation.AiResponseValidator;
import com.example.agent.validation.AiResponseValidator.ValidationCriteria;
import com.example.agent.validation.AiResponseValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentEndpointTest extends ChatEndpointTestBase {

    private static final String MULTI_AGENT_STREAM = "/api/chat/multi-agent/stream";

    @Test
    void shouldStreamFromMultiAgentEndpoint() throws Exception {
        String raw = streamAndGetRaw(MULTI_AGENT_STREAM, "default", "你好");
        assertThat(raw).isNotEmpty();
        assertThat(raw).contains("data:");
    }

    @Test
    void shouldHandleNonFinancialInput() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "今天天气怎么样");
        assertThat(AiResponseValidator.isNotEmpty(content)).isTrue();
    }

    @Test
    void shouldWorkWithDifferentUsers() throws Exception {
        String raw = streamAndGetRaw(MULTI_AGENT_STREAM, "e2e-test-user", "我有几个账户");
        assertThat(raw).isNotEmpty();
        assertThat(raw).contains("data:");
    }

    @Test
    void shouldRouteAnalysisToAnalyst() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "看下我在餐饮上花了多少钱");
        ValidationResult result = AiResponseValidator.validate(content,
                new ValidationCriteria(List.of("餐饮"), null, 30, 3000));
        assertThat(result.passed())
                .as("分析师路由验证失败: " + result.failures())
                .isTrue();
    }

    @Test
    void shouldRouteBookkeepingToBookkeeper() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "我有哪些账户");
        assertThat(content).isNotEmpty();
        assertThat(AiResponseValidator.hasDegradation(content)).isFalse();
        assertThat(AiResponseValidator.containsKeywords(content, List.of("账户", "储蓄", "支付宝", "微信", "现金")))
                .as("记账员路由验证失败，响应中未包含账户信息: " + content)
                .isTrue();
    }

    @Test
    void shouldReturnAgentIdentityEvent() throws Exception {
        String raw = streamAndGetRaw(MULTI_AGENT_STREAM, "default", "查一下最近的支出");
        assertThat(raw).contains("event:thinking");
        assertThat(AiResponseValidator.containsKeywords(raw, List.of("分析师", "记账员")))
                .as("SSE 流中未包含 Agent 身份标识: " + raw.substring(0, Math.min(200, raw.length())))
                .isTrue();
    }

    @Test
    void shouldReturnCompleteAnalysisResponse() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "帮我汇总一下上个月的收支");
        assertThat(content.length()).isGreaterThan(30);
        assertThat(AiResponseValidator.containsKeywords(content, List.of("收入", "支出", "元", "¥")))
                .as("汇总响应缺少财务内容: " + content)
                .isTrue();
    }
}
