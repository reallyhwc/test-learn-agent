"""Supervisor 节点：意图分类 + 路由派发。"""
import logging
from langchain_core.messages import SystemMessage
from langgraph.types import Command

logger = logging.getLogger(__name__)

CLASSIFY_PROMPT = """你是一个意图分类器。分析用户消息，返回以下分类之一：
- booking: 记账、查余额、查账户、添加交易记录
- analysis: 统计汇总、趋势分析、分类占比、对比支出
- other: 与个人财务无关的请求
只返回分类名称，不要解释。"""


def build_supervisor_node(llm=None, audit_callback_factory=None):
    """构建 supervisor 节点函数。

    Args:
        llm: ChatOpenAI 实例（不绑 MCP 工具），只用于文本分类。
             如果为 None，使用简单的关键词匹配分类。
        audit_callback_factory: callable(trace_id, user_id) -> LlmAuditCallback。
    """

    def supervisor_node(state: dict) -> Command:
        messages = state.get("messages", [])
        if not messages:
            return Command(goto="__end__")

        user_msgs = [m for m in messages if isinstance(m, dict) and m.get("role") == "user"]
        if not user_msgs:
            return Command(goto="__end__")

        last_user_msg = user_msgs[-1]["content"]
        trace_id = state.get("trace_id", "unknown")
        user_id = state.get("user_id", "unknown")

        # 如果有 LLM，使用 LLM 分类；否则用关键词匹配
        if llm is not None:
            try:
                config = {}
                if audit_callback_factory:
                    callback = audit_callback_factory(trace_id, "supervisor", "classify", user_id)
                    config = {"callbacks": [callback]}
                response = llm.invoke([
                    SystemMessage(content=CLASSIFY_PROMPT),
                    {"role": "user", "content": last_user_msg},
                ], config=config)
                target = response.content.strip().lower()
            except Exception as e:
                logger.warning("Supervisor 分类失败: %s", e)
                return Command(goto="__end__", update={
                    "messages": [{"role": "assistant",
                                  "content": "抱歉，暂时无法处理您的请求，请稍后重试。"}]})
        else:
            # 简单关键词匹配
            target = _keyword_classify(last_user_msg)

        if "booking" in target:
            return Command(goto="bookkeeper", update={"next_agent": "bookkeeper"})
        elif "analysis" in target:
            return Command(goto="analyst", update={"next_agent": "analyst"})
        else:
            return Command(goto="__end__", update={
                "messages": [{"role": "assistant",
                              "content": "我是记账助手，请问有什么记账或财务分析的问题吗？"}]})

    return supervisor_node


def _keyword_classify(message: str) -> str:
    """关键词匹配分类（无需 LLM 的轻量方案）。"""
    booking_keywords = ["记", "余额", "账户", "卡", "转账", "添加", "记录"]
    analysis_keywords = ["花了", "汇总", "统计", "分析", "趋势", "占比", "多少"]

    for kw in booking_keywords:
        if kw in message:
            return "booking"
    for kw in analysis_keywords:
        if kw in message:
            return "analysis"
    return "other"
