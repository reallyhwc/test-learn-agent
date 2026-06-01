# 02 — MCP 工具超时 / 断流

## 症状

- Agent 日志出现：
  - `tool call timeout`
  - `circuit breaker OPEN` / `熔断器开启`
  - `Connection refused`（连 MCP Server）
  - `read timeout` 在 RestClient 调用时
- 前端 chat 卡住、或 Agent 回复"我无法获取你的账户信息"

## 常见原因

### 1. Backend 服务未启动 (40%)

MCP 工具会调用 Backend 的 REST API。Backend 没起，工具必挂。

```bash
# 验证
curl -sf http://localhost:8080/actuator/health | jq
# 期望 {"status":"UP"}
lsof -i :8080
```

**修复**：
```bash
cd finance-backend && ./mvnw spring-boot:run
# 或重启全部
./start-all.sh
```

### 2. MCP Server 与 Backend 不在同一台机器 / 端口被改 (20%)

MCP Server 调 Backend 用的是 `application.yml` 中的 `backend.base-url`：

```bash
grep -A2 "backend:" finance-mcp-server/src/main/resources/application.yml
```

如果不是 `http://localhost:8080`，确认 Backend 实际监听端口。

### 3. 熔断器（SimpleCircuitBreaker）触发 (15%)

Agent 端的熔断器在连续失败后会 OPEN 一段时间。

```bash
# 查看熔断器状态
grep "circuit" logs/agent-java.log | tail -20
```

**修复路径**：
- 找到失败根因（前面两条），修复后等待熔断器半开窗口（默认 30s 后自动恢复）
- 或重启 Agent：`./start-all.sh restart-java-agent`

### 4. Spring AI MCP SSE 连接断开 (15%)

Agent 通过 SSE 长连接连 MCP Server。Server 重启或网络抖动会断流。

```bash
# 观察 MCP Server 日志
tail -f logs/mcp-java.log | grep -E "SSE|connection|disconnect"
```

**修复**：重启 Agent 触发重连。

### 5. 中文参数 URI 双重编码 (10%)

历史坑：`@McpToolParam` 传中文（如 `category=餐饮`）时 `RestClient` 默认会再编码一次，Backend 收到的是乱码。

**已修复方式**（保留参考）：
- MCP Server 端用 `UriComponentsBuilder.build().toUri()` 显式构建 URI（见 `FinanceTools.java`）

如果再次出现：检查新加的工具是不是绕过了 `UriComponentsBuilder`。

## 排查步骤

```bash
# 1. 链路从下到上逐层验证
curl -sf http://localhost:8080/actuator/health    # Backend
curl -sf http://localhost:8082/actuator/health    # MCP Server (Java)
curl -sf http://localhost:8081/actuator/health    # Agent (Java)

# 2. 看最近 200 行 Agent 日志
tail -200 logs/agent-java.log

# 3. 直接调 MCP Server 测工具能否用（绕过 Agent）
curl -sN http://localhost:8082/sse/messages \
  -X POST -H "Content-Type: application/json" \
  -d '{"method":"tools/list","params":{},"id":1,"jsonrpc":"2.0"}'
```

## 关联代码

- `finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java` — MCP 工具实现
- `finance-mcp-server/src/main/java/com/example/mcp/config/RestClientConfig.java` — 调 Backend 的 client 配置
- `finance-agent/src/main/java/com/example/agent/resilience/SimpleCircuitBreaker*` — 熔断器
- `finance-agent/src/main/resources/application.yml` — MCP SSE URL 配置

## 预防

- 启动用 `./start-all.sh`，会自动 health check 等待每个服务就绪
- `./start-all.sh` 的 `wait_for_service` 函数会因依赖未就绪而失败退出，避免"服务都启动了但其实没就绪"的状态
