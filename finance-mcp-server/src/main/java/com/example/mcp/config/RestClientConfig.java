package com.example.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${finance.backend.url}")
    private String backendUrl;

    @Bean
    public RestClient.Builder restClientBuilder() {
        var requestFactorySettings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(backendUrl)
                .requestFactory(ClientHttpRequestFactories.get(requestFactorySettings));
    }

    @Bean
    public RestClient financeRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
