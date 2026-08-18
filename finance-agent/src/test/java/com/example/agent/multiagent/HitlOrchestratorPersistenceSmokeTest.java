package com.example.agent.multiagent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 层跨进程落库冒烟测试。
 *
 * <p>用 JDK 内置 {@link HttpServer} 起真实 HTTP 端口，后端 handler 将收到的
 * {@code POST /api/transactions} 请求体逐行追加到临时 CSV 文件，模拟 backend 的
 * 真实落库副作用。验证「未确认不落库、确认后才落库」的完整链路。
 *
 * <p>与 A 层 {@link HitlOrchestratorTest}（mock 断言 postCount）互补：
 * 本测试证明 orchestrator 构造的 HTTP 请求能穿过真实网络栈并触发落库副作用。
 */
class HitlOrchestratorPersistenceSmokeTest {

    private HttpServer server;
    private Path csvFile;
    private List<String> persistedRows;
    private AtomicInteger postCount;
    private HitlOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws IOException {
        persistedRows = new ArrayList<>();
        postCount = new AtomicInteger(0);
        csvFile = Files.createTempFile("hitl-smoke-", ".csv");

        // 起真实 HTTP 服务器，模拟 backend 落库
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/transactions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                postCount.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                // 模拟 backend：追加一行 CSV（简化为直接记录 JSON 行）
                synchronized (persistedRows) {
                    persistedRows.add(body);
                    Files.writeString(csvFile, body + "\n",
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
                byte[] resp = "{\"id\":1,\"status\":\"created\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        orchestrator = new HitlOrchestrator(new PendingConfirmationStore(), restClient);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        try {
            Files.deleteIfExists(csvFile);
        } catch (IOException ignored) {
        }
    }

    @Test
    void shouldNotPersistBeforeConfirm() throws IOException {
        orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30, "category", "餐饮", "type", "EXPENSE"),
                "user-1", "session-1");

        // 暂停后不应有任何 HTTP 落库
        assertThat(postCount.get()).isZero();
        assertThat(Files.readString(csvFile)).isEmpty();
    }

    @Test
    void shouldPersistAfterConfirm() throws IOException {
        String id = orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30, "category", "餐饮", "type", "EXPENSE"),
                "user-1", "session-1");

        var outcome = orchestrator.confirm(id, null);

        assertThat(outcome.status()).isEqualTo(HitlOrchestrator.ConfirmStatus.EXECUTED);
        // 确认后真实落库一次
        assertThat(postCount.get()).isEqualTo(1);
        String csvContent = Files.readString(csvFile).trim();
        assertThat(csvContent).contains("\"amount\"");
        assertThat(csvContent).contains("30");
        assertThat(csvContent).contains("餐饮");
    }

    @Test
    void shouldNotPersistOnCancel() throws IOException {
        String id = orchestrator.pauseForConfirmation("add_transaction",
                Map.of("amount", 30, "category", "餐饮", "type", "EXPENSE"),
                "user-1", "session-1");

        var outcome = orchestrator.cancel(id);

        assertThat(outcome.status()).isEqualTo(HitlOrchestrator.CancelStatus.CANCELLED);
        assertThat(postCount.get()).isZero();
        assertThat(Files.readString(csvFile)).isEmpty();
    }
}
