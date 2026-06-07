"""Analyst 节点：交易统计、分类汇总、趋势分析。"""
import logging
from langgraph.types import Command

logger = logging.getLogger(__name__)


def build_analyst_node(agent=None, audit_callback_factory=None):
    """构建 analyst 节点函数。

    Args:
        agent: create_react_agent 创建的 ReAct Agent，绑定 list_transactions/summarize_transactions。
               如果为 None，返回占位回复。
        audit_callback_factory: callable(trace_id, agent_name, call_type, user_id) -> LlmAuditCallback。
    """

    async def analyst_node(state: dict) -> Command:
        messages = state.get("messages", [])

        if agent is None:
            return Command(goto="supervisor", update={
                "messages": [{"role": "assistant",
                              "content": "[Analyst 占位] 分析功能待初始化"}]})

        try:
            config = {}
            if audit_callback_factory:
                trace_id = state.get("trace_id", "unknown")
                user_id = state.get("user_id", "unknown")
                callback = audit_callback_factory(trace_id, "analyst", "execute", user_id)
                config = {"callbacks": [callback]}
            result = await agent.ainvoke({"messages": messages}, config=config)
        except Exception as e:
            logger.error("Analyst 执行失败: %s", e)
            return Command(goto="supervisor", update={
                "messages": [{"role": "assistant", "content": "分析请求失败，请稍后重试。"}]})

        return Command(goto="supervisor", update={"messages": result.get("messages", [])})

    return analyst_node
