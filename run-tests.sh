#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RETRY_COUNT=3
SKIP_AI=false
LAYER="all"

while [[ $# -gt 0 ]]; do
    case $1 in
        --retry) RETRY_COUNT="$2"; shift 2 ;;
        --retry=*) RETRY_COUNT="${1#*=}"; shift ;;
        --skip-ai) SKIP_AI=true; shift ;;
        --layer) LAYER="$2"; shift 2 ;;
        --layer=*) LAYER="${1#*=}"; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass_count=0
fail_count=0
skip_count=0

run_maven_test() {
    local module=$1
    local label=$2
    echo -e "${YELLOW}[测试] $label ($module)${NC}"
    cd "$SCRIPT_DIR/$module"
    if ./mvnw test -q 2>&1 | tail -20; then
        echo -e "${GREEN}[通过] $label${NC}"
        ((pass_count++)) || true
        return 0
    else
        echo -e "${RED}[失败] $label${NC}"
        ((fail_count++)) || true
        return 1
    fi
}

# Layer 1-2: Backend + MCP (确定性测试)
if [[ "$LAYER" == "all" || "$LAYER" == "backend" ]]; then
    echo "========== Layer 1: Backend 测试 =========="
    run_maven_test "finance-backend" "Backend 单元/集成测试"

    echo "========== Layer 2: MCP Server 测试 =========="
    run_maven_test "finance-mcp-server" "MCP Server 工具测试"
fi

# Layer 3: Agent (AI 测试，支持重试)
if [[ "$LAYER" == "all" || "$LAYER" == "agent" ]]; then
    if $SKIP_AI; then
        echo -e "${YELLOW}[跳过] AI 测试${NC}"
    else
        echo "========== Layer 3: Agent AI 测试 =========="

        # 检查 .env
        if [ ! -f ".env" ]; then
            echo -e "${YELLOW}[跳过] 未找到 .env 文件，跳过 AI 测试${NC}"
            ((skip_count++)) || true
        else
            cd "$SCRIPT_DIR/finance-agent"

            for i in $(seq 1 $RETRY_COUNT); do
                echo -e "${YELLOW}[Agent 测试] 第 $i/$RETRY_COUNT 次尝试${NC}"

                if ./mvnw test -q 2>&1; then
                    echo -e "${GREEN}[通过] Agent AI 测试 (第 ${i} 次)${NC}"
                    ((pass_count++)) || true
                    break
                else
                    if [ $i -lt $RETRY_COUNT ]; then
                        echo -e "${YELLOW}[重试] AI 测试失败，5 秒后重试...${NC}"
                        sleep 5
                    else
                        echo -e "${RED}[失败] Agent AI 测试，已重试 ${RETRY_COUNT} 次${NC}"
                        echo "失败详情见: finance-agent/target/surefire-reports/"
                        ((fail_count++)) || true
                    fi
                fi
            done
        fi
    fi
fi

# 汇总报告
echo ""
echo "============================================"
echo "  测试汇总"
echo "============================================"
echo -e "  通过: ${GREEN}$pass_count${NC}"
echo -e "  失败: ${RED}$fail_count${NC}"
echo -e "  跳过: ${YELLOW}$skip_count${NC}"
echo "============================================"

if [ $fail_count -gt 0 ]; then
    exit 1
fi
