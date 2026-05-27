package com.example.agent.guardrails;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutputGuardrailAdvisor 单元测试。
 * <p>
 * 测试金额提取和幻觉检测逻辑。
 */
class OutputGuardrailAdvisorTest {

    private final OutputGuardrailAdvisor advisor = new OutputGuardrailAdvisor();

    // ========== 金额提取 ==========

    @Test
    void shouldExtractYuanSymbolAmount() {
        List<BigDecimal> amounts = advisor.extractAmounts("您的余额是 ¥20,273.96");
        assertThat(amounts).containsExactly(new BigDecimal("20273.96"));
    }

    @Test
    void shouldExtractYuanSuffixAmount() {
        List<BigDecimal> amounts = advisor.extractAmounts("本月餐饮支出 1,234.50 元");
        assertThat(amounts).containsExactly(new BigDecimal("1234.50"));
    }

    @Test
    void shouldExtractMultipleAmounts() {
        List<BigDecimal> amounts = advisor.extractAmounts("收入 ¥5,000.00，支出 ¥3,200.00");
        assertThat(amounts).hasSize(2);
        assertThat(amounts).contains(new BigDecimal("5000.00"), new BigDecimal("3200.00"));
    }

    @Test
    void shouldExtractAmountWithoutComma() {
        List<BigDecimal> amounts = advisor.extractAmounts("余额 ¥20273.96");
        assertThat(amounts).containsExactly(new BigDecimal("20273.96"));
    }

    @Test
    void shouldExtractIntegerAmount() {
        List<BigDecimal> amounts = advisor.extractAmounts("共计 ¥500");
        assertThat(amounts).containsExactly(new BigDecimal("500"));
    }

    @Test
    void shouldReturnEmptyForNoAmounts() {
        List<BigDecimal> amounts = advisor.extractAmounts("今天天气不错");
        assertThat(amounts).isEmpty();
    }

    @Test
    void shouldHandleNullText() {
        List<BigDecimal> amounts = advisor.extractAmounts(null);
        assertThat(amounts).isEmpty();
    }

    @Test
    void shouldHandleEmptyText() {
        List<BigDecimal> amounts = advisor.extractAmounts("");
        assertThat(amounts).isEmpty();
    }

    // ========== 幻觉检测 ==========

    @Test
    void shouldDetectNoHallucinationWhenAmountsMatch() {
        List<BigDecimal> replyAmounts = List.of(new BigDecimal("20273.96"));
        List<BigDecimal> toolAmounts = List.of(new BigDecimal("20273.96"));

        assertThat(advisor.hasAmountHallucination(replyAmounts, toolAmounts)).isFalse();
    }

    @Test
    void shouldDetectHallucinationWhenAmountsDiffer() {
        List<BigDecimal> replyAmounts = List.of(new BigDecimal("20000.00"));
        List<BigDecimal> toolAmounts = List.of(new BigDecimal("20273.96"));

        assertThat(advisor.hasAmountHallucination(replyAmounts, toolAmounts)).isTrue();
    }

    @Test
    void shouldTolerateMinorRoundingDifference() {
        List<BigDecimal> replyAmounts = List.of(new BigDecimal("20273.96"));
        List<BigDecimal> toolAmounts = List.of(new BigDecimal("20273.965"));

        // 偏差 0.005，在 0.01 容差内
        assertThat(advisor.hasAmountHallucination(replyAmounts, toolAmounts)).isFalse();
    }

    @Test
    void shouldDetectHallucinationBeyondTolerance() {
        List<BigDecimal> replyAmounts = List.of(new BigDecimal("20274.00"));
        List<BigDecimal> toolAmounts = List.of(new BigDecimal("20273.96"));

        // 偏差 0.04，超过 0.01 容差
        assertThat(advisor.hasAmountHallucination(replyAmounts, toolAmounts)).isTrue();
    }

    @Test
    void shouldNotDetectHallucinationWithEmptyReply() {
        assertThat(advisor.hasAmountHallucination(List.of(), List.of(new BigDecimal("100")))).isFalse();
    }

    @Test
    void shouldNotDetectHallucinationWithEmptyToolData() {
        assertThat(advisor.hasAmountHallucination(List.of(new BigDecimal("100")), List.of())).isFalse();
    }

    @Test
    void shouldMatchPartialAmounts() {
        // 工具返回多个金额，LLM 只提到其中一个
        List<BigDecimal> replyAmounts = List.of(new BigDecimal("5000.00"));
        List<BigDecimal> toolAmounts = List.of(
                new BigDecimal("5000.00"), new BigDecimal("3200.00"));

        assertThat(advisor.hasAmountHallucination(replyAmounts, toolAmounts)).isFalse();
    }
}
