# Personal Finance Agent · 个人记账 AI 助手

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring_Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D)](https://vuejs.org/)
[![Element Plus](https://img.shields.io/badge/Element_Plus-2.14-blue)](https://element-plus.org/)

一个基于 Java + Spring AI 的个人记账学习项目，集成 AI 对话查询和 MCP 协议（Model Context Protocol）服务。

[English](README.md) | 中文

---

## 项目简介

这是一个学习型 Demo 项目，目标是在 Java 生态体系下实践 **AI Agent** 和 **MCP 协议**。项目包含 4 个独立服务，通过标准接口协作，形成一条完整的 AI 工具调用链路。

**核心亮点：**
- AI Agent 通过自然语言对话完成记账、查账操作
- 完整保留 MCP 协议链路（Agent → MCP Client → MCP Server → Backend），不做简化
- 前后端分离，可独立部署
- CSV 文件存储，零外部依赖，开箱即用
- 流式 AI 输出（SSE），逐字返回
- 多用户支持，AI 记忆系统
- ECharts 图表展示

### 系统架构

```
┌─────────────────────────────────────────────────────────┐
│                    Browser (:5173)                       │
│        Vue 3 + Element Plus · 记账面板 + AI 对话          │
└──────────┬──────────────────────┬───────────────────────┘
           │ HTTP REST            │ HTTP REST / SSE Stream
           ▼                      ▼
┌──────────────────┐   ┌──────────────────────────────────┐
│  Backend (:8080) │   │       Agent (:8081)               │
│  Spring Boot     │   │  Spring AI + ChatClient           │
│  REST API        │◄──│  DeepSeek (LLM)                   │
│  CSV 存储        │   │  MCP Client + ChatMemory          │
└──────────────────┘   └───────────┬──────────────────────┘
                                   │ MCP Protocol (SSE)
                                   ▼
                      ┌──────────────────────────────────┐
                      │    MCP Server (:8082)             │
                      │  Spring AI MCP Server WebMVC      │
                      │  4 Tools: query_balance,          │
                      │  list_transactions,               │
                      │  add_transaction, list_accounts   │
                      └───────────┬──────────────────────┘
                                  │ HTTP REST
                                  ▼
                      ┌──────────────────┐
                      │  Backend (:8080) │
                      └──────────────────┘
```

**数据流说明：**
- **用户 → 前端**：Vue 3 SPA，直接调用 Backend REST API 和 Agent Chat API
- **前端 → Agent**：发送自然语言消息，Agent 调用 DeepSeek 模型，支持 SSE 流式输出
- **Agent → MCP Server**：DeepSeek 返回 function call，Agent 通过 MCP Client 调用 MCP Tool
- **MCP Server → Backend**：MCP Server 将 Tool 调用转换为 HTTP 请求，访问 Backend API
- **MCP Protocol**：采用 SSE (Server-Sent Events) 传输，协议版本 2024-11-05

### 设计缘由

**为什么要分成 4 个独立服务？**

作为一个学习项目，保持完整的 MCP 协议链路是核心目标：

1. **Backend (8080)**：纯粹的记账 REST API，不感知 AI/MCP，可以独立使用
2. **MCP Server (8082)**：将 Backend API 包装为 MCP Tool，对外暴露标准 MCP 协议
3. **Agent (8081)**：作为 MCP Client，动态发现 MCP Tool，结合 LLM 实现智能对话
4. **Frontend (5173)**：独立的 Vue 前端，可以替换为任何前端框架

这种架构清晰地展示了 MCP 协议的价值——**中间层（MCP Server）解耦了 AI 和业务**，使得：
- Backend 不需要感知 AI 的存在
- Agent 不需要知道 Backend 的具体实现
- MCP Server 可以独立演进，增加更多 Tool
- 任何 MCP Client（Claude Desktop、其他 Agent）都可以直接接入

**为什么选择 Spring AI？**

Spring AI 1.1 提供了完整的 MCP 支持：
- `@McpTool` 注解零代码生成 MCP Tool Schema
- MCP Client 自动发现和调用 Tool
- 与 Spring Boot 生态无缝集成

**为什么用 CSV 而非数据库？**

保持零外部依赖，项目 clone 后即可运行，无需安装 MySQL/PostgreSQL。CSV 文件可直接用文本编辑器查看，方便调试。

### 功能特性

**记账管理（Backend + Frontend）**
- 账户管理：创建账户、查看余额
- 交易录入：收支记录、分类标签、备注
- 分类筛选：按日期、分类、类型过滤
- 分页查询：支持大数据量交易列表
- CSV 存储：Jackson CsvMapper，自动字段转义

**AI 对话（Agent + MCP Server）**
- 自然语言查询余额：`"我的账户余额是多少？"`
- 自然语言查交易：`"最近有什么餐饮支出？"`
- 自然语言记账：`"帮我记一笔午餐50元"`
- 流式 AI 输出：逐字返回，实时渲染
- AI 记忆系统：记住用户偏好和对话历史
- Markdown 渲染：AI 回复支持表格、加粗、列表等格式
- 多用户支持：伪登录切换，数据完全隔离

**数据可视化**
- 日收支曲线：收入/支出趋势图
- 分类饼图：支出分类占比分析

### 技术栈

| 模块 | 技术 | 说明 |
|------|------|------|
| Backend | Spring Boot 3.4.5 + Java 17 | REST API，Jackson CsvMapper |
| MCP Server | Spring AI 1.1.0 + MCP Server WebMVC | SSE 传输，4 个 MCP Tool |
| Agent | Spring AI 1.1.0 + DeepSeek | MCP Client，ChatClient，ChatMemory |
| Frontend | Vue 3 + Vite + Element Plus | Composition API，SSE Streaming，ECharts |
| 存储 | CSV 文件 | 零外部依赖 |
| LLM | DeepSeek V3 (deepseek-chat) | OpenAI 兼容 API |
| MCP | 2024-11-05 Protocol | SYNC + SSE Transport |

### 项目结构

```
personal-finance-agent/
├── finance-backend/              # 记账后端 (Spring Boot)
│   ├── src/main/java/com/example/finance/
│   │   ├── controller/           # AccountController, TransactionController, CategoryController
│   │   ├── service/              # FinanceService
│   │   ├── repository/           # CsvDataStore (CSV 读写)
│   │   ├── model/                # Account, Transaction, Category, Enums
│   │   ├── dto/                  # PageResult
│   │   └── config/               # CORS 配置
│   └── pom.xml
├── finance-mcp-server/           # MCP Server (Spring AI)
│   ├── src/main/java/com/example/mcp/
│   │   ├── tool/                 # FinanceTools (@McpTool 注解)
│   │   ├── dto/                  # DTO for REST responses
│   │   └── config/               # RestClient 配置
│   └── pom.xml
├── finance-agent/                # Agent 服务 (Spring AI + MCP Client)
│   ├── src/main/java/com/example/agent/
│   │   ├── controller/           # ChatController (含 /chat/stream)
│   │   ├── config/               # CORS 配置, ChatMemoryConfig
│   │   ├── memory/               # JsonFileChatMemory, ChatHistoryItem
│   │   └── dto/                  # ChatRequest/Response
│   └── pom.xml
├── finance-frontend/             # Vue 3 前端
│   ├── src/
│   │   ├── App.vue               # 主布局 (Element Plus Container)
│   │   ├── stores/               # userStore.js (多用户状态)
│   │   └── components/           # AppHeader, AccountList, TransactionForm,
│   │                               TransactionList, ChartPanel, ChatPanel, ChatMessage
│   └── package.json
├── .env.example                  # API Key 配置模板
├── start-all.sh                  # 一键启动脚本
├── README.md                     # English
└── README_CN.md                  # 中文
```

### 快速开始

#### 环境要求

- **Java 17+**（推荐 Homebrew: `brew install openjdk@17`）
- **Node.js 18+**
- **DeepSeek API Key**（[免费注册获取](https://platform.deepseek.com/api_keys)）

> **不需要**：Maven（项目自带 Maven Wrapper）、数据库、Docker

#### 1. 克隆项目

```bash
git clone https://github.com/your-username/personal-finance-agent.git
cd personal-finance-agent
```

#### 2. 配置 API Key

```bash
# 复制并编辑配置文件
cp .env.example .env
# 编辑 .env 填入你的 DeepSeek API Key
# DEEPSEEK_API_KEY=sk-your-real-key-here
```

#### 3. 安装前端依赖

```bash
cd finance-frontend && npm install && cd ..
```

#### 4. 一键启动

```bash
# 设置 DeepSeek API Key
export DEEPSEEK_API_KEY=sk-your-key-here

# 启动所有服务
./start-all.sh
```

#### 5. 打开浏览器

访问 **http://localhost:5173**，开始使用！

#### 手动启动（4 个终端）

```bash
# 终端 1: 记账后端 (:8080)
cd finance-backend && ./mvnw spring-boot:run

# 终端 2: MCP Server (:8082)
cd finance-mcp-server && ./mvnw spring-boot:run

# 终端 3: Agent (:8081)
export DEEPSEEK_API_KEY=sk-your-key-here
cd finance-agent && ./mvnw spring-boot:run

# 终端 4: 前端 (:5173)
cd finance-frontend && npm run dev
```

### API 文档

#### 记账后端 (:8080)

| Method | Path | Description | Parameters |
|--------|------|-------------|------------|
| GET | `/api/accounts` | 查询账户 | `userId` |
| POST | `/api/accounts` | 创建账户 | `{"name":"...","type":"CASH\|BANK\|CARD","userId":"..."}` |
| GET | `/api/accounts/{id}/balance` | 查询余额 | - |
| GET | `/api/transactions` | 查询交易（分页） | `userId, page, pageSize, date, category, type, accountId` |
| POST | `/api/transactions` | 添加交易 | `{"userId":"...","accountId":1,"type":"EXPENSE","amount":50,"category":"餐饮","note":"午餐","date":"2026-05-23"}` |
| GET | `/api/categories` | 查询分类 | - |

#### Agent (:8081)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | AI 对话 `{"message": "查询余额", "userId": "zhangsan"}` → `{"reply": "..."}` |
| POST | `/api/chat/stream` | AI 流式对话（SSE），逐字返回 |

#### MCP Server Tools (:8082)

| Tool | Description | Parameters |
|------|-------------|------------|
| `query_balance` | 查询账户余额 | `userId, accountId` |
| `list_transactions` | 查询交易记录 | `userId, date?, category?, type?, accountId?` |
| `add_transaction` | 添加交易 | `userId, accountId, type, amount, category, note?` |
| `list_accounts` | 查询所有账户 | `userId` |

### 使用指南

#### Web 界面

浏览器打开 http://localhost:5173 后：

1. **顶部**：用户切换器，可在张三/李四/王五/默认用户之间切换
2. **左侧面板**：账户卡片、交易表单、交易列表（支持分页）、统计图表
3. **右侧面板**：AI 对话窗口，支持流式输出和 Markdown 渲染

#### AI 对话示例

```
你: 我的账户余额是多少？
AI: 您的默认现金账户当前余额为 ¥20,273.96 元。

你: 这个月餐饮花了多少钱？
AI: 本月餐饮支出共 ¥1,500 元，共 15 笔。

你: 帮我记一笔：午餐50元
AI: 已为您记录：支出 ¥50.00，分类：餐饮，备注：午餐。

你: 有哪些账户？
AI: 您共有 1 个账户：默认现金账户（现金），余额 ¥20,273.96 元。
```

### MCP 接入

MCP Server 对外暴露标准 MCP 协议，任何 MCP 客户端都可以接入：

**Claude Desktop 配置：**

```json
{
  "mcpServers": {
    "finance": {
      "url": "http://localhost:8082/sse"
    }
  }
}
```

添加后，Claude Desktop 即可调用 4 个记账 Tool。

**自定义 MCP Client：**

MCP Server 运行在 `http://localhost:8082/sse`，使用 SSE 传输 + 2024-11-05 协议。任何兼容的 MCP SDK 都可以接入。

### 常见问题

**Q: 启动报 "Web server failed to start. Port XXXX was already in use"**

端口被占用。执行以下命令释放端口：

```bash
lsof -ti:8080 | xargs kill -9  # Backend
lsof -ti:8081 | xargs kill -9  # Agent
lsof -ti:8082 | xargs kill -9  # MCP Server
lsof -ti:5173 | xargs kill -9  # Frontend
```

**Q: Agent 返回错误 "HTTP 404"**

检查 DeepSeek API Key 是否正确配置，且在启动 Agent 前 `export DEEPSEEK_API_KEY`。

**Q: 如何重置数据？**

删除 Backend 的 data 目录后重启即可：

```bash
rm -rf finance-backend/data
```

**Q: 可以用其他 LLM 吗？**

可以。修改 `finance-agent/src/main/resources/application.yml` 中的 OpenAI 兼容配置（`base-url`、`model`、`api-key`），支持任何 OpenAI 兼容 API（如 OpenAI、DeepSeek、通义千问等）。

### License

MIT © 2026

See [LICENSE](LICENSE) for details.

---

<p align="center">
  <sub>Built with Spring AI + DeepSeek + Vue 3 + Element Plus · MCP Protocol 2024-11-05</sub>
</p>
