# Personal Finance Agent · AI-Powered Bookkeeping

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring_Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D)](https://vuejs.org/)
[![Element Plus](https://img.shields.io/badge/Element_Plus-2.14-blue)](https://element-plus.org/)

A learning project for AI Agent & MCP protocol in the Java ecosystem — personal finance bookkeeping with AI-powered natural language queries.

[中文](README_CN.md) | English

---

## Overview

A learning project to explore **AI Agent** and **MCP (Model Context Protocol)** in the Java ecosystem. Built with Spring Boot + Spring AI + Vue 3 + Element Plus.

**Why this project exists:** As AI tools become mainstream, understanding how to build AI-powered applications in the Java ecosystem is essential. This project demonstrates the full MCP protocol chain — from frontend to LLM to tool execution — without cutting corners.

**Key features:**
- AI Agent with natural language bookkeeping queries
- Full MCP protocol chain (Agent → MCP Client → MCP Server → Backend)
- Streaming AI output (SSE, character-by-character)
- Multi-user support with AI memory system
- ECharts-powered data visualization
- Zero external dependencies (CSV storage)

### Architecture

```
Browser (:5173) → Frontend (Vue 3 + Element Plus)
                      ├── REST API → Backend (:8080) [Spring Boot, CSV]
                      └── REST/SSE → Agent (:8081) [Spring AI + DeepSeek]
                                        │ MCP/SSE
                                        ▼
                                    MCP Server (:8082) [Spring AI MCP]
                                        │ REST
                                        ▼
                                    Backend (:8080)
```

### Design Rationale

**Why 4 separate services?**

As a learning project, preserving the complete MCP protocol chain is the core goal:

1. **Backend (8080)**: Pure REST API, AI/MCP agnostic, can be used independently
2. **MCP Server (8082)**: Wraps Backend APIs as MCP Tools, exposing standard MCP protocol
3. **Agent (8081)**: MCP Client, dynamically discovers Tools, combines with LLM for intelligent chat
4. **Frontend (5173)**: Independent Vue SPA, replaceable with any frontend framework

This architecture demonstrates the value of MCP — **the middle layer decouples AI from business logic**:
- Backend doesn't need to know about AI
- Agent doesn't need to know about Backend implementation
- MCP Server can evolve independently, adding more Tools
- Any MCP Client (Claude Desktop, other Agents) can connect directly

**Why CSV instead of a database?**

Zero external dependencies. Clone and run — no MySQL/PostgreSQL needed. CSV files are human-readable for easy debugging.

### Features

**Bookkeeping (Backend + Frontend)**
- Account management with balance tracking
- Transaction recording with categories and notes
- Filtering by date, category, type
- Server-side pagination
- CSV persistence with automatic migration

**AI Chat (Agent + MCP Server)**
- Natural language balance queries: *"What's my account balance?"*
- Natural language transaction queries: *"Show my dining expenses"*
- Natural language bookkeeping: *"Record lunch ¥50"*
- SSE streaming output with real-time rendering
- AI memory system with per-user conversation history
- Markdown rendering (tables, bold, lists)
- Multi-user support with data isolation

**Data Visualization**
- Daily income/expense trend line chart
- Expense category breakdown pie chart

### Tech Stack

| Module | Technology | Description |
|--------|-----------|-------------|
| Backend | Spring Boot 3.4.5 + Java 17 | REST API, Jackson CsvMapper |
| MCP Server | Spring AI 1.1.0 + MCP Server WebMVC | SSE transport, 4 MCP Tools |
| Agent | Spring AI 1.1.0 + DeepSeek | MCP Client, ChatClient, ChatMemory |
| Frontend | Vue 3 + Vite + Element Plus | Composition API, SSE Streaming, ECharts |
| Storage | CSV files | Zero external dependencies |
| LLM | DeepSeek V3 (deepseek-chat) | OpenAI-compatible API |
| MCP | 2024-11-05 Protocol | SYNC + SSE Transport |

### Project Structure

```
personal-finance-agent/
├── finance-backend/              # Bookkeeping backend (Spring Boot)
│   ├── src/main/java/com/example/finance/
│   │   ├── controller/           # AccountController, TransactionController, CategoryController
│   │   ├── service/              # FinanceService
│   │   ├── repository/           # CsvDataStore (CSV read/write)
│   │   ├── model/                # Account, Transaction, Category, Enums
│   │   ├── dto/                  # PageResult
│   │   └── config/               # CORS configuration
│   └── pom.xml
├── finance-mcp-server/           # MCP Server (Spring AI)
│   ├── src/main/java/com/example/mcp/
│   │   ├── tool/                 # FinanceTools (@McpTool)
│   │   ├── dto/                  # DTOs for REST responses
│   │   └── config/               # RestClient configuration
│   └── pom.xml
├── finance-agent/                # Agent Service (Spring AI + MCP Client)
│   ├── src/main/java/com/example/agent/
│   │   ├── controller/           # ChatController (including /chat/stream)
│   │   ├── config/               # CORS, ChatMemoryConfig
│   │   ├── memory/               # JsonFileChatMemory, ChatHistoryItem
│   │   └── dto/                  # ChatRequest/Response
│   └── pom.xml
├── finance-frontend/             # Vue 3 Frontend
│   ├── src/
│   │   ├── App.vue               # Main layout (Element Plus Container)
│   │   ├── stores/               # userStore.js (multi-user state)
│   │   └── components/           # AppHeader, AccountList, TransactionForm,
│   │                               TransactionList, ChartPanel, ChatPanel, ChatMessage
│   └── package.json
├── .env.example                  # API Key configuration template
├── start-all.sh                  # One-click startup script
├── README.md                     # English
└── README_CN.md                  # Chinese
```

### Quick Start

#### Prerequisites

- **Java 17+** (Recommended: `brew install openjdk@17`)
- **Node.js 18+**
- **DeepSeek API Key** ([Get one free](https://platform.deepseek.com/api_keys))

> **NOT required**: Maven (Maven Wrapper included), database, Docker

#### 1. Clone

```bash
git clone https://github.com/your-username/personal-finance-agent.git
cd personal-finance-agent
```

#### 2. Configure API Key

```bash
cp .env.example .env
# Edit .env with your DeepSeek API key
# DEEPSEEK_API_KEY=sk-your-real-key-here
```

#### 3. Install Frontend Dependencies

```bash
cd finance-frontend && npm install && cd ..
```

#### 4. Start All Services

```bash
export DEEPSEEK_API_KEY=sk-your-key-here
./start-all.sh
```

#### 5. Open Browser

Visit **http://localhost:5173** and start using!

#### Manual Start (4 terminals)

```bash
# Terminal 1: Backend (:8080)
cd finance-backend && ./mvnw spring-boot:run

# Terminal 2: MCP Server (:8082)
cd finance-mcp-server && ./mvnw spring-boot:run

# Terminal 3: Agent (:8081)
export DEEPSEEK_API_KEY=sk-your-key-here
cd finance-agent && ./mvnw spring-boot:run

# Terminal 4: Frontend (:5173)
cd finance-frontend && npm run dev
```

### API Reference

#### Backend (:8080)

| Method | Path | Description | Parameters |
|--------|------|-------------|------------|
| GET | `/api/accounts` | List accounts | `userId` |
| POST | `/api/accounts` | Create account | `{"name":"...","type":"CASH\|BANK\|CARD","userId":"..."}` |
| GET | `/api/accounts/{id}/balance` | Get balance | - |
| GET | `/api/transactions` | List transactions (paginated) | `userId, page, pageSize, date, category, type, accountId` |
| POST | `/api/transactions` | Add transaction | `{"userId":"...","accountId":1,"type":"EXPENSE","amount":50,"category":"Food","note":"Lunch","date":"2026-05-23"}` |
| GET | `/api/categories` | List categories | - |

#### Agent (:8081)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | AI Chat `{"message": "What's my balance?", "userId": "zhangsan"}` → `{"reply": "..."}` |
| POST | `/api/chat/stream` | AI Streaming Chat (SSE, character-by-character) |

#### MCP Server Tools (:8082)

| Tool | Description | Parameters |
|------|-------------|------------|
| `query_balance` | Query account balance | `userId, accountId` |
| `list_transactions` | List transaction records | `userId, date?, category?, type?, accountId?` |
| `add_transaction` | Add a transaction | `userId, accountId, type, amount, category, note?` |
| `list_accounts` | List all accounts | `userId` |

### AI Chat Examples

```
You: What's my account balance?
AI: Your default cash account balance is ¥20,273.96.

You: Add an expense: lunch 50 yuan
AI: Recorded: EXPENSE ¥50.00, category: 餐饮, note: 午餐.

You: Show me all my accounts
AI: You have 1 account: 默认现金账户 (CASH), balance ¥20,273.96.
```

### MCP Integration

The MCP Server exposes standard MCP protocol. Any MCP client can connect.

**Claude Desktop Configuration:**

```json
{
  "mcpServers": {
    "finance": {
      "url": "http://localhost:8082/sse"
    }
  }
}
```

### FAQ

**Q: "Port XXXX was already in use"**

```bash
lsof -ti:8080 | xargs kill -9  # Backend
lsof -ti:8081 | xargs kill -9  # Agent
lsof -ti:8082 | xargs kill -9  # MCP Server
lsof -ti:5173 | xargs kill -9  # Frontend
```

**Q: How to reset data?**

```bash
rm -rf finance-backend/data
```

**Q: Can I use a different LLM?**

Yes. Modify `finance-agent/src/main/resources/application.yml` with any OpenAI-compatible API (OpenAI, DeepSeek, Qwen, etc.).

### License

MIT © 2026

---

<p align="center">
  <sub>Built with Spring AI + DeepSeek + Vue 3 + Element Plus · MCP Protocol 2024-11-05</sub>
</p>
