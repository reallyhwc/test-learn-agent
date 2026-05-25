# 二级分类功能 — 任务清单

## Phase 1：finance-backend Model + Repository 层

- [x] 修改 `Category.java`：新增 `parentId` 字段
- [x] 修改 `Transaction.java`：新增 `subCategory` 字段
- [x] 修改 `CsvDataStore.java`：更新 CSV Schema（CATEGORY_SCHEMA 加 parentId，TRANSACTION_SCHEMA 加 subCategory）
- [x] 修改 `CsvDataStore.java`：重写 `loadCategories()` 种子数据为二级树形（约 35 条）
- [x] 修改 `CsvDataStore.java`：`loadTransactions()` 旧格式兼容 + subCategory 自动推导补全
- [x] 修改 `CsvDataStore.java`：`findTransactions()` 和 `findTransactionsPaginated()` 增加 subCategory 过滤

## Phase 2：finance-backend Service + Controller 层

- [x] 修改 `FinanceService.java`：`createTransaction()` 校验 subCategory 必填
- [x] 修改 `FinanceService.java`：`summarizeTransactions()` 支持 groupBy 参数（category/subCategory）
- [x] 修改 `FinanceService.java`：`listCategories()` 返回树形结构（listCategoriesTree）
- [x] 修改 `TransactionController.java`：GET /api/transactions 增加 subCategory 参数 + XSS 清洗
- [x] 修改 `TransactionController.java`：GET /api/transactions/summary 增加 groupBy 参数
- [x] 修改 `CategoryController.java`：GET /api/categories 返回树形结构
- [x] 运行 finance-backend 测试验证（通过，5个失败为已有问题，非本次改动引起）

## Phase 3：finance-mcp-server 适配

- [x] 修改 `TransactionResponse.java`：新增 subCategory 字段
- [x] 修改 `FinanceTools.java`：add_transaction 新增 subCategory 参数 + 分类树描述
- [x] 修改 `FinanceTools.java`：list_transactions filters 增加 subCategory
- [x] 修改 `FinanceTools.java`：summarize_transactions filters 增加 groupBy
- [x] 运行 finance-mcp-server 编译验证通过

## Phase 4：finance-agent Prompt 适配

- [x] 修改 `ChatController.java`：System Prompt 分类列表改为树形格式
- [x] 编译验证 finance-agent 通过

## Phase 5：finance-frontend 适配

- [x] 修改 `TransactionForm.vue`：分类选择器改为 el-cascader 级联选择器
- [x] 修改 `TransactionList.vue`：筛选器增加二级分类 + 表格展示 category/subCategory
- [x] 修改 `ChartPanel.vue`：饼图增加一级/二级维度切换

## Phase 6：端到端验证

- [ ] 启动项目，验证历史数据自动补全
- [ ] 新建交易验证级联选择器和必填校验
- [ ] AI 对话测试二级分类汇总
- [ ] 提交代码并推送

---
生成时间: 2026/5/25 17:29:06
planId: 0f5975b3-7d64-421a-979a-273fcd0ec38e