package com.example.agent.exception;

import com.example.agent.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({SocketTimeoutException.class, TimeoutException.class,
            java.net.http.HttpTimeoutException.class})
    public ResponseEntity<ChatResponse> handleTimeout(Exception e) {
        log.warn("LLM 调用超时: {}", e.getMessage());
        return ResponseEntity.ok(new ChatResponse("抱歉，AI 服务响应超时，请稍后重试。"));
    }

    @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.TooManyRequests.class)
    public ResponseEntity<ChatResponse> handleRateLimit(Exception e) {
        log.warn("LLM 调用限流: {}", e.getMessage());
        return ResponseEntity.ok(new ChatResponse("抱歉，当前请求过于频繁，请稍后重试。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatResponse> handleGeneral(Exception e) {
        log.error("LLM 调用异常: {}", e.getMessage(), e);
        return ResponseEntity.ok(new ChatResponse("抱歉，AI 服务暂时不可用，请稍后重试。"));
    }
}
