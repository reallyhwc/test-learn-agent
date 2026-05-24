package com.example.agent.validation;

import java.util.List;

public class AiResponseValidator {

    private static final List<String> DEGRADATION_PATTERNS = List.of(
        "抱歉", "暂时不可用", "无法获取", "请稍后重试", "我没有", "我不知道"
    );

    public static ValidationResult validate(String response, ValidationCriteria criteria) {
        if (response == null || response.isBlank()) {
            return ValidationResult.fail(response, "响应为空");
        }
        if (hasDegradation(response)) {
            return ValidationResult.fail(response, "响应包含降级文案");
        }
        if (isPossiblyTruncated(response)) {
            return ValidationResult.fail(response, "响应可能被截断");
        }
        if (!containsKeywords(response, criteria.requiredKeywords())) {
            return ValidationResult.fail(response, "缺少预期关键词: " + criteria.requiredKeywords());
        }
        if (!containsData(response, criteria.requiredDataPoints())) {
            return ValidationResult.fail(response, "缺少预期数据: " + criteria.requiredDataPoints());
        }
        if (response.length() < criteria.minLength()) {
            return ValidationResult.fail(response, "响应过短 (" + response.length() + " < " + criteria.minLength() + ")");
        }
        return ValidationResult.pass(response);
    }

    public static boolean isNotEmpty(String response) {
        return response != null && !response.isBlank();
    }

    public static boolean hasDegradation(String response) {
        return DEGRADATION_PATTERNS.stream().anyMatch(response::contains);
    }

    public static boolean isPossiblyTruncated(String response) {
        String trimmed = response.trim();
        if (trimmed.length() < 20) return true;
        return trimmed.endsWith("的") || trimmed.endsWith("了") || trimmed.endsWith("和")
            || trimmed.endsWith("或") || trimmed.endsWith("在") || trimmed.endsWith("从");
    }

    public static boolean containsKeywords(String response, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return true;
        return keywords.stream().anyMatch(response::contains);
    }

    public static boolean containsData(String response, List<String> dataPoints) {
        if (dataPoints == null || dataPoints.isEmpty()) return true;
        return dataPoints.stream().anyMatch(response::contains);
    }

    public record ValidationResult(boolean passed, List<String> failures, String response) {
        public static ValidationResult pass(String response) {
            return new ValidationResult(true, List.of(), response);
        }
        public static ValidationResult fail(String response, String reason) {
            return new ValidationResult(false, List.of(reason), response);
        }
    }

    public record ValidationCriteria(
        List<String> requiredKeywords,
        List<String> requiredDataPoints,
        int minLength,
        int maxLength
    ) {
        public ValidationCriteria {
            if (requiredKeywords == null) requiredKeywords = List.of();
            if (requiredDataPoints == null) requiredDataPoints = List.of();
            if (minLength <= 0) minLength = 20;
            if (maxLength <= 0) maxLength = 5000;
        }
    }
}
