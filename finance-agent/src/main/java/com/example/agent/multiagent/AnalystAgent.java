package com.example.agent.multiagent;

import com.example.agent.prompt.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 分析 Specialist Agent — 处理交易统计、分类汇总、趋势分析。
 *
 * <p>绑定工具：list_transactions, summarize_transactions
 * <p>System Prompt 约 300 token，专注数据分析和金额精确性。
 */
@Component
public class AnalystAgent {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public AnalystAgent(java.util.Map<String, ChatClient.Builder> builders,
                        PromptLoader promptLoader) {
        this.chatClient = builders.get("analystChatClientBuilder").build();
        this.promptLoader = promptLoader;
    }

    /** 从 prompts/ 加载并拼装 System Prompt（含安全规则、账户上下文注入） */
    public String buildSystemPrompt(String userId, String accountSummary, String currentDate) {
        String safetyRules = promptLoader.loadShared("safety-rules");
        return promptLoader.assemble("analyst",
                java.util.Map.of(
                        "userId", userId,
                        "accountSummary", accountSummary != null ? accountSummary : "",
                        "currentDate", currentDate,
                        "safetyRules", safetyRules));
    }

    /**
     * 执行分析类请求。返回 LLM 原始回复文本或包含 tool_call 元数据的结果。
     */
    public ChatClient chatClient() {
        return chatClient;
    }
}
