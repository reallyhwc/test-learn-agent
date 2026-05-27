"""MemoryManager 单元测试 — CRUD、滚动淘汰、持久化、多用户隔离。"""
import json
from pathlib import Path

import pytest

from memory_manager import MemoryManager, MAX_MESSAGES, _cache


@pytest.fixture(autouse=True)
def isolate_memory(tmp_path, monkeypatch):
    """每个测试使用独立的临时目录，避免污染真实数据。"""
    monkeypatch.setattr("memory_manager.DATA_DIR", tmp_path)
    _cache.clear()
    yield
    _cache.clear()


def test_append_and_get():
    """追加消息后应能正确读取。"""
    mem = MemoryManager("test-user")
    mem.append("user", "你好")
    mem.append("assistant", "你好！")
    messages = mem.get_messages()
    assert len(messages) == 2
    assert messages[0] == {"role": "user", "content": "你好"}
    assert messages[1] == {"role": "assistant", "content": "你好！"}


def test_count():
    """count 应返回正确的消息数量。"""
    mem = MemoryManager("test-user")
    assert mem.count() == 0
    mem.append("user", "消息1")
    mem.append("assistant", "消息2")
    assert mem.count() == 2


def test_clear():
    """清除后应无消息且文件被删除。"""
    mem = MemoryManager("test-user")
    mem.append("user", "临时消息")
    mem.clear()
    assert mem.count() == 0
    assert mem.get_messages() == []


def test_rolling_eviction():
    """超过 MAX_MESSAGES 条时应滚动淘汰旧消息。"""
    mem = MemoryManager("test-user")
    for i in range(MAX_MESSAGES + 5):
        mem.append("user", f"消息{i}")

    messages = mem.get_messages()
    assert len(messages) == MAX_MESSAGES
    # 最早的 5 条被淘汰，第一条应是 "消息5"
    assert messages[0]["content"] == "消息5"


def test_json_file_persistence(tmp_path, monkeypatch):
    """消息应被持久化到 JSON 文件。"""
    monkeypatch.setattr("memory_manager.DATA_DIR", tmp_path)
    _cache.clear()

    mem = MemoryManager("persist-user")
    mem.append("user", "持久化测试")

    # 清除缓存，强制从文件读取
    _cache.clear()

    mem2 = MemoryManager("persist-user")
    messages = mem2.get_messages()
    assert len(messages) == 1
    assert messages[0]["content"] == "持久化测试"


def test_multi_user_isolation():
    """不同用户的记忆应相互隔离。"""
    mem_a = MemoryManager("user-a")
    mem_b = MemoryManager("user-b")

    mem_a.append("user", "用户A的消息")
    mem_b.append("user", "用户B的消息")

    assert mem_a.count() == 1
    assert mem_b.count() == 1
    assert mem_a.get_messages()[0]["content"] == "用户A的消息"
    assert mem_b.get_messages()[0]["content"] == "用户B的消息"


def test_invalid_role_raises():
    """无效的 role 应抛出 ValueError。"""
    mem = MemoryManager("test-user")
    with pytest.raises(ValueError, match="无效的 role"):
        mem.append("invalid_role", "内容")


def test_empty_content_skipped():
    """空内容消息应被跳过。"""
    mem = MemoryManager("test-user")
    mem.append("user", "")
    mem.append("user", "   ")
    assert mem.count() == 0
