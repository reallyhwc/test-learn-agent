"""HITL 写操作闸门 — LangGraph interrupt 集成。

在 create_react_agent 挂载工具之前，把写操作工具（add_transaction）包装一层：
执行前通过 langgraph `interrupt()` 暂停，将确认请求交给上层；用户 confirm 后
用 resume 恢复执行真实工具；cancel 则抛出取消异常终止。

与 Java 栈 advisor「识别写操作 → pause」语义对齐：这里 pause 由 interrupt 承担。

注意：完整 interrupt/resume 需要 Python 3.10+ 的 LangGraph 运行时（create_react_agent
需在 chat 调用侧响应 interrupt 输出，并通过 Command(resume=...) 恢复）。
本模块提供纯逻辑层（哪些工具需确认、如何构造确认 payload），可独立单测。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)

WRITE_TOOLS = {"add_transaction"}


@dataclass
class ConfirmationPayload:
    """interrupt 挂起时向用户抛出的确认请求。"""

    tool_name: str
    parameters: dict[str, Any]
    description: str = ""


def is_write_tool(tool_name: str) -> bool:
    """判断工具是否需要 HITL 确认。"""
    return tool_name in WRITE_TOOLS


def build_confirmation_payload(tool_name: str, args: dict[str, Any]) -> ConfirmationPayload:
    """根据写工具名与参数构造确认 payload。"""
    description = {"add_transaction": "记一笔交易"}.get(tool_name, tool_name)
    return ConfirmationPayload(tool_name=tool_name, parameters=args, description=description)


def make_confirming_tool(raw_tool, interrupt_fn: Callable[[dict[str, Any]], Any]):
    """包装写工具：执行前调用 interrupt_fn 暂停，返回决策后再执行。

    Args:
        raw_tool: 原始 MCP 工具（可调用对象，通常有 .ainvoke(args)/invoke(args)）。
        interrupt_fn: 暂停回调，签名 (payload: dict) -> decision: dict，
                      返回 ``{"action": "confirm", "modified_params": {...}}`` 或
                      ``{"action": "cancel"}``。生产环境传 langgraph.types.interrupt。
    """

    async def confirming_tool(**args):
        payload = build_confirmation_payload(raw_tool.name, args)
        decision = interrupt_fn({
            "type": "confirmation_required",
            "tool_name": payload.tool_name,
            "parameters": payload.parameters,
            "description": payload.description,
        })
        if decision is None or decision.get("action") == "cancel":
            logger.info("HITL 取消写操作: tool=%s", payload.tool_name)
            raise CancelledToolCall(payload.tool_name)
        if decision.get("modified_params"):
            args = dict(args)
            args.update(decision["modified_params"])
        if hasattr(raw_tool, "ainvoke"):
            return await raw_tool.ainvoke(args)
        return await raw_tool(args)

    # 复制工具元数据，保证 create_react_agent 能识别 name/description
    confirming_tool.__name__ = getattr(raw_tool, "name", raw_tool.__name__ if hasattr(raw_tool, "__name__") else "tool")
    if hasattr(raw_tool, "description"):
        confirming_tool.__doc__ = raw_tool.description
    confirming_tool.name = getattr(raw_tool, "name", confirming_tool.__name__)
    confirming_tool.description = getattr(raw_tool, "description", "")
    return confirming_tool


class CancelledToolCall(Exception):
    """用户取消写操作时抛出，终止本次工具执行。"""

    def __init__(self, tool_name: str) -> None:
        super().__init__(f"写操作已取消: {tool_name}")
        self.tool_name = tool_name


def wrap_write_tools(tools: list, interrupt_fn: Callable[[dict[str, Any]], Any]) -> list:
    """对工具列表中的写操作工具应用确认闸门包装，其余原样返回。"""
    return [
        make_confirming_tool(t, interrupt_fn) if is_write_tool(getattr(t, "name", "")) else t
        for t in tools
    ]
