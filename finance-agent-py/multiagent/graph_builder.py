"""组装 Multi-Agent StateGraph。"""
from langgraph.graph import StateGraph


def build_multi_agent_graph(supervisor_llm=None, bookkeeper_agent=None, analyst_agent=None,
                            audit_callback_factory=None):
    """构建 Supervisor + Bookkeeper + Analyst 的 StateGraph。

    Args:
        supervisor_llm: 用于分类的 ChatOpenAI（不绑工具），可选
        bookkeeper_agent: 绑定记账工具的 ReAct Agent，可选
        analyst_agent: 绑定分析工具的 ReAct Agent，可选
        audit_callback_factory: callable(trace_id, agent_name, call_type, user_id)
                                -> LlmAuditCallback，可选

    Returns:
        编译后的 LangGraph CompiledStateGraph
    """
    from .supervisor_node import build_supervisor_node
    from .bookkeeper_node import build_bookkeeper_node
    from .analyst_node import build_analyst_node

    graph = StateGraph(dict)

    graph.add_node("supervisor", build_supervisor_node(supervisor_llm, audit_callback_factory))
    graph.add_node("bookkeeper", build_bookkeeper_node(bookkeeper_agent, audit_callback_factory))
    graph.add_node("analyst", build_analyst_node(analyst_agent, audit_callback_factory))

    graph.set_entry_point("supervisor")

    return graph.compile(recursion_limit=5)
