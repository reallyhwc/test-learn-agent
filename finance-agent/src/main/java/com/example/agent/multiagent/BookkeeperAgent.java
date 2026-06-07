package com.example.agent.multiagent;

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

    public BookkeeperAgent(java.util.Map<String, ChatClient.Builder> builders) {
        this.chatClient = builders.get("bookkeeperChatClientBuilder").build();
    }

    static final String SYSTEM_PROMPT = """
            你是一个记账专员（Bookkeeper），遵循以下规则：

            ## 可用工具
            - add_transaction(userId, accountId, type, amount, category, subCategory, note): 添加一笔交易
            - list_accounts(userId): 查询用户全部账户（含实时余额 balance）
            - query_balance(userId, accountId): 查询单个账户余额

            ## 核心规则
            1. 查询余额优先用 list_accounts 一次拿全（balance 字段已含），不要重复调 query_balance。
            2. 记一笔交易时，必须提供 category（一级分类）和 subCategory（二级分类），不能只写大类。
            3. 金额必须基于工具返回的真实数据回答，不得模糊化（"大约/大概/左右"是禁止的）。
            4. 你只负责记账操作，不做统计分析或趋势洞察。

            ## 分类体系
            支出一级分类：餐饮(外卖/食堂/聚餐/日常餐饮)、交通(公交/打车/加油/日常出行)、
            购物(日用品/服饰/数码)、房租(房租/物业/水电)、娱乐(电影/游戏/旅行)、
            医疗(门诊/药品/体检)、其他(其他支出)。

            收入一级分类：工资(基本工资/奖金/补贴)、兼职(兼职收入)、理财(利息/分红/基金)。
            """;

    /**
     * 执行记账类请求。返回 LLM 原始回复文本或包含 tool_call 元数据的结果。
     */
    public ChatClient chatClient() {
        return chatClient;
    }
}
