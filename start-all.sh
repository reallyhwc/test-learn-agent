#!/bin/bash
# start-all.sh — 一键启动所有服务
set -e

echo "=== Personal Finance Agent ==="
echo ""

# Check JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME="/usr/local/opt/openjdk@17"
    echo "JAVA_HOME not set, using $JAVA_HOME"
fi

# Start backend
echo "[1/4] Starting finance-backend (:8080)..."
cd finance-backend
./mvnw spring-boot:run -q &
BACKEND_PID=$!
cd ..
sleep 8

# Start MCP Server
echo "[2/4] Starting finance-mcp-server (:8082)..."
cd finance-mcp-server
./mvnw spring-boot:run -q &
MCP_PID=$!
cd ..
sleep 5

# Start Agent
echo "[3/4] Starting finance-agent (:8081)..."
cd finance-agent
./mvnw spring-boot:run -q &
AGENT_PID=$!
cd ..
sleep 5

# Start Frontend
echo "[4/4] Starting finance-frontend (:5173)..."
cd finance-frontend
npm run dev &
FRONTEND_PID=$!
cd ..

echo ""
echo "=== All services started ==="
echo "Frontend:    http://localhost:5173"
echo "Backend:     http://localhost:8080"
echo "Agent:       http://localhost:8081"
echo "MCP Server:  http://localhost:8082"
echo ""
echo "Press Ctrl+C to stop all services"

trap "kill $BACKEND_PID $MCP_PID $AGENT_PID $FRONTEND_PID 2>/dev/null; exit 0" SIGINT SIGTERM
wait
