"""日志脱敏工具 — 与 Java LogMaskUtils 对等。"""

import logging
from decimal import Decimal

logger = logging.getLogger(__name__)


def mask_user_id(user_id: str | None) -> str:
    """保留前 3 个字符，其余替换为 ***"""
    if not user_id:
        return "(null)"
    if len(user_id) <= 3:
        return user_id + "***"
    return user_id[:3] + "***"


def mask_amount(amount: Decimal | float | int | None) -> str:
    """返回金额的位数掩码，如 123.45 → ***.** """
    if amount is None:
        return "(null)"
    s = str(amount)
    result_chars = []
    for ch in s:
        if ch.isdigit():
            result_chars.append("*")
        else:
            result_chars.append(ch)
    return "".join(result_chars)
