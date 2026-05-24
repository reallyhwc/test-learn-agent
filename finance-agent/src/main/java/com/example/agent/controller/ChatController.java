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
            AtomicBoolean clientGone = new AtomicBoolean(false);

            AtomicBoolean firstToken = new AtomicBoolean(false);
            Timer.Sample ttftSample = agentMetrics.startTimer();
            AtomicLong tokenCount = new AtomicLong(0);
            long[] startMs = {System.currentTimeMillis()};

            var subscription = chatClient.prompt()
                    .system(systemPrompt)
                    .user(request.getMessage())
                    .advisors(advisor)
                    .stream()
                    .content()
                    .subscribe(
                            token -> {
                                if (clientGone.get()) return;
                                if (!firstToken.getAndSet(true)) {
                                    agentMetrics.recordTtft(userId, ttftSample);
                                }
                                tokenCount.incrementAndGet();
                                writeSseData(outputStream, token, clientGone);
                            },
                            error -> {
                                String msg = error.getMessage() != null
                                        ? error.getMessage()
                                        : error.getClass().getSimpleName();
                                log.error("Stream error for userId={}: {}", userId, msg, error);
                                agentMetrics.recordLlmError(error.getClass().getSimpleName());
                                writeSseError(outputStream, "AI 服务异常：" + msg, clientGone);
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
                                } else if (tokenCount.get() == 0 && !clientGone.get()) {
                                    // Spring AI 对部分 LLM 4xx (API key/模型/限流) 走 onComplete 而非 onError
                                    // 前端会显示空气泡，主动发 event:error 让用户感知
                                    log.warn("Stream completed with 0 tokens for userId={}, treating as error", userId);
                                    agentMetrics.recordLlmError("EmptyResponse");
                                    writeSseError(outputStream,
                                            "AI 服务返回空响应（可能是 API key 失效、模型不可用或上游限流，请稍后重试或检查 .env 配置）",
                                            clientGone);
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
            } finally {
                // 客户端断开或 stream 结束后取消上游 LLM 订阅，及时释放资源
                subscription.dispose();
            }
        };

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private void writeSseData(OutputStream out, String token, AtomicBoolean clientGone) {
        if (clientGone.get()) return;
        try {
            // SSE spec: payload 内的 \n 必须每行加 data: 前缀，浏览器解析时再用 \n 拼回。
            String escaped = token.replace("\n", "\ndata:");
            out.write(("data:" + escaped + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // Response not usable / Broken pipe → 客户端已断，标记并停止重试避免日志刷屏
            if (clientGone.compareAndSet(false, true)) {
                log.warn("SSE client disconnected: {}", e.getMessage());
            }
        }
    }

    private void writeSseError(OutputStream out, String message, AtomicBoolean clientGone) {
        if (clientGone.get()) return;
        try {
            String escaped = message.replace("\n", "\ndata:");
            out.write(("event:error\ndata:" + escaped + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            if (clientGone.compareAndSet(false, true)) {
                log.warn("SSE client disconnected during error write: {}", e.getMessage());
            }
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

                回复风格：使用 Markdown 表格展示统计数据，适度使用标题和强调，简洁清晰。
                """.formatted(userId, java.time.LocalDate.now(), contextInfo, userId);
    }
}
