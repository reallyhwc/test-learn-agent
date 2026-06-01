# `.aone_copilot/` —— AI Coding Harness

本目录是 **AI 编程工具**（Claude Code / Aone Copilot / Cursor 等）操作本项目时遵循的"宪法 + 工具箱"。

> 项目顶层的 `CLAUDE.md` 是最高优先级的项目宪法（每次会话都会被加载）；
> `.aone_copilot/` 是详细的规则、技能和规划档案。两者配合使用。

---

## 目录结构

```
.aone_copilot/
├── README.md           # 本文（harness 自身的元约定）
├── rules/              # 编码规范（AI 写代码必须遵守）
│   ├── 工程结构.md
│   ├── 05-测试规范.md
│   ├── 07-Git提交规范.md
│   └── spec/           # 三层架构与自定义规范
├── skills/             # AI 操作清单（特定任务的 SOP）
│   ├── add-mcp-tool/
│   ├── add-model-field/
│   ├── change-prompt/
│   └── csv-migration/
└── plans/              # 功能/治理规划档案（按状态分区）
    ├── README.md
    ├── done/
    ├── doing/
    └── backlog/
```

---

## 三类内容的职责边界

| 类别 | 职责 | 何时使用 | 何时更新 |
|------|------|---------|---------|
| **rules/** | 强约束的编码规范，AI 必须遵守 | 每次写/改代码时 | 编码风格变化、新增技术栈时 |
| **skills/** | 特定任务的标准操作清单（SOP） | 用户触发"我要加 X" / "我要改 Y" 时 | 发现新场景或现有 SOP 漏步骤时 |
| **plans/** | 已落地或待落地的功能/治理规划 | 回答"为什么这么做"或"X 进度如何" | 新功能立项 / 阶段性收尾 |

**判断流程**：
- 它是"任何 AI 都该遵守的规则"？→ rules/
- 它是"做某类具体任务的 11 步操作"？→ skills/
- 它是"某次具体改造的来龙去脉"？→ plans/

---

## 月度 Review 清单

`.aone_copilot/` 是项目的一部分，会随代码漂移。建议**每月 review 一次**（例：每月最后一个周五），按以下清单检查：

### 1. plans/ 健康度
- [ ] `doing/` 中是否有超过 30 天没 commit 的 plan？→ 移回 backlog/ 或确认完成移到 done/
- [ ] `done/` 中是否有标题与 `docs/roadmap/README.md` 中"已覆盖 ✅"列表能对齐的？
- [ ] `backlog/` 是否按优先级排序（顶部最该做）？

### 2. skills/ 有效性
- [ ] 每个 skill 至少被一次用户场景或 plan 引用过？没用过的 skill 考虑删除
- [ ] skill 的步骤里是否有指向已不存在的文件路径？（`grep -r` 关键词扫一遍）

### 3. rules/ 与代码一致性
- [ ] rules 中举例的代码片段是否还能在主分支编译通过？
- [ ] 是否存在"代码反过来违反 rule"的情况？（如 rule 说 Controller 不能调 Repository，但有新代码这么做）

### 4. CLAUDE.md 与代码漂移
- [ ] 跑 `bash scripts/claude-check.sh`（项目根目录），退出码非 0 时按提示修正

---

## 变更约定

修改 `.aone_copilot/` 自身遵循以下约定：

### Conventional Commit Type
- 新增 plan / skill / rule：`chore(harness): 新增 <类型> <主题>`
- 修订现有内容：`docs(harness): 更新 <文件路径>`
- 大型治理重构：`refactor(harness): <说明>`

### Review 必备
任何对 `rules/` 的修改建议**先在 plans/backlog/** 立项讨论，避免悄悄改约束影响所有 AI 生成的代码。

### 删除原则
- skills/ 中过时的 SOP：先标 `# [DEPRECATED]` 一个迭代，下次 review 再删
- plans/ 中已完成的：**永不删除**，迁到 done/ 作为档案
- rules/ 中被代码反过来打破的：先评估是改 rule 还是改代码

---

## 常见疑问

**Q: 为什么不直接把所有规则塞进 CLAUDE.md？**
A: CLAUDE.md 在每次会话都加载到 LLM context，越大越浪费 token。把详细规范放 `.aone_copilot/`，让 AI 按需读取。

**Q: skills/ 与 plans/ 有时看起来很像？**
A: skill 是"做某类任务的通用步骤"（可复用），plan 是"某一次具体改造的方案"（一次性）。如果一个 plan 完成后发现里面的步骤可复用 → 抽出来成 skill。

**Q: 如何让 AI 主动用这些 skills？**
A: 在 CLAUDE.md 里点名："新增 MCP 工具时遵循 `.aone_copilot/skills/add-mcp-tool/`"。AI 看到匹配场景会主动加载。
