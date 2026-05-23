# Personal Finance Agent — 设计文档

## 概述

一个学习型 demo 项目，在 Java 生态体系下实践 AI Agent 和 MCP 协议。包含记账后端、Vue 前端、答疑 Agent、MCP Server 四个模块。

- **目标用户**: Java 后端程序员，用于学习 Agent/MCP 相关技术
- **项目名**: personal-finance-agent
- **语言**: 中文（代码 & 文档）

---

## 架构

### 进程拓扑

```
Vue 前端 (:5173)
    ├── HTTP REST → 记账后端 (:8080)         # 账户/交易 CRUD
    └── HTTP REST → Agent 服务 (:8081)       # 对话接口
                        │ MCP 协议 (SSE)
                        ▼
                    MCP Server (:8082)        # Tool 暴露
                        │ HTTP REST
                        ▼
                    记账后端 (:8080)          # 数据查询
```

### 调用链路（对话场景）

1. 用户在聊天面板输入自然语言 → Vue 调 `POST /api/chat`
2. Agent (LLM + MCP Client) 分析意图 → 调 MCP Server `tools/call`
3. MCP Server 调记账后端 HTTP API 获取数据
4. 数据返回 Agent → LLM 生成自然语言回复

### MCP 协议握手流程

- Agent 启动时连接 MCP Server SSE endpoint
- `initialize` → `tools/list` 获取 Tool 定义
- 运行时通过 `tools/call` 调用 Tool
- 服务发现：Agent yml 配置 MCP Server URL，MCP Server yml 配置记账后端 URL（纯静态，无注册中心）

---

## 模块设计

### 1. 记账后端 (finance-backend)

- **技术**: Spring Boot 3.x + Java 17 + Maven Wrapper
- **端口**: 8080
- **存储**: CSV 文件 (`data/accounts.csv`, `data/transactions.csv`, `data/categories.csv`)，启动加载到内存，写操作同时刷盘

**API**:

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/accounts | 账户列表 |
| POST | /api/accounts | 创建账户 |
| GET | /api/accounts/{id}/balance | 账户余额 |
| GET | /api/transactions | 交易列表 (?date=&category=&type=&accountId=) |
| POST | /api/transactions | 录入交易 |
| GET | /api/categories | 分类列表 |

**领域模型**:

| 实体 | 字段 |
|------|------|
| Account | id, name, type(CASH/BANK/CARD), balance |
| Transaction | id, accountId, type(INCOME/EXPENSE), amount, category, note, date |
| Category | id, name, type(INCOME/EXPENSE) |

### 2. MCP Server (finance-mcp-server)

- **技术**: Spring Boot 3.x + Spring AI MCP Server + Maven Wrapper
- **端口**: 8082
- **传输**: SSE
- **依赖**: 通过 RestTemplate 调用记账后端 API

**暴露的 Tool**:

| Tool | 参数 | 对应后端 API |
|------|------|------------|
| query_balance | accountId (long) | GET /api/accounts/{id}/balance |
| list_transactions | date, category, type, accountId (all optional) | GET /api/transactions |
| add_transaction | accountId, type, amount, category, note | POST /api/transactions |
| list_accounts | none | GET /api/accounts |

### 3. Agent 服务 (finance-agent)

- **技术**: Spring Boot 3.x + Spring AI + MCP Client (Boot Starter) + Maven Wrapper
- **端口**: 8081
- **LLM**: DeepSeek API (兼容 OpenAI 格式)
- **MCP Client**: 连接 MCP Server，通过 MCP 协议调用 Tool

**API**:

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/chat | 对话接口，body: { "message": "..." } → { "reply": "..." } |

### 4. 前端 (finance-frontend)

- **技术**: Vue 3 + Vite
- **端口**: 5173
- **UI**: 纯 CSS，不引入 UI 库

**组件树**:

```
App.vue
├── AppHeader.vue          # 顶部导航
├── AccountList.vue        # 账户卡片列表
├── TransactionList.vue    # 交易流水列表（支持日期/分类筛选）
├── TransactionForm.vue    # 新增交易表单（模态框）
└── ChatPanel.vue          # 右侧内嵌聊天面板
     └── ChatMessage.vue   # 单条消息气泡
```

---

## 环境 & 配置

### 开发环境

- **Java**: 17 (通过 SDKMAN 安装，与现有 Java 8 共存)
- **Maven**: 通过 Maven Wrapper (`mvnw`)，无需系统安装
- **Node.js**: v24 (已安装)
- **Python**: 3.11 (已安装)

### DeepSeek API 配置

在 `finance-agent/src/main/resources/application.yml` 中配置：

```yaml
spring.ai.openai:
  api-key: ${DEEPSEEK_API_KEY}
  base-url: https://api.deepseek.com/v1
  chat.options:
    model: deepseek-chat
```

### 启动脚本

`start-all.sh`:

```bash
#!/bin/bash
./finance-backend/mvnw spring-boot:run &
sleep 5
./finance-mcp-server/mvnw spring-boot:run &
sleep 5
./finance-agent/mvnw spring-boot:run &
sleep 3
cd finance-frontend && npm install && npm run dev &
```

---

## 测试策略 (TDD)

| 模块 | 框架 | 测试内容 |
|------|------|---------|
| 记账后端 | JUnit 5 + MockMvc | Controller 集成测试 + Service 单元测试 |
| MCP Server | JUnit 5 + MockMvc | Tool 调用集成测试 |
| Agent | JUnit 5 + Mock | ChatController + MCP Client mock 测试 |
| 前端 | Vitest | 组件渲染测试（按需） |

开发节奏：先写测试 → 写实现 → 跑绿 → 重构。每个模块独立 TDD 循环。

---

## 非功能性约束

- 无鉴权逻辑（纯 demo）
- 不考虑高并发/性能优化
- CSV 文件为单实例设计，不保证并发安全
- 前端不引入额外 UI 框架，保持轻量

---

## 项目目录结构

```
personal-finance-agent/
├── finance-backend/          # 记账后端
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   ├── data/                 # CSV 数据文件（运行时生成）
│   └── src/
├── finance-mcp-server/       # MCP Server
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/
├── finance-agent/            # Agent 服务
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/
├── finance-frontend/         # Vue 前端
│   ├── package.json
│   └── src/
├── start-all.sh
└── README.md
```
