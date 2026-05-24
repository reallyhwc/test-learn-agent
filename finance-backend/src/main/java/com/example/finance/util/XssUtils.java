package com.example.finance.util;

public final class XssUtils {

    private XssUtils() {}

    /**
     * 对用户输入进行 HTML 实体转义，防止 XSS 攻击。
     * 转义 < > & " ' 五个字符。
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
