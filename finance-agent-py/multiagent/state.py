"""Multi-Agent 共享状态定义。"""


class MultiAgentState(dict):
    """LangGraph StateGraph 的共享状态。

    字段：
    - messages: 共享对话历史
    - next_agent: supervisor 决定的目标节点名
    - pending_confirmation: HITL 暂存的待确认操作或 None
    """

    @classmethod
    def create(cls, messages=None, next_agent="", pending_confirmation=None,
               trace_id="", user_id=""):
        return {
            "messages": messages or [],
            "next_agent": next_agent,
            "pending_confirmation": pending_confirmation,
            "trace_id": trace_id,
            "user_id": user_id,
        }
