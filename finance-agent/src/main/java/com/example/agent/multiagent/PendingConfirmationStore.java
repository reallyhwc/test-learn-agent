package com.example.agent.multiagent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * HITL 待确认操作状态机 — 6 态 + CAS 原子抢占。
 *
 * <p>状态机状态：
 * <pre>
 *   IDLE ──(save)──► PENDING ──(confirm CAS)──► EXECUTING ──(同步收尾)──► DONE
 *                            ├──(cancel CAS)──► CANCELLED
 *                            └──(TTL 超时)────► EXPIRED
 * </pre>
 *
 * <h3>核心语义</h3>
 * <ul>
 *   <li><b>状态与存储解耦</b>：状态是 {@link PendingCall#status()} 字段；物理删除仅
 *       由 {@link #evictExpired()} 定时回收，不参与正确性判断。</li>
 *   <li><b>CAS 抢占</b>：{@link #transition(String, Status, Status)} 用
 *       {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}
 *       单原子操作完成「读旧状态 → 比对 → 写新状态」，保证 confirm/cancel 竞争时有且仅有一次成功。</li>
 *   <li><b>EXPIRED 显式暴露</b>：{@link #lookup(String)} 区分
 *       {@link Lookup#FOUND} / {@link Lookup#NOT_FOUND} / {@link Lookup#EXPIRED}，
 *       过期不再被静默吞成「不存在」。</li>
 * </ul>
 */
@Component
public class PendingConfirmationStore {

    /** 待确认操作的生命周期状态。 */
    public enum Status {
        PENDING,
        EXECUTING,
        DONE,
        CANCELLED,
        EXPIRED
    }

    /** 查询结果的分类。 */
    public enum Lookup {
        /** 存在且未过期（状态为 PENDING 或 EXECUTING）。 */
        FOUND,
        /** 从未存在，或已被物理清理。 */
        NOT_FOUND,
        /** 存在但已过期。 */
        EXPIRED
    }

    /**
     * 三态查询结果。
     *
     * @param lookup 查询分类
     * @param call   {@link Lookup#FOUND} 时非空，其余为 null
     */
    public record LookupResult(Lookup lookup, PendingCall call) {
        public static LookupResult found(PendingCall call) {
            return new LookupResult(Lookup.FOUND, call);
        }

        public static LookupResult notFound() {
            return new LookupResult(Lookup.NOT_FOUND, null);
        }

        public static LookupResult expired() {
            return new LookupResult(Lookup.EXPIRED, null);
        }
    }

    /**
     * 待确认调用。
     *
     * @param confirmationId 确认 ID（UUID）
     * @param toolName       写操作工具名（add_transaction）
     * @param parameters     工具参数
     * @param userId         所属用户
     * @param sessionId      会话 ID（可选）
     * @param expiresAt      过期时间
     * @param status         当前状态
     */
    public record PendingCall(
            String confirmationId,
            String toolName,
            Map<String, Object> parameters,
            String userId,
            String sessionId,
            Instant expiresAt,
            Status status) {

        public PendingCall withStatus(Status newStatus) {
            return new PendingCall(confirmationId, toolName, parameters, userId, sessionId, expiresAt, newStatus);
        }
    }

    private static final long TTL_SECONDS = 60;

    private final ConcurrentHashMap<String, PendingCall> store = new ConcurrentHashMap<>();

    public String save(String toolName, Map<String, Object> params, String userId) {
        return save(toolName, params, userId, null);
    }

    public String save(String toolName, Map<String, Object> params, String userId, String sessionId) {
        String id = UUID.randomUUID().toString();
        PendingCall call = new PendingCall(
                id, toolName, params, userId, sessionId,
                Instant.now().plusSeconds(TTL_SECONDS),
                Status.PENDING);
        store.put(id, call);
        return id;
    }

    /**
     * 三态查询：区分 FOUND / NOT_FOUND / EXPIRED。
     * 过期条目在查询时被就地标记为 EXPIRED（幂等，不影响正确性）。
     */
    public LookupResult lookup(String confirmationId) {
        PendingCall call = store.get(confirmationId);
        if (call == null) {
            return LookupResult.notFound();
        }
        if (Instant.now().isAfter(call.expiresAt())) {
            // 过期：显式暴露，并就地标记 EXPIRED（幂等）
            store.computeIfPresent(confirmationId, (k, v) -> v.withStatus(Status.EXPIRED));
            return LookupResult.expired();
        }
        return LookupResult.found(call);
    }

    /**
     * CAS 原子状态抢占。
     *
     * <p>仅当当前状态等于 {@code expected}（且未过期）时，才推进到 {@code target}。
     * 返回 {@code true} 表示本次调用者获得推进权；{@code false} 表示状态已变化或不存在。
     */
    public boolean transition(String confirmationId, Status expected, Status target) {
        final boolean[] changed = {false};
        store.compute(confirmationId, (k, current) -> {
            if (current == null) {
                return null; // 不存在，抢占失败
            }
            if (Instant.now().isAfter(current.expiresAt())) {
                // 已过期：标记 EXPIRED 并保持，抢占失败
                return current.withStatus(Status.EXPIRED);
            }
            if (current.status() == expected) {
                changed[0] = true;
                return current.withStatus(target);
            }
            return current; // 状态不匹配，保持不变，抢占失败
        });
        return changed[0];
    }

    public void remove(String confirmationId) {
        store.remove(confirmationId);
    }

    /**
     * 将指定条目强制置过期（仅测试用，模拟 TTL 超时）。
     */
    public void forceExpire(String confirmationId) {
        store.computeIfPresent(confirmationId, (k, v) ->
                new PendingCall(v.confirmationId(), v.toolName(), v.parameters(), v.userId(), v.sessionId(),
                        Instant.now().minusSeconds(1), v.status()));
    }

    /**
     * 定时清理已终止（EXPIRED / DONE / CANCELLED）或过期的条目，仅作内存回收。
     */
    @Scheduled(fixedDelay = 10_000)
    public void evictExpired() {
        Instant now = Instant.now();
        store.values().removeIf(call ->
                now.isAfter(call.expiresAt())
                        || call.status() == Status.DONE
                        || call.status() == Status.CANCELLED);
    }
}
