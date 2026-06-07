package com.example.agent.eval;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Eval 专用的 ChatClient 配置，与 {@code ChatController} 中的生产 ChatClient 隔离。
 *
 * <p><b>设计目的</b>：</p>
 * <ul>
 *   <li>不依赖生产链路的 Guardrails / ChatMemory / Logger，只测 LLM + MCP 工具的核心行为</li>
 *   <li>通过 {@link ToolCallRecordingManager} 在工具执行层抓取 tool_calls 供断言</li>
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
     * 包装 {@link DefaultToolCallingManager}，在工具执行层记录工具调用。
     * 替换 {@code ToolCallingAutoConfiguration} 中的默认 Bean。
     *
     * <p>Spring AI 1.1.0 的 {@code OpenAiChatModel} 内部递归处理工具调用，
     * Advisor 链无法观测。此包装器直接在 {@code executeToolCalls} 被调用时记录。</p>
     */
    @Bean
    public ToolCallRecordingManager toolCallRecordingManager(
            ToolCallbackResolver callbackResolver,
            ToolExecutionExceptionProcessor exceptionProcessor,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ToolCallingObservationConvention> observationConvention) {

        DefaultToolCallingManager defaultManager = new DefaultToolCallingManager(
                observationRegistry.getIfAvailable(),
                callbackResolver,
                exceptionProcessor);

        observationConvention.ifAvailable(defaultManager::setObservationConvention);

        return new ToolCallRecordingManager(defaultManager);
    }

    /**
     * 旁路 ChatClient：独立创建 Builder（避免复用 @Primary Bean 导致工具重复注册），
     * 仅挂载 RecordingAdvisor，不挂 Guardrails/ChatMemory/审计。
     */
    @Bean(name = "evalChatClient")
    public ChatClient evalChatClient(
            org.springframework.ai.chat.model.ChatModel chatModel,
            List<ToolCallbackProvider> toolProviders,
            ToolCallRecordingAdvisor recordingAdvisor
    ) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(recordingAdvisor)
                .defaultToolCallbacks(toolProviders.toArray(new ToolCallbackProvider[0]))
                .build();
    }
}
