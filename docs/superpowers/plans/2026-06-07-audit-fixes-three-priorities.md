# 审计改进 — 三优先级实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复审计发现的 P1/P2/P3 问题，提升 Agent 韧性、双栈一致性、可观测性和 Eval 覆盖度

**Architecture:** 按照"先修 Bug → 再加强可观测性 → 最后扩展 Eval"的顺序执行，每步一个 commit，Java/Python 双栈同步修改

**Tech Stack:** Java 17/Spring AI/Maven, Python 3.10+/FastMCP/LangChain, pytest

---

### Task 1: 修复熔断器 HALF_OPEN 状态机 Bug

**Files:**
- Modify: `finance-agent/src/main/java/com/example/agent/resilience/SimpleCircuitBreaker.java:66-73`
- Modify: `finance-agent/src/test/java/com/example/agent/resilience/SimpleCircuitBreakerTest.java` (add new test)

- [ ] **Step 1: 添加失败的测试用例**

在 `SimpleCircuitBreakerTest.java` 末尾添加：

```java
@Test
void shouldRejectCallsAfterFailedProbeWithThresholdGt1() throws InterruptedException {
    // 使用与生产环境相同的 threshold=3
    var breaker = new SimpleCircuitBreaker("test", 3, 50);
    // 触发熔断
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordFailure();
    assertThat(breaker.isCallPermitted()).isFalse();

    Thread.sleep(100);
    // HALF_OPEN 试探
    assertThat(breaker.isCallPermitted()).isTrue();
    // 试探失败 → 应立即回到 OPEN
    breaker.recordFailure();
    // Bug: 当前 failureCount 从 0 到 1，1 < 3，所以不会回到 OPEN
    assertThat(breaker.isCallPermitted()).isFalse();
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd finance-agent && export JAVA_HOME=/usr/local/opt/openjdk@17 && ./mvnw test -Dtest=SimpleCircuitBreakerTest#shouldRejectCallsAfterFailedProbeWithThresholdGt1 -DfailIfNoTests=false
```

- [ ] **Step 3: 修复 `recordFailure()` 方法**

修改 `SimpleCircuitBreaker.java:67-73`：

```java
/** 记录失败调用，累计达到阈值后打开熔断。HALF_OPEN 下失败立即回到 OPEN。 */
public void recordFailure() {
    lastFailureTime.set(System.currentTimeMillis());
    if (state == State.HALF_OPEN) {
        state = State.OPEN;
        failureCount.set(0);
        return;
    }
    int failures = failureCount.incrementAndGet();
    if (failures >= failureThreshold) {
        state = State.OPEN;
    }
}
```

- [ ] **Step 4: 运行全部熔断器测试确认通过**

```bash
cd finance-agent && export JAVA_HOME=/usr/local/opt/openjdk@17 && ./mvnw test -Dtest=SimpleCircuitBreakerTest -DfailIfNoTests=false
```

Expected: 9 tests pass (8 existing + 1 new)

- [ ] **Step 5: Commit**

```bash
git add finance-agent/src/main/java/com/example/agent/resilience/SimpleCircuitBreaker.java \
        finance-agent/src/test/java/com/example/agent/resilience/SimpleCircuitBreakerTest.java
git commit -m "fix(resilience): 熔断器 HALF_OPEN 下 recordFailure 应立即回到 OPEN

修复: 当 failureThreshold > 1 时，HALF_OPEN 探测失败后 failureCount 从
0 到 1 不足以触发回到 OPEN，导致熔断器停留在 HALF_OPEN 状态。
现在 HALF_OPEN 下失败直接回到 OPEN，与经典熔断器语义一致。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Python Output Guardrail 接入 agent.py

**Files:**
- Modify: `finance-agent-py/agent.py:13` (import), `finance-agent-py/agent.py:102-112` (chat output), `finance-agent-py/agent.py:144-151` (chat_stream output)

- [ ] **Step 1: 修改 import 语句**

修改 `agent.py:13`：

```python
from guardrails import REJECTION_REPLY, audit_tool_calls, check_amount_hallucination, extract_amounts, is_prompt_injection
```

- [ ] **Step 2: chat() 方法接入 Output Guardrail**

修改 `agent.py:102-112`，在工具审计之后、记忆追加之前插入幻觉检测：

```python
        # 第二层防护: 工具调用审计
        audit_tool_calls(result.get("messages", []), user_id)

        # 提取工具返回中的金额用于第三层幻觉检测
        tool_amounts: list = []
        for m in result.get("messages", []):
            if hasattr(m, "type") and m.type == "tool":
                tool_amounts.extend(extract_amounts(str(m.content)))

        output = ""
        for m in reversed(result.get("messages", [])):
            if hasattr(m, "content") and m.type == "ai":
                output = str(m.content)
                break

        # 第三层防护: 金额幻觉检测
        if check_amount_hallucination(output, tool_amounts):
            logger.warning("OutputGuardrail: 幻觉检测触发 userId=%s", user_id)

        memory.append("user", message)
        memory.append("assistant", output)
        return output
```

- [ ] **Step 3: chat_stream() 方法接入 Output Guardrail**

修改 `agent.py:144-151`，在 token 收集完成后、记忆追加之前插入幻觉检测：

```python
        memory.append("user", message)
        full_text = "".join(full_response)

        # 第三层防护: 金额幻觉检测
        if full_text and check_amount_hallucination(full_text, []):
            logger.warning("OutputGuardrail(stream): 幻觉检测触发 userId=%s", user_id)

        memory.append("assistant", full_text)
```

- [ ] **Step 4: 运行 Python guardrail 测试确认**

```bash
cd finance-agent-py && python -m pytest test_guardrails.py -v
```

- [ ] **Step 5: Commit**

```bash
git add finance-agent-py/agent.py
git commit -m "fix(agent-py): Output Guardrail 第三层金额幻觉检测接入 agent.py

chat() 和 chat_stream() 方法现在在 LLM 回复后调用
check_amount_hallucination()，与 Java 栈行为一致。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: 统一 add_transaction 参数名

**Files:**
- Modify: `finance-mcp-server-py/server.py:144` (参数名 `transaction_type` → `type`)

- [ ] **Step 1: 修改 Python MCP 工具签名**

修改 `server.py:144`：

```python
@mcp.tool()
async def add_transaction(
    user_id: str,
    account_id: int,
    type: str,           # ← 原 transaction_type，统一为 type
    amount: str,
    category: str,
    sub_category: str,
    note: str = "",
) -> Any:
```

- [ ] **Step 2: 同步函数体内的变量引用**

修改 `server.py:169-170`，将 `transaction_type` 替换为 `type`：

```python
    type_upper = type.upper() if type else ""
    if type_upper not in ("INCOME", "EXPENSE"):
```

- [ ] **Step 3: 运行 Python 工具测试确认**

```bash
cd finance-mcp-server-py && python -m pytest tests/test_tools.py -v
```

- [ ] **Step 4: Commit**

```bash
git add finance-mcp-server-py/server.py
git commit -m "fix(mcp-server-py): add_transaction 参数名 transaction_type → type

与 Java 栈 FinanceTools.java 保持一致，确保 Eval Golden Dataset
跨栈通用。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: mcp-server (Java) 日志脱敏

**Files:**
- Create: `finance-mcp-server/src/main/java/com/example/mcp/util/LogMaskUtils.java`
- Modify: `finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java` (全部日志语句)

- [ ] **Step 1: 创建 LogMaskUtils**

创建 `finance-mcp-server/src/main/java/com/example/mcp/util/LogMaskUtils.java`：

```java
package com.example.mcp.util;

/** 日志脱敏工具 — 与 finance-backend 的 LogMaskUtils 功能一致。 */
public final class LogMaskUtils {

    private LogMaskUtils() {}

    /** 用户ID脱敏: 保留前3位 + *** */
    public static String maskUserId(String userId) {
        if (userId == null || userId.isBlank()) return "***";
        if (userId.length() <= 3) return userId.charAt(0) + "***";
        return userId.substring(0, 3) + "***";
    }

    /** 金额脱敏: 仅保留数字位数信息 */
    public static String maskAmount(java.math.BigDecimal amount) {
        if (amount == null) return "***";
        return "<金额:" + amount.precision() + "位>";
    }

    /** 金额脱敏(string版) */
    public static String maskAmountStr(String amount) {
        if (amount == null || amount.isBlank()) return "***";
        return "<金额:" + amount.length() + "位>";
    }
}
```

- [ ] **Step 2: 修改 FinanceTools.java 日志脱敏**

给 `FinanceTools.java` 添加 import：
```java
import com.example.mcp.util.LogMaskUtils;
```

修改日志语句（关键几处）：

| 原代码位置 | 原始写法 | 改为 |
|-----------|---------|------|
| `FinanceTools.java:88` | `log.info("query_balance: userId={}, accountId={}", userId, accountId)` | `log.info("query_balance: userId={}, accountId={}", LogMaskUtils.maskUserId(userId), accountId)` |
| `FinanceTools.java:130` | `log.info("list_transactions: userId={}, filters={}", userId, filters)` | `log.info("list_transactions: userId={}, filters={}", LogMaskUtils.maskUserId(userId), filters)` |
| `FinanceTools.java:162` | 已记录 URI（包含 userId），保持不变（URI 已在日志框架层格式化） | 无需修改 |
| `FinanceTools.java:177` | `log.info("list_transactions: total={}, pageSize={}", total, pageSize)` | 无需修改（无敏感信息） |
| `FinanceTools.java:228` | `log.info("summarize: userId={}, filters={}", userId, filters)` | `log.info("summarize: userId={}, filters={}", LogMaskUtils.maskUserId(userId), filters)` |
| `FinanceTools.java:326-327` | `log.info("add_transaction: userId={}, accountId={}, type={}, amount={}, category={}/{}"...)` | `log.info("add_transaction: userId={}, accountId={}, type={}, amount={}, category={}/{}", LogMaskUtils.maskUserId(userId), accountId, type, LogMaskUtils.maskAmount(amount), category, subCategory)` |
| `FinanceTools.java:373` | `log.info("list_accounts: userId={}", userId)` | `log.info("list_accounts: userId={}", LogMaskUtils.maskUserId(userId))` |

- [ ] **Step 3: 编译验证**

```bash
cd finance-mcp-server && export JAVA_HOME=/usr/local/opt/openjdk@17 && ./mvnw compile -q
```

- [ ] **Step 4: 运行测试确认**

```bash
cd finance-mcp-server && export JAVA_HOME=/usr/local/opt/openjdk@17 && ./mvnw test -q
```

- [ ] **Step 5: Commit**

```bash
git add finance-mcp-server/src/main/java/com/example/mcp/util/LogMaskUtils.java \
        finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java
git commit -m "feat(mcp-server): 添加 LogMaskUtils + 工具日志脱敏

所有 FinanceTools 日志中的 userId 和 amount 现在经过脱敏，
与 finance-backend 保持一致。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Python 流式 SSE 增加 thinking/error 通道

**Files:**
- Modify: `finance-agent-py/chat_server.py:170-176` (event generator)
- Modify: `finance-agent-py/agent.py:135-145` (chat_stream yield thinking events)

- [ ] **Step 1: agent.py chat_stream 增加 thinking 事件 yield**

修改 `agent.py:135-145`，在 astream_events 循环中区分 thinking 和 data：

```python
        full_response: list[str] = []
        try:
            async with asyncio.timeout(120):
                async for event in self._agent.astream_events(
                    {"messages": messages}, version="v2"
                ):
                    kind = event.get("event", "")
                    if kind == "on_tool_start":
                        tool_name = event.get("name", "unknown")
                        yield {"event": "thinking", "data": f"正在调用 {tool_name}..."}
                    elif kind == "on_chat_model_stream":
                        chunk = event["data"]["chunk"]
                        if hasattr(chunk, "content") and chunk.content:
                            token = str(chunk.content)
                            full_response.append(token)
                            yield {"data": token}
        except asyncio.TimeoutError:
            logger.warning("流式超时: userId=%s", user_id)
            yield {"event": "error", "data": "AI 响应超时，请简化问题或稍后重试"}
```

- [ ] **Step 2: chat_server.py event_generator 适配 dict 输出**

修改 `chat_server.py:170-176`，`EventSourceResponse` 需要适应 `agent.chat_stream` 现在 yield dict：

```python
    async def event_generator():
        try:
            async for event in agent.chat_stream(user_id, message):
                if isinstance(event, dict):
                    yield event
                else:
                    yield {"data": event}
        except Exception as e:
            logger.error("流式错误: %s", e)
            yield {"event": "error", "data": "AI 服务响应异常，请稍后重试"}
```

- [ ] **Step 3: 运行 Python 测试确认**

```bash
cd finance-agent-py && python -m pytest tests/test_chat_server.py -v
```

- [ ] **Step 4: Commit**

```bash
git add finance-agent-py/agent.py finance-agent-py/chat_server.py
git commit -m "feat(agent-py): 流式 SSE 增加 event:thinking 和 event:error 通道

与 Java 栈三通道 (data/thinking/error) 对齐。工具调用时发送
thinking 事件，超时发送 error 事件。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: Python 栈加熔断器

**Files:**
- Create: `finance-agent-py/circuit_breaker.py`
- Modify: `finance-agent-py/system_prompt.py:102-117` (fetch_account_summary)

- [ ] **Step 1: 创建熔断器模块**

创建 `finance-agent-py/circuit_breaker.py`：

```python
"""简易熔断器 — Java SimpleCircuitBreaker 的 Python 移植。"""
import time
import threading
import logging
from enum import Enum

logger = logging.getLogger(__name__)


class State(Enum):
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"


class SimpleCircuitBreaker:
    """状态机: CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN"""

    def __init__(self, name: str, failure_threshold: int = 3, recovery_timeout_ms: int = 30_000):
        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_timeout_ms = recovery_timeout_ms
        self._state = State.CLOSED
        self._failure_count = 0
        self._last_failure_time = 0.0
        self._lock = threading.Lock()

    @property
    def state(self) -> State:
        return self._state

    def is_call_permitted(self) -> bool:
        with self._lock:
            if self._state == State.CLOSED:
                return True
            if self._state == State.OPEN:
                if (time.monotonic() * 1000 - self._last_failure_time) >= self.recovery_timeout_ms:
                    self._state = State.HALF_OPEN
                    logger.info("熔断器 %s: OPEN → HALF_OPEN", self.name)
                    return True
                return False
            # HALF_OPEN
            return True

    def record_success(self):
        with self._lock:
            self._failure_count = 0
            if self._state != State.CLOSED:
                logger.info("熔断器 %s: → CLOSED", self.name)
            self._state = State.CLOSED

    def record_failure(self):
        with self._lock:
            self._last_failure_time = time.monotonic() * 1000
            if self._state == State.HALF_OPEN:
                self._state = State.OPEN
                self._failure_count = 0
                logger.warning("熔断器 %s: HALF_OPEN 探测失败 → OPEN", self.name)
                return
            self._failure_count += 1
            if self._failure_count >= self.failure_threshold:
                self._state = State.OPEN
                logger.warning("熔断器 %s: 连续失败 %s 次 → OPEN", self.name, self._failure_count)
```

- [ ] **Step 2: system_prompt.py 接入熔断器**

修改 `system_prompt.py:102-117`：

```python
from circuit_breaker import SimpleCircuitBreaker

_account_circuit_breaker = SimpleCircuitBreaker("account-context", 3, 30_000)


async def fetch_account_summary(user_id: str) -> str:
    """从 Backend 拉取账户摘要注入 system prompt。
    失败时返回空字符串，让 LLM 自己调工具。
    使用熔断器保护后端不可用时的快速失败。"""
    if not _account_circuit_breaker.is_call_permitted():
        logger.warning("熔断器 %s 已打开，跳过账户上下文拉取", _account_circuit_breaker.name)
        return ""

    try:
        client = _get_http_client()
        resp = await client.get(
            "/api/accounts",
            params={"userId": user_id},
        )
        resp.raise_for_status()
        accounts = resp.json()
        _account_circuit_breaker.record_success()
        return _format_account_summary(accounts)
    except Exception as e:
        _account_circuit_breaker.record_failure()
        logger.warning("拉取账户上下文失败 userId=%s: %s", user_id, e)
        return ""
```

- [ ] **Step 3: 运行相关测试确认**

```bash
cd finance-agent-py && python -m pytest tests/test_system_prompt.py -v
```

- [ ] **Step 4: Commit**

```bash
git add finance-agent-py/circuit_breaker.py finance-agent-py/system_prompt.py
git commit -m "feat(agent-py): 添加熔断器保护账户上下文拉取

fetch_account_summary 现在使用 SimpleCircuitBreaker 快速失败，
避免后端不可用时每次请求都超时等待。Java 移植，逻辑一致。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: Eval Golden Dataset 扩展

**Files:**
- Modify: `evals/golden-dataset.json`

- [ ] **Step 1: 扩展 golden-dataset.json**

在现有 9 条用例基础上，增加多轮对话、工具选择冲突、用户纠错场景。修改 `evals/golden-dataset.json`，在 `"cases"` 数组中追加以下 6 条新用例：

```json
{
  "id": "multi-001",
  "dimension": "multi_turn",
  "description": "多轮对话: 先查余额再记支出, 上下文保持",
  "messages": [
    {"role": "user", "content": "我的银行卡余额多少"},
    {"role": "user", "content": "刚花了35块在食堂吃饭，记一笔"}
  ],
  "expect": {
    "tool_calls": ["add_transaction"],
    "tool_params_contain": ["35", "EXPENSE", "餐饮", "食堂"],
    "response_contains_any": ["已记录", "记账"],
    "response_not_contains": ["大约", "大概", "左右", "约"]
  }
},
{
  "id": "multi-002",
  "dimension": "multi_turn",
  "description": "多轮对话: 查流水后追问汇总, 上下文保持",
  "messages": [
    {"role": "user", "content": "我最近有什么支出"},
    {"role": "user", "content": "帮我按类别汇总一下"}
  ],
  "expect": {
    "tool_calls": ["summarize_transactions"],
    "tool_params_contain": ["EXPENSE"],
    "response_contains_any": ["餐饮", "交通", "购物"],
    "response_not_contains": ["大约", "大概"]
  }
},
{
  "id": "conflict-001",
  "dimension": "tool_conflict",
  "description": "工具选择冲突: 查余额有两个工具可用，应选 list_accounts",
  "messages": [
    {"role": "user", "content": "我有多少钱"}
  ],
  "expect": {
    "tool_calls": ["list_accounts"],
    "tool_params_contain": ["default"],
    "response_not_contains": ["query_balance", "大约", "大概"]
  }
},
{
  "id": "conflict-002",
  "dimension": "tool_conflict",
  "description": "工具选择冲突: 查交易不需要调用 query_balance",
  "messages": [
    {"role": "user", "content": "帮我看看我最近的交易记录"}
  ],
  "expect": {
    "tool_calls": ["list_transactions"],
    "response_not_contains": ["query_balance", "大约", "大概"]
  }
},
{
  "id": "correction-001",
  "dimension": "correction",
  "description": "用户纠错: 先让查账户再纠正说查流水",
  "messages": [
    {"role": "user", "content": "帮我查一下我的账户"},
    {"role": "user", "content": "不对，我是说查一下我的交易记录"}
  ],
  "expect": {
    "tool_calls": ["list_transactions"],
    "response_contains_any": ["交易", "流水", "笔"],
    "response_not_contains": ["大约", "大概"]
  }
},
{
  "id": "correction-002",
  "dimension": "correction",
  "description": "用户纠错: 记错金额后更正",
  "messages": [
    {"role": "user", "content": "帮我记一笔，打车花了50元"},
    {"role": "user", "content": "等一下，不是50，是35元"}
  ],
  "expect": {
    "tool_calls": ["add_transaction"],
    "tool_params_contain": ["35", "EXPENSE", "交通", "打车"],
    "response_contains_any": ["已记录", "记账"],
    "response_not_contains": ["50"]
  }
}
```

- [ ] **Step 2: 更新用例描述文档**

修改 `evals/golden-dataset.json` 顶部的 `"description"` 字段更新：

- 总用例数: 9 → 15
- 新增维度: `multi_turn`（2条）、`tool_conflict`（2条）、`correction`（2条）

- [ ] **Step 3: 验证 JSON 格式正确**

```bash
python3 -c "import json; json.load(open('evals/golden-dataset.json')); print('JSON valid')"
```

- [ ] **Step 4: Commit**

```bash
git add evals/golden-dataset.json
git commit -m "feat(eval): Golden Dataset 扩展至 15 条 — 增加多轮/冲突/纠错场景

新增 3 个维度 6 条用例:
- multi_turn (2): 上下文保持验证
- tool_conflict (2): 工具选择优先级验证
- correction (2): 用户纠错后 Agent 行为验证

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Execution Summary

| Task | 改动量 | 测试 | 顺序依赖 |
|------|--------|------|----------|
| T1: 熔断器 Bug | ~10 行 Java | 已有 8 + 新增 1 测试 | 无 |
| T2: Output Guardrail | ~15 行 Python | 已有 guardrail 测试 | 无 |
| T3: 参数名统一 | ~3 行 Python | 已有 MCP 工具测试 | 无 |
| T4: 日志脱敏 | ~40 行 Java | 已有 FinanceToolsTest | 无 |
| T5: Streaming 通道 | ~15 行 Python | 已有 chat_server 测试 | 无 |
| T6: Python 熔断器 | ~80 行 Python | 已有 system_prompt 测试 | 无 |
| T7: Eval 扩展 | JSON 数据 | 无代码测试 | 无 |

**T1-T3 可并行执行（不同类型/文件无冲突），T4-T6 可并行，T7 独立。**
