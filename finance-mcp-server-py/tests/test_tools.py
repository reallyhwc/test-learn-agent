"""MCP Server 集成测试 — 需要 Backend (:8080) 已启动。"""
import json

import pytest
from mcp.client.sse import sse_client
from mcp import ClientSession


@pytest.fixture
async def mcp_session():
    """连接到 MCP Server 并返回已初始化的 ClientSession。"""
    async with sse_client("http://localhost:8083/sse") as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            yield session


@pytest.mark.asyncio
async def test_list_tools(mcp_session):
    """验证 MCP Server 注册了 5 个工具。"""
    result = await mcp_session.list_tools()
    tool_names = [t.name for t in result.tools]
    expected = {
        "query_balance", "list_transactions", "summarize_transactions",
        "add_transaction", "list_accounts",
    }
    assert expected.issubset(set(tool_names)), f"缺少工具: {expected - set(tool_names)}"


@pytest.mark.asyncio
async def test_list_accounts(mcp_session):
    """验证 list_accounts 能够查询到账户数据。"""
    result = await mcp_session.call_tool("list_accounts", {"user_id": "default"})
    assert result.content, "返回结果不应为空"
    text = result.content[0].text if result.content else ""
    data = json.loads(text) if text else []
    assert isinstance(data, list), f"预期列表，实际: {type(data)}"


@pytest.mark.asyncio
async def test_query_balance(mcp_session):
    """验证 query_balance 可查询余额。"""
    result = await mcp_session.call_tool(
        "query_balance", {"user_id": "default", "account_id": 1}
    )
    assert result.content, "返回结果不应为空"


@pytest.mark.asyncio
async def test_list_transactions(mcp_session):
    """验证 list_transactions 能查询交易。"""
    result = await mcp_session.call_tool(
        "list_transactions", {"user_id": "default", "filters": "{}"}
    )
    assert result.content, "返回结果不应为空"


@pytest.mark.asyncio
async def test_summarize_transactions(mcp_session):
    """验证 summarize_transactions 能汇总。"""
    result = await mcp_session.call_tool(
        "summarize_transactions",
        {"user_id": "default", "filters": '{"type":"EXPENSE"}'},
    )
    assert result.content, "返回结果不应为空"


@pytest.mark.asyncio
async def test_add_transaction_validation(mcp_session):
    """验证 add_transaction 参数校验。"""
    # 缺少必填参数应返回错误消息
    result = await mcp_session.call_tool(
        "add_transaction",
        {"user_id": "default", "account_id": 0, "type": "INCOME",
         "amount": 0, "category": "", "sub_category": ""},
    )
    text = result.content[0].text if result.content else ""
    assert "失败" in text, f"应返回校验失败消息，实际: {text}"
