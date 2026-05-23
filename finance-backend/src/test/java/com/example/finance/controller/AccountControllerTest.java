package com.example.finance.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
}
