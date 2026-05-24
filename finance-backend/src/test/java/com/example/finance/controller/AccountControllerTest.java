package com.example.finance.controller;

import com.example.finance.config.TestDataConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestDataConfig testDataConfig;

    @BeforeEach
    void setUp() {
        testDataConfig.backup("accounts.csv");
        testDataConfig.reset("accounts.csv");
    }

    @Test
    void shouldListAccounts() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void shouldCreateAccount() throws Exception {
        String json = """
            {"name":"测试卡","type":"CARD","balance":5000}
        """;
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("测试卡"));
    }

    @Test
    void shouldGetBalance() throws Exception {
        mockMvc.perform(get("/api/accounts/1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isNumber());
    }

    @Test
    void shouldFilterAccountsByUserId() throws Exception {
        mockMvc.perform(get("/api/accounts").param("userId", "zhangsan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].userId").value("zhangsan"));
    }

    @Test
    void shouldReturnEmptyForUnknownUser() throws Exception {
        mockMvc.perform(get("/api/accounts").param("userId", "nobody"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldReturn404ForNonExistentAccount() throws Exception {
        mockMvc.perform(get("/api/accounts/99999/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldCreateAccountWithUserId() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试账户\",\"type\":\"BANK\",\"balance\":1000,\"userId\":\"zhangsan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("zhangsan"));
    }
}
