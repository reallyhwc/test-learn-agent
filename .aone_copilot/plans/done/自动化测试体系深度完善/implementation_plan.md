# 自动化测试体系深度完善

## 背景

当前项目测试覆盖不均匀：前端 Utils 层 100%，后端 Backend 约 85%，但前端组件仅 33%、Agent Java 约 30%、Python Agent 约 15%。需要系统性地完善测试规范和补全缺失测试。

> [!IMPORTANT]
> 用户明确要求"先完善规则体系，再处理当前遗漏"，严格按此顺序执行。

## Proposed Changes

### Phase 1: 测试规范规则完善

#### [MODIFY] [05-测试规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/05-测试规范.md)

当前规范仅覆盖 Java 后端（Spring Boot Test + MockMvc），需要扩展为全栈测试规范：

1. **新增前端测试规范章节**
   - 框架：Vitest + jsdom + @vue/test-utils
   - 测试文件路径：`test/{components,stores,utils}/*.test.js`
   - 命名：`{被测模块}.test.js`
   - Mock 规范：`vi.fn()` / `vi.mock()` / `vi.stubGlobal()`
   - SSE 流式测试规范（CRLF/LF 兼容）
   - 组件 mount 规范（Element Plus 插件注入）

2. **新增 Python 测试规范章节**
   - 框架：pytest + pytest-asyncio + httpx
   - 测试文件路径：`tests/test_{模块名}.py`
   - 命名：`test_{预期行为}`
   - Mock 规范：`unittest.mock.patch` / `pytest.fixture`
   - 异步测试：`asyncio_mode = "auto"`

3. **新增通用测试覆盖率要求**
   - Controller/API 层：必须覆盖 happy path + 参数校验 + 异常
   - Service/核心逻辑层：必须覆盖 happy path + 边界值 + 异常
   - 工具类/纯函数：必须覆盖 happy path + 边界值 + 空值
   - 新增功能必须同步新增测试（CI 卡点）

---

### Phase 2: 前端测试补全

#### [NEW] [streamParser.test.js 补充 CRLF](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/utils/streamParser.test.js)

现有测试未覆盖 CRLF 格式，需补充：
- `\r\n` 行尾的 SSE 解析
- `data: ` 后有空格的 payload 提取
- 混合 LF/CRLF 的兼容

#### [NEW] [aiStore.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/stores/aiStore.test.js)

AI 状态管理测试：
- 默认 agentType/mcpType
- switchAgent 路由切换 + fetchConfig 同步
- switchMcp 发请求 + mcpSwitching 状态 + 轮询等待
- localStorage 持久化/恢复
- agentApiPrefix 计算属性
- comboLabel 计算属性

#### [NEW] [AppHeader.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/components/AppHeader.test.js)

- Agent/MCP 双 select 渲染
- onAgentChange 调用 aiStore.switchAgent
- onMcpChange 调用 aiStore.switchMcp
- loading 状态

#### [NEW] [TransactionList.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/components/TransactionList.test.js)

- 列表渲染
- 筛选（类型/分类/日期）
- 分页
- 空状态

#### [NEW] [AccountList.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/components/AccountList.test.js)

- 账户列表渲染
- 余额展示
- 新增账户交互

---

### Phase 3: Java Agent 测试补全

#### [NEW] [SimpleCircuitBreakerTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/test/java/com/example/agent/resilience/SimpleCircuitBreakerTest.java)

- CLOSED → OPEN（连续失败达阈值）
- OPEN → HALF_OPEN（超时后）
- HALF_OPEN → CLOSED（成功）
- HALF_OPEN → OPEN（再次失败）
- 并发安全

#### [NEW] [FeedbackControllerTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/test/java/com/example/agent/controller/FeedbackControllerTest.java)

- 提交反馈 201
- 缺少必填字段 400

#### [NEW] [MemoryControllerTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/test/java/com/example/agent/controller/MemoryControllerTest.java)

- 获取记忆数量
- 清除记忆
- 按 userId 隔离

---

### Phase 4: Python Agent 测试补全

#### [NEW] [test_config_loader.py](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent-py/tests/test_config_loader.py)

- 环境变量优先级
- 缺失 api_key 抛 ValueError
- YAML 变量替换
- get_agent_config 端口解析

#### [NEW] [test_memory_manager.py](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent-py/tests/test_memory_manager.py)

- append/get/clear/count
- MAX_MESSAGES 滚动淘汰
- JSON 文件持久化
- 多用户隔离

#### [NEW] [test_system_prompt.py](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent-py/tests/test_system_prompt.py)

- build_system_prompt 格式正确
- _format_account_summary 空列表/多账户/排序
- fetch_account_summary mock HTTP

#### [MODIFY] [test_chat_server.py](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent-py/tests/test_chat_server.py)

补充用例：
- POST /api/chat 正常流程
- POST /api/chat/stream SSE 流式
- POST /api/switch-mcp 参数校验
- _sanitize_user_id 边界值

---

## Verification Plan

### Automated Tests

每个 Phase 完成后立即运行验证：

```bash
# Phase 2: 前端
cd finance-frontend && npx vitest run

# Phase 3: Java Agent
cd finance-agent && ./mvnw test -q

# Phase 4: Python Agent
cd finance-agent-py && source .venv/bin/activate && python -m pytest tests/ -v
```

### Manual Verification

- 用户确认测试规范规则文档内容
- 确认所有测试通过且无遗漏

---
生成时间: 2026/5/27 15:38:06
planId: 585e3811-d0b5-4693-8c2d-01341f775001
plan_status: review