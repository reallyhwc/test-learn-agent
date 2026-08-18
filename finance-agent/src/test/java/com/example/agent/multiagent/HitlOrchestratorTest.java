package com.example.agent.multiagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HitlOrchestrator 编排器测试（A 层进程内）。
 *
 * <p>验证「写操作 → 暂停 → 确认执行 → 落库」闭环，以及取消/过期/幂等。
 * backend client 用 mock 拦截，断言确认时才真正发出 POST /api/transactions。
 */
class HitlOrchestratorTest {

    private PendingConfirmationStore store;
    private RecordingBackendClient backend;
    private HitlOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        store = new PendingConfirmationStore();
        backend = new RecordingBackendClient();
        orchestrator = new HitlOrchestrator(store, backend.restClient());
    }

    @Test
    void shouldIdentifyWriteTool() {
        assertThat(orchestrator.isWriteTool("add_transaction")).isTrue();
        assertThat(orchestrator.isWriteTool("query_balance")).isFalse();
    }

    @Test
    void shouldPauseThenConfirmThenExecute() {
        String id = orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30, "category", "餐饮", "type", "EXPENSE"),
                "user-1", "session-1");

        assertThat(id).isNotBlank();
        assertThat(backend.postCount()).isZero(); // 暂停时不落库

        var outcome = orchestrator.confirm(id, null);

        assertThat(outcome.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.EXECUTED);
        assertThat(backend.postCount()).isEqualTo(1); // 确认后落库一次
        // 状态应推进到 DONE
        var state = store.lookup(id);
        assertThat(state.call().status()).isEqualTo(PendingConfirmationStore.Status.DONE);
    }

    @Test
    void shouldCancelWithoutExecuting() {
        String id = orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30), "user-1", null);

        var cancelled = orchestrator.cancel(id);
        assertThat(cancelled.status()).isEqualTo(HitlOrchestrator.CancelStatus.CANCELLED);
        assertThat(backend.postCount()).isZero(); // 取消不落库

        // 取消后确认应失败
        var outcome = orchestrator.confirm(id, null);
        assertThat(outcome.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.NOT_PENDING);
        assertThat(backend.postCount()).isZero();
    }

    @Test
    void shouldReturnNotFoundForUnknownConfirm() {
        var outcome = orchestrator.confirm("nonexistent", null);
        assertThat(outcome.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.NOT_FOUND);
    }

    @Test
    void shouldReturnExpiredForExpiredConfirm() {
        String id = orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30), "user-1", null);
        store.forceExpire(id);

        var outcome = orchestrator.confirm(id, null);
        assertThat(outcome.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.EXPIRED);
        assertThat(backend.postCount()).isZero();
    }

    @Test
    void shouldAllowOnlyOneConfirmToExecute() {
        String id = orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30), "user-1", null);

        var first = orchestrator.confirm(id, null);
        var second = orchestrator.confirm(id, null);

        assertThat(first.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.EXECUTED);
        assertThat(second.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.NOT_PENDING);
        assertThat(backend.postCount()).isEqualTo(1); // 只落库一次
    }

    @Test
    void shouldApplyModifiedParams() {
        String id = orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30, "category", "餐饮", "type", "EXPENSE"),
                "user-1", null);

        var outcome = orchestrator.confirm(id, Map.of("amount", 15, "category", "餐饮", "type", "EXPENSE"));

        assertThat(outcome.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.EXECUTED);
        // 落库金额应为修改后的值
        assertThat(backend.lastBody()).containsEntry("amount", 15);
    }
}
