#!/bin/bash
# ============================================================================
# restart-all.sh — 一键重启全部服务（杀进程 → 启动 → 健康检查 → 结构化结果）
#
# 用法:
#   ./scripts/restart-all.sh              # 重启全部 4 个服务
#   ./scripts/restart-all.sh --json       # 输出 JSON 格式结果（供 Agent 解析）
#
# 输出:
#   - stdout: 结构化 JSON（成功/失败、耗时、每个服务的状态、失败原因）
#   - 日志文件: logs/restart-YYYY-MM-DD-HHmmss.log
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------
# 参数解析
# ------------------------------------------------------------------
JSON_OUTPUT=false
for arg in "$@"; do
    case $arg in
        --json) JSON_OUTPUT=true ;;
    esac
done

# ------------------------------------------------------------------
# 初始化
# ------------------------------------------------------------------
TIMESTAMP=$(date +%Y-%m-%d-%H%M%S)
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/restart-$TIMESTAMP.log"
START_EPOCH=$(date +%s)  # 秒级时间戳（macOS 兼容，不用 %N）

# 所有输出同时写入日志文件
exec > >(tee -a "$LOG_FILE") 2>&1

# ------------------------------------------------------------------
# JAVA_HOME 探测
# ------------------------------------------------------------------
detect_java_home() {
    if [ -n "$JAVA_HOME" ] && [ -f "$JAVA_HOME/bin/java" ]; then
        return 0
    fi
    for candidate in \
        "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
        "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
        "/opt/homebrew/opt/openjdk@17" \
        "/usr/local/opt/openjdk@17" \
        "$HOME/.sdkman/candidates/java/17"; do
        if [ -f "$candidate/bin/java" ]; then
            export JAVA_HOME="$candidate"
            return 0
        fi
    done
    # 尝试 java_home 命令
    if command -v /usr/libexec/java_home &>/dev/null; then
        local jh
        jh=$(/usr/libexec/java_home -v 17 2>/dev/null) || true
        if [ -n "$jh" ] && [ -f "$jh/bin/java" ]; then
            export JAVA_HOME="$jh"
            return 0
        fi
    fi
    return 1
}

# ------------------------------------------------------------------
# 工具函数
# ------------------------------------------------------------------
# 从启动到现在的耗时秒数
elapsed_s() {
    echo $(($(date +%s) - START_EPOCH))
}

# 杀端口上的进程（优雅 SIGTERM → 等 3s → SIGKILL → 等 2s 确认）
kill_port() {
    local port=$1
    local pids
    pids=$(lsof -ti:"$port" 2>/dev/null) || true
    if [ -z "$pids" ]; then
        echo "[$(elapsed_s)s] 端口 $port 空闲，无需杀进程"
        return 0
    fi
    echo "[$(elapsed_s)s] 杀掉端口 $port 上的进程: $pids"
    for pid in $pids; do
        kill "$pid" 2>/dev/null || true
    done
    sleep 3
    # 确认已死
    pids=$(lsof -ti:"$port" 2>/dev/null) || true
    if [ -n "$pids" ]; then
        echo "[$(elapsed_s)s] 进程未响应 SIGTERM，强制 SIGKILL: $pids"
        for pid in $pids; do
            kill -9 "$pid" 2>/dev/null || true
        done
        sleep 2
    fi
    # 再次确认
    pids=$(lsof -ti:"$port" 2>/dev/null) || true
    if [ -n "$pids" ]; then
        echo "[$(elapsed_s)s] ⚠ 端口 $port 仍有进程残留: $pids"
        return 1
    fi
    echo "[$(elapsed_s)s] 端口 $port 已释放"
    return 0
}

# 等待 HTTP 服务就绪
# 参数: name url [maxWaitSec=60]
wait_for_http() {
    local name=$1
    local url=$2
    local max_wait=${3:-60}
    local start=$(date +%s)
    local deadline=$((start + max_wait))

    while true; do
        local http_code
        http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "$url" 2>/dev/null) || true
        if [ "$http_code" = "200" ] || [ "$http_code" = "401" ] || [ "$http_code" = "404" ] || [ "$http_code" = "302" ]; then
            local elapsed=$(($(date +%s) - start))
            echo "[${elapsed}s] ✅ $name 就绪 (HTTP $http_code)"
            return 0
        fi
        local now=$(date +%s)
        if [ $now -ge $deadline ]; then
            local elapsed=$((now - start))
            echo "[${elapsed}s] ❌ $name 启动超时 (${max_wait}s)"
            return 1
        fi
        sleep 2
    done
}

# 等待 TCP 端口监听
wait_for_port() {
    local name=$1
    local port=$2
    local max_wait=${3:-60}
    local start=$(date +%s)
    local deadline=$((start + max_wait))

    while true; do
        if lsof -ti:"$port" >/dev/null 2>&1; then
            local elapsed=$(($(date +%s) - start))
            echo "[${elapsed}s] ✅ $name 端口 :$port 已监听"
            return 0
        fi
        local now=$(date +%s)
        if [ $now -ge $deadline ]; then
            local elapsed=$((now - start))
            echo "[${elapsed}s] ❌ $name 端口 :$port 未在 ${max_wait}s 内监听"
            return 1
        fi
        sleep 2
    done
}

# 检查 npm 依赖
ensure_npm_deps() {
    if [ ! -d "finance-frontend/node_modules" ] || [ ! -f "finance-frontend/node_modules/.bin/vite" ]; then
        echo "[$(elapsed_s)s] 安装前端依赖..."
        cd finance-frontend
        if [ -f "package-lock.json" ]; then
            npm ci 2>&1 | tail -3 || npm install 2>&1 | tail -3
        else
            npm install 2>&1 | tail -3
        fi
        cd "$SCRIPT_DIR"
    fi
}

# 确保 Python 依赖安装
ensure_python_deps() {
    local dir=$1
    if [ -d "$dir/.venv" ]; then
        return 0
    fi
    if [ -f "$dir/requirements.txt" ]; then
        echo "[$(elapsed_s)s] 安装 Python 依赖: $dir ..."
        cd "$dir"
        python3 -m venv .venv 2>/dev/null || true
        .venv/bin/pip install -r requirements.txt -q 2>&1 | tail -3
        cd "$SCRIPT_DIR"
    fi
}

# 收集服务状态信息
service_status() {
    local name=$1
    local port=$2
    local pid=$3
    local status=$4
    local startup_ms=$5
    local health_url=$6

    local pid_val="null"
    local health_val="null"

    if [ -n "$pid" ] && [ "$pid" != "null" ]; then
        pid_val=$pid
    fi
    if [ -n "$health_url" ]; then
        health_val="\"$health_url\""
    fi

    echo "{\"name\":\"$name\",\"port\":$port,\"pid\":$pid_val,\"status\":\"$status\",\"startupS\":$startup_ms,\"healthUrl\":$health_val}"
}

# ------------------------------------------------------------------
# 加载 .env
# ------------------------------------------------------------------
ENV_FILE="$SCRIPT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    source "$ENV_FILE"
    set +a
else
    echo "❌ .env 文件不存在，请先 cp .env.example .env 并填入配置"
    exit 1
fi

# ------------------------------------------------------------------
# Phase 0: JAVA_HOME & 环境
# ------------------------------------------------------------------
echo "============================================"
echo "  restart-all.sh — $TIMESTAMP"
echo "============================================"

if ! detect_java_home; then
    echo "❌ 未找到 JDK 17，请安装: brew install openjdk@17"
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
echo "[$(elapsed_s)s] JAVA_HOME=$JAVA_HOME ($(java -version 2>&1 | head -1))"

# 检查 Python3（仅当 config.yaml 需要时尝试）
PYTHON_BIN=""
if command -v python3 &>/dev/null; then
    PYTHON_BIN="python3"
fi

# ------------------------------------------------------------------
# Phase 1: 杀死全部旧进程
# ------------------------------------------------------------------
echo ""
echo "--- Phase 1: 清理旧进程 ---"
declare -a KILL_FAILURES=()
for port in 5173 8081 8084 8082 8083 8080; do
    kill_port $port || KILL_FAILURES+=("$port")
done

if [ ${#KILL_FAILURES[@]} -gt 0 ]; then
    echo "⚠ 以下端口未能完全释放: ${KILL_FAILURES[*]}"
fi
echo "[$(elapsed_s)s] 旧进程清理完成"

# ------------------------------------------------------------------
# Phase 2: 启动服务（按依赖顺序）
# ------------------------------------------------------------------
echo ""
echo "--- Phase 2: 启动服务 ---"

SERVICES_JSON=""
FAILED_SERVICE=""
FAILED_REASON=""
OVERALL_STATUS="success"

# --- 2a. Backend :8080 ---（共享数据层，最先启动）
echo ""
echo ">> 启动 Backend (:8080)..."
BACKEND_START=$(date +%s)
cd "$SCRIPT_DIR/finance-backend"
nohup ./mvnw spring-boot:run -q > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
cd "$SCRIPT_DIR"
if wait_for_http "Backend" "http://localhost:8080/actuator/health" 90; then
    BACKEND_STARTUP=$(($(date +%s) - BACKEND_START))
    SERVICES_JSON+="$(service_status "backend" 8080 "$BACKEND_PID" "running" "$BACKEND_STARTUP" "http://localhost:8080/actuator/health"),"
else
    BACKEND_STARTUP=$(($(date +%s) - BACKEND_START))
    SERVICES_JSON+="$(service_status "backend" 8080 "$BACKEND_PID" "failed" "$BACKEND_STARTUP" "http://localhost:8080/actuator/health"),"
    FAILED_SERVICE="backend"
    FAILED_REASON="Backend :8080 启动超时或健康检查失败"
    OVERALL_STATUS="failure"
fi

# --- 2b. MCP Server Java :8082 ---
if [ "$OVERALL_STATUS" = "success" ] || [ "$FAILED_SERVICE" = "backend" ]; then
    echo ""
    echo ">> 启动 MCP Server (:8082)..."
    MCP_START=$(date +%s)
    cd "$SCRIPT_DIR/finance-mcp-server"
    nohup ./mvnw spring-boot:run -q > "$LOG_DIR/mcp-server.log" 2>&1 &
    MCP_PID=$!
    cd "$SCRIPT_DIR"
    if wait_for_http "MCP Server" "http://localhost:8082/actuator/health" 90; then
        MCP_STARTUP=$(($(date +%s) - MCP_START))
        SERVICES_JSON+="$(service_status "mcp-server" 8082 "$MCP_PID" "running" "$MCP_STARTUP" "http://localhost:8082/actuator/health"),"
    else
        MCP_STARTUP=$(($(date +%s) - MCP_START))
        SERVICES_JSON+="$(service_status "mcp-server" 8082 "$MCP_PID" "failed" "$MCP_STARTUP" "http://localhost:8082/actuator/health"),"
        [ -z "$FAILED_SERVICE" ] && FAILED_SERVICE="mcp-server" && FAILED_REASON="MCP Server :8082 启动超时或健康检查失败"
        OVERALL_STATUS="failure"
    fi
fi

# --- 2c. MCP Server Python :8083 ---
if [ -n "$PYTHON_BIN" ]; then
    echo ""
    echo ">> 启动 MCP Server Python (:8083)..."
    MCP_PY_START=$(date +%s)
    cd "$SCRIPT_DIR/finance-mcp-server-py"
    nohup "$PYTHON_BIN" server.py > "$LOG_DIR/mcp-server-py.log" 2>&1 &
    MCP_PY_PID=$!
    cd "$SCRIPT_DIR"
    if wait_for_port "MCP Server Python" 8083 60; then
        MCP_PY_STARTUP=$(($(date +%s) - MCP_PY_START))
        SERVICES_JSON+="$(service_status "mcp-server-py" 8083 "$MCP_PY_PID" "running" "$MCP_PY_STARTUP" "http://localhost:8083/sse"),"
    else
        MCP_PY_STARTUP=$(($(date +%s) - MCP_PY_START))
        SERVICES_JSON+="$(service_status "mcp-server-py" 8083 "$MCP_PY_PID" "failed" "$MCP_PY_STARTUP" "http://localhost:8083/sse"),"
        echo "⚠ MCP Server Python 启动失败（不阻塞 Java 栈）"
    fi
else
    echo ""
    echo "⚠ 未找到 python3，跳过 MCP Server Python (:8083)"
    SERVICES_JSON+="$(service_status "mcp-server-py" 8083 "null" "skipped" 0 "http://localhost:8083/sse"),"
fi

# --- 2d. Agent Java :8081 ---
if [ "$OVERALL_STATUS" = "success" ]; then
    echo ""
    echo ">> 启动 Agent Java (:8081)..."
    AGENT_START=$(date +%s)
    cd "$SCRIPT_DIR/finance-agent"
    export MCP_SSE_URL="${MCP_SSE_URL:-http://localhost:8082}"
    nohup env MCP_SSE_URL="$MCP_SSE_URL" ./mvnw spring-boot:run -q > "$LOG_DIR/agent.log" 2>&1 &
    AGENT_PID=$!
    cd "$SCRIPT_DIR"
    if wait_for_http "Agent" "http://localhost:8081/actuator/health" 120; then
        AGENT_STARTUP=$(($(date +%s) - AGENT_START))
        SERVICES_JSON+="$(service_status "agent" 8081 "$AGENT_PID" "running" "$AGENT_STARTUP" "http://localhost:8081/actuator/health"),"
    else
        AGENT_STARTUP=$(($(date +%s) - AGENT_START))
        SERVICES_JSON+="$(service_status "agent" 8081 "$AGENT_PID" "failed" "$AGENT_STARTUP" "http://localhost:8081/actuator/health"),"
        [ -z "$FAILED_SERVICE" ] && FAILED_SERVICE="agent" && FAILED_REASON="Agent :8081 启动超时（LLM 连接可能慢，等待 120s）"
        OVERALL_STATUS="failure"
    fi
fi

# --- 2e. Agent Python :8084 ---
if [ -n "$PYTHON_BIN" ]; then
    echo ""
    echo ">> 启动 Agent Python (:8084)..."
    AGENT_PY_START=$(date +%s)
    cd "$SCRIPT_DIR/finance-agent-py"
    ensure_python_deps "$SCRIPT_DIR/finance-agent-py"
    if [ -d ".venv" ]; then
        nohup .venv/bin/python main.py > "$LOG_DIR/agent-py.log" 2>&1 &
    else
        nohup "$PYTHON_BIN" main.py > "$LOG_DIR/agent-py.log" 2>&1 &
    fi
    AGENT_PY_PID=$!
    cd "$SCRIPT_DIR"
    if wait_for_http "Agent Python" "http://localhost:8084/actuator/health" 60; then
        AGENT_PY_STARTUP=$(($(date +%s) - AGENT_PY_START))
        SERVICES_JSON+="$(service_status "agent-py" 8084 "$AGENT_PY_PID" "running" "$AGENT_PY_STARTUP" "http://localhost:8084/actuator/health"),"
    else
        AGENT_PY_STARTUP=$(($(date +%s) - AGENT_PY_START))
        SERVICES_JSON+="$(service_status "agent-py" 8084 "$AGENT_PY_PID" "failed" "$AGENT_PY_STARTUP" "http://localhost:8084/actuator/health"),"
        echo "⚠ Agent Python 启动失败（不阻塞 Java 栈）"
    fi
else
    echo ""
    echo "⚠ 未找到 python3，跳过 Agent Python (:8084)"
    SERVICES_JSON+="$(service_status "agent-py" 8084 "null" "skipped" 0 "http://localhost:8084/actuator/health"),"
fi

# --- 2f. Frontend :5173 ---
echo ""
echo ">> 启动 Frontend (:5173)..."

ensure_npm_deps
FRONTEND_START=$(date +%s)
cd "$SCRIPT_DIR/finance-frontend"
nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
cd "$SCRIPT_DIR"
# Frontend 用端口监听检测（Vite 不一定有 /actuator/health）
if wait_for_port "Frontend" 5173 30; then
    FRONTEND_STARTUP=$(($(date +%s) - FRONTEND_START))
    SERVICES_JSON+="$(service_status "frontend" 5173 "$FRONTEND_PID" "running" "$FRONTEND_STARTUP" "http://localhost:5173")"
else
    FRONTEND_STARTUP=$(($(date +%s) - FRONTEND_START))
    SERVICES_JSON+="$(service_status "frontend" 5173 "$FRONTEND_PID" "failed" "$FRONTEND_STARTUP" "http://localhost:5173")"
    # frontend 失败不影响 overall status（后端服务才是核心）
fi

# ------------------------------------------------------------------
# Phase 3: 输出结果
# ------------------------------------------------------------------
TOTAL_DURATION=$(($(date +%s) - START_EPOCH))

# 去掉末尾多余逗号（SERVICES_JSON 最后可能没有逗号在前面逻辑中不会出现）

echo ""
echo "============================================"
echo "  重启完成"
echo "============================================"

if [ "$JSON_OUTPUT" = true ]; then
    # 输出纯 JSON（最后一行，Agent 解析用）
    cat <<JSON_END
{
  "status": "$OVERALL_STATUS",
  "totalDurationS": $TOTAL_DURATION,
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "failedService": $(if [ -n "$FAILED_SERVICE" ]; then echo "\"$FAILED_SERVICE\""; else echo "null"; fi),
  "failureReason": $(if [ -n "$FAILED_REASON" ]; then echo "\"$FAILED_REASON\""; else echo "null"; fi),
  "logFile": "$LOG_FILE",
  "services": [$SERVICES_JSON]
}
JSON_END
else
    echo ""
    echo "状态: $OVERALL_STATUS"
    echo "总耗时: ${TOTAL_DURATION}ms"
    echo "日志文件: $LOG_FILE"
    if [ -n "$FAILED_REASON" ]; then
        echo "失败原因: $FAILED_REASON"
    fi
    echo ""
    echo "端口状态:"
    lsof -ti:8080 >/dev/null 2>&1 && echo "  Backend      :8080 ✅" || echo "  Backend      :8080 ❌"
    lsof -ti:8082 >/dev/null 2>&1 && echo "  MCP Java     :8082 ✅" || echo "  MCP Java     :8082 ❌"
    lsof -ti:8083 >/dev/null 2>&1 && echo "  MCP Python   :8083 ✅" || echo "  MCP Python   :8083 ❌"
    lsof -ti:8081 >/dev/null 2>&1 && echo "  Agent Java   :8081 ✅" || echo "  Agent Java   :8081 ❌"
    lsof -ti:8084 >/dev/null 2>&1 && echo "  Agent Python :8084 ✅" || echo "  Agent Python :8084 ❌"
    lsof -ti:5173 >/dev/null 2>&1 && echo "  Frontend     :5173 ✅" || echo "  Frontend     :5173 ❌"
fi

exit 0
