package com.example.agent.controller;

import com.example.agent.dto.FeedbackRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class FeedbackController {

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody FeedbackRequest request) {
        log.info("Feedback received: userId={} messageId={} rating={}",
                request.getUserId(), request.getMessageId(), request.getRating());

        try {
            new java.io.File("data").mkdirs();
            String record = String.format(
                    "{\"timestamp\":\"%s\",\"userId\":\"%s\",\"messageId\":\"%s\",\"rating\":\"%s\"}\n",
                    LocalDateTime.now().toString(),
                    request.getUserId(),
                    request.getMessageId(),
                    request.getRating());
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
