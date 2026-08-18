"""hitl_gate.py 纯逻辑单元测试 — 验证写操作确认闸门的判定与包装行为。

不依赖 langgraph 运行时，通过注入 mock interrupt_fn 验证 confirm/cancel/modified_params 分支。
"""
import pytest

from hitl_gate import (
    CancelledToolCall,
    build_confirmation_payload,
    is_write_tool,
    make_confirming_tool,
    wrap_write_tools,
)


class FakeRawTool:
    """模拟 MCP 工具，记录调用参数。"""

    def __init__(self, name, description=""):
        self.name = name
        self.description = description
        self.calls = []

    async def ainvoke(self, args):
        self.calls.append(args)
        return {"executed": args}


class TestIsWriteTool:
    def test_write_tool(self):
        assert is_write_tool("add_transaction") is True

    def test_read_tool(self):
        assert is_write_tool("query_balance") is False
        assert is_write_tool("list_transactions") is False


class TestBuildPayload:
    def test_add_transaction_payload(self):
        p = build_confirmation_payload("add_transaction", {"amount": 30})
        assert p.tool_name == "add_transaction"
        assert p.description == "记一笔交易"
        assert p.parameters == {"amount": 30}


class TestWrapWriteTools:
    def test_wraps_only_write_tools(self):
        write = FakeRawTool("add_transaction")
        read = FakeRawTool("query_balance")

        wrapped = wrap_write_tools([write, read], lambda p: {"action": "confirm"})

        assert wrapped[0].name == "add_transaction"  # 写工具被包装
        assert wrapped[1] is read  # 读工具原样返回

    def test_confirm_executes_raw_tool(self):
        write = FakeRawTool("add_transaction")
        interrupt_calls = []

        def fake_interrupt(payload):
            interrupt_calls.append(payload)
            return {"action": "confirm"}

        confirming = make_confirming_tool(write, fake_interrupt)

        import asyncio
        result = asyncio.run(confirming(amount=30, category="餐饮"))

        assert result == {"executed": {"amount": 30, "category": "餐饮"}}
        assert len(interrupt_calls) == 1
        assert interrupt_calls[0]["type"] == "confirmation_required"
        assert write.calls == [{"amount": 30, "category": "餐饮"}]

    def test_cancel_raises(self):
        write = FakeRawTool("add_transaction")

        def fake_interrupt(payload):
            return {"action": "cancel"}

        confirming = make_confirming_tool(write, fake_interrupt)

        import asyncio
        with pytest.raises(CancelledToolCall):
            asyncio.run(confirming(amount=30))
        assert write.calls == []  # 取消不执行

    def test_modified_params_merged(self):
        write = FakeRawTool("add_transaction")

        def fake_interrupt(payload):
            return {"action": "confirm", "modified_params": {"amount": 15}}

        confirming = make_confirming_tool(write, fake_interrupt)

        import asyncio
        asyncio.run(confirming(amount=30, category="餐饮"))

        assert write.calls == [{"amount": 15, "category": "餐饮"}]  # 金额被修改
