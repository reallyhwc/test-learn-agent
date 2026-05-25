package com.example.agent.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简易熔断器，用于保护对下游服务的调用。
 * <p>
 * 状态机: CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN
 * <ul>
 *   <li>CLOSED: 正常放行，连续失败达到阈值后进入 OPEN</li>
 *   <li>OPEN: 拒绝所有调用，等待恢复时间窗口后进入 HALF_OPEN</li>
 *   <li>HALF_OPEN: 放行一次试探，成功则回到 CLOSED，失败则回到 OPEN</li>
 * </ul>
 */
public class SimpleCircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long recoveryTimeoutMs;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    /**
     * @param name             熔断器名称（用于日志）
     * @param failureThreshold 连续失败次数阈值，达到后熔断
     * @param recoveryTimeoutMs 熔断后等待恢复的时间窗口（毫秒）
     */
    public SimpleCircuitBreaker(String name, int failureThreshold, long recoveryTimeoutMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.recoveryTimeoutMs = recoveryTimeoutMs;
    }

    /**
     * 判断是否允许调用。
     * CLOSED/HALF_OPEN 状态允许，OPEN 状态需检查是否已过恢复时间。
     */
    public boolean isCallPermitted() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - lastFailureTime.get() >= recoveryTimeoutMs) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            case HALF_OPEN:
                return true;
            default:
                return true;
        }
    }

    /** 记录成功调用，重置失败计数并关闭熔断。 */
    public void recordSuccess() {
        failureCount.set(0);
        state = State.CLOSED;
    }

    /** 记录失败调用，累计达到阈值后打开熔断。 */
    public void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold) {
            state = State.OPEN;
        }
    }

    public String getName() {
        return name;
    }

    public State getState() {
        return state;
    }
}
