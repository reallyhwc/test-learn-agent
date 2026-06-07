package com.example.agent.multiagent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * HITL 待确认操作暂存 — confirmationId 映射 PendingCall。
 *
 * <p>get() 后自动移除（drain 语义），防止重复确认。
 * <p>每 10s 清理过期项（TTL 60s）。
 */
@Component
public class PendingConfirmationStore {

    private static final long TTL_SECONDS = 60;

    private final ConcurrentHashMap<String, PendingCall> store = new ConcurrentHashMap<>();

    public record PendingCall(
            String confirmationId,
            String toolName,
            Map<String, Object> parameters,
            String userId,
            String sessionId,
            Instant expiresAt) {
    }

    public String save(String toolName, Map<String, Object> params, String userId) {
        return save(toolName, params, userId, null);
    }

    public String save(String toolName, Map<String, Object> params, String userId, String sessionId) {
        String id = UUID.randomUUID().toString();
        PendingCall call = new PendingCall(
                id, toolName, params, userId, sessionId,
                Instant.now().plusSeconds(TTL_SECONDS));
        store.put(id, call);
        return id;
    }

    /**
     * 取出待确认操作并删除（drain 语义）。
     */
    public Optional<PendingCall> get(String confirmationId) {
        PendingCall call = store.remove(confirmationId);
        if (call == null) return Optional.empty();
        if (Instant.now().isAfter(call.expiresAt())) return Optional.empty();
        return Optional.of(call);
    }

    public void remove(String confirmationId) {
        store.remove(confirmationId);
    }

    @Scheduled(fixedDelay = 10_000)
    public void evictExpired() {
        Instant now = Instant.now();
        store.values().removeIf(call -> now.isAfter(call.expiresAt()));
    }
}
