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

# Check frontend dependencies
if [ ! -d "finance-frontend/node_modules" ]; then
    echo "[0/4] Installing frontend dependencies..."
    cd finance-frontend && npm install && cd ..
fi

# Start backend
echo "[1/4] Starting finance-backend (:8080)..."
cd finance-backend && ./mvnw spring-boot:run -q &
BACKEND_PID=$!
cd "$SCRIPT_DIR"
sleep 8

# Start MCP Server
echo "[2/4] Starting finance-mcp-server (:8082)..."
cd finance-mcp-server && ./mvnw spring-boot:run -q &
MCP_PID=$!
cd "$SCRIPT_DIR"
sleep 5

# Start Agent
echo "[3/4] Starting finance-agent (:8081)..."
cd finance-agent && ./mvnw spring-boot:run -q &
AGENT_PID=$!
cd "$SCRIPT_DIR"
sleep 5

# Start Frontend
echo "[4/4] Starting finance-frontend (:5173)..."
cd finance-frontend && npm run dev &
FRONTEND_PID=$!
cd "$SCRIPT_DIR"

echo ""
echo "============================================"
echo "  All services started"
echo "============================================"
echo "  Frontend:    http://localhost:5173"
echo "  Backend:     http://localhost:8080"
echo "  Agent:       http://localhost:8081"
echo "  MCP Server:  http://localhost:8082"
echo ""
echo "  Press Ctrl+C to stop all services"
echo "============================================"

trap "kill $BACKEND_PID $MCP_PID $AGENT_PID $FRONTEND_PID 2>/dev/null; exit 0" SIGINT SIGTERM
wait
