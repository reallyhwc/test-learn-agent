package com.example.agent.eval;

/**
 * Eval 用例的运行上下文（会话级数据）。
 *
 * @param userId    会话用户标识，注入到 system prompt 让 LLM 在工具调用中使用
 * @param accountId 可选的账户 ID（部分 case 涉及特定账户时用）
 */
public record EvalContext(
        String userId,
        Long accountId
) {}
