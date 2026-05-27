"""LangChain Agent — 绑定 MCP 工具 + DeepSeek LLM。"""
import logging
from collections.abc import AsyncIterator

from langchain_mcp_adapters.tools import load_mcp_tools
from langchain_deepseek import ChatDeepSeek
from langgraph.prebuilt import create_react_agent
from mcp import ClientSession
from mcp.client.sse import sse_client

from config_loader import get_llm_config
from memory_manager import MemoryManager
from system_prompt import build_system_prompt, fetch_account_summary

logger = logging.getLogger(__name__)


class FinanceAgent:
    """封装 LangChain Agent 的创建和调用。"""

    def __init__(self, mcp_sse_url: str = "http://localhost:8083/sse"):
        self.mcp_sse_url = mcp_sse_url
        self._session: ClientSession | None = None
        self._agent = None
        self._model = None
        self._tools: list = []

    async def initialize(self):
        """连接 MCP Server 并创建 Agent。"""
        llm_config = get_llm_config()
        self._model = ChatDeepSeek(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            api_base=llm_config["base_url"],
            temperature=0.1,
        )

        # 连接 MCP Server 并加载工具
        self._read, self._write = await sse_client(self.mcp_sse_url).__aenter__()
        self._session = ClientSession(self._read, self._write)
        await self._session.__aenter__()
        await self._session.initialize()
        self._tools = await load_mcp_tools(self._session)
        logger.info("MCP 工具加载完成: %s", [t.name for t in self._tools])

        self._agent = create_react_agent(self._model, self._tools)
        logger.info("LangChain Agent 初始化完成")

    async def close(self):
        if self._session:
            await self._session.__aexit__(None, None, None)

    async def chat(self, user_id: str, message: str) -> str:
        """同步对话，返回完整响应文本。"""
        memory = MemoryManager(user_id)
        memory.append("user", message)
        account_summary = await fetch_account_summary(user_id)
        system_prompt = build_system_prompt(user_id, memory, account_summary)

        messages = [{"role": "system", "content": system_prompt}]
        for m in memory.get_messages():
            messages.append(m)

        result = await self._agent.ainvoke({"messages": messages})
        # 提取最后一条 AI 消息
        output = ""
        for m in reversed(result.get("messages", [])):
            if hasattr(m, "content") and m.type == "ai":
                output = str(m.content)
                break
        memory.append("assistant", output)
        return output

    async def chat_stream(
        self, user_id: str, message: str
    ) -> AsyncIterator[str]:
        """流式对话，逐 token yield。"""
        memory = MemoryManager(user_id)
        memory.append("user", message)
        account_summary = await fetch_account_summary(user_id)
        system_prompt = build_system_prompt(user_id, memory, account_summary)

        messages = [{"role": "system", "content": system_prompt}]
        for m in memory.get_messages():
            messages.append(m)

        full_response: list[str] = []
        async for event in self._agent.astream_events(
            {"messages": messages}, version="v2"
        ):
            kind = event.get("event", "")
            if kind == "on_chat_model_stream":
                chunk = event["data"]["chunk"]
                if hasattr(chunk, "content") and chunk.content:
                    token = str(chunk.content)
                    full_response.append(token)
                    yield token

        memory.append("assistant", "".join(full_response))
