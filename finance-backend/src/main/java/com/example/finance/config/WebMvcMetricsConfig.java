package com.example.finance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 【Micrometer 指标拦截器注册】
 *
 * <p>将 {@link MetricsInterceptor} 注册到 Spring MVC 拦截器链，
 * 拦截所有 {@code /api/**} 请求，统计请求计数、响应耗时和错误率。
 */
@Configuration
public class WebMvcMetricsConfig implements WebMvcConfigurer {

    private final MetricsInterceptor metricsInterceptor;

    public WebMvcMetricsConfig(MetricsInterceptor metricsInterceptor) {
        this.metricsInterceptor = metricsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(metricsInterceptor)
                .addPathPatterns("/api/**");
    }
}
