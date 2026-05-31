package com.example.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【对话记忆查询控制器】
 *
 * <p>提供调试/监控用的记忆查询端点。返回指定用户当前的对话记忆条数。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    /** 对话记忆 Bean（{@link com.example.agent.config.ChatMemoryConfig} 注册的 JsonFileChatMemory） */
    private final ChatMemory chatMemory;

    public MemoryController(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    /**
     * 查询指定用户的记忆消息数。
     *
     * @param userId 用户标识（默认 "default"）
     * @return {"userId": "...", "count": N}
     */
    @GetMapping("/count")
    public Map<String, Object> count(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        int count = chatMemory.get(userId).size();
        return Map.of("userId", userId, "count", count);
    }
}
