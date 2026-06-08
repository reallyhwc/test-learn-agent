"""Multi-Agent 节点单元测试 — Supervisor/Bookkeeper/Analyst 节点逻辑。"""
import pytest
from unittest.mock import MagicMock, AsyncMock, patch
from langgraph.types import Command

from .supervisor_node import build_supervisor_node, _keyword_classify, _get_classify_prompt
from .bookkeeper_node import build_bookkeeper_node, WRITE_TOOLS
from .analyst_node import build_analyst_node
from .state import MultiAgentState


# ============================================================
# Supervisor 节点测试
# ============================================================

class TestKeywordClassify:
    """关键词分类测试（无需 LLM 的轻量方案）。"""

    def test_classify_booking_by_keyword_ji(self):
        assert _keyword_classify("记一笔午餐") == "booking"

    def test_classify_booking_by_keyword_balance(self):
        assert _keyword_classify("我的余额是多少") == "booking"

    def test_classify_booking_by_keyword_account(self):
        assert _keyword_classify("查看我的账户") == "booking"

    def test_classify_booking_by_keyword_add(self):
        assert _keyword_classify("添加一笔交易") == "booking"

    def test_classify_analysis_by_keyword_hua(self):
        assert _keyword_classify("本月花了多少钱") == "analysis"

    def test_classify_analysis_by_keyword_summary(self):
        assert _keyword_classify("汇总一下支出") == "analysis"

    def test_classify_analysis_by_keyword_compare(self):
        assert _keyword_classify("占比分析") == "analysis"

    def test_classify_analysis_by_keyword_how_much(self):
        assert _keyword_classify("餐饮花了多少") == "analysis"

    def test_classify_other_for_irrelevant(self):
        assert _keyword_classify("帮我写一首诗") == "other"

    def test_classify_other_for_greeting(self):
        assert _keyword_classify("你好") == "other"

    def test_classify_booking_priority_over_analysis(self):
        # "余额" 是 booking 关键词，"多少" 也是 analysis 关键词 → booking 先匹配
        assert _keyword_classify("余额是多少") == "booking"


class TestSupervisorNode:
    """supervisor_node 函数测试。"""

    def test_classify_prompt_contains_categories(self):
        prompt = _get_classify_prompt()
        assert "booking" in prompt
        assert "analysis" in prompt
        assert "other" in prompt

    def test_empty_messages_routes_to_end(self):
        node = build_supervisor_node(llm=None)
        state = MultiAgentState.create(messages=[])
        cmd = node(state)
        assert cmd.goto == "__end__"

    def test_no_user_messages_routes_to_end(self):
        node = build_supervisor_node(llm=None)
        state = MultiAgentState.create(messages=[
            {"role": "assistant", "content": "有什么可以帮您的？"}
        ])
        cmd = node(state)
        assert cmd.goto == "__end__"

    def test_keyword_booking_routes_to_bookkeeper(self):
        node = build_supervisor_node(llm=None)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "帮我记一笔午餐30元"}
        ])
        cmd = node(state)
        assert cmd.goto == "bookkeeper"
        assert cmd.update["next_agent"] == "bookkeeper"

    def test_keyword_analysis_routes_to_analyst(self):
        node = build_supervisor_node(llm=None)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "本月花了多少钱"}
        ])
        cmd = node(state)
        assert cmd.goto == "analyst"
        assert cmd.update["next_agent"] == "analyst"

    def test_keyword_other_routes_to_end_with_reply(self):
        node = build_supervisor_node(llm=None)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "帮我写诗"}
        ])
        cmd = node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert len(msgs) == 1
        assert "记账" in msgs[0]["content"]

    def test_llm_classify_booking_routes_to_bookkeeper(self):
        mock_llm = MagicMock()
        mock_response = MagicMock()
        mock_response.content = "booking"
        mock_llm.invoke.return_value = mock_response

        node = build_supervisor_node(llm=mock_llm)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "记录一笔支出"}
        ])
        cmd = node(state)
        assert cmd.goto == "bookkeeper"

    def test_llm_classify_analysis_routes_to_analyst(self):
        mock_llm = MagicMock()
        mock_response = MagicMock()
        mock_response.content = "analysis"
        mock_llm.invoke.return_value = mock_response

        node = build_supervisor_node(llm=mock_llm)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "汇总本月支出"}
        ])
        cmd = node(state)
        assert cmd.goto == "analyst"

    def test_llm_error_falls_back_to_end_with_error_msg(self):
        mock_llm = MagicMock()
        mock_llm.invoke.side_effect = RuntimeError("LLM timeout")

        node = build_supervisor_node(llm=mock_llm)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "查询余额"}
        ])
        cmd = node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert len(msgs) == 1
        assert "抱歉" in msgs[0]["content"]

    def test_uses_last_user_message_only(self):
        node = build_supervisor_node(llm=None)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "你好"},
            {"role": "assistant", "content": "你好！"},
            {"role": "user", "content": "记一笔午餐"},
        ])
        cmd = node(state)
        # 最后一条用户消息是"记一笔午餐" → booking
        assert cmd.goto == "bookkeeper"


# ============================================================
# Bookkeeper 节点测试
# ============================================================

class TestBookkeeperNode:
    """bookkeeper_node 函数测试。"""

    @pytest.mark.asyncio
    async def test_no_agent_returns_placeholder(self):
        node = build_bookkeeper_node(agent=None)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "查询余额"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert len(msgs) == 1
        assert "占位" in msgs[0]["content"]

    @pytest.mark.asyncio
    async def test_agent_error_returns_error_msg(self):
        mock_agent = AsyncMock()
        mock_agent.ainvoke.side_effect = RuntimeError("MCP timeout")

        node = build_bookkeeper_node(agent=mock_agent)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "查询余额"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert len(msgs) == 1
        assert "失败" in msgs[0]["content"]

    @pytest.mark.asyncio
    async def test_agent_success_returns_result(self):
        mock_agent = AsyncMock()
        mock_agent.ainvoke.return_value = {
            "messages": [{"role": "assistant", "content": "已记录午餐支出 ¥30.00"}]
        }

        node = build_bookkeeper_node(agent=mock_agent)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "记一笔午餐30元"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert "已记录" in msgs[0]["content"]

    @pytest.mark.asyncio
    async def test_add_transaction_triggers_pending_confirmation(self):
        mock_agent = AsyncMock()
        tool_call_msg = MagicMock()
        tool_call_msg.tool_calls = [{"name": "add_transaction", "args": {"amount": 30, "type": "EXPENSE", "category": "餐饮"}}]
        mock_agent.ainvoke.return_value = {"messages": [tool_call_msg]}

        node = build_bookkeeper_node(agent=mock_agent)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "记一笔午餐30元"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        assert "pending_confirmation" in cmd.update
        confirmation = cmd.update["pending_confirmation"]
        assert confirmation is not None
        assert confirmation["tool_name"] == "add_transaction"
        assert confirmation["parameters"]["amount"] == 30

    @pytest.mark.asyncio
    async def test_non_write_tool_no_confirmation(self):
        mock_agent = AsyncMock()
        # query_balance 不在 WRITE_TOOLS 中 → 不触发 confirmation
        tool_call_msg = MagicMock()
        tool_call_msg.tool_calls = [{"name": "query_balance", "args": {"accountId": 1}}]
        mock_agent.ainvoke.return_value = {"messages": [tool_call_msg]}

        node = build_bookkeeper_node(agent=mock_agent)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "查询余额"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        assert cmd.update.get("pending_confirmation") is None

    def test_write_tools_set_contains_add_transaction(self):
        assert "add_transaction" in WRITE_TOOLS
        assert "query_balance" not in WRITE_TOOLS
        assert "list_accounts" not in WRITE_TOOLS


# ============================================================
# Analyst 节点测试
# ============================================================

class TestAnalystNode:
    """analyst_node 函数测试。"""

    @pytest.mark.asyncio
    async def test_no_agent_returns_placeholder(self):
        node = build_analyst_node(agent=None)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "本月花了多少钱"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert len(msgs) == 1
        assert "占位" in msgs[0]["content"]

    @pytest.mark.asyncio
    async def test_agent_error_returns_error_msg(self):
        mock_agent = AsyncMock()
        mock_agent.ainvoke.side_effect = RuntimeError("API timeout")

        node = build_analyst_node(agent=mock_agent)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "汇总支出"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert len(msgs) == 1
        assert "失败" in msgs[0]["content"]

    @pytest.mark.asyncio
    async def test_agent_success_returns_result(self):
        mock_agent = AsyncMock()
        mock_agent.ainvoke.return_value = {
            "messages": [{"role": "assistant", "content": "本月餐饮支出：¥523.50，共8笔"}]
        }

        node = build_analyst_node(agent=mock_agent)
        state = MultiAgentState.create(messages=[
            {"role": "user", "content": "本月餐饮花了多少"}
        ])
        cmd = await node(state)
        assert cmd.goto == "__end__"
        msgs = cmd.update.get("messages", [])
        assert "¥523.50" in msgs[0]["content"]


# ============================================================
# MultiAgentState 测试
# ============================================================

class TestMultiAgentState:
    def test_create_with_defaults(self):
        state = MultiAgentState.create()
        assert state["messages"] == []
        assert state["next_agent"] == ""
        assert state["pending_confirmation"] is None

    def test_create_with_values(self):
        state = MultiAgentState.create(
            messages=[{"role": "user", "content": "你好"}],
            next_agent="bookkeeper",
            pending_confirmation={"tool_name": "add_transaction", "parameters": {}},
        )
        assert len(state["messages"]) == 1
        assert state["next_agent"] == "bookkeeper"
        assert state["pending_confirmation"]["tool_name"] == "add_transaction"

    def test_state_is_dict_like(self):
        state = MultiAgentState.create()
        state["new_key"] = "value"
        assert state["new_key"] == "value"
