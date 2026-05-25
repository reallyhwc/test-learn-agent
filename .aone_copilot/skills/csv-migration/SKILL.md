---
name: csv-migration
description: 当需要升级 CSV Schema（新增/删除/重命名列）时使用此 skill
---

# CSV Schema 升级

## 检查清单

1. [ ] **定义新 Schema**: 在 CsvDataStore 中新增 SCHEMA 常量，字段顺序与 Model 一致
2. [ ] **保留旧 Schema**: 创建 SCHEMA_OLD 或 SCHEMA_NO_XXX，对应旧 CSV 的列结构
3. [ ] **列检测方法**: 使用 `csvHasColumn(file, "columnName")` 检测 CSV 是否有新列
4. [ ] **loadXxx() 改造**: if 新格式→用新Schema; else if 中间格式→中间Schema+补全; else→旧Schema+补全所有
5. [ ] **补全逻辑**: 编写 `inferXxx()` 方法，根据已有字段推导新字段值（不留空）
6. [ ] **persist 写回**: 补全后立即用新 Schema persist，下次启动就是新格式
7. [ ] **种子数据**: 如果是新表或初始化场景，种子数据必须包含新字段
8. [ ] **测试验证**: 准备旧格式 CSV 文件，验证 loadXxx() 能正确检测+迁移+写回

## 兼容性矩阵

| 场景 | CSV 状态 | 处理方式 |
|------|---------|----------|
| 全新安装 | 无 CSV 文件 | 生成种子数据（含新字段） |
| 最旧格式 | 缺多列 | 用 SCHEMA_OLD 加载，补全所有新字段 |
| 中间格式 | 缺部分列 | 用中间 Schema 加载，补全缺失字段 |
| 最新格式 | 全部列 | 用新 Schema 直接加载 |

## 注意事项

- 旧格式 CSV 的列头不含新列名，通过 `csvHasColumn` 检测而非 try-catch
- 补全逻辑不能留空值，必须有合理的默认值或推导规则
- persist 后原 CSV 被覆盖，无法回退——建议先备份
