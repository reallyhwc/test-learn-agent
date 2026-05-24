# Personal Finance Agent · AI 记账助手

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring_Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![Spring AI 1.1](https://img.shields.io/badge/Spring_AI-1.1-blue)](https://docs.spring.io/spring-ai/)
[![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D)](https://vuejs.org/)
[![CI](https://img.shields.io/badge/CI-GitHub_Actions-2088FF)](https://github.com/features/actions)

一个学习型 Demo，在 Java 生态体系下实践 **AI Agent** 和 **MCP 协议（Model Context Protocol）**——可以对话的记账应用。

中文 | [English](README_EN.md)

---

## 这是什么？

一个包含 4 个独立服务的全栈项目，探索如何在 JVM 上构建 AI 驱动的应用。记录日常收支，然后通过自然语言对话查询数据。AI 理解你的意图，通过 MCP 工具调用正确的 API，返回格式化结果——支持 **SSE 流式输出**和**对话记忆**。

**你能从这个代码库中学到：**
- MCP 协议如何桥接 LLM 和业务 API
- Spring AI 1.1 如何集成 OpenAI 兼容模型（DeepSeek、通义千问等）
- 如何实现从 LLM 到浏览器的逐字 SSE 流式输出
- 如何组织多服务 Java 项目的清晰边界
- 如何构建完整的前后端自动化测试体系

---

## 系统架构

```mermaid
graph LR
    Browser["🌐 浏览器<br/>:5173"]
    Frontend["📱 前端<br/>Vue 3 + Element Plus<br/>+ ECharts"]
    Agent["🤖 Agent :8081<br/>Spring AI 1.1<br/>MCP Client<br/>ChatMemory"]
    LLM["🧠 LLM<br/>DeepSeek / OpenAI<br/>兼容 API"]
    MCPServer["🔧 MCP Server :8082<br/>5 个 Tool<br/>@McpTool 注解"]
    Backend["💾 Backend :8080<br/>Spring Boot 3.4<br/>CSV 存储"]

    Browser --> Frontend
    Frontend -->|"REST API"| Backend
    Frontend -->|"SSE 流式"| Agent
    Agent <-->|"OpenAI API"| LLM
    Agent -->|"MCP over SSE"| MCPServer
    MCPServer -->|"REST"| Backend
```

**4 个服务，2 条数据通路：**
- **CRUD 通路**：前端 → Backend（直接操作记账数据）
- **AI 通路**：前端 → Agent → LLM → MCP Server → Backend（自然语言驱动）

---

## Agent 调用架构

用户问 *"我理财赚了多少钱？"* 时的完整流转：

```mermaid
sequenceDiagram
    participant U as 👤 用户
    participant F as 📱 前端 (Vue)
    participant A as 🤖 Agent
    participant L as 🧠 LLM
    participant M as 🔧 MCP Server
    participant B as 💾 Backend

    U->>F: "我理财赚了多少钱？"
    F->>A: POST /api/chat/stream (SSE)
    
    Note over A: 构建 System Prompt<br/>注入账户上下文 + 工具决策规则
    A->>L: system prompt + user message + 5个工具定义
    
    Note over L: 自主决策：这是聚合统计问题<br/>→ 应调用 summarize_transactions
    L-->>A: Function Call:<br/>summarize_transactions<br/>filters={"type":"INCOME"}
    
    A->>M: MCP 协议调用工具
    M->>B: GET /api/transactions/summary?type=INCOME
    B-->>M: [{category:"理财",totalAmount:13164.35,count:13}, ...]
    M-->>A: 汇总数据
    
    A->>L: 工具返回的真实数据
    L-->>A: "您通过理财共赚取了 ¥13,164.35..."
    
    loop SSE 逐 token 推送
        A-->>F: event:data → token
    end
    F-->>U: Markdown 渲染 + ECharts 图表
```

### Agent 内部架构

```mermaid
graph TB
    subgraph "ChatController"
        REQ["POST /api/chat/stream"]
        SYS["System Prompt 构建器"]
        CTX["AccountContextBuilder<br/>自动注入账户上下文<br/>30s 缓存"]
        MEM["ChatMemory<br/>对话记忆 (max 20轮)"]
        METRICS["Metrics 埋点<br/>TTFT / Token 用量"]
    end
    
    subgraph "Spring AI ChatClient"
        CC["ChatClient.prompt()"]
        TOOLS["MCP Tool Callbacks<br/>5 个工具自动注册"]
        STREAM["Flux 流式响应"]
    end
    
    subgraph "SSE 输出"
        THK["event:thinking → 推理过程"]
        MSG["event:message → 正式回答"]
        ERR["event:error → 错误信息"]
    end
    
    REQ --> SYS
    SYS --> CTX
    SYS --> MEM
    SYS --> CC
    CC --> TOOLS
    CC --> STREAM
    STREAM --> THK
    STREAM --> MSG
    STREAM --> ERR
    REQ --> METRICS
```

**核心设计：LLM 自主决定调用哪个工具。** System Prompt 内置参数决策规则（如"涉及汇总用 summarize_transactions"），LLM 据此判断并调用——这就是 Agent 模式的核心。

---

## 记账应用设计

### 数据模型

```mermaid
erDiagram
    USER ||--o{ ACCOUNT : "拥有"
    ACCOUNT ||--o{ TRANSACTION : "产生"
    
    ACCOUNT {
        long id PK
        string name "账户名 (现金/银行卡/...)"
        string type "CASH / BANK / ALIPAY / WECHAT"
        decimal balance "实时余额 (自动计算)"
        string userId "所属用户"
    }
    
    TRANSACTION {
        long id PK
        long accountId FK "关联账户"
        string type "INCOME / EXPENSE"
        decimal amount "金额"
        string category "分类 (餐饮/交通/理财/...)"
        string note "备注"
        date date "日期"
        string userId "所属用户"
    }
```

### 存储设计

**CSV 文件存储**——零环境依赖，clone + 配 Key 就能跑：

```
finance-backend/data/
├── accounts.csv        # id,name,type,balance,userId
└── transactions.csv    # id,accountId,type,amount,category,note,date,userId
```

- 启动时全量加载到内存 `ConcurrentHashMap`
- 写入时原子重命名（临时文件 → 正式文件），避免数据损坏
- 按 `userId` 隔离数据，模拟多租户

### MCP 工具清单

| 工具 | 功能 | 参数 |
|------|------|------|
| **`summarize_transactions`** | 按分类汇总交易金额统计 | `userId`, `filters` (JSON) |
| **`list_transactions`** | 查询交易记录明细列表 | `userId`, `filters` (JSON) |
| **`add_transaction`** | 添加一笔交易记录 | `userId`, `accountId`, `type`, `amount`, `category`, `note` |
| **`list_accounts`** | 查询用户全部账户列表 | `userId` |
| **`query_balance`** | 按 accountId 查询余额 | `userId`, `accountId` |

> 可选参数通过 `filters` JSON 字符串传入（如 `{"type":"INCOME","category":"理财"}`），避免 MCP Schema 的 required/optional 歧义。

### 后端 API

| 方法 | 路径 | 功能 |
|------|------|------|
| `GET` | `/api/accounts` | 查询账户列表 |
| `POST` | `/api/accounts` | 创建账户 |
| `GET` | `/api/accounts/{id}/balance` | 查询余额 |
| `GET` | `/api/transactions` | 分页查询交易记录（支持日期范围/分类/类型过滤） |
| `GET` | `/api/transactions/summary` | 按分类汇总统计 |
| `POST` | `/api/transactions` | 创建交易记录 |
| `GET` | `/api/categories` | 获取分类列表 |

> 集成 SpringDoc OpenAPI，启动后访问 `http://localhost:8080/swagger-ui.html` 查看完整文档。

### 前端组件

```mermaid
graph TB
    subgraph "App.vue — 响应式布局"
        direction TB
        HEADER["AppHeader · 顶部导航 + 用户切换"]
        
        subgraph "左侧 · 记账区"
            ACC["AccountList · 账户列表"]
            FORM["TransactionForm · 记一笔"]
            LIST["TransactionList · 交易明细"]
            CHART["ChartPanel → ChartRenderer · ECharts 图表"]
        end
        
        subgraph "右侧 · AI 对话区"
            CHAT["ChatPanel · SSE 流式接收"]
            MSG["ChatMessage · Markdown 渲染"]
        end
    end
    
    HEADER --> ACC
    ACC --> FORM
    FORM --> LIST
    LIST --> CHART
    CHAT --> MSG
```

- **移动端**自动切换为 Tab 模式（📊 数据 / 💬 助手）
- **ChatMessage** 支持 Markdown 表格、代码高亮、XSS 防护
- **ChartRenderer** 自动检测表格数据并生成 ECharts 图表

---

## 为什么要拆成 4 个服务？

把 Java 代码塞进一个 Spring Boot 项目也能跑。故意拆开是为了学习：

| 服务 | 职责 | 知道 AI？ | 知道业务？ |
|------|------|:---:|:---:|
| **Backend** | 纯 REST API + CSV 存储 | ✗ | ✓ |
| **MCP Server** | 将 REST 包装为 MCP 工具 | ✗ | ✗（纯透传） |
| **Agent** | MCP Client + LLM 编排 | ✓ | ✗ |
| **Frontend** | UI，同时调 Backend 和 Agent | ✗ | ✗ |

这种分离让 MCP 层**可见且可感知**。真实系统中可以把 MCP Server 合并到 Backend，但这里你能清楚看到协议边界究竟在哪里。

---

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| **前端** | Vue 3 + Element Plus + ECharts + Pinia | 3.5 / 2.14 / 6.1 / 3.0 |
| **前端工具** | Vite + Vitest + ESLint | 8.0 / 4.1 / 10.4 |
| **后端** | Spring Boot + SpringDoc OpenAPI | 3.4.5 / 2.8.6 |
| **AI 框架** | Spring AI + MCP Protocol | 1.1.0 |
| **LLM** | DeepSeek / OpenAI / 通义千问 (任何兼容 API) | — |
| **监控** | Micrometer + Prometheus | 随 Spring Boot |
| **CI/CD** | GitHub Actions (Java 17 + Node 18) | — |
| **容器化** | Docker Compose (4 服务) | — |

---

## 设计决策

| 决策 | 为什么 |
|------|--------|
| **CSV 而非数据库** | 零环境依赖，clone + 配 Key 就跑。CSV 文件可直接用文本编辑器打开调试 |
| **`.env` 配置** | 一份文件搞定 LLM 凭证。Spring Boot 自定义 `PropertySourceLoader` 原生加载，不用 export |
| **SSE 而非 WebSocket** | 单向推送契合流式 LLM 输出，比 WebSocket 简单，能穿透 HTTP 代理 |
| **`userId` 参数隔离** | 顶部切换用户，无真正鉴权。演示多租户数据隔离思路 |
| **filters JSON 参数** | MCP `@McpToolParam` 无法标记 optional，将可选参数合并为 JSON 字符串避免 LLM 困惑 |
| **System Prompt 决策规则** | 内置工具选择规则（如"汇总用 summarize_transactions"），减少 LLM 反复推理 |

---

## 快速开始

### 环境要求

- **Java 17+**（推荐 [Adoptium](https://adoptium.net/)）
- **Node.js 18+**
- **LLM API Key**（DeepSeek / OpenAI / 通义千问等）

### 一键启动

```bash
# 1. 克隆
git clone https://github.com/your-username/personal-finance-agent.git
cd personal-finance-agent

# 2. 配置 LLM
cp .env.example .env
# 编辑 .env → 填入你的 API Key

# 3. 安装前端依赖
cd finance-frontend && npm install && cd ..

# 4. 一键启动（按顺序启动 4 个服务，含健康检查）
./start-all.sh

# 5. 打开 http://localhost:5173
```

> **提示：** 如果 Maven 编译报错，检查 `JAVA_HOME` 是否指向 JDK 17。

### 手动启动（4 个终端，方便调试）

```bash
cd finance-backend && ./mvnw spring-boot:run      # :8080
cd finance-mcp-server && ./mvnw spring-boot:run    # :8082
cd finance-agent && ./mvnw spring-boot:run         # :8081
cd finance-frontend && npm run dev                 # :5173
```

### Docker Compose 部署

```bash
cp .env.example .env && vim .env    # 配置 API Key
docker-compose up
```

4 个服务容器化部署，自动按依赖顺序启动，含 healthcheck。Backend 数据持久化到 Docker volume。

### 环境变量

| 变量 | 必填 | 说明 | 默认值 |
|------|:----:|------|--------|
| `LLM_API_KEY` | ✅ | LLM API Key | — |
| `LLM_BASE_URL` | ✅ | OpenAI 兼容 API 地址 | `https://api.deepseek.com` |
| `LLM_MODEL` | ✅ | 模型名称 | `deepseek-chat` |

支持：**DeepSeek**、**通义千问**、**OpenAI**、**Groq**、**Moonshot**、**SiliconFlow** 等任何 OpenAI 兼容 API。

---

## 项目地图

```
.
├── finance-backend/          Spring Boot 3.4 · REST API · CSV 存储
│   ├── controller/           AccountController, TransactionController
│   ├── service/              FinanceService (业务逻辑 + 聚合统计)
│   ├── repository/           CsvDataStore (原子写入 + 内存索引)
│   ├── exception/            GlobalExceptionHandler (统一错误响应)
│   └── util/                 XssUtils, LogMaskUtils
│
├── finance-mcp-server/       Spring AI MCP · @McpTool 注解
│   └── tool/FinanceTools     5 个工具, parseFilters 辅助方法
│
├── finance-agent/            Spring AI 1.1 · MCP Client · ChatMemory
│   ├── controller/           ChatController (/chat/stream SSE)
│   ├── context/              AccountContextBuilder (30s 缓存)
│   ├── memory/               对话记忆 (max 20 轮)
│   └── metrics/              AgentMetrics (TTFT, Token 用量)
│
├── finance-frontend/         Vue 3 · Element Plus · ECharts
│   ├── components/           9 个组件 (ChatPanel, ChartRenderer...)
│   ├── stores/               Pinia userStore (localStorage 持久化)
│   └── utils/                api.js, streamParser.js, markdown.js
│
├── .github/workflows/ci.yml  GitHub Actions CI
├── docker-compose.yml        4 服务容器化部署
├── .env.example              LLM 配置模板
└── start-all.sh              一键启动 (含健康检查)
```

每个模块独立 `pom.xml` / `package.json`，互相不共享代码——仅通过 HTTP 通信。

---

## AI 对话示例

```
你: 我的账户余额是多少？
AI: 您的默认现金账户当前余额为 ¥20,273.96 元。

你: 我理财赚了多少钱？
AI: 您通过理财共赚取了 ¥13,164.35，累计 13 笔交易。

你: 帮我记一笔：午餐 50 元
AI: 已为您记录：支出 ¥50.00，分类：餐饮，备注：午餐。
```

所有查询都走 MCP 工具链路。AI 不会编造数据——System Prompt 要求每次必须调用工具获取真实数据。

---

## 测试体系

```
测试覆盖: 后端 ~46 用例 + 前端 70 用例 + MCP ~16 用例
```

| 层 | 框架 | 覆盖范围 |
|----|------|----------|
| **后端 Controller** | Spring MockMvc | 账户/交易 CRUD、分页、日期范围过滤、聚合统计 |
| **后端 Service** | JUnit 5 | CSV 读写、多用户隔离、余额计算 |
| **后端异常处理** | MockMvc | GlobalExceptionHandler 统一响应格式 |
| **MCP 工具** | MockRestServiceServer | 5 个工具正常/异常路径、入参校验、JSON 降级 |
| **前端组件** | Vitest + Vue Test Utils | ChatPanel、ChatMessage、TransactionForm |
| **前端 Store** | Vitest | Pinia userStore 持久化、用户切换 |
| **前端工具** | Vitest | API 封装、SSE 流解析、Markdown 渲染、图表提取 |
| **CI** | GitHub Actions | 自动化测试 + ESLint + 覆盖率 + OWASP 安全扫描 |

运行测试：
```bash
# 前端
cd finance-frontend && npx vitest run

# 后端（含 MCP Server）
cd finance-backend && ./mvnw verify
cd finance-mcp-server && ./mvnw verify
```

---

## Claude Desktop 接入

MCP Server 对外暴露标准 MCP 协议端点：

```json
{
  "mcpServers": {
    "finance": {
      "url": "http://localhost:8082/sse"
    }
  }
}
```

加到 `claude_desktop_config.json`，Claude Desktop 就能直接查询你的记账数据。

---

## 常见问题

**能用其他大模型吗？** 可以。编辑 `.env` 切换——任何 OpenAI 兼容 API 都行。

**端口被占用？**
```bash
lsof -ti:8080,8081,8082,5173 | xargs kill -9
```

**怎么重置数据？** `rm -rf finance-backend/data`

**Swagger 文档在哪？** 启动 Backend 后访问 `http://localhost:8080/swagger-ui.html`

**健康检查？** 各服务暴露 Actuator 端点：`http://localhost:{port}/actuator/health`

---

## License

MIT © 2026
