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
        Counter.builder("agent.chat.requests")
                .tag("userId", userId)
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordTtft(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.ttft")
                .tag("userId", userId)
                .register(registry));
    }

    public void recordDuration(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.duration")
                .tag("userId", userId)
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

    public void updateMemoryGauge(String userId, int messageCount, long sizeBytes) {
        memoryMessages.computeIfAbsent(userId, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("agent.memory.messages", g, AtomicLong::get)
                    .tag("userId", userId)
                    .register(registry);
            return g;
        }).set(messageCount);

        memorySizes.computeIfAbsent(userId, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("agent.memory.size_bytes", g, AtomicLong::get)
                    .tag("userId", userId)
                    .register(registry);
            return g;
        }).set(sizeBytes);
    }
}
