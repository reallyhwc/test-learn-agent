package com.example.agent.multiagent;

import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Mock 配置：为集成测试提供替代 ChatClient Bean（不连 LLM）。
 */
@TestConfiguration
public class MultiAgentTestConfig {

    @Bean
    @Primary
    ChatModel mockChatModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Bean(name = "supervisorChatClientBuilder")
    @Primary
    ChatClient.Builder supervisorBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean(name = "bookkeeperChatClientBuilder")
    @Primary
    ChatClient.Builder bookkeeperBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean(name = "analystChatClientBuilder")
    @Primary
    ChatClient.Builder analystBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /** 给 ChatController 注入的无名默认 ChatClient.Builder */
    @Bean
    @Primary
    ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
