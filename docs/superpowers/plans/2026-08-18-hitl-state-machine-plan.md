# HITL 6 态状态机实施计划

> 对应 spec：`docs/superpowers/specs/2026-08-18-hitl-state-machine-design.md`
> 执行方式：TDD（test-driven-development），Java 主栈先行，Python 副栈同 commit 同步。

## 阶段划分（依赖顺序）

### Phase 1 — Java 状态机核心（TDD 红→绿）

依据 `PendingConfirmationStore` 现状（66 行，drain 语义）。

1. 写 `PendingConfirmationStoreStateMachineTest`（先红）：覆盖 6 态迁移、CAS 幂等、EXPIRED 显式暴露、并发 confirm 只成功一次、confirm/cancel 竞争。
2. 重构 `PendingConfirmationStore`：新增 `Status` 枚举、`PendingCall.status` 字段、`lookup()` 三态查询、`transition()` CAS 抢占、`evictExpired()` 保留。
3. 删旧 `PendingConfirmationStoreTest`、`HITLIntegrationTest`（被新测试取代）。

### Phase 2 — HitlOrchestrator（TDD）

1. 写 `HitlOrchestratorTest`（先红）：pause→confirm 触发工具执行（mock backend client 断言副作用）、cancel 不执行、过期拒绝。
2. 实现 `HitlOrchestrator`：封装 `isWriteTool / pauseForConfirmation / confirm / cancel / lookup`，confirm 内真正调用 backend client 执行 `add_transaction`。

### Phase 3 — Advisor 增强 + Controller 接线

1. `ToolCallGuardrailAdvisor` 注入 `HitlOrchestrator`，写操作触发 `pauseForConfirmation`，confirmationId 写入 response context。
2. `ChatController`：流式回调发射 `event:confirmation`；`/chat/confirm`、`/chat/cancel` 改调 `hitlOrchestrator`。
3. 更新 `ToolCallGuardrailAdvisorTest` 构造。

### Phase 4 — B 层落库冒烟测试

1. 写 B 层冒烟测试（`@SpringBootTest` + test profile + 临时 CSV），断言 confirm 前不落库、confirm 后落库。

### Phase 5 — Python 副栈对等实现

1. 写 `finance-agent-py/test_hitl.py`（先红）。
2. 实现 `finance-agent-py/hitl.py`。
3. `guardrails.py` 写操作触发 pause；`chat_server.py` 新增 confirm/cancel 端点 + confirmation 事件。

### Phase 6 — 文档同步 + 报告

1. 更新 `docs/roadmap/03-human-in-the-loop.md`、`CLAUDE.md`（Known Tech Debt）、`README.md`/`README_EN.md`。
2. 输出工程报告文档。

## 验收

- `cd finance-agent && ./mvnw test` 全绿
- `cd finance-agent-py && pytest` 全绿
- B 层冒烟证明「未确认不落库」

## 风险与约束

- 双栈同步：Phase 1-4（Java）与 Phase 5（Python）最终合入同一 commit 序列，保证编译/语义一致。
- `get()` API 移除会破坏多处调用，必须先 grep 全量调用方（已知：`ChatController.confirm`、旧两个测试）。
- Java 默认 1.8，编译需 `export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`（或 start-all.sh 探测）。
