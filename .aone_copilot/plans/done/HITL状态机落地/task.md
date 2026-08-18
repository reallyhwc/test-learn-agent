# HITL 6 态状态机落地 — 工程报告

> 日期：2026-08-18 · 性质：架构级重构（Java 主栈 + Python 副栈双栈对齐）
> 关联 spec：[docs/superpowers/specs/2026-08-18-hitl-state-machine-design.md](../../../superpowers/specs/2026-08-18-hitl-state-machine-design.md)
> 关联 plan：[docs/superpowers/plans/2026-08-18-hitl-state-machine-plan.md](../../../superpowers/plans/2026-08-18-hitl-state-machine-plan.md)

## 一、背景与问题定性

项目定位「生产级 Agent 参考实现」，HITL（Human-in-the-Loop，写操作人工确认）是其差异化能力之一。但代码审计发现，原 HITL 是**「五环缺三环半」**的悬空实现：

| 环节 | 原状态 |
|------|--------|
| `PendingConfirmationStore`（存储） | ✅ 有，但 drain 语义 + 过期吞空 |
| `/chat/confirm` `/chat/cancel` 端点 | ⚠️ 有但悬空（`get()` 永远拿不到，执行走 `// TODO` 空壳） |
| advisor 触发 `save()` | ❌ 从不调用，写操作直接执行 |
| `event:confirmation` SSE 事件发射 | ❌ 从不发射 |
| confirm 后真实执行工具落库 | ❌ 只返回 `status:ok` |

且 `docs/roadmap/03-human-in-the-loop.md` 错误标记为「✅ 已实施」，属于文档乐观、与代码不符。

## 二、核心决策（grill 阶段固化）

确立工程标准为 **C：防御能力必须「接通 + 可自动验证」**，并据此定下 6 态状态机架构：

```
IDLE ──(save)──► PENDING ──(confirm CAS)──► EXECUTING ──(同步收尾)──► DONE
                          ├──(cancel CAS)──► CANCELLED
                          └──(TTL 超时)────► EXPIRED（显式暴露）
```

关键不变量：
1. **CAS 原子抢占**：confirm/cancel 走同一 `transition(id, expected, target)`，`ConcurrentHashMap.compute` 保证有且仅有一次成功。
2. **EXPIRED 显式暴露**：`lookup()` 三态区分 FOUND / NOT_FOUND / EXPIRED。
3. **状态与存储解耦**：正确性不依赖物理删除，定时清理仅做内存回收。

## 三、架构调整点（harness 工程体系视角）

### 3.1 新增独立编排层 `HitlOrchestrator`

将「写操作 → 暂停 → 确认执行 → 落库」封为单一职责组件，advisor 只识别+暂停（写 confirmationId 进 context），端点只推进状态机。避免把状态机塞进 advisor 导致「审计」与「编排恢复」纠缠。

### 3.2 状态机成为一等公民

`PendingConfirmationStore` 从「带 TTL 的 Map」升级为「6 态 + CAS」的真正状态机，`Status`/`Lookup` 枚举、三态查询、CAS 抢占均为一等 API，可独立测试 8 类迁移 + 并发幂等。

### 3.3 双栈同步

按 CLAUDE.md Dual-Stack 规则（HITL 属「必须同步」类），Python 副栈新增 `hitl.py` 对等实现：`PendingConfirmationStore` + `HitlOrchestrator` 语义一致（asyncio.Lock 替代 compute，dataclass 替代 record）。

## 四、交付物清单

### Java 主栈

| 文件 | 变更 |
|------|------|
| `PendingConfirmationStore.java` | 重构：6 态 + Status/Lookup 枚举 + lookup() 三态查询 + transition() CAS + forceExpire() |
| `HitlOrchestrator.java` | 新增：编排 + confirm/cancel outcome + executeTool 落库 |
| `ToolCallGuardrailAdvisor.java` | 增强：注入 orchestrator，写操作 pause + 写入 `CONTEXT_HITL_CONFIRMATION_ID` |
| `ChatController.java` | 接线：confirm/cancel 改调 orchestrator，返回 200/404/409 三态 |

### Java 测试（A 层）

| 文件 | 覆盖 |
|------|------|
| `PendingConfirmationStoreStateMachineTest.java` | 9 用例：6 态迁移 + CAS 幂等 + EXPIRED + 并发 confirm 只成功一次 |
| `HitlOrchestratorTest.java` | 7 用例：pause→confirm 落库、cancel 不落库、幂等、modifiedParams |
| `ToolCallGuardrailAdvisorTest.java` | +2 用例：写操作 pause + 读操作不 pause |
| `RecordingBackendClient.java` | 新增测试辅助：拦截 POST /api/transactions 断言落库副作用 |

### Python 副栈

| 文件 | 变更 |
|------|------|
| `hitl.py` | 新增：Status/Lookup 枚举 + PendingConfirmationStore + HitlOrchestrator（asyncio.Lock CAS） |
| `test_hitl.py` | 新增：15 用例对等覆盖 |
| `chat_server.py` | 接线：confirm/cancel 端点接 orchestrator + 真正落库执行 |

## 五、测试结果（可验证证据）

- **Java**：`./mvnw test` → **150 个测试，0 失败 0 错误，14 个因无 LLM 环境跳过**（BUILD SUCCESS）。
- **Python**：`python3 -m pytest test_hitl.py` → **15 passed in 0.03s**。
- **TDD 关键捕获**：RED 阶段发现并修复一个真实原子性 bug——`transition` 原判据 `result.status() == target` 在「状态已非 expected 但恰等于 target」时误判抢占成功，导致并发 confirm 8 线程全部「成功」（`shouldAllowOnlyOneConcurrentConfirmToWin` 期望 1 实得 8）。改用 `changed[]` 标志记录是否真正发生 expected→target 变更后修复。**这正是「并发幂等」测试不可省的价值。**

## 六、已知遗留（如实记录）

1. **`event:confirmation` 的流式发射未实现（有源码级依据的遗留，非遗漏）**：反编译 `BaseAdvisor.adviseStream` 证实，流式下 `after()` 通过 `Flux.map` 对**每个 chunk** 独立应用，其写回的 `ChatClientResponse.context()` 是**瞬时的、不跨 LLM step 传递**。因此「advisor 写 confirmationId 进 context → controller subscribe 读 context 发射事件」这条路径在流式下**读不到**（tool-call chunk 的 context 不会出现在后续文本 chunk）。要做到发射，需一个跨 step 的载体（如 controller 侧直接从 `HitlOrchestrator` 单例查询 pending 项，或改用非流式 `call()` 路径的 advisor context）。此判断基于源码，故未贸然实现会产出死代码的方案。
2. **Python LangGraph 工具执行层「写操作暂停」未接线**：`hitl.py` 状态机/编排器 + 端点已完成并通过测试，但 `agent.py` 的 `create_react_agent` 自动执行工具，真正「暂停写操作不执行」需 LangGraph `interrupt()` 专集（需改造 graph 结构）。当前 Python 系统为 3.9（项目要求 3.10+），无 venv，无法完整验证 chat_server 运行。已记录于 CLAUDE.md。
3. **B 层跨进程落库冒烟**：未新增重型跨进程测试。「未确认不落库、确认才落库」副作用已在 `HitlOrchestratorTest` + `RecordingBackendClient` 精确断言（pause 时 postCount=0，confirm 后=1，body 参数核验）。

## 七、后续建议（按优先级）

1. **补 `event:confirmation` 发射（需先定载体）**：在 controller 流式回调中，从 `HitlOrchestrator` 单例查询「当前会话有无 pending 的写操作」来发射确认事件（而非依赖 advisor context 传递）。这是打通「advisor→前端卡片」的最后一环，需先决定载体方案。
2. **Python LangGraph interrupt 集成**：在 3.10+ venv 环境补 `interrupt()` 实现写操作暂停。
3. **挂入 pre-merge pipeline gate**：把 HITL 状态机测试纳入流水线（当前 pipeline 只跑 restart→eval∥regression→review，缺 guardrail 验证 gate）。
4. **金额阈值联动**：与 roadmap 01「大额才确认」协同，`MAX_AMOUNT` 复用 HITL 触发条件。

## 八、harness 体系沉淀

本次落地完整走通「grill → brainstorm → spec → plan → TDD → 双栈同步 → 文档回写」的 harness 规范：spec/plan 入 `docs/superpowers/`，状态如实回写 `docs/roadmap/`，Tech Debt 更新 `CLAUDE.md`，报告归档 `.aone_copilot/plans/done/`。验证了「防御能力必须可自动验证」这一标准如何通过 TDD 的 RED 阶段暴露真实并发 bug。
