package com.example.agent.eval;

import java.time.Instant;
import java.util.List;

/**
 * Eval 运行的汇总报告，序列化后写到 evals/reports/eval-{stack}-yyyyMMdd-HHmmss.json
 *
 * @param runAt       运行开始时间（UTC Instant）
 * @param model       LLM 模型名（如 deepseek-chat / Qwen3.6-Plus-DogFooding）
 * @param stack       栈标识："java" / "python"，便于 HTML 报告分栈展示
 * @param totalCases  总用例数
 * @param passCount   通过数
 * @param failCount   失败数
 * @param results     每个用例的详细结果
 */
public record EvalReport(
        Instant runAt,
        String model,
        String stack,
        int totalCases,
        int passCount,
        int failCount,
        List<EvalResult> results
) {}
