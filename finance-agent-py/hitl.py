"""HITL 6 态状态机 + 编排器 — Python 副栈对等实现。

与 Java 栈 `PendingConfirmationStore` + `HitlOrchestrator` 语义对齐：

状态机：
    PENDING ──(confirm CAS)──► EXECUTING ──(同步收尾)──► DONE
            ├──(cancel CAS)──► CANCELLED
            └──(TTL 超时)────► EXPIRED（显式暴露）

核心不变量：
- CAS 原子抢占：confirm/cancel 竞争时有且仅有一次成功（asyncio.Lock 保证）。
- 状态与存储解耦：状态是 PendingCall.status 字段，物理清理不参与正确性判断。
- EXPIRED 显式暴露：lookup 区分 FOUND / NOT_FOUND / EXPIRED。
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any, Optional

logger = logging.getLogger(__name__)

TTL_SECONDS = 60


class Status(str, Enum):
    PENDING = "PENDING"
    EXECUTING = "EXECUTING"
    DONE = "DONE"
    CANCELLED = "CANCELLED"
    EXPIRED = "EXPIRED"


class Lookup(str, Enum):
    FOUND = "FOUND"
    NOT_FOUND = "NOT_FOUND"
    EXPIRED = "EXPIRED"


WRITE_TOOLS = {"add_transaction"}


@dataclass
class PendingCall:
    confirmation_id: str
    tool_name: str
    parameters: dict[str, Any]
    user_id: str
    session_id: Optional[str]
    expires_at: datetime
    status: Status = Status.PENDING


class PendingConfirmationStore:
    """6 态 + CAS 抢占的待确认操作状态机。"""

    def __init__(self) -> None:
        self._store: dict[str, PendingCall] = {}
        self._lock: Optional[asyncio.Lock] = None  # 惰性初始化，避免跨事件循环复用

    def _get_lock(self) -> asyncio.Lock:
        if self._lock is None:
            self._lock = asyncio.Lock()
        return self._lock

    async def save(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
        session_id: Optional[str] = None,
    ) -> str:
        confirmation_id = str(uuid.uuid4())
        call = PendingCall(
            confirmation_id=confirmation_id,
            tool_name=tool_name,
            parameters=params,
            user_id=user_id,
            session_id=session_id,
            expires_at=datetime.now(timezone.utc) + timedelta(seconds=TTL_SECONDS),
            status=Status.PENDING,
        )
        async with self._get_lock():
            self._store[confirmation_id] = call
        return confirmation_id

    async def lookup(self, confirmation_id: str) -> tuple[Lookup, Optional[PendingCall]]:
        async with self._get_lock():
            call = self._store.get(confirmation_id)
            if call is None:
                return Lookup.NOT_FOUND, None
            if datetime.now(timezone.utc) > call.expires_at:
                self._store[confirmation_id] = replace(call, status=Status.EXPIRED)
                return Lookup.EXPIRED, None
            return Lookup.FOUND, call

    async def transition(
        self, confirmation_id: str, expected: Status, target: Status
    ) -> bool:
        """CAS 原子抢占：仅当当前状态 == expected（且未过期）时推进到 target。"""
        async with self._get_lock():
            call = self._store.get(confirmation_id)
            if call is None:
                return False
            if datetime.now(timezone.utc) > call.expires_at:
                self._store[confirmation_id] = replace(call, status=Status.EXPIRED)
                return False
            if call.status == expected:
                self._store[confirmation_id] = replace(call, status=target)
                return True
            return False

    async def remove(self, confirmation_id: str) -> None:
        async with self._get_lock():
            self._store.pop(confirmation_id, None)

    async def force_expire(self, confirmation_id: str) -> None:
        async with self._get_lock():
            call = self._store.get(confirmation_id)
            if call is not None:
                self._store[confirmation_id] = replace(
                    call, expires_at=datetime.now(timezone.utc) - timedelta(seconds=1)
                )

    async def evict_expired(self) -> None:
        now = datetime.now(timezone.utc)
        async with self._get_lock():
            for cid in list(self._store.keys()):
                call = self._store[cid]
                if now > call.expires_at or call.status in (Status.DONE, Status.CANCELLED):
                    self._store.pop(cid, None)


class HitlOrchestrator:
    """封装「写操作 → 待确认 → 确认执行 → 落库」闭环。"""

    def __init__(self, store: PendingConfirmationStore, execute_fn) -> None:
        self.store = store
        # execute_fn: async (PendingCall, modified_params) -> Any；注入便于测试时 mock 落库
        self._execute_fn = execute_fn

    @staticmethod
    def is_write_tool(tool_name: str) -> bool:
        return tool_name in WRITE_TOOLS

    async def pause_for_confirmation(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
        session_id: Optional[str] = None,
    ) -> str:
        return await self.store.save(tool_name, params, user_id, session_id)

    async def confirm(
        self, confirmation_id: str, modified_params: Optional[dict[str, Any]] = None
    ) -> dict[str, Any]:
        lookup, _ = await self.store.lookup(confirmation_id)
        if lookup == Lookup.NOT_FOUND:
            return {"status": "NOT_FOUND", "message": "确认请求不存在"}
        if lookup == Lookup.EXPIRED:
            return {"status": "EXPIRED", "message": "确认请求已过期"}

        won = await self.store.transition(confirmation_id, Status.PENDING, Status.EXECUTING)
        if not won:
            return {"status": "NOT_PENDING", "message": "该操作已被确认或取消"}

        _, call = await self.store.lookup(confirmation_id)
        try:
            result = await self._execute_fn(call, modified_params)
            await self.store.transition(confirmation_id, Status.EXECUTING, Status.DONE)
            return {"status": "EXECUTED", "message": "操作已确认执行", "result": result}
        except Exception as e:  # noqa: BLE001
            logger.warning("HITL 执行工具失败 confirmationId=%s: %s", confirmation_id, e)
            await self.store.transition(confirmation_id, Status.EXECUTING, Status.CANCELLED)
            return {"status": "NOT_PENDING", "message": f"工具执行失败: {e}"}

    async def cancel(self, confirmation_id: str) -> dict[str, Any]:
        lookup, _ = await self.store.lookup(confirmation_id)
        if lookup == Lookup.NOT_FOUND:
            return {"status": "NOT_FOUND", "message": "确认请求不存在"}
        if lookup == Lookup.EXPIRED:
            return {"status": "EXPIRED", "message": "确认请求已过期"}
        won = await self.store.transition(confirmation_id, Status.PENDING, Status.CANCELLED)
        if not won:
            return {"status": "NOT_PENDING", "message": "该操作已被确认或取消"}
        return {"status": "CANCELLED", "message": "操作已取消"}
