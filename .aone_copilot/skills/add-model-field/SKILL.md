---
name: add-model-field
description: 当需要给 Model（如 Transaction、Account、Category）新增字段时使用此 skill
---

# 给 Model 新增字段

## 检查清单

按以下顺序逐步执行，每步完成后标记：

1. [ ] **Model 类**: 新增字段（注意 @AllArgsConstructor 会改变构造器签名）
2. [ ] **CsvDataStore Schema**: 新增字段到 SCHEMA，同时创建旧格式 SCHEMA_OLD
3. [ ] **CsvDataStore loadXxx()**: 检测旧格式 → 加载 → 补全新字段 → persist 写回
4. [ ] **CsvDataStore findXxx()**: 如需过滤，在 filter 链中增加新字段条件
5. [ ] **FinanceService**: 更新校验逻辑、汇总逻辑
6. [ ] **Controller**: 新增查询参数、XSS 清洗
7. [ ] **MCP TransactionResponse**: 新增对应字段
8. [ ] **MCP FinanceTools**: 更新工具参数和描述
9. [ ] **Agent ChatController**: 更新 System Prompt 中的字段描述
10. [ ] **前端组件**: 更新表单(Form)、列表(List)、图表(Chart)
11. [ ] **测试**: 修复构造器/签名变更导致的编译错误，运行 `mvnw test`

## 注意事项

- 先用 `file_grep` 搜索所有 `new Transaction(` 或 `new Category(` 构造器调用，确认影响范围
- 旧 CSV 兼容必须测试：删除 data/ 目录重启验证种子数据，保留旧 CSV 验证自动迁移
- 同一个 commit 提交所有模块改动
