# Personal Finance Agent · AI 记账助手

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Python 3.11+](https://img.shields.io/badge/Python-3.11+-blue)](https://www.python.org/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring_Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![Spring AI 1.1](https://img.shields.io/badge/Spring_AI-1.1-blue)](https://docs.spring.io/spring-ai/)
[![LangChain](https://img.shields.io/badge/LangChain-0.3+-orange)](https://www.langchain.com/)
[![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D)](https://vuejs.org/)
[![CI](https://img.shields.io/badge/CI-GitHub_Actions-2088FF)](https://github.com/features/actions)

一个学习型 Demo，在 **Java + Python** 双生态下实践 **AI Agent** 和 **MCP 协议（Model Context Protocol）**——可以对话的记账应用。

中文 | [English](README_EN.md)

---

## 这是什么？

一个包含 6 个服务模块的全栈项目，探索如何用 **Java Spring AI** 和 **Python LangChain** 构建 AI 驱动的应用。记录日常收支，然后通过自然语言对话查询数据。AI 理解你的意图，通过 MCP 工具调用正确的 API，返回格式化结果——支持 **SSE 流式输出**和**对话记忆**。

**你能从这个代码库中学到：**
- MCP 协议如何桥接 LLM 和业务 API
- Spring AI vs LangChain 构建 AI Agent 的对比
- FastMCP (Python) vs Spring AI MCP (Java) 的 MCP Server 实现对比
- 如何实现从 LLM 到浏览器的逐字 SSE 流式输出
- 通过 `config.yaml` 在 Java/Python 服务间自由切换
- 如何组织多服务项目的清晰边界
- 如何构建覆盖 Java + Python + Vue 的全栈自动化测试体系

---

## 系统架构

```mermaid
graph LR
    Browser["🌐 浏览器<br/>:5173"]
    Frontend["📱 前端<br/>Vue 3 + Element Plus<br/>+ ECharts"]
    Agent["🤖 Agent :8081/:8084<br/>Spring AI / LangChain<br/>MCP Client<br/>ChatMemory"]
    LLM["🧠 LLM<br/>DeepSeek / OpenAI<br/>兼容 API"]
    MCPServer["🔧 MCP Server :8082/:8083<br/>Spring AI MCP / FastMCP<br/>5 个 Tool"]
    Backend["💾 Backend :8080<br/>Spring Boot 3.4<br/>CSV 存储"]

    Browser --> Frontend
    Frontend -->|"REST API"| Backend
    Frontend -->|"SSE 流式"| Agent
    Agent <-->|"OpenAI API"| LLM
    Agent -->|"MCP over SSE"| MCPServer
    MCPServer -->|"REST"| Backend
```

**Java + Python 双栈，共享同一个 Backend：**

| 层 | Java 实现 | Python 实现 | 端口 |
|----|----------|------------|:---:|
| **Agent** | `finance-agent` (Spring AI) | `finance-agent-py` (LangChain) | 8081 / 8084 |
| **MCP Server** | `finance-mcp-server` (Spring AI MCP) | `finance-mcp-server-py` (FastMCP) | 8082 / 8083 |
| **前端** | `finance-frontend` (Vue 3) | — | 5173 |
| **后端** | `finance-backend` (Spring Boot) | — | 8080 |

通过 `config.yaml` 切换 Java/Python：

```yaml
# config.yaml
ai:
  agent: python   # java | python
  mcp: python     # java | python
```

前端顶栏实时显示当前 AI 提供者徽标，支持一键切换。

**2 条数据通路：**
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
        string category "一级分类 (餐饮/交通/理财/...)"
        string subCategory "二级分类 (外卖/打车/利息/...)"
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
└── transactions.csv    # id,accountId,type,amount,category,subCategory,note,date,userId
```

- 启动时全量加载到内存 `ConcurrentHashMap`
- 写入时原子重命名（临时文件 → 正式文件），避免数据损坏
- 按 `userId` 隔离数据，模拟多租户

### MCP 工具清单

| 工具 | 功能 | 参数 |
|------|------|------|
| **`summarize_transactions`** | 按分类汇总交易金额统计 | `userId`, `filters` (JSON) |
| **`list_transactions`** | 查询交易记录明细列表 | `userId`, `filters` (JSON) |
| **`add_transaction`** | 添加一笔交易记录 | `userId`, `accountId`, `type`, `amount`, `category`, `subCategory`, `note` |
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
        HEADER["AppHeader · 顶部导航 + 用户切换 + AI 提供者徽标"]
        
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
- **AppHeader** 显示当前 AI 提供者徽标（Java/Python），支持一键切换

---

## 为什么要拆成 6 个模块？

把代码塞进一个项目也能跑。故意拆开是为了学习：

| 服务 | 职责 | 知道 AI？ | 知道业务？ |
|------|------|:---:|:---:|
| **Backend** | 纯 REST API + CSV 存储 | ✗ | ✓ |
| **MCP Server** | 将 REST 包装为 MCP 工具 | ✗ | ✗（纯透传） |
| **Agent** | MCP Client + LLM 编排 | ✓ | ✗ |
| **Frontend** | UI，同时调 Backend 和 Agent | ✗ | ✗ |

Agent 和 MCP Server 各有 Java/Python 两套实现，功能完整且可互换——通过 `config.yaml` 切换，非常适合对比两个技术栈的差异。

这种分离让 MCP 层**可见且可感知**。真实系统中可以把 MCP Server 合并到 Backend，但这里你能清楚看到协议边界究竟在哪里。

---

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| **前端** | Vue 3 + Element Plus + ECharts + Pinia | 3.5 / 2.14 / 6.1 / 3.0 |
| **前端工具** | Vite + Vitest + ESLint | 8.0 / 4.1 / 10.4 |
| **后端** | Spring Boot + SpringDoc OpenAPI | 3.4.5 / 2.8.6 |
| **AI 框架 (Java)** | Spring AI + MCP Protocol | 1.1.0 |
| **AI 框架 (Python)** | LangChain + langchain-mcp-adapters + LangGraph | 0.3+ |
| **MCP Server (Python)** | FastMCP (mcp) | 1.x |
| **LLM** | DeepSeek / OpenAI / 通义千问 (任何兼容 API) | — |
| **监控** | Micrometer + Prometheus | 随 Spring Boot |
| **CI/CD** | GitHub Actions (Java 17 + Node 18) | — |

---

## 设计决策

| 决策 | 为什么 |
|------|--------|
| **CSV 而非数据库** | 零环境依赖，clone + 配 Key 就跑。CSV 文件可直接用文本编辑器打开调试 |
| **`.env` 配置** | 一份文件搞定 LLM 凭证。Java 用自定义 `PropertySourceLoader`，Python 用 `python-dotenv` |
| **`config.yaml` 切换** | 通过配置选择 Java/Python 实现，前端实时反映当前 AI 提供者 |
| **SSE 而非 WebSocket** | 单向推送契合流式 LLM 输出，比 WebSocket 简单，能穿透 HTTP 代理 |
| **`userId` 参数隔离** | 顶部切换用户，无真正鉴权。演示多租户数据隔离思路 |
| **filters JSON 参数** | MCP `@McpToolParam` 无法标记 optional，将可选参数合并为 JSON 字符串避免 LLM 困惑 |
| **System Prompt 决策规则** | 内置工具选择规则（如"汇总用 summarize_transactions"），减少 LLM 反复推理 |
| **Agent 外部初始化** | Python Agent 在 uvicorn 启动前初始化 MCP 连接，避免 FastAPI lifespan + anyio cancel scope 冲突 |

---

## 快速开始

### 环境要求

- **Java 17+**（推荐 [Adoptium](https://adoptium.net/)）
- **Python 3.11+**（用于 Python 服务）
- **Node.js 18+**
- **LLM API Key**（DeepSeek / OpenAI / 通义千问等）

### 一键启动

```bash
# 1. 克隆
git clone https://github.com/reallyhwc/test-learn-agent.git
cd test-learn-agent

# 2. 配置 LLM
cp .env.example .env
# 编辑 .env → 填入你的 API Key

# 3. 配置 AI 提供者（可选，默认 java）
# 编辑 config.yaml → 设置 ai.agent 和 ai.mcp 为 java 或 python

# 4. 一键启动（读取 config.yaml，按需启动对应服务）
./start-all.sh

# 5. 打开 http://localhost:5173
```

> **提示：** 如果 Maven 编译报错，检查 `JAVA_HOME` 是否指向 JDK 17。

### config.yaml 说明

```yaml
ai:
  agent: python   # Agent 实现: java (Spring AI) | python (LangChain)
  mcp: python     # MCP Server 实现: java (Spring AI MCP) | python (FastMCP)
```

- **全 Java 栈**：agent=java, mcp=java → 使用 :8081 和 :8082
- **全 Python 栈**：agent=python, mcp=python → 使用 :8084 和 :8083
- **混合栈**：agent=java, mcp=python 或 agent=python, mcp=java

### 手动启动

```bash
# Java 栈
cd finance-backend && ./mvnw spring-boot:run          # Backend :8080
cd finance-mcp-server && ./mvnw spring-boot:run        # MCP Server :8082
cd finance-agent && ./mvnw spring-boot:run             # Agent :8081

# Python 栈
cd finance-mcp-server-py && python3 server.py          # MCP Server :8083
cd finance-agent-py && python3 main.py                 # Agent :8084

# 前端
cd finance-frontend && npm run dev                     # :5173
```

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
├── config.yaml                        # AI 提供者配置 (Agent/MCP 语言选择)
├── start-all.sh                       # 一键启动 (读取 config.yaml)
│
├── finance-backend/                   Spring Boot 3.4 · REST API · CSV 存储
│   ├── controller/                    AccountController, TransactionController
│   ├── service/                       FinanceService (业务逻辑 + 聚合统计)
│   ├── repository/                    CsvDataStore (原子写入 + 内存索引)
│   ├── exception/                     GlobalExceptionHandler (统一错误响应)
│   └── util/                          XssUtils, LogMaskUtils
│
├── finance-mcp-server/                Spring AI MCP · @McpTool 注解
│   └── tool/FinanceTools              5 个工具, parseFilters 辅助方法
│
├── finance-mcp-server-py/             Python FastMCP · 功能完整
│   └── server.py                      5 个 MCP 工具 (SSE 传输)
│
├── finance-agent/                     Spring AI 1.1 · MCP Client · ChatMemory
│   ├── controller/                    ChatController (/chat/stream SSE)
│   ├── context/                       AccountContextBuilder (30s 缓存)
│   ├── memory/                        对话记忆 (max 20 轮)
│   └── metrics/                       AgentMetrics (TTFT, Token 用量)
│
├── finance-agent-py/                  Python LangChain · ReAct Agent · 功能完整
│   ├── agent.py                       FinanceAgent (MCP 工具 + DeepSeek LLM)
│   ├── chat_server.py                 FastAPI SSE 流式接口
│   ├── system_prompt.py               System Prompt + 账户上下文注入
│   ├── memory_manager.py              JSON 文件对话记忆
│   └── config_loader.py               .env + config.yaml 加载器
│
├── finance-frontend/                  Vue 3 · Element Plus · ECharts
│   ├── components/                    9 个组件 (ChatPanel, ChartRenderer...)
│   ├── stores/                        Pinia (userStore, aiStore)
│   └── utils/                         api.js, streamParser.js, markdown.js
│
├── .github/workflows/ci.yml           GitHub Actions CI
├── .env.example                       LLM 配置模板
└── githooks/                          commit-msg (Conventional Commits 校验)
```

每个模块独立构建，互相不共享代码——仅通过 HTTP / MCP 协议通信。

---

## AI Coding Harness · AI 编程脚手架

本项目内置了完整的 **AI Coding Harness**——专为 AI 编程工具（Copilot / Claude Code / Aone Copilot）设计的工程脚手架，通过 Rules、Skills、CLAUDE.md 等结构化约束，让 AI 在操作本项目时有明确的规范边界和操作指南。

### 设计理念

> AI 编程工具的能力上限，取决于它对项目的理解深度。Harness 的核心是**把人脑中的隐式知识（架构约束、变更链路、踩坑经验）显式化为机器可读的规则**。

```mermaid
graph TB
    subgraph "Harness 三层体系"
        CLAUDE["CLAUDE.md<br/>全局约束入口<br/>Testing / Anti-Patterns / Tech Debt"]
        RULES["Rules 编码规范<br/>8 份规范文档<br/>命名 / 分层 / 异常 / DTO / 测试 / 前端 / MCP / CSV"]
        SKILLS["Skills 操作清单<br/>3 个标准流程<br/>add-model-field / add-mcp-tool / csv-migration"]
    end

    AI["🤖 AI 编程工具"]
    AI -->|"自动读取"| CLAUDE
    AI -->|"按架构匹配"| RULES
    AI -->|"按场景触发"| SKILLS

    CLAUDE -->|"全局防御规则"| RULES
    RULES -->|"规范约束"| SKILLS
```

### 目录结构

```
.aone_copilot/
├── rules/                          # 编码规范（AI 自动读取）
│   ├── 工程结构.md                   # 项目全景：模块清单、数据模型、影响链
│   └── spec/
│       ├── 三层架构/                  # 三层架构通用规范
│       │   ├── 01-命名规范.md         # 类名/方法名/包名/变量名
│       │   ├── 02-分层规范.md         # Controller→Service→Repository 边界
│       │   ├── 03-异常处理规范.md      # GlobalExceptionHandler + MCP 兜底
│       │   ├── 04-DTO规范.md          # Lombok 注解选择 + Javadoc 要求
│       │   ├── 05-测试规范.md         # 状态码约定 + 数据隔离
│       │   └── 06-前端规范.md         # Vue 组合式 API + SSE 处理
│       └── custom/                   # 项目特有规范
│           ├── mcp-tool-规范.md       # @McpTool 注解 + 参数设计 + 埋点
│           └── csv-存储规范.md        # Schema 升级流程 + 兼容性规则
│
├── skills/                         # 项目级 Skills（按场景触发）
│   ├── add-model-field/SKILL.md     # "给 Model 加字段" 11 步清单
│   ├── add-mcp-tool/SKILL.md        # "新增 MCP 工具" 7 步清单
│   └── csv-migration/SKILL.md       # "CSV Schema 升级" 8 步清单
│
└── plans/                          # 执行计划（自动生成）
    └── {feature_name}/
        ├── implementation_plan.md   # 技术实施计划
        └── task.md                  # 任务进度清单
```

### 核心机制

#### 1. 跨模块变更影响链

本项目最大的复杂度不在单个模块，而在**变更传播**。改一个 Model 字段，会像多米诺骨牌一样影响 7 层：

```
Model 字段变更 → CSV Schema → 种子数据 → Service 校验
→ Controller 参数 → MCP 工具描述 → Agent Prompt → 前端表单/列表/图表
```

Harness 通过 `add-model-field` Skill 把这条链路固化为 11 步检查清单，AI 按步执行不会遗漏。

#### 2. 防御性规则（Anti-Patterns）

CLAUDE.md 中内置了从实际踩坑中总结的防御规则：

- **禁止盲改**：修改文件前必须先 `read_file`，改方法签名前必须 `file_grep` 找到所有调用方
- **禁止假设**：不假设 test 的期望状态码（先看 Controller 注解）、不假设构造器参数顺序（先看字段声明）、不假设旧 CSV 有新列（必须做兼容检测）
- **多模块同步**：修改共享类型时，必须在同一个 commit 内同步所有模块

#### 3. Skills 触发机制

当你对 AI 说"给 Transaction 加一个 tag 字段"，AI 会自动匹配 `add-model-field` Skill，按清单逐步执行：

| 步骤 | 操作 | 模块 |
|------|------|------|
| 1 | 新增字段到 Model 类 | finance-backend |
| 2 | 更新 CSV Schema + 旧 Schema 兼容 | finance-backend |
| 3 | loadXxx() 旧格式检测 + 自动补全 | finance-backend |
| 4 | findXxx() 新增过滤条件 | finance-backend |
| 5 | Service 校验逻辑 | finance-backend |
| 6 | Controller 参数 + XSS 清洗 | finance-backend |
| 7 | MCP Response DTO | finance-mcp-server |
| 8 | MCP 工具参数和描述 | finance-mcp-server |
| 9 | Agent System Prompt | finance-agent |
| 10 | 前端表单/列表/图表 | finance-frontend |
| 11 | 测试修复 + 编译验证 | 全部模块 |

### 对 AI 编程的价值

| 没有 Harness | 有 Harness |
|-------------|-----------|
| AI 改了 Model 忘了改 CSV Schema | Skill 清单强制 11 步全走 |
| AI 假设 POST 返回 200，测试断言写错 | Rules 明确规定 POST 创建返回 201 |
| AI 改了方法签名，其他调用方编译报错 | Anti-Pattern 要求先 grep 再改 |
| AI 不知道旧 CSV 缺新列，启动时 crash | CSV 规范要求兼容检测 + 自动补全 |
| AI 在不同 commit 改不同模块，中间态编译失败 | 规则要求同一 commit 同步所有模块 |

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
全栈测试覆盖: 后端 ~46 用例 + 前端 109 用例 + MCP ~16 用例 + Agent Java 14 用例 + Python 33 用例
```

| 层 | 框架 | 覆盖范围 |
|----|------|----------|
| **后端 Controller** | Spring MockMvc | 账户/交易 CRUD、分页、日期范围过滤、聚合统计 |
| **后端 Service** | JUnit 5 | CSV 读写、多用户隔离、余额计算 |
| **后端异常处理** | MockMvc | GlobalExceptionHandler 统一响应格式 |
| **MCP 工具 (Java)** | MockRestServiceServer | 5 个工具正常/异常路径、入参校验、JSON 降级 |
| **Agent (Java)** | JUnit 5 + MockMvc | 熔断器状态转换、反馈接口、记忆管理 |
| **前端组件** | Vitest + Vue Test Utils | ChatPanel、ChatMessage、TransactionForm、AppHeader、TransactionList、AccountList |
| **前端 Store** | Vitest | Pinia userStore 持久化 + aiStore Agent/MCP 切换 |
| **前端工具** | Vitest | API 封装、SSE 流解析（含 CRLF 兼容）、Markdown 渲染、图表提取 |
| **Python Agent** | pytest + pytest-asyncio | 配置加载、记忆管理、System Prompt、SSE 端点、userId 校验 |
| **CI** | GitHub Actions | 自动化测试 + ESLint + 覆盖率 + OWASP 安全扫描 |

运行测试：
```bash
# 前端 (109 用例)
cd finance-frontend && npx vitest run

# 后端（含 MCP Server）
cd finance-backend && ./mvnw verify
cd finance-mcp-server && ./mvnw verify

# Java Agent
cd finance-agent && ./mvnw test

# Python Agent (33 用例)
cd finance-agent-py && python -m pytest tests/ -v
```

---

## Claude Desktop 接入

MCP Server 对外暴露标准 MCP 协议端点，根据你的配置选择对应端口：

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

加到 `claude_desktop_config.json`，Claude Desktop 就能直接查询你的记账数据。

---

## 常见问题

**能用其他大模型吗？** 可以。编辑 `.env` 切换——任何 OpenAI 兼容 API 都行。

**端口被占用？**
```bash
lsof -ti:8080,8081,8082,8083,8084,5173 | xargs kill -9
```

**怎么重置数据？** `rm -rf finance-backend/data`

**Swagger 文档在哪？** 启动 Backend 后访问 `http://localhost:8080/swagger-ui.html`

**健康检查？**

| 服务 | 健康检查地址 |
|------|------------|
| Backend | `http://localhost:8080/actuator/health` |
| Agent (Java) | `http://localhost:8081/actuator/health` |
| MCP Server (Java) | `http://localhost:8082/actuator/health` |
| MCP Server (Python) | `http://localhost:8083/sse` |
| Agent (Python) | `http://localhost:8084/actuator/health` |

**Python 服务启动失败？** 检查 pip 依赖：
```bash
pip3 install -e finance-mcp-server-py/
pip3 install -e finance-agent-py/
```

---

## License

MIT © 2026
