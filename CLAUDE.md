# CLAUDE.md — Personal Finance Agent

A 4-service demo project showcasing AI Agent + MCP (Model Context Protocol) on the Java/Spring ecosystem.

## Architecture

```
Frontend (:5173) → Agent (:8081) → MCP Server (:8082) → Backend (:8080)
     Vue 3           Spring AI          Spring AI MCP       Spring Boot
                      + DeepSeek                             + CSV storage
```

- **finance-backend** (:8080) — REST API for accounts & transactions, CSV file storage
- **finance-mcp-server** (:8082) — MCP Server exposing 4 tools (query_balance, list_transactions, add_transaction, list_accounts)
- **finance-agent** (:8081) — Spring AI ChatClient + DeepSeek LLM, calls MCP tools via SSE
- **finance-frontend** (:5173) — Vue 3 + Element Plus + ECharts

## Prerequisites

- Java 17+ (`JAVA_HOME` set)
- Node.js 18+
- Maven wrapper included (no global Maven needed)

## Quick Start

```bash
# 1. Configure LLM
cp .env.example .env
# Edit .env with your API key, base URL, and model

# 2. Start all services
./start-all.sh

# 3. Open browser
open http://localhost:5173
```

## Common Commands

```bash
# Individual service start
cd finance-backend && ./mvnw spring-boot:run
cd finance-mcp-server && ./mvnw spring-boot:run
cd finance-agent && ./mvnw spring-boot:run
cd finance-frontend && npm install && npm run dev

# Run tests
cd finance-backend && ./mvnw test

# Check ports
lsof -ti:8080  # Backend
lsof -ti:8081  # Agent
lsof -ti:8082  # MCP Server
lsof -ti:5173  # Frontend
```

## Configuration

All LLM config in `.env` (gitignored, copy from `.env.example`):

```properties
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
```

Supported providers: DeepSeek, OpenAI, 通义千问, Groq, Moonshot, SiliconFlow (any OpenAI-compatible API).

## Key Design Decisions

- **No database** — CSV files in `finance-backend/data/` for zero-setup storage
- **No auth** — Simple `userId` query param for multi-tenant demo
- **MCP over SSE** — SYNC client type, MCP protocol version 2024-11-05
- **Streaming** — SSE-based token-by-token output via `StreamingResponseBody`
- **Per-user memory** — `JsonFileChatMemory` persists conversation history per userId

## Code Patterns

- MCP tools use `@McpTool` and `@McpToolParam` annotations
- Chinese category names MUST use `UriComponentsBuilder.build().toUri()` to avoid double-encoding
- Frontend SSE parsing: `line.startsWith('data:')`, then character-by-character rendering with 20ms delay
- Vue 3 reactivity: always access through reactive array index (`messages.value[idx].text`), never raw object references

## Git Rules

**Commit discipline:**
- Commit after every meaningful change — one logical change per commit, no batching
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  - `feat:` — new feature
  - `fix:` — bug fix
  - `refactor:` — code restructure, no behavior change
  - `docs:` — documentation only
  - `style:` — formatting, whitespace
  - `test:` — add or update tests
  - `chore:` — build, config, tools
- Messages in English, lowercase imperative: `feat: add multi-user support`
- Commit immediately after completing a task — don't let changes pile up

**Push discipline:**
- When a remote (`origin`) is configured, track local unpushed commits
- If unpushed commits reach 5, push to origin immediately: `git push origin <current-branch>`
- Push before the 5-commit threshold if work is done for the day

**Gitignore:**
- `.env` is gitignored
- `.env.example` is the committed template
- `finance-backend/data/` and `finance-agent/data/` are gitignored
