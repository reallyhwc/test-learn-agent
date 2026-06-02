package com.example.agent.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Eval 专用的 ChatClient 配置，与 {@code ChatController} 中的生产 ChatClient 隔离。
 *
 * <p><b>设计目的</b>：</p>
 * <ul>
 *   <li>不依赖生产链路的 Guardrails / ChatMemory / Logger，只测 LLM + MCP 工具的核心行为</li>
 *   <li>注入 {@link ToolCallRecordingAdvisor} 抓取 tool_calls 供断言</li>
 *   <li>System Prompt 由测试用例自行构建（简化、稳定，避免与生产 prompt 演进耦合）</li>
 * </ul>
 */
@TestConfiguration
public class EvalChatClientConfig {

    @Bean
    public ToolCallRecordingAdvisor toolCallRecordingAdvisor() {
        return new ToolCallRecordingAdvisor();
    }

    /**
     * 旁路 ChatClient：复用同一份 ChatModel + MCP ToolCallbackProvider，
     * 但只挂载 RecordingAdvisor，不挂 Guardrails。
     */
    @Bean(name = "evalChatClient")
    public ChatClient evalChatClient(
            ChatClient.Builder chatClientBuilder,
            List<ToolCallbackProvider> toolProviders,
            ToolCallRecordingAdvisor recordingAdvisor
    ) {
        return chatClientBuilder
                .defaultAdvisors(recordingAdvisor)
                .defaultToolCallbacks(toolProviders.toArray(new ToolCallbackProvider[0]))
                .build();
    }
}
