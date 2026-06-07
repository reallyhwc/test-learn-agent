package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SupervisorAgentTest {

    @Test
    void shouldContainBookingAndAnalysisInPrompt() {
        assertThat(SupervisorAgent.CLASSIFY_PROMPT)
                .contains("booking")
                .contains("analysis")
                .contains("other");
    }

    @Test
    void shouldPromptReturnOnlyCategoryName() {
        String prompt = SupervisorAgent.CLASSIFY_PROMPT;
        assertThat(prompt).contains("只返回分类名称");
    }

    @Test
    void shouldHaveMaxRounds() {
        assertThat(SupervisorAgent.MAX_ROUNDS).isEqualTo(2);
    }
}
