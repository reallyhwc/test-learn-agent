"""Python Agent 入口 — 初始化 Agent 后启动 uvicorn。"""
import asyncio
import logging

import uvicorn

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)


async def main() -> None:
    import os
    from config_loader import get_agent_config
    from agent import FinanceAgent
    import chat_server

    config = get_agent_config()
    # MCP_PORT 环境变量优先（MCP 切换重启时设置），否则从 config.yaml 读取
    mcp_port = int(os.environ.get("MCP_PORT", 0)) or config["mcp_port"]
    mcp_url = f"http://localhost:{mcp_port}/sse"
    agent_port: int = config.get("agent_port", 8084)
    logger.info("连接 MCP Server: %s", mcp_url)

    agent = FinanceAgent(mcp_sse_url=mcp_url)
    try:
        await agent.initialize()
        chat_server.agent = agent
        logger.info("Agent 初始化完成，启动 HTTP 服务 (port=%d)", agent_port)

        uvi_config = uvicorn.Config(
            "chat_server:app", host="0.0.0.0", port=agent_port, log_level="info"
        )
        server = uvicorn.Server(uvi_config)
        await server.serve()
    finally:
        logger.info("正在关闭 Agent 连接...")
        await agent.close()
        logger.info("Agent 连接已关闭")


if __name__ == "__main__":
    asyncio.run(main())
