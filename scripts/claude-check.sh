#!/usr/bin/env bash
# claude-check.sh — 校验 CLAUDE.md 中关键事实声明与代码现状一致
# 用法：bash scripts/claude-check.sh
# 退出码：0 = 一致；非 0 = 发现漂移，输出每条不一致项

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

CLAUDE_MD="$ROOT/CLAUDE.md"
FAIL=0

red()   { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
yellow(){ printf "\033[33m%s\033[0m\n" "$*"; }

# 检查 CLAUDE.md 存在
if [ ! -f "$CLAUDE_MD" ]; then
  red "❌ CLAUDE.md 不存在于 $CLAUDE_MD"
  exit 1
fi

echo "============================================"
echo "  CLAUDE.md 现状校对"
echo "============================================"

# ----------------------------------------------------------------------
# 1. 工具数校验
# ----------------------------------------------------------------------
TOOLS_FILE="$ROOT/finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java"
if [ -f "$TOOLS_FILE" ]; then
  # 只统计作为注解使用的 @McpTool（行首空白 + @McpTool( ），排除 import/javadoc/@link 等
  ACTUAL_TOOL_COUNT=$(grep -cE "^[[:space:]]*@McpTool[[:space:]]*\(" "$TOOLS_FILE" || echo 0)
  # 从 CLAUDE.md 中提取 "暴露 N 个工具" 的数字
  CLAIMED_TOOL_COUNT=$(grep -oE "暴露 [0-9]+ 个工具|exposing [0-9]+ tools|暴露[0-9]+个工具" "$CLAUDE_MD" 2>/dev/null \
                      | head -1 | grep -oE "[0-9]+" 2>/dev/null | head -1 || true)
  CLAIMED_TOOL_COUNT="${CLAIMED_TOOL_COUNT:-}"
  if [ -z "$CLAIMED_TOOL_COUNT" ]; then
    yellow "⚠ CLAUDE.md 未声明 MCP 工具数（找不到 '暴露 N 个工具' 模式）；FinanceTools.java 实际 $ACTUAL_TOOL_COUNT 个 @McpTool"
  elif [ "$CLAIMED_TOOL_COUNT" = "$ACTUAL_TOOL_COUNT" ]; then
    green "✅ MCP 工具数一致：CLAUDE.md=${CLAIMED_TOOL_COUNT}，FinanceTools.java=${ACTUAL_TOOL_COUNT}"
  else
    red "❌ MCP 工具数漂移：CLAUDE.md 声明 ${CLAIMED_TOOL_COUNT} 个，实际 ${ACTUAL_TOOL_COUNT} 个"
    red "   修复：编辑 CLAUDE.md，把 '暴露 ${CLAIMED_TOOL_COUNT} 个工具' 改为 '暴露 ${ACTUAL_TOOL_COUNT} 个工具'"
    FAIL=$((FAIL+1))
  fi
else
  yellow "⚠ 未找到 $TOOLS_FILE，跳过工具数校验"
fi

# ----------------------------------------------------------------------
# 2. 端口号校验
# ----------------------------------------------------------------------
check_port() {
  local label="$1"
  local expected_port="$2"
  local file="$3"
  local pattern="$4"

  if [ ! -f "$file" ]; then
    yellow "⚠ $label：文件不存在 $file"
    return 0
  fi

  if grep -qE "$pattern" "$file"; then
    if grep -q "$expected_port" "$CLAUDE_MD"; then
      green "✅ $label 端口 $expected_port 在 CLAUDE.md 和 $file 都能找到"
    else
      red "❌ $label：$file 用 $expected_port，但 CLAUDE.md 找不到该端口"
      FAIL=$((FAIL+1))
    fi
  else
    red "❌ $label：在 $file 中找不到端口模式 $pattern"
    FAIL=$((FAIL+1))
  fi
}

check_port "Backend"             "8080" "$ROOT/finance-backend/src/main/resources/application.yml"     "port:[[:space:]]*8080"
check_port "Agent (Java)"        "8081" "$ROOT/finance-agent/src/main/resources/application.yml"       "port:[[:space:]]*8081"
check_port "MCP Server (Java)"   "8082" "$ROOT/finance-mcp-server/src/main/resources/application.yml"  "port:[[:space:]]*8082"
check_port "MCP Server (Python)" "8083" "$ROOT/finance-mcp-server-py/server.py"                        "port=8083"

# Python Agent 端口（在 main.py 中）
PY_AGENT_FILE="$ROOT/finance-agent-py/main.py"
if [ -f "$PY_AGENT_FILE" ]; then
  if grep -qE "8084" "$PY_AGENT_FILE" || grep -rqE "8084" "$ROOT/finance-agent-py/" --include="*.py" 2>/dev/null; then
    if grep -q "8084" "$CLAUDE_MD"; then
      green "✅ Agent (Python) 端口 8084 一致"
    else
      red "❌ Agent (Python)：代码里有 8084 但 CLAUDE.md 找不到"
      FAIL=$((FAIL+1))
    fi
  else
    yellow "⚠ Agent (Python)：8084 端口在代码中未显式声明（可能由 config 注入）"
  fi
fi

# Frontend (Vite 默认 5173)
if grep -q "5173" "$CLAUDE_MD"; then
  green "✅ Frontend 端口 5173 在 CLAUDE.md 中声明（Vite 默认值）"
else
  red "❌ Frontend：CLAUDE.md 中找不到 5173"
  FAIL=$((FAIL+1))
fi

# ----------------------------------------------------------------------
# 3. 关键文件存在性
# ----------------------------------------------------------------------
echo ""
echo "--- 关键路径存在性 ---"
for path in \
  ".aone_copilot/README.md" \
  ".aone_copilot/plans/README.md" \
  ".aone_copilot/skills/change-prompt/SKILL.md" \
  "docs/troubleshooting/README.md" \
  "docs/roadmap/README.md"; do
  if [ -e "$ROOT/$path" ]; then
    green "✅ $path"
  else
    red "❌ $path 缺失（CLAUDE.md 入口章节引用）"
    FAIL=$((FAIL+1))
  fi
done

# ----------------------------------------------------------------------
# 总结
# ----------------------------------------------------------------------
echo ""
echo "============================================"
if [ "$FAIL" -eq 0 ]; then
  green "  全部一致 ✅"
  echo "============================================"
  exit 0
else
  red "  发现 $FAIL 项漂移，请按上面提示修正 CLAUDE.md 或代码"
  echo "============================================"
  exit 1
fi
