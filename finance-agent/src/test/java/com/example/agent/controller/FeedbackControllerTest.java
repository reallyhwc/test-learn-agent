package com.example.agent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FeedbackController 单元测试 — standaloneSetup 方式。
 */
class FeedbackControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FeedbackController()).build();
    }

    @Test
    void shouldAcceptValidFeedback() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "userId": "default",
                                "messageId": "msg-001",
                                "rating": "good"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void shouldAcceptFeedbackWithBadRating() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "userId": "user-001",
                                "messageId": "msg-002",
                                "rating": "bad"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void shouldReturnErrorWhenBodyIsEmpty() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    assertThat(statusCode).isGreaterThanOrEqualTo(400);
                });
    }
}
