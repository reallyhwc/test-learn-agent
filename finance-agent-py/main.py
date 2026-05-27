"""Python Agent 入口 — 初始化 Agent 后启动 uvicorn。"""
import asyncio
import logging

import uvicorn

logger = logging.getLogger(__name__)


async def main():
    from config_loader import get_agent_config
    from agent import FinanceAgent
    import chat_server

    config = get_agent_config()
    mcp_url = f"http://localhost:{config['mcp_port']}/sse"
    logger.info("连接 MCP Server: %s", mcp_url)

    agent = FinanceAgent(mcp_sse_url=mcp_url)
    await agent.initialize()
    chat_server.agent = agent
    logger.info("Agent 初始化完成，启动 HTTP 服务")

    config = uvicorn.Config(
        "chat_server:app", host="0.0.0.0", port=8084, log_level="info"
    )
    server = uvicorn.Server(config)
    await server.serve()


if __name__ == "__main__":
    asyncio.run(main())
