package com.example.agent.eval;

/**
 * Eval 用例，从 evals/golden-dataset.json 反序列化。
 * 字段命名与 JSON schema 完全一致。
 */
public record EvalCase(
        String id,
        String category,
        String input,
        EvalContext context,
        EvalExpectations expectations
) {
    /** 让 @ParameterizedTest 的 name = "{0}" 显示 caseId 而非默认 record toString */
    @Override
    public String toString() {
        return id;
    }
}
