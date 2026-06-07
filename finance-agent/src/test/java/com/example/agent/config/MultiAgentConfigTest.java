package com.example.agent.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentConfigTest {

    @Test
    void bookkeeperToolListShouldHaveThreeTools() {
        assertThat(MultiAgentConfig.BOOKKEEPER_TOOLS)
                .containsExactly("add_transaction", "list_accounts", "query_balance");
    }

    @Test
    void analystToolListShouldHaveTwoTools() {
        assertThat(MultiAgentConfig.ANALYST_TOOLS)
                .containsExactly("list_transactions", "summarize_transactions");
    }

    @Test
    void bookkeeperAndAnalystToolsShouldNotOverlap() {
        var overlap = MultiAgentConfig.BOOKKEEPER_TOOLS.stream()
                .filter(MultiAgentConfig.ANALYST_TOOLS::contains)
                .toList();
        assertThat(overlap).isEmpty();
    }
}
