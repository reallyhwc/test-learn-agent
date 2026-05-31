package com.example.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 【Web MVC 跨域配置】
 *
 * <p>允许前端（Vite :5173、:5174）通过 SSE 跨域访问 Agent API。
 * 配置项 {@code cors.allowed-origins} 可在 application.yml 中覆盖。
 */
@Configuration
public class WebConfig {

    /** 跨域允许的来源地址，多个用英文逗号分隔 */
    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private String allowedOrigins;

    /**
     * 注册跨域映射规则：允许 {@code /api/**} 路径的 GET/POST/PUT/DELETE/OPTIONS 请求，
     * 支持携带 Cookie/Authorization 等凭据。
     *
     * @return WebMvcConfigurer 实例
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins.split(","))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
