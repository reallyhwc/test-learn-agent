package com.example.mcp.tool;

import com.example.mcp.dto.AccountResponse;
import com.example.mcp.dto.TransactionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class FinanceTools {

    private static final Logger log = LoggerFactory.getLogger(FinanceTools.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public FinanceTools(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "query_balance", description = "查询指定账户的余额")
    public BigDecimal queryBalance(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "账户ID") Long accountId) {
        log.info("queryBalance called with userId={}, accountId={}", userId, accountId);
        return restClient.get()
                .uri("/api/accounts/{id}/balance", accountId)
                .retrieve()
                .body(BigDecimal.class);
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

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/api/transactions")
                .queryParam("userId", userId)
                .queryParam("pageSize", 1000); // get all results
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
            return List.of();
        }

        List<TransactionResponse> result = new ArrayList<>();
        for (Map<String, Object> item : rawItems) {
            result.add(objectMapper.convertValue(item, TransactionResponse.class));
        }
        return result;
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

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("userId", userId);
        body.put("accountId", accountId);
        body.put("type", type);
        body.put("amount", amount);
        body.put("category", category);
        body.put("note", note != null ? note : "");
        body.put("date", java.time.LocalDate.now().toString());

        return restClient.post()
                .uri("/api/transactions")
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    @McpTool(name = "list_accounts", description = "查询所有账户列表")
    public List<AccountResponse> listAccounts(
            @McpToolParam(description = "用户ID") String userId) {
        log.info("listAccounts called with userId={}", userId);
        return List.of(restClient.get()
                .uri("/api/accounts?userId=" + userId)
                .retrieve()
                .body(AccountResponse[].class));
    }

}
