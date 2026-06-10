package com.example.agent.controller;

import com.example.agent.validation.AiResponseValidator;
import org.junit.jupiter.api.Test;

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
        AiResponseValidator.ValidationResult result = AiResponseValidator.validate(content,
                new AiResponseValidator.ValidationCriteria(
                        java.util.List.of("餐饮"), null, 30, 3000));
        assertThat(result.passed())
                .as("分析师路由验证失败: " + result.failures())
                .isTrue();
    }

    @Test
    void shouldRouteBookkeepingToBookkeeper() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "我有哪些账户");
        assertThat(content).isNotEmpty();
        assertThat(AiResponseValidator.hasDegradation(content)).isFalse();
        boolean hasAccountInfo = content.contains("账户") || content.contains("储蓄") || content.contains("支付宝")
                || content.contains("微信") || content.contains("现金");
        assertThat(hasAccountInfo)
                .as("记账员路由验证失败，响应中未包含账户信息: " + content)
                .isTrue();
    }

    @Test
    void shouldReturnAgentIdentityEvent() throws Exception {
        String raw = streamAndGetRaw(MULTI_AGENT_STREAM, "default", "查一下最近的支出");
        assertThat(raw).contains("event:thinking");
        boolean hasAgentIdentity = raw.contains("分析师") || raw.contains("记账员");
        assertThat(hasAgentIdentity)
                .as("SSE 流中未包含 Agent 身份标识: " + raw.substring(0, Math.min(200, raw.length())))
                .isTrue();
    }

    @Test
    void shouldReturnCompleteAnalysisResponse() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "帮我汇总一下上个月的收支");
        assertThat(content.length()).isGreaterThan(30);
        boolean hasFinancialContent = content.contains("收入") || content.contains("支出")
                || content.contains("元") || content.contains("¥");
        assertThat(hasFinancialContent)
                .as("汇总响应缺少财务内容: " + content)
                .isTrue();
    }
}
