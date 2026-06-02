# Python 栈 Eval

与 Java 版（`finance-agent/src/test/java/com/example/agent/eval/AgentEvalTest.java`）对称的 Python LangChain Agent 评估实现，**共享同一份 [`../golden-dataset.json`](../golden-dataset.json)**。

## 设计要点

- **旁路 `FinanceAgent` 类**：conftest 自建 `ChatOpenAI` + `langchain_mcp_adapters.load_mcp_tools` + `langgraph.create_react_agent`，不走 Guardrails / Memory。与 Java 旁路 `ChatClient` 对称。
- **System Prompt 与 Java 完全一致**（同一字符串），保证两栈断言可对比。
- **报告 schema 与 Java 完全一致**（`runAt / model / totalCases / passCount / failCount / results`），HTML 报告可合并两栈数据。

## 前置条件

1. `.env` 中 `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` 已配置
2. **mcp-server-py** 已启动（默认 `:8083`）：
   ```bash
   ./start-all.sh --dual           # 启动所有，包括 Python MCP
   # 或单独：
   cd finance-mcp-server-py && python3 server.py
   ```
3. **finance-agent-py 的 venv 已激活**（含 langchain / langchain-openai / langchain-mcp-adapters / pytest 等）：
   ```bash
   cd finance-agent-py && source .venv/bin/activate
   ```

## 运行

```bash
# 从项目根：
cd finance-agent-py && source .venv/bin/activate
pytest ../evals/py/ -v

# 或从 evals/py/ 跑（前提：当前 shell 已激活含 langchain 的 venv）
cd evals/py && pytest -v
```

## 端点切换

默认连 Python MCP Server (`:8083`)。可通过环境变量切到 Java MCP（同样工具，对比两栈兼容性）：

```bash
MCP_SSE_URL=http://localhost:8082/sse pytest -v
```

## 输出

- 控制台：`[Eval-py] N cases: ✅ X 通过, ❌ Y 失败`
- JSON 报告：`evals/reports/eval-python-yyyyMMdd-HHmmss.json`

## 与 Java 版差异

| 维度 | Java | Python |
|------|------|--------|
| Agent 框架 | Spring AI ChatClient | LangChain `create_react_agent` |
| Tool calls 拿取 | `ToolCallRecordingAdvisor` (afterChain) | LangGraph `result["messages"]` 中 AIMessage.tool_calls |
| 触发命令 | `mvn test -Dgroups=evals -DexcludedGroups=` | `pytest -v` |
| LLM gate | `LlmCondition` JUnit Extension | `skip_if_no_llm` pytest fixture |
| 报告文件名 | `eval-java-<ts>.json` | `eval-python-<ts>.json` |

两栈跑同一 case 结果可能略有差异（模型温度、agent 框架行为）。差异不是问题，是观察价值 —— 见 [CLAUDE.md Dual-Stack Strategy](../../CLAUDE.md)。

## 失败排查

| 现象 | 原因 / 修复 |
|------|-----------|
| `所有 case 都 SKIPPED` | LLM_API_KEY 未配，或 mcp-server-py 未起 |
| `ImportError: langchain_mcp_adapters` | venv 未激活：`cd finance-agent-py && source .venv/bin/activate` |
| `ConnectionRefusedError` | mcp-server-py 没起：`./start-all.sh --dual` |
| `期望工具 X 实际 Y` | LLM 工具选择能力问题，看 [`../README.md`](../README.md) 失败排查表 |
