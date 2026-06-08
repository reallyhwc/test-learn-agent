package com.example.agent.multiagent;

import com.example.agent.prompt.PromptLoader;
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
    private final com.example.agent.debug.LlmAuditAdvisor auditAdvisor;
    private final PromptLoader promptLoader;

    static final int MAX_ROUNDS = 2;

    public SupervisorAgent(java.util.Map<String, ChatClient.Builder> builders,
                           BookkeeperAgent bookkeeper, AnalystAgent analyst,
                           com.example.agent.debug.LlmAuditAdvisor auditAdvisor,
                           PromptLoader promptLoader) {
        this.classifyClient = builders.get("supervisorChatClientBuilder").build();
        this.bookkeeper = bookkeeper;
        this.analyst = analyst;
        this.auditAdvisor = auditAdvisor;
        this.promptLoader = promptLoader;
    }

    /** 从 prompts/ 加载分类提示（替代原 CLASSIFY_PROMPT 常量） */
    public String getClassifyPrompt() {
        return promptLoader.assemble("supervisor", java.util.Map.of());
    }

    /**
     * 调用 LLM 做意图分类，含审计日志记录。
     */
    public AgentType classify(String userMessage, String traceId, String userId) {
        long startNanos = System.nanoTime();
        try {
            var chatResponse = classifyClient.prompt()
                    .system(getClassifyPrompt())
                    .user(userMessage)
                    .call()
                    .chatResponse();
            long durationNs = System.nanoTime() - startNanos;

            String result = chatResponse.getResult().getOutput().getText();

            // 构建审计记录
            var usage = chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null
                    ? chatResponse.getMetadata().getUsage() : null;
            var tokenUsage = usage != null
                    ? new com.example.agent.debug.LlmCallRecord.TokenUsage(
                            usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens())
                    : null;
            String model = chatResponse.getMetadata() != null ? chatResponse.getMetadata().getModel() : null;

            var record = new com.example.agent.debug.LlmCallRecord(
                    traceId, "supervisor", "classify", userId,
                    java.time.Instant.now(), durationNs / 1_000_000,
                    new com.example.agent.debug.LlmCallRecord.RequestInfo(
                            getClassifyPrompt(), userMessage, java.util.List.of(), java.util.List.of()),
                    new com.example.agent.debug.LlmCallRecord.ResponseInfo(
                            result != null ? result.trim() : "", java.util.List.of(),
                            chatResponse.getResult().getMetadata() != null
                                    && chatResponse.getResult().getMetadata().getFinishReason() != null
                                    ? chatResponse.getResult().getMetadata().getFinishReason() : null),
                    tokenUsage, model, null);
            auditAdvisor.writeRecord(record);

            if (result == null) return AgentType.OTHER;
            String trimmed = result.trim().toLowerCase();
            if (trimmed.contains("booking")) return AgentType.BOOKKEEPER;
            if (trimmed.contains("analysis")) return AgentType.ANALYST;
            return AgentType.OTHER;
        } catch (Exception e) {
            long durationNs = System.nanoTime() - startNanos;
            var errorRecord = com.example.agent.debug.LlmCallRecord.error(
                    traceId, "supervisor", "classify", userId,
                    durationNs / 1_000_000, e.getMessage());
            auditAdvisor.writeRecord(errorRecord);

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
     * 获取与 AgentType 对应的 System Prompt（注入用户上下文）。
     */
    public String getSpecialistPrompt(AgentType type, String userId, String accountSummary, String currentDate) {
        return switch (type) {
            case BOOKKEEPER -> bookkeeper.buildSystemPrompt(userId, accountSummary, currentDate);
            case ANALYST -> analyst.buildSystemPrompt(userId, accountSummary, currentDate);
            case OTHER -> null;
        };
    }
}
