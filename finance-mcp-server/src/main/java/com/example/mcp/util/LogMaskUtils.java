package com.example.mcp.util;

import java.math.BigDecimal;

/** 日志脱敏工具 — 与 finance-backend 的 LogMaskUtils 功能一致。 */
public final class LogMaskUtils {

    private LogMaskUtils() {}

    /** 用户ID脱敏: 保留前3位 + *** */
    public static String maskUserId(String userId) {
        if (userId == null || userId.isBlank()) return "***";
        if (userId.length() <= 3) return userId.charAt(0) + "***";
        return userId.substring(0, 3) + "***";
    }

    /** 金额脱敏: 仅保留数字位数信息 */
    public static String maskAmount(BigDecimal amount) {
        if (amount == null) return "***";
        return "<金额:" + amount.precision() + "位>";
    }

    /** 金额脱敏(string版) */
    public static String maskAmountStr(String amount) {
        if (amount == null || amount.isBlank()) return "***";
        return "<金额:" + amount.length() + "位>";
    }
}
