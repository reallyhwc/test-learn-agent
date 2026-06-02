#!/usr/bin/env bash
# new-mcp-tool.sh — 生成新 MCP 工具的骨架代码
#
# 用法：
#   ./scripts/new-mcp-tool.sh <tool_name> "<description>"
#
# 例：
#   ./scripts/new-mcp-tool.sh transfer_money "在两个账户间转账"
#
# 行为：
#   1. 在 FinanceTools.java 追加 Java 方法骨架（带 @McpTool + TODO）
#   2. 在 FinanceToolsTest.java 追加 JUnit 测试骨架
#   3. 在 finance-mcp-server-py/server.py 追加 Python @mcp.tool() 骨架
#   4. 输出 manual checklist：仍需手动完成的 5 项工作
#
# 实现：脚本本体用 bash 解析参数 + 路径，实际代码生成委托给同目录的 Python 辅助脚本
#       （避免 awk/sed 处理多行字符串的跨平台坑）。

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "用法: $0 <tool_name> \"<description>\""
  echo "例:   $0 transfer_money \"在两个账户间转账\""
  exit 1
fi

TOOL_NAME="$1"
DESCRIPTION="$2"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 委托给 Python 实现实际的文件修改
exec python3 "$ROOT/scripts/_new_mcp_tool_impl.py" "$TOOL_NAME" "$DESCRIPTION" "$ROOT"
