#!/bin/bash
# start-all.sh — One-click startup for all 4 services
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================"
echo "  Personal Finance Agent"
echo "============================================"
echo ""

# Check config
if [ ! -f ".env" ]; then
    echo "ERROR: .env not found!"
    echo "  Run: cp .env.example .env"
    echo "  Then edit .env with your LLM API key."
    exit 1
fi

# Check JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/usr/local/opt/openjdk@17" ]; then
        export JAVA_HOME="/usr/local/opt/openjdk@17"
    elif [ -d "$HOME/.sdkman/candidates/java/17" ]; then
        export JAVA_HOME="$HOME/.sdkman/candidates/java/17"
    fi
    echo "JAVA_HOME=$JAVA_HOME"
fi

# 等待服务就绪（替换 sleep）
wait_for_service() {
  local name=$1
  local url=$2
  local max_attempts=${3:-30}
  local attempt=0
  echo "等待 ${name} 就绪..."
  while [ $attempt -lt $max_attempts ]; do
    if curl -sf "${url}" > /dev/null 2>&1; then
      echo "✅ ${name} 已就绪"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  echo "❌ ${name} 启动超时"
  return 1
}

# Check frontend dependencies — npm ci 确保完整安装（含 .bin 软链接）
if [ ! -d "finance-frontend/node_modules" ] || [ ! -f "finance-frontend/node_modules/.bin/vite" ]; then
    echo "[0/4] Installing frontend dependencies..."
    cd finance-frontend
    if [ -f "package-lock.json" ]; then
        npm ci 2>/dev/null || npm install
    else
        npm install
    fi
    cd ..
fi

# Start backend
echo "[1/4] Starting finance-backend (:8080)..."
cd finance-backend && ./mvnw spring-boot:run -q &
BACKEND_PID=$!
cd "$SCRIPT_DIR"
wait_for_service "Backend" "http://localhost:8080/actuator/health"

# 读取 config.yaml 中的 ai.agent 和 ai.mcp
AGENT_TYPE=$(python3 -c "
import yaml, sys
try:
    with open('config.yaml') as f:
        config = yaml.safe_load(f)
    print(config.get('ai', {}).get('agent', 'java'))
except Exception:
    print('java')
" 2>/dev/null)

MCP_TYPE=$(python3 -c "
import yaml, sys
try:
    with open('config.yaml') as f:
        config = yaml.safe_load(f)
    print(config.get('ai', {}).get('mcp', 'java'))
except Exception:
    print('java')
" 2>/dev/null)

echo "AI 配置: Agent=$AGENT_TYPE, MCP=$MCP_TYPE"

# 启动 MCP Server
if [ "$MCP_TYPE" = "python" ]; then
    echo "[2/4] Starting finance-mcp-server-py (:8083)..."
    cd finance-mcp-server-py && python3 server.py &
    MCP_PID=$!
    cd "$SCRIPT_DIR"
    wait_for_service "MCP Server (Python)" "http://localhost:8083/actuator/health" || true
else
    echo "[2/4] Starting finance-mcp-server (:8082)..."
    cd finance-mcp-server && ./mvnw spring-boot:run -q &
    MCP_PID=$!
    cd "$SCRIPT_DIR"
    wait_for_service "MCP Server (Java)" "http://localhost:8082/actuator/health"
fi

# 启动 Agent
if [ "$AGENT_TYPE" = "python" ]; then
    echo "[3/4] Starting finance-agent-py (:8084)..."
    # 确保 Python 依赖已安装
    if [ ! -d "finance-agent-py/.venv" ] && [ ! -f "finance-agent-py/.deps_installed" ]; then
        pip3 install -e finance-agent-py/ 2>/dev/null && touch finance-agent-py/.deps_installed
    fi
    cd finance-agent-py && python3 main.py &
    AGENT_PID=$!
    cd "$SCRIPT_DIR"
    wait_for_service "Agent (Python)" "http://localhost:8084/actuator/health"
else
    echo "[3/4] Starting finance-agent (:8081)..."
    cd finance-agent && ./mvnw spring-boot:run -q &
    AGENT_PID=$!
    cd "$SCRIPT_DIR"
    wait_for_service "Agent (Java)" "http://localhost:8081/actuator/health"
fi

# Start Frontend
echo "[4/4] Starting finance-frontend (:5173)..."
cd finance-frontend && npm run dev &
FRONTEND_PID=$!
cd "$SCRIPT_DIR"

if [ "$AGENT_TYPE" = "python" ]; then
    AGENT_PORT=8084
    AGENT_LABEL="Agent (Python)"
else
    AGENT_PORT=8081
    AGENT_LABEL="Agent (Java)"
fi

if [ "$MCP_TYPE" = "python" ]; then
    MCP_PORT=8083
    MCP_LABEL="MCP Server (Python)"
else
    MCP_PORT=8082
    MCP_LABEL="MCP Server (Java)"
fi

echo ""
echo "============================================"
echo "  All services started"
echo "============================================"
echo "  Frontend:       http://localhost:5173"
echo "  Backend:        http://localhost:8080"
echo "  ${AGENT_LABEL}:  http://localhost:${AGENT_PORT}"
echo "  ${MCP_LABEL}:    http://localhost:${MCP_PORT}"
echo ""
echo "  Press Ctrl+C to stop all services"
echo "============================================"

trap "kill $BACKEND_PID $MCP_PID $AGENT_PID $FRONTEND_PID 2>/dev/null; exit 0" SIGINT SIGTERM
wait
