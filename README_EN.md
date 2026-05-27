# Personal Finance Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Python 3.11+](https://img.shields.io/badge/Python-3.11+-blue)](https://www.python.org/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring_Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![Spring AI 1.1](https://img.shields.io/badge/Spring_AI-1.1-blue)](https://docs.spring.io/spring-ai/)
[![LangChain](https://img.shields.io/badge/LangChain-0.3+-orange)](https://www.langchain.com/)
[![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D)](https://vuejs.org/)
[![CI](https://img.shields.io/badge/CI-GitHub_Actions-2088FF)](https://github.com/features/actions)

A learning demo exploring **AI Agent** and **MCP (Model Context Protocol)** across both Java and Python ecosystems — a personal finance tracker you can chat with.

[中文](README.md) | English

---

## What Is This?

A full-stack project with 6 service modules, exploring how to build AI-driven applications using **Java Spring AI** and **Python LangChain**. Record daily income and expenses, then query your data through natural language. The AI understands your intent, calls the right API via MCP tools, and returns formatted results — with **SSE streaming** and **conversation memory**.

**What you'll learn from this codebase:**
- How the MCP protocol bridges LLMs and business APIs
- Comparing Spring AI vs LangChain for building AI Agents
- Comparing FastMCP (Python) vs Spring AI MCP (Java) for MCP Server implementations
- How to implement token-by-token SSE streaming from LLM to browser
- Switching between Java/Python services via `config.yaml`
- How to organize clear boundaries in a multi-service project

---

## System Architecture

```mermaid
graph LR
    Browser["🌐 Browser<br/>:5173"]
    Frontend["📱 Frontend<br/>Vue 3 + Element Plus<br/>+ ECharts"]
    Agent["🤖 Agent :8081/:8084<br/>Spring AI / LangChain<br/>MCP Client<br/>ChatMemory"]
    LLM["🧠 LLM<br/>DeepSeek / OpenAI<br/>Compatible API"]
    MCPServer["🔧 MCP Server :8082/:8083<br/>Spring AI MCP / FastMCP<br/>5 Tools"]
    Backend["💾 Backend :8080<br/>Spring Boot 3.4<br/>CSV Storage"]

    Browser --> Frontend
    Frontend -->|"REST API"| Backend
    Frontend -->|"SSE Stream"| Agent
    Agent <-->|"OpenAI API"| LLM
    Agent -->|"MCP over SSE"| MCPServer
    MCPServer -->|"REST"| Backend
```

**Dual tech stack, same Backend:**

| Layer | Java Implementation | Python Implementation | Port (configurable) |
|-------|--------------------|----------------------|:---:|
| **Agent** | `finance-agent` (Spring AI) | `finance-agent-py` (LangChain) | 8081 / 8084 |
| **MCP Server** | `finance-mcp-server` (Spring AI MCP) | `finance-mcp-server-py` (FastMCP) | 8082 / 8083 |
| **Frontend** | `finance-frontend` (Vue 3) | — | 5173 |
| **Backend** | `finance-backend` (Spring Boot) | — | 8080 |

Switch between Java/Python via `config.yaml`:

```yaml
# config.yaml
ai:
  agent: python   # java | python
  mcp: python     # java | python
```

The frontend header displays the current AI provider with a clickable badge.

---

## Project Map

```
.
├── config.yaml                        # AI provider config (Agent/MCP language)
├── start-all.sh                       # One-click start (reads config.yaml)
│
├── finance-backend/                   Spring Boot 3.4 · REST API · CSV Storage
│   ├── controller/                    AccountController, TransactionController
│   ├── service/                       FinanceService (business logic + aggregation)
│   ├── repository/                    CsvDataStore (atomic writes + in-memory index)
│   ├── exception/                     GlobalExceptionHandler (unified error responses)
│   └── util/                          XssUtils, LogMaskUtils
│
├── finance-mcp-server/                Spring AI MCP · @McpTool annotations
│   └── tool/FinanceTools              5 tools, parseFilters helper
│
├── finance-mcp-server-py/             Python FastMCP · feature-complete
│   └── server.py                      5 MCP tools (SSE transport)
│
├── finance-agent/                     Spring AI 1.1 · MCP Client · ChatMemory
│   ├── controller/                    ChatController (/chat/stream SSE)
│   ├── context/                       AccountContextBuilder (30s cache)
│   ├── memory/                        Conversation memory (max 20 turns)
│   └── metrics/                       AgentMetrics (TTFT, token usage)
│
├── finance-agent-py/                  Python LangChain · ReAct Agent · feature-complete
│   ├── agent.py                       FinanceAgent (MCP tools + DeepSeek LLM)
│   ├── chat_server.py                 FastAPI SSE streaming endpoints
│   ├── system_prompt.py               System Prompt + account context injection
│   ├── memory_manager.py              JSON file conversation memory
│   └── config_loader.py               .env + config.yaml loader
│
├── finance-frontend/                  Vue 3 · Element Plus · ECharts
│   ├── components/                    9 components (ChatPanel, ChartRenderer...)
│   ├── stores/                        Pinia (userStore, aiStore)
│   └── utils/                         api.js, streamParser.js, markdown.js
│
├── .github/workflows/ci.yml           GitHub Actions CI
├── .env.example                       LLM config template
└── githooks/                          commit-msg (Conventional Commits)
```

Each module builds independently. No shared code — HTTP / MCP protocol communication only.

---

## Agent Call Architecture

What happens when a user asks *"How much did I earn from investments?"*:

```mermaid
sequenceDiagram
    participant U as 👤 User
    participant F as 📱 Frontend (Vue)
    participant A as 🤖 Agent
    participant L as 🧠 LLM
    participant M as 🔧 MCP Server
    participant B as 💾 Backend

    U->>F: "How much did I earn from investments?"
    F->>A: POST /api/chat/stream (SSE)

    Note over A: Build System Prompt<br/>Inject account context + tool decision rules
    A->>L: system prompt + user message + 5 tool definitions

    Note over L: Autonomous decision: aggregation query<br/>→ call summarize_transactions
    L-->>A: Function Call:<br/>summarize_transactions<br/>filters={"type":"INCOME"}

    A->>M: MCP protocol tool invocation
    M->>B: GET /api/transactions/summary?type=INCOME
    B-->>M: [{category:"Investment",totalAmount:13164.35,count:13}, ...]
    M-->>A: Summary data

    A->>L: Real data from tool
    L-->>A: "You earned ¥13,164.35 from investments..."

    loop SSE token-by-token push
        A-->>F: event:data → token
    end
    F-->>U: Markdown rendering + ECharts charts
```

**Core design: The LLM autonomously decides which tool to call.** The System Prompt embeds decision rules (e.g., "use summarize_transactions for aggregation queries"), and the LLM acts accordingly — the essence of the Agent pattern.

---

## Finance App Design

### Data Model

```mermaid
erDiagram
    USER ||--o{ ACCOUNT : "owns"
    ACCOUNT ||--o{ TRANSACTION : "generates"

    ACCOUNT {
        long id PK
        string name "Account name (Cash/Bank/...)"
        string type "CASH / BANK / ALIPAY / WECHAT"
        decimal balance "Real-time balance (auto-calculated)"
        string userId "Owner"
    }

    TRANSACTION {
        long id PK
        long accountId FK "Linked account"
        string type "INCOME / EXPENSE"
        decimal amount "Amount"
        string category "Primary category (Dining/Transport/...)"
        string subCategory "Secondary category (Takeout/Taxi/...)"
        string note "Note"
        date date "Date"
        string userId "Owner"
    }
```

### Storage Design

**CSV file storage** — zero environment dependencies, clone + set Key and run:

```
finance-backend/data/
├── accounts.csv        # id,name,type,balance,userId
└── transactions.csv    # id,accountId,type,amount,category,subCategory,note,date,userId
```

- Full data loaded into in-memory `ConcurrentHashMap` on startup
- Atomic write via temp file + rename to prevent corruption
- Data isolated by `userId` to simulate multi-tenancy

### MCP Tool Inventory

| Tool | Function | Parameters |
|------|----------|------------|
| **`summarize_transactions`** | Aggregate transaction amounts by category | `userId`, `filters` (JSON) |
| **`list_transactions`** | Query transaction detail list | `userId`, `filters` (JSON) |
| **`add_transaction`** | Add a transaction record | `userId`, `accountId`, `type`, `amount`, `category`, `subCategory`, `note` |
| **`list_accounts`** | List all user accounts | `userId` |
| **`query_balance`** | Query balance by accountId | `userId`, `accountId` |

> Optional parameters are passed via a `filters` JSON string (e.g., `{"type":"INCOME","category":"Investment"}`) to avoid MCP Schema required/optional ambiguity.

### Backend API

| Method | Path | Function |
|--------|------|----------|
| `GET` | `/api/accounts` | List accounts |
| `POST` | `/api/accounts` | Create account |
| `GET` | `/api/accounts/{id}/balance` | Query balance |
| `GET` | `/api/transactions` | Paginated transaction query (date range/category/type filters) |
| `GET` | `/api/transactions/summary` | Aggregate statistics by category |
| `POST` | `/api/transactions` | Create transaction |
| `GET` | `/api/categories` | List categories |

> Integrated with SpringDoc OpenAPI — visit `http://localhost:8080/swagger-ui.html` after startup.

### Frontend Components

```mermaid
graph TB
    subgraph "App.vue — Responsive Layout"
        direction TB
        HEADER["AppHeader · Nav bar + User switcher + AI provider badge"]

        subgraph "Left · Finance Area"
            ACC["AccountList · Account list"]
            FORM["TransactionForm · Add transaction"]
            LIST["TransactionList · Transaction details"]
            CHART["ChartPanel → ChartRenderer · ECharts"]
        end

        subgraph "Right · AI Chat Area"
            CHAT["ChatPanel · SSE stream receiver"]
            MSG["ChatMessage · Markdown rendering"]
        end
    end

    HEADER --> ACC
    ACC --> FORM
    FORM --> LIST
    LIST --> CHART
    CHAT --> MSG
```

- **Mobile** auto-switches to tab mode (📊 Data / 💬 Assistant)
- **ChatMessage** supports Markdown tables, code highlighting, XSS protection
- **ChartRenderer** auto-detects table data and generates ECharts charts
- **AppHeader** shows current AI provider badge (Java/Python), clickable for details

---

## Why 6 Modules?

You could stuff everything into a single project. The split is intentional for learning:

| Service | Responsibility | Knows AI? | Knows Business? |
|---------|---------------|:---:|:---:|
| **Backend** | Pure REST API + CSV storage | ✗ | ✓ |
| **MCP Server** | Wraps REST as MCP tools | ✗ | ✗ (pure proxy) |
| **Agent** | MCP Client + LLM orchestration | ✓ | ✗ |
| **Frontend** | UI, talks to both Backend and Agent | ✗ | ✗ |

Agent and MCP Server each have Java and Python implementations, feature-complete and interchangeable via `config.yaml` — great for comparing both tech stacks.

---

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| **Frontend** | Vue 3 + Element Plus + ECharts + Pinia | 3.5 / 2.14 / 6.1 / 3.0 |
| **Frontend Tooling** | Vite + Vitest + ESLint | 8.0 / 4.1 / 10.4 |
| **Backend** | Spring Boot + SpringDoc OpenAPI | 3.4.5 / 2.8.6 |
| **AI Framework (Java)** | Spring AI + MCP Protocol | 1.1.0 |
| **AI Framework (Python)** | LangChain + langchain-mcp-adapters + LangGraph | 0.3+ |
| **MCP Server (Python)** | FastMCP (mcp) | 1.x |
| **LLM** | DeepSeek / OpenAI / Qwen (any compatible API) | — |
| **CI/CD** | GitHub Actions (Java 17 + Node 18) | — |

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| **CSV instead of DB** | Zero dependencies. Clone + set Key and run. CSV files debuggable with any text editor |
| **`.env` config** | One file for LLM credentials. Java uses custom `PropertySourceLoader`, Python uses `python-dotenv` |
| **`config.yaml` switching** | Select Java/Python implementations via config, frontend reflects current provider in real-time |
| **SSE instead of WebSocket** | Unidirectional push fits LLM streaming. Simpler, works through HTTP proxies |
| **`userId` param isolation** | Dropdown switches users, no real auth. Demonstrates multi-tenant data isolation |
| **filters JSON param** | Merge optional params into JSON string to avoid MCP Schema required/optional ambiguity |
| **System Prompt decision rules** | Built-in tool selection rules (e.g., "use summarize for aggregation") reduce LLM reasoning loops |
| **Agent external init** | Python Agent initializes MCP connection before uvicorn starts, avoiding FastAPI lifespan + anyio cancel scope conflict |

---

## Quick Start

### Requirements

- **Java 17+** (recommended [Adoptium](https://adoptium.net/))
- **Python 3.11+** (for Python services)
- **Node.js 18+**
- **LLM API Key** (DeepSeek / OpenAI / Qwen, etc.)

### One-Click Start

```bash
# 1. Clone
git clone https://github.com/reallyhwc/test-learn-agent.git
cd test-learn-agent

# 2. Configure LLM
cp .env.example .env
# Edit .env → add your API key

# 3. Configure AI provider (optional, defaults to java)
# Edit config.yaml → set ai.agent and ai.mcp to java or python

# 4. One-click start (reads config.yaml, starts services accordingly)
./start-all.sh

# 5. Open http://localhost:5173
```

> **Tip:** If Maven compilation fails, check that `JAVA_HOME` points to JDK 17 (`/usr/local/opt/openjdk@17` or SDKMAN).

### config.yaml Guide

```yaml
ai:
  agent: python   # Agent implementation: java (Spring AI) | python (LangChain)
  mcp: python     # MCP Server implementation: java (Spring AI MCP) | python (FastMCP)
```

Defaults to `python`. Switch as needed:
- **Full Java stack**: agent=java, mcp=java → uses :8081 and :8082
- **Full Python stack**: agent=python, mcp=python → uses :8084 and :8083
- **Mixed stack**: agent=java, mcp=python or agent=python, mcp=java

### Manual Start

```bash
# Java stack
cd finance-backend && ./mvnw spring-boot:run          # Backend :8080
cd finance-mcp-server && ./mvnw spring-boot:run        # MCP Server :8082
cd finance-agent && ./mvnw spring-boot:run             # Agent :8081

# Python stack
cd finance-mcp-server-py && python3 server.py          # MCP Server :8083
cd finance-agent-py && python3 main.py                 # Agent :8084

# Frontend
cd finance-frontend && npm run dev                     # :5173
```

### Environment Variables

| Variable | Required | Description | Default |
|----------|:--------:|-------------|---------|
| `LLM_API_KEY` | ✅ | LLM API Key | — |
| `LLM_BASE_URL` | ✅ | OpenAI-compatible API URL | `https://api.deepseek.com` |
| `LLM_MODEL` | ✅ | Model name | `deepseek-chat` |

Supports: **DeepSeek**, **Qwen**, **OpenAI**, **Groq**, **Moonshot**, **SiliconFlow**, and any OpenAI-compatible API.

---

## AI Conversation Examples

```
You: What's my account balance?
AI: Your default cash account balance is ¥20,273.96.

You: How much did I earn from investments?
AI: You earned ¥13,164.35 from investments across 13 transactions.

You: Record a transaction: lunch ¥50
AI: Recorded: expense ¥50.00, category: dining, note: lunch.
```

All queries go through the MCP tool chain. The AI never fabricates data — the System Prompt requires it to always call tools for real data.

---

## Test Suite

```
Full-stack coverage: Backend ~46 tests + Frontend 109 tests + MCP ~16 tests + Agent Java 14 tests + Python 33 tests
```

| Layer | Framework | Coverage |
|-------|-----------|----------|
| **Backend Controller** | Spring MockMvc | Account/Transaction CRUD, pagination, date range, aggregation |
| **Backend Service** | JUnit 5 | CSV read/write, multi-user isolation, balance calculation |
| **Backend Exceptions** | MockMvc | GlobalExceptionHandler unified responses |
| **MCP Tools (Java)** | MockRestServiceServer | 5 tools normal/error paths, input validation, JSON fallback |
| **Agent (Java)** | JUnit 5 + MockMvc | Circuit breaker state transitions, feedback endpoint, memory management |
| **Frontend Components** | Vitest + Vue Test Utils | ChatPanel, ChatMessage, TransactionForm, AppHeader, TransactionList, AccountList |
| **Frontend Store** | Vitest | Pinia userStore persistence + aiStore Agent/MCP switching |
| **Frontend Utils** | Vitest | API wrapper, SSE stream parsing (incl. CRLF compat), Markdown rendering, chart extraction |
| **Python Agent** | pytest + pytest-asyncio | Config loading, memory management, System Prompt, SSE endpoints, userId sanitization |
| **CI** | GitHub Actions | Automated tests + ESLint + coverage + OWASP security scan |

Run tests:
```bash
# Frontend (109 tests)
cd finance-frontend && npx vitest run

# Backend (including MCP Server)
cd finance-backend && ./mvnw verify
cd finance-mcp-server && ./mvnw verify

# Java Agent
cd finance-agent && ./mvnw test

# Python Agent (33 tests)
cd finance-agent-py && python -m pytest tests/ -v
```

---

## Claude Desktop Integration

MCP Server exposes standard MCP protocol. Depending on your config, use the appropriate port:

```json
{
  "mcpServers": {
    "finance": {
      "url": "http://localhost:8082/sse"
    }
  }
}
```

- Java MCP Server → `http://localhost:8082/sse`
- Python MCP Server → `http://localhost:8083/sse`

Add to `claude_desktop_config.json` and Claude Desktop can directly query your finance data.

---

## FAQ

**Can I use other LLMs?** Yes. Edit `.env` to switch — any OpenAI-compatible API works.

**Port already in use?**
```bash
lsof -ti:8080,8081,8082,8083,8084,5173 | xargs kill -9
```

**How to reset data?** `rm -rf finance-backend/data`

**Where's the Swagger docs?** Start Backend, then visit `http://localhost:8080/swagger-ui.html`

**Health checks?**

| Service | Health Check URL |
|---------|-----------------|
| Backend | `http://localhost:8080/actuator/health` |
| Agent (Java) | `http://localhost:8081/actuator/health` |
| MCP Server (Java) | `http://localhost:8082/actuator/health` |
| MCP Server (Python) | `http://localhost:8083/sse` |
| Agent (Python) | `http://localhost:8084/actuator/health` |

**Python services fail to start?** Check pip dependencies:
```bash
pip3 install -e finance-mcp-server-py/
pip3 install -e finance-agent-py/
```

---

## License

MIT © 2026
