---
alwaysApply: true
---

# 07 Git 提交规范

## 强制提交时机

以下时机**必须**执行 `git add -A && git commit`，不可延后、不可遗忘：

1. **每完成一个 task.md 中的顶层任务**后，立即 commit
2. **计划执行结束**（调用 review_tasks 之前），必须先 commit 所有变更
3. **用户明确要求 commit** 时，立即执行
4. **会话即将结束**时，检查是否有未提交的变更，有则 commit

## Commit Message 格式

遵循 Conventional Commits 规范（由 `githooks/commit-msg` hook 强制校验）：

```
<type>(<scope>): <中文描述>

<可选的详细说明>
```

- **type**: feat / fix / refactor / docs / style / test / chore / perf / ci / build / revert
- **scope**: 可选，模块名如 frontend / agent / mcp / backend
- **描述**: 中文，简明扼要

## 禁止行为

- 禁止在 review_tasks 之前不 commit
- 禁止将多个不相关的 Phase 合并为一个 commit（每个顶层任务独立 commit）
- 禁止用 `git commit -m "update"` 等无意义的提交信息

## 自检清单

在每次 commit 前，确认：
1. `git status` 确认变更范围正确
2. 提交信息符合 Conventional Commits 格式
3. 不包含临时文件、调试代码、TODO 注释
