package com.example.agent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MemoryController 单元测试 — standaloneSetup 方式，不启动完整 Spring 容器。
 */
class MemoryControllerTest {

    private MockMvc mockMvc;
    private ChatMemory chatMemory;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryController(chatMemory)).build();
    }

    @Test
    void shouldReturnMemoryCountForDefaultUser() throws Exception {
        List<Message> messages = List.of(
                new UserMessage("你好"),
                new AssistantMessage("你好！")
        );
        when(chatMemory.get("default")).thenReturn(messages);

        mockMvc.perform(get("/api/memory/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("default"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void shouldReturnMemoryCountForSpecificUser() throws Exception {
        when(chatMemory.get("user-001")).thenReturn(List.of(new UserMessage("测试")));

        mockMvc.perform(get("/api/memory/count").param("userId", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-001"))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void shouldReturnZeroForNewUser() throws Exception {
        when(chatMemory.get("new-user")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/memory/count").param("userId", "new-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("new-user"))
                .andExpect(jsonPath("$.count").value(0));
    }
}
