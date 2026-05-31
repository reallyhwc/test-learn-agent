package com.example.agent.exception;

import com.example.agent.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 【Agent 全局异常处理器】
 *
 * <p>将 LLM 调用相关的异常转换为用户友好的 {@link ChatResponse}。
 * 不返回原始异常信息，防止敏感信息泄露。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验异常 → 400 Bad Request。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ChatResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ChatResponse(e.getMessage()));
    }

    /**
     * LLM 调用超时 → 504 Gateway Timeout。
     */
    @ExceptionHandler({SocketTimeoutException.class, TimeoutException.class})
    public ResponseEntity<ChatResponse> handleTimeout(Exception e) {
        log.warn("LLM 调用超时: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ChatResponse("抱歉，AI 服务响应超时，请稍后重试。"));
    }

    /**
     * LLM API 限流 (HTTP 429) → 429 Too Many Requests。
     */
    @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.TooManyRequests.class)
    public ResponseEntity<ChatResponse> handleRateLimit(Exception e) {
        log.warn("LLM 调用限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ChatResponse("抱歉，当前请求过于频繁，请稍后重试。"));
    }

    /**
     * 兜底异常处理 → 500，返回脱敏后的通用错误提示。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatResponse> handleGeneral(Exception e) {
        log.error("LLM 调用异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ChatResponse("抱歉，AI 服务暂时不可用，请稍后重试。"));
    }
}
