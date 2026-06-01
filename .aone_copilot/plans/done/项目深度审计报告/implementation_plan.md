# 全栈项目深度审计报告 — Personal Finance Agent

本报告对项目 4 个服务模块进行了全面审计，发现问题按 P0(Critical) → P3(Low) 分级，涵盖安全、Bug、架构、UI/UX、监控、测试、DevOps 七大维度。

---

## User Review Required

> [!CAUTION]
> **P0 级安全漏洞**：发现 4 个严重安全问题（路径注入、Prompt 注入、横向越权、JSON 注入），建议最高优先级修复。

> [!WARNING]
> **P0 级数据一致性问题**：CSV 并发读写无同步保护，可导致数据损坏和资金不一致。

---

## Proposed Changes

### 🔴 P0 - Critical（7 个，必须立即修复）

---

#### 安全漏洞

##### 1. ChatMemory 路径注入 — 任意文件读写
- **模块**: finance-agent
- **文件**: `memory/JsonFileChatMemory.java`
- **问题**: `conversationId` 直接来自用户 `userId`，无校验，攻击者可传 `../../etc/passwd` 进行路径穿越
- **修复**: 对 `conversationId` 做白名单校验（仅允许 `[a-zA-Z0-9_-]`）

##### 2. Prompt 注入零防护
- **模块**: finance-agent
- **文件**: `controller/ChatController.java`
- **问题**: 用户消息直接传入 LLM，可注入 `忽略以上指令` 覆盖 System Prompt
- **修复**: 用户输入长度限制 + System Prompt 加入防护指令 + 敏感指令过滤

##### 3. MCP 工具横向越权
- **模块**: finance-mcp-server
- **文件**: `tool/FinanceTools.java`
- **问题**: `userId` 由 LLM 传入，MCP Server 不做身份校验，Prompt 注入可操控 userId
- **修复**: MCP Server 从 Agent 认证上下文获取 userId，不信任 LLM 参数

##### 4. FeedbackController JSON 注入 + 路径注入
- **模块**: finance-agent
- **文件**: `controller/FeedbackController.java`
- **问题**: 用 `String.format` 拼接 JSON，userId 等字段未转义
- **修复**: 改用 `ObjectMapper` 序列化

---

#### 数据一致性

##### 5. CSV 并发读写无同步保护 — 数据损坏风险
- **模块**: finance-backend
- **文件**: `CsvDataStore.java`
- **问题**: `ArrayList` 无同步，并发读写导致 `ConcurrentModificationException` 或 CSV 文件损坏
- **修复**: 加 `ReadWriteLock` 或替换为 `CopyOnWriteArrayList` + 写锁

##### 6. saveTransaction 余额更新非原子 — 资金不一致
- **模块**: finance-backend
- **文件**: `CsvDataStore.java`
- **问题**: 余额读-改-写非原子，两次 persist 间无事务保证，可导致余额丢失更新或资金凭空消失
- **修复**: 包装为原子操作，确保 accounts + transactions 一起持久化

##### 7. 创建交易缺少关键字段校验
- **模块**: finance-backend
- **文件**: `FinanceService.java`、`TransactionController.java`
- **问题**: `amount` 可为 null/0/负数，`accountId` 可为 null 或不存在，导致 NPE
- **修复**: 添加 `@Valid` + JSR-303 校验或手动校验

---

### 🟠 P1 - High（14 个，需尽快修复）

---

#### 异常处理

##### 8. Backend 无全局异常处理器
- **模块**: finance-backend
- **问题**: 无 `@ControllerAdvice`，异常直接返回 500 + 栈信息泄露
- **修复**: 添加 `@ControllerAdvice` + 统一错误响应格式 `ApiResponse<T>`

##### 9. GlobalExceptionHandler 返回 200 而非错误码
- **模块**: finance-agent
- **文件**: `exception/GlobalExceptionHandler.java`
- **问题**: 所有异常返回 HTTP 200，前端无法区分成功失败
- **修复**: 超时返回 504，限流返回 429，通用错误返回 500

##### 10. TransactionType.valueOf 未捕获异常
- **模块**: finance-backend
- **文件**: `FinanceService.java`
- **问题**: 非法 type 值抛 `IllegalArgumentException`，500 暴露栈信息

---

#### 超时与连接管理

##### 11. MCP Client 无连接超时/重试配置
- **模块**: finance-agent
- **问题**: MCP Server 宕机时 Agent 线程无限阻塞
- **修复**: 配置 `connection-timeout`、`read-timeout`

##### 12. 所有 RestClient 无超时设置
- **模块**: finance-agent (`BackendClientConfig`)、finance-mcp-server (`RestClientConfig`)
- **问题**: 后端不可用时阻塞请求线程
- **修复**: 配置连接超时 5s + 读取超时 30s

##### 13. 同步 chat 接口无超时控制
- **模块**: finance-agent
- **文件**: `ChatController.java`
- **问题**: LLM 卡住时请求线程永久阻塞，Tomcat 线程池耗尽

---

#### 内存与性能

##### 14. ChatMemory 内存泄漏
- **模块**: finance-agent
- **文件**: `JsonFileChatMemory.java`
- **问题**: `ConcurrentHashMap` 永不清理，随用户增长 OOM
- **修复**: 加 LRU 淘汰或定期清理

##### 15. AgentMetrics 高基数标签
- **模块**: finance-agent
- **文件**: `AgentMetrics.java`
- **问题**: `userId` 作为 Micrometer 标签，用户量大时内存爆炸
- **修复**: 移除 userId 标签，改用日志记录

##### 16. ChatMemory 并发竞态条件
- **模块**: finance-agent
- **文件**: `JsonFileChatMemory.java`
- **问题**: `add()` + `trimAndPersist()` 间无原子保护，并发写丢消息

---

#### 安全与配置

##### 17. CORS 硬编码 localhost
- **模块**: finance-backend、finance-agent
- **问题**: 仅允许 `http://localhost:5173`，生产不可用
- **修复**: 通过配置文件/环境变量外化

##### 18. MetricsInterceptor 空指针风险
- **模块**: finance-backend
- **文件**: `MetricsInterceptor.java`
- **问题**: `getAttribute("startTime")` 可能返回 null，拆箱 NPE

##### 19. Account 创建无参数校验
- **模块**: finance-backend
- **文件**: `AccountController.java`
- **问题**: name 可 null/空，type 可 null，balance 可负数

---

#### DevOps

##### 20. 完全无 CI/CD 配置
- **问题**: 无 GitHub Actions / GitLab CI，代码合入无自动化质量门禁
- **修复**: 至少配置 lint → test → build 流水线

##### 21. 无 Docker 支持
- **问题**: 无 Dockerfile / docker-compose.yml，环境不可复现
- **修复**: 多阶段 Dockerfile + docker-compose 编排

---

### 🟡 P2 - Medium（29 个）

---

#### 前端 UI/UX

##### 22. 无移动端响应式适配
- **文件**: `App.vue`
- **问题**: 布局写死 `width="400px"`，移动端不可用

##### 23. 无空状态提示
- **文件**: `TransactionList.vue`、`ChatPanel.vue`
- **问题**: 列表为空时无引导文案

##### 24. API 请求失败无用户友好提示
- **文件**: `AccountList.vue`、`TransactionForm.vue`

##### 25. 未使用 Pinia，手动 reactive 管理状态
- **文件**: `stores/userStore.js`

##### 26. 无状态持久化（用户选择刷新丢失）

##### 27. ChatMessage 使用原生 DOM 操作注入图表
- **文件**: `ChatMessage.vue`
- **问题**: 绕过 Vue 响应式系统，可能内存泄漏

##### 28. 无虚拟滚动
- **文件**: `ChatPanel.vue`
- **问题**: 长对话 100+ 消息 DOM 膨胀

##### 29. 无全局错误边界
- **文件**: `main.js`
- **问题**: 未配置 `app.config.errorHandler`，错误白屏

##### 30. ARIA 无障碍标签缺失

##### 31. 键盘导航支持不足

---

#### 前端 SSE/流式

##### 32. SSE 无断连重试机制
##### 33. fetch 未检查 HTTP 状态码
- **文件**: `ChatPanel.vue`

---

#### 后端

##### 34. 无输入清洗（XSS 风险）
##### 35. API 响应格式不统一
##### 36. 分页参数无边界校验（page=-1 导致负索引）
##### 37. loadFromCsv 启动失败直接崩溃（无降级）
##### 38. 每次写操作全量覆盖 CSV（性能瓶颈）
##### 39. 内存中全量加载数据（无法支撑大数据量）

---

#### Agent/MCP

##### 40. Token 计量不准确（流式模式）
##### 41. Token Usage 写入也有 JSON 注入
##### 42. System Prompt 每次请求都拉后端数据（无缓存）
##### 43. ChatMemoryConfig 中 Advisor Bean 未被使用（死代码）
##### 44. SSE 流中客户端断连后上游 LLM 仍继续
##### 45. listTransactions 硬编码 pageSize=1000（Token 爆炸）
##### 46. 所有 MCP 工具异常直接 throw（LLM 收到 Java 栈信息）
##### 47. add_transaction 无金额/类型校验
##### 48. queryBalance 不校验 accountId 归属
##### 49. listAccounts URI 拼接方式不一致（URL 注入风险）
##### 50. MCP 工具参数缺少 required 标注

---

#### 工程化

##### 51. 启动脚本无服务健康检查（`sleep` 替代探针）
##### 52. 无优雅停机
##### 53. 无健康检查端点（未启用 Actuator）
##### 54. 无指标采集（Prometheus/Micrometer）
##### 55. 无链路追踪（无 traceId 传递）
##### 56. 无 API 接口文档（Swagger/OpenAPI）
##### 57. 前端无 ESLint/Prettier 配置
##### 58. 无前端测试集成到统一测试脚本
##### 59. 无集成测试/E2E 测试
##### 60. 无熔断/降级机制（resilience4j）

---

### 🟢 P3 - Low（20+ 个）

---

##### 61. POST 接口返回 200 而非 201
##### 62. Account 列表缺少分页
##### 63. findAccountById 线性遍历（无 Map 索引）
##### 64. Model 缺少 equals/hashCode/toString
##### 65. Controller 日志记录敏感信息（userId/金额明文 INFO）
##### 66. 无缓存机制（Category 等不变数据）
##### 67. saveAccount 更新逻辑用 for 循环（可用 Map 优化）
##### 68. 测试全部为集成测试，缺少纯单元测试
##### 69. TestDataConfig 异常被静默吞掉
##### 70. TestDataConfig.backup 只备份第一次
##### 71. logback 日志无 traceId/requestId
##### 72. AgentApplication 两套 .env 加载机制可能冲突
##### 73. ChatRequest 无 Bean Validation
##### 74. Tomcat 连接参数未调优
##### 75. MCP Server logback 无 traceId
##### 76. MCP Server 无 MCP SSE 超时/线程池配置
##### 77. McpServerApplication 无 .env 加载
##### 78. 无代码块语法高亮（highlight.js）
##### 79. ECharts 未充分 tree-shaking
##### 80. 无路由懒加载
##### 81. ChartPanel 与 TransactionList 重复请求数据
##### 82. 聊天记录无前端持久化
##### 83. 图表无替代文本（a11y）
##### 84. 后端无 Checkstyle/SpotBugs
##### 85. 无依赖漏洞扫描
##### 86. 无部署文档
##### 87. 前端 API URL 拼接无参数转义
##### 88. Java 版本检测仅覆盖 macOS
##### 89. 前端 Vite proxy 仅限开发（无 Nginx 配置）

---

## Verification Plan

### Automated Tests
- 修复安全漏洞后运行 `./run-tests.sh` 确保无回归
- 新增并发测试验证 CsvDataStore 线程安全
- 新增参数校验测试验证边界条件
- 前端运行 `cd finance-frontend && npx vitest run`

### Manual Verification
- 使用 Postman/curl 验证路径注入已被阻止
- 使用恶意 Prompt 验证 Prompt 注入防护
- 并发工具（JMeter/wrk）验证 CSV 并发安全
- 移动端浏览器验证响应式布局


---
生成时间: 2026/5/24 19:56:35
planId: ca140ba1-b504-4ee3-963a-0174af007998
plan_status: review