package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PendingConfirmationStoreTest {

    @Test
    void shouldSaveAndRetrieve() {
        var store = new PendingConfirmationStore();
        Map<String, Object> params = Map.of("amount", 35, "category", "餐饮");
        String id = store.save("add_transaction", params, "test-user");

        assertThat(id).isNotBlank();
        var pending = store.get(id);
        assertThat(pending).isPresent();
        assertThat(pending.get().toolName()).isEqualTo("add_transaction");
        assertThat(pending.get().parameters()).containsEntry("amount", 35);
    }

    @Test
    void shouldRemoveAfterGet() {
        // drain 语义：取走就删除
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of(), "user");
        store.get(id);  // 第一次取到
        assertThat(store.get(id)).isEmpty();  // 第二次空
    }

    @Test
    void shouldReturnEmptyForExpired() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of(), "user");
        // 直接 remove 模拟过期行为
        store.remove(id);
        assertThat(store.get(id)).isEmpty();
    }

    @Test
    void shouldSaveWithSessionId() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of(), "user", "session-abc");
        var pending = store.get(id);
        assertThat(pending.get().sessionId()).isEqualTo("session-abc");
    }
}
