package com.example.finance.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 测试 GlobalExceptionHandler 的统一错误响应格式。
 * 通过触发真实 Controller 的异常路径来验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturn400ForInvalidTransactionType() throws Exception {
        // type 字段传入非法值，触发 IllegalArgumentException
        String json = """
            {"accountId":1,"type":"INVALID_TYPE","amount":100,"category":"餐饮","note":"测试","date":"2026-05-23"}
        """;
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void shouldReturn400ForMissingRequestBody() throws Exception {
        // POST 不带 body，触发参数解析异常
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400ForInvalidDateFormat() throws Exception {
        // 日期格式错误，触发类型转换异常
        mockMvc.perform(get("/api/transactions")
                        .param("startDate", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn404ForNonExistentAccount() throws Exception {
        mockMvc.perform(get("/api/accounts/99999/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnErrorResponseWithTimestamp() throws Exception {
        String json = """
            {"accountId":1,"type":"BAD","amount":100,"category":"餐饮","note":"测试","date":"2026-05-23"}
        """;
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.error").exists());
    }
}
