package com.example.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class BackendClientConfig {

    @Bean
    public RestClient backendRestClient(
            @Value("${finance.backend.url:http://localhost:8080}") String backendUrl) {
        var requestFactorySettings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(backendUrl)
                .requestFactory(ClientHttpRequestFactories.get(requestFactorySettings))
                .build();
    }
}
