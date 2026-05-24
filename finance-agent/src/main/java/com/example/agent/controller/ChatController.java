package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AgentMetrics agentMetrics;

    public ChatController(ChatClient.Builder chatClientBuilder,
                          List<ToolCallbackProvider> toolProviders,
                          ChatMemory chatMemory,
                          AgentMetrics agentMetrics) {
        log.info("ChatController initialized with {} tool providers", toolProviders.size());
        for (var provider : toolProviders) {
            log.info("  Provider: {} -> {} tools", provider.getClass().getSimpleName(),
                    provider.getToolCallbacks().length);
        }
        this.chatMemory = chatMemory;
        this.agentMetrics = agentMetrics;
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolProviders.toArray(new ToolCallbackProvider[0]))
                .build();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String userId = request.getUserId() != null ? request.getUserId() : "default";
        log.info("Chat request from userId={}: {}", userId, request.getMessage());
        long startMs = System.currentTimeMillis();
        agentMetrics.recordChatRequest(userId, "normal");

        Timer.Sample sample = agentMetrics.startTimer();
        String systemPrompt = buildSystemPrompt(userId);

        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(userId)
                .build();

        var chatResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(request.getMessage())
                .advisors(advisor)
                .call()
                .chatResponse();

        agentMetrics.recordDuration(userId, sample);

        var usage = chatResponse.getMetadata().getUsage();
        if (usage != null) {
            agentMetrics.recordTokens("deepseek-chat",
                    usage.getPromptTokens(), usage.getCompletionTokens());
            writeTokenUsage(userId, usage.getPromptTokens(), usage.getCompletionTokens(),
                    "deepseek-chat", System.currentTimeMillis() - startMs, false);
        }

        return new ChatResponse(chatResponse.getResult().getOutput().getText());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody ChatRequest request,
                                                             HttpServletResponse response) {
        String userId = request.getUserId() != null ? request.getUserId() : "default";
        log.info("Stream chat request from userId={}: {}", userId, request.getMessage());
        agentMetrics.recordChatRequest(userId, "stream");

        Timer.Sample durationSample = agentMetrics.startTimer();
        String systemPrompt = buildSystemPrompt(userId);

        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(userId)
                .build();

        // Disable response buffering so each SSE event flushes immediately to the network
        response.setBufferSize(0);
        response.setHeader("X-Accel-Buffering", "no");

        StreamingResponseBody body = outputStream -> {
            CountDownLatch latch = new CountDownLatch(1);

            AtomicBoolean firstToken = new AtomicBoolean(false);
            Timer.Sample ttftSample = agentMetrics.startTimer();
            AtomicLong tokenCount = new AtomicLong(0);
            long[] startMs = {System.currentTimeMillis()};

            chatClient.prompt()
                    .system(systemPrompt)
                    .user(request.getMessage())
                    .advisors(advisor)
                    .stream()
                    .content()
                    .subscribe(
                            token -> {
                                if (!firstToken.getAndSet(true)) {
                                    agentMetrics.recordTtft(userId, ttftSample);
                                }
                                tokenCount.incrementAndGet();
                                writeSseEvent(outputStream, token);
                            },
                            error -> {
                                log.error("Stream error for userId={}: {}", userId, error.getMessage());
                                agentMetrics.recordLlmError(error.getClass().getSimpleName());
                                latch.countDown();
                            },
                            () -> {
                                long elapsedMs = System.currentTimeMillis() - startMs[0];
                                if (elapsedMs > 0 && tokenCount.get() > 0) {
                                    long tps = tokenCount.get() * 1000 / elapsedMs;
                                    agentMetrics.recordTokenSpeed(tps);
                                    agentMetrics.recordTokens("deepseek-chat", 0, tokenCount.get());
                                writeTokenUsage(userId, 0, tokenCount.get(),
                                        "deepseek-chat", System.currentTimeMillis() - startMs[0], true);
                                }
                                agentMetrics.recordDuration(userId, durationSample);
                                log.info("Stream completed for userId={}, tokens={}", userId, tokenCount.get());
                                latch.countDown();
                            }
                    );

            try {
                latch.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private void writeSseEvent(OutputStream out, String token) {
        try {
            String sse = "data:" + token + "\n\n";
            out.write(sse.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            log.error("SSE write error: {}", e.getMessage());
        }
    }

    private void writeTokenUsage(String userId, long inputTokens, long outputTokens,
                                 String model, long durationMs, boolean stream) {
        try {
            new java.io.File("data").mkdirs();
            String record = String.format(
                    "{\"timestamp\":\"%s\",\"userId\":\"%s\",\"inputTokens\":%d,\"outputTokens\":%d,\"model\":\"%s\",\"durationMs\":%d,\"stream\":%b}\n",
                    java.time.LocalDateTime.now().toString(),
                    userId, inputTokens, outputTokens, model, durationMs, stream);
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("data", "token-usage.jsonl"),
                    record,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            log.warn("Failed to write token usage: {}", e.getMessage());
        }
    }

    private String buildSystemPrompt(String userId) {
        int memoryCount = chatMemory.get(userId).size();
        String contextInfo = memoryCount > 0
                ? "当前对话记忆: " + memoryCount + " 条 / 上限 20 条"
                : "";

        return """
                你是"小财"，一个智能个人财务助手。

                **关键规则：你必须通过调用工具来获取数据，绝对不能编造、猜测或使用自己的知识来回答财务问题。**
                每次用户提问，你都要调用相应的工具查询真实数据后再回答。

                核心能力：
                - 查询账户余额：调用 query_balance 工具
                - 查询交易记录：调用 list_transactions 工具，支持按分类、日期、类型过滤
                - 添加交易记录：调用 add_transaction 工具
                - 查看所有账户：调用 list_accounts 工具

                支出分类：餐饮、交通、购物、房租、娱乐、医疗、教育、其他
                收入分类：工资、兼职、理财

                当前信息：
                - 用户ID: %s
                - 日期: %s
                - %s

                工作流程：
                1. 用户提问后，立即调用相关工具
                2. 根据工具返回的真实数据组织回答
                3. 调用任何工具时必须传递 userId = "%s"
                4. 金额格式：¥12,345.67
                5. 中文回复，简洁清晰

                图表输出：当回答包含数值统计时，用 <chart> 标签替代 Markdown 表格。
                精确格式（空格和引号必须完全一致）：
                <chart type="bar|pie|line" title="标题">
                列名1,列名2
                标签1,数值1
                标签2,数值2
                </chart>

                规则：
                - type 和 title 之间必须有空格（type="bar" title="...")
                - 列名不要包含括号注释，写"金额"而不是"金额（元）"
                - 数值只写数字，不要带 ¥、元、, 等符号
                - 数据前后保留自然语言解释文字
                - 月度对比用 bar，分类占比用 pie，趋势变化用 line
                """.formatted(userId, java.time.LocalDate.now(), contextInfo, userId);
    }
}
