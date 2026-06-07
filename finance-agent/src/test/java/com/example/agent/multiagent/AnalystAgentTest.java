package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalystAgentTest {

    @Test
    void shouldHaveSummarizeTransactionsInPrompt() {
        assertThat(AnalystAgent.SYSTEM_PROMPT)
                .contains("summarize_transactions")
                .contains("list_transactions");
    }

    @Test
    void shouldNotIncludeAddTransactionInPrompt() {
        // Analyst 不应包含写操作工具
        assertThat(AnalystAgent.SYSTEM_PROMPT)
                .doesNotContain("add_transaction")
                .doesNotContain("query_balance");
    }

    @Test
    void shouldHaveNoFuzzyRule() {
        // Analyst 必须包含禁止模糊金额的规则
        assertThat(AnalystAgent.SYSTEM_PROMPT)
                .contains("大约")
                .contains("大概");  // 这些词在规则中作为"禁止"出现
    }
}
