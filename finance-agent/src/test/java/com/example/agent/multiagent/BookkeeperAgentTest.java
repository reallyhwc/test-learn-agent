package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BookkeeperAgentTest {

    @Test
    void shouldHaveAddTransactionInPrompt() {
        assertThat(BookkeeperAgent.SYSTEM_PROMPT)
                .contains("add_transaction")
                .contains("list_accounts")
                .contains("query_balance");
    }

    @Test
    void shouldNotIncludeAnalysisToolsInPrompt() {
        assertThat(BookkeeperAgent.SYSTEM_PROMPT)
                .doesNotContain("summarize_transactions");
    }

    @Test
    void shouldHaveCategoryEnumeration() {
        assertThat(BookkeeperAgent.SYSTEM_PROMPT)
                .contains("餐饮")
                .contains("交通")
                .contains("购物");
    }
}
