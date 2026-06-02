#!/usr/bin/env python3
"""new_mcp_tool 的实现层。

被 scripts/new-mcp-tool.sh 调用。Python 重写避免 awk/sed 处理多行字符串的跨平台坑。
"""
import sys
from pathlib import Path


def snake_to_camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p.title() for p in parts[1:])


def snake_to_pascal(name: str) -> str:
    return "".join(p.title() for p in name.split("_"))


def append_before_last_brace(file: Path, snippet: str) -> None:
    """在 Java 文件最后一个 } 之前插入 snippet。"""
    text = file.read_text(encoding="utf-8")
    idx = text.rfind("}")
    if idx == -1:
        raise RuntimeError(f"{file} 中找不到结束 }}")
    new_text = text[:idx] + snippet + text[idx:]
    file.write_text(new_text, encoding="utf-8")


def insert_before_marker(file: Path, marker: str, snippet: str) -> None:
    """在 Python 文件指定 marker 行之前插入 snippet；找不到则追加到末尾。"""
    text = file.read_text(encoding="utf-8")
    idx = text.find(marker)
    if idx == -1:
        # 兜底：追加到末尾
        if not text.endswith("\n"):
            text += "\n"
        text += snippet
    else:
        text = text[:idx] + snippet + text[idx:]
    file.write_text(text, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 4:
        print("内部用法: _new_mcp_tool_impl.py <tool_name> <description> <root>", file=sys.stderr)
        return 1

    tool_name = sys.argv[1]
    description = sys.argv[2]
    root = Path(sys.argv[3])

    method_name = snake_to_camel(tool_name)
    test_method = snake_to_pascal(tool_name)

    java_tools = root / "finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java"
    java_test = root / "finance-mcp-server/src/test/java/com/example/mcp/tool/FinanceToolsTest.java"
    py_server = root / "finance-mcp-server-py/server.py"

    if not java_tools.exists():
        print(f"❌ 未找到 {java_tools}", file=sys.stderr)
        return 1
    if not py_server.exists():
        print(f"❌ 未找到 {py_server}", file=sys.stderr)
        return 1

    print("=" * 44)
    print(f"  生成 MCP 工具骨架: {tool_name}")
    print(f"  描述: {description}")
    print(f"  Java 方法: {method_name}")
    print("=" * 44)

    # ---------- Java 工具 ----------
    java_snippet = f'''
    /**
     * TODO: {description}
     *
     * <p>骨架由 scripts/new-mcp-tool.sh 生成，需手动补全：
     * <ul>
     *   <li>方法参数（用 @McpToolParam 标注）</li>
     *   <li>调用 Backend REST API 的逻辑</li>
     *   <li>异常处理与友好提示</li>
     * </ul>
     */
    @McpTool(name = "{tool_name}",
            description = "{description}（TODO: 完善描述与适用场景）")
    public Object {method_name}(
            @McpToolParam(description = "用户ID") String userId
            /* TODO: 在这里添加其他参数，参考 queryBalance/addTransaction */) {{
        long start = System.nanoTime();
        try {{
            userId = validateUserId(userId);
            log.info("{method_name} called with userId={{}}", userId);
            // TODO: 调用 Backend，例如：
            //   Object result = restClient.get().uri("...").retrieve().body(...);
            Object result = "TODO: 实现 {tool_name}";
            recordSuccess("{tool_name}", start);
            return result;
        }} catch (Exception e) {{
            recordError("{tool_name}", e);
            log.error("{tool_name} 失败: userId={{}}", userId, e);
            return "TODO: 友好错误提示";
        }}
    }}

'''
    append_before_last_brace(java_tools, java_snippet)
    print(f"✅ 已追加 Java 方法骨架到: {java_tools}")

    # ---------- JUnit 测试 ----------
    if java_test.exists():
        test_snippet = f'''
    @Test
    void should{test_method}() {{
        // TODO: 完善测试用例
        // 1. mock RestClient 行为
        // 2. 调用 financeTools.{method_name}(...)
        // 3. assertEquals/assertThat 校验结果
        // 4. 验证 metric 已记录
        org.junit.jupiter.api.Assertions.fail("TODO: 实现 should{test_method} 测试");
    }}

'''
        append_before_last_brace(java_test, test_snippet)
        print(f"✅ 已追加 JUnit 测试骨架到: {java_test}")
    else:
        print(f"⚠ 未找到 {java_test}，跳过测试骨架")

    # ---------- Python 工具 ----------
    py_snippet = f'''

@mcp.tool()
def {method_name}(user_id: str) -> Any:
    """TODO: {description}

    骨架由 scripts/new-mcp-tool.sh 生成，需手动补全：
    - 添加其他参数
    - 调用 Backend REST API
    - 异常处理
    """
    try:
        logger.info("{method_name} called user_id=%s", user_id)
        # TODO: 实现 {tool_name}
        return "TODO: 实现 {tool_name}"
    except Exception as e:
        logger.error("{tool_name} 失败: %s", e)
        return "TODO: 友好错误提示"


'''
    insert_before_marker(py_server, "# ──────────── helpers", py_snippet)
    print(f"✅ 已追加 Python 工具骨架到: {py_server}")

    # ---------- Manual Checklist ----------
    print(f"""
============================================
  ⚠️ 仍需手动完成的 5 项工作
============================================

[ ] 1. Backend Controller
       在 finance-backend/src/main/java/com/example/finance/controller/
       中新增对应的 REST endpoint（如果工具需要调用新的 Backend API）

[ ] 2. Backend Service
       在 finance-backend/src/main/java/com/example/finance/service/FinanceService.java
       中实现业务逻辑

[ ] 3. Agent System Prompt
       修改 finance-agent/src/main/java/com/example/agent/controller/ChatController.java
       的 buildSystemPrompt()，把新工具加入"可用工具列表"和决策规则
       同步修改 finance-agent-py/system_prompt.py（双栈）
       ⚠️ 必读：.aone_copilot/skills/change-prompt/SKILL.md

[ ] 4. 前端组件
       如果工具影响前端可见状态（账户、交易列表），同步更新对应组件

[ ] 5. 文档与校对
       - 跑 bash scripts/claude-check.sh，CLAUDE.md 工具数应自动校正
       - 在 .aone_copilot/plans/done/ 留个记录（如果是计划内的功能）

参考：.aone_copilot/skills/add-mcp-tool/SKILL.md 有完整 11 步清单

============================================""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
