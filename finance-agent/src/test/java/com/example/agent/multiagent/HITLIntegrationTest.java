package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 确认/取消流程集成测试。
 */
class HITLIntegrationTest {

    private final PendingConfirmationStore store = new PendingConfirmationStore();

    @Test
    void shouldCompleteConfirmFlow() {
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 30, "category", "餐饮", "type", "EXPENSE"),
                "test-user", "session-1");
        assertThat(confirmationId).isNotBlank();

        var pending = store.get(confirmationId);
        assertThat(pending).isPresent();
        assertThat(pending.get().toolName()).isEqualTo("add_transaction");
        assertThat(pending.get().parameters()).containsEntry("amount", 30);

        // drain 语义 — 确认后立即删除
        var secondGet = store.get(confirmationId);
        assertThat(secondGet).isEmpty();
    }

    @Test
    void shouldCompleteCancelFlow() {
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 50), "test-user");
        store.remove(confirmationId);
        assertThat(store.get(confirmationId)).isEmpty();
    }

    @Test
    void shouldHandleModifiedParams() {
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 50, "category", "餐饮"), "test-user");
        var pending = store.get(confirmationId);
        Map<String, Object> modified = new java.util.HashMap<>(pending.get().parameters());
        modified.put("amount", 35);
        assertThat(modified.get("amount")).isEqualTo(35);
        assertThat(modified.get("category")).isEqualTo("餐饮");
    }

    @Test
    void shouldAutoExpireAfterGet() {
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 100), "test-user");
        assertThat(store.get(confirmationId)).isPresent();
        // drain 后不可再取
        assertThat(store.get(confirmationId)).isEmpty();
    }
}
