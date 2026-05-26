# AI 对话全链路性能优化 — 目标 ≤ 10s

将 AI 对话端到端耗时从 15s+ 降至 7-10s。核心策略：减少 LLM 处理的 token 量、避免不必要的工具调用往返、加固基础设施。

> [!IMPORTANT]
> "查全部资产"场景下，prompt 里已有完整账户数据（AccountContextBuilder 30s 缓存），LLM 理论上不需要调工具。15s 耗时主要因为：① prompt 指令不够强导致 LLM 仍调工具 ② ChatMemory 无 token 控制，历史消息撑大 prompt ③ 工具返回数据量过大（pageSize=1000）

## Proposed Changes

### 一、LLM 推理减负（预计节省 3-5s）

#### [MODIFY] [application.yml](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/main/resources/application.yml)
- 新增 `max-tokens: 1024`：限制输出长度，避免 LLM 生成冗长回复（"查资产"回答通常 200-300 token）
- 新增 LLM HTTP 超时：`connect-timeout: 5s`、`read-timeout: 60s`，避免 LLM 卡住无限等待

---

#### [MODIFY] [ChatController.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/main/java/com/example/agent/controller/ChatController.java)
- **优化 system prompt**（`buildSystemPrompt()` 方法）：
  - 强化"直接读取上下文"的指令，改为命令式 + 粗体 + 示例：`**"我的资产/余额/账户" → 100%直接读取上方数据回答，绝对不要调用任何工具**`
  - 精简分类体系描述：移入独立的工具 description 中，system prompt 只保留一句引用（减少 ~200 token）
  - 精简安全规则：合并重复条目（减少 ~100 token）
  - **总计精简 ~300 token**，LLM 推理上下文更短 → 更快

---

### 二、ChatMemory token 控制（预计节省 2-4s）

#### [MODIFY] [JsonFileChatMemory.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/main/java/com/example/agent/memory/JsonFileChatMemory.java)
- `trimAndPersist()` 增加 **token 估算截断**：在现有 20 条消息上限基础上，增加总 token 估算（按 `text.length() / 2` 粗估中文 token），当累积超过 **4000 token** 时从头部移除最早消息
- 将 `persistToFile` 改为**异步写入**（`CompletableFuture.runAsync`），消除同步磁盘 IO 阻塞

> [!NOTE]
> 按 `text.length() / 2` 估算中文 token 不够精确，但对于截断目的足够，引入 tokenizer 依赖成本不值

---

### 三、MCP 工具调用减负（预计节省 1-3s）

#### [MODIFY] [FinanceTools.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java)
- `list_transactions`：将 `pageSize` 从硬编码 `1000` 改为 `50`，filters 中新增 `limit` 字段支持 LLM 指定数量
- `list_transactions` 返回值增加 `totalCount` 摘要字段（"共 N 条，已展示前 50 条"），让 LLM 知道数据全貌而不需要拉全量
- 优化 `@McpTool description`：加入 `"默认返回最近50条。如需更多可在 filters 中指定 limit（最大200）"` 引导 LLM 合理请求

---

### 四、连接池与超时加固（基础设施）

#### [MODIFY] [RestClientConfig.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/src/main/java/com/example/mcp/config/RestClientConfig.java)
- 引入 Apache HttpClient 5 连接池：`maxConnTotal=20`、`maxConnPerRoute=10`
- 降低 `readTimeout` 从 30s 到 10s（Backend 全内存操作不需要 30s）

#### [MODIFY] [BackendClientConfig.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/main/java/com/example/agent/config/BackendClientConfig.java)
- 同样引入连接池，降低 `readTimeout` 到 10s

#### [MODIFY] [pom.xml (mcp-server)](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/pom.xml)
- 新增 `httpclient5` 依赖

#### [MODIFY] [pom.xml (agent)](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/pom.xml)
- 新增 `httpclient5` 依赖

---

### 五、前端超时与体验优化

#### [MODIFY] [ChatPanel.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/ChatPanel.vue)
- fetch 请求增加 **15s 自动超时**（`AbortSignal.timeout(15000)`），超时后显示"响应较慢，请稍候…"提示而非一直等待
- TTFT（首 token 时间）超过 5s 时显示"正在思考…"动画提示

---

## Verification Plan

### Automated Tests
```bash
cd finance-agent && ./mvnw test
cd finance-mcp-server && ./mvnw test
cd finance-frontend && npx vitest run
```

### Manual Verification
- 启动全栈服务，发送"查一下我的全部资产"，对比优化前后端到端耗时（Agent 日志 `durationMs`）
- 发送"这个月花了多少"验证 summarize_transactions 路径
- 发送"查看最近交易"验证 list_transactions 返回 50 条而非 1000 条
- 验证暗色模式下 "正在思考…" 提示的样式
- 验证 ChatMemory token 截断：快速发 10+ 轮长对话后检查 memory JSON 文件大小

---
生成时间: 2026/5/25 20:28:21
planId: 0f5975b3-7d64-421a-979a-273fcd0ec38e
plan_status: review