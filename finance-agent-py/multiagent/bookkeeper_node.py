"""Bookkeeper 节点：记账、查余额、查账户。"""
import logging
from langgraph.types import Command

WRITE_TOOLS = {"add_transaction"}

logger = logging.getLogger(__name__)


def build_bookkeeper_node(agent=None):
    """构建 bookkeeper 节点函数。

    Args:
        agent: create_react_agent 创建的 ReAct Agent，绑定 add_transaction/list_accounts/query_balance。
               如果为 None，返回占位回复。
    """

    async def bookkeeper_node(state: dict) -> Command:
        messages = state.get("messages", [])

        if agent is None:
            return Command(goto="supervisor", update={
                "messages": [{"role": "assistant",
                              "content": "[Bookkeeper 占位] 记账功能待初始化"}]})

        try:
            result = await agent.ainvoke({"messages": messages})
        except Exception as e:
            logger.error("Bookkeeper 执行失败: %s", e)
            return Command(goto="supervisor", update={
                "messages": [{"role": "assistant", "content": "记账操作失败，请稍后重试。"}]})

        result_messages = result.get("messages", [])
        for msg in result_messages:
            if hasattr(msg, "tool_calls") and msg.tool_calls:
                for tc in msg.tool_calls:
                    tool_name = tc.get("name", "") if isinstance(tc, dict) else getattr(tc, "name", "")
                    if tool_name in WRITE_TOOLS:
                        return Command(goto="supervisor", update={
                            "messages": result_messages,
                            "pending_confirmation": {
                                "tool_name": tool_name,
                                "parameters": tc.get("args", {}) if isinstance(tc, dict) else getattr(tc, "args", {}),
                            }
                        })

        return Command(goto="supervisor", update={"messages": result_messages})

    return bookkeeper_node
