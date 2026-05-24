package com.example.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final ChatMemory chatMemory;

    public MemoryController(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    @GetMapping("/count")
    public Map<String, Object> count(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        int count = chatMemory.get(userId).size();
        return Map.of("userId", userId, "count", count);
    }
}
