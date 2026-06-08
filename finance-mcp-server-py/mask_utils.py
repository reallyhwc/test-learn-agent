"""日志脱敏工具 — 与 Java LogMaskUtils 对等。"""

import logging
from decimal import Decimal

logger = logging.getLogger(__name__)


def mask_user_id(user_id: str | None) -> str:
    """保留首字符，其余替换为 ***（与 Java LogMaskUtils 对齐）"""
    if not user_id:
        return "(null)"
    return user_id[0] + "***"


def mask_amount(amount: Decimal | float | int | None) -> str:
    """返回 <金额:N位> 格式（与 Java LogMaskUtils 对齐）"""
    if amount is None:
        return "(null)"
    s = str(amount)
    digit_count = sum(1 for ch in s if ch.isdigit())
    return f"<金额:{digit_count}位>"
