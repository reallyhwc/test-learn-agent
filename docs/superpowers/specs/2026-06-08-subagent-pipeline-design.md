# Sub-Agent 架构升级：输出标准化 + Pipeline 编排 + 双栈并行

**日期**: 2026-06-08
**状态**: Draft
**范围**: `.claude/agents/`、`.claude/skills/`、CLAUDE.md、README.md

---

## 1. 背景与动机

项目当前有 4 个 Claude Code 子 Agent（code-reviewer、eval-runner、regression-test、restart-services），各自独立运行，互不通信。存在三个可改进点：

1. **无编排能力**：用户想做"上线前全套检查"，需手动依次触发 4 个 Agent，且后者依赖前者结果（服务没起来就不能跑 eval）
2. **输出仅人类可读**：Agent 输出 Markdown 报告，无法被上层编排程序化解析和做 gate 判断
3. **双栈串行**：eval-runner 的 `--dual` 模式串行跑 Java → Python，但两者完全独立，可并行

## 2. 设计目标

- 子 Agent 输出追加机器可读的 gate 信号，向后兼容不影响现有使用
- 新增 pipeline skill，自动编排子 Agent，支持并行调度和 gate 流转
- eval 双栈拆分为独立 Agent，由 pipeline 编排层实现并行

## 3. 约束

- 子 Agent 不能嵌套调度其他子 Agent（Claude Code 限制），并行必须在主对话层实现
- pipeline 是主对话 Skill（非子 Agent），会占用主对话上下文
- Gate 判断采用严格模式：eval 100% 通过、regression 全场景通过，任一失败终止流水线
- 所有改动遵循项目 CLAUDE.md 规范（中文注释/提交信息、Conventional Commits 等）

---

## 4. Phase 1：子 Agent 输出标准化（GATE_SIGNAL）

### 4.1 GATE_SIGNAL 格式

在每个子 Agent 的 Markdown 报告末尾追加 HTML 注释块：

```markdown
（...Agent 原有 Markdown 报告...）

<!-- GATE_SIGNAL
{
  "agent": "eval-runner",
  "status": "pass",
  "metrics": {
    "passRate": 1.0,
    "passed": 12,
    "total": 12
  },
  "blockers": [],
  "timestamp": "2026-06-08T14:30:00Z"
}
-->
```

### 4.2 格式规范

| 字段 | 类型 | 说明 |
|------|------|------|
| `agent` | string | Agent 名称，与 YAML frontmatter 的 `name` 一致 |
| `status` | `"pass"` \| `"fail"` | 二元判断，无中间态 |
| `metrics` | object | Agent 特有的关键指标（见 4.3） |
| `blockers` | string[] | fail 时必须非空，列出具体失败原因 |
| `timestamp` | ISO 8601 | 报告生成时间 |

### 4.3 各 Agent 的 gate 定义

#### restart-services

- **status=pass 条件**：全部服务 UP
- **metrics**: `{"servicesUp": 4, "servicesTotal": 4}`
- **blockers 示例**: `["Agent :8081 启动失败"]`

#### eval-runner

- **status=pass 条件**：passRate == 1.0
- **metrics**: `{"passRate": 1.0, "passed": 12, "total": 12}`
- **blockers 示例**: `["tool_selection#3: 未调用 query_balance"]`

#### regression-test

- **status=pass 条件**：全部场景 passed
- **metrics**: `{"passRate": 1.0, "passed": 5, "total": 5, "avgLatencyS": 3.2}`
- **blockers 示例**: `["单Agent-查余额: timeout"]`

#### code-reviewer

- **status=pass 条件**：无 🔴 严重问题
- **metrics**: `{"critical": 0, "warning": 2, "info": 1}`
- **blockers 示例**: `["Model 字段变更未同步 MCP Server"]`

### 4.4 向后兼容

- GATE_SIGNAL 是 HTML 注释，Markdown 渲染时不可见
- 用户直接触发子 Agent 时，报告显示完全不变，GATE_SIGNAL 只是附加信息
- 不改变任何 Agent 的输入参数或触发方式

### 4.5 改动范围

| 文件 | 操作 |
|------|------|
| `.claude/agents/restart-services.md` | 在"输出报告"section 末尾追加 GATE_SIGNAL 生成指令 |
| `.claude/agents/eval-runner.md` | 在"输出报告"section 末尾追加 GATE_SIGNAL 生成指令 |
| `.claude/agents/regression-test.md` | 在"输出报告"section 末尾追加 GATE_SIGNAL 生成指令 |
| `.claude/agents/code-reviewer.md` | 在"输出审查报告"section 末尾追加 GATE_SIGNAL 生成指令 |

---

## 5. Phase 2：Pre-Merge Pipeline Skill

### 5.1 Skill 元信息

- **位置**: `.claude/skills/pre-merge-pipeline/SKILL.md`
- **触发词**: "上线前检查"、"pre-merge"、"跑流水线"、"pipeline"
- **运行环境**: 主对话上下文（非子 Agent）

### 5.2 流水线拓扑

```
Stage 1: restart-services              (串行，前置条件)
    ↓ gate: status == "pass"
Stage 2: eval-runner ∥ regression-test  (并行，互相独立)
    ↓ gate: 两者都 status == "pass"
Stage 3: code-reviewer                 (串行，最终审查)
    ↓ gate: 无 🔴 严重 → pass（警告不阻断）
    ↓
  输出最终报告
```

### 5.3 编排逻辑

```
1. 前置检查
   - 检查 .env 是否存在（不存在 → 终止）
   - 检查当前分支是否有未提交变更（有 → 警告用户，不终止）

2. Stage 1: restart-services
   - dispatch restart-services Agent
   - 等待完成，从输出文本中提取 GATE_SIGNAL
   - if status == "fail" → 输出失败报告 + blockers，终止流水线

3. Stage 2: eval + regression（并行）
   - 同时 dispatch eval-runner Agent 和 regression-test Agent
     （如带 --dual 参数，同时 dispatch eval-runner-py Agent，Phase 3 实现）
   - 等待全部完成，分别提取 GATE_SIGNAL
   - if 任一 status == "fail" → 输出失败报告（含所有 Agent 结果），终止流水线

4. Stage 3: code-reviewer
   - dispatch code-reviewer Agent
   - 等待完成，提取 GATE_SIGNAL
   - if status == "fail" → 标记 ⚠️ 警告，不终止流水线

5. 输出最终报告（见 5.5）
```

### 5.4 GATE_SIGNAL 解析方式

从子 Agent 返回的文本中正则匹配：

```
/<!-- GATE_SIGNAL\n([\s\S]*?)\n-->/
```

提取内部 JSON 字符串，解析为对象，读取 `status`、`metrics`、`blockers`。

匹配失败（旧版 Agent 未输出 GATE_SIGNAL）→ 视为 `status: "fail"`，blockers 为 `["GATE_SIGNAL 未找到，请更新 Agent 定义"]`。

### 5.5 最终报告模板

```markdown
## Pre-Merge Pipeline 报告

**分支**: {branch_name}
**时间**: {start_time} ~ {end_time}
**总耗时**: {duration}
**结论**: ✅ 可以合并 / ❌ 流水线失败于 Stage {n}

### 阶段概览

| Stage | Agent | 状态 | 耗时 | 关键指标 |
|-------|-------|------|------|---------|
| 1. 服务重启 | restart-services | ✅/❌ | {t} | {servicesUp}/{servicesTotal} 服务 UP |
| 2a. Eval | eval-runner | ✅/❌ | {t} | {passed}/{total} ({passRate}%) |
| 2b. 回归 | regression-test | ✅/❌ | {t} | {passed}/{total}, 平均 {avgLatencyS}s |
| 3. 代码审查 | code-reviewer | ✅/⚠️ | {t} | {critical} 严重 / {warning} 建议 |

### 失败详情（如有）

{汇总所有 status=="fail" 的 Agent 的 blockers，按 Stage 分组列出}

### 代码审查建议（如有）

{从 code-reviewer 报告中摘录 🟡 建议和 ℹ️ 参考项}
```

### 5.6 可选参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--skip-restart` | 服务已在运行时跳过 Stage 1 | 不跳过 |
| `--skip-review` | 跳过 Stage 3 代码审查 | 不跳过 |
| `--dual` | eval 阶段同时跑 Java + Python（Phase 3 实现后生效） | 仅 Java |

### 5.7 改动范围

| 文件 | 操作 |
|------|------|
| `.claude/skills/pre-merge-pipeline/SKILL.md` | **新增** |

---

## 6. Phase 3：双栈并行探索

### 6.1 核心决策：拆分 eval-runner

不在 eval-runner 内部做并行（子 Agent 无法并行），而是拆为两个独立 Agent，由 pipeline 编排层实现并行。

| Agent | 职责 |
|-------|------|
| `eval-runner.md`（改动） | 仅跑 Java eval，移除 Python 相关逻辑 |
| `eval-runner-py.md`（新增） | 仅跑 Python eval |

### 6.2 eval-runner-py Agent 定义

```yaml
---
name: eval-runner-py
description: Python 栈 Eval 执行器。运行 Python Golden Dataset 评估测试，汇总通过率和失败 case。
tools: Bash, Read, Grep
model: inherit
permissionMode: default
maxTurns: 15
color: cyan
---
```

body 结构与 eval-runner 对称：
- 前置检查：Backend(:8080) + MCP Server Python(:8083) 是否 UP
- 执行：`cd finance-agent-py && source .venv/bin/activate && pytest ../evals/py/ -v`
- 读取报告：Python eval 的 JSON 报告
- 输出：同格式 Markdown + GATE_SIGNAL（`"agent": "eval-runner-py"`）

### 6.3 eval-runner.md 改动

移除 `--dual` 相关的 Python eval 执行逻辑，简化为纯 Java eval 执行器。

### 6.4 Pipeline Skill 对应变化

Stage 2 从并行 2 个 Agent 变为并行 3 个（`--dual` 时）：

```
Stage 2（并行）:
  ├── eval-runner        (Java eval)
  ├── eval-runner-py     (Python eval)    ← --dual 时加入
  └── regression-test
```

Gate 判断：所有被 dispatch 的 Agent 都 pass 才算 Stage 2 通过。

### 6.5 最终报告变化（--dual 模式）

Stage 2 区域增加一行：

```markdown
| 2a. Java Eval | eval-runner | ✅ | 3m 20s | 12/12 (100%) |
| 2b. Python Eval | eval-runner-py | ✅ | 1m 50s | 12/12 (100%) |
| 2c. 回归测试 | regression-test | ✅ | 2m 15s | 5/5 通过 |
```

### 6.6 改动范围

| 文件 | 操作 |
|------|------|
| `.claude/agents/eval-runner.md` | 改动：移除 Python 相关逻辑 |
| `.claude/agents/eval-runner-py.md` | **新增** |
| `.claude/skills/pre-merge-pipeline/SKILL.md` | 改动：Stage 2 支持 `--dual` 三路并行 |

---

## 7. 文档同步

### 7.1 CLAUDE.md

更新「项目级子 Agent」表格：
- 新增 `eval-runner-py` 行
- 新增 `pre-merge-pipeline` 说明（注明是 Skill 而非 Agent）
- 更新 `eval-runner` 描述（仅 Java）

更新「AI Coding Harness 入口」section：
- `.claude/agents/` 条目中增加 eval-runner-py
- 新增 `.claude/skills/pre-merge-pipeline/` 条目

### 7.2 README.md

更新以下 section：
- 「目录结构」树形图：增加 eval-runner-py.md 和 skills/pre-merge-pipeline/
- 「Agents 子 Agent 调度」表格：增加 eval-runner-py 行
- 「Agents 子 Agent 调度」mermaid 图：增加 eval-runner-py 节点 + pipeline skill 节点
- 「Skills vs Agents 的区别」表格：增加 pipeline skill 作为示例

---

## 8. 测试策略

本次改动全部是 Claude Code 子 Agent 定义文件（Markdown）和 Skill 定义文件（Markdown），不涉及运行时代码（Java/Python/Vue）。

### 8.1 验证方式

每个 Phase 完成后的验证：

| Phase | 验证方式 |
|-------|---------|
| Phase 1 | 手动触发每个 Agent，检查输出末尾是否包含合法的 GATE_SIGNAL JSON |
| Phase 2 | 对用户说"跑流水线"，验证 Skill 是否按拓扑编排 Agent 并输出最终报告 |
| Phase 3 | 对用户说"跑流水线 --dual"，验证 Java/Python eval 是否并行执行 |

### 8.2 回归验证

- 直接触发子 Agent（不经过 pipeline）时，行为与改动前完全一致
- GATE_SIGNAL 是 HTML 注释，Markdown 渲染时不可见

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 子 Agent 模型配置问题导致 dispatch 失败 | pipeline 卡在某个 Stage | GATE_SIGNAL 解析失败时 fallback 为 fail + 明确错误信息 |
| 双栈并行对 LLM API 产生双倍并发 | eval 被 rate limit | 可通过不带 --dual 退回单栈模式 |
| pipeline Skill 占用主对话上下文 | 长流水线可能接近上下文限制 | Skill 本身逻辑轻量（~50 行），实际工作在子 Agent 中完成 |

---

## 10. 总改动一览

| Phase | 改动文件 | 新增文件 |
|-------|---------|---------|
| Phase 1 | `.claude/agents/{code-reviewer,eval-runner,regression-test,restart-services}.md` (4个) | 无 |
| Phase 2 | 无 | `.claude/skills/pre-merge-pipeline/SKILL.md` |
| Phase 3 | `.claude/agents/eval-runner.md` + `.claude/skills/pre-merge-pipeline/SKILL.md` | `.claude/agents/eval-runner-py.md` |
| 文档 | `CLAUDE.md` + `README.md` | 无 |
