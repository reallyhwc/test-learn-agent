package com.example.agent.controller;

import com.example.agent.config.LlmCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(LlmCondition.class)
abstract class ChatEndpointTestBase {

    static {
        loadDotEnv();
    }

    private static void loadDotEnv() {
        String[] paths = {"../.env", ".env"};
        for (String path : paths) {
            try (FileInputStream in = new FileInputStream(path)) {
                Properties props = new Properties();
                props.load(in);
                props.forEach((key, value) -> {
                    String envKey = key.toString();
                    String envValue = value.toString().trim();
                    if (!envValue.isEmpty()) {
                        System.setProperty(envKey, envValue);
                    }
                });
                return;
            } catch (FileNotFoundException ignored) {
            } catch (IOException e) {
                System.err.println("[ChatEndpointTestBase] 读取 .env 失败: " + e.getMessage());
            }
        }
    }

    @Autowired
    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected String buildRequestBody(String userId, String message) throws Exception {
        return objectMapper.writeValueAsString(Map.of("userId", userId, "message", message));
    }

    protected String chatAndGetReply(String userId, String message) throws Exception {
        String json = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequestBody(userId, message)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractReply(json);
    }

    protected String extractReply(String json) throws Exception {
        return objectMapper.readTree(json).get("reply").asText();
    }

    protected String extractSseContent(String sseText) {
        StringBuilder sb = new StringBuilder();
        for (String line : sseText.split("\n")) {
            if (line.startsWith("data:")) {
                String data = line.substring(5);
                if (data.startsWith(" ")) data = data.substring(1);
                sb.append(data);
            }
        }
        return sb.toString();
    }

    protected String streamAndGetContent(String endpoint, String userId, String message) throws Exception {
        String raw = streamAndGetRaw(endpoint, userId, message);
        return extractSseContent(raw);
    }

    protected String streamAndGetRaw(String endpoint, String userId, String message) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post(endpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRequestBody(userId, message)))
                .andExpect(request().asyncStarted())
                .andReturn();

        return mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
