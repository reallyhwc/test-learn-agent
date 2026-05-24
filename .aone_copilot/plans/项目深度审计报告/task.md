# 项目深度审计改进任务清单

## P0 - Critical（必须立即修复）

- [x] 修复 `JsonFileChatMemory` 路径注入漏洞（对 conversationId 白名单校验）
- [x] 修复 Prompt 注入防护（用户输入过滤 + System Prompt 防护指令）
- [x] 修复 MCP 工具横向越权（userId 从认证上下文获取）
- [x] 修复 FeedbackController JSON 注入（改用 ObjectMapper）
- [x] 修复 CsvDataStore 并发安全（加 ReadWriteLock）
- [x] 修复 saveTransaction 余额更新原子性
- [x] 修复创建交易/账户参数校验（@Valid + JSR-303）

## P1 - High（需尽快修复）

- [x] Backend 添加全局异常处理器 @ControllerAdvice
- [x] Agent GlobalExceptionHandler 返回正确 HTTP 状态码
- [x] 修复 TransactionType.valueOf 异常处理
- [x] 配置 MCP Client 连接超时/重试
- [x] 配置所有 RestClient 超时设置
- [x] 同步 chat 接口添加超时控制
- [x] 修复 ChatMemory 内存泄漏（LRU 淘汰）
- [x] 修复 AgentMetrics 高基数标签
- [x] 修复 ChatMemory 并发竞态条件
- [x] CORS 配置外化为环境变量
- [x] 修复 MetricsInterceptor 空指针风险
- [x] 修复 Account 创建参数校验（已在 P0-7 中完成）
- [x] 添加 CI/CD 流水线（GitHub Actions）
- [x] 添加 Docker 支持（Dockerfile + docker-compose）

## P2 - Medium（中期改进）

### 前端
- [x] 移动端响应式适配
- [x] 添加空状态提示
- [x] 统一 API 请求错误处理
- [x] 迁移到 Pinia 状态管理
- [x] 用户选择持久化
- [x] ChatMessage 图表改用 Vue 组件方式
- [x] 聊天消息虚拟滚动
- [x] 添加全局错误边界
- [x] 补充 ARIA 无障碍标签
- [x] SSE 断连重试机制
- [x] fetch 检查 HTTP 状态码

### 后端
- [x] 输入清洗防 XSS
- [x] 统一 API 响应格式 ApiResponse<T>
- [x] 分页参数边界校验
- [x] CSV 加载降级策略
- [x] CSV 增量写入优化

### Agent/MCP
- [x] 修复流式 Token 计量
- [x] System Prompt 账户数据缓存
- [x] 清理死代码（未使用的 Advisor Bean）
- [x] MCP 工具友好错误信息
- [x] add_transaction 参数校验
- [x] listTransactions 分页保护

### 工程化
- [x] 启动脚本健康检查（替换 sleep）
- [x] 启用 Spring Boot Actuator 健康检查
- [x] 集成 OpenTelemetry 链路追踪
- [x] 添加 Swagger/OpenAPI 文档
- [x] 添加 ESLint + Prettier
- [x] 前端测试集成到 run-tests.sh
- [x] 添加熔断/降级机制

## P3 - Low（长期优化）

- [x] POST 接口返回 201
- [x] Account 列表分页
- [x] CsvDataStore 使用 Map 索引
- [x] Model 补充 equals/hashCode/toString
- [x] 日志脱敏
- [x] 添加缓存机制
- [x] 添加代码块语法高亮
- [x] ECharts tree-shaking 优化
- [x] 添加 E2E 测试
- [x] 补充纯单元测试
- [x] 添加依赖漏洞扫描
- [x] 添加部署文档

## 人工验证任务

- [ ] 使用 Postman 验证路径注入已阻止
- [ ] 使用恶意 Prompt 验证注入防护
- [ ] 并发工具验证 CSV 读写安全
- [ ] 移动端浏览器验证响应式布局


---
生成时间: 2026/5/24 19:56:35
planId: ca140ba1-b504-4ee3-963a-0174af007998