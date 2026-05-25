package com.example.mcp.tool;

import com.example.mcp.dto.AccountResponse;
import com.example.mcp.dto.TransactionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FinanceTools {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;

    public FinanceTools(RestClient restClient, ObjectMapper objectMapper, MeterRegistry registry) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @McpTool(name = "query_balance",
            description = "按 accountId 查询单个账户余额。注意：list_accounts 返回的对象已含 balance 字段，"
                    + "查询余额时优先用 list_accounts 一次拿全，不要重复调用此工具。")
    public Object queryBalance(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "账户ID") Long accountId) {
        long start = System.nanoTime();
        try {
            userId = validateUserId(userId);
            log.info("queryBalance called with userId={}, accountId={}", userId, accountId);
            BigDecimal result = restClient.get()
                    .uri("/api/accounts/{id}/balance", accountId)
                    .retrieve()
                    .body(BigDecimal.class);
            recordSuccess("query_balance", start);
            return result;
        } catch (Exception e) {
            recordError("query_balance", e);
            log.error("查询余额失败: userId={}, accountId={}", userId, accountId, e);
            return "查询余额失败，请检查账户ID是否正确";
        }
    }

    @McpTool(name = "list_transactions",
            description = "查询交易记录明细列表。仅 userId 必填，其余过滤条件通过 filters JSON 传入。"
                    + "filters 示例: {\"category\":\"餐饮\",\"subCategory\":\"外卖\",\"type\":\"EXPENSE\"}"
                    + " filters 可用字段: startDate、endDate(yyyy-MM-dd)、category(一级分类)、subCategory(二级分类)、type(INCOME/EXPENSE)、accountId")
    public Object listTransactions(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "过滤条件JSON，如{\"category\":\"理财\",\"type\":\"INCOME\"}，无过滤传{}") String filters) {
        long start = System.nanoTime();

        try {
            userId = validateUserId(userId);
            log.info("listTransactions called with userId={}, filters={}", userId, filters);
            Map<String, Object> filterMap = parseFilters(filters);
            String startDate = (String) filterMap.get("startDate");
            String endDate = (String) filterMap.get("endDate");
            String category = (String) filterMap.get("category");
            String subCategory = (String) filterMap.get("subCategory");
            String type = (String) filterMap.get("type");
            Object accountIdObj = filterMap.get("accountId");

            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/api/transactions")
                    .queryParam("userId", userId)
                    .queryParam("pageSize", 1000);
            if (startDate != null) uriBuilder.queryParam("startDate", startDate);
            if (endDate != null) uriBuilder.queryParam("endDate", endDate);
            if (category != null) uriBuilder.queryParam("category", category);
            if (subCategory != null) uriBuilder.queryParam("subCategory", subCategory);
            if (type != null) uriBuilder.queryParam("type", type);
            if (accountIdObj != null) uriBuilder.queryParam("accountId", accountIdObj);

            java.net.URI uri = uriBuilder.build().toUri();
            log.info("listTransactions URI: {}", uri);

            Map<String, Object> pageResult = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

            if (pageResult == null) {
                recordSuccess("list_transactions", start);
                return List.of();
            }
            log.info("listTransactions response: total={}", pageResult.get("total"));

            List<Map<String, Object>> rawItems = (List<Map<String, Object>>) pageResult.get("items");
            if (rawItems == null || rawItems.isEmpty()) {
                recordSuccess("list_transactions", start);
                return List.of();
            }

            List<TransactionResponse> result = new ArrayList<>();
            for (Map<String, Object> item : rawItems) {
                result.add(objectMapper.convertValue(item, TransactionResponse.class));
            }
            recordSuccess("list_transactions", start);
            return result;
        } catch (Exception e) {
            recordError("list_transactions", e);
            log.error("查询交易记录失败: userId={}", userId, e);
            return "查询交易记录失败，请稍后重试";
        }
    }

    @McpTool(name = "summarize_transactions",
            description = "按分类汇总交易金额统计。返回每个分类的总金额和笔数及合计。"
                    + "适用于'赚了多少''花了多少''收支汇总'类问题。"
                    + "仅 userId 必填，filters 可选。"
                    + "filters 可用字段: type(INCOME/EXPENSE)、startDate、endDate(yyyy-MM-dd)、"
                    + "groupBy('category'按一级分类汇总，'subCategory'按二级分类汇总，默认category)")
    @SuppressWarnings("unchecked")
    public Object summarizeTransactions(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "过滤条件JSON，如{\"type\":\"INCOME\",\"groupBy\":\"subCategory\"}，无过滤传{}") String filters) {
        long start = System.nanoTime();

        try {
            userId = validateUserId(userId);
            log.info("summarizeTransactions called with userId={}, filters={}", userId, filters);
            Map<String, Object> filterMap = parseFilters(filters);
            String type = (String) filterMap.get("type");
            String startDate = (String) filterMap.get("startDate");
            String endDate = (String) filterMap.get("endDate");
            String groupBy = (String) filterMap.get("groupBy");

            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/api/transactions/summary")
                    .queryParam("userId", userId);
            if (type != null) uriBuilder.queryParam("type", type);
            if (startDate != null) uriBuilder.queryParam("startDate", startDate);
            if (endDate != null) uriBuilder.queryParam("endDate", endDate);
            if (groupBy != null) uriBuilder.queryParam("groupBy", groupBy);

            java.net.URI uri = uriBuilder.build().toUri();
            log.info("summarizeTransactions URI: {}", uri);

            List<Map<String, Object>> result = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(List.class);

            recordSuccess("summarize_transactions", start);
            return result != null ? result : List.of();
        } catch (Exception e) {
            recordError("summarize_transactions", e);
            log.error("汇总交易统计失败: userId={}", userId, e);
            return "汇总交易统计失败，请稍后重试";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFilters(String filters) {
        if (filters == null || filters.isBlank() || "{}".equals(filters.trim())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(filters, Map.class);
        } catch (Exception e) {
            log.warn("解析 filters JSON 失败: {}", filters, e);
            return Map.of();
        }
    }

    @McpTool(name = "add_transaction",
            description = "添加一笔交易记录。category 和 subCategory 必须同时提供。"
                    + "支出一级分类: 餐饮(外卖/食堂/聚餐/日常餐饮)、交通(公交/打车/加油/日常出行)、"
                    + "购物(日用品/服饰/数码)、房租(房租/物业/水电)、娱乐(电影/游戏/旅行)、"
                    + "医疗(门诊/药品/体检)、其他(其他支出)。"
                    + "收入一级分类: 工资(基本工资/奖金/补贴)、兼职(兼职收入)、理财(利息/分红/基金)。")
    public Object addTransaction(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "账户ID") Long accountId,
            @McpToolParam(description = "交易类型: INCOME 或 EXPENSE") String type,
            @McpToolParam(description = "金额") BigDecimal amount,
            @McpToolParam(description = "一级分类，如餐饮、工资") String category,
            @McpToolParam(description = "二级分类，如外卖、基本工资") String subCategory,
            @McpToolParam(description = "备注") String note) {
        try {
            userId = validateUserId(userId);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        // 参数校验 — 返回友好错误信息，不抛异常
        if (accountId == null) {
            return "添加交易失败，账户ID不能为空";
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return "添加交易失败，金额必须大于0";
        }
        if (type == null || (!type.equalsIgnoreCase("INCOME") && !type.equalsIgnoreCase("EXPENSE"))) {
            return "添加交易失败，交易类型必须是 INCOME 或 EXPENSE";
        }
        if (category == null || category.isBlank()) {
            return "添加交易失败，一级分类不能为空";
        }
        if (subCategory == null || subCategory.isBlank()) {
            return "添加交易失败，二级分类不能为空";
        }
        log.info("addTransaction called with userId={}, accountId={}, type={}, amount={}, category={}/{}",
                userId, accountId, type, amount, category, subCategory);
        long start = System.nanoTime();

        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("userId", userId);
            body.put("accountId", accountId);
            body.put("type", type);
            body.put("amount", amount);
            body.put("category", category);
            body.put("subCategory", subCategory);
            body.put("note", note != null ? note : "");
            body.put("date", java.time.LocalDate.now().toString());

            Map<String, Object> result = restClient.post()
                    .uri("/api/transactions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            recordSuccess("add_transaction", start);
            return result;
        } catch (Exception e) {
            recordError("add_transaction", e);
            log.error("添加交易失败: userId={}, accountId={}", userId, accountId, e);
            return "添加交易失败，请检查参数是否完整";
        }
    }

    @McpTool(name = "list_accounts",
            description = "查询用户的全部账户列表。返回字段：id、name、type、balance（实时余额）、userId。"
                    + "balance 已包含在返回中，无需再调用 query_balance。")
    public Object listAccounts(
            @McpToolParam(description = "用户ID") String userId) {
        long start = System.nanoTime();

        try {
            userId = validateUserId(userId);
            log.info("listAccounts called with userId={}", userId);
            // 使用 UriComponentsBuilder 防止 URL 注入（统一风格）
            java.net.URI uri = UriComponentsBuilder.fromPath("/api/accounts")
                    .queryParam("userId", userId)
                    .build().toUri();
            AccountResponse[] body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(AccountResponse[].class);
            List<AccountResponse> result = (body != null) ? List.of(body) : List.of();
            recordSuccess("list_accounts", start);
            return result;
        } catch (Exception e) {
            recordError("list_accounts", e);
            log.error("查询账户列表失败: userId={}", userId, e);
            return "查询账户列表失败，请稍后重试";
        }
    }

    /** 仅允许安全字符，防止路径穿越和注入 */
    private static final Pattern SAFE_USER_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * 校验 userId 格式，防止注入攻击和横向越权。
     * 在 demo 中仅做格式校验；生产环境应从认证上下文获取 userId。
     */
    private String validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!SAFE_USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId 格式非法，仅允许字母、数字、下划线和短横线");
        }
        return userId;
    }

    private void recordSuccess(String toolName, long startNs) {
        Counter.builder("mcp.tool.calls.total")
                .tag("tool_name", toolName)
                .tag("status", "success")
                .register(registry)
                .increment();
        Timer.builder("mcp.tool.calls.duration")
                .tag("tool_name", toolName)
                .register(registry)
                .record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
    }

    private void recordError(String toolName, Exception e) {
        Counter.builder("mcp.tool.calls.total")
                .tag("tool_name", toolName)
                .tag("status", "error")
                .register(registry)
                .increment();
        Counter.builder("mcp.tool.calls.errors")
                .tag("tool_name", toolName)
                .tag("error_type", e.getClass().getSimpleName())
                .register(registry)
                .increment();
    }
}
