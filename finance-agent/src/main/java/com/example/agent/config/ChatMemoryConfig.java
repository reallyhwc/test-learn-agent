package com.example.agent.config;

import com.example.agent.memory.JsonFileChatMemory;
import com.example.agent.metrics.AgentMetrics;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    @Value("${finance.memory-dir:data/memory}")
    private String memoryDir;

    @Bean
    public ChatMemory chatMemory(AgentMetrics agentMetrics) {
        return new JsonFileChatMemory(memoryDir, 20, agentMetrics);
    }
}
