# Personal Finance Agent

一个基于 Java + Spring AI 的个人记账助手，集成了 AI 对话查询和 MCP 服务。

## 项目简介

这是一个学习型 demo 项目，在 Java 生态体系下实践 AI Agent 和 MCP 协议。

### 功能

- **记账管理**：账户管理、交易录入、分类筛选
- **AI 对话**：通过自然语言查询余额、交易记录，支持添加交易
- **MCP 服务**：对外暴露标准 MCP Tool，可供 Claude Desktop 等客户端接入

### 架构

```
Vue 前端 (:5173)
    ├── HTTP REST → 记账后端 (:8080)
    └── HTTP REST → Agent 服务 (:8081)
                        │ MCP 协议 (SSE)
                        ▼
                    MCP Server (:8082)
                        │ HTTP REST
                        ▼
                    记账后端 (:8080)
```

### 技术栈

| 模块 | 技术 |
|------|------|
| 记账后端 | Spring Boot 3.4 + Java 17 + CSV 存储 |
| MCP Server | Spring AI 1.1 + MCP Server WebMVC |
| Agent | Spring AI 1.1 + DeepSeek + MCP Client |
| 前端 | Vue 3 + Vite |

## 快速开始

### 环境要求

- Java 17+
- Node.js 18+
- DeepSeek API Key（[获取地址](https://platform.deepseek.com/api_keys)）

### 启动

```bash
# 1. 安装前端依赖
cd finance-frontend && npm install && cd ..

# 2. 设置 DeepSeek API Key
export DEEPSEEK_API_KEY=sk-your-key-here

# 3. 一键启动
./start-all.sh

# 4. 打开浏览器
open http://localhost:5173
```

### 手动启动

```bash
# 终端 1: 记账后端
cd finance-backend && ./mvnw spring-boot:run

# 终端 2: MCP Server
cd finance-mcp-server && ./mvnw spring-boot:run

# 终端 3: Agent
cd finance-agent && ./mvnw spring-boot:run

# 终端 4: 前端
cd finance-frontend && npm run dev
```

## API 文档

### 记账后端 (:8080)

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/accounts | 账户列表 |
| POST | /api/accounts | 创建账户 |
| GET | /api/accounts/{id}/balance | 账户余额 |
| GET | /api/transactions | 交易列表 (?date=&category=&type=&accountId=) |
| POST | /api/transactions | 录入交易 |
| GET | /api/categories | 分类列表 |

### Agent (:8081)

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/chat | 对话接口 `{"message": "..."}` → `{"reply": "..."}` |

### MCP Server (:8082)

| Tool | Description |
|------|-------------|
| query_balance | 查询指定账户的余额 |
| list_transactions | 查询交易记录列表 |
| add_transaction | 添加一笔交易记录 |
| list_accounts | 查询所有账户列表 |

## MCP 接入

在 Claude Desktop 或其他 MCP 客户端中配置：

```json
{
  "mcpServers": {
    "finance": {
      "url": "http://localhost:8082/sse"
    }
  }
}
```

## 项目结构

```
personal-finance-agent/
├── finance-backend/          # 记账后端
├── finance-mcp-server/       # MCP Server
├── finance-agent/            # Agent 服务
├── finance-frontend/         # Vue 前端
├── start-all.sh              # 一键启动脚本
└── README.md
```

## License

MIT
