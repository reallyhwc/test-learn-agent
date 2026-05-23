package com.example.mcp.tool;

import com.example.mcp.dto.AccountResponse;
import com.example.mcp.dto.TransactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class FinanceTools {

    private static final Logger log = LoggerFactory.getLogger(FinanceTools.class);
    private final RestClient restClient;

    public FinanceTools(RestClient restClient) {
        this.restClient = restClient;
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

        StringBuilder uri = new StringBuilder("/api/transactions?userId=").append(userId).append("&");
        if (date != null) uri.append("date=").append(date).append("&");
        if (category != null) uri.append("category=").append(category).append("&");
        if (type != null) uri.append("type=").append(type).append("&");
        if (accountId != null) uri.append("accountId=").append(accountId).append("&");

        return List.of(restClient.get()
                .uri(uri.toString())
                .retrieve()
                .body(TransactionResponse[].class));
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
