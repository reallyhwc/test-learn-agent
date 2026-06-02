# CLAUDE.md — Personal Finance Agent

一个展示 **AI Agent + MCP（Model Context Protocol）** 工程化最佳实践的演示项目，覆盖 Java 主栈和 Python 副栈双实现。

## Language

**所有内容使用中文** — 代码注释、提交信息、文档、与用户的所有交互均使用中文。技术术语（如类名、方法名、注解名）保持英文。

## Architecture

```
Frontend (:5173) → Agent → MCP Server → Backend (:8080)
     Vue 3        ┌──────┴──────┐    ┌────┴────┐
                  │             │    │         │
                  Java(:8081)   │    Java(:8082)│
                  Python(:8084) │    Python(:8083)
                                │              │
                       (双栈可单跑或同时跑)    │
                                               + Backend 共享 (:8080)
```

- **finance-backend** (:8080) — REST API for accounts & transactions, CSV file storage
- **finance-mcp-server** (:8082, Java) / **finance-mcp-server-py** (:8083, Python) — MCP Server，暴露 5 个工具：`query_balance`、`list_transactions`、`summarize_transactions`、`add_transaction`、`list_accounts`
- **finance-agent** (:8081, Java) / **finance-agent-py** (:8084, Python) — Spring AI ChatClient + LLM，通过 SSE 调用 MCP 工具
- **finance-frontend** (:5173) — Vue 3 + Element Plus + ECharts

> Java 是**主栈**，Python 是**副栈**。详见 [Dual-Stack Strategy](#dual-stack-strategy双栈策略) 章节。

## Prerequisites

- Java 17+ (`JAVA_HOME` set，否则 start-all.sh 会自动尝试常见路径)
- Node.js 18+
- Python 3.10+（仅当使用 Python 栈时）
- Maven wrapper included (no global Maven needed)

## Quick Start

```bash
# 1. Configure LLM
cp .env.example .env
# Edit .env with your API key, base URL, and model

# 2. Start all services (with preflight checks)
./start-all.sh

# 3. Open browser
open http://localhost:5173
```

启动失败常见原因见 [docs/troubleshooting/](docs/troubleshooting/README.md)。

## Common Commands

```bash
# Individual service start
cd finance-backend && ./mvnw spring-boot:run
cd finance-mcp-server && ./mvnw spring-boot:run
cd finance-agent && ./mvnw spring-boot:run
cd finance-frontend && npm install && npm run dev

# 仅重启 Java Agent（用于改了 prompt 后快速验证）
./start-all.sh restart-java-agent

# 双栈模式（Java + Python 同时启动）
./start-all.sh --dual

# Run tests
cd finance-backend && ./mvnw test

# 跑 Agent Eval（评估 LLM 行为质量，需 LLM_API_KEY + 启动 backend/mcp-server）
cd finance-agent && ./mvnw test -Dgroups=evals -DexcludedGroups= -Dtest=AgentEvalTest

# 跑 Python 栈 Eval（共享同一份 golden-dataset.json）
cd finance-agent-py && source .venv/bin/activate && pytest ../evals/py/ -v

# 生成可视化 HTML 报告（聚合 evals/reports/*.json）
python3 scripts/eval-report.py && open evals/reports/index.html

# 校验 CLAUDE.md 与代码一致性
bash scripts/claude-check.sh

# 生成新 MCP 工具骨架
./scripts/new-mcp-tool.sh transfer_money "转账工具描述"

# Check ports
lsof -ti:8080  # Backend
lsof -ti:8081  # Agent (Java)
lsof -ti:8082  # MCP Server (Java)
lsof -ti:8083  # MCP Server (Python)
lsof -ti:8084  # Agent (Python)
lsof -ti:5173  # Frontend
```

## Configuration

All LLM config in `.env` (gitignored, copy from `.env.example`):

```properties
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
```

Supported providers: DeepSeek, OpenAI, 通义千问, Groq, Moonshot, SiliconFlow, idealab DogFooding（base-url 不带 `/v1`）。详见 [01-llm-401-403.md](docs/troubleshooting/01-llm-401-403.md)。

## Key Design Decisions

- **No external database** — CSV 文件在 `finance-backend/data/` 用作业务数据持久化；ChatMemory 用 JsonFileChatMemory 持久化在 `finance-agent/data/memory/`
- **No auth** — Simple `userId` query param for multi-tenant demo
- **MCP over SSE** — SYNC client type, MCP protocol version 2024-11-05
- **Streaming** — SSE-based token-by-token output via `StreamingResponseBody`
- **Per-user memory** — `JsonFileChatMemory` persists conversation history per userId
- **三层 Guardrails** — Input（PromptInjection 检测）、ToolCall（次数/参数白名单）、Output（敏感词/格式）
- **熔断器 + 超时** — `SimpleCircuitBreaker` 在连续 MCP 调用失败时自动 OPEN

## Code Patterns

- MCP tools use `@McpTool` and `@McpToolParam` annotations
- Chinese category names MUST use `UriComponentsBuilder.build().toUri()` to avoid double-encoding
- Frontend SSE parsing: `line.startsWith('data:')`, then character-by-character rendering with 20ms delay
- Vue 3 reactivity: always access through reactive array index (`messages.value[idx].text`), never raw object references

## AI Coding Harness 入口

本项目对 AI 编程工具有完整的规范约束体系：

- **本文件（CLAUDE.md）** — 项目宪法（最高优先级）
- **[`.aone_copilot/`](./.aone_copilot/README.md)** — 详细的 rules / skills / plans 档案
  - `skills/add-mcp-tool/` — 新增 MCP 工具的 SOP
  - `skills/add-model-field/` — 新增 Model 字段的 SOP
  - `skills/change-prompt/` — 修改 System Prompt 的 SOP（高风险操作必读）
  - `skills/csv-migration/` — CSV Schema 升级
- **[`docs/roadmap/`](./docs/roadmap/README.md)** — 5 篇技术演进方向（Guardrails / Evals / HITL / Prompt 管理 / Multi-Agent）
- **[`docs/troubleshooting/`](./docs/troubleshooting/README.md)** — 失败模式手册
- **[`evals/`](./evals/README.md)** — Agent 输出质量评估（Golden Dataset + Eval Runner）。改 Prompt 后必跑

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

## Dual-Stack Strategy（双栈策略）

项目并行维护 Java 和 Python 两套 Agent + MCP Server 实现，目的是对比同一架构在两个生态下的工程实践差异。

### 角色定位

- **主栈：Java**（Spring AI / Spring AI MCP）—— 生产参考实现，新功能优先在这里落地
- **副栈：Python**（FastAPI / langchain-mcp-adapters）—— 教学/对比实现，验证非 Spring 生态的可行性

### 必须双栈同步的改动

下列改动要求**同一 commit 内同时改两栈**：

- MCP 工具新增 / 删除 / 签名（参数、返回类型）变更
- Guardrail 类型新增（如 InputGuardrail、ToolCallGuardrail）
- System Prompt 中**关键决策规则**改动（哪些工具该被调用、什么时候拒答）

### 允许漂移的改动

下列方面可以独立演进：

- 内部实现细节（缓存策略、日志格式、Metrics 字段名、连接池配置）
- 测试用例（两栈测试框架不同：JUnit vs pytest）
- Prompt 中的措辞、示例话术、长度（核心规则一致即可）

### 临时单栈改动

新功能允许"先 Java 再 Python"的渐进推进，但：

- commit message 必须标注 `[java-only]` 或 `[py-only]`
- 在 `.aone_copilot/plans/doing/` 立项追踪，下个迭代必须补齐
- 标 `[java-only]` 超过 30 天没补齐 → 月度 review 时回写状态

## AI Coding Constraints

- 修改Model字段时，必须同步更新：CSV Schema、种子数据、测试用例、MCP工具描述、Agent Prompt、前端组件
- 改方法签名时，先用`file_grep`找到所有调用方再动手
- 修改文件前必须先`read_file`了解现有内容，禁止盲改
- 多模块共享类型（Model、DTO、枚举）变更时，必须在同一个commit内同步所有模块
- 修改 System Prompt 必须遵循 [`.aone_copilot/skills/change-prompt/SKILL.md`](./.aone_copilot/skills/change-prompt/SKILL.md)

## Module Dependency Order

修改顺序必须遵循：
```
Model → Repository → Service → Controller → MCP Server → Agent → Frontend
```
上游变更必须先完成才能正确传播到下游。

## Known Tech Debt

- `AccountControllerTest`: shouldCreateAccount/shouldCreateAccountWithUserId 期望 200，但 Controller 用了 `@ResponseStatus(HttpStatus.CREATED)` → 应该期望 201（需修复测试断言）
- `GlobalExceptionHandlerTest`: 历史曾有 3 个用例期望 400 但实际返回 500，目前已全部改为 `isBadRequest()`，需运行测试验证是否仍存在断言/状态码不匹配
- 系统默认 Java 是 1.8，编译需要：`export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`（或 start-all.sh 会自动探测 Homebrew 路径）
- `Transaction` 使用 `@AllArgsConstructor`，新增字段会破坏所有现有构造调用 → 推荐用 setter 或 Builder

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

### 双栈漂移
对照上面 [Dual-Stack Strategy](#dual-stack-strategy双栈策略) 章节判断当前改动是"必须同步"还是"允许漂移"。**默认假设需要同步**，例外情况要在 commit message 明确标注。
