package com.example.agent.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个 Eval 用例的执行结果。
 *
 * @param caseId       用例 ID
 * @param category     用例类别（tool_selection / rejection / amount_accuracy）
 * @param pass         是否通过
 * @param failReason   失败原因（pass=true 时为 null）
 * @param toolsCalled  实际调用的工具名列表
 * @param responseText LLM 回复全文
 * @param durationMs   端到端耗时（毫秒）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalResult(
        String caseId,
        String category,
        boolean pass,
        String failReason,
        List<String> toolsCalled,
        String responseText,
        long durationMs
) {}
