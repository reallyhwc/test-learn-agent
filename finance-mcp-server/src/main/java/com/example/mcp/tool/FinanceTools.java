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

    @McpTool(name = "query_balance", description = "查询指定账户的余额")
    public BigDecimal queryBalance(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "账户ID") Long accountId) {
        log.info("queryBalance called with userId={}, accountId={}", userId, accountId);
        long start = System.nanoTime();
        try {
            BigDecimal result = restClient.get()
                    .uri("/api/accounts/{id}/balance", accountId)
                    .retrieve()
                    .body(BigDecimal.class);
            recordSuccess("query_balance", start);
            return result;
        } catch (Exception e) {
            recordError("query_balance", e);
            throw e;
        }
    }

    @McpTool(name = "list_transactions", description = "查询交易记录列表，可按日期、分类、类型和账户过滤")
    public List<TransactionResponse> listTransactions(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "交易日期 (yyyy-MM-dd)") String date,
            @McpToolParam(description = "交易分类，如餐饮、交通、购物等") String category,
            @McpToolParam(description = "交易类型: INCOME 或 EXPENSE") String type,
            @McpToolParam(description = "账户ID") Long accountId) {
        log.info("listTransactions called with userId={}, date={}, category={}, type={}, accountId={}",
                userId, date, category, type, accountId);
        long start = System.nanoTime();

        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/api/transactions")
                    .queryParam("userId", userId)
                    .queryParam("pageSize", 1000);
            if (date != null) uriBuilder.queryParam("date", date);
            if (category != null) uriBuilder.queryParam("category", category);
            if (type != null) uriBuilder.queryParam("type", type);
            if (accountId != null) uriBuilder.queryParam("accountId", accountId);

            java.net.URI uri = uriBuilder.build().toUri();
            log.info("listTransactions URI: {}", uri);

            Map<String, Object> pageResult = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

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
            throw e;
        }
    }

    @McpTool(name = "add_transaction", description = "添加一笔交易记录")
    public Map<String, Object> addTransaction(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "账户ID") Long accountId,
            @McpToolParam(description = "交易类型: INCOME 或 EXPENSE") String type,
            @McpToolParam(description = "金额") BigDecimal amount,
            @McpToolParam(description = "分类") String category,
            @McpToolParam(description = "备注") String note) {
        log.info("addTransaction called with userId={}, accountId={}, type={}, amount={}, category={}",
                userId, accountId, type, amount, category);
        long start = System.nanoTime();

        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("userId", userId);
            body.put("accountId", accountId);
            body.put("type", type);
            body.put("amount", amount);
            body.put("category", category);
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
            throw e;
        }
    }

    @McpTool(name = "list_accounts", description = "查询所有账户列表")
    public List<AccountResponse> listAccounts(
            @McpToolParam(description = "用户ID") String userId) {
        log.info("listAccounts called with userId={}", userId);
        long start = System.nanoTime();

        try {
            List<AccountResponse> result = List.of(restClient.get()
                    .uri("/api/accounts?userId=" + userId)
                    .retrieve()
                    .body(AccountResponse[].class));
            recordSuccess("list_accounts", start);
            return result;
        } catch (Exception e) {
            recordError("list_accounts", e);
            throw e;
        }
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
