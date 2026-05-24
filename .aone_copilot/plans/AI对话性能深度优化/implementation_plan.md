# AI 对话性能深度优化

用户问"我理财赚了多少钱"时耗时 30s 且查询失败。根因分析：
- **90%+ 时间消耗在 LLM reasoning**：工具参数未标注可选，LLM 反复纠结 date/accountId 是否必填
- **查询失败**：LLM 误传参数或纠结不传导致 MCP 调用失败
- **功能缺陷**：缺少服务端聚合，LLM 需拿原始列表自行求和，增加 token 和推理时间

> [!IMPORTANT]
> 优化策略：**让 LLM 少思考、让服务端多干活**

## Proposed Changes

### 1. MCP 工具参数优化 — 消除 LLM 犹豫

#### [MODIFY] [FinanceTools.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java)

**核心改动**：
- `list_transactions` 的 `@McpToolParam` description 明确标注"可选，不传则不过滤"
- `@McpTool` description 增加调用示例（few-shot 减少推理步数）
- 新增 `summarize_transactions` 聚合工具 — 服务端完成按分类汇总，LLM 无需自行计算
- `list_transactions` 支持 `startDate` + `endDate` 日期范围查询，替代单一 `date`
- pageSize 从固定 100 改为 1000（汇总场景需要全量数据）

```diff
- @McpTool(name = "list_transactions", description = "查询交易记录列表，可按日期、分类、类型和账户过滤")
+ @McpTool(name = "list_transactions",
+     description = "查询交易记录列表。所有过滤参数均可选，不传则不过滤。"
+         + "示例：查全部理财收入 → category='理财', type='INCOME'（其余不传）")
  public Object listTransactions(
-     @McpToolParam(description = "用户ID") String userId,
-     @McpToolParam(description = "交易日期 (yyyy-MM-dd)") String date,
+     @McpToolParam(description = "用户ID（必填）") String userId,
+     @McpToolParam(description = "起始日期 (yyyy-MM-dd)，可选，不传则不限起始") String startDate,
+     @McpToolParam(description = "结束日期 (yyyy-MM-dd)，可选，不传则不限结束") String endDate,
```

新增聚合工具：
```java
@McpTool(name = "summarize_transactions",
    description = "按分类汇总交易金额统计。返回每个分类的总金额和笔数。"
        + "适用于'赚了多少''花了多少''收支汇总'类问题，无需再调 list_transactions 后自行计算。")
public Object summarizeTransactions(
    @McpToolParam(description = "用户ID（必填）") String userId,
    @McpToolParam(description = "交易类型: INCOME 或 EXPENSE，可选") String type,
    @McpToolParam(description = "起始日期 (yyyy-MM-dd)，可选") String startDate,
    @McpToolParam(description = "结束日期 (yyyy-MM-dd)，可选") String endDate)
```

---

### 2. 后端支持日期范围 + 聚合查询

#### [MODIFY] [TransactionController.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/controller/TransactionController.java)

- `date` 参数改为 `startDate` + `endDate` 范围查询
- 新增 `GET /api/transactions/summary` 聚合接口

#### [MODIFY] [FinanceService.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/service/FinanceService.java)

- 新增 `summarizeTransactions()` 方法：按分类 groupBy 并 sum

#### [MODIFY] [CsvDataStore.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-backend/src/main/java/com/example/finance/repository/CsvDataStore.java)

- `date.equals()` 精确匹配改为 `startDate`/`endDate` 范围过滤

---

### 3. System Prompt 优化 — 减少 LLM 推理步数

#### [MODIFY] [ChatController.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-agent/src/main/java/com/example/agent/controller/ChatController.java)

在 `buildSystemPrompt()` 中增加参数决策指导：

```
**工具参数决策（直接按规则填参，不要反复推理）：**
- 用户未指定日期 → 不传 startDate/endDate（查全部）
- 用户未指定账户 → 不传 accountId（查全部账户）
- 用户说"理财" → category="理财", type="INCOME"
- 用户说"花了/支出" → type="EXPENSE"
- 用户说"赚了/收入" → type="INCOME"
- 涉及"多少钱/总共/汇总" → 优先用 summarize_transactions
- 参数不确定时，宁可不传（少过滤），拿到结果后再总结
```

---

### 4. MCP 工具测试同步更新

#### [MODIFY] [FinanceToolsTest.java](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-mcp-server/src/test/java/com/example/mcp/tool/FinanceToolsTest.java)

- 更新 `list_transactions` 测试用例适配 `startDate`/`endDate` 新参数
- 新增 `summarize_transactions` 测试用例

## Verification Plan

### Automated Tests
- `cd finance-frontend && npm run build && npx vitest run`
- `cd finance-mcp-server && ./mvnw test -Dtest=FinanceToolsTest`（如环境支持）

### Manual Verification
- 启动服务后在 AI 对话中测试：
  - "我理财赚了多少钱" → 应返回汇总结果，耗时 < 15s
  - "这个月花了多少" → 应正确使用日期范围 + summarize_transactions
  - "查下昨天的交易" → 应正确使用 startDate=endDate=昨天日期


---
生成时间: 2026/5/24 21:53:16
planId: ca140ba1-b504-4ee3-963a-0174af007998
plan_status: review