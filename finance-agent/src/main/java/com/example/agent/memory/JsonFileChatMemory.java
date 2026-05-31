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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 【对话记忆的 JSON 文件持久化实现】
 *
 * <p>实现了 Spring AI 的 {@link ChatMemory} 接口，通过
 * {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}
 * 在 Advisor 链的 before/after 阶段自动读写对话历史。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li><b>持久化</b>：每条对话存储为 {@code data/memory/{conversationId}.json}，conversationId 即 userId</li>
 *   <li><b>条数截断</b>：最多保留 20 条消息（可按需配置）</li>
 *   <li><b>Token 估算截断</b>：总 token 超 4000 时继续移除最早消息</li>
 *   <li><b>LRU 内存缓存</b>：最多 200 个用户会话，超限淘汰最久未访问的</li>
 *   <li><b>异步持久化</b>：通过 CompletableFuture 异步写文件，不阻塞请求线程</li>
 *   <li><b>安全校验</b>：conversationId 仅允许 {@code [a-zA-Z0-9_-]{1,64}}，防路径穿越</li>
 * </ul>
 *
 * @see com.example.agent.config.ChatMemoryConfig Bean 注册配置
 */
@Slf4j
public class JsonFileChatMemory implements ChatMemory {
    /** 默认每会话最大消息数 */
    private static final int DEFAULT_MAX_MESSAGES = 20;
    /** 内存中最多缓存的用户会话数，超过后按 LRU 淘汰最久未访问的会话 */
    private static final int MAX_CACHED_CONVERSATIONS = 200;
    /** 仅允许字母、数字、下划线、短横线，防止路径穿越攻击 */
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** LRU 缓存：超过 MAX_CACHED_CONVERSATIONS 时自动淘汰最久未访问的条目，防止 OOM */
    private final Map<String, List<Message>> store = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Message>> eldest) {
                    boolean shouldRemove = size() > MAX_CACHED_CONVERSATIONS;
                    if (shouldRemove) {
                        log.info("LRU 淘汰会话缓存: {}", eldest.getKey());
                    }
                    return shouldRemove;
                }
            });
    private final ObjectMapper objectMapper;
    private final String dataDir;
    private final int maxMessages;
    private final AgentMetrics agentMetrics;

    /**
     * @param dataDir 记忆文件存储目录（默认 {@code data/memory}）
     */
    public JsonFileChatMemory(String dataDir) {
        this(dataDir, DEFAULT_MAX_MESSAGES, null);
    }

    /**
     * @param dataDir 存储目录
     * @param maxMessages 每会话最大消息数（默认 20）
     * @param agentMetrics 指标采集对象（可为 null，跳过指标更新）
     */
    public JsonFileChatMemory(String dataDir, int maxMessages, AgentMetrics agentMetrics) {
        this.dataDir = dataDir;
        this.maxMessages = maxMessages;
        this.agentMetrics = agentMetrics;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        new File(dataDir).mkdirs();
    }

    /**
     * 追加消息到指定会话。add + trim + persist 在同一个 synchronized 块内完成，防止并发竞态。
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        // 整个 add + trim + persist 在同一个 synchronized 块内完成，防止并发竞态丢消息
        synchronized (store) {
            List<Message> list = store.computeIfAbsent(conversationId, k -> new ArrayList<>());
            list.addAll(messages);
            trimAndPersist(conversationId, list);
        }
    }

    /**
     * 读取指定会话的全部历史消息。优先从 LRU 缓存读取，未命中时从 JSON 文件加载。
     */
    @Override
    public List<Message> get(String conversationId) {
        synchronized (store) {
            List<Message> cached = store.get(conversationId);
            if (cached == null) {
                cached = loadFromFile(conversationId);
                if (!cached.isEmpty()) {
                    store.put(conversationId, new ArrayList<>(cached));
                }
            }
            return cached != null ? new ArrayList<>(cached) : new ArrayList<>();
        }
    }

    /**
     * 清除指定会话的全部记忆（缓存 + 磁盘文件）。
     */
    @Override
    public void clear(String conversationId) {
        synchronized (store) {
            store.remove(conversationId);
        }
        File file = getFile(conversationId);
        if (file.exists()) file.delete();
    }

    /** 粗估 token 上限：中文约 2 字符/token，超出后从头部移除最早消息 */
    private static final int MAX_ESTIMATED_TOKENS = 4000;

    private void trimAndPersist(String conversationId, List<Message> messages) {
        // 调用方已持有 store 锁，此处无需再加锁

        // 1. 条数截断（原有逻辑）
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }

        // 2. token 估算截断：防止少量超长消息撑爆 LLM 上下文
        while (messages.size() > 1 && estimateTokens(messages) > MAX_ESTIMATED_TOKENS) {
            messages.remove(0);
        }

        // 异步持久化：避免同步磁盘 IO 阻塞请求线程
        int messageCount = messages.size();
        List<Message> snapshot = new ArrayList<>(messages);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            persistToFile(conversationId, snapshot);
            // 写完后再更新文件大小指标，保证读到的是最新值
            if (agentMetrics != null) {
                long fileSize = getFile(conversationId).length();
                agentMetrics.updateMemoryGauge(conversationId, messageCount, fileSize);
            }
        });
    }

    /** 粗估消息列表的 token 数（中文约 2 字符/token） */
    private int estimateTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message msg : messages) {
            String text = msg.getText();
            if (text != null) {
                totalChars += text.length();
            }
        }
        return totalChars / 2;
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
            // 调用方已持有 store 锁，无需再对 messages 加锁
            List<ChatHistoryItem> items = new ArrayList<>();
            for (Message msg : messages) {
                String role = msg instanceof UserMessage ? "USER" : "ASSISTANT";
                items.add(new ChatHistoryItem(role, msg.getText()));
            }
            objectMapper.writeValue(getFile(conversationId), items);
        } catch (IOException e) {
            log.warn("Failed to persist chat memory for {}: {}", conversationId, e.getMessage());
        }
    }

    private File getFile(String conversationId) {
        validateConversationId(conversationId);
        return new File(dataDir, conversationId + ".json");
    }

    /**
     * 校验 conversationId 防止路径穿越攻击。
     * 仅允许字母、数字、下划线、短横线，长度 1-64。
     */
    private void validateConversationId(String conversationId) {
        if (conversationId == null || !SAFE_ID_PATTERN.matcher(conversationId).matches()) {
            throw new IllegalArgumentException(
                    "非法的会话ID，仅允许字母、数字、下划线和短横线: " + conversationId);
        }
    }
}
