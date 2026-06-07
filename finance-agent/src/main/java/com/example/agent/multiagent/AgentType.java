package com.example.agent.multiagent;

/**
 * Supervisor 意图分类结果。
 */
public enum AgentType {
    BOOKKEEPER,  // 记账类: 添加交易、查余额、查账户
    ANALYST,     // 分析类: 交易明细、分类汇总、趋势分析
    OTHER        // 与财务无关，拒绝
}
