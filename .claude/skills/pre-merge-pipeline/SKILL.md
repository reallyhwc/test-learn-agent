# Pre-Merge Pipeline

上线前全套检查流水线。自动编排子 Agent 按依赖顺序执行，并行化无依赖阶段，gate 判断控制流转。

## 触发词

用户说"上线前检查"、"pre-merge"、"跑流水线"、"pipeline"时使用此 Skill。

## 可选参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--skip-restart` | 服务已在运行时跳过 Stage 1 | 不跳过 |
| `--skip-review` | 跳过 Stage 3 代码审查 | 不跳过 |
| `--dual` | eval 阶段同时跑 Java + Python 双栈 | 仅 Java |

## 流水线拓扑

```
Stage 1: restart-services              (串行，前置条件)
    ↓ gate: status == "pass"
Stage 2: eval-runner ∥ regression-test  (并行，互相独立)
    ↓ gate: 全部 status == "pass"
Stage 3: code-reviewer                 (串行，最终审查)
    ↓ gate: 无严重问题 → pass（警告不阻断）
    ↓
  输出最终报告
```

## 执行流程

### 0. 前置检查

```bash
# 检查 .env 是否存在
test -f .env && echo ".env OK" || echo ".env MISSING"

# 检查未提交变更
git status --porcelain
```

- `.env` 不存在 → 输出 "❌ 缺少 .env 配置文件，请先 `cp .env.example .env` 并填入 API Key"，终止流水线
- 有未提交变更 → 输出 "⚠️ 当前有未提交变更，建议先 commit 再跑流水线"，继续执行（不终止）

记录流水线开始时间和当前分支名。

### 1. Stage 1: 服务重启

**如果带 `--skip-restart` 参数，跳过此阶段，在报告中标记 ⏭️。**

使用 Agent 工具 dispatch `restart-services` 子 Agent：

```
Agent({
  subagent_type: "restart-services",
  prompt: "重启全部服务并验证健康状态。执行完毕后输出报告。"
})
```

等待子 Agent 完成。从返回文本中解析 GATE_SIGNAL：

```
正则匹配: /<!-- GATE_SIGNAL\n([\s\S]*?)\n-->/
```

**Gate 判断：**
- 匹配失败 → `status: "fail"`，blockers: `["GATE_SIGNAL 未找到，请确认 restart-services Agent 已更新"]`
- `status == "pass"` → 继续到 Stage 2
- `status == "fail"` → 输出失败报告（含 blockers），终止流水线

### 2. Stage 2: Eval + 回归测试（并行）

使用 Agent 工具**同时**（在同一条消息中发出多个 Agent 调用）dispatch 子 Agent：

**默认模式（无 --dual）：**
```
Agent({
  subagent_type: "eval-runner",
  prompt: "运行 Java 栈 Agent Eval 测试，输出完整报告。"
})
Agent({
  subagent_type: "regression-test",
  prompt: "运行回归测试（3 轮），输出完整报告。"
})
```

**双栈模式（带 --dual）：**
```
Agent({
  subagent_type: "eval-runner",
  prompt: "运行 Java 栈 Agent Eval 测试，输出完整报告。"
})
Agent({
  subagent_type: "eval-runner-py",
  prompt: "运行 Python 栈 Agent Eval 测试，输出完整报告。"
})
Agent({
  subagent_type: "regression-test",
  prompt: "运行回归测试（3 轮），输出完整报告。"
})
```

等待全部子 Agent 完成。分别解析每个 Agent 返回文本中的 GATE_SIGNAL。

**Gate 判断：**
- 任一 Agent 的 GATE_SIGNAL 匹配失败 → 视为该 Agent fail
- **全部** `status == "pass"` → 继续到 Stage 3
- **任一** `status == "fail"` → 输出失败报告（含所有 Agent 的结果和 blockers），终止流水线

### 3. Stage 3: 代码审查

**如果带 `--skip-review` 参数，跳过此阶段，在报告中标记 ⏭️。**

```
Agent({
  subagent_type: "code-reviewer",
  prompt: "审查当前分支相对于 master 的所有变更，输出审查报告。"
})
```

等待完成，解析 GATE_SIGNAL。

**Gate 判断（不同于其他 Stage）：**
- `status == "fail"`（有严重问题）→ 标记 ⚠️ 警告，**但不终止流水线**
- `status == "pass"` → 正常继续

### 4. 输出最终报告

汇总所有 Stage 的结果，按以下模板输出：

```markdown
## Pre-Merge Pipeline 报告

**分支**: {branch_name}
**时间**: {start_time} ~ {end_time}
**总耗时**: {duration}
**结论**: ✅ 可以合并 / ❌ 流水线失败于 Stage {n}

### 阶段概览

| Stage | Agent | 状态 | 关键指标 |
|-------|-------|------|---------|
| 1. 服务重启 | restart-services | ✅/❌/⏭️ | {servicesUp}/{servicesTotal} 服务 UP |
| 2a. Eval (Java) | eval-runner | ✅/❌ | {passed}/{total} ({passRate}%) |
| 2b. Eval (Python) | eval-runner-py | ✅/❌/⏭️ | {passed}/{total} ({passRate}%) |
| 2c. 回归测试 | regression-test | ✅/❌ | {passed}/{total}, 平均 {avgLatencyS}s |
| 3. 代码审查 | code-reviewer | ✅/⚠️/⏭️ | {critical} 严重 / {warning} 建议 |

（⏭️ = 已跳过）

### 失败详情（如有）

{汇总所有 status=="fail" 的 Agent 的 blockers，按 Stage 分组列出}

### 代码审查建议（如有）

{从 code-reviewer 的 blockers 或 metrics 中摘录建议项}
```

## GATE_SIGNAL 解析规范

所有子 Agent 在 Markdown 报告末尾输出的 GATE_SIGNAL 格式：

```
<!-- GATE_SIGNAL
{
  "agent": "{agent-name}",
  "status": "pass" | "fail",
  "metrics": { ... },
  "blockers": ["...", "..."],
  "timestamp": "ISO 8601"
}
-->
```

解析步骤：
1. 对 Agent 返回的完整文本做正则匹配：`/<!-- GATE_SIGNAL\n([\s\S]*?)\n-->/`
2. 提取第一个捕获组的 JSON 字符串
3. 解析 JSON，读取 `status`、`metrics`、`blockers`
4. 匹配失败 → 视为 `status: "fail"`，blockers: `["GATE_SIGNAL 未找到"]`
