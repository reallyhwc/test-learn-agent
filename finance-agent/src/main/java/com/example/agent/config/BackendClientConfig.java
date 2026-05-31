package com.example.agent.config;

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
 * Agent → Backend 的 RestClient 配置。
 * 使用 Apache HttpClient 5 连接池复用 TCP 连接，减少握手开销。
 */
@Configuration
public class BackendClientConfig {

    /**
     * 创建连接 Backend 的 {@link RestClient} Bean。
     *
     * <p>配置说明：
     * <ul>
     *   <li>连接池 maxTotal=20, maxPerRoute=10 — Agent 到 Backend 是低频查询（账户摘要），不需要大连接池</li>
     *   <li>连接超时 3s — 本地服务建立连接很快</li>
     *   <li>响应超时 10s — Backend CSV 查询在 10s 内完成</li>
     * </ul>
     *
     * @param backendUrl Backend 服务地址（从配置项 {@code finance.backend.url} 注入）
     * @return 配置好的 RestClient 实例
     */
    @Bean
    public RestClient backendRestClient(
            @Value("${finance.backend.url:http://localhost:8080}") String backendUrl) {
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
                .requestFactory(requestFactory)
                .build();
    }
}
