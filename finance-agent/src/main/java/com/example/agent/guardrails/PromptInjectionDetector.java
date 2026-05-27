package com.example.agent.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt Injection 检测工具类。
 * <p>
 * 通过正则模式匹配检测用户输入中的注入攻击，支持中文和英文注入模式。
 * 类似 SQL Injection 检测，但针对的是 LLM System Prompt 的"语义逃逸"。
 */
@Slf4j
@Component
public class PromptInjectionDetector {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 中文：试图覆盖指令
            Pattern.compile("忽略.{0,10}(以上|之前|所有|前面).{0,10}(指令|规则|提示|设定|约束)"),
            Pattern.compile("(无视|抛弃|放弃|丢掉|不要遵守).{0,10}(指令|规则|提示|设定|约束)"),
            // 中文：试图改变角色
            Pattern.compile("你现在是.{0,20}(角色|身份|助手|机器人|AI)"),
            Pattern.compile("(扮演|假装|模拟|变成|充当).{0,10}(另一个|其他|新的|不同的)"),
            // 中文：试图提取 System Prompt
            Pattern.compile("(输出|显示|告诉我|重复|打印).{0,10}(系统提示|system\\s*prompt|初始指令|设定)"),
            // 英文：试图覆盖指令
            Pattern.compile("(ignore|disregard|forget|override|bypass).{0,20}(instruction|rule|prompt|directive|constraint)", Pattern.CASE_INSENSITIVE),
            // 英文：试图改变角色
            Pattern.compile("you\\s+are\\s+(now|actually|really).{0,20}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(act|pretend|behave)\\s+(as|like)\\s+", Pattern.CASE_INSENSITIVE),
            // 英文：试图提取 System Prompt
            Pattern.compile("(print|output|show|repeat|display).{0,15}(system\\s*prompt|initial\\s*instruction)", Pattern.CASE_INSENSITIVE),
            // 通用：DAN / jailbreak 关键词
            Pattern.compile("\\bDAN\\b|\\bjailbreak\\b|\\bdo\\s+anything\\s+now\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 检测用户消息是否包含 Prompt Injection 攻击。
     *
     * @param userMessage 用户输入的消息
     * @return true 表示检测到注入攻击
     */
    public boolean isInjection(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(userMessage).find()) {
                log.warn("Prompt Injection 检测命中: pattern={}, message={}",
                        pattern.pattern(), truncateForLog(userMessage));
                return true;
            }
        }
        return false;
    }

    private String truncateForLog(String message) {
        return message.length() > 100 ? message.substring(0, 100) + "..." : message;
    }
}
