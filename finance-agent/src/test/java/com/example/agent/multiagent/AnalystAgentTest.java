package com.example.agent.multiagent;

import com.example.agent.prompt.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystAgentTest {

    @TempDir
    Path tempDir;

    private PromptLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        Path v1 = tempDir.resolve("v1/analyst");
        Files.createDirectories(v1);
        Files.writeString(v1.resolve("system.md"), "你是财务分析师\nlist_transactions\nsummarize_transactions");
        Files.writeString(v1.resolve("tool-rules.md"), "禁止大约/大概/左右/约");
        Files.writeString(v1.resolve("response-format.md"), "先给数字再总结");

        loader = new PromptLoader(tempDir.toString(), "v1");
    }

    @Test
    void shouldHaveSummarizeTransactionsInPrompt() {
        String prompt = loader.assemble("analyst", java.util.Map.of());
        assertThat(prompt)
                .contains("summarize_transactions")
                .contains("list_transactions");
    }

    @Test
    void shouldNotIncludeAddTransactionInPrompt() {
        String prompt = loader.assemble("analyst", java.util.Map.of());
        assertThat(prompt)
                .doesNotContain("add_transaction")
                .doesNotContain("query_balance");
    }

    @Test
    void shouldHaveNoFuzzyRule() {
        String prompt = loader.assemble("analyst", java.util.Map.of());
        assertThat(prompt)
                .contains("大约")
                .contains("大概");
    }
}
