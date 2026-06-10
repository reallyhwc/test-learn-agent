# Agent 端到端测试体系设计

## 背景

Multi-Agent 模式存在一个 conversationId 拼接 bug（`"multi:" + userId` 包含非法字符 `:`），导致前端调用 `/chat/multi-agent/stream` 时抛出 `IllegalArgumentException`。该 bug 未被现有测试发现，因为：

- `MultiAgentIntegrationTest` 只验证"组件能创建"，从未发起真实 HTTP 请求
- `ChatControllerTest` 只覆盖 Single-Agent 模式的 `/chat` 和 `/chat/stream`
- Eval golden-dataset 的 `multi_turn` 类别是多轮对话，不是 Multi-Agent 模式
- 回归测试子 Agent 没有 Multi-Agent 场景

本设计补全 Multi-Agent 端到端测试覆盖，同时统一 Single/Multi 两种模式的测试风格。

## 决策记录

- **LLM 依赖策略**：真实 LLM 调用（与现有 `ChatControllerTest` 一致），通过 `@ExtendWith(LlmCondition)` 在无 LLM 环境自动跳过
- **测试范围**：Multi-Agent 新增 + Single-Agent 统一整理
- **架构方案**：拆分 3 层结构 + 共享基类（方案 B）

## 文件结构

```
finance-agent/src/test/java/com/example/agent/controller/
├── ChatEndpointTestBase.java          # 新增：共享基类
├── SingleAgentEndpointTest.java       # 重命名自 ChatControllerTest.java
└── MultiAgentEndpointTest.java        # 新增：Multi-Agent 端到端
```

## ChatEndpointTestBase（共享基类）

抽象类，提取自现有 `ChatControllerTest` 的公共逻辑：

### 类级注解

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(LlmCondition.class)
```

### 静态初始化

- `loadDotEnv()`：从 `../.env` 或 `.env` 加载 LLM 配置到 System Properties

### 注入

- `MockMvc mockMvc`（protected）
- `ObjectMapper objectMapper`（protected）

### 共享工具方法

| 方法 | 签名 | 说明 |
|------|------|------|
| `chatAndGetReply` | `String chatAndGetReply(String userId, String message)` | POST `/api/chat` 非流式请求，返回 reply 文本 |
| `extractReply` | `String extractReply(String json)` | 从 JSON 响应中提取 `reply` 字段 |
| `extractSseContent` | `String extractSseContent(String sseText)` | 拼接所有 `data:` 行内容为完整文本 |
| `streamAndGetContent` | `String streamAndGetContent(String endpoint, String userId, String message)` | POST 到指定流式端点，执行 asyncDispatch，返回完整 SSE 文本（新增，核心复用方法） |
| `streamAndGetRaw` | `String streamAndGetRaw(String endpoint, String userId, String message)` | 同上但返回原始 SSE 文本（含 `event:` 和 `data:` 行），用于验证 SSE 事件类型 |

## SingleAgentEndpointTest

继承 `ChatEndpointTestBase`，保留现有 7 个用例，无逻辑变更：

1. `shouldReturnNonEmptyResponse` — 非流式返回非空
2. `shouldNotContainDegradationText` — 无降级文案
3. `shouldMentionFinancialDataWhenQueryingBalance` — 余额查询包含金额关键词
4. `shouldHandleDiningExpenseQuery` — 餐饮查询包含关键词
5. `shouldStreamWithSSEHeaders` — 流式请求 async started
6. `shouldStreamTokensWithDataPrefix` — SSE 包含 `data:` 前缀
7. `shouldStreamCompleteResponse` — 流式响应完整且无降级

重构点：删除重复的私有方法（移至基类），其余不变。

## MultiAgentEndpointTest

继承 `ChatEndpointTestBase`，新增 7 个用例：

### 用例清单

| # | 方法名 | 输入 | 验证点 | 防护的风险 |
|---|--------|------|--------|-----------|
| 1 | `shouldStreamFromMultiAgentEndpoint` | "你好" | SSE 流正常返回，包含 `data:`，HTTP 200 | 管道不炸（本次 bug 的直接防护） |
| 2 | `shouldRouteAnalysisToAnalyst` | "看下我在餐饮上花了多少钱" | 响应包含"餐饮"和金额 | Supervisor 正确分派到 Analyst |
| 3 | `shouldRouteBookkeepingToBookkeeper` | "我有哪些账户" | 响应包含账户名称 | Supervisor 正确分派到 Bookkeeper |
| 4 | `shouldReturnAgentIdentityEvent` | "查一下最近的支出" | 原始 SSE 包含 `event:thinking` + "分析师"或"记账员" | Agent 标识事件正确发射 |
| 5 | `shouldHandleNonFinancialInput` | "今天天气怎么样" | 不抛异常，返回非空响应 | 非财务输入不崩溃 |
| 6 | `shouldWorkWithDifferentUsers` | userId="e2e-test-user" | SSE 流正常，无 IllegalArgumentException | conversationId 校验通过 |
| 7 | `shouldReturnCompleteAnalysisResponse` | "帮我汇总一下上个月的收支" | 响应包含"收入"或"支出"关键词，长度 > 30 字符 | Analyst 端到端完整输出 |

### 验证策略

- 用例 1/5/6：验证**管道健壮性**（不抛异常、流正常）
- 用例 2/3/4/7：验证**行为正确性**（分派、工具调用、输出质量）
- 所有用例使用 `AiResponseValidator` 做通用验证（非空、无降级）

## 测试命名规范

遵循 CLAUDE.md：`should{预期行为}`，如 `shouldStreamFromMultiAgentEndpoint`。

## 运行方式

```bash
# 运行全部 Agent 端到端测试（需要 LLM + MCP Server）
cd finance-agent && ./mvnw test -Dtest="SingleAgentEndpointTest,MultiAgentEndpointTest"

# 仅跑 Multi-Agent
cd finance-agent && ./mvnw test -Dtest="MultiAgentEndpointTest"
```

无 LLM 环境（CI 默认）自动跳过，不会红。

## 不在本次范围

- Python 栈的端到端测试（Python 有独立的 pytest 体系）
- Mock LLM 层的 CI 快速测试（后续迭代可加）
- HITL 确认流程测试（该功能尚未实现，见 CLAUDE.md Known Tech Debt）
