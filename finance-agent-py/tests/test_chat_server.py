"""Agent 集成测试 — 使用 mocked Agent，不需要 MCP Server 和 Backend 在线。"""
import pytest
from unittest.mock import AsyncMock, patch


@pytest.fixture(scope="module")
def app():
    """创建 FastAPI app，mock FinanceAgent.initialize/close 避免连接 MCP。"""
    with patch.object(
        __import__("agent").FinanceAgent, "initialize", new_callable=AsyncMock
    ) as mock_init:
        with patch.object(
            __import__("agent").FinanceAgent, "close", new_callable=AsyncMock
        ):
            from chat_server import app as _app
            yield _app


@pytest.fixture
async def async_client(app):
    """创建 ASGI 测试客户端（ASGITransport 直接调用 app，不启动真实的 TCP server）。"""
    from httpx import ASGITransport, AsyncClient
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client


@pytest.mark.asyncio
async def test_config_endpoint(async_client):
    """验证 /api/config 返回正确的字段。"""
    resp = await async_client.get("/api/config")
    assert resp.status_code == 200
    data = resp.json()
    assert "agent" in data
    assert "mcp" in data
    assert "available_agents" in data
    assert isinstance(data["available_agents"], list)


@pytest.mark.asyncio
async def test_health_endpoint(async_client):
    """验证健康检查端点。"""
    resp = await async_client.get("/actuator/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "UP"}


@pytest.mark.asyncio
async def test_chat_empty_message(async_client):
    """验证空消息被拒绝。

    _validate_message 抛出 ValueError，Starlette error middleware 会发送
    500 响应后重新抛出异常。ASGITransport 可能收到响应或直接传播异常，
    两种行为都表示消息被拒绝，符合预期。
    """
    rejected = False
    try:
        resp = await async_client.post("/api/chat", json={"userId": "default", "message": ""})
        # 如果收到响应，则状态码应为错误码
        rejected = resp.status_code >= 400
    except ValueError:
        # ASGITransport 直接传播 ValueError —— 空消息被拒绝
        rejected = True
    assert rejected, "空消息应被拒绝"


@pytest.mark.asyncio
async def test_memory_clear(async_client):
    """验证清除记忆端点。"""
    resp = await async_client.delete("/api/memory?user_id=test_user")
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is True
