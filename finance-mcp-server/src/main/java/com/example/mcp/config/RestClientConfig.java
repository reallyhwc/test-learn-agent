package com.example.mcp.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * MCP Server → Backend 的 RestClient 配置。
 * 使用 Apache HttpClient 5 连接池复用 TCP 连接，减少握手开销。
 */
@Configuration
public class RestClientConfig {

    /**
     * Backend 服务的基础 URL。
     * 从配置项 {@code finance.backend.url} 注入（application.yml 中默认 http://localhost:8080）。
     */
    @Value("${finance.backend.url}")
    private String backendUrl;

    /**
     * 创建带连接池和超时配置的 {@link RestClient.Builder}。
     *
     * <p>配置说明：
     * <ul>
     *   <li>连接池 maxTotal=20, maxPerRoute=10 — MCP 工具调用是低频同步请求，不需要大连接池</li>
     *   <li>连接超时 3s — Backend 是本地服务，建立连接很快</li>
     *   <li>响应超时 10s — 最慢的 CSV 全表扫描场景下也在 10s 内完成</li>
     * </ul>
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        var connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(20);
        connectionManager.setDefaultMaxPerRoute(10);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                        .setResponseTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();

        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

        return RestClient.builder()
                .baseUrl(backendUrl)
                .requestFactory(requestFactory);
    }

    /**
     * 构建最终的 {@link RestClient} Bean，供 {@link com.example.mcp.tool.FinanceTools} 注入使用。
     */
    @Bean
    public RestClient financeRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
