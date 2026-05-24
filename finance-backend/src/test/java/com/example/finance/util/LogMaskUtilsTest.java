package com.example.finance.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogMaskUtilsTest {

    @Test
    void shouldMaskUserId() {
        assertEquals("use***01", LogMaskUtils.maskUserId("user-001"));
    }

    @Test
    void shouldReturnStarsForShortUserId() {
        assertEquals("***", LogMaskUtils.maskUserId("ab"));
        assertEquals("***", LogMaskUtils.maskUserId("abcde"));
    }

    @Test
    void shouldReturnStarsForNull() {
        assertEquals("***", LogMaskUtils.maskUserId(null));
    }

    @Test
    void shouldMaskAmount() {
        assertEquals("5位数金额", LogMaskUtils.maskAmount(12345.67));
        assertEquals("3位数金额", LogMaskUtils.maskAmount(100));
    }

    @Test
    void shouldHandleNullAmount() {
        assertEquals("null", LogMaskUtils.maskAmount(null));
    }
}
