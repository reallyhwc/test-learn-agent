# AI 对话性能深度优化 - 任务清单

## 后端改造
- [x] 修改 TransactionController：date 参数改为 startDate + endDate 范围查询
- [x] 修改 CsvDataStore：getTransactions 支持日期范围过滤
- [x] 修改 FinanceService：新增 summarizeTransactions 聚合方法
- [x] TransactionController 新增 GET /api/transactions/summary 聚合接口

## MCP 工具改造
- [x] 修改 FinanceTools list_transactions：参数标注可选 + startDate/endDate
- [x] 新增 FinanceTools summarize_transactions 聚合工具
- [x] 更新 FinanceToolsTest 测试用例

## Agent 优化
- [x] 修改 ChatController buildSystemPrompt：增加参数决策指导规则

## 验证
- [x] 前端构建 + 测试通过
- [x] 启动服务，测试"我理财赚了多少钱"性能和结果


---
生成时间: 2026/5/24 21:53:16
planId: ca140ba1-b504-4ee3-963a-0174af007998