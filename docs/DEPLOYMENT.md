# 部署指南

## 环境要求

- Java 17+
- Node.js 18+
- npm 9+

## 快速启动（开发环境）

```bash
# 1. 克隆仓库
git clone <repo-url>
cd personal-finance-agent

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env，填入 LLM API Key

# 3. 一键启动
./start-all.sh
```

## Docker 部署

```bash
# 构建并启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 停止服务
docker-compose down
```

## 服务架构

| 服务 | 端口 | 说明 |
|------|------|------|
| finance-frontend | 5173 | Vue3 前端应用 |
| finance-backend | 8080 | Spring Boot 后端 API |
| finance-agent | 8081 | AI 智能助手 |
| finance-mcp-server | 8082 | MCP 工具服务 |

## 健康检查

各服务均提供 Actuator 健康检查端点：

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
```

## API 文档

启动 Backend 后访问：http://localhost:8080/swagger-ui.html

## 监控

Prometheus 指标端点：

```bash
curl http://localhost:8080/actuator/prometheus
curl http://localhost:8081/actuator/prometheus
curl http://localhost:8082/actuator/prometheus
```

## 测试

```bash
# 运行所有测试
./run-tests.sh

# 只运行前端测试
./run-tests.sh --layer frontend

# 只运行后端测试
./run-tests.sh --layer backend

# 跳过 AI 测试
./run-tests.sh --skip-ai
```
