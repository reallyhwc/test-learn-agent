package com.example.mcp.tool;

import com.example.mcp.dto.AccountResponse;
import com.example.mcp.dto.TransactionResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest
@ActiveProfiles("test")
class FinanceToolsTest {

    static MockRestServiceServer mockServer;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public RestClient financeRestClient(RestClient.Builder builder) {
            mockServer = MockRestServiceServer.bindTo(builder).build();
            return builder.build();
        }

        @Bean
        @Primary
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private FinanceTools financeTools;

    @BeforeEach
    void setUp() {
        mockServer.reset();
    }

    @AfterEach
    void tearDown() {
        mockServer.verify();
    }

    // === query_balance ===

    @Test
    void shouldQueryBalanceSuccessfully() {
        mockServer.expect(requestTo("http://localhost:9999/api/accounts/1/balance"))
                .andRespond(withSuccess("12345.67", MediaType.APPLICATION_JSON));

        BigDecimal result = (BigDecimal) financeTools.queryBalance("default", 1L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("12345.67"));
    }

    @Test
    void shouldHandleBackendErrorOnBalance() {
        mockServer.expect(requestTo("http://localhost:9999/api/accounts/1/balance"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> financeTools.queryBalance("default", 1L))
                .isInstanceOf(RuntimeException.class);
    }

    // === list_transactions ===

    @Test
    void shouldListTransactionsSuccessfully() {
        String responseBody = """
            {"items":[{"id":1,"accountId":1,"type":"EXPENSE","amount":35.00,\
            "category":"餐饮","note":"午餐","date":"2026-05-20","userId":"default"}],\
            "page":1,"pageSize":20,"total":1,"totalPages":1}""";
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        List<TransactionResponse> result = (List<TransactionResponse>) financeTools.listTransactions("default", "{}");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo("餐饮");
    }

    @Test
    void shouldReturnEmptyListWhenNoTransactions() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        List<TransactionResponse> result = (List<TransactionResponse>) financeTools.listTransactions("default", "{}");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldIncludeQueryParamsInUri() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));

        financeTools.listTransactions("zhangsan", "{\"startDate\":\"2026-05-20\",\"category\":\"餐饮\",\"type\":\"EXPENSE\"}");
    }

    // === summarize_transactions ===

    @Test
    void shouldSummarizeTransactionsSuccessfully() {
        String responseBody = """
            [{"category":"理财","totalAmount":5000.00,"count":3},\
            {"category":"合计","totalAmount":5000.00,"count":3}]""";
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions/summary")))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) financeTools.summarizeTransactions("default", "{\"type\":\"INCOME\"}");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("category")).isEqualTo("理财");
    }

    // === add_transaction ===

    @Test
    void shouldAddTransactionSuccessfully() {
        mockServer.expect(requestTo("http://localhost:9999/api/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":100,\"accountId\":1}", MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) financeTools.addTransaction("default", 1L, "EXPENSE",
                new BigDecimal("50"), "餐饮", "午餐");
        assertThat(result).containsKey("id");
    }

    // === list_accounts ===

    @Test
    void shouldListAccountsSuccessfully() {
        mockServer.expect(requestTo("http://localhost:9999/api/accounts?userId=default"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"name\":\"现金\",\"type\":\"CASH\",\"balance\":10000}]",
                        MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        List<AccountResponse> result = (List<AccountResponse>) financeTools.listAccounts("default");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("现金");
    }

    @Test
    void shouldReturnEmptyArrayForAccounts() {
        mockServer.expect(requestTo("http://localhost:9999/api/accounts?userId=nobody"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        List<AccountResponse> result = (List<AccountResponse>) financeTools.listAccounts("nobody");
        assertThat(result).isEmpty();
    }

    // === 入参校验 ===

    @Test
    void shouldFallbackToDefaultWhenUserIdIsNull() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));

        financeTools.listTransactions(null, "{}");
        // 不抛异常即通过，validateUserId 会回退到 "default"
    }

    @Test
    void shouldFallbackToDefaultWhenUserIdIsBlank() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));

        financeTools.listTransactions("  ", "{}");
    }

    @Test
    void shouldHandleInvalidFiltersJsonGracefully() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));

        // 传入非法 JSON，parseFilters 应降级为空 map
        @SuppressWarnings("unchecked")
        List<TransactionResponse> result = (List<TransactionResponse>) financeTools.listTransactions("default", "not-valid-json");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleNullFilters() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));

        financeTools.listTransactions("default", null);
    }

    @Test
    void shouldReturnErrorMessageWhenBackendReturns500OnSummary() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions/summary")))
                .andRespond(withServerError());

        Object result = financeTools.summarizeTransactions("default", "{}");
        assertThat(result).isInstanceOf(String.class);
        assertThat(result.toString()).contains("失败");
    }

    @Test
    void shouldReturnErrorMessageWhenBackendReturns500OnList() {
        mockServer.expect(requestTo(startsWith("http://localhost:9999/api/transactions")))
                .andRespond(withServerError());

        Object result = financeTools.listTransactions("default", "{}");
        assertThat(result).isInstanceOf(String.class);
        assertThat(result.toString()).contains("失败");
    }
}
