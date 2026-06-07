package com.example.agent.multiagent;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Mock 配置：为集成测试提供替代 ChatClient.Builder Bean（不连 LLM）。
 */
@TestConfiguration
public class MultiAgentTestConfig {

    @Bean
    @Primary
    ChatModel mockChatModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Bean(name = "supervisorChatClientBuilder")
    ChatClient.Builder supervisorBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean(name = "bookkeeperChatClientBuilder")
    ChatClient.Builder bookkeeperBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean(name = "analystChatClientBuilder")
    ChatClient.Builder analystBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /** ChatController 使用的默认 ChatClient.Builder（单 Agent 模式） */
    @Bean
    @Primary
    ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
