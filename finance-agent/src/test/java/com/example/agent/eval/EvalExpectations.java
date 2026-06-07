package com.example.agent.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Eval 用例的期望断言集合。所有字段都是可选的。
 *
 * @param toolCalled          期望被调用的工具名；null 表示"不应调用任何工具"（拒绝场景）
 * @param toolParamsContain   工具参数 JSON 必须包含的键值对子集
 * @param responseContainsAny 回复文本必须包含列表中至少一个字符串
 * @param responseNotContains 回复文本不得包含列表中任何字符串
 * @param routedTo            期望 Supervisor 分类结果 booking|analysis|other
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalExpectations(
        String toolCalled,
        Map<String, Object> toolParamsContain,
        List<String> responseContainsAny,
        List<String> responseNotContains,
        String routedTo
) {}
