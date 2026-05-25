# CLAUDE.md — Personal Finance Agent

A 4-service demo project showcasing AI Agent + MCP (Model Context Protocol) on the Java/Spring ecosystem.

## Language

**所有内容使用中文** — 代码注释、提交信息、文档、与用户的所有交互均使用中文。技术术语（如类名、方法名、注解名）保持英文。

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

## Testing Strategy

- POST创建类端点使用`@ResponseStatus(HttpStatus.CREATED)`，测试期望`status().isCreated()`（201）
- GET查询类端点期望`status().isOk()`（200）
- 测试类命名：`{被测类}Test.java`
- 测试数据目录：`src/test/resources/test-data/`
- Transaction构造推荐使用setter或Builder，避免@AllArgsConstructor直接构造（字段顺序变化会导致所有调用方编译失败）
- 测试方法命名：`should{预期行为}`，如`shouldCreateTransaction`

## AI Coding Constraints

- 修改Model字段时，必须同步更新：CSV Schema、种子数据、测试用例、MCP工具描述、Agent Prompt、前端组件
- 改方法签名时，先用`file_grep`找到所有调用方再动手
- 修改文件前必须先`read_file`了解现有内容，禁止盲改
- 多模块共享类型（Model、DTO、枚举）变更时，必须在同一个commit内同步所有模块

## Module Dependency Order

修改顺序必须遵循：
```
Model → Repository → Service → Controller → MCP Server → Agent → Frontend
```
上游变更必须先完成才能正确传播到下游。

## Known Tech Debt

- `AccountControllerTest`: 期望200但Controller返回201（需修复测试断言）
- `GlobalExceptionHandlerTest`: 3个用例期望400但实际返回500（需完善异常处理覆盖）
- 系统默认Java是1.8，编译需要`export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`
- Transaction使用@AllArgsConstructor，新增字段会破坏所有现有构造调用

## Anti-Patterns（禁止事项）

### 禁止盲改
- 修改文件前必须先`read_file`了解现有内容
- 改方法签名前必须`file_grep`找到所有调用方
- 改Model字段时禁止只改Model不改Schema

### 禁止假设
- 不要假设test的期望状态码，先看Controller注解
- 不要假设构造器参数顺序，先看字段声明顺序
- 不要假设旧CSV文件有新列，必须做兼容检测

### 多模块同步规则
修改任何共享类型时，必须在同一个commit内同步所有模块。禁止"先改backend提交，再改mcp-server提交"——中间状态会编译失败。
