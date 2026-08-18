# HITL 6 态状态机设计（Human-in-the-Loop 写操作确认闭环）

## 背景

项目自称「生产级 Agent 参考实现」，核心差异点之一是三层 Guardrails + HITL（Human-in-the-Loop，人工确认）。但代码审计发现，HITL 只完成了「组件 + 端点」的孤立实现，关键闭环从未接通：

- `PendingConfirmationStore` 只有 `save/get/remove` 三方法，`get()` 采用「先删除再判过期」的 drain 语义，过期与不存在都坍缩为 `empty`，无法区分。
- `ToolCallGuardrailAdvisor` 对写操作只做 `log.warn` 审计（金额 / 频率），**从不调用 `save()` 暂停执行**。
- `/chat/confirm` 端点已接线但悬空：`get()` 成功后走 `// TODO` 分支，只返回 `status:ok`，**不真的执行工具**（`ChatController.java:545-546`）。
- `event:confirmation` SSE 事件从不发射。
- Python 副栈（`finance-agent-py/guardrails.py`）同样只有审计、无 HITL，与 Java 栈对称地「缺失」。

结论：五环链路（store → 端点 → advisor 触发 → confirmation 事件 → confirm 真执行）缺了三环半。本设计补齐整条闭环，并满足「防御能力必须接通 + 可自动验证」的 C 级标准。

## 决策记录（grill 阶段固化）

| # | 决策 | 结论 |
|---|------|------|
| 1 | 项目定位 | 生产参考实现（主） + 双栈对比（辅） |
| 2 | 工程标准 | C：防御能力「接通 + 可自动验证」 |
| 3 | HITL 补齐程度 | 接通 + 端到端 + 完整状态机负面用例 |
| 4 | 顺序 | 链路与测试同 commit 交付 |
| 5 | 状态数 | 6 态：IDLE / PENDING / EXECUTING / DONE / CANCELLED / EXPIRED |
| 6 | EXPIRED | 显式暴露，与「不存在」可区分 |
| 7 | 测试边界 | A+B 分层：A 进程内全状态迁移 + B 跨进程落库冒烟 |
| 8 | 架构 | 抽独立 `HitlOrchestrator`，advisor 只识别+暂停，端点驱动推进 |
| 9a | 幂等 | confirm = 原子 `PENDING → EXECUTING` CAS 抢占 |
| 9b | EXECUTING→DONE | confirm 端点同步执行 tool，就地收尾 |
| 10 | cancel 竞争 | confirm/cancel 共用同一 CAS `transition(id, expected, new)` |

## 状态机模型

### 6 个状态

| 状态 | 含义 | 进入者 |
|------|------|--------|
| `IDLE` | 无待确认操作 | 起始 / 结束后 |
| `PENDING` | 已生成 confirmationId，等待用户决定 | advisor 识别写操作时 `save()` |
| `EXECUTING` | 已被 confirm，正在同步执行 tool | confirm 的 CAS 抢占成功瞬间 |
| `DONE` | tool 执行成功（已落库） | confirm 请求内同步收尾 |
| `CANCELLED` | 用户取消 | cancel 的 CAS 抢占成功 |
| `EXPIRED` | TTL 超时，显式暴露 | 查询时发现超时 |

### 状态迁移图

```
IDLE ──(advisor 识别写操作 + save())──► PENDING
PENDING ──(confirm CAS 抢占成功)──► EXECUTING ──(tool 同步执行成功)──► DONE
PENDING ──(cancel CAS 抢占成功)──► CANCELLED
PENDING ──(TTL 超时被查询)──► EXPIRED
```

关键不变式：

1. **confirm 与 cancel 竞争**：二者都走原子的 `transition(id, PENDING, target)`，有且仅有一个成功。失败的返回明确错误码，绝不静默为空。
2. **EXECUTING 可被观察**：CAS 抢占成功后、tool 执行完成前，状态为 `EXECUTING`。并发 confirm 此时拿到「非 PENDING」的明确失败，而非「不存在」的误报。
3. **正确性不依赖物理删除**：状态是字段，物理存储的清理延后到定时任务，仅作内存回收，不参与正确性判断。

## Java 主栈设计

### 1. `PendingConfirmationStore` 重构

从「带 TTL 的 Map」重构为「6 态 + CAS 抢占」的真正状态机。

**`PendingCall` record 增加字段**：`Status status`、`Instant expiresAt` 保留。新增 `enum Status { PENDING, EXECUTING, DONE, CANCELLED, EXPIRED }`。

**查询 API 三态化**：废弃「drain 式 `get()`」，新增三态查询结果：

```java
public enum Lookup { FOUND, NOT_FOUND, EXPIRED }

public record LookupResult(Lookup lookup, PendingCall call) { /* call 仅 FOUND 时非空 */ }

public LookupResult lookup(String confirmationId)
```

- `FOUND`：存在且未过期（状态可为 PENDING 或 EXECUTING，由 `call.status()` 判定）。
- `NOT_FOUND`：从未存在或已被物理清理。
- `EXPIRED`：存在但 `expiresAt` 已过 → 显式暴露，且当场标记 `EXPIRED` 状态。

**CAS 抢占 API**：

```java
public boolean transition(String confirmationId, Status expected, Status target)
```

内部用 `ConcurrentHashMap.compute(confirmationId, ...)` 做单原子「读旧 → 比对（含过期判断）→ 写新」，返回是否抢占成功。true = 本次调用者获得推进权。

**物理清理**：保留 `@Scheduled(fixedDelay = 10_000) evictExpired()`，只清 `EXPIRED / DONE / CANCELLED` 且超过保留期的项。

### 2. `HitlOrchestrator` 新增组件

单一职责：封装「写操作 → 待确认 → 确认执行 → 落库」的编排，供 advisor 与端点共用。

```java
@Component
public class HitlOrchestrator {
    // 识别写操作是否需确认
    public boolean isWriteTool(String toolName);
    // 暂停写操作：save 为 PENDING，返回 confirmationId
    public String pauseForConfirmation(String toolName, Map<String,Object> params, String userId, String sessionId);
    // 确认：CAS PENDING→EXECUTING，成功则同步执行 tool 落地，返回执行结果；失败返回原因
    public ConfirmOutcome confirm(String confirmationId, Map<String,Object> modifiedParams);
    // 取消：CAS PENDING→CANCELLED
    public CancelOutcome cancel(String confirmationId);
    // 查询状态（供测试与端点复用）
    public LookupResult lookup(String confirmationId);
}
```

- `ConfirmOutcome` / `CancelOutcome` 为三态结果（成功 / 非 PENDING / 不存在 / 过期），供端点映射到 HTTP 状态码。
- 「真正执行 tool」的实现：复用现有 `BackendClientConfig` 中的 backend 客户端，构造 `add_transaction` 的 REST 调用并返回结果，使 `confirm` 端点具备真实的落库副作用（B 层冒烟的基础）。

### 3. `ToolCallGuardrailAdvisor` 增强

在 `after()` 的 `auditToolCall()` 中，对 `WRITE_TOOLS` 命中项，除原有审计外：

1. 调用 `hitlOrchestrator.pauseForConfirmation(...)` 生成 confirmationId。
2. 将 confirmationId 写入 `ChatClientResponse` 的 context（新增 key `HITL_AGENT_CONFIRMATION_ID`），供 controller 在流式回调中读取并发射 `event:confirmation`。

advisor 本身仍「只识别 + 暂停」，不亲自执行工具（职责边界，决策 #8）。

### 4. `ChatController` 接线

- **流式回调**：在 single/multi 两条流的 `subscribe` 中，检测 context 是否携带 `HITL_AGENT_CONFIRMATION_ID`，若有则发射 `event:confirmation`，payload 含 `confirmationId / toolName / parameters / expiresAt`。
- **`/chat/confirm`**：改为调用 `hitlOrchestrator.confirm(...)`，返回真实执行结果；`NOT_FOUND/EXPIRED` → 404，抢夺失败（非 PENDING）→ 409。
- **`/chat/cancel`**：改为调用 `hitlOrchestrator.cancel(...)`。

### 5. 测试（A + B 分层）

**A 层（进程内，不依赖 LLM / 后端）**：

- `PendingConfirmationStoreStateMachineTest`：6 态全迁移 + CAS 幂等 + EXPIRED 显式暴露 + 并发 confirm 只成功一次 + cancel/confirm 竞争。
- `HitlOrchestratorTest`：pause → confirm 触发工具执行（用 mock backend client 断言副作用）→ done；cancel 不执行；过期拒绝。
- `ToolCallGuardrailAdvisorTest` 增强：写操作触发 pause、写 confirmationId 进 context。

**B 层（跨进程落库冒烟，1~2 条）**：

- 用 `@SpringBootTest` + test profile + 临时 CSV 目录，起 backend + mcp-server，断言「confirm 前 `add_transaction` 未出现在 CSV，confirm 后出现」。用 `@ExtendWith(LlmCondition)` 在无 LLM 环境跳过。

## Python 副栈设计（对等实现）

新增 `finance-agent-py/hitl.py`，与 Java 语义一致：

- `HitlStatus` 枚举：`PENDING / EXECUTING / DONE / CANCELLED / EXPIRED`。
- `PendingConfirmationStore` 类：`save / lookup / transition / evict_expired`，用 `threading.Lock` 或 `asyncio.Lock` 保证 CAS 原子性。
- `HitlOrchestrator` 类：`is_write_tool / pause_for_confirmation / confirm / cancel / lookup`。
- `guardrails.py` 的 `_audit_single_tool_call` 对写操作调用 `pause_for_confirmation`，并在返回结构里带出 confirmationId。
- `chat_server.py` 新增 `/chat/confirm` 与 `/chat/cancel` 端点；流式回调发射 `confirmation` 事件。
- `test_hitl.py`：对等覆盖 Java 的 A 层状态机用例。

> 按 CLAUDE.md Dual-Stack 规则，HITL 属「必须双栈同步」改动：新组件 + Guardrail 类型新增，要求同一 commit 内同步两栈。Python 侧用 `@dataclass` 替代 record，用 `dict` 替代 `Map`，语义对齐即可，不追求逐行一致。

## 影响面与同步项

| 变更 | 影响模块 |
|------|---------|
| `PendingConfirmationStore` API 变更 | 现有 `PendingConfirmationStoreTest`、`HITLIntegrationTest`、`ChatController.confirm/cancel` 全部重构 |
| `HitlOrchestrator` 新增 | 新增依赖注入点 |
| `ToolCallGuardrailAdvisor` 增强 | 需注入 `HitlOrchestrator`，`ToolCallGuardrailAdvisorTest` 更新构造 |
| `ChatController` 接线 | confirm/cancel 签名与语义变更 |
| Python 副栈 | 新增 hitl.py + test_hitl.py，guardrails/chat_server 联动 |

## 文档同步

- `docs/roadmap/03-human-in-the-loop.md`：状态从「✅ 已实施」改为如实反映（此前为文档乐观标记，与代码不符）。
- `CLAUDE.md` Known Tech Debt：移除「HITL 确认流程未完成」条目。
- `README.md` / `README_EN.md`：架构图补充 HITL 闭环与 6 态状态机。

## 明确不做（YAGNI）

- 不做异步执行 / 消息队列回调（EXECUTING→DONE 同步收尾）。
- 不做 DELETE / UPDATE 工具的 HITL（当前 backend 无此 API）。
- 不做「大额才确认」的金额阈值联动（属 roadmap 01 与 03 的协同项，非本次范围）。
- 不做前端 `ConfirmationCard` 的新增字段（已有），仅确认其消费的 `event:confirmation` payload 与本设计一致。

## 验证标准

- `cd finance-agent && ./mvnw test` 全绿（A 层状态机 + B 层冒烟）。
- `cd finance-agent-py && pytest` 全绿。
- B 层冒烟证明「未确认不落库、确认才落库」。
