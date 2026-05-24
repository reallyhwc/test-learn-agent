package com.example.finance.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void okShouldContainData() {
        ApiResponse<String> resp = ApiResponse.ok("hello");
        assertTrue(resp.isSuccess());
        assertEquals("hello", resp.getData());
        assertNull(resp.getMessage());
    }

    @Test
    void errorShouldContainMessage() {
        ApiResponse<Object> resp = ApiResponse.error("fail");
        assertFalse(resp.isSuccess());
        assertNull(resp.getData());
        assertEquals("fail", resp.getMessage());
    }
}
