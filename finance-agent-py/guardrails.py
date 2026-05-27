"""Guardrails — Python Agent 三层防护模块。

第一层: Prompt Injection 检测 (输入防护)
第二层: 工具调用审计 (工具防护)
第三层: 输出金额幻觉检测 (输出防护)
"""
import json
import logging
import re
from collections import defaultdict
from decimal import Decimal, InvalidOperation

logger = logging.getLogger(__name__)

# ═══════════════════════════════════════════════════════════════
#  第一层: Prompt Injection 检测
# ═══════════════════════════════════════════════════════════════

_INJECTION_PATTERNS = [
    # 中文：试图覆盖指令
    re.compile(r"忽略.{0,10}(?:以上|之前|所有|前面).{0,10}(?:指令|规则|提示|设定|约束)"),
    re.compile(r"(?:无视|抛弃|放弃|丢掉|不要遵守).{0,10}(?:指令|规则|提示|设定|约束)"),
    # 中文：试图改变角色
    re.compile(r"你现在是.{0,20}(?:角色|身份|助手|机器人|AI)"),
    re.compile(r"(?:扮演|假装|模拟|变成|充当).{0,10}(?:另一个|其他|新的|不同的)"),
    # 中文：试图提取 System Prompt
    re.compile(r"(?:输出|显示|告诉我|重复|打印).{0,10}(?:系统提示|system\s*prompt|初始指令|设定)"),
    # 英文：试图覆盖指令
    re.compile(
        r"(?:ignore|disregard|forget|override|bypass).{0,20}(?:instruction|rule|prompt|directive|constraint)",
        re.IGNORECASE,
    ),
    # 英文：试图改变角色
    re.compile(r"you\s+are\s+(?:now|actually|really).{0,20}", re.IGNORECASE),
    re.compile(r"(?:act|pretend|behave)\s+(?:as|like)\s+", re.IGNORECASE),
    # 英文：试图提取 System Prompt
    re.compile(
        r"(?:print|output|show|repeat|display).{0,15}(?:system\s*prompt|initial\s*instruction)",
        re.IGNORECASE,
    ),
    # 通用：DAN / jailbreak 关键词
    re.compile(r"\bDAN\b|\bjailbreak\b|\bdo\s+anything\s+now\b", re.IGNORECASE),
]

REJECTION_REPLY = "我是财务助手小财，只能处理与个人记账和财务管理相关的问题。请问有什么财务方面的问题我可以帮您？"


def is_prompt_injection(user_message: str) -> bool:
    """检测用户消息是否包含 Prompt Injection 攻击。"""
    if not user_message or not user_message.strip():
        return False
    for pattern in _INJECTION_PATTERNS:
        if pattern.search(user_message):
            truncated = user_message[:100] + "..." if len(user_message) > 100 else user_message
            logger.warning("Prompt Injection 检测命中: pattern=%s, message=%s", pattern.pattern, truncated)
            return True
    return False


# ═══════════════════════════════════════════════════════════════
#  第二层: 工具调用审计
# ═══════════════════════════════════════════════════════════════

_WRITE_TOOLS = {"add_transaction"}
_MAX_WRITE_OPS_PER_SESSION = 5
_MAX_AMOUNT = Decimal("1000000")

# 会话级写操作计数
_write_counts: dict[str, int] = defaultdict(int)


def audit_tool_calls(messages: list, session_user_id: str) -> None:
    """审计 LangGraph Agent 返回的消息列表中的工具调用。

    LangGraph 的返回消息格式:
      - AIMessage: 可能包含 tool_calls 字段 (LLM 决定调用工具)
      - ToolMessage: 工具执行结果
    """
    for msg in messages:
        if not hasattr(msg, "tool_calls"):
            continue
        for tool_call in msg.tool_calls:
            tool_name = tool_call.get("name", "")
            args = tool_call.get("args", {})
            _audit_single_tool_call(tool_name, args, session_user_id)


def _audit_single_tool_call(tool_name: str, args: dict, session_user_id: str) -> None:
    """审计单次工具调用。"""
    # userId 篡改检测
    tool_user_id = args.get("userId")
    if tool_user_id and tool_user_id != session_user_id:
        logger.warning(
            "ToolCallGuardrail: userId 篡改检测! tool=%s, expected=%s, actual=%s",
            tool_name, session_user_id, tool_user_id,
        )

    # 写操作审计
    if tool_name in _WRITE_TOOLS:
        # 金额范围校验
        amount_str = args.get("amount")
        if amount_str is not None:
            try:
                amount = Decimal(str(amount_str))
                if amount <= 0:
                    logger.warning(
                        "ToolCallGuardrail: 金额校验异常! tool=%s, amount=%s (非正数)",
                        tool_name, amount,
                    )
                if amount > _MAX_AMOUNT:
                    logger.warning(
                        "ToolCallGuardrail: 金额校验异常! tool=%s, amount=%s (超过上限 %s)",
                        tool_name, amount, _MAX_AMOUNT,
                    )
            except InvalidOperation:
                logger.debug("ToolCallGuardrail: 金额解析失败: %s", amount_str)

        # 写操作频率监控
        _write_counts[session_user_id] += 1
        if _write_counts[session_user_id] > _MAX_WRITE_OPS_PER_SESSION:
            logger.warning(
                "ToolCallGuardrail: 写操作频率告警! userId=%s, tool=%s, count=%d (上限 %d)",
                session_user_id, tool_name, _write_counts[session_user_id], _MAX_WRITE_OPS_PER_SESSION,
            )


def reset_write_count(session_user_id: str) -> None:
    """重置指定会话的写操作计数。"""
    _write_counts.pop(session_user_id, None)


# ═══════════════════════════════════════════════════════════════
#  第三层: 输出金额幻觉检测
# ═══════════════════════════════════════════════════════════════

_AMOUNT_PATTERN = re.compile(
    r"¥\s?([\d,]+(?:\.\d{1,2})?)"   # ¥12,345.67
    r"|"
    r"([\d,]+(?:\.\d{1,2})?)\s*元"  # 12345.67 元
)


def extract_amounts(text: str) -> list[Decimal]:
    """从文本中提取所有金额值。"""
    if not text:
        return []
    amounts: list[Decimal] = []
    for match in _AMOUNT_PATTERN.finditer(text):
        amount_str = match.group(1) or match.group(2)
        if amount_str:
            try:
                cleaned = amount_str.replace(",", "")
                amounts.append(Decimal(cleaned))
            except InvalidOperation:
                pass
    return amounts


def check_amount_hallucination(reply_text: str, tool_amounts: list[Decimal]) -> bool:
    """检测 LLM 回复中的金额是否与工具返回数据一致。

    返回 True 表示检测到幻觉。
    """
    reply_amounts = extract_amounts(reply_text)
    if not reply_amounts or not tool_amounts:
        return False

    tolerance = Decimal("0.01")
    for reply_amount in reply_amounts:
        match_found = any(
            abs(tool_amount - reply_amount) <= tolerance
            for tool_amount in tool_amounts
        )
        if not match_found:
            logger.warning(
                "OutputGuardrail: 幻觉检测! LLM 回复金额 %s 与工具数据不匹配, 工具数据: %s",
                reply_amount, tool_amounts,
            )
            return True
    return False
