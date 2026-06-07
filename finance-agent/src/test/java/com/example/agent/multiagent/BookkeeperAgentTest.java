package com.example.agent.multiagent;

import com.example.agent.prompt.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BookkeeperAgentTest {

    @TempDir
    Path tempDir;

    private PromptLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        Path v1 = tempDir.resolve("v1/bookkeeper");
        Files.createDirectories(v1);
        Files.writeString(v1.resolve("system.md"), "你是记账专员\nadd_transaction\nlist_accounts\nquery_balance");
        Files.writeString(v1.resolve("tool-rules.md"), "规则: {{categorySystem}}");
        Files.writeString(v1.resolve("response-format.md"), "中文简洁");

        Path shared = tempDir.resolve("shared");
        Files.createDirectories(shared);
        Files.writeString(shared.resolve("category-system.md"), "餐饮\n交通\n购物");

        loader = new PromptLoader(tempDir.toString(), "v1");
    }

    @Test
    void shouldHaveAddTransactionInPrompt() {
        String prompt = loader.assemble("bookkeeper", java.util.Map.of("categorySystem", "餐饮\n交通\n购物"));
        assertThat(prompt)
                .contains("add_transaction")
                .contains("list_accounts")
                .contains("query_balance");
    }

    @Test
    void shouldNotIncludeAnalysisToolsInPrompt() {
        String prompt = loader.assemble("bookkeeper", java.util.Map.of("categorySystem", ""));
        assertThat(prompt).doesNotContain("summarize_transactions");
    }

    @Test
    void shouldHaveCategoryEnumeration() {
        String prompt = loader.assemble("bookkeeper", java.util.Map.of("categorySystem", "餐饮\n交通\n购物"));
        assertThat(prompt).contains("餐饮").contains("交通").contains("购物");
    }
}
