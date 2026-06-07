---
name: regression-test
description: 运行 AI Agent 回归测试——端到端场景耗时采集 + 审计日志质量校验。触发词："回归测试"、"跑回归"、"上线前检查"、"/regression-test"
---

# 回归测试 Sub-Agent

## 触发场景

当用户说"跑回归测试"、"上线前检查"、"回归测试"、"/regression-test"时，**必须派发一个 sub-agent 执行**，不要在当前会话直接跑。

## 为什么用 Sub-Agent

- 回归测试耗时 ~80s（5 场景 × 3 次 LLM 调用），在当前会话跑会阻塞交互
- Sub-agent 隔离上下文，测试日志不会污染当前会话
- 结构化 JSON 报告便于跨会话对比

## 执行流程

### Step 1: 确认前置条件

sub-agent 启动后先检查：

```bash
# 检查 3 个服务是否都在运行
lsof -ti:8080 && echo "Backend OK" || echo "Backend DOWN"
lsof -ti:8081 && echo "Agent OK" || echo "Agent DOWN"
lsof -ti:8082 && echo "MCP OK" || echo "MCP DOWN"
```

如果有服务未运行 → 报告用户 "请先启动服务: `./start-all.sh`"

### Step 2: 运行测试脚本

```bash
cd /Users/xuhu/workspace/test-learn-agent
python3 scripts/regression-test.py --runs 3
```

脚本会自动：
- 创建测试账户和种子数据（如不存在）
- 清理对话记忆（确保每次测试独立）
- 清空审计日志后运行（只保留本次数据）
- 输出 JSON 报告到 `scripts/regression-reports/<timestamp>.json`

### Step 3: 解析报告

读取生成的 JSON 报告，提取以下关键信息：

```python
import json, os, glob

report_dir = "scripts/regression-reports"
files = sorted(glob.glob(f"{report_dir}/*.json"), reverse=True)
with open(files[0]) as f:
    report = json.load(f)
```

### Step 4: 输出报告

按以下格式输出给用户：

```
## 回归测试报告

**时间**: {timestamp}
**通过率**: {passed}/{total} ({percent}%)

| 场景 | 次数 | 平均 | P95 | 范围 | 状态 |
|------|------|------|-----|------|------|
| ... | ... | ... | ... | ... | ... |

**审计日志**: {issues_count} 个问题（或"正常"）

**结论**: ✓ 全部通过 / ✗ 存在失败
```

## 判定标准

- **通过**: 所有场景无失败 + 审计日志零问题
- **警告**: 有场景耗时 > 历史基线 2x（需人工判断是否退化）
- **失败**: 任一场景有 HTTP 错误 / 超时 / 审计日志有空记录或 unknown traceId

## 失败处理

如果回归测试失败，sub-agent 必须：

1. **报告失败场景名称和错误原因**（从 JSON 报告的 error 字段提取）
2. **报告审计日志问题**（空记录 → 可能双重注册复发；unknown → context 传递断裂）
3. **建议排查方向**：
   - 超时 → 检查 LLM API 可用性
   - AI 服务暂时不可用 → 检查 agent 日志 `finance-agent/logs/`
   - 审计日志异常 → 检查 `LlmAuditAdvisor` 和 `MultiAgentConfig` 注册链

## 可选参数

如果需要自定义测试参数，在派发 sub-agent 时覆盖：

```
--runs 5     每个场景跑 5 次
--quick      快速模式（每个场景 1 次）
--skip-preflight  跳过前置检查
```

## 反模式

- ❌ 在当前会话直接跑 `python3 scripts/regression-test.py`（应派发 sub-agent）
- ❌ sub-agent 回归测试失败后不报原因，只说"失败了"
- ❌ 不检查服务运行状态就直接跑
