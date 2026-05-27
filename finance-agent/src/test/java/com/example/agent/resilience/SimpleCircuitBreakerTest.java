package com.example.agent.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SimpleCircuitBreaker 单元测试 — 通过行为验证状态转换。
 * <p>
 * State 枚举是 private 的，因此通过 isCallPermitted() 行为来验证状态。
 */
class SimpleCircuitBreakerTest {

    @Test
    void shouldStartPermittingCalls() {
        var breaker = new SimpleCircuitBreaker("test", 3, 1000);
        assertThat(breaker.isCallPermitted()).isTrue();
    }

    @Test
    void shouldPermitCallsWhenFailuresBelowThreshold() {
        var breaker = new SimpleCircuitBreaker("test", 3, 1000);
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isCallPermitted()).isTrue();
    }

    @Test
    void shouldRejectCallsAfterReachingFailureThreshold() {
        var breaker = new SimpleCircuitBreaker("test", 3, 1000);
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isCallPermitted()).isFalse();
    }

    @Test
    void shouldPermitOneCallAfterRecoveryTimeout() throws InterruptedException {
        var breaker = new SimpleCircuitBreaker("test", 1, 50);
        breaker.recordFailure();
        assertThat(breaker.isCallPermitted()).isFalse();

        Thread.sleep(100);
        // 超时后应允许一次试探调用（HALF_OPEN）
        assertThat(breaker.isCallPermitted()).isTrue();
    }

    @Test
    void shouldPermitCallsAfterSuccessfulProbe() throws InterruptedException {
        var breaker = new SimpleCircuitBreaker("test", 1, 50);
        breaker.recordFailure();
        Thread.sleep(100);
        breaker.isCallPermitted(); // OPEN → HALF_OPEN
        breaker.recordSuccess();   // HALF_OPEN → CLOSED
        assertThat(breaker.isCallPermitted()).isTrue();
    }

    @Test
    void shouldRejectCallsAfterFailedProbe() throws InterruptedException {
        var breaker = new SimpleCircuitBreaker("test", 1, 50);
        breaker.recordFailure();
        Thread.sleep(100);
        breaker.isCallPermitted(); // OPEN → HALF_OPEN
        breaker.recordFailure();   // HALF_OPEN → OPEN
        assertThat(breaker.isCallPermitted()).isFalse();
    }

    @Test
    void shouldResetFailureCountOnSuccess() {
        var breaker = new SimpleCircuitBreaker("test", 3, 1000);
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        // 重新计数，再失败2次不应触发熔断
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isCallPermitted()).isTrue();
    }

    @Test
    void shouldReturnCorrectName() {
        var breaker = new SimpleCircuitBreaker("my-breaker", 3, 1000);
        assertThat(breaker.getName()).isEqualTo("my-breaker");
    }
}
