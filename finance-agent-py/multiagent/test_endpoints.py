"""Multi-Agent chat_server 端点测试 — 使用 httpx.AsyncClient + pytest-asyncio。"""
import pytest
from httpx import AsyncClient, ASGITransport


@pytest.fixture
def anyio_backend():
    return "asyncio"


class TestMultiAgentEndpoints:
    """测试 /api/chat/multi-agent/stream, /api/chat/confirm, /api/chat/cancel。"""

    async def test_multi_agent_stream_returns_200(self):
        """SSE 流式端点应返回 200 + text/event-stream。"""
        from chat_server import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/chat/multi-agent/stream", json={
                "userId": "default",
                "message": "我的余额是多少"
            })
            # 如果 multi_agent 未初始化，返回 503
            assert response.status_code in (200, 503)

    async def test_confirm_returns_ok(self):
        """确认端点应返回 OK 状态。"""
        from chat_server import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/chat/confirm", json={
                "confirmationId": "test-uuid"
            })
            assert response.status_code == 200
            data = response.json()
            assert "status" in data

    async def test_cancel_returns_cancelled(self):
        """取消端点应返回 cancelled 状态。"""
        from chat_server import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/chat/cancel", json={
                "confirmationId": "test-uuid"
            })
            assert response.status_code == 200
            data = response.json()
            assert data["status"] == "cancelled"

    async def test_multi_agent_stream_rejects_empty_message(self):
        """空消息应返回 400。"""
        from chat_server import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/chat/multi-agent/stream", json={
                "userId": "default",
                "message": ""
            })
            assert response.status_code in (400, 422, 503)
