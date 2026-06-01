# 二级分类功能实施计划

为记账系统引入二级分类功能。采用方案 B（双字段冗余存储）：Category 表增加 `parentId` 建树形结构，Transaction 保留 `category` 并新增 `subCategory` 字段。历史数据自动推导补全二级分类。

## Proposed Changes

### finance-backend — Model 层

#### [MODIFY] [Category.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/model/Category.java)
- 新增 `parentId` 字段（Long 类型）
- 表示父分类ID，null 表示一级分类

#### [MODIFY] [Transaction.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/model/Transaction.java)
- 新增 `subCategory` 字段（String 类型）
- 表示二级分类名称，新交易必填

---

### finance-backend — Repository 层

#### [MODIFY] [CsvDataStore.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/repository/CsvDataStore.java)
- CATEGORY_SCHEMA 增加 `parentId` 列，增加旧格式兼容 CATEGORY_SCHEMA_OLD
- TRANSACTION_SCHEMA 增加 `subCategory` 列
- `loadCategories()`：种子数据改为二级树形（一级 + 二级共约 35 条）
- `loadTransactions()`：检测旧格式时自动根据映射表推导 `subCategory`
- `findTransactions()`：新增 `subCategory` 过滤条件
- `findTransactionsPaginated()`：同上

---

### finance-backend — Service 层

#### [MODIFY] [FinanceService.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/service/FinanceService.java)
- `createTransaction()`：校验 `subCategory` 必填
- `summarizeTransactions()`：新增 `groupBy` 参数，支持按 `category`（一级）或 `subCategory`（二级）汇总
- `listCategories()`：返回树形结构（一级分类带 children 列表）

---

### finance-backend — Controller 层

#### [MODIFY] [TransactionController.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/controller/TransactionController.java)
- `GET /api/transactions`：新增 `subCategory` 过滤参数
- `GET /api/transactions/summary`：新增 `groupBy` 参数
- `POST /api/transactions`：对 `subCategory` 字段做 XSS 清洗

#### [MODIFY] [AccountController.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/controller/AccountController.java)
- 暴露 `GET /api/categories` 端点（当前 `listCategories()` 未暴露），返回树形结构

---

### finance-backend — 测试

#### [MODIFY] [TransactionControllerTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/test/java/com/example/finance/controller/TransactionControllerTest.java)
- 补充 `subCategory` 相关的创建、查询、汇总测试用例

---

### finance-mcp-server — MCP 工具

#### [MODIFY] [FinanceTools.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java)
- `add_transaction`：新增 `subCategory` 参数（必填），描述中列出分类树
- `list_transactions`：filters 描述新增 `subCategory` 字段
- `summarize_transactions`：filters 描述新增 `groupBy` 字段（`category`/`subCategory`）
- 工具描述更新分类可选值为树形结构

#### [MODIFY] [TransactionResponse.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/src/main/java/com/example/mcp/dto/TransactionResponse.java)
- 新增 `subCategory` 字段

---

### finance-agent — AI Prompt

#### [MODIFY] [ChatController.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/main/java/com/example/agent/controller/ChatController.java)
- System Prompt 中分类列表改为树形格式
- 工具使用提示更新：记账时必须同时提供 category 和 subCategory

---

### finance-frontend — 记账表单

#### [MODIFY] [TransactionForm.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/TransactionForm.vue)
- 分类选择器从 `el-select` 改为 `el-cascader` 级联选择器
- 数据源适配树形结构（`GET /api/categories` 返回带 children）
- 提交时填充 `category`（一级）+ `subCategory`（二级）

---

### finance-frontend — 交易列表

#### [MODIFY] [TransactionList.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/TransactionList.vue)
- 筛选器增加二级分类联动下拉
- 表格分类列展示 `category/subCategory`
- 查询参数新增 `subCategory`

---

### finance-frontend — 图表

#### [MODIFY] [ChartPanel.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/ChartPanel.vue)
- 饼图增加维度切换按钮（一级分类 / 二级分类）
- 按所选维度分组聚合数据

---

## Verification Plan

### Automated Tests
- `cd finance-backend && ./mvnw test -q` — 后端测试通过（含新增 subCategory 测试）
- `cd finance-mcp-server && ./mvnw test -q` — MCP Server 测试通过
- `cd finance-agent && ./mvnw compile -q` — Agent 编译通过

### Manual Verification
- 启动项目，验证历史数据自动补全 subCategory
- 新建交易，验证级联选择器和必填校验
- 查看统计图表的一级/二级维度切换
- AI 对话测试："外卖花了多少钱"、"餐饮类支出汇总"

---
生成时间: 2026/5/25 17:29:06
planId: 0f5975b3-7d64-421a-979a-273fcd0ec38e
plan_status: review