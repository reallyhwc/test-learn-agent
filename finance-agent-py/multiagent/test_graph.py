"""Multi-Agent StateGraph 单元测试。"""
import pytest
from .state import MultiAgentState
from .graph_builder import build_multi_agent_graph


class TestMultiAgentState:
    def test_state_has_messages_field(self):
        state = MultiAgentState.create(messages=[], next_agent="", pending_confirmation=None)
        assert state["messages"] == []
        assert state["next_agent"] == ""

    def test_state_defaults(self):
        state = MultiAgentState.create()
        assert state["messages"] == []
        assert state["next_agent"] == ""
        assert state["pending_confirmation"] is None


class TestGraphBuilder:
    def test_build_returns_compiled_graph(self):
        graph = build_multi_agent_graph()
        assert graph is not None
        assert hasattr(graph, "invoke")
        assert hasattr(graph, "astream")
