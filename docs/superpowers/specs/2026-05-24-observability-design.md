# 可观测性体系设计

> **状态：** 待用户审阅
> **日期：** 2026-05-24

## 目标

为 4 个服务（Backend、MCP Server、Agent、Frontend）建立统一的可观测性体系，覆盖日志、指标、Token 统计、LLM 容错、对话反馈和 Context 监控。

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│                    Frontend (:5173)                          │
│  TTFT 打点 │ 对话反馈 │ 请求耗时 │ console 分级              │
└──────────────────────┬───────────────────────────────────────┘
                       │ REST / SSE
┌──────────────────────▼───────────────────────────────────────┐
│                   Agent (:8081)                               │
│  Token 统计 │ 重试/降级 │ 耗时打点 │ Context 监控 │ Logback  │
│  Actuator + Prometheus (:8081/actuator)                      │
└──────────────────────┬───────────────────────────────────────┘
                       │ MCP over SSE
┌──────────────────────▼───────────────────────────────────────┐
│                 MCP Server (:8082)                            │
│  工具调用计数 │ 耗时 Timer │ 成功/失败率 │ Logback           │
│  Actuator + Prometheus (:8082/actuator)                      │
└──────────────────────┬───────────────────────────────────────┘
                       │ REST
┌──────────────────────▼───────────────────────────────────────┐
│                  Backend (:8080)                              │
│  API 请求计数 │ 耗时 Timer │ 错误率 │ Logback                │
│  Actuator + Prometheus (:8080/actuator)                      │
└──────────────────────────────────────────────────────────────┘
```

---

## 一、日志基础设施

### 1.1 Logback 配置

三个 Java 服务各创建 `src/main/resources/logback-spring.xml`，配置相同：

- **终端输出**：彩色文本，`%d{HH:mm:ss.SSS} %highlight(%-5level) [%logger{36}] %msg%n`
- **文件输出**：JSON 格式，滚动策略按天 + 500MB 上限，保留 30 天
- **日志路径**：`log/agent.log`、`log/agent.json.log`、`log/mcp-server.log`、`log/mcp-server.json.log`、`log/backend.log`、`log/backend.json.log`

JSON 格式包含字段：`@timestamp`、`level`、`service`、`thread`、`logger`、`message`、`userId`（通过 MDC 传递）。

### 1.2 统一使用 @Slf4j

Backend 当前无日志，需在所有 Controller、Service、Repository 加 `@Slf4j` 并补充关键日志点。MCP Server 和 Agent 已有的 `LoggerFactory.getLogger` 统一改为 `@Slf4j`。

### 1.3 关键日志点

| 服务 | 日志点 | 级别 |
|------|--------|------|
| Backend | 每个 API 进入/返回（method、path、userId、status） | INFO |
| Backend | CSV 读写异常 | ERROR |
| MCP Server | 每个工具调用进入/返回（tool_name、userId、结果摘要） | INFO |
| MCP Server | Backend 调用失败 | ERROR |
| Agent | 对话请求进入（userId、消息长度） | INFO |
| Agent | LLM 调用耗时（含 token 数） | INFO |
| Agent | 首 Token 时间 | INFO |
| Agent | LLM 调用失败/重试 | WARN/ERROR |
| Agent | 记忆文件读写异常 | ERROR |

---

## 二、Prometheus 指标

### 2.1 依赖

三个 Java 服务各添加：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

`application.yml` 配置：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

### 2.2 Backend 指标

| 指标名 | 类型 | Tag | 说明 |
|--------|------|-----|------|
| `api.requests.total` | Counter | endpoint, method, status | API 请求总数 |
| `api.requests.duration` | Timer | endpoint, method | 请求耗时 |
| `api.requests.errors` | Counter | endpoint, error_type | 错误数 |

通过自定义 `HandlerInterceptor` 统一收集，不侵入 Controller 代码。

### 2.3 MCP Server 指标

| 指标名 | 类型 | Tag | 说明 |
|--------|------|-----|------|
| `mcp.tool.calls.total` | Counter | tool_name, status | 工具调用总数 |
| `mcp.tool.calls.duration` | Timer | tool_name | 工具调用耗时 |
| `mcp.tool.calls.errors` | Counter | tool_name, error_type | 工具调用错误数 |

在每个 `@McpTool` 方法内手动记录。

### 2.4 Agent 指标

| 指标名 | 类型 | Tag | 说明 |
|--------|------|-----|------|
| `agent.chat.requests` | Counter | userId, type(stream/normal) | 对话请求总数 |
| `agent.chat.ttft` | Timer | userId | 首Token响应时间 |
| `agent.chat.duration` | Timer | userId | 单次对话总耗时 |
| `agent.llm.tokens.input` | Counter | model | 输入 Token 消耗量 |
| `agent.llm.tokens.output` | Counter | model | 输出 Token 消耗量 |
| `agent.llm.tokens.speed` | Gauge | - | Token 生成速度(tokens/s) |
| `agent.llm.errors` | Counter | error_type | LLM 调用错误数（含超时、限流、服务端错误） |
| `agent.llm.retries` | Counter | - | LLM 重试次数 |
| `agent.memory.messages` | Gauge | userId | 当前对话记忆消息数 |
| `agent.memory.size_bytes` | Gauge | userId | 记忆文件大小(bytes) |

---

## 三、Token 消耗统计

### 3.1 获取方式

- **非流式** (`/api/chat`)：从 `ChatResponse.getMetadata().getUsage()` 获取
- **流式** (`/api/chat/stream`)：Spring AI 流式场景下 `Usage` 需从 `FluxChatResponse` 的 `doOnComplete` 中获取，或通过自定义 `ChatClientFilter` 拦截

### 3.2 存储

Token 使用记录写入 `finance-agent/data/token-usage.jsonl`：

```json
{"timestamp":"2026-05-24T10:30:15","userId":"zhangsan","inputTokens":245,"outputTokens":89,"model":"deepseek-chat","durationMs":3200,"stream":true}
```

每次对话调用追加一行。不涉及真实金额计算（DeepSeek 有缓存命中机制）。

### 3.3 Counter 上报

同时通过 Micrometer Counter 累加，可在 Prometheus 查询累计消耗：
```
agent_llm_tokens_input_total{model="deepseek-chat"}
agent_llm_tokens_output_total{model="deepseek-chat"}
```

---

## 四、LLM 调用失败处理

### 4.1 重试配置

`application.yml`：
```yaml
spring:
  ai:
    retry:
      max-attempts: 2
      backoff:
        initial-interval: 1000
        multiplier: 2
```

重试过程通过 `RetryListener` 记录日志和指标。

### 4.2 降级策略

`@ExceptionHandler` 统一拦截 LLM 调用异常，返回 HTTP 200 + 降级文案 `"抱歉，AI 服务暂时不可用，请稍后重试。"`，而非 500。异常类型对应：
- 超时 (ReadTimeoutException) → 提示超时
- 限流 (HttpClientErrorException 429) → 提示繁忙
- 其他 → 通用降级提示

---

## 五、工具调用成功率

MCP Server 中每个 `@McpTool` 方法用 try-catch 包裹：
- try 块最后 `counter.increment("success")` + `timer.record()`
- catch 块 `counter.increment("error", error_type)` + 重新抛出给 MCP 框架

---

## 六、对话反馈

### 6.1 前端

每条 AI 回复消息下方添加 👍 👎 两个图标，点击后 POST 到 Agent：
```
POST /api/feedback
{ "userId": "zhangsan", "messageId": "msg-1716480000000", "rating": "positive" }
```
点击后图标变色确认，不可重复点击同一条消息。

### 6.2 后端

Agent 新增 `FeedbackController`：
- 接收反馈请求
- 写入 `finance-agent/data/feedback.jsonl`
- 返回 200

JSONL 格式：
```json
{"timestamp":"2026-05-24T10:31:00","userId":"zhangsan","messageId":"msg-1716480000000","rating":"positive"}
```

未来可扩展：按 userId 聚合好评率、用于微调数据集收集。

---

## 七、Context 窗口监控

- `JsonFileChatMemory` 每次 `add()` 时更新 Gauge：当前消息总数、文件大小
- ChatController 在 system prompt 中注入当前记忆占用量，让 LLM 感知上下文余量
- 前端 ChatPanel 在对话区域显示 *"记忆: 12/20 条"*

---

## 八、前端打点

| 打点项 | 实现 | 日志格式 |
|--------|------|----------|
| TTFT | `performance.now()` 计算首字节到达时间 | `[Agent] TTFT: 1230ms` |
| 总耗时 | `console.time('chat-request')` / `console.timeEnd()` | `[Agent] 总耗时: 4520ms` |
| API 错误 | fetch catch 块 | `[API] Error: /api/transactions - 500 Internal Server Error` |
| SSE 异常 | EventSource onerror | `[Agent] SSE 连接异常` |

统一使用 `[Agent]` `[API]` `[Chart]` 等前缀区分来源。

---

## 九、文件清单

### 创建

| 文件 | 说明 |
|------|------|
| `finance-backend/src/main/resources/logback-spring.xml` | Backend 日志配置 |
| `finance-mcp-server/src/main/resources/logback-spring.xml` | MCP Server 日志配置 |
| `finance-agent/src/main/resources/logback-spring.xml` | Agent 日志配置 |
| `finance-backend/src/main/java/.../config/MetricsInterceptor.java` | API 指标拦截器 |
| `finance-backend/src/main/java/.../config/WebMvcMetricsConfig.java` | 注册拦截器 |
| `finance-agent/src/main/java/.../metrics/AgentMetrics.java` | Agent 指标封装 |
| `finance-agent/src/main/java/.../config/RetryConfig.java` | LLM 重试监听器 |
| `finance-agent/src/main/java/.../controller/FeedbackController.java` | 对话反馈接口 |
| `finance-agent/src/main/java/.../exception/GlobalExceptionHandler.java` | 统一异常处理 |

### 修改

| 文件 | 改动 |
|------|------|
| `finance-backend/pom.xml` | 加 actuator + micrometer 依赖 |
| `finance-mcp-server/pom.xml` | 加 actuator + micrometer 依赖 |
| `finance-agent/pom.xml` | 加 actuator + micrometer 依赖，加 Lombok |
| `finance-backend/src/main/resources/application.yml` | 加 management 配置 |
| `finance-mcp-server/src/main/resources/application.yml` | 加 management 配置 |
| `finance-agent/src/main/resources/application.yml` | 加 management + retry 配置 |
| `finance-backend/*/controller/*.java` | 加 @Slf4j + 日志 |
| `finance-backend/*/service/*.java` | 加 @Slf4j + 日志 |
| `finance-backend/*/repository/*.java` | 加 @Slf4j + 日志 |
| `finance-mcp-server/*/tool/FinanceTools.java` | 加指标记录 + @Slf4j |
| `finance-agent/*/controller/ChatController.java` | 加指标记录、Token 统计、重试 |
| `finance-agent/*/memory/JsonFileChatMemory.java` | 加 Gauge 暴露 |
| `finance-agent/*/config/ChatMemoryConfig.java` | 暴露 memory Gauge |
| `finance-frontend/src/components/ChatPanel.vue` | 加打点 + 反馈按钮 + 记忆条数显示 |
| `finance-frontend/src/components/ChatMessage.vue` | 加反馈图标 |
| `.gitignore` | 加 `log/` 目录 |
