package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PendingConfirmationStore 6 态状态机测试（RED 先行）。
 *
 * <p>覆盖 spec 定义的状态机语义：
 * <ul>
 *   <li>IDLE →（save）→ PENDING</li>
 *   <li>PENDING →（confirm CAS）→ EXECUTING →（同步收尾）→ DONE</li>
 *   <li>PENDING →（cancel CAS）→ CANCELLED</li>
 *   <li>PENDING →（TTL 超时）→ EXPIRED（显式暴露）</li>
 *   <li>confirm/cancel 竞争时 CAS 抢占有且仅有一次成功</li>
 * </ul>
 */
class PendingConfirmationStoreStateMachineTest {

    @Test
    void shouldStartInPendingAfterSave() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");

        var result = store.lookup(id);
        assertThat(result.lookup()).isEqualTo(PendingConfirmationStore.Lookup.FOUND);
        assertThat(result.call().status()).isEqualTo(PendingConfirmationStore.Status.PENDING);
        assertThat(result.call().toolName()).isEqualTo("add_transaction");
    }

    @Test
    void shouldTransitionPendingToExecutingOnConfirm() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");

        boolean won = store.transition(id,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.EXECUTING);

        assertThat(won).isTrue();
        var result = store.lookup(id);
        assertThat(result.lookup()).isEqualTo(PendingConfirmationStore.Lookup.FOUND);
        assertThat(result.call().status()).isEqualTo(PendingConfirmationStore.Status.EXECUTING);
    }

    @Test
    void shouldTransitionExecutingToDone() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");
        store.transition(id, PendingConfirmationStore.Status.PENDING, PendingConfirmationStore.Status.EXECUTING);

        boolean won = store.transition(id,
                PendingConfirmationStore.Status.EXECUTING,
                PendingConfirmationStore.Status.DONE);

        assertThat(won).isTrue();
        assertThat(store.lookup(id).call().status()).isEqualTo(PendingConfirmationStore.Status.DONE);
    }

    @Test
    void shouldTransitionPendingToCancelledOnCancel() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");

        boolean won = store.transition(id,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.CANCELLED);

        assertThat(won).isTrue();
        assertThat(store.lookup(id).call().status()).isEqualTo(PendingConfirmationStore.Status.CANCELLED);
    }

    @Test
    void shouldFailTransitionWhenStatusDoesNotMatch() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");
        // 首次抢占成功
        assertThat(store.transition(id,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.EXECUTING)).isTrue();

        // 第二次期望 PENDING 已不成立 → 失败
        boolean won = store.transition(id,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.EXECUTING);
        assertThat(won).isFalse();
    }

    @Test
    void shouldExposeExpiredState() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");
        // 手动将过期时间回拨，模拟 TTL 超时
        store.forceExpire(id);

        var result = store.lookup(id);
        assertThat(result.lookup()).isEqualTo(PendingConfirmationStore.Lookup.EXPIRED);
    }

    @Test
    void shouldReturnNotFoundForUnknownId() {
        var store = new PendingConfirmationStore();
        var result = store.lookup("nonexistent-id");
        assertThat(result.lookup()).isEqualTo(PendingConfirmationStore.Lookup.NOT_FOUND);
    }

    @Test
    void shouldNotAllowConfirmAfterCancel() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");
        assertThat(store.transition(id,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.CANCELLED)).isTrue();

        // cancel 后 confirm 无法推进
        assertThat(store.transition(id,
                PendingConfirmationStore.Status.PENDING,
                PendingConfirmationStore.Status.EXECUTING)).isFalse();
    }

    @Test
    void shouldAllowOnlyOneConcurrentConfirmToWin() throws Exception {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user");

        int threads = 8;
        var latch = new CountDownLatch(threads);
        var executor = Executors.newFixedThreadPool(threads);
        var wins = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    boolean won = store.transition(id,
                            PendingConfirmationStore.Status.PENDING,
                            PendingConfirmationStore.Status.EXECUTING);
                    if (won) wins.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        // CAS 幂等：有且仅有一个线程抢占成功
        assertThat(wins.get()).isEqualTo(1);
    }

    @Test
    void shouldListPendingByUserId() {
        var store = new PendingConfirmationStore();
        store.save("add_transaction", Map.of("amount", 30), "user-a");
        store.save("add_transaction", Map.of("amount", 50), "user-a");
        store.save("add_transaction", Map.of("amount", 70), "user-b");

        var pendingA = store.listPendingByUserId("user-a");
        assertThat(pendingA).hasSize(2);

        var pendingB = store.listPendingByUserId("user-b");
        assertThat(pendingB).hasSize(1);

        assertThat(store.listPendingByUserId("user-c")).isEmpty();
    }

    @Test
    void shouldNotListTerminalStatesByUserId() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of("amount", 30), "user-a");
        store.transition(id, PendingConfirmationStore.Status.PENDING, PendingConfirmationStore.Status.CANCELLED);

        // 已终止（CANCELLED）不计入 pending 列表
        assertThat(store.listPendingByUserId("user-a")).isEmpty();
    }
}
