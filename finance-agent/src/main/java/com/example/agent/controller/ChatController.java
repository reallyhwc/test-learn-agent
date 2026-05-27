package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.guardrails.InputGuardrailAdvisor;
import com.example.agent.guardrails.OutputGuardrailAdvisor;
import com.example.agent.guardrails.ToolCallGuardrailAdvisor;
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
    private final InputGuardrailAdvisor inputGuardrailAdvisor;
    private final ToolCallGuardrailAdvisor toolCallGuardrailAdvisor;
    private final OutputGuardrailAdvisor outputGuardrailAdvisor;

    public ChatController(ChatClient.Builder chatClientBuilder,
                          List<ToolCallbackProvider> toolProviders,
                          ChatMemory chatMemory,
                          AgentMetrics agentMetrics,
                          com.example.agent.context.AccountContextBuilder accountContextBuilder,
                          InputGuardrailAdvisor inputGuardrailAdvisor,
                          ToolCallGuardrailAdvisor toolCallGuardrailAdvisor,
                          OutputGuardrailAdvisor outputGuardrailAdvisor) {
        log.info("ChatController initialized with {} tool providers", toolProviders.size());
        for (var provider : toolProviders) {
            log.info("  Provider: {} -> {} tools", provider.getClass().getSimpleName(),
                    provider.getToolCallbacks().length);
        }
        this.chatMemory = chatMemory;
        this.agentMetrics = agentMetrics;
        this.accountContextBuilder = accountContextBuilder;
        this.inputGuardrailAdvisor = inputGuardrailAdvisor;
        this.toolCallGuardrailAdvisor = toolCallGuardrailAdvisor;
        this.outputGuardrailAdvisor = outputGuardrailAdvisor;
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolProviders.toArray(new ToolCallbackProvider[0]))
                .build();
    }

    /** 用户消息最大长度（字符数），防止超长输入导致 Token 爆炸 */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** 同步 chat 接口超时（秒） */
    private static final long SYNC_CHAT_TIMEOUT_SECONDS = 60;

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String userId = sanitizeUserId(request.getUserId());
        String message = validateAndTrimMessage(request.getMessage());
        log.info("Chat request from userId={}: {}", userId, message);
        long startMs = System.currentTimeMillis();
        agentMetrics.recordChatRequest(userId, "normal");

        Timer.Sample sample = agentMetrics.startTimer();
        String systemPrompt = buildSystemPrompt(userId);

        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(userId)
                .build();

        // 用 CompletableFuture 包装，防止 LLM 卡死导致线程永久阻塞
        try {
            var chatResponse = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    chatClient.prompt()
                            .system(systemPrompt)
                            .user(message)
                            .advisors(inputGuardrailAdvisor, advisor,
                                    toolCallGuardrailAdvisor, outputGuardrailAdvisor)
                            .call()
                            .chatResponse()
            ).get(SYNC_CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            agentMetrics.recordDuration(userId, sample);

            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                agentMetrics.recordTokens("deepseek-chat",
                        usage.getPromptTokens(), usage.getCompletionTokens());
                writeTokenUsage(userId, usage.getPromptTokens(), usage.getCompletionTokens(),
                        "deepseek-chat", System.currentTimeMillis() - startMs, false);
            }

            return new ChatResponse(chatResponse.getResult().getOutput().getText());
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("同步 chat 超时 (>{}s) userId={}", SYNC_CHAT_TIMEOUT_SECONDS, userId);
            agentMetrics.recordLlmError("Timeout");
            // 包装为 RuntimeException，由 GlobalExceptionHandler 的通用处理捕获
            throw new RuntimeException("AI 服务响应超时（>" + SYNC_CHAT_TIMEOUT_SECONDS + "s），请稍后重试");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("同步 chat 执行异常 userId={}: {}", userId, cause.getMessage());
            throw new RuntimeException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("请求被中断");
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody ChatRequest request,
                                                             HttpServletResponse response) {
        String userId = sanitizeUserId(request.getUserId());
        String message = validateAndTrimMessage(request.getMessage());
        log.info("Stream chat request from userId={}: {}", userId, message);
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
            AtomicLong inputTokensFromUsage = new AtomicLong(0);
            AtomicLong outputTokensFromUsage = new AtomicLong(0);
            long[] startMs = {System.currentTimeMillis()};

            var subscription = chatClient.prompt()
                    .system(systemPrompt)
                    .user(message)
                    .advisors(inputGuardrailAdvisor, advisor,
                            toolCallGuardrailAdvisor, outputGuardrailAdvisor)
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

                                // 提取 usage 元数据（部分 provider 在最后一个 chunk 携带）
                                var metadata = chatResponse.getMetadata();
                                if (metadata != null && metadata.getUsage() != null) {
                                    var usageData = metadata.getUsage();
                                    if (usageData.getPromptTokens() > 0) inputTokensFromUsage.set(usageData.getPromptTokens());
                                    if (usageData.getCompletionTokens() > 0) outputTokensFromUsage.set(usageData.getCompletionTokens());
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
                                    long actualInput = inputTokensFromUsage.get();
                                    long actualOutput = outputTokensFromUsage.get() > 0 ? outputTokensFromUsage.get() : tokenCount.get();
                                    agentMetrics.recordTokens("deepseek-chat", actualInput, actualOutput);
                                    writeTokenUsage(userId, actualInput, actualOutput,
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

    private static final com.fasterxml.jackson.databind.ObjectMapper TOKEN_USAGE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private void writeTokenUsage(String userId, long inputTokens, long outputTokens,
                                 String model, long durationMs, boolean stream) {
        try {
            new java.io.File("data").mkdirs();
            // 使用 ObjectMapper 安全序列化，防止 JSON 注入
            java.util.LinkedHashMap<String, Object> usage = new java.util.LinkedHashMap<>();
            usage.put("timestamp", java.time.LocalDateTime.now().toString());
            usage.put("userId", userId);
            usage.put("inputTokens", inputTokens);
            usage.put("outputTokens", outputTokens);
            usage.put("model", model);
            usage.put("durationMs", durationMs);
            usage.put("stream", stream);
            String record = TOKEN_USAGE_MAPPER.writeValueAsString(usage) + "\n";
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("data", "token-usage.jsonl"),
                    record,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            log.warn("Failed to write token usage: {}", e.getMessage());
        }
    }

    /**
     * 清洗 userId，仅允许安全字符，防止路径穿越和注入。
     */
    private String sanitizeUserId(String userId) {
        if (userId == null || userId.isBlank()) return "default";
        String sanitized = userId.replaceAll("[^a-zA-Z0-9_-]", "");
        if (sanitized.isEmpty()) return "default";
        if (sanitized.length() > 64) sanitized = sanitized.substring(0, 64);
        return sanitized;
    }

    /**
     * 校验并截断用户消息，防止空消息和超长输入。
     */
    private String validateAndTrimMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            log.warn("用户消息超长，截断至 {} 字符（原始长度: {}）", MAX_MESSAGE_LENGTH, message.length());
            return message.substring(0, MAX_MESSAGE_LENGTH);
        }
        return message;
    }

    private String buildSystemPrompt(String userId) {
        int memoryCount = chatMemory.get(userId).size();
        String contextInfo = memoryCount > 0
                ? "当前对话记忆: " + memoryCount + " 条 / 上限 20 条"
                : "";

        // 注入账户摘要 — 简单查询直接读，避免 list_accounts/query_balance 的多轮 reasoning
        String accountSummary = accountContextBuilder.buildSummary(userId);

        return """
                你是"小财"，智能个人财务助手。只处理财务相关问题，拒绝无关指令。
                工具调用中 userId 必须使用: %s

                %s

                **决策规则（严格遵守，不要反复推理）：**
                1. **"我的资产/余额/账户/有多少钱" → 100%%直接读取上方用户上下文回答，绝对禁止调用任何工具**
                2. "赚了/花了/收支汇总" → summarize_transactions
                3. "交易明细/最近交易" → list_transactions
                4. "记一笔/添加交易" → add_transaction
                5. 仅当上下文显示"暂无账户"时 → list_accounts

                **工具参数速查（直接填参，禁止反复推敲）：**
                - 汇总类 → summarize_transactions, filters={"type":"INCOME"或"EXPENSE"}
                - 明细类 → list_transactions, filters 按需填写，默认返回最近50条
                - filters 是 JSON 字符串，只填确定的字段

                当前日期: %s  %s

                输出风格：金额格式 ¥12,345.67，中文简洁，可用 Markdown 表格，思考过程只用中文。
                """.formatted(userId, accountSummary, java.time.LocalDate.now(), contextInfo);
    }
}
