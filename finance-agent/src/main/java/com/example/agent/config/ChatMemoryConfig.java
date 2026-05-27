package com.example.agent.config;

import com.example.agent.memory.JsonFileChatMemory;
import com.example.agent.metrics.AgentMetrics;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【对话记忆配置】—— 定义 ChatMemory Bean。
 *
 * <p>Spring AI 的 {@code MessageChatMemoryAdvisor} 会使用此 Bean 自动管理对话历史。
 * 使用自定义的 {@link com.example.agent.memory.JsonFileChatMemory} 实现：
 * <ul>
 *   <li>存储方式：JSON 文件持久化到 {@code data/memory/{userId}.json}</li>
 *   <li>条数上限：max 20 轮（超出时移除最早的消息）</li>
 *   <li>Token 估算截断：总 token 超过 4000 时继续移除最早消息</li>
 *   <li>LRU 内存缓存：最多 200 个用户会话</li>
 *   <li>异步持久化：不阻塞请求线程</li>
 * </ul>
 *
 * @see com.example.agent.memory.JsonFileChatMemory — 具体实现
 */
@Configuration
public class ChatMemoryConfig {

    @Value("${finance.memory-dir:data/memory}")
    private String memoryDir;

    @Bean
    public ChatMemory chatMemory(AgentMetrics agentMetrics) {
        return new JsonFileChatMemory(memoryDir, 20, agentMetrics);
    }
}
