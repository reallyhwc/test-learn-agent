package com.example.finance.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 【API 请求指标拦截器】
 *
 * <p>基于 Micrometer 记录每个 API 请求的三个核心指标：
 * <ul>
 *   <li>{@code api.requests.total} — 请求计数（按 endpoint、method、status 分 tag）</li>
 *   <li>{@code api.requests.duration} — 请求耗时分布（纳秒精度）</li>
 *   <li>{@code api.requests.errors} — 请求错误计数（按 error_type 分 tag）</li>
 * </ul>
 *
 * <p>端点路径中包含数字 ID 的部分会被归一化为 {@code {id}}，避免指标基数爆炸。
 */
@Component
public class MetricsInterceptor implements HandlerInterceptor {

    /** Micrometer 指标注册中心 */
    private final MeterRegistry registry;

    public MetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 请求到达时记录开始时间（纳秒），存入 request attribute 供 {@link #afterCompletion} 计算耗时。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        request.setAttribute("startTime", System.nanoTime());
        return true;
    }

    /**
     * 请求完成后记录指标：若 preHandle 未执行则跳过；
     * 否则记录请求计数（Counter）、请求耗时（Timer）、错误计数（如 HTTP status ≥ 400）。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object startTimeAttr = request.getAttribute("startTime");
        if (startTimeAttr == null) return; // preHandle 未执行（如被其他过滤器拦截），跳过指标记录
        long startTime = (long) startTimeAttr;
        long durationNs = System.nanoTime() - startTime;
        String endpoint = request.getRequestURI().replaceAll("/\\d+", "/{id}");

        Counter.builder("api.requests.total")
                .tag("endpoint", endpoint)
                .tag("method", request.getMethod())
                .tag("status", String.valueOf(response.getStatus()))
                .register(registry)
                .increment();

        Timer.builder("api.requests.duration")
                .tag("endpoint", endpoint)
                .tag("method", request.getMethod())
                .register(registry)
                .record(durationNs, TimeUnit.NANOSECONDS);

        if (ex != null || response.getStatus() >= 400) {
            Counter.builder("api.requests.errors")
                    .tag("endpoint", endpoint)
                    .tag("error_type", ex != null ? ex.getClass().getSimpleName() : String.valueOf(response.getStatus()))
                    .register(registry)
                    .increment();
        }
    }
}
