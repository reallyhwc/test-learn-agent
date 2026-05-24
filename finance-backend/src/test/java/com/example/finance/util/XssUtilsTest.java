package com.example.finance.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XssUtilsTest {

    @Test
    void shouldSanitizeHtmlTags() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", XssUtils.sanitize("<script>alert(1)</script>"));
    }

    @Test
    void shouldSanitizeSpecialChars() {
        assertEquals("&amp;&lt;&gt;&quot;&#x27;", XssUtils.sanitize("&<>\"'"));
    }

    @Test
    void shouldReturnNullForNull() {
        assertNull(XssUtils.sanitize(null));
    }

    @Test
    void shouldPreservePlainText() {
        assertEquals("Hello World", XssUtils.sanitize("Hello World"));
    }

    @Test
    void shouldHandleEmptyString() {
        assertEquals("", XssUtils.sanitize(""));
    }
}
