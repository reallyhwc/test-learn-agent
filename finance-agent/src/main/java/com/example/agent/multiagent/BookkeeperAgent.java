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

    public BookkeeperAgent(java.util.Map<String, ChatClient.Builder> builders,
                           PromptLoader promptLoader) {
        this.chatClient = builders.get("bookkeeperChatClientBuilder").build();
        this.promptLoader = promptLoader;
    }

    /** 从 prompts/ 加载并拼装 System Prompt（含分类体系注入） */
    public String buildSystemPrompt() {
        String categorySystem = promptLoader.loadShared("category-system");
        return promptLoader.assemble("bookkeeper",
                java.util.Map.of("categorySystem", categorySystem));
    }

    /**
     * 执行记账类请求。返回 LLM 原始回复文本或包含 tool_call 元数据的结果。
     */
    public ChatClient chatClient() {
        return chatClient;
    }
}
