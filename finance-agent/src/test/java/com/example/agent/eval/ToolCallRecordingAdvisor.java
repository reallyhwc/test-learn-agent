package com.example.agent.eval;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Eval 专用 Advisor：从 LLM 响应中抓取 tool_calls 列表，按 sessionId 隔离存储，
 * 测试代码通过 {@link #drain(String)} 取出。
 *
 * <p>使用方式：</p>
 * <pre>
 * recorder.start(userId);
 * evalClient.prompt().system(...).user(...).advisors(spec -> spec.param(SESSION_ID, userId)).call();
 * List&lt;String&gt; toolsCalled = recorder.drain(userId);
 * </pre>
 *
 * <p>仿照 {@code com.example.agent.guardrails.ToolCallGuardrailAdvisor} 的模式，
 * 但只做记录不做拦截。</p>
 */
public class ToolCallRecordingAdvisor implements BaseAdvisor {

    /** advisor context 中 sessionId 的 key */
    public static final String SESSION_ID = "eval.sessionId";

    /** sessionId -> 调用列表 */
    private final ConcurrentMap<String, List<RecordedCall>> records = new ConcurrentHashMap<>();

    /**
     * 已记录的工具调用。
     *
     * @param toolName       工具名（如 query_balance）
     * @param argumentsJson  工具参数 JSON 字符串
     */
    public record RecordedCall(String toolName, String argumentsJson) {}

    /**
     * 排序：靠近 LLM，after 阶段最早拿到响应。
     * 数值越大优先级越低（after 链中越早执行）。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    /**
     * 为指定 sessionId 准备一个空的记录槽。重复调用会清空已有记录。
     */
    public void start(String sessionId) {
        records.put(sessionId, Collections.synchronizedList(new ArrayList<>()));
    }

    /**
     * 取出并清空指定 sessionId 的记录。从未 {@link #start} 过返回空列表。
     */
    public List<RecordedCall> drain(String sessionId) {
        List<RecordedCall> list = records.remove(sessionId);
        return list != null ? list : List.of();
    }

    /**
     * before 阶段：透传 sessionId 到 context（如果调用方通过 .param(SESSION_ID, xxx) 注入了）。
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    /**
     * after 阶段：从 LLM 响应抓取 tool_calls，按 sessionId 存到 records。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return response;
        }

        AssistantMessage output = chatResponse.getResult().getOutput();
        if (output == null || !output.hasToolCalls()) {
            return response;
        }

        Object sessionId = response.context().get(SESSION_ID);
        if (sessionId == null) {
            return response;  // 未注入 sessionId，跳过记录
        }

        List<RecordedCall> bucket = records.computeIfAbsent(
                sessionId.toString(),
                k -> Collections.synchronizedList(new ArrayList<>())
        );
        for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
            bucket.add(new RecordedCall(tc.name(), tc.arguments()));
        }
        return response;
    }
}
