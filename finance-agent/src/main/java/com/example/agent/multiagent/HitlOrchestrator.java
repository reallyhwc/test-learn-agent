package com.example.agent.multiagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * HITL 编排器 — 封装「写操作 → 待确认 → 确认执行 → 落库」闭环。
 *
 * <p>单一职责：advisor 识别写操作后调用 {@link #pauseForConfirmation} 暂停并生成
 * 待确认项；confirm/cancel 端点调用 {@link #confirm} / {@link #cancel} 推进状态机。
 * advisor 与端点都不直接操作 {@link PendingConfirmationStore}，保证状态机语义
 * 收敛在此处，可独立测试。
 */
@Slf4j
@Component
public class HitlOrchestrator {

    /** 需要 HITL 确认的写操作工具集合。 */
    private static final Set<String> WRITE_TOOLS = Set.of("add_transaction");

    private final PendingConfirmationStore store;
    private final RestClient backendRestClient;

    public HitlOrchestrator(PendingConfirmationStore store, RestClient backendRestClient) {
        this.store = store;
        this.backendRestClient = backendRestClient;
    }

    public boolean isWriteTool(String toolName) {
        return WRITE_TOOLS.contains(toolName);
    }

    /**
     * 暂停写操作：生成待确认项（PENDING），返回 confirmationId。
     */
    public String pauseForConfirmation(String toolName, Map<String, Object> params,
                                       String userId, String sessionId) {
        String id = store.save(toolName, params, userId, sessionId);
        log.info("HITL pause: confirmationId={}, tool={}, userId={}", id, toolName, userId);
        return id;
    }

    /**
     * 确认执行结果。
     */
    public enum ConfirmStatus {
        /** 抢占成功并执行完成。 */
        EXECUTED,
        /** 状态已非 PENDING（已被确认/取消/过期），无法推进。 */
        NOT_PENDING,
        /** confirmationId 不存在。 */
        NOT_FOUND,
        /** 已过期。 */
        EXPIRED
    }

    public record ConfirmOutcome(ConfirmStatus status, Object result, String message) {
        public static ConfirmOutcome executed(Object result) {
            return new ConfirmOutcome(ConfirmStatus.EXECUTED, result, "操作已确认执行");
        }

        public static ConfirmOutcome of(ConfirmStatus status, String message) {
            return new ConfirmOutcome(status, null, message);
        }
    }

    /**
     * 确认：CAS 抢占 PENDING→EXECUTING，成功则同步执行工具落地，随后置 DONE。
     */
    public ConfirmOutcome confirm(String confirmationId, Map<String, Object> modifiedParams) {
        var lookup = store.lookup(confirmationId);
        if (lookup.lookup() == PendingConfirmationStore.Lookup.NOT_FOUND) {
            return ConfirmOutcome.of(ConfirmStatus.NOT_FOUND, "确认请求不存在");
        }
        if (lookup.lookup() == PendingConfirmationStore.Lookup.EXPIRED) {
            return ConfirmOutcome.of(ConfirmStatus.EXPIRED, "确认请求已过期");
        }

        boolean won = store.transition(confirmationId,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.EXECUTING);
        if (!won) {
            return ConfirmOutcome.of(ConfirmStatus.NOT_PENDING, "该操作已被确认或取消");
        }

        PendingConfirmationStore.PendingCall call = store.lookup(confirmationId).call();
        try {
            Object result = executeTool(call, modifiedParams);
            store.transition(confirmationId,
                    PendingConfirmationStore.Status.EXECUTING,
                    PendingConfirmationStore.Status.DONE);
            return ConfirmOutcome.executed(result);
        } catch (Exception e) {
            log.warn("HITL 执行工具失败 confirmationId={}: {}", confirmationId, e.getMessage());
            store.transition(confirmationId,
                    PendingConfirmationStore.Status.EXECUTING,
                    PendingConfirmationStore.Status.CANCELLED);
            return ConfirmOutcome.of(ConfirmStatus.NOT_PENDING, "工具执行失败: " + e.getMessage());
        }
    }

    public enum CancelStatus {
        CANCELLED,
        NOT_PENDING,
        NOT_FOUND,
        EXPIRED
    }

    public record CancelOutcome(CancelStatus status, String message) {
        public static CancelOutcome of(CancelStatus status, String message) {
            return new CancelOutcome(status, message);
        }
    }

    /**
     * 取消：CAS 抢占 PENDING→CANCELLED。
     */
    public CancelOutcome cancel(String confirmationId) {
        var lookup = store.lookup(confirmationId);
        if (lookup.lookup() == PendingConfirmationStore.Lookup.NOT_FOUND) {
            return CancelOutcome.of(CancelStatus.NOT_FOUND, "确认请求不存在");
        }
        if (lookup.lookup() == PendingConfirmationStore.Lookup.EXPIRED) {
            return CancelOutcome.of(CancelStatus.EXPIRED, "确认请求已过期");
        }
        boolean won = store.transition(confirmationId,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.CANCELLED);
        if (!won) {
            return CancelOutcome.of(CancelStatus.NOT_PENDING, "该操作已被确认或取消");
        }
        return CancelOutcome.of(CancelStatus.CANCELLED, "操作已取消");
    }

    public PendingConfirmationStore.LookupResult lookup(String confirmationId) {
        return store.lookup(confirmationId);
    }

    /**
     * 真正执行写操作：调用 backend `POST /api/transactions` 落地。
     */
    private Object executeTool(PendingConfirmationStore.PendingCall call,
                               Map<String, Object> modifiedParams) {
        Map<String, Object> params = modifiedParams != null && !modifiedParams.isEmpty()
                ? modifiedParams
                : call.parameters();

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("userId", call.userId());
        if (params.get("accountId") != null) body.put("accountId", params.get("accountId"));
        if (params.get("type") != null) body.put("type", params.get("type"));
        if (params.get("amount") != null) body.put("amount", params.get("amount"));
        if (params.get("category") != null) body.put("category", params.get("category"));
        if (params.get("subCategory") != null) body.put("subCategory", params.get("subCategory"));
        if (params.get("note") != null) body.put("note", params.get("note"));
        if (params.get("date") != null) body.put("date", params.get("date"));

        log.info("HITL execute: tool={}, userId={}, params={}", call.toolName(), call.userId(), body);

        return backendRestClient.post()
                .uri("/api/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
    }
}
