package com.example.finance.util;

public final class LogMaskUtils {

    private LogMaskUtils() {}

    /**
     * 对 userId 进行脱敏，保留前3后2位，中间用 *** 替换。
     * 如 "user-001" -> "use***01"
     */
    public static String maskUserId(String userId) {
        if (userId == null || userId.length() <= 5) {
            return "***";
        }
        return userId.substring(0, 3) + "***" + userId.substring(userId.length() - 2);
    }

    /**
     * 对金额进行脱敏，仅显示整数部分的位数。
     * 如 12345.67 -> "5位数金额"
     */
    public static String maskAmount(Object amount) {
        if (amount == null) {
            return "null";
        }
        String str = amount.toString();
        int dotIndex = str.indexOf('.');
        int intLen = dotIndex > 0 ? dotIndex : str.length();
        return intLen + "位数金额";
    }
}
