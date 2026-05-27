# Personal Finance Agent — 技术演进路线图

本目录包含项目未来演进的技术方向分析文档，按推荐优先级排列。

每份文档面向**有多年 Java 后端经验、正在探索 AI 领域的开发者**，会用你熟悉的类比来解释 AI 特有的概念。

## 文档清单

| 优先级 | 文档 | 主题 | 一句话说明 |
|:---:|------|------|-----------|
| 1 | [01-guardrails.md](01-guardrails.md) | Guardrails 防护栏 | 让 AI 不说谎、不越权、不被骗 |
| 2 | [02-evals.md](02-evals.md) | Evals 评估体系 | 改了 prompt 怎么知道变好还是变坏？ |
| 3 | [03-human-in-the-loop.md](03-human-in-the-loop.md) | Human-in-the-Loop | 大额操作先确认再执行 |
| 4 | [04-prompt-engineering.md](04-prompt-engineering.md) | Prompt 版本管理 | 像管理代码一样管理 prompt |
| 5 | [05-multi-agent.md](05-multi-agent.md) | Multi-Agent 协作 | 多个 AI 分工协作 |

## 怎么用这些文档

1. **按顺序阅读**：每份文档都是独立的，但优先级 1 是后续所有方向的基础
2. **学完就做**：每份文档末尾有"落地建议"，可以直接让 AI 帮你按计划实施
3. **渐进式演进**：不需要一次全做，每完成一个方向都会让项目更接近生产级

## 当前项目能力版图

```
已覆盖 ✅                          待探索 🔲
─────────────                    ─────────────
✅ Agent 基础 (双栈)              🔲 Evals 评估
✅ MCP 协议 (双栈)                🔲 Human-in-the-Loop
✅ SSE 流式输出                   🔲 Prompt 版本管理
✅ 对话记忆                       🔲 Multi-Agent
✅ System Prompt 决策规则          🔲 RAG 检索增强
✅ 熔断器 + 超时                  🔲 结构化输出
✅ Guardrails 三层防护             🔲 可观测性仪表盘
✅ 全栈测试体系 (~303 用例)        🔲 本地模型支持
✅ AI Coding Harness
✅ Java/Python 双栈切换
```
