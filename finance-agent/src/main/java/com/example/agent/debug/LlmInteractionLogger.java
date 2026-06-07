package com.example.agent.debug;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 交互日志 —— 每次 LLM 调用一条 JSONL 记录，按天切分日志文件。
 *
 * <h3>日志格式</h3>
 * <pre>{@code
 * {"ts":"2026-06-07 19:58:30.123","agent":"finance-agent","durationMs":1523,
 *  "request":{"systemLen":2500,"userMessage":"查余额","historyCount":3},
 *  "response":{"text":"您的余额...","toolCalls":["list_accounts"],"finishReason":"stop"},
 *  "tokens":{"input":450,"output":120,"total":570},"model":"deepseek-chat"}
 * }</pre>
 *
 * <h3>文件路径</h3>
 * <p>{@code logs/llm-interactions/llm-call-YYYY-MM-DD.jsonl}</p>
 */
@Slf4j
@Component
public class LlmInteractionLogger implements BaseAdvisor {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path logDir;
    private final boolean enabled;
    private final ThreadLocal<Instant> callStartTime = new ThreadLocal<>();
    private final ThreadLocal<String> callUserMessage = new ThreadLocal<>();
    private final ThreadLocal<Integer> callSystemLen = new ThreadLocal<>();

    public LlmInteractionLogger(
            @Value("${finance.debug.llm-log-dir:logs/llm-interactions}") String logDirPath,
            @Value("${finance.debug.log-llm-interactions:true}") boolean enabled) {
        this.logDir = Path.of(logDirPath);
        this.enabled = enabled;
        if (enabled) {
            try {
                Files.createDirectories(this.logDir);
                log.info("LlmInteractionLogger 已启用，日志目录: {}", this.logDir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("无法创建日志目录: {}", e.getMessage());
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) {
            return request;
        }
        callStartTime.set(Instant.now());

        if (request.prompt() != null && request.prompt().getInstructions() != null) {
            List<Message> messages = request.prompt().getInstructions();
            for (Message msg : messages) {
                String role = msg.getMessageType().name();
                if ("USER".equals(role) || "HUMAN".equals(role)) {
                    callUserMessage.set(msg.getText());
                }
                if ("SYSTEM".equals(role) && msg.getText() != null) {
                    callSystemLen.set(msg.getText().length());
                }
            }
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) {
            return response;
        }

        Instant start = callStartTime.get();
        String userMsg = callUserMessage.get();
        Integer sysLen = callSystemLen.get();

        // 清理 ThreadLocal
        callStartTime.remove();
        callUserMessage.remove();
        callSystemLen.remove();

        if (start == null) {
            return response;
        }

        long durationMs = Duration.between(start, Instant.now()).toMillis();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ts", start.atZone(ZoneId.systemDefault()).format(TS_FMT));
        record.put("agent", "finance-agent");
        record.put("durationMs", durationMs);

        // ---- request ----
        Map<String, Object> req = new LinkedHashMap<>();
        if (sysLen != null) {
            req.put("systemLen", sysLen);
        }
        req.put("userMessage", userMsg != null ? userMsg : "");

        // 统计历史消息数量
        if (response.chatResponse() != null && response.chatResponse().getResults() != null) {
            // 从 advisor context 中无法直接获取消息数, 设为 0
            req.put("historyCount", 0);
        }
        record.put("request", req);

        // ---- response ----
        Map<String, Object> resp = new LinkedHashMap<>();
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse != null) {
            List<Generation> generations = chatResponse.getResults();
            if (generations != null && !generations.isEmpty()) {
                Generation gen = generations.get(0);
                AssistantMessage output = gen.getOutput();

                String text = output.getText();
                resp.put("text", text != null ? text : "");

                List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    resp.put("toolCalls", toolCalls.stream()
                            .map(tc -> tc.name() + "(" + tc.arguments() + ")")
                            .toList());
                }

                if (gen.getMetadata() != null && gen.getMetadata().getFinishReason() != null) {
                    resp.put("finishReason", gen.getMetadata().getFinishReason());
                }
            }

            // ---- tokens + model ----
            if (chatResponse.getMetadata() != null) {
                var usage = chatResponse.getMetadata().getUsage();
                if (usage != null) {
                    Map<String, Object> tokens = new LinkedHashMap<>();
                    tokens.put("input", usage.getPromptTokens());
                    tokens.put("output", usage.getCompletionTokens());
                    tokens.put("total", usage.getTotalTokens());
                    record.put("tokens", tokens);
                }
                if (chatResponse.getMetadata().getModel() != null) {
                    record.put("model", chatResponse.getMetadata().getModel());
                }
            }
        }
        record.put("response", resp);

        // 写入按日切分的 JSONL 文件
        writeRecord(record);
        return response;
    }

    private void writeRecord(Map<String, Object> record) {
        try {
            String today = LocalDate.now().format(FILE_DATE_FMT);
            Path file = logDir.resolve("llm-call-" + today + ".jsonl");
            // 用 Jackson 风格的简单 JSON 序列化（避免引入额外依赖）
            String json = toJson(record);
            Files.writeString(file, json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("写入 LLM 交互日志失败: {}", e.getMessage());
        }
    }

    /** 简易 JSON 序列化，避免引入 Jackson/Gson 依赖。 */
    @SuppressWarnings("unchecked")
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value instanceof String s) {
                sb.append("\"").append(escape(s)).append("\"");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof Map) {
                sb.append(toJson((Map<String, Object>) value));
            } else if (value instanceof List<?> list) {
                sb.append("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(",");
                    Object item = list.get(i);
                    if (item instanceof String s) {
                        sb.append("\"").append(escape(s)).append("\"");
                    } else {
                        sb.append("\"").append(escape(String.valueOf(item))).append("\"");
                    }
                }
                sb.append("]");
            } else if (value == null) {
                sb.append("null");
            } else {
                sb.append("\"").append(escape(String.valueOf(value))).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
