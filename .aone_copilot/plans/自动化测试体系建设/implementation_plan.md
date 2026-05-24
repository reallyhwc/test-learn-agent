# 自动化测试体系建设

项目已有一定测试基础（后端10个测试类约35+用例，前端6个测试文件45个用例），但存在关键缺口：后端异常路径未覆盖、前端9个组件仅测了1个、CI缺少质量门禁。

> [!IMPORTANT]
> 优先补充**高ROI测试**：异常处理、核心组件、入参校验。不追求100%覆盖率，而是覆盖最容易出问题的路径。

## Proposed Changes

### 1. 后端测试补充

#### [NEW] [GlobalExceptionHandlerTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/test/java/com/example/finance/exception/GlobalExceptionHandlerTest.java)

MockMvc 测试异常处理统一响应格式：
- `IllegalArgumentException` → 400 + ApiResponse.error
- `RuntimeException` → 500 + ApiResponse.error
- 请求参数类型错误 → 400

#### [MODIFY] [TransactionControllerTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/test/java/com/example/finance/controller/TransactionControllerTest.java)

补充测试用例：
- 创建交易时账户不存在 → 400
- startDate/endDate 日期范围过滤
- `/api/transactions/summary` 聚合接口

---

### 2. 前端测试补充

#### [NEW] [ChatPanel.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/components/ChatPanel.test.js)

核心聊天组件测试：
- 发送消息后消息列表更新
- 空消息不发送
- 加载状态切换

#### [NEW] [TransactionForm.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/components/TransactionForm.test.js)

表单组件测试：
- 必填字段校验
- 金额必须为正数
- 提交后触发事件

#### [NEW] [userStore.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/stores/userStore.test.js)

Pinia store 测试：
- 默认用户为 'default'
- setUser 切换用户
- localStorage 持久化

#### [NEW] [api.test.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/test/utils/api.test.js)

API 封装测试：
- GET/POST 请求封装
- 错误处理

---

### 3. MCP Server 测试补充

#### [MODIFY] [FinanceToolsTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/src/test/java/com/example/mcp/tool/FinanceToolsTest.java)

补充测试用例：
- userId 为空/null 时的校验
- filters JSON 解析失败时的降级
- 后端返回500时的错误消息

---

### 4. CI 流水线增强

#### [MODIFY] [ci.yml](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.github/workflows/ci.yml)

- 前端加 ESLint 检查
- 前端加覆盖率报告（vitest --coverage）
- 后端测试报告上传为 artifact
- 前端测试报告上传为 artifact

## Verification Plan

### Automated Tests
- `cd finance-frontend && npx vitest run` — 所有测试通过
- `cd finance-frontend && npm run build` — 构建成功
- 后端测试通过编译验证（环境限制无法运行 mvnw test）

### Manual Verification
- 查看 CI yml 配置语法正确


---
生成时间: 2026/5/24 22:19:45
planId: ca140ba1-b504-4ee3-963a-0174af007998
plan_status: review