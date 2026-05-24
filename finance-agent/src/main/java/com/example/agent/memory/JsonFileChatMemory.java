package com.example.agent.memory;

import com.example.agent.metrics.AgentMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class JsonFileChatMemory implements ChatMemory {
    private static final int DEFAULT_MAX_MESSAGES = 20;

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final String dataDir;
    private final int maxMessages;
    private final AgentMetrics agentMetrics;

    public JsonFileChatMemory(String dataDir) {
        this(dataDir, DEFAULT_MAX_MESSAGES, null);
    }

    public JsonFileChatMemory(String dataDir, int maxMessages, AgentMetrics agentMetrics) {
        this.dataDir = dataDir;
        this.maxMessages = maxMessages;
        this.agentMetrics = agentMetrics;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        new File(dataDir).mkdirs();
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        store.computeIfAbsent(conversationId, k -> Collections.synchronizedList(new ArrayList<>()))
                .addAll(messages);
        trimAndPersist(conversationId);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> cached = store.get(conversationId);
        if (cached == null) {
            cached = loadFromFile(conversationId);
            if (!cached.isEmpty()) {
                store.put(conversationId, Collections.synchronizedList(new ArrayList<>(cached)));
            }
        }
        return cached != null ? new ArrayList<>(cached) : List.of();
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
        File file = getFile(conversationId);
        if (file.exists()) file.delete();
    }

    private void trimAndPersist(String conversationId) {
        List<Message> messages = store.get(conversationId);
        if (messages == null) return;
        // Keep only the last maxMessages
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
        persistToFile(conversationId, messages);

        // 更新 Context 监控指标
        if (agentMetrics != null) {
            List<Message> currentMessages = store.get(conversationId);
            if (currentMessages != null) {
                int count;
                synchronized (currentMessages) {
                    count = currentMessages.size();
                }
                long fileSize = getFile(conversationId).length();
                agentMetrics.updateMemoryGauge(conversationId, count, fileSize);
            }
        }
    }

    private List<Message> loadFromFile(String conversationId) {
        File file = getFile(conversationId);
        if (!file.exists()) return List.of();
        try {
            List<ChatHistoryItem> items = objectMapper.readValue(file,
                    new TypeReference<List<ChatHistoryItem>>() {});
            List<Message> messages = new ArrayList<>();
            for (ChatHistoryItem item : items) {
                if ("USER".equals(item.getRole())) {
                    messages.add(new UserMessage(item.getText()));
                } else if ("ASSISTANT".equals(item.getRole())) {
                    messages.add(new AssistantMessage(item.getText()));
                }
            }
            return messages;
        } catch (IOException e) {
            log.warn("Failed to load chat memory for {}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    private void persistToFile(String conversationId, List<Message> messages) {
        try {
            List<ChatHistoryItem> items = new ArrayList<>();
            synchronized (messages) {
                for (Message msg : messages) {
                    String role = msg instanceof UserMessage ? "USER" : "ASSISTANT";
                    items.add(new ChatHistoryItem(role, msg.getText()));
                }
            }
            objectMapper.writeValue(getFile(conversationId), items);
        } catch (IOException e) {
            log.warn("Failed to persist chat memory for {}: {}", conversationId, e.getMessage());
        }
    }

    private File getFile(String conversationId) {
        return new File(dataDir, conversationId + ".json");
    }
}
