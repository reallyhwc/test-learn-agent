package com.example.finance.util;

/**
 * 【XSS 防护工具类】
 *
 * <p>提供 HTML 实体转义功能，对用户输入的 {@code < > & " '} 五个字符做转义处理，
 * 防止跨站脚本注入攻击。在所有 Controller 中写入用户可控文本前调用。
 */
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
