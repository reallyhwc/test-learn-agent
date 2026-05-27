"""system_prompt 单元测试 — prompt 构建、账户摘要格式化。"""
from unittest.mock import MagicMock

import pytest

from system_prompt import build_system_prompt, _format_account_summary, _balance_of


def test_build_system_prompt_contains_user_id():
    """构建的 prompt 应包含用户 ID。"""
    mock_memory = MagicMock()
    mock_memory.count.return_value = 0

    prompt = build_system_prompt("test-user", mock_memory, "")
    assert "test-user" in prompt
    assert "userId" in prompt.lower() or "用户ID" in prompt


def test_build_system_prompt_contains_date():
    """构建的 prompt 应包含当前日期。"""
    from datetime import date
    mock_memory = MagicMock()
    mock_memory.count.return_value = 0

    prompt = build_system_prompt("default", mock_memory, "")
    assert date.today().isoformat() in prompt


def test_build_system_prompt_with_memory_count():
    """有记忆时应显示记忆数量。"""
    mock_memory = MagicMock()
    mock_memory.count.return_value = 5

    prompt = build_system_prompt("default", mock_memory, "")
    assert "5 条" in prompt


def test_build_system_prompt_includes_account_summary():
    """账户摘要应被注入到 prompt 中。"""
    mock_memory = MagicMock()
    mock_memory.count.return_value = 0
    summary = "**用户上下文**: 测试账户摘要\n"

    prompt = build_system_prompt("default", mock_memory, summary)
    assert "测试账户摘要" in prompt


def test_format_account_summary_empty():
    """空账户列表应返回'暂无账户'。"""
    result = _format_account_summary([])
    assert "暂无账户" in result


def test_format_account_summary_single_account():
    """单个账户应显示名称、类型和余额。"""
    accounts = [{"id": 1, "name": "工商银行", "type": "BANK", "balance": 15000.50}]
    result = _format_account_summary(accounts)
    assert "工商银行" in result
    assert "BANK" in result
    assert "15,000.50" in result


def test_format_account_summary_multiple_accounts_sorted():
    """多个账户应按余额降序排列。"""
    accounts = [
        {"id": 1, "name": "低余额", "type": "CASH", "balance": 100},
        {"id": 2, "name": "高余额", "type": "BANK", "balance": 50000},
        {"id": 3, "name": "中余额", "type": "CARD", "balance": 5000},
    ]
    result = _format_account_summary(accounts)
    # 高余额应出现在前面
    high_idx = result.index("高余额")
    mid_idx = result.index("中余额")
    low_idx = result.index("低余额")
    assert high_idx < mid_idx < low_idx


def test_format_account_summary_more_than_five():
    """超过5个账户时应显示'另有 N 个账户'。"""
    accounts = [
        {"id": i, "name": f"账户{i}", "type": "BANK", "balance": 1000 * i}
        for i in range(1, 8)
    ]
    result = _format_account_summary(accounts)
    assert "另有 2 个账户" in result


def test_balance_of_none():
    """balance 为 None 时应返回 0.0。"""
    assert _balance_of({"balance": None}) == 0.0


def test_balance_of_invalid():
    """balance 为无效值时应返回 0.0。"""
    assert _balance_of({"balance": "invalid"}) == 0.0
    assert _balance_of({}) == 0.0
