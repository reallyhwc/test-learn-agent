# Multi-Agent 架构演进 — 设计规格

> **项目**: Personal Finance Agent
> **日期**: 2026-06-07
> **决策**: Supervisor 编排模式 + 双栈完整实现 + HITL 包含 + Eval 同步扩展

---

## 1. 目标

将当前单 Agent（5 个 MCP 工具全绑定在一个 ChatClient）拆分为 3 个 Agent 协作：**Supervisor（编排器）+ Bookkeeper（记账员）+ Analyst（分析师）**。

核心收益：
- 职责分离：每个 Agent 的 System Prompt 精简到 ~300 token，解决 "Lost in the Middle" 问题
- 架构学习：Java 侧手动编排 vs Python 侧 LangGraph StateGraph，理解 Multi-Agent 底层原理
- HITL 闭环：写操作经由 Supervisor 拦截确认，形成 "LLM 决策 → 人工确认 → 执行" 完整链路

---

## 2. 架构总览

### 2.1 Agent 拆分

```
                          ┌─────────────────┐
                          │    Supervisor    │
                          │  (编排 + 分类 +   │
                          │   HITL 拦截)      │
                          └───────┬─────────┘
                    ┌─────────────┴─────────────┐
            ┌───────▼──────┐            ┌───────▼──────┐
            │  Bookkeeper  │            │   Analyst    │
            │  (记账 CRUD) │            │  (统计分析)  │
            └───────┬──────┘            └───────┬──────┘
                    │                           │
         ┌──────────┼──────────┐     ┌──────────┼──────────┐
         │add_txn   │list_accts│     │list_txns │summarize │
         │query_bal │          │     │          │          │
         └──────────┴──────────┘     └──────────┴──────────┘
```

### 2.2 职责矩阵

| | Supervisor | Bookkeeper | Analyst |
|---|---|---|---|
| 职责 | 意图分类、编排循环、HITL 拦截、结果整合 | 记账、查余额、查账户列表 | 交易统计、分类汇总、趋势分析 |
| 工具 | 无 MCP 工具 | `add_transaction` `list_accounts` `query_balance` | `list_transactions` `summarize_transactions` |
| System Prompt | ~200 token（分类规则 + 路由决策） | ~300 token（记账规则 + 分类二级枚举） | ~300 token（分析规则 + 禁止模糊金额） |
| Guardrail | Input（注入检测） | ToolCall（参数白名单）+ Output（金额幻觉） | ToolCall + Output |
| 模型 | 轻量即可 | 同现有配置 | 同现有配置 |

---

## 3. 数据流

### 3.1 正常流程（读操作）

```
用户: "本月餐饮花了多少"
  → Supervisor（分类: analysis）
    → Analyst（调用 summarize_transactions）
      → MCP Server → Backend → 返回汇总数据
    ← Analyst（生成分析回复）
  ← Supervisor（透传给用户）
```

### 3.2 HITL 流程（写操作）

```
用户: "记一笔午餐30元"
  → Supervisor（分类: booking）
    → Bookkeeper（LLM 决策: tool_call add_transaction(30, 餐饮)）
  ← Supervisor（检测到写操作 → 拦截！）
  ← SSE confirmation 事件 → 前端确认卡片
  用户点击 [确认]
  → POST /chat/confirm → 执行 tool_call → 返回结果
  用户点击 [取消]
  → POST /chat/cancel → 返回"已取消"
```

### 3.3 编排循环

```
- Round 1: 分类 → 派发 → Specialist 返回结果
- Round 2: 如果结果需要另一个 Specialist 协助，再次派发
- 强制上限: 2 轮（Java 手动计数 / LangGraph recursion_limit=5）
```

### 3.4 共享状态

所有 Agent 共享 `SharedChatMemory`（userId 维度）。Supervisor 写入原始用户消息，Specialist 写入工具调用结果，所有 Agent 读取完整历史。

---

## 4. Java 实现

### 4.1 文件清单

```
finance-agent/src/main/java/com/example/agent/
├── multiagent/
│   ├── SupervisorAgent.java          (NEW) 编排器
│   ├── BookkeeperAgent.java          (NEW) 记账 Specialist
│   ├── AnalystAgent.java             (NEW) 分析 Specialist
│   ├── AgentType.java                (NEW) 枚举
│   └── PendingConfirmationStore.java (NEW) HITL 暂存
├── controller/
│   └── ChatController.java           (MODIFY) 新增端点
├── config/
│   └── MultiAgentConfig.java         (NEW) 3 个 Agent 的 ChatClient Bean
```

### 4.2 核心类签名

**SupervisorAgent** — 持有 `supervisorClient`（不绑 MCP 工具），`classifyIntent()` 返回 `AgentType`，`handle()` 返回 `SupervisorResult`（含 finalResponse + 可选 confirmationId）。

**BookkeeperAgent** — 持有独立 `ChatClient`，绑定 `add_transaction`、`list_accounts`、`query_balance` 三个工具。System Prompt 包含记账分类枚举和金额精确性规则。

**AnalystAgent** — 持有独立 `ChatClient`，绑定 `list_transactions`、`summarize_transactions` 两个工具。System Prompt 禁止 "大约/大概" 等模糊表述。

**PendingConfirmationStore** — `ConcurrentHashMap<String, PendingCall>`，TTL 60s，`@Scheduled` 每 10s 清理过期项。

### 4.3 新增 API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/chat/multi-agent/stream` | GET (SSE) | Multi-Agent 流式入口 |
| `/chat/confirm` | POST | 确认执行待定操作 |
| `/chat/cancel` | POST | 取消待定操作 |

### 4.4 关键实现细节

- 3 个独立 `ChatClient` Bean：`MultiAgentConfig` 中用 `ChatClient.builder()` 分别创建，绑定不同工具子集
- 已有 `SimpleCircuitBreaker` 复用：每个 Specialist 独立一个 breaker 实例
- 已有 `GuardrailAdvisor` 复用：Bookkeeper 和 Analyst 各自注册对应的 Guardrail

---

## 5. Python 实现（LangGraph）

### 5.1 文件清单

```
finance-agent-py/
├── multiagent/
│   ├── __init__.py               (NEW)
│   ├── state.py                   (NEW) MultiAgentState TypedDict
│   ├── supervisor_node.py         (NEW) 分类节点
│   ├── bookkeeper_node.py         (NEW) 记账节点
│   ├── analyst_node.py            (NEW) 分析节点
│   └── graph_builder.py           (NEW) StateGraph 构建
├── agent.py                       (MODIFY) 新增 MultiAgentFinanceAgent 类
├── chat_server.py                 (MODIFY) 新增端点
└── circuit_breaker.py            (已存在，复用)
```

### 5.2 StateGraph 拓扑

```python
class MultiAgentState(TypedDict):
    messages: Annotated[list, add_messages]
    next_agent: str
    pending_confirmation: dict | None

# 节点: supervisor → bookkeeper / analyst / __end__
# 每个 specialist 执行后回到 supervisor，决定继续或结束
# recursion_limit = 5（框架内置循环保护）
```

### 5.3 HITL: LangGraph interrupt()

Python 侧利用 `langgraph.types.interrupt()` 在 `bookkeeper_node` 中检测到写操作时暂停图执行，等待外部 `Command(resume=...)` 恢复。

### 5.4 与 Java 的关键差异

| | Java 手动编排 | Python LangGraph |
|---|---|---|
| 路由 | `switch` 语句 | `Command(goto=...)` |
| 循环保护 | 手动计数器 | `recursion_limit=5` |
| 状态合并 | `SharedChatMemory` 手动读写 | `add_messages` 注解自动合并 |
| HITL | `PendingConfirmationStore` + Advisor | `interrupt()` 原生暂停 |
| 可视化 | 无 | LangSmith 图可视化 |

---

## 6. HITL 详细设计

### 6.1 状态机

```
IDLE → THINKING → PENDING → EXECUTING → THINKING → IDLE
                     │            ▲
                     └── CANCEL ──┘
```

### 6.2 SSE confirmation 事件格式

```json
{
  "event": "confirmation",
  "data": {
    "confirmationId": "uuid-xxx",
    "toolName": "add_transaction",
    "description": "记一笔交易 — 打车 35元",
    "parameters": {
      "accountId": 1, "type": "EXPENSE", "amount": 35.00,
      "category": "交通", "subCategory": "打车", "note": ""
    },
    "expiresAt": "2026-06-07T16:45:00+08:00"
  }
}
```

### 6.3 超时策略

- PendingConfirmationStore TTL = 60s
- 前端倒计时，到期自动发送 `/chat/cancel`
- Supervisor 收到 cancel 后生成 "操作已超时取消" 回复

### 6.4 触发条件

| 操作 | 是否需确认 |
|------|:----------:|
| `add_transaction` | ✅ 需确认 |
| `list_accounts` `query_balance` `list_transactions` `summarize_transactions` | ❌ 自动执行 |

原则：读自动、写确认。

---

## 7. Eval 扩展

### 7.1 新增维度：intent_routing（4 条）

| ID | 用户输入 | 期望路由 | 期望工具 |
|----|---------|---------|---------|
| `route-001` | "记一笔午餐30元" | `booking` | `add_transaction` |
| `route-002` | "本月花了多少" | `analysis` | `summarize_transactions` |
| `route-003` | "我的余额还有多少" | `booking` | `list_accounts` |
| `route-004` | "帮我写首诗" | `other` | `null`（拒绝） |

### 7.2 Schema 扩展

`EvalExpectations` 追加可选字段 `routedTo: String`，Golden Dataset 从 15 条扩展到 19 条。

### 7.3 前端确认卡片的 Eval

HITL 流程的评估不需要实际用户交互。Eval 只需验证：
- 写操作是否产生了 `confirmationId`（未直接执行）
- 确认后的执行结果是否正确

---

## 8. 前端适配

### 8.1 改动点

1. **TopBar 切换** — 新增 Toggle: `单 Agent / Multi-Agent`，影响调用的 SSE 端点
2. **Agent 标识** — `thinking` 事件扩展 `agent` 字段，前端显示 "📝 记账员正在处理..." / "📊 分析师正在处理..."
3. **ConfirmationCard 组件** — 解析 `event:confirmation`，渲染确认卡片，60s 倒计时，`[确认]/[修改]/[取消]` 按钮
4. **ChatPanel 改动** — 注册 `confirmation` 事件监听，将卡片插入消息流

### 8.2 组件树

```
ChatPanel.vue
├── TopBar.vue          (已存在，新增 Toggle)
├── MessageList.vue     (已存在，新增 Agent 标识)
│   └── ConfirmationCard.vue  (NEW)
└── InputArea.vue       (已存在，不变)
```

---

## 9. 兼容性

- 现有 `/chat/stream` 保留不变（单 Agent 模式）
- 新增 `/chat/multi-agent/stream`（Multi-Agent 模式）
- 前端 Toggle 默认在 "单 Agent"，用户可手动切换
- 两套架构共享同一 Backend + MCP Server，不影响数据层

---

## 10. 测试策略

| 层 | 测试内容 | 框架 |
|----|---------|------|
| 单元测试 | Supervisor 分类逻辑、Bookkeeper/Analyst Prompt 行为、PendingConfirmationStore CRUD | JUnit 5 / pytest |
| 集成测试 | Multi-Agent 完整链路（mock LLM 回复）、HITL 确认/取消流程 | Spring Boot Test / pytest-asyncio |
| Eval | 意图路由准确率（4 条新 case）、HITL 行为验证 | AgentEvalTest / test_agent_eval.py |
