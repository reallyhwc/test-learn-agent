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
    private final com.example.agent.context.AccountContextBuilder accountContextBuilder;

    public ChatController(ChatClient.Builder chatClientBuilder,
                          List<ToolCallbackProvider> toolProviders,
                          ChatMemory chatMemory,
                          AgentMetrics agentMetrics,
                          com.example.agent.context.AccountContextBuilder accountContextBuilder) {
        log.info("ChatController initialized with {} tool providers", toolProviders.size());
        for (var provider : toolProviders) {
            log.info("  Provider: {} -> {} tools", provider.getClass().getSimpleName(),
                    provider.getToolCallbacks().length);
        }
        this.chatMemory = chatMemory;
        this.agentMetrics = agentMetrics;
        this.accountContextBuilder = accountContextBuilder;
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
                    .chatResponse()
                    .subscribe(
                            chatResponse -> {
                                if (clientGone.get()) return;
                                var gen = chatResponse.getResult();
                                if (gen == null) return;
                                var msg = gen.getOutput();
                                if (msg == null) return;

                                // Spring AI 把 reasoning_content 放在 AssistantMessage.metadata 里
                                Object reasoningObj = msg.getMetadata() != null
                                        ? msg.getMetadata().get("reasoningContent")
                                        : null;
                                String reasoning = reasoningObj != null ? reasoningObj.toString() : null;
                                String text = msg.getText();

                                // 任意一个非空都算"开始响应"，记录 TTFT（应 < 1s）
                                boolean hasReasoning = reasoning != null && !reasoning.isEmpty();
                                boolean hasText = text != null && !text.isEmpty();
                                if ((hasReasoning || hasText) && !firstToken.getAndSet(true)) {
                                    agentMetrics.recordTtft(userId, ttftSample);
                                }

                                // reasoning 走独立 channel：event:thinking
                                if (hasReasoning) {
                                    writeSseThinking(outputStream, reasoning, clientGone);
                                }
                                // 真正的回答走默认 channel
                                if (hasText) {
                                    tokenCount.incrementAndGet();
                                    writeSseData(outputStream, text, clientGone);
                                }
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
                if (!latch.await(115, TimeUnit.SECONDS)) {
                    // latch 超时（含工具反复调用/LLM 卡死）。比 Spring async timeout (120s) 早触发以争取写出错误
                    log.warn("Stream timed out after 115s for userId={}", userId);
                    writeSseError(outputStream,
                            "AI 响应超时（>115s），可能是 tool 调用过多或 LLM 慢，请简化问题或稍后重试",
                            clientGone);
                }
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

    private void writeSseThinking(OutputStream out, String token, AtomicBoolean clientGone) {
        if (clientGone.get()) return;
        try {
            String escaped = token.replace("\n", "\ndata:");
            out.write(("event:thinking\ndata:" + escaped + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            if (clientGone.compareAndSet(false, true)) {
                log.warn("SSE client disconnected during thinking write: {}", e.getMessage());
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

        // 注入账户摘要 — 简单查询直接读，避免 list_accounts/query_balance 的多轮 reasoning
        String accountSummary = accountContextBuilder.buildSummary(userId);

        return """
                你是"小财"，一个智能个人财务助手。

                %s

                **决策规则：**
                1. 涉及账户、余额、账户名/类型 等基本信息：**直接读取上方"用户上下文"作答，不要调用任何工具**
                2. 涉及交易明细、按时间/类别筛选/统计：调用 list_transactions
                3. 添加新交易：调用 add_transaction
                4. 上下文显示"暂无账户"或不在前 5 大但用户问到细节：才需要调 list_accounts

                工具能力：
                - list_transactions: 查询交易记录，支持按分类、日期、类型过滤
                - add_transaction: 添加一笔交易
                - list_accounts: 查询全部账户列表（仅当上下文不足时使用）
                - query_balance: 按 accountId 查询余额（注意：list_accounts 返回值已含 balance，通常无需再调）

                支出分类：餐饮、交通、购物、房租、娱乐、医疗、其他
                收入分类：工资、兼职、理财

                当前信息：
                - 用户ID: %s
                - 日期: %s
                - %s

                输出风格：
                - 调用任何工具时必须传 userId = "%s"
                - 金额格式：¥12,345.67
                - 中文简洁回复，可用 Markdown 表格展示统计；不要展示自己的内部推理过程
                """.formatted(accountSummary, userId, java.time.LocalDate.now(), contextInfo, userId);
    }
}
