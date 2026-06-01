# Plans 目录索引

本目录存放项目的所有功能/治理规划。每个 plan 是一个独立子目录，固定包含：
- `task.md` — 任务背景、目标、验收标准
- `implementation_plan.md` — 详细实施方案

## 目录结构（按状态分区）

```
.aone_copilot/plans/
├── done/        # 已完成的 plan（保留作为参考与历史档案）
├── doing/       # 进行中的 plan（同时最多 2-3 个）
└── backlog/     # 待启动的 plan（按优先级排序）
```

## 状态流转规则

```
新建 plan         → 默认放在 backlog/
开工              → 移到 doing/
完工（功能上线 + 测试通过 + 提交合并）  → 移到 done/
搁置 / 废弃       → 仍放在 backlog/，在 task.md 顶部加 `# [DEPRECATED]` 标记
```

## 命名约定

- 子目录名用中文短语（4-15 字），描述功能/治理主题
- 同一 plan 在不同状态间移动时**保留原目录名**，便于 git history 追溯

## 如何新建 plan

1. 在 `backlog/` 下创建子目录：`mkdir -p ".aone_copilot/plans/backlog/<主题名>"`
2. 创建 `task.md`：写清触发动机、目标、验收标准、不在范围
3. 创建 `implementation_plan.md`：拆解到具体文件和改动
4. commit message：`chore(harness): 新增 plan <主题名>`

## 当前清单

### done/ —— 10 个已完成
- AI Coding Harness 工程脚手架建设 — `.aone_copilot/` 体系建设
- Guardrails三层防护 — Input/Output/ToolCall 三层防护
- 自动化测试体系建设 — 测试基础体系
- 自动化测试体系深度完善 — 测试覆盖深化
- AI对话性能优化 — Agent 性能基础优化
- AI对话性能深度优化 — Agent 性能深度优化
- DTO代码规范优化 — DTO 规范统一
- Python双栈深度优化 — Python 栈对齐
- 二级分类功能 — Category 二级分类
- 项目深度审计报告 — 一次性审计

### doing/ —— 0 个

### backlog/ —— 1 个
- 前端UI重构-柔和圆润风格

## 维护提示

- doing/ 中超过 30 天没有 commit 的 plan，应回写状态：要么移到 done/，要么移回 backlog/
- 一个 plan 完工后**不要删除**，移到 done/ 保留历史
- 月度 review 见 [`.aone_copilot/README.md`](../README.md)
