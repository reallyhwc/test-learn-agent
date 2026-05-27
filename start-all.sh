#!/bin/bash
# start-all.sh — One-click startup for all 4 services
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================"
echo "  Personal Finance Agent"
echo "============================================"
echo ""

# 解析命令行参数
DUAL_MODE=false
ACTION="start"  # start | restart-java-agent

for arg in "$@"; do
  case $arg in
    --all|--dual)
      DUAL_MODE=true
      ;;
    restart-java-agent)
      ACTION="restart-java-agent"
      ;;
  esac
done

# 处理 restart-java-agent 子命令
if [ "$ACTION" = "restart-java-agent" ]; then
    echo "重启 Java Agent..."
    # 加载 .env 环境变量（LLM_API_KEY、LLM_BASE_URL、LLM_MODEL 等）
    if [ -f "$SCRIPT_DIR/.env" ]; then
        set -a
        source "$SCRIPT_DIR/.env"
        set +a
    fi
    # 停掉旧进程
    AGENT_PID=$(lsof -i :8081 -t 2>/dev/null)
    if [ -n "$AGENT_PID" ]; then
        kill "$AGENT_PID" 2>/dev/null
        sleep 2
    fi
    # 确保 MCP_SSE_URL 已导出给子进程（application.yml 中 ${MCP_SSE_URL:...} 读取此变量）
    export MCP_SSE_URL="${MCP_SSE_URL:-http://localhost:8082}"
    echo "MCP_SSE_URL=$MCP_SSE_URL"

    cd "$SCRIPT_DIR/finance-agent"
    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/opt/homebrew/opt/openjdk@17" ]; then
            export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
        elif [ -d "/usr/local/opt/openjdk@17" ]; then
            export JAVA_HOME="/usr/local/opt/openjdk@17"
        fi
    fi
    export PATH="$JAVA_HOME/bin:$PATH"
    MCP_SSE_URL="$MCP_SSE_URL" nohup ./mvnw spring-boot:run -q > "$SCRIPT_DIR/logs/agent-java.log" 2>&1 &
    echo "Java Agent 正在重启 (PID=$!)，MCP_SSE_URL=$MCP_SSE_URL，日志: logs/agent-java.log"
    exit 0
fi

# Check config
if [ ! -f ".env" ]; then
    echo "ERROR: .env not found!"
    echo "  Run: cp .env.example .env"
    echo "  Then edit .env with your LLM API key."
    exit 1
fi

# Check JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/opt/homebrew/opt/openjdk@17" ]; then
        export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
    elif [ -d "/usr/local/opt/openjdk@17" ]; then
        export JAVA_HOME="/usr/local/opt/openjdk@17"
    elif [ -d "$HOME/.sdkman/candidates/java/17" ]; then
        export JAVA_HOME="$HOME/.sdkman/candidates/java/17"
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"

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

# 检查 config.yaml 中的 mode 字段，如果是 dual 则自动开启双栈
CONFIG_MODE=$(python3 -c "
import yaml
try:
    with open('config.yaml') as f:
        config = yaml.safe_load(f)
    print(config.get('ai', {}).get('mode', 'single'))
except Exception:
    print('single')
" 2>/dev/null)

if [ "$CONFIG_MODE" = "dual" ]; then
    DUAL_MODE=true
fi

if [ "$DUAL_MODE" = "true" ]; then
    echo "AI 配置: 双栈模式 (Java + Python 同时启动)"
else
    echo "AI 配置: Agent=$AGENT_TYPE, MCP=$MCP_TYPE"
fi

# 确保 Python 依赖安装的辅助函数
ensure_python_deps() {
    if [ ! -f "finance-mcp-server-py/.deps_installed" ]; then
        pip3 install -e finance-mcp-server-py/ 2>/dev/null && touch finance-mcp-server-py/.deps_installed
    fi
    if [ ! -f "finance-agent-py/.deps_installed" ]; then
        pip3 install -e finance-agent-py/ 2>/dev/null && touch finance-agent-py/.deps_installed
    fi
}

ALL_PIDS=""

# 启动 MCP Server
if [ "$DUAL_MODE" = "true" ]; then
    echo "[2a/6] Starting finance-mcp-server (Java :8082)..."
    cd finance-mcp-server && ./mvnw spring-boot:run -q &
    MCP_JAVA_PID=$!
    ALL_PIDS="$ALL_PIDS $MCP_JAVA_PID"
    cd "$SCRIPT_DIR"

    ensure_python_deps
    echo "[2b/6] Starting finance-mcp-server-py (Python :8083)..."
    cd finance-mcp-server-py && python3 server.py &
    MCP_PY_PID=$!
    ALL_PIDS="$ALL_PIDS $MCP_PY_PID"
    cd "$SCRIPT_DIR"

    wait_for_service "MCP Server (Java)" "http://localhost:8082/actuator/health"
    wait_for_service "MCP Server (Python)" "http://localhost:8083/sse" || true
elif [ "$MCP_TYPE" = "python" ]; then
    echo "[2/4] Starting finance-mcp-server-py (:8083)..."
    if [ ! -f "finance-mcp-server-py/.deps_installed" ]; then
        pip3 install -e finance-mcp-server-py/ 2>/dev/null && touch finance-mcp-server-py/.deps_installed
    fi
    cd finance-mcp-server-py && python3 server.py &
    MCP_PID=$!
    ALL_PIDS="$ALL_PIDS $MCP_PID"
    cd "$SCRIPT_DIR"
    wait_for_service "MCP Server (Python)" "http://localhost:8083/sse" || true
else
    echo "[2/4] Starting finance-mcp-server (:8082)..."
    cd finance-mcp-server && ./mvnw spring-boot:run -q &
    MCP_PID=$!
    ALL_PIDS="$ALL_PIDS $MCP_PID"
    cd "$SCRIPT_DIR"
    wait_for_service "MCP Server (Java)" "http://localhost:8082/actuator/health"
fi

# 启动 Agent
if [ "$DUAL_MODE" = "true" ]; then
    echo "[3a/6] Starting finance-agent (Java :8081)..."
    cd finance-agent && ./mvnw spring-boot:run -q &
    AGENT_JAVA_PID=$!
    ALL_PIDS="$ALL_PIDS $AGENT_JAVA_PID"
    cd "$SCRIPT_DIR"

    echo "[3b/6] Starting finance-agent-py (Python :8084)..."
    cd finance-agent-py
    if [ -d ".venv" ]; then
        source .venv/bin/activate
    fi
    python3 main.py &
    AGENT_PY_PID=$!
    ALL_PIDS="$ALL_PIDS $AGENT_PY_PID"
    cd "$SCRIPT_DIR"

    wait_for_service "Agent (Java)" "http://localhost:8081/actuator/health"
    wait_for_service "Agent (Python)" "http://localhost:8084/actuator/health"
elif [ "$AGENT_TYPE" = "python" ]; then
    echo "[3/4] Starting finance-agent-py (:8084)..."
    cd finance-agent-py
    if [ -d ".venv" ]; then
        source .venv/bin/activate
    elif [ ! -f ".deps_installed" ]; then
        pip3 install -r requirements.txt 2>/dev/null && touch .deps_installed
    fi
    python3 main.py &
    AGENT_PID=$!
    ALL_PIDS="$ALL_PIDS $AGENT_PID"
    cd "$SCRIPT_DIR"
    wait_for_service "Agent (Python)" "http://localhost:8084/actuator/health"
else
    echo "[3/4] Starting finance-agent (:8081)..."
    cd finance-agent && ./mvnw spring-boot:run -q &
    AGENT_PID=$!
    ALL_PIDS="$ALL_PIDS $AGENT_PID"
    cd "$SCRIPT_DIR"
    wait_for_service "Agent (Java)" "http://localhost:8081/actuator/health"
fi

# Start Frontend
STEP_LABEL="4/4"
if [ "$DUAL_MODE" = "true" ]; then STEP_LABEL="4/6"; fi
echo "[$STEP_LABEL] Starting finance-frontend (:5173)..."
cd finance-frontend && npm run dev &
FRONTEND_PID=$!
ALL_PIDS="$ALL_PIDS $FRONTEND_PID"
cd "$SCRIPT_DIR"

echo ""
echo "============================================"
echo "  All services started"
echo "============================================"
echo "  Frontend:       http://localhost:5173"
echo "  Backend:        http://localhost:8080"
if [ "$DUAL_MODE" = "true" ]; then
    echo "  Agent (Java):   http://localhost:8081"
    echo "  Agent (Python): http://localhost:8084"
    echo "  MCP (Java):     http://localhost:8082"
    echo "  MCP (Python):   http://localhost:8083"
    echo ""
    echo "  Mode: DUAL (前端可动态切换 Java/Python)"
else
    if [ "$AGENT_TYPE" = "python" ]; then
        echo "  Agent (Python): http://localhost:8084"
    else
        echo "  Agent (Java):   http://localhost:8081"
    fi
    if [ "$MCP_TYPE" = "python" ]; then
        echo "  MCP (Python):   http://localhost:8083"
    else
        echo "  MCP (Java):     http://localhost:8082"
    fi
fi
echo ""
echo "  Press Ctrl+C to stop all services"
echo "============================================"

trap "kill $BACKEND_PID $ALL_PIDS 2>/dev/null; exit 0" SIGINT SIGTERM
wait
