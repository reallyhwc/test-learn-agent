package com.example.agent.controller;

import com.example.agent.dto.FeedbackRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【用户反馈收集控制器】
 *
 * <p>接收前端用户对 AI 回复的评分（1-5 星），以 JSONL 格式追加写入 {@code data/feedback.jsonl}。
 * 使用 Jackson ObjectMapper 安全序列化，防止 JSON 注入。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class FeedbackController {

    /** JSON 序列化器，用于安全写入反馈记录 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 提交用户反馈评分。
     *
     * @param request 反馈请求（含 userId、messageId、rating）
     * @return 200 + {"status": "ok"}，写入失败时返回 500
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody FeedbackRequest request) {
        log.info("Feedback received: userId={} messageId={} rating={}",
                request.getUserId(), request.getMessageId(), request.getRating());

        try {
            new java.io.File("data").mkdirs();

            // 使用 ObjectMapper 安全序列化，防止 JSON 注入
            Map<String, String> feedbackRecord = new LinkedHashMap<>();
            feedbackRecord.put("timestamp", LocalDateTime.now().toString());
            feedbackRecord.put("userId", request.getUserId());
            feedbackRecord.put("messageId", request.getMessageId());
            feedbackRecord.put("rating", request.getRating());

            String record = objectMapper.writeValueAsString(feedbackRecord) + "\n";
            Files.writeString(
                    Path.of("data", "feedback.jsonl"),
                    record,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write feedback: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", "写入失败"));
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
