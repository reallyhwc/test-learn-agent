# Python LangChain Agent + MCP 设计方案

## 目标

在当前 Java/Spring AI 实现的 Agent + MCP 基础上，使用 Python LangChain 构建一套功能对等的 Agent 和 MCP Server，与 Java 版并存，通过配置文件和前端 UI 切换服务提供者。

## 当前架构

```
前端 (:5173) → Agent (:8081) → MCP Server (:8082) → Backend (:8080)
     Vue 3      Spring AI          Spring AI MCP      Spring Boot
```

## 目标架构

```
                        ┌─ Agent-Java (:8081) ─ MCP-Java (:8082) ─┐
前端 (:5173) ─ config ──┤                                         ├── Backend (:8080)
                        └─ Agent-Python (:8084) ─ MCP-Python (:8083) ┘
```

## 端口规划

| 服务 | Java 版 | Python 版 |
|------|---------|-----------|
| Backend | 8080 | (共用) |
| MCP Server | 8082 | 8083 |
| Agent | 8081 | 8084 |

## 目录布局

```
test-learn-agent/
├── config.yaml                  # 新增：AI 服务提供者配置
├── start-all.sh                 # 修改：按 config 启动对应服务
│
├── finance-backend/             # 不变
├── finance-mcp-server/          # 现有 Java MCP Server
├── finance-agent/               # 现有 Java Agent
├── finance-frontend/            # 修改：增加 Agent/MCP 切换 UI
│
├── finance-mcp-server-py/       # 新增：Python MCP Server
│   ├── pyproject.toml
│   ├── server.py                # FastMCP 入口 + 5 个 tool 注册
│   └── ...
│
├── finance-agent-py/            # 新增：Python Agent
│   ├── pyproject.toml
│   ├── agent.py                 # LangChain Agent 定义
│   ├── chat_server.py           # FastAPI + SSE 流式接口
│   ├── memory_manager.py        # per-user JSON 文件记忆
│   ├── system_prompt.py         # System prompt 模板
│   └── ...
│
└── .env                         # 不变：LLM API key 等
```

## 配置文件 `config.yaml`

```yaml
ai:
  agent: java          # java | python
  mcp: java            # java | python

services:
  backend:
    port: 8080
  mcp-java:
    port: 8082
  mcp-python:
    port: 8083
  agent-java:
    port: 8081
  agent-python:
    port: 8084

llm:
  api_key: ${LLM_API_KEY}
  base_url: https://api.deepseek.com
  model: deepseek-chat
```

- `start-all.sh` 读取 `ai.agent` / `ai.mcp` 决定启动哪些服务
- Agent 暴露 `/api/config` 端点，前端获取当前配置和可用选项
- 前端可运行时切换（改前端 state + 重启服务生效）

## Python MCP Server (`finance-mcp-server-py`)

### 技术栈

- Python 3.10+
- `mcp[cli]` (FastMCP) — MCP Server 框架
- `httpx` + `asyncio` — 异步调用 Backend REST API

### 5 个 Tool (与 Java 版完全对等)

Tool 名称和签名保持一致：

| Tool | 参数 | Backend API |
|------|------|-------------|
| `query_balance` | user_id, account_id | GET /api/accounts/{id}/balance |
| `list_transactions` | user_id, filters | GET /api/transactions?userId=&... |
| `summarize_transactions` | user_id, filters | GET /api/transactions/summary?userId=&... |
| `add_transaction` | user_id, account_id, type, amount, category, sub_category, note | POST /api/transactions |
| `list_accounts` | user_id | GET /api/accounts?userId= |

### 关键实现

```python
from mcp.server.fastmcp import FastMCP
import httpx

mcp = FastMCP("finance-mcp-server-py")

@mcp.tool()
async def list_accounts(user_id: str) -> list[dict]:
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"http://localhost:8080/api/accounts",
            params={"userId": user_id}
        )
        return resp.json()

# ... 其余 4 个 tool

mcp.run(transport="sse", host="0.0.0.0", port=8083)
```

- `validate_user_id()` — 同 Java 版的安全校验
- 参数校验错误返回友好中文提示，不抛异常
- 走 SSE 传输协议

## Python Agent (`finance-agent-py`)

### 技术栈

- Python 3.10+
- `langchain` + `langchain-deepseek` — LangChain Agent
- `langchain-mcp-adapters` — LangChain 适配 MCP 客户端
- `fastapi` + `sse-starlette` — HTTP + SSE 流式接口
- `uvicorn` — ASGI 服务器
- `python-dotenv` — 从 `.env` 加载 LLM 配置

### 核心组件

```
chat_server.py      FastAPI app, /api/chat (同步) + /api/chat/stream (SSE)
agent.py            LangChain create_agent + MCP tool 绑定 + system prompt
memory_manager.py   per-user JSON 文件记忆 (与 Java 版格式兼容)
system_prompt.py    System prompt 模板 (与 Java 版语义一致)
```

### Agent 流程

```
POST /api/chat/stream → FastAPI → LangChain Agent (astream_events)
    → MCP Client (SSE → localhost:8083/sse) → Python MCP Server → Backend
    → 逐 token SSE 返回到前端
```

### ChatServer 端点

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/chat` | POST | 同步对话，返回完整 JSON |
| `/api/chat/stream` | POST | SSE 流式对话 |
| `/api/config` | GET | 返回当前 agent/mcp 类型和可用选项 |
| `/api/memory` | DELETE | 清除用户记忆 |

### 记忆管理

- 格式：per-user JSON 文件 (`data/memory/{userId}.json`)
- 文件结构与 Java 版 `JsonFileChatMemory` 兼容
- 上限 20 条，滚动淘汰

### System Prompt

- 与 Java 版 ChatController.buildSystemPrompt() 语义完全一致
- 相同的安全规则、决策规则、工具参数规则、分类体系
- 相同的输出风格要求

## 前端修改

### Vite Proxy

```js
proxy: {
  '/api/config': {
    target: 'http://localhost:8081',  // 默认 Java Agent
    changeOrigin: true
  },
  '/api/chat': {
    target: 'http://localhost:8081',
    changeOrigin: true
  },
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true
  }
}
```

- 前端从 `/api/config` 获取当前 Agent 端口，动态设置 `/api/chat` 的 proxy target
- 切换 Agent 时调用 `/api/config?switch=python` 触发后端切换（或重启服务）

### UI 切换控件

- AppHeader 增加 `el-select` 下拉：`当前 Agent: Java | Python`
- 切换后提示用户需重启服务生效
- `config.yaml` 控制默认值

## 启动脚本 `start-all.sh` 修改

1. 用 `yq` 或 python 解析 `config.yaml`
2. 读取 `ai.agent` 和 `ai.mcp`
3. 按配置启动对应服务：
   - `ai.agent=java` → 启动 `finance-agent` (8081)
   - `ai.agent=python` → 启动 `finance-agent-py` (8084)
   - `ai.mcp=java` → 启动 `finance-mcp-server` (8082)
   - `ai.mcp=python` → 启动 `finance-mcp-server-py` (8083)
4. Backend 始终启动
5. 前端始终启动，通过 `/api/config` 获取 Agent 端口

## 兼容性保证

- Java 和 Python MCP Server 暴露**完全相同的 5 个 tool name 和签名**
- Java 和 Python Agent 使用**相同的 system prompt 模板**
- 记忆文件格式兼容，切换 Agent 后对话历史不丢失
- LLM 配置统一从 `.env` 读取

## 技术依赖

### Python MCP Server
```
mcp[cli]>=1.0.0
httpx>=0.27.0
```

### Python Agent
```
langchain>=0.3.0
langchain-deepseek>=0.1.0
langchain-mcp-adapters>=0.1.0
fastapi>=0.115.0
sse-starlette>=2.0.0
uvicorn[standard]>=0.32.0
python-dotenv>=1.0.0
pyyaml>=6.0.0
```

## 测试策略

- MCP Server：pytest 集成测试，启动真实 Backend，验证 5 个 tool 的请求/响应
- Agent：pytest + FastAPI TestClient，mock MCP Server，验证对话流程和 SSE 流
- 与 Java 版一致的测试覆盖范围
