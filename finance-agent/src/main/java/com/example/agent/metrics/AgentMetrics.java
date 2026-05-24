package com.example.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final AtomicLong tokenSpeed = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> memoryMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> memorySizes = new ConcurrentHashMap<>();

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("agent.llm.tokens.speed", tokenSpeed, AtomicLong::get)
                .register(registry);
    }

    public void recordChatRequest(String userId, String type) {
        // 不再将 userId 作为标签，避免高基数导致 Prometheus 指标爆炸
        Counter.builder("agent.chat.requests")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordTtft(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.ttft")
                .register(registry));
    }

    public void recordDuration(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.duration")
                .register(registry));
    }

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

    public void recordTokenSpeed(long tokensPerSecond) {
        tokenSpeed.set(tokensPerSecond);
    }

    public void recordLlmError(String errorType) {
        Counter.builder("agent.llm.errors")
                .tag("error_type", errorType)
                .register(registry)
                .increment();
    }

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
