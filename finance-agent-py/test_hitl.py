"""hitl.py 状态机 + 编排器单元测试（Python 副栈对等实现）。

语义与 Java 栈 PendingConfirmationStoreStateMachineTest / HitlOrchestratorTest 对齐。
"""
import pytest

from hitl import (
    Lookup,
    PendingConfirmationStore,
    Status,
    HitlOrchestrator,
)


@pytest.fixture
def store():
    return PendingConfirmationStore()


async def _execute_stub(call, modified_params=None):
    """记录落库副作用并返回结果。"""
    _execute_stub.calls.append((call, modified_params))
    return {"id": 1}


@pytest.fixture
def orchestrator(store):
    _execute_stub.calls = []
    return HitlOrchestrator(store, _execute_stub)


class TestPendingConfirmationStore:

    async def test_save_starts_in_pending(self, store):
        cid = await store.save("add_transaction", {"amount": 30}, "user")
        lookup, call = await store.lookup(cid)
        assert lookup == Lookup.FOUND
        assert call.status == Status.PENDING
        assert call.tool_name == "add_transaction"

    async def test_transition_pending_to_executing(self, store):
        cid = await store.save("add_transaction", {"amount": 30}, "user")
        won = await store.transition(cid, Status.PENDING, Status.EXECUTING)
        assert won is True
        lookup, call = await store.lookup(cid)
        assert call.status == Status.EXECUTING

    async def test_transition_executing_to_done(self, store):
        cid = await store.save("add_transaction", {"amount": 30}, "user")
        await store.transition(cid, Status.PENDING, Status.EXECUTING)
        won = await store.transition(cid, Status.EXECUTING, Status.DONE)
        assert won is True
        _, call = await store.lookup(cid)
        assert call.status == Status.DONE

    async def test_transition_pending_to_cancelled(self, store):
        cid = await store.save("add_transaction", {"amount": 30}, "user")
        won = await store.transition(cid, Status.PENDING, Status.CANCELLED)
        assert won is True
        _, call = await store.lookup(cid)
        assert call.status == Status.CANCELLED

    async def test_transition_fails_when_status_mismatch(self, store):
        cid = await store.save("add_transaction", {"amount": 30}, "user")
        assert await store.transition(cid, Status.PENDING, Status.EXECUTING) is True
        # 第二次期望 PENDING 已不成立
        assert await store.transition(cid, Status.PENDING, Status.EXECUTING) is False

    async def test_expose_expired_state(self, store):
        cid = await store.save("add_transaction", {"amount": 30}, "user")
        await store.force_expire(cid)
        lookup, call = await store.lookup(cid)
        assert lookup == Lookup.EXPIRED
        assert call is None

    async def test_not_found_for_unknown_id(self, store):
        lookup, call = await store.lookup("nonexistent")
        assert lookup == Lookup.NOT_FOUND

    async def test_confirm_not_allowed_after_cancel(self, store):
        cid = await store.save("add_transaction", {"amount": 30}, "user")
        assert await store.transition(cid, Status.PENDING, Status.CANCELLED) is True
        assert await store.transition(cid, Status.PENDING, Status.EXECUTING) is False


class TestHitlOrchestrator:

    async def test_identify_write_tool(self):
        assert HitlOrchestrator.is_write_tool("add_transaction") is True
        assert HitlOrchestrator.is_write_tool("query_balance") is False

    async def test_pause_then_confirm_then_execute(self, orchestrator):
        cid = await orchestrator.pause_for_confirmation(
            "add_transaction", {"amount": 30, "category": "餐饮", "type": "EXPENSE"},
            "user-1", "session-1",
        )
        assert cid
        assert len(_execute_stub.calls) == 0  # 暂停时不落库

        outcome = await orchestrator.confirm(cid)
        assert outcome["status"] == "EXECUTED"
        assert len(_execute_stub.calls) == 1  # 确认后落库一次

        _, call = await orchestrator.store.lookup(cid)
        assert call.status == Status.DONE

    async def test_cancel_without_executing(self, orchestrator):
        cid = await orchestrator.pause_for_confirmation(
            "add_transaction", {"amount": 30}, "user-1",
        )
        outcome = await orchestrator.cancel(cid)
        assert outcome["status"] == "CANCELLED"
        assert len(_execute_stub.calls) == 0

        # 取消后确认失败
        confirm = await orchestrator.confirm(cid)
        assert confirm["status"] == "NOT_PENDING"
        assert len(_execute_stub.calls) == 0

    async def test_not_found_for_unknown_confirm(self, orchestrator):
        outcome = await orchestrator.confirm("nonexistent")
        assert outcome["status"] == "NOT_FOUND"

    async def test_expired_confirm(self, orchestrator):
        cid = await orchestrator.pause_for_confirmation("add_transaction", {"amount": 30}, "user-1")
        await orchestrator.store.force_expire(cid)
        outcome = await orchestrator.confirm(cid)
        assert outcome["status"] == "EXPIRED"
        assert len(_execute_stub.calls) == 0

    async def test_only_one_confirm_executes(self, orchestrator):
        cid = await orchestrator.pause_for_confirmation("add_transaction", {"amount": 30}, "user-1")
        first = await orchestrator.confirm(cid)
        second = await orchestrator.confirm(cid)
        assert first["status"] == "EXECUTED"
        assert second["status"] == "NOT_PENDING"
        assert len(_execute_stub.calls) == 1

    async def test_apply_modified_params(self, orchestrator):
        cid = await orchestrator.pause_for_confirmation(
            "add_transaction", {"amount": 30, "category": "餐饮", "type": "EXPENSE"}, "user-1",
        )
        outcome = await orchestrator.confirm(cid, {"amount": 15, "category": "餐饮", "type": "EXPENSE"})
        assert outcome["status"] == "EXECUTED"
        # 落库参数应为修改后的值
        call, params = _execute_stub.calls[0]
        assert params["amount"] == 15
