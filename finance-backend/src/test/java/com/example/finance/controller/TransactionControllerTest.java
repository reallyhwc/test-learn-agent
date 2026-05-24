package com.example.finance.controller;

import com.example.finance.config.TestDataConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestDataConfig testDataConfig;

    @BeforeEach
    void setUp() {
        testDataConfig.backup("accounts.csv");
        testDataConfig.backup("transactions.csv");
        testDataConfig.resetAll();
    }

    @Test
    void shouldListTransactions() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void shouldCreateTransaction() throws Exception {
        String json = """
            {"accountId":1,"type":"EXPENSE","amount":100,"category":"餐饮","note":"午餐","date":"2026-05-23"}
        """;
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void shouldFilterTransactionsByDate() throws Exception {
        mockMvc.perform(get("/api/transactions").param("date", "2026-05-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void shouldPaginateTransactions() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("userId", "default")
                        .param("page", "1")
                        .param("pageSize", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(3))
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.items.length()").value(lessThanOrEqualTo(3)));
    }

    @Test
    void shouldReturnSecondPage() throws Exception {
        String page1 = mockMvc.perform(get("/api/transactions")
                        .param("userId", "default")
                        .param("page", "1")
                        .param("pageSize", "3"))
                .andReturn().getResponse().getContentAsString();
        String page2 = mockMvc.perform(get("/api/transactions")
                        .param("userId", "default")
                        .param("page", "2")
                        .param("pageSize", "3"))
                .andReturn().getResponse().getContentAsString();
        assertThat(page1).isNotEqualTo(page2);
    }

    @Test
    void shouldFilterByUserId() throws Exception {
        mockMvc.perform(get("/api/transactions").param("userId", "zhangsan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].userId").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("zhangsan"))));
    }

    @Test
    void shouldFilterByCategory() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("userId", "default")
                        .param("category", "餐饮"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].category").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("餐饮"))));
    }

    @Test
    void shouldFilterByType() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("userId", "default")
                        .param("type", "INCOME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].type").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("INCOME"))));
    }

    @Test
    void shouldReturnEmptyPageForLargePage() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("userId", "default")
                        .param("page", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
