# evals/ —— Agent 输出质量评估

本目录是 Personal Finance Agent 的 **Eval（评估）体系**：对每次 LLM 行为做可量化的断言，避免 System Prompt / 模型 / 工具改动后"凭感觉"判断好坏。

> 详细背景与设计：[`docs/roadmap/02-evals.md`](../docs/roadmap/02-evals.md)

## 目录结构

```
evals/
├── README.md              # 本文（总入口 + Java 栈说明）
├── golden-dataset.json    # Golden Dataset：所有评估用例（两栈共享）
├── py/                    # Python 栈 eval（见 py/README.md）
│   ├── README.md
│   ├── conftest.py        # pytest fixtures
│   ├── test_agent_eval.py # 主测试
│   └── report_writer.py
└── reports/               # 运行后生成的报告（.gitignored，仅保留 .gitkeep）
    ├── eval-java-yyyyMMdd-HHmmss.json
    ├── eval-python-yyyyMMdd-HHmmss.json
    └── index.html          # 由 scripts/eval-report.py 生成
```

## 双栈

| 栈 | 实现 | 触发命令 | 报告文件名 |
|---|---|---|---|
| Java | `finance-agent/src/test/java/com/example/agent/eval/AgentEvalTest.java` | `cd finance-agent && ./mvnw test -Dgroups=evals -DexcludedGroups=` | `eval-java-*.json` |
| Python | `evals/py/` | `cd finance-agent-py && source .venv/bin/activate && pytest ../evals/py/` | `eval-python-*.json` |

两栈共享同一 `golden-dataset.json`，跑同一组 case，便于对比两栈实际行为差异。详见 [`py/README.md`](./py/README.md)。

## HTML 报告（可视化）

聚合 `reports/eval-*.json` 为单页 HTML（卡片 + 通过率趋势折线图）：

```bash
python3 scripts/eval-report.py
open evals/reports/index.html
```

可选参数：
- `--format=markdown` 输出 markdown 到 stdout（CI 用 `>> $GITHUB_STEP_SUMMARY`）
- `--reports-dir=<path>` 指定 reports 目录
- `--output=<path>` 指定 HTML 输出路径

报告本身 `.gitignored`，仅本地查看或 CI artifact。

## 当前覆盖

19 组用例，7 个评估维度：

| 维度 | 用例数 | 说明 |
|------|:-----:|------|
| `tool_selection` | 5 | 5 个 MCP 工具各 1 case，验证 LLM 工具选择是否正确 |
| `rejection` | 2 | 与记账无关的请求 + Prompt Injection 是否被拒绝 |
| `amount_accuracy` | 2 | 金额相关回复是否模糊化（"大约/大概/左右"判失败） |
| `multi_turn` | 2 | 单条输入串联多个意图，验证上下文保持能力 |
| `tool_conflict` | 2 | 多个工具候选时能否选择正确的工具 |
| `correction` | 2 | 用户输入中自我纠错时 Agent 是否使用修正后的值 |
| `intent_routing` | 4 | Supervisor 意图分类准确率 booking/analysis/other |

## 如何运行

### 前置条件
1. `.env` 中 `LLM_API_KEY` 已配置（否则 `LlmCondition` 会自动跳过所有 case）
2. **Backend + MCP Server 已启动**（eval 调真实工具）：
   ```bash
   ./start-all.sh        # 或单独启动 backend + mcp-server
   ```
3. `userId=default` 在 `finance-backend/data/accounts.csv` 有真实数据

### 执行
```bash
cd finance-agent
./mvnw test -Dgroups=evals -DexcludedGroups=
```

> 注意：`mvn test`（不带参数）**默认不跑** eval（surefire 配置了 `excludedGroups=evals`），避免每次测试消耗 LLM token。

### 报告
- 控制台输出：通过/失败汇总 + 失败 case 的 ID 列表
- JSON 报告：`evals/reports/eval-yyyyMMdd-HHmmss.json` —— 含每个 case 的完整 LLM 响应和失败原因

## Golden Dataset Schema

```json
{
  "id": "tool-001",                          // 唯一 ID
  "category": "tool_selection",              // 维度分类
  "input": "我的账户余额是多少？",            // 用户输入
  "context": {
    "userId": "default",                     // 会话 userId
    "accountId": 1                           // 可选
  },
  "expectations": {
    "toolCalled": "list_accounts",           // 期望工具；null = 不应调任何工具
    "toolParamsContain": { "userId": "default" },  // 工具参数 JSON 必含的键值对
    "responseContainsAny": ["余额", "账户"], // 回复必含其中任一
    "responseNotContains": ["大约", "大概"]  // 回复必不含任何
  }
}
```

## 如何加新 case

1. 编辑 `golden-dataset.json`，按上述 schema 追加新 case
2. ID 规则：`<category>-NNN`（如 `tool-006`、`amount-003`）
3. 跑一次确认通过：`./mvnw test -Dgroups=evals -DexcludedGroups=`
4. commit：`feat(eval): 新增 <case-id> case 测试 XX 场景`

## 与 `change-prompt` skill 的关系

每次修改 System Prompt（`ChatController.buildSystemPrompt()` 或 `finance-agent-py/system_prompt.py`）后**必须**跑一次 eval，对比通过率有无下降。详见 [`.aone_copilot/skills/change-prompt/SKILL.md`](../.aone_copilot/skills/change-prompt/SKILL.md)。

## 失败 case 排查

| 失败现象 | 可能原因 | 修复 |
|---------|---------|------|
| `期望工具 X，实际调了 Y` | LLM 工具选择能力问题，或 prompt 描述不够清晰 | 调 prompt / 调整 toolCalled 期望 |
| `期望工具 X，实际未调任何工具` | LLM 误把请求当无关请求拒绝了 | 检查 prompt 中的拒绝条件是否过严 |
| `响应不含期望关键词` | 回复风格变化或表达方式不同 | 扩大 `responseContainsAny` 范围 |
| `响应包含禁止词` | LLM 在金额场景模糊化 | 调整 prompt 强化"不可模糊化" |
| 所有 case 都 skip | `.env` 中 `LLM_API_KEY` 未配，被 `LlmCondition` 跳过 | 配 `.env` |
| `tool call timeout` | Backend 或 MCP Server 未启动 | `./start-all.sh` |

## 不在本次范围（backlog）

- CI 集成（GitHub Actions + PR Comment 准确率对比）
- HTML 报告 / 趋势图
- LLM Judge（用另一 LLM 给输出打分）
