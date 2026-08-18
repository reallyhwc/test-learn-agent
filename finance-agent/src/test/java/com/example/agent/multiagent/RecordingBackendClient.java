package com.example.agent.multiagent;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A 层测试辅助：拦截 backend `POST /api/transactions`，记录 body 与调用次数，
 * 返回预置 JSON 响应，不真正发网络请求。
 */
class RecordingBackendClient {

    private final AtomicInteger postCount = new AtomicInteger(0);
    private final LinkedHashMap<String, Object> lastBody = new LinkedHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    RestClient restClient() {
        ClientHttpRequestFactory factory = (uri, httpMethod) -> new ClientHttpRequest() {
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();

            @Override
            public HttpMethod getMethod() {
                return httpMethod;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public OutputStream getBody() {
                return body;
            }

            @Override
            public ClientHttpResponse execute() throws IOException {
                if (httpMethod == HttpMethod.POST && uri.getPath().equals("/api/transactions")) {
                    postCount.incrementAndGet();
                    String json = body.toString(StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = json.isBlank()
                            ? new LinkedHashMap<>()
                            : (Map<String, Object>) MAPPER.readValue(json, Map.class);
                    synchronized (lastBody) {
                        lastBody.clear();
                        lastBody.putAll(parsed);
                    }
                }
                return stubResponse();
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public Map<String, Object> getAttributes() {
                return new LinkedHashMap<>();
            }
        };
        return RestClient.builder()
                .baseUrl("http://stub.local")
                .requestFactory(factory)
                .build();
    }

    private ClientHttpResponse stubResponse() {
        byte[] content = "{\"id\":1,\"status\":\"created\"}".getBytes(StandardCharsets.UTF_8);
        return new ClientHttpResponse() {
            @Override
            public org.springframework.http.HttpStatusCode getStatusCode() {
                return org.springframework.http.HttpStatus.CREATED;
            }

            @Override
            public String getStatusText() {
                return "Created";
            }

            @Override
            public void close() {
            }

            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(content);
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                return headers;
            }
        };
    }

    int postCount() {
        return postCount.get();
    }

    Map<String, Object> lastBody() {
        synchronized (lastBody) {
            return new LinkedHashMap<>(lastBody);
        }
    }
}
