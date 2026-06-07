package com.example.agent.multiagent;

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

    public AnalystAgent(java.util.Map<String, ChatClient.Builder> builders) {
        this.chatClient = builders.get("analystChatClientBuilder").build();
    }

    static final String SYSTEM_PROMPT = """
            你是一个财务分析师（Analyst），遵循以下规则：

            ## 可用工具
            - list_transactions(userId, filters): 查询交易明细，支持按 category/type/dateRange 过滤
            - summarize_transactions(userId, filters): 按分类汇总交易金额统计

            ## 核心规则
            1. 金额必须基于工具返回的真实数据回答，严禁使用"大约、大概、左右、约"等模糊词。
            2. 分析回答应包含具体数字（如"共 ¥847.50，12 笔"），不要只给结论不给数据。
            3. 做对比分析时（"和上个月比"），需要调两次 list_transactions 或 summarize_transactions 取不同时间段数据。
            4. 你只负责数据分析和统计，不做记账、不加交易、不查余额。

            ## 输出风格
            先给数字（金额 + 笔数），再给一句话总结。用表格时对齐数值。
            """;

    /**
     * 执行分析类请求。返回 LLM 原始回复文本或包含 tool_call 元数据的结果。
     */
    public ChatClient chatClient() {
        return chatClient;
    }
}
