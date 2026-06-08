package com.example.agent.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-Agent 配置：创建 4 个独立 ChatClient Builder Bean，各自绑定不同的 MCP 工具子集。
 *
 * <p>ToolCallbackProvider 列表由 Spring AI MCP auto-configuration 注入，
 * 包含全部 5 个 MCP 工具。各 Bean 通过名称过滤绑定子集。
 *
 * <p>LlmAuditAdvisor 通过 defaultAdvisors 注入 chatClient/bookkeeper/analyst 三个 Builder，
 * 从 per-request .param() 设置的 context 中读取 traceId/agentName 等元数据。
 * supervisorChatClientBuilder 不注入（SupervisorAgent 手动 writeRecord）。
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "multi-agent.enabled", havingValue = "true", matchIfMissing = true)
public class MultiAgentConfig {

    /** 记账类工具：add_transaction, list_accounts, query_balance */
    static final List<String> BOOKKEEPER_TOOLS = List.of(
            "add_transaction", "list_accounts", "query_balance");

    /** 分析类工具：list_transactions, summarize_transactions */
    static final List<String> ANALYST_TOOLS = List.of(
            "list_transactions", "summarize_transactions");

    private final com.example.agent.debug.LlmAuditAdvisor llmAuditAdvisor;

    public MultiAgentConfig(com.example.agent.debug.LlmAuditAdvisor llmAuditAdvisor) {
        this.llmAuditAdvisor = llmAuditAdvisor;
    }

    /**
     * 默认 ChatClient.Builder（@Primary）— 不预绑定工具，供单 Agent 模式使用。
     * ChatController 构造函数会自行调用 defaultToolCallbacks() 绑定全量工具。
     * 同时保证 Spring 注入 ChatClient.Builder 时能匹配到唯一的 bean。
     */
    @Bean
    @org.springframework.context.annotation.Primary
    ChatClient.Builder chatClientBuilder(
            org.springframework.ai.chat.model.ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(llmAuditAdvisor);
    }

    @Bean(name = "bookkeeperChatClientBuilder")
    ChatClient.Builder bookkeeperChatClientBuilder(
            org.springframework.ai.chat.model.ChatModel chatModel,
            List<ToolCallbackProvider> toolProviders) {
        var filtered = filterTools(toolProviders, BOOKKEEPER_TOOLS);
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(filtered.toArray(new ToolCallbackProvider[0]))
                .defaultAdvisors(llmAuditAdvisor);
    }

    @Bean(name = "analystChatClientBuilder")
    ChatClient.Builder analystChatClientBuilder(
            org.springframework.ai.chat.model.ChatModel chatModel,
            List<ToolCallbackProvider> toolProviders) {
        var filtered = filterTools(toolProviders, ANALYST_TOOLS);
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(filtered.toArray(new ToolCallbackProvider[0]))
                .defaultAdvisors(llmAuditAdvisor);
    }

    @Bean(name = "supervisorChatClientBuilder")
    ChatClient.Builder supervisorChatClientBuilder(
            org.springframework.ai.chat.model.ChatModel chatModel) {
        // Supervisor 不绑定任何 MCP 工具，只做文本分类。
        // 不注入 LlmAuditAdvisor — SupervisorAgent.classify() 手动 writeRecord。
        return ChatClient.builder(chatModel);
    }

    /**
     * 按工具名称过滤 ToolCallbackProvider 列表。
     * 每个 provider 可能包含多个工具，只保留名称在白名单中的。
     */
    private List<ToolCallbackProvider> filterTools(List<ToolCallbackProvider> providers,
                                                    List<String> allowedNames) {
        return providers.stream()
                .map(p -> (ToolCallbackProvider) () -> Arrays.stream(p.getToolCallbacks())
                        .filter(tc -> allowedNames.contains(tc.getToolDefinition().name()))
                        .toArray(ToolCallback[]::new))
                .filter(p -> p.getToolCallbacks().length > 0)
                .toList();
    }
}
