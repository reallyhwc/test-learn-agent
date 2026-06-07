package com.example.agent.eval;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ToolCallingManager} 包装器，在工具执行层记录工具调用。
 *
 * <p>Spring AI 1.1.0 的 {@code OpenAiChatModel} 在内部递归处理工具调用，
 * Advisor 链无法观测到中间的工具调用响应。此包装器直接在
 * {@link #executeToolCalls(Prompt, ChatResponse)} 被调用时记录工具名称和参数，
 * 供 Eval 测试断言使用。</p>
 */
public class ToolCallRecordingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final ThreadLocal<List<ToolCallRecordingAdvisor.RecordedCall>> recordedCalls =
            ThreadLocal.withInitial(ArrayList::new);

    public ToolCallRecordingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    /** 清空当前线程的记录，在每次 Eval case 开始前调用。 */
    public void start() {
        recordedCalls.get().clear();
    }

    /** 取出并清空当前线程的记录。 */
    public List<ToolCallRecordingAdvisor.RecordedCall> drain() {
        List<ToolCallRecordingAdvisor.RecordedCall> calls = List.copyOf(recordedCalls.get());
        recordedCalls.get().clear();
        return calls;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        AssistantMessage output = response.getResult().getOutput();
        if (output != null && output.hasToolCalls()) {
            for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
                recordedCalls.get().add(
                        new ToolCallRecordingAdvisor.RecordedCall(tc.name(), tc.arguments()));
            }
        }
        return delegate.executeToolCalls(prompt, response);
    }
}
