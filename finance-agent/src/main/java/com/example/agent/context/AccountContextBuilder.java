package com.example.agent.context;

import com.example.agent.resilience.SimpleCircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把当前用户的账户信息摘要后注入 system prompt，
 * 让 LLM 不需要 list_accounts/query_balance 就能回答简单查询。
 *
 * 摘要策略（防 token 爆炸）：
 * - 0 个账户：明确告知
 * - 1-5 个：完整列出
 * - >5 个：按余额降序列前 5 + "另有 N-5 个账户合计 ¥X"
 *
 * 失败时返回空字符串，让 prompt 退化到老路（让 LLM 自己调工具），不影响功能。
 */
@Slf4j
@Component
public class AccountContextBuilder {

    static final int FULL_LIST_THRESHOLD = 5;
    private static final ParameterizedTypeReference<List<Map<String, Object>>> ACCOUNT_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final long CACHE_TTL_SECONDS = 30;
    private final ConcurrentHashMap<String, CachedSummary> summaryCache = new ConcurrentHashMap<>();

    private record CachedSummary(String summary, Instant expireAt) {
        boolean isExpired() { return Instant.now().isAfter(expireAt); }
    }

    private final RestClient backendRestClient;
    private final SimpleCircuitBreaker circuitBreaker = new SimpleCircuitBreaker("backend-accounts", 3, 30000);

    public AccountContextBuilder(RestClient backendRestClient) {
        this.backendRestClient = backendRestClient;
    }

    public String buildSummary(String userId) {
        // 检查缓存
        CachedSummary cached = summaryCache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.summary();
        }
        if (!circuitBreaker.isCallPermitted()) {
            log.warn("后端熔断中，跳过账户上下文拉取 userId={}", userId);
            return "";
        }
        try {
            List<Map<String, Object>> accounts = backendRestClient.get()
                    .uri("/api/accounts?userId={u}", userId)
                    .retrieve()
                    .body(ACCOUNT_LIST_TYPE);
            String summary = formatSummary(accounts);
            summaryCache.put(userId, new CachedSummary(summary, Instant.now().plusSeconds(CACHE_TTL_SECONDS)));
            circuitBreaker.recordSuccess();
            return summary;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("拉取账户上下文失败 userId={}: {}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 给定账户列表渲染摘要文本。包级私有便于单测。
     */
    static String formatSummary(List<Map<String, Object>> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return "**用户上下文**: 当前用户暂无账户。\n";
        }
        List<Map<String, Object>> sorted = new ArrayList<>(accounts);
        sorted.sort(Comparator.comparing(AccountContextBuilder::balanceOf).reversed());

        BigDecimal total = sorted.stream()
                .map(AccountContextBuilder::balanceOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder();
        sb.append("**用户上下文（实时数据，简单查询直接读取，不用调用工具）**\n");
        sb.append("- 账户数: ").append(sorted.size()).append("\n");
        sb.append("- 总余额: ").append(formatMoney(total)).append("\n");

        int listed = Math.min(sorted.size(), FULL_LIST_THRESHOLD);
        sb.append(sorted.size() <= FULL_LIST_THRESHOLD ? "- 账户列表:\n" : "- 主要账户（按余额前 5）:\n");
        for (int i = 0; i < listed; i++) {
            Map<String, Object> a = sorted.get(i);
            sb.append("  - ID=").append(a.get("id"))
                    .append(" ").append(a.getOrDefault("name", ""))
                    .append("（").append(a.getOrDefault("type", "")).append("）")
                    .append(" 余额 ").append(formatMoney(balanceOf(a)))
                    .append("\n");
        }
        int rest = sorted.size() - listed;
        if (rest > 0) {
            BigDecimal restSum = sorted.subList(listed, sorted.size()).stream()
                    .map(AccountContextBuilder::balanceOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append("  - …另有 ").append(rest)
                    .append(" 个账户余额合计 ").append(formatMoney(restSum))
                    .append("，详情请调用 list_accounts\n");
        }
        return sb.toString();
    }

    private static BigDecimal balanceOf(Map<String, Object> account) {
        Object v = account.get("balance");
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String formatMoney(BigDecimal amount) {
        return "¥" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
