package com.example.agent.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptLoaderTest {

    @TempDir
    Path tempDir;

    private PromptLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        // 创建测试用的 prompts 目录结构
        Path v1 = tempDir.resolve("v1/bookkeeper");
        Files.createDirectories(v1);
        Files.writeString(v1.resolve("system.md"), "你是记账专员");
        Files.writeString(v1.resolve("tool-rules.md"), "规则: {{categorySystem}}");
        Files.writeString(v1.resolve("response-format.md"), "中文简洁");

        Path shared = tempDir.resolve("shared");
        Files.createDirectories(shared);
        Files.writeString(shared.resolve("category-system.md"), "餐饮/交通/购物");
        Files.writeString(shared.resolve("safety-rules.md"), "拒绝无关请求");

        loader = new PromptLoader(tempDir.toString(), "v1");
    }

    @Test
    void shouldLoadSingleFile() {
        String content = loader.load("bookkeeper", "system");
        assertThat(content).isEqualTo("你是记账专员");
    }

    @Test
    void shouldLoadSharedFile() {
        String content = loader.loadShared("category-system");
        assertThat(content).isEqualTo("餐饮/交通/购物");
    }

    @Test
    void shouldReturnEmptyStringForMissingFile() {
        String content = loader.load("bookkeeper", "nonexistent");
        assertThat(content).isEqualTo("");
    }

    @Test
    void shouldAssembleAndReplaceVariables() {
        String result = loader.assemble("bookkeeper", Map.of("categorySystem", "餐饮/交通/购物"));
        assertThat(result)
                .contains("你是记账专员")
                .contains("规则: 餐饮/交通/购物")
                .contains("中文简洁");
    }

    @Test
    void shouldCacheLoadedFiles() {
        loader.load("bookkeeper", "system");
        String result = loader.load("bookkeeper", "system");
        assertThat(result).isEqualTo("你是记账专员");
    }

    @Test
    void shouldAssembleSupervisorPrompt() throws Exception {
        Path supervisorDir = tempDir.resolve("v1/supervisor");
        Files.createDirectories(supervisorDir);
        Files.writeString(supervisorDir.resolve("classify.md"), "你是一个意图分类器");

        String result = loader.assemble("supervisor", Map.of());
        assertThat(result).isEqualTo("你是一个意图分类器");
    }

    @Test
    void shouldAssembleAnalystPrompt() throws Exception {
        Path analystDir = tempDir.resolve("v1/analyst");
        Files.createDirectories(analystDir);
        Files.writeString(analystDir.resolve("system.md"), "你是财务分析师");
        Files.writeString(analystDir.resolve("tool-rules.md"), "禁止模糊词");
        Files.writeString(analystDir.resolve("response-format.md"), "先给数字");

        String result = loader.assemble("analyst", Map.of());
        assertThat(result)
                .contains("你是财务分析师")
                .contains("禁止模糊词")
                .contains("先给数字");
    }

    @Test
    void shouldAssembleSingleAgentWithAllVariables() throws Exception {
        Path singleDir = tempDir.resolve("v1/single-agent");
        Files.createDirectories(singleDir);
        Files.writeString(singleDir.resolve("system.md"), "你是小财 userId={{userId}}\n{{accountSummary}}\n{{safetyRules}}");
        Files.writeString(singleDir.resolve("tool-rules.md"), "工具参数\n{{categorySystem}}");
        Files.writeString(singleDir.resolve("response-format.md"), "日期:{{currentDate}}\n{{contextInfo}}");

        String result = loader.assemble("single-agent", Map.of(
                "userId", "user-1",
                "accountSummary", "总余额 ¥1000",
                "safetyRules", "拒绝无关",
                "categorySystem", "餐饮/交通",
                "currentDate", "2026-06-07",
                "contextInfo", "记忆: 3条"
        ));

        assertThat(result).contains("user-1");
        assertThat(result).contains("总余额 ¥1000");
        assertThat(result).contains("拒绝无关");
        assertThat(result).contains("餐饮/交通");
        assertThat(result).contains("2026-06-07");
        assertThat(result).contains("记忆: 3条");
    }
}
