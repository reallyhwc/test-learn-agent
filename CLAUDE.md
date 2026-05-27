# CLAUDE.md — Personal Finance Agent

A 6-module demo project showcasing AI Agent + MCP (Model Context Protocol) with dual Java/Python implementations. Same Backend, interchangeable Agent and MCP Server.

## Language

**所有内容使用中文** — 代码注释、提交信息、文档、与用户的所有交互均使用中文。技术术语（如类名、方法名、注解名）保持英文。

## Architecture

```
Frontend (:5173) → Agent (:8081/:8084) → MCP Server (:8082/:8083) → Backend (:8080)
     Vue 3        Spring AI / LangChain    Spring AI MCP / FastMCP    Spring Boot
                   + DeepSeek                                         + CSV storage
```

通过 `config.yaml` 切换 Java/Python 实现：

```yaml
ai:
  agent: python   # java | python
  mcp: python     # java | python
```

- **finance-backend** (:8080) — REST API for accounts & transactions, CSV file storage
- **finance-mcp-server** (:8082) — Java MCP Server (Spring AI MCP, 5 tools)
- **finance-mcp-server-py** (:8083) — Python MCP Server (FastMCP, 5 tools, 功能对等)
- **finance-agent** (:8081) — Java Agent (Spring AI ChatClient + DeepSeek)
- **finance-agent-py** (:8084) — Python Agent (LangChain ReAct Agent + DeepSeek, 功能对等)
- **finance-frontend** (:5173) — Vue 3 + Element Plus + ECharts

**当前默认配置（config.yaml）：Python Agent + Python MCP Server**

## Prerequisites

- Java 17+ (`JAVA_HOME` set, e.g. `/usr/local/opt/openjdk@17`)
- Python 3.11+（Python 服务需要）
- Node.js 18+
- Maven wrapper included (no global Maven needed)

## Quick Start

```bash
# 1. Configure LLM
cp .env.example .env
# Edit .env with your API key, base URL, and model

# 2. (Optional) Configure AI provider in config.yaml

# 3. Start all services (reads config.yaml, starts accordingly)
./start-all.sh

# 4. Open browser
open http://localhost:5173
```

## Common Commands

```bash
# Individual service start — Java stack
cd finance-backend && ./mvnw spring-boot:run          # :8080
cd finance-mcp-server && ./mvnw spring-boot:run        # :8082
cd finance-agent && ./mvnw spring-boot:run             # :8081

# Individual service start — Python stack
cd finance-mcp-server-py && python3 server.py          # :8083
cd finance-agent-py && python3 main.py                 # :8084

# Frontend
cd finance-frontend && npm install && npm run dev      # :5173

# Run tests
cd finance-backend && ./mvnw test
cd finance-frontend && npx vitest run

# Check ports
lsof -ti:8080  # Backend
lsof -ti:8081  # Agent (Java)
lsof -ti:8082  # MCP Server (Java)
lsof -ti:8083  # MCP Server (Python)
lsof -ti:8084  # Agent (Python)
lsof -ti:5173  # Frontend

# Kill all services
lsof -ti:8080,8081,8082,8083,8084,5173 | xargs kill -9
```

## Configuration

All LLM config in `.env` (gitignored, copy from `.env.example`):

```properties
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
```

AI provider config in `config.yaml`:

```yaml
ai:
  agent: python   # java | python
  mcp: python     # java | python
```

Supported providers: DeepSeek, OpenAI, 通义千问, Groq, Moonshot, SiliconFlow (any OpenAI-compatible API).

## Key Design Decisions

- **No database** — CSV files in `finance-backend/data/` for zero-setup storage
- **No auth** — Simple `userId` query param for multi-tenant demo
- **MCP over SSE** — Both Java and Python MCP servers use SSE transport, MCP protocol version 2024-11-05
- **Streaming** — SSE-based token-by-token output
- **Per-user memory** — JSON file chat memory per userId (max 20 rounds)
- **config.yaml switching** — `start-all.sh` reads config to decide which Agent/MCP to launch; frontend Vite reads config to set proxy port
- **Agent external init** — Python Agent initializes MCP connection in `main.py` before uvicorn starts, NOT in FastAPI lifespan (avoids anyio cancel scope conflict with `sse_client`)

## Code Patterns

### Java Stack
- MCP tools use `@McpTool` and `@McpToolParam` annotations
- Chinese category names MUST use `UriComponentsBuilder.build().toUri()` to avoid double-encoding

### Python Stack
- MCP tools use `@mcp.tool()` decorator (FastMCP)
- `sse_client()` context manager MUST be stored as instance variable — temporary object gets GC'd, triggering GeneratorExit and anyio cancel scope crash
- Agent uses `create_react_agent` (LangGraph) with `load_mcp_tools` (langchain-mcp-adapters)
- System prompt uses `str.replace()` for account summary injection (NOT `.format()` — JSON examples contain `{}`)
- FastAPI SSE streaming uses `sse_starlette.EventSourceResponse`
- `main.py` uses `asyncio.run(main())` → `uvicorn.Server.serve()` pattern (not `uvicorn.run()`)

### Frontend
- SSE parsing: `line.startsWith('data:')`, then character-by-character rendering with 20ms delay
- Vue 3 reactivity: always access through reactive array index (`messages.value[idx].text`), never raw object references
- Vite dev server proxy port: parsed from `config.yaml` at startup (regex match), dynamic agent port
- `aiStore.js` (Pinia) manages AI provider state, displayed as clickable badge in `AppHeader.vue`

## Project Structure

```
.
├── config.yaml                        # AI 提供者配置
├── start-all.sh                       # 一键启动脚本
├── finance-backend/                   # Java · Spring Boot 3.4 · CSV 存储
├── finance-mcp-server/                # Java · Spring AI MCP · 5 个工具
├── finance-mcp-server-py/             # Python · FastMCP · 5 个工具 (功能对等)
│   └── server.py                      # MCP 服务入口 (SSE transport)
├── finance-agent/                     # Java · Spring AI 1.1 · MCP Client
├── finance-agent-py/                  # Python · LangChain · ReAct Agent (功能对等)
│   ├── main.py                        # 入口 — 初始化 Agent 后启动 uvicorn
│   ├── chat_server.py                 # FastAPI — /api/chat, /api/chat/stream, /api/config
│   ├── agent.py                       # FinanceAgent — MCP 连接 + LLM 编排
│   ├── system_prompt.py               # System Prompt + 账户上下文构建
│   ├── memory_manager.py              # JSON 文件对话记忆
│   └── config_loader.py               # .env + config.yaml 加载
├── finance-frontend/                  # Vue 3 · Element Plus · ECharts
├── githooks/                          # commit-msg hook (Conventional Commits)
├── .github/workflows/ci.yml           # GitHub Actions CI
└── .env.example                       # LLM 配置模板
```

## Git Rules

**Enforced by git hooks** in `githooks/` (activated via `git config core.hooksPath githooks`):

- `commit-msg` hook — blocks commits that don't follow [Conventional Commits](https://www.conventionalcommits.org/). Valid types: `feat, fix, refactor, docs, style, test, chore, perf, ci, build, revert`. Format: `type: description` or `type(scope): description`.
- `post-commit` hook — auto-pushes to `origin` when local unpushed commits reach 5.

**You MUST:**
- Commit after every meaningful change — one logical change per commit, no batching
- 提交信息描述部分使用中文: `feat: 添加多用户支持`，`fix: 修复中文 URI 编码问题`
- Make sure hooks are active: `git config core.hooksPath githooks` (one-time setup)

**Gitignore:**
- `.env` is gitignored
- `.env.example` is the committed template
- `finance-backend/data/` and `finance-agent/data/` are gitignored
- Python `__pycache__/`, `.deps_installed` are gitignored
