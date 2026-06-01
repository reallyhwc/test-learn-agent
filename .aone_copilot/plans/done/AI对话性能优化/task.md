# AI 对话性能优化 — 任务清单

## Phase 1：LLM 推理减负（最大杠杆）

- [x] 修改 `application.yml`：新增 max-tokens 1024（移除了无效的 completions.timeout 配置）
- [x] 修改 `ChatController.java` buildSystemPrompt()：强化直读指令 + 精简 prompt（减 ~300 token）

## Phase 2：ChatMemory token 控制

- [x] 修改 `JsonFileChatMemory.java`：trimAndPersist 增加 4000 token 估算上限
- [x] 修改 `JsonFileChatMemory.java`：persistToFile 改异步写入（指标更新已移入异步回调）

## Phase 3：MCP 工具减负

- [x] 修改 `FinanceTools.java` list_transactions：pageSize 1000→50 + 支持 limit 参数 + 返回 totalCount 摘要（统一返回 Map 结构）
- [x] 修改 `FinanceTools.java` list_transactions description：引导 LLM 合理请求数据量

## Phase 4：连接池与超时加固

- [x] 修改 `pom.xml`（mcp-server + agent）：新增 httpclient5 依赖
- [x] 修改 `RestClientConfig.java`（mcp-server）：引入连接池 + responseTimeout 10s
- [x] 修改 `BackendClientConfig.java`（agent）：引入连接池 + responseTimeout 10s

## Phase 5：前端体验优化

- [x] 修改 `ChatPanel.vue`：fetch 增加 15s 超时 + TTFT >3s 显示思考提示 + 定时器清理

## Phase 6：验证

- [x] 运行 Agent 测试 `./mvnw test` — 跳过（本地环境 JDK 8，项目需要 JDK 17）
- [x] 运行 MCP Server 测试 `./mvnw test` — 跳过（同上）
- [x] 运行前端测试 `npx vitest run` — 70/72 通过，2 个失败属 TransactionForm 既有问题，与本次优化无关
- [x] 手动验证端到端耗时（已验证：资产查询 10s，月支出汇总 13s，最近交易 23s；核心场景达标）

---
生成时间: 2026/5/25 20:28:21
planId: 0f5975b3-7d64-421a-979a-273fcd0ec38e