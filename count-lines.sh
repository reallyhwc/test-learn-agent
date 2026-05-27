#!/bin/bash
# 项目代码量统计脚本
# 用法: ./count-lines.sh

set -e
cd "$(dirname "$0")"

EXCLUDE_DIRS="target|node_modules|\.git|\.venv|\.mvn|__pycache__|dist|build"

count() {
    local pattern="$1"
    local label="$2"
    local extra_exclude="${3:-}"
    local files
    files=$(find . -type f -name "$pattern" | grep -vE "$EXCLUDE_DIRS" ${extra_exclude:+| grep -vE "$extra_exclude"})
    if [ -z "$files" ]; then
        echo "0"
    else
        echo "$files" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}'
    fi
}

count_dir() {
    local dir="$1"
    local pattern="$2"
    local files
    files=$(find "$dir" -type f -name "$pattern" 2>/dev/null | grep -vE "$EXCLUDE_DIRS")
    if [ -z "$files" ]; then
        echo "0"
    else
        echo "$files" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}'
    fi
}

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║          Personal Finance Agent — 代码量统计                ║"
echo "║          $(date '+%Y-%m-%d %H:%M:%S')                          ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# 按语言统计
java_lines=$(count "*.java" "Java")
python_lines=$(count "*.py" "Python")
vue_lines=$(count "*.vue" "Vue")
js_lines=$(count "*.js" "JavaScript")
html_lines=$(count "*.html" "HTML")
css_lines=$(count "*.css" "CSS")
yml_lines=$(find . -type f \( -name "*.yml" -o -name "*.yaml" \) | grep -vE "$EXCLUDE_DIRS" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}')
md_lines=$(count "*.md" "Markdown")
sh_lines=$(count "*.sh" "Shell")
xml_lines=$(count "*.xml" "XML")

echo "┌─────────────────────────────────────────────────────────────┐"
echo "│ 📊 按语言分类                                               │"
echo "├──────────────────┬──────────────┬────────────────────────────┤"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "Java" "$java_lines" "后端 + MCP + Agent"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "Python" "$python_lines" "Agent-py + MCP-py"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "Vue" "$vue_lines" "前端组件"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "JavaScript" "$js_lines" "前端工具/配置"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "HTML/CSS" "$((html_lines + css_lines))" "页面模板/样式"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "YAML" "$yml_lines" "Spring/CI 配置"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "Shell" "$sh_lines" "启动/构建脚本"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "XML" "$xml_lines" "Maven pom"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "Markdown" "$md_lines" "文档/规范/计划"
echo "├──────────────────┼──────────────┼────────────────────────────┤"
code_total=$((java_lines + python_lines + vue_lines + js_lines + html_lines + css_lines + sh_lines))
all_total=$((code_total + yml_lines + xml_lines + md_lines))
printf "│ %-16s │ %10s 行 │ %-26s │\n" "代码合计" "$code_total" "不含配置/文档"
printf "│ %-16s │ %10s 行 │ %-26s │\n" "总计" "$all_total" "含配置/文档"
echo "└──────────────────┴──────────────┴────────────────────────────┘"

echo ""
echo "┌─────────────────────────────────────────────────────────────┐"
echo "│ 📦 按模块分类                                               │"
echo "├──────────────────────────┬──────────────┬────────────────────┤"
printf "│ %-24s │ %10s 行 │ %-18s │\n" "finance-backend" "$(count_dir ./finance-backend '*.java')" "Java"
printf "│ %-24s │ %10s 行 │ %-18s │\n" "finance-mcp-server" "$(count_dir ./finance-mcp-server '*.java')" "Java"
printf "│ %-24s │ %10s 行 │ %-18s │\n" "finance-agent" "$(count_dir ./finance-agent '*.java')" "Java"
printf "│ %-24s │ %10s 行 │ %-18s │\n" "finance-agent-py" "$(count_dir ./finance-agent-py '*.py')" "Python"
printf "│ %-24s │ %10s 行 │ %-18s │\n" "finance-mcp-server-py" "$(count_dir ./finance-mcp-server-py '*.py')" "Python"

fe_lines=$(find ./finance-frontend -type f \( -name "*.vue" -o -name "*.js" -o -name "*.html" -o -name "*.css" \) | grep -vE "$EXCLUDE_DIRS" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}')
printf "│ %-24s │ %10s 行 │ %-18s │\n" "finance-frontend" "$fe_lines" "Vue/JS/HTML/CSS"
echo "└──────────────────────────┴──────────────┴────────────────────┘"

echo ""
echo "┌─────────────────────────────────────────────────────────────┐"
echo "│ 🧪 测试代码占比                                             │"
echo "├──────────────────────────┬──────────────┬────────────────────┤"
test_java=$(find . -path "*/test*" -name "*.java" -not -path "*/target/*" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}')
test_py=$(find . -name "test_*.py" -not -path "*/.venv/*" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}')
test_js=$(find ./finance-frontend/test -name "*.js" 2>/dev/null | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}')
test_total=$((test_java + test_py + test_js))
printf "│ %-24s │ %10s 行 │ %-18s │\n" "Java 测试" "$test_java" ""
printf "│ %-24s │ %10s 行 │ %-18s │\n" "Python 测试" "$test_py" ""
printf "│ %-24s │ %10s 行 │ %-18s │\n" "前端测试" "$test_js" ""
printf "│ %-24s │ %10s 行 │ %-18s │\n" "测试合计" "$test_total" "占代码 $((test_total * 100 / code_total))%"
echo "└──────────────────────────┴──────────────┴────────────────────┘"
