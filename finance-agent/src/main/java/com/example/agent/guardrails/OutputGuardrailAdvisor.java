package com.example.agent.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【第三层防护 — 输出幻觉检测 Advisor】
 *
 * <h3>在 Advisor 链中的位置</h3>
 * <pre>
 * after 阶段执行顺序（order 降序）:
 *   ① OutputGuardrailAdvisor (LOWEST-100) ← 你在这里，after 最先执行
 *   ② MessageChatMemoryAdvisor (HIGHEST+1000)
 *   ③ ToolCallGuardrailAdvisor (HIGHEST+300)
 *   ④ InputGuardrailAdvisor (HIGHEST+100)
 * </pre>
 *
 * <h3>工作原理</h3>
 * <p>{@code before()} 空操作。{@code after()} 在 LLM 回复返回给用户之前：</p>
 * <ol>
 *   <li>用正则从回复文本中提取金额（¥xx,xxx.xx 和 xxx元 格式）</li>
 *   <li>异常大金额告警：超过 100 万时记录 WARN</li>
 *   <li>幻觉检测：与 context 中的工具返回金额比对，容差 0.01</li>
 * </ol>
 *
 * <p>流式场景中 {@code after()} 仅在 {@code AdvisorUtils.onFinishReason()} 为 true 时触发
 * （即流结束时才做一次完整检测）。</p>
 *
 * <p>当前阶段仅日志告警，不拦截（避免影响正常流式输出）。</p>
 *
 * @see InputGuardrailAdvisor — 第一层：Prompt Injection 检测
 * @see ToolCallGuardrailAdvisor — 第二层：工具调用审计
 */
@Slf4j
@Component
public class OutputGuardrailAdvisor implements BaseAdvisor {

    /**
     * 匹配中文金额格式：¥12,345.67 或 12345.67 元 或 ¥12345
     */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "¥\\s?([\\d,]+(?:\\.\\d{1,2})?)|([\\d,]+(?:\\.\\d{1,2})?)\\s*元"
    );

    /** 单笔金额告警阈值：超过此值记录 WARN */
    private static final BigDecimal LARGE_AMOUNT_THRESHOLD = new BigDecimal("1000000");

    @Override
    public int getOrder() {
        // 最后执行，在所有其他 Advisor 之后
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 输出防护不需要前处理
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return response;
        }

        String replyText = chatResponse.getResult().getOutput().getText();
        if (replyText == null || replyText.isBlank()) {
            return response;
        }

        // 提取回复中的金额
        List<BigDecimal> replyAmounts = extractAmounts(replyText);
        if (replyAmounts.isEmpty()) {
            return response;
        }

        log.debug("OutputGuardrail: LLM 回复中检测到 {} 个金额值: {}", replyAmounts.size(), replyAmounts);

        // 异常大金额告警
        for (BigDecimal amount : replyAmounts) {
            if (amount.compareTo(LARGE_AMOUNT_THRESHOLD) > 0) {
                log.warn("OutputGuardrail: LLM 回复中出现异常大金额 {}，请人工核实", amount);
            }
        }

        // 从 context 中获取工具返回的金额数据（由 ToolCallGuardrailAdvisor 或外部写入）
        Object toolAmountsObj = response.context().get("guardrail.toolAmounts");
        if (toolAmountsObj instanceof List<?> rawList) {
            List<BigDecimal> toolAmounts = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof BigDecimal bd) {
                    toolAmounts.add(bd);
                }
            }
            if (!toolAmounts.isEmpty()) {
                hasAmountHallucination(replyAmounts, toolAmounts);
            }
        }

        return response;
    }

    /**
     * 从文本中提取所有金额值。
     *
     * @param text 待提取的文本
     * @return 提取到的金额列表
     */
    public List<BigDecimal> extractAmounts(String text) {
        List<BigDecimal> amounts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return amounts;
        }

        Matcher matcher = AMOUNT_PATTERN.matcher(text);
        while (matcher.find()) {
            String amountStr = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (amountStr != null) {
                try {
                    String cleaned = amountStr.replace(",", "");
                    BigDecimal amount = new BigDecimal(cleaned);
                    amounts.add(amount);
                } catch (NumberFormatException e) {
                    // 忽略无法解析的金额
                }
            }
        }
        return amounts;
    }

    /**
     * 检测金额是否存在幻觉（与工具返回的原始数据不一致）。
     *
     * @param replyAmounts LLM 回复中的金额
     * @param toolAmounts  工具返回的原始金额
     * @return true 表示存在幻觉
     */
    public boolean hasAmountHallucination(List<BigDecimal> replyAmounts, List<BigDecimal> toolAmounts) {
        if (replyAmounts.isEmpty() || toolAmounts.isEmpty()) {
            return false;
        }

        for (BigDecimal replyAmount : replyAmounts) {
            boolean matchFound = toolAmounts.stream()
                    .anyMatch(toolAmount ->
                            toolAmount.subtract(replyAmount).abs()
                                    .compareTo(new BigDecimal("0.01")) <= 0);
            if (!matchFound) {
                log.warn("OutputGuardrail: 幻觉检测! LLM 回复金额 {} 与工具数据不匹配, 工具数据: {}",
                        replyAmount, toolAmounts);
                return true;
            }
        }
        return false;
    }
}
