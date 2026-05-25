# 二级分类功能设计文档

## 概述

为记账系统引入二级分类功能，实现分类的树形层级（如"餐饮→外卖"），采用**方案 B：双字段冗余存储**——Category 表增加 `parentId` 建树形结构，Transaction 保留 `category` 字段（一级分类名）并新增 `subCategory` 字段（二级分类名）。历史数据自动推导补全二级分类。

## 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 数据模型 | 双字段冗余（category + subCategory） | 历史数据零迁移，CSV 可读性好，改动量适中 |
| 二级分类是否必填 | 新交易必填 | 强制分类细化，提升统计精度 |
| 历史数据处理 | 自动推导补全 | 根据一级分类映射默认二级分类，不留空 |
| 统计维度 | 两级都支持 | 一级汇总 + 二级明细 |

## 数据模型

### Category 表变更

新增 `parentId` 字段：

```
字段       类型    说明
id         Long    分类唯一标识
name       String  分类名称
type       TransactionType  收入/支出
parentId   Long    父分类ID（null=一级分类）
```

### Transaction 表变更

新增 `subCategory` 字段：

```
字段          类型      说明
...existing fields...
category      String    一级分类名（如"餐饮"）
subCategory   String    二级分类名（如"外卖"），新交易必填
```

### 种子分类数据

**支出分类**：

| 一级分类 | 二级分类 |
|---------|---------|
| 餐饮 | 外卖、食堂、聚餐、日常餐饮 |
| 交通 | 公交、打车、加油、日常出行 |
| 购物 | 日用品、服饰、数码 |
| 房租 | 房租、物业、水电 |
| 娱乐 | 电影、游戏、旅行 |
| 医疗 | 门诊、药品、体检 |
| 其他 | 其他支出 |

**收入分类**：

| 一级分类 | 二级分类 |
|---------|---------|
| 工资 | 基本工资、奖金、补贴 |
| 兼职 | 兼职收入 |
| 理财 | 利息、分红、基金 |

### 历史数据推导映射

启动时检测 `transactions.csv` 是否有 `subCategory` 列，若无则按以下映射自动补全：

| 一级分类 | 默认二级 | 一级分类 | 默认二级 |
|---------|---------|---------|---------|
| 餐饮 | 日常餐饮 | 工资 | 基本工资 |
| 交通 | 日常出行 | 兼职 | 兼职收入 |
| 购物 | 日用品 | 理财 | 利息 |
| 房租 | 房租 | 居住 | 日常居住 |
| 娱乐 | 日常娱乐 | 社交 | 聚会 |
| 医疗 | 门诊 | 其他 | 其他 |

不在映射表中的分类，默认二级为该分类名本身。

## 各层改动设计

### finance-backend

#### Model 层

- `Category.java`：新增 `parentId` 字段（Long）
- `Transaction.java`：新增 `subCategory` 字段（String）

#### Repository 层（CsvDataStore）

- CSV Schema 增加新列（`parentId`、`subCategory`）
- `loadCategories()`：种子数据改为树形（一级 + 二级）
- `loadTransactions()`：检测旧格式自动补全 `subCategory`
- 历史 `categories.csv` 兼容：检测是否有 `parentId` 列

#### Service 层（FinanceService）

- `createTransaction()`：校验 `subCategory` 必填
- `summarizeTransactions()`：新增 `groupBy` 参数，支持 `category`（一级汇总）和 `subCategory`（二级明细）
- `listCategories()`：返回带 `children` 的树形结构

#### Controller 层

- `GET /api/categories`：返回树形分类列表
- `POST /api/transactions`：请求体新增 `subCategory`，XSS 清洗
- `GET /api/transactions`：新增 `subCategory` 过滤参数
- `GET /api/transactions/summary`：新增 `groupBy` 参数

---

### finance-mcp-server

#### FinanceTools

- `add_transaction`：新增 `subCategory` 参数（必填），描述列出可选值树
- `list_transactions`：filters 新增 `subCategory` 字段
- `summarize_transactions`：filters 新增 `groupBy` 字段（`category`/`subCategory`）

---

### finance-agent

#### ChatController

- System Prompt 分类列表改为树形格式：
  ```
  支出分类：餐饮(外卖/食堂/聚餐/日常餐饮)、交通(公交/打车/加油/日常出行)...
  收入分类：工资(基本工资/奖金/补贴)、兼职(兼职收入)、理财(利息/分红/基金)
  ```
- 工具使用提示更新：告知 LLM 记账时必须同时提供 `category` 和 `subCategory`

---

### finance-frontend

#### TransactionForm.vue

- 分类选择器从 `el-select` 改为 `el-cascader` 级联选择器
- 数据源从扁平列表改为树形（`GET /api/categories` 返回带 children）
- 提交时填充 `category`（一级）+ `subCategory`（二级）

#### TransactionList.vue

- 筛选器增加二级分类下拉（联动一级）
- 表格分类列展示 `category/subCategory`
- 查询参数新增 `subCategory`

#### ChartPanel.vue

- 饼图增加维度切换按钮（一级分类 / 二级分类）
- 按所选维度分组聚合数据

---

## 验证计划

### 自动化测试

- `cd finance-backend && ./mvnw test` — 所有后端测试通过
- `cd finance-mcp-server && ./mvnw test` — MCP 测试通过
- `cd finance-agent && ./mvnw compile` — Agent 编译通过

### 手动验证

- 启动项目，检查历史数据是否正确补全二级分类
- 新建交易，验证级联选择器和必填校验
- 查看统计图表的一级/二级维度切换
- 通过 AI 对话测试："外卖花了多少钱"、"餐饮类支出汇总"
