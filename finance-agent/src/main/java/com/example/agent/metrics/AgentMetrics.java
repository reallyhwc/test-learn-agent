package com.example.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【Agent 自定义 Micrometer 指标】
 *
 * <p>涵盖 Agent 核心监控维度：
 * <ul>
 *   <li>{@code agent.chat.requests} — 请求计数（按 type: normal/stream 分 tag）</li>
 *   <li>{@code agent.chat.duration} — 请求总耗时（Timer）</li>
 *   <li>{@code agent.chat.ttft} — 首个 token 到达时间（TTFT）</li>
 *   <li>{@code agent.llm.tokens.input/output} — Token 用量（按 model 分 tag）</li>
 *   <li>{@code agent.llm.tokens.speed} — 流式输出 TPS（Gauge）</li>
 *   <li>{@code agent.llm.errors} — LLM 错误计数（按 error_type 分 tag）</li>
 *   <li>{@code agent.memory.*} — 对话记忆聚合指标（总量，不按 userId 拆分）</li>
 * </ul>
 *
 * <h3>高基数注意事项</h3>
 * 为避免 Prometheus 指标爆炸，userId 不作为标签使用，
 * 记忆指标使用聚合 gauge（总和/活跃用户数）替代按用户拆分。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    /** token 速率 gauge 的最新值（tokens/s） */
    private final AtomicLong tokenSpeed = new AtomicLong(0);
    /** userId → 消息数（仅用于聚合 gauge 计算） */
    private final ConcurrentHashMap<String, AtomicLong> memoryMessages = new ConcurrentHashMap<>();
    /** userId → 文件大小（仅用于聚合 gauge 计算） */
    private final ConcurrentHashMap<String, AtomicLong> memorySizes = new ConcurrentHashMap<>();

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("agent.llm.tokens.speed", tokenSpeed, AtomicLong::get)
                .register(registry);
    }

    /**
     * 记录一次聊天请求。userId 不作为标签，避免高基数导致 Prometheus 指标爆炸。
     */
    public void recordChatRequest(String userId, String type) {
        // 不再将 userId 作为标签，避免高基数导致 Prometheus 指标爆炸
        Counter.builder("agent.chat.requests")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    /**
     * 启动一个高精度计时器（纳秒），返回的 Sample 用于后续 recordDuration 或 recordTtft。
     */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * 记录首个 token 到达时间（TTFT）。
     */
    public void recordTtft(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.ttft")
                .register(registry));
    }

    /**
     * 记录一次请求的总耗时。
     */
    public void recordDuration(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.duration")
                .register(registry));
    }

    /**
     * 记录一次请求的 Token 用量（输入 + 输出），按模型名分 tag。
     */
    public void recordTokens(String model, long inputTokens, long outputTokens) {
        Counter.builder("agent.llm.tokens.input")
                .tag("model", model)
                .register(registry)
                .increment(inputTokens);
        Counter.builder("agent.llm.tokens.output")
                .tag("model", model)
                .register(registry)
                .increment(outputTokens);
    }

    /**
     * 记录流式输出的 token 速率（tokens/s），由 Gauge 自动读取最新值。
     */
    public void recordTokenSpeed(long tokensPerSecond) {
        tokenSpeed.set(tokensPerSecond);
    }

    /**
     * 记录 LLM 调用错误，按错误类型分类。
     */
    public void recordLlmError(String errorType) {
        Counter.builder("agent.llm.errors")
                .tag("error_type", errorType)
                .register(registry)
                .increment();
    }

    /**
     * 记录 LLM 调用重试次数。
     */
    public void recordLlmRetry() {
        Counter.builder("agent.llm.retries")
                .register(registry)
                .increment();
    }

    /**
     * 更新聊天记忆监控指标。
     * 使用聚合 gauge（总消息数、总大小）替代按 userId 拆分，避免高基数标签导致内存爆炸。
     */
    public void updateMemoryGauge(String userId, int messageCount, long sizeBytes) {
        // 按 userId 存储最新值，用于计算聚合
        memoryMessages.computeIfAbsent(userId, k -> new AtomicLong()).set(messageCount);
        memorySizes.computeIfAbsent(userId, k -> new AtomicLong()).set(sizeBytes);

        // 注册聚合 gauge（仅注册一次，后续自动读取最新聚合值）
        Gauge.builder("agent.memory.total_messages",
                        memoryMessages, m -> m.values().stream().mapToLong(AtomicLong::get).sum())
                .register(registry);
        Gauge.builder("agent.memory.total_size_bytes",
                        memorySizes, m -> m.values().stream().mapToLong(AtomicLong::get).sum())
                .register(registry);
        Gauge.builder("agent.memory.active_users",
                        memoryMessages, ConcurrentHashMap::size)
                .register(registry);
    }
}
