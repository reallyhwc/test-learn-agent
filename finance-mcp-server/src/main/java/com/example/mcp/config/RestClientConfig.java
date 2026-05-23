package com.example.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${finance.backend.url}")
    private String backendUrl;

    @Bean
    public RestClient financeRestClient() {
        return RestClient.create(backendUrl);
    }
}
