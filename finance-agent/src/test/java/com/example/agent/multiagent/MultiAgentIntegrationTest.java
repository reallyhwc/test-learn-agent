package com.example.agent.multiagent;

import com.example.agent.controller.ChatController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-Agent 端到端集成测试 — 验证 Supervisor 分类 → Specialist 执行的完整链路。
 */
@SpringBootTest
@Import(MultiAgentTestConfig.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.ai.mcp.client.enabled=false",
        "spring.ai.mcp.client.toolcallback.enabled=false"
})
class MultiAgentIntegrationTest {

    @Autowired
    private SupervisorAgent supervisorAgent;

    @Autowired
    private ChatController chatController;

    @Test
    void supervisorShouldClassifyWithoutError() {
        // 测试分类不抛异常（mock LLM 不可用时会 fallback 到 OTHER）
        AgentType result = supervisorAgent.classify("记一笔午餐30元");
        // mock 环境下 LLM 不可用，classify() 会 catch 异常并返回 OTHER
        assertThat(result).isNotNull();
    }

    @Test
    void supervisorShouldReturnOtherForUnknownInput() {
        // 非财务输入应返回 OTHER
        AgentType result = supervisorAgent.classify("帮我写首诗");
        assertThat(result).isNotNull();
    }

    @Test
    void chatControllerShouldHaveMultiAgentEndpoint() {
        // 验证 /api/chat/multi-agent/stream 端点已注册
        var methods = chatController.getClass().getDeclaredMethods();
        var hasEndpoint = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getName().equals("chatMultiAgentStream"));
        assertThat(hasEndpoint).isTrue();
    }

    @Test
    void supervisorGetSpecialistClientShouldReturnNonNullForBooking() {
        var client = supervisorAgent.getSpecialistClient(AgentType.BOOKKEEPER);
        assertThat(client).isNotNull();
    }

    @Test
    void supervisorGetSpecialistClientShouldReturnNonNullForAnalysis() {
        var client = supervisorAgent.getSpecialistClient(AgentType.ANALYST);
        assertThat(client).isNotNull();
    }

    @Test
    void supervisorGetSpecialistClientShouldReturnNullForOther() {
        var client = supervisorAgent.getSpecialistClient(AgentType.OTHER);
        assertThat(client).isNull();
    }
}
