package com.example.agent.resilience;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单的熔断器实现。
 * 当连续失败次数超过阈值时，进入 OPEN 状态拒绝请求，
 * 经过恢复窗口后自动进入 HALF_OPEN 允许试探性请求。
 */
@Slf4j
public class SimpleCircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long recoveryWindowMs;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public SimpleCircuitBreaker(String name, int failureThreshold, long recoveryWindowMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.recoveryWindowMs = recoveryWindowMs;
    }

    public boolean isCallPermitted() {
        if (state == State.CLOSED) return true;
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime.get() > recoveryWindowMs) {
                state = State.HALF_OPEN;
                log.info("CircuitBreaker [{}] -> HALF_OPEN", name);
                return true;
            }
            return false;
        }
        // HALF_OPEN: 允许一个试探请求
        return true;
    }

    public void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("CircuitBreaker [{}] -> CLOSED", name);
        }
        state = State.CLOSED;
        failureCount.set(0);
    }

    public void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        if (failureCount.incrementAndGet() >= failureThreshold) {
            if (state != State.OPEN) {
                log.warn("CircuitBreaker [{}] -> OPEN (failures={})", name, failureCount.get());
            }
            state = State.OPEN;
        }
    }

    public String getState() { return state.name(); }
}
