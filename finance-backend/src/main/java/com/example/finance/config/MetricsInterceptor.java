package com.example.finance.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

@Component
public class MetricsInterceptor implements HandlerInterceptor {

    private final MeterRegistry registry;

    public MetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        request.setAttribute("startTime", System.nanoTime());
        return true;
    }

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
