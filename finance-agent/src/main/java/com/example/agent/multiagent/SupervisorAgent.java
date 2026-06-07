package com.example.agent.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Supervisor 编排器 — 意图分类 + 派发 Specialist + 整合结果。
 *
 * <p>不绑定任何 MCP 工具，只做 LLM 级文本分类。
 * <p>最多 2 轮编排循环，防止无限循环。
 */
@Component
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);

    private final ChatClient classifyClient;
    private final BookkeeperAgent bookkeeper;
    private final AnalystAgent analyst;

    static final int MAX_ROUNDS = 2;

    static final String CLASSIFY_PROMPT = """
            你是一个意图分类器。分析用户消息，返回以下分类之一：
            - booking: 记账、查余额、查账户、添加交易记录
            - analysis: 统计汇总、趋势分析、分类占比、对比支出
            - other: 与个人财务无关的请求（写诗、闲聊、写代码等）
            只返回分类名称，不要解释。
            """;

    public SupervisorAgent(java.util.Map<String, ChatClient.Builder> builders,
                           BookkeeperAgent bookkeeper, AnalystAgent analyst) {
        this.classifyClient = builders.get("supervisorChatClientBuilder").build();
        this.bookkeeper = bookkeeper;
        this.analyst = analyst;
    }

    /**
     * 调用 LLM 做意图分类。
     */
    public AgentType classify(String userMessage) {
        try {
            String result = classifyClient.prompt()
                    .system(CLASSIFY_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();
            if (result == null) return AgentType.OTHER;
            String trimmed = result.trim().toLowerCase();
            if (trimmed.contains("booking")) return AgentType.BOOKKEEPER;
            if (trimmed.contains("analysis")) return AgentType.ANALYST;
            return AgentType.OTHER;
        } catch (Exception e) {
            log.warn("意图分类失败，fallback to OTHER: {}", e.getMessage());
            return AgentType.OTHER;
        }
    }

    /**
     * 获取目标 Specialist 的 ChatClient。
     */
    public ChatClient getSpecialistClient(AgentType type) {
        return switch (type) {
            case BOOKKEEPER -> bookkeeper.chatClient();
            case ANALYST -> analyst.chatClient();
            case OTHER -> null;
        };
    }

    /**
     * 获取与 AgentType 对应的 System Prompt。
     */
    public String getSpecialistPrompt(AgentType type) {
        return switch (type) {
            case BOOKKEEPER -> BookkeeperAgent.SYSTEM_PROMPT;
            case ANALYST -> AnalystAgent.SYSTEM_PROMPT;
            case OTHER -> null;
        };
    }
}
