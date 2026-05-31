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

/**
 * 【MCP 工具层】—— 将 Backend REST API 包装为 MCP 协议的工具供 LLM 调用。
 *
 * <p>本类定义了 5 个 {@code @McpTool} 方法，Spring AI MCP Server 启动时自动扫描注册。
 * Agent 通过 SSE 协议连接本 Server，LLM 决策后自动调用这些工具。
 *
 * <h3>工具清单</h3>
 * <ul>
 *   <li>{@link #queryBalance} — 查询单个账户余额（GET /api/accounts/{id}/balance）</li>
 *   <li>{@link #listTransactions} — 查询交易明细列表（GET /api/transactions）</li>
 *   <li>{@link #summarizeTransactions} — 按分类汇总交易（GET /api/transactions/summary）</li>
 *   <li>{@link #addTransaction} — 添加一笔交易（POST /api/transactions）</li>
 *   <li>{@link #listAccounts} — 查询用户全部账户（GET /api/accounts）</li>
 * </ul>
 *
 * <h3>调用链路</h3>
 * <pre>
 * 前端 → Agent(ChatController) → LLM 决策 tool_call → MCP 协议(SSE) → 本类方法 → RestClient → Backend(:8080)
 * </pre>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>每个方法内部捕获所有异常，返回友好字符串（禁止抛异常，会中断 MCP 协议）</li>
 *   <li>每个方法记录 Micrometer 指标（mcp.tool.calls.total / duration / errors）</li>
 *   <li>URI 构建统一使用 {@code UriComponentsBuilder}，防止中文二次编码</li>
 *   <li>userId 通过 {@link #validateUserId} 校验，防止路径穿越等安全问题</li>
 * </ul>
 *
 * @see com.example.agent.controller.ChatController — Agent 侧的 MCP Client 消费方
 */
@Slf4j
@Component
public class FinanceTools {

    /** 连接 Backend REST API 的客户端 */
    private final RestClient restClient;
    /** JSON 解析器，用于 filters 参数和响应转换 */
    private final ObjectMapper objectMapper;
    /** Micrometer 指标注册中心，用于 mcp.tool.* 指标 */
    private final MeterRegistry registry;

    public FinanceTools(RestClient restClient, ObjectMapper objectMapper, MeterRegistry registry) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    /**
     * 查询单个账户余额。
     *
     * <p>实现要点：直接调用 Backend GET /api/accounts/{id}/balance，异常全捕获返回友好字符串。
     *
     * @param userId 用户标识
     * @param accountId 账户 ID
     * @return 余额（BigDecimal）或错误提示字符串
     */
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

    /** 默认返回条数，平衡信息量与 token 消耗 */
    private static final int DEFAULT_PAGE_SIZE = 50;
    /** 分页上限，防止 token 消耗过大 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 查询交易明细列表。
     *
     * <p>实现要点：支持 LLM 通过 filters JSON 传入多维度筛选条件，URI 使用
     * {@link UriComponentsBuilder} 构建防止中文二次编码。返回包含 summary 的结构，
     * 让 LLM 了解数据全貌而不必拉取全量。
     *
     * @param userId 用户标识
     * @param filters 过滤条件 JSON（可选字段：startDate, endDate, category, subCategory, type, accountId, limit）
     * @return 包含 items、total、showing、summary 的 Map，或错误提示字符串
     */
    @McpTool(name = "list_transactions",
            description = "查询交易记录明细列表，默认返回最近50条。仅 userId 必填，其余过滤条件通过 filters JSON 传入。"
                    + "如需更多可在 filters 中指定 limit（最大200）。"
                    + "filters 示例: {\"category\":\"餐饮\",\"type\":\"EXPENSE\",\"limit\":100}"
                    + " filters 可用字段: startDate、endDate(yyyy-MM-dd)、category(一级分类)、subCategory(二级分类)、type(INCOME/EXPENSE)、accountId、limit(返回条数)")
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

            // 支持 LLM 通过 filters.limit 指定返回条数，默认 50，上限 200
            int pageSize = DEFAULT_PAGE_SIZE;
            Object limitObj = filterMap.get("limit");
            if (limitObj != null) {
                try {
                    pageSize = Math.min(Integer.parseInt(limitObj.toString()), MAX_PAGE_SIZE);
                    if (pageSize < 1) pageSize = DEFAULT_PAGE_SIZE;
                } catch (NumberFormatException ignored) {
                    // 解析失败用默认值
                }
            }

            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/api/transactions")
                    .queryParam("userId", userId)
                    .queryParam("pageSize", pageSize);
            if (startDate != null) uriBuilder.queryParam("startDate", startDate);
            if (endDate != null) uriBuilder.queryParam("endDate", endDate);
            if (category != null) uriBuilder.queryParam("category", category);
            if (subCategory != null) uriBuilder.queryParam("subCategory", subCategory);
            if (type != null) uriBuilder.queryParam("type", type);
            if (accountIdObj != null) uriBuilder.queryParam("accountId", accountIdObj);

            java.net.URI uri = uriBuilder.build().toUri();
            log.info("listTransactions URI: {}", uri);

            @SuppressWarnings("unchecked")
            Map<String, Object> pageResult = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

            if (pageResult == null) {
                recordSuccess("list_transactions", start);
                return Map.of("items", List.of(), "total", 0, "showing", 0,
                        "summary", "未查询到交易记录");
            }

            int total = pageResult.get("total") != null ? ((Number) pageResult.get("total")).intValue() : 0;
            log.info("listTransactions response: total={}, pageSize={}", total, pageSize);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawItems = (List<Map<String, Object>>) pageResult.get("items");
            if (rawItems == null || rawItems.isEmpty()) {
                recordSuccess("list_transactions", start);
                return Map.of("items", List.of(), "total", total, "showing", 0,
                        "summary", "共 " + total + " 条记录，当前无匹配数据");
            }

            List<TransactionResponse> items = new ArrayList<>();
            for (Map<String, Object> item : rawItems) {
                items.add(objectMapper.convertValue(item, TransactionResponse.class));
            }
            recordSuccess("list_transactions", start);

            // 返回带摘要的结构，让 LLM 知道数据全貌而不需要拉全量
            String summary = rawItems.size() < total
                    ? "共 " + total + " 条记录，已展示最近 " + rawItems.size() + " 条"
                    : "共 " + total + " 条记录";
            return Map.of("items", items, "total", total, "showing", rawItems.size(), "summary", summary);
        } catch (Exception e) {
            recordError("list_transactions", e);
            log.error("查询交易记录失败: userId={}", userId, e);
            return "查询交易记录失败，请稍后重试";
        }
    }

    /**
     * 按分类汇总交易金额。
     *
     * <p>实现要点：支持按 category 或 subCategory 分组，groupBy 参数通过 filters JSON 传入。
     *
     * @param userId 用户标识
     * @param filters 过滤条件 JSON（可选字段：type, startDate, endDate, groupBy）
     * @return 汇总结果列表（每项含分类名和金额），或错误提示字符串
     */
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

    /**
     * 解析 LLM 传入的 filters JSON 字符串为 Map。
     * 解析失败时静默降级为空 Map（不中断工具调用）。
     */
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

    /**
     * 添加一笔交易记录。
     *
     * <p>实现要点：参数校验不抛异常（返回友好字符串），防止中断 MCP 协议。
     * 校验通过后 POST JSON 到 Backend，包含 date 自动填当天。
     *
     * @param userId 用户标识
     * @param accountId 账户 ID
     * @param type 交易类型（INCOME/EXPENSE）
     * @param amount 金额
     * @param category 一级分类
     * @param subCategory 二级分类
     * @param note 备注
     * @return 创建成功的交易信息 Map，或错误提示字符串
     */
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

    /**
     * 查询用户全部账户列表。
     *
     * <p>实现要点：返回的 AccountResponse 已包含实时 balance 字段，
     * Agent 侧拿到后可直接使用，无需再调 query_balance。
     *
     * @param userId 用户标识
     * @return 账户列表（AccountResponse[]），或错误提示字符串
     */
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

    /**
     * 记录工具调用成功指标：增加 mcp.tool.calls.total（tag: success），记录耗时。
     */
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

    /**
     * 记录工具调用失败指标：增加 mcp.tool.calls.total（tag: error），
     * 按异常类型分类记录 mcp.tool.calls.errors。
     */
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
