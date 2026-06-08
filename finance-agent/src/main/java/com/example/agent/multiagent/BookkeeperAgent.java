package com.example.agent.multiagent;

import com.example.agent.prompt.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 记账 Specialist Agent — 处理记账、查余额、查账户等 CRUD 操作。
 *
 * <p>绑定工具：add_transaction, list_accounts, query_balance
 * <p>System Prompt 约 300 token，专注记账规则 + 二级分类枚举。
 */
@Component
public class BookkeeperAgent {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public BookkeeperAgent(@org.springframework.beans.factory.annotation.Qualifier("bookkeeperChatClientBuilder")
                           ChatClient.Builder bookkeeperBuilder,
                           PromptLoader promptLoader) {
        this.chatClient = bookkeeperBuilder.build();
        this.promptLoader = promptLoader;
    }

    /** 从 prompts/ 加载并拼装 System Prompt（含安全规则、账户上下文、分类体系注入） */
    public String buildSystemPrompt(String userId, String accountSummary, String currentDate) {
        String categorySystem = promptLoader.loadShared("category-system");
        String safetyRules = promptLoader.loadShared("safety-rules");
        return promptLoader.assemble("bookkeeper",
                java.util.Map.of(
                        "userId", userId,
                        "accountSummary", accountSummary != null ? accountSummary : "",
                        "currentDate", currentDate,
                        "safetyRules", safetyRules,
                        "categorySystem", categorySystem));
    }

    /**
     * 执行记账类请求。返回 LLM 原始回复文本或包含 tool_call 元数据的结果。
     */
    public ChatClient chatClient() {
        return chatClient;
    }
}
