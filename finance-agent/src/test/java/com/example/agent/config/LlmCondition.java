package com.example.agent.config;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.FileInputStream;
import java.util.Properties;

public class LlmCondition implements ExecutionCondition {

    private static final boolean LLM_AVAILABLE = checkLlmAvailability();

    private static boolean checkLlmAvailability() {
        String[] paths = {"../.env", ".env"};
        for (String path : paths) {
            try (FileInputStream in = new FileInputStream(path)) {
                Properties props = new Properties();
                props.load(in);
                String apiKey = props.getProperty("LLM_API_KEY", "").trim();
                if (!apiKey.isEmpty() && !apiKey.equals("your-api-key-here")) {
                    System.out.println("[LlmCondition] LLM API key found, enabling AI tests");
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        System.out.println("[LlmCondition] No LLM API key found, AI tests will be skipped");
        return false;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (LLM_AVAILABLE) {
            return ConditionEvaluationResult.enabled("LLM is available");
        }
        return ConditionEvaluationResult.disabled("LLM API key not configured, skipping AI tests");
    }
}
