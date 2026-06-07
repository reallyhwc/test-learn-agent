package com.example.agent.multiagent;

import com.example.agent.prompt.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorAgentTest {

    @TempDir
    Path tempDir;

    private PromptLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        Path v1 = tempDir.resolve("v1/supervisor");
        Files.createDirectories(v1);
        Files.writeString(v1.resolve("classify.md"), """
                你是一个意图分类器。分析用户消息，返回以下分类之一：
                - booking: 记账、查余额、查账户、添加交易记录
                - analysis: 统计汇总、趋势分析、分类占比、对比支出
                - other: 与个人财务无关的请求（写诗、闲聊、写代码等）
                只返回分类名称，不要解释。""");

        loader = new PromptLoader(tempDir.toString(), "v1");
    }

    @Test
    void shouldContainBookingAndAnalysisInPrompt() {
        String prompt = loader.assemble("supervisor", java.util.Map.of());
        assertThat(prompt)
                .contains("booking")
                .contains("analysis")
                .contains("other");
    }

    @Test
    void shouldPromptReturnOnlyCategoryName() {
        String prompt = loader.assemble("supervisor", java.util.Map.of());
        assertThat(prompt).contains("只返回分类名称");
    }

    @Test
    void shouldHaveMaxRounds() {
        assertThat(SupervisorAgent.MAX_ROUNDS).isEqualTo(2);
    }
}
