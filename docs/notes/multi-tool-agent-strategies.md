# 大工具量场景下 Agent 上下文优化策略

> **分类**：Agent / 架构
> **日期**：2026-05-31
> **来源**：对话讨论
> **标签**：Tool Selection, Prompt Engineering, Multi-Agent, RAG, Context Window

---

## TL;DR

当 MCP 工具数量膨胀（如 500+）时，工具定义本身就会吃掉大量 Token 上下文。业界的核心思路是 **"先检索，再注入"** —— 不让 LLM 看到它不需要的东西。具体策略从轻到重：Embedding 语义检索、领域分组动态加载、多 Agent 分治、元工具渐进发现。

---

## 背景/场景

单个 MCP Server 下挂载的工具可能非常多（如企业级 SaaS 有数百个 API），每个工具的 JSON Schema 定义占用 200-500 tokens，500 个工具就是 10-25 万 tokens，远超大多数模型的上下文窗口，且大幅增加推理成本。

---

## 核心内容

### 策略对比

| 规模 | 策略 | 代表产品 |
|------|------|---------|
| 10-50 个工具 | 全部注入，Prompt Cache 优化 | Claude Code、ChatGPT Plugins |
| 50-200 个工具 | 领域分组 + 动态加载 | Semantic Kernel、LangGraph |
| 200-500 个工具 | 工具 RAG（Embedding 检索） | OpenAI Assistants API 推荐方案 |
| 500+ 个工具 | 多 Agent 分治 + RAG + 渐进披露 | AutoGen、Google ADK、Coze |

### 策略详解

#### 1. 多 Agent 架构

最根本的解法：不要让一个 Agent 面对 500 个工具，拆成 50 个 Agent，每个只负责一个小领域。

- **AutoGen（微软）**：Agent 之间有 "handoff" 机制，Router Agent 不持业务工具，只有 `transfer_to_xxx_agent` 类工具
- **Google ADK**：Router → Sub Agent 模式，子 Agent 只暴露少量领域内工具
- **CrewAI**：每个 Crew 成员分配特定角色和工具子集，编排器统一协调

#### 2. 领域分组 + 动态加载

- **Semantic Kernel**：工具组织为 Plugin，Planner 阶段确定需要的 Plugin 后动态加载
- **Dify**：Workflow 中手动选择工具节点，不自动暴露所有工具
- **LangGraph**：`ToolNode` 支持节点级 filter，不同图节点加载不同工具子集

#### 3. 工具 RAG（Embedding 检索）

每个工具的 name + description + parameter schema 向量化，请求时用 Embedding 检索 Top-K。

- OpenAI 官方推荐的方案
- Anthropic 建议工具超过 100 时优先考虑检索
- 侵入性最小，不需要改变现有 Agent 架构

#### 4. 元工具（Meta-Tool）

把 "搜索工具" 本身暴露为一个工具，LLM 先调用元工具拿到候选列表，再调业务工具。

- **Coze（字节跳动）**：插件市场有上千个插件，通过搜索/推荐按需发现
- **Anthropic 最佳实践**：工具超过 100 时，先让模型调用 `search_tools(query)`

#### 5. 渐进式 Schema 披露

LLM 先看到简版定义（名称 + 一句话描述），选定后再从注册表补全完整 JSON Schema 发起调用。对 LLM 透明，由框架层完成检索和注入。

#### 补充：Prompt Cache

Anthropic 的 prompt caching 可部分缓解（工具定义被缓存），但缓存有 5 分钟 TTL 和最少 1024 token 的限制，500 个工具的缓存命中率需实测。

---

## 关键要点

- 核心思想：**LLM 不应该看到它不需要的东西**，本质是 Information Filtering before Context Injection
- 从简到繁：Embedding 检索（暴力但有效）→ 分组加载 → 多 Agent 分治
- 多 Agent 是终极方案但架构最重，Embedding 检索是最小侵入的起步方案
- MCP 协议支持 `listTools` 的渐进式发现，可以改造为按分类懒加载
- 实际项目中通常是多种策略的组合，没有银弹

---

## 延伸阅读

- [OpenAI - How to handle large numbers of tools](https://platform.openai.com/docs/guides/function-calling)
- [Anthropic - Tool use best practices](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)
- [AutoGen - Multi-Agent Conversation Framework](https://microsoft.github.io/autogen/)
- [Google ADK - Agent Development Kit](https://google.github.io/adk-docs/)
