"""LangChain Agent — 绑定 MCP 工具 + OpenAI-compatible LLM。"""
import asyncio
import logging
from collections.abc import AsyncIterator

from langchain_mcp_adapters.tools import load_mcp_tools
from langchain_openai import ChatOpenAI
from langgraph.prebuilt import create_react_agent
from mcp import ClientSession
from mcp.client.sse import sse_client

from config_loader import get_llm_config
from guardrails import REJECTION_REPLY, audit_tool_calls, check_amount_hallucination, extract_amounts, is_prompt_injection
from memory_manager import MemoryManager
from system_prompt import build_system_prompt, fetch_account_summary

logger = logging.getLogger(__name__)


class FinanceAgent:
    """封装 LangChain Agent 的创建和调用。"""

    def __init__(self, mcp_sse_url: str = "http://localhost:8083/sse"):
        self.mcp_sse_url = mcp_sse_url
        self._session: ClientSession | None = None
        self._sse_context = None
        self._agent = None
        self._model = None
        self._tools: list = []

    async def initialize(self):
        """连接 MCP Server 并创建 Agent。"""
        llm_config = get_llm_config()
        # ChatOpenAI 需要 base_url 以 /v1 结尾；Spring AI 则会自动追加 /v1
        # 统一处理：.env 中不带 /v1，Python 侧自动补上
        base_url = llm_config["base_url"].rstrip("/")
        if not base_url.endswith("/v1"):
            base_url += "/v1"
        self._model = ChatOpenAI(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            base_url=base_url,
            temperature=0.1,
        )
        await self._connect_mcp()
        self._agent = create_react_agent(self._model, self._tools)
        logger.info("LangChain Agent 初始化完成")

    async def _connect_mcp(self):
        """建立 MCP SSE 连接并加载工具列表。"""
        try:
            self._sse_context = sse_client(self.mcp_sse_url)
            self._read, self._write = await self._sse_context.__aenter__()
            self._session = ClientSession(self._read, self._write)
            await self._session.__aenter__()
            await self._session.initialize()
            self._tools = await load_mcp_tools(self._session)
            logger.info("MCP 工具加载完成: %s", [t.name for t in self._tools])
        except Exception:
            logger.error("MCP 连接失败 (%s)，执行清理", self.mcp_sse_url)
            await self.close()
            raise

    async def close(self):
        """关闭 MCP SSE 连接（仅在进程退出时调用）。"""
        if self._session:
            try:
                await self._session.__aexit__(None, None, None)
            except Exception as e:
                logger.warning("关闭 MCP session 异常: %s", e)
        if self._sse_context:
            try:
                await self._sse_context.__aexit__(None, None, None)
            except Exception as e:
                logger.warning("关闭 SSE context 异常: %s", e)

    async def chat(self, user_id: str, message: str) -> str:
        """同步对话，返回完整响应文本。"""
        # 第一层防护: Prompt Injection 检测
        if is_prompt_injection(message):
            logger.warning("InputGuardrail 拦截: userId=%s", user_id)
            return REJECTION_REPLY

        memory = MemoryManager(user_id)
        account_summary = await fetch_account_summary(user_id)
        system_prompt = build_system_prompt(user_id, memory, account_summary)

        messages = [{"role": "system", "content": system_prompt}]
        for m in memory.get_messages():
            messages.append(m)
        messages.append({"role": "user", "content": message})

        try:
            result = await asyncio.wait_for(
                self._agent.ainvoke({"messages": messages}),
                timeout=60,
            )
        except asyncio.TimeoutError:
            logger.warning("Agent 调用超时: userId=%s", user_id)
            return "AI 响应超时，请简化问题或稍后重试"

        # 第二层防护: 工具调用审计
        audit_tool_calls(result.get("messages", []), user_id)

        # 提取工具返回中的金额用于第三层幻觉检测
        tool_amounts: list = []
        for m in result.get("messages", []):
            if hasattr(m, "type") and m.type == "tool":
                tool_amounts.extend(extract_amounts(str(m.content)))

        output = ""
        for m in reversed(result.get("messages", [])):
            if hasattr(m, "content") and m.type == "ai":
                output = str(m.content)
                break

        # 第三层防护: 金额幻觉检测
        if check_amount_hallucination(output, tool_amounts):
            logger.warning("OutputGuardrail: 幻觉检测触发 userId=%s", user_id)

        memory.append("user", message)
        memory.append("assistant", output)
        return output

    async def chat_stream(
        self, user_id: str, message: str
    ) -> AsyncIterator[dict]:
        """流式对话，逐 token yield dict —— 与 Java 栈三通道 (data/thinking/error) 对齐。"""
        # 第一层防护: Prompt Injection 检测
        if is_prompt_injection(message):
            logger.warning("InputGuardrail 拦截(stream): userId=%s", user_id)
            yield {"data": REJECTION_REPLY}
            return

        memory = MemoryManager(user_id)
        account_summary = await fetch_account_summary(user_id)
        system_prompt = build_system_prompt(user_id, memory, account_summary)

        messages = [{"role": "system", "content": system_prompt}]
        for m in memory.get_messages():
            messages.append(m)
        messages.append({"role": "user", "content": message})

        full_response: list[str] = []
        try:
            async with asyncio.timeout(120):
                async for event in self._agent.astream_events(
                    {"messages": messages}, version="v2"
                ):
                    kind = event.get("event", "")
                    if kind == "on_tool_start":
                        tool_name = event.get("name", "unknown")
                        yield {"event": "thinking", "data": f"正在调用 {tool_name}..."}
                    elif kind == "on_chat_model_stream":
                        chunk = event["data"]["chunk"]
                        if hasattr(chunk, "content") and chunk.content:
                            token = str(chunk.content)
                            full_response.append(token)
                            yield {"data": token}
        except asyncio.TimeoutError:
            logger.warning("流式超时: userId=%s", user_id)
            yield {"event": "error", "data": "AI 响应超时，请简化问题或稍后重试"}

        memory.append("user", message)
        full_text = "".join(full_response)

        # 第三层防护: 金额幻觉检测
        # 注: 流式场景下不易提取工具返回的金额，故 tool_amounts 传空列表。
        # check_amount_hallucination 的 short-circuit 逻辑会跳过检测（无副作用）。
        # chat() 同步路径已完整实现；此处保留调用点为未来流式工具消息提取预留。
        if full_text and check_amount_hallucination(full_text, []):
            logger.warning("OutputGuardrail(stream): 幻觉检测触发 userId=%s", user_id)

        memory.append("assistant", full_text)


class MultiAgentFinanceAgent:
    """Multi-Agent 版 FinanceAgent — Supervisor + Bookkeeper + Analyst StateGraph。"""

    def __init__(self, mcp_sse_url: str = "http://localhost:8083/sse"):
        self.mcp_sse_url = mcp_sse_url
        self._graph = None
        self._supervisor_llm = None
        self._bookkeeper_agent = None
        self._analyst_agent = None
        self._session = None
        self._sse_context = None
        self._initialized = False

    async def initialize(self):
        """初始化 3 组 LLM + MCP 连接，构建 StateGraph。"""
        from langchain_mcp_adapters.tools import load_mcp_tools
        from langchain_openai import ChatOpenAI
        from langgraph.prebuilt import create_react_agent
        from mcp import ClientSession
        from mcp.client.sse import sse_client

        from multiagent.graph_builder import build_multi_agent_graph

        llm_config = get_llm_config()
        base_url = llm_config["base_url"].rstrip("/")
        if not base_url.endswith("/v1"):
            base_url += "/v1"

        # --- 连接 MCP Server ---
        self._sse_context = sse_client(self.mcp_sse_url)
        read, write = await self._sse_context.__aenter__()
        self._session = ClientSession(read, write)
        await self._session.__aenter__()
        await self._session.initialize()
        all_tools = await load_mcp_tools(self._session)

        # --- Supervisor LLM（不绑工具，只做分类）---
        self._supervisor_llm = ChatOpenAI(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            base_url=base_url,
            temperature=0.0,
        )

        # --- Bookkeeper Agent（记账工具子集）---
        bookkeeper_tools = [t for t in all_tools
                            if t.name in ("add_transaction", "list_accounts", "query_balance")]
        bookkeeper_llm = ChatOpenAI(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            base_url=base_url,
            temperature=0.1,
        )
        self._bookkeeper_agent = create_react_agent(bookkeeper_llm, bookkeeper_tools)

        # --- Analyst Agent（分析工具子集）---
        analyst_tools = [t for t in all_tools
                         if t.name in ("list_transactions", "summarize_transactions")]
        analyst_llm = ChatOpenAI(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            base_url=base_url,
            temperature=0.1,
        )
        self._analyst_agent = create_react_agent(analyst_llm, analyst_tools)

        # --- 构建 StateGraph ---
        from audit.callbacks import LlmAuditCallback

        def audit_callback_factory(
            trace_id, agent_name, call_type, user_id
        ):
            return LlmAuditCallback(
                trace_id=trace_id,
                agent_name=agent_name,
                call_type=call_type,
                user_id=user_id,
            )

        self._graph = build_multi_agent_graph(
            self._supervisor_llm, self._bookkeeper_agent, self._analyst_agent,
            audit_callback_factory=audit_callback_factory)

        self._initialized = True
        logger.info("Multi-Agent StateGraph 初始化完成")

    async def chat(self, user_id: str, message: str) -> str:
        """同步对话。"""
        import uuid
        from multiagent.state import MultiAgentState

        if is_prompt_injection(message):
            return REJECTION_REPLY

        trace_id = str(uuid.uuid4())
        initial_state = MultiAgentState.create(
            messages=[{"role": "user", "content": message}],
            trace_id=trace_id, user_id=user_id)

        try:
            result = await asyncio.wait_for(
                self._graph.ainvoke(initial_state),
                timeout=60,
            )
        except asyncio.TimeoutError:
            return "AI 响应超时，请简化问题或稍后重试"

        messages = result.get("messages", [])
        for m in reversed(messages):
            if isinstance(m, dict) and m.get("role") == "assistant" and m.get("content"):
                return str(m.get("content", ""))
        return "无法处理该请求"

    async def chat_stream(self, user_id: str, message: str):
        """流式对话 — 逐节点 yield SSE 事件 dict。"""
        import uuid
        from multiagent.state import MultiAgentState

        if is_prompt_injection(message):
            yield {"data": REJECTION_REPLY}
            return

        trace_id = str(uuid.uuid4())
        initial_state = MultiAgentState.create(
            messages=[{"role": "user", "content": message}],
            trace_id=trace_id, user_id=user_id)

        try:
            async with asyncio.timeout(120):
                async for event in self._graph.astream_events(
                    initial_state, version="v2"
                ):
                    kind = event.get("event", "")
                    node_name = event.get("name", "")

                    if kind == "on_chain_start":
                        if node_name in ("bookkeeper", "analyst"):
                            label = "记账员" if node_name == "bookkeeper" else "分析师"
                            yield {"event": "thinking",
                                   "data": f"正在由{label}处理..."}

                    elif kind == "on_chat_model_stream":
                        chunk = event["data"]["chunk"]
                        if hasattr(chunk, "content") and chunk.content:
                            yield {"data": str(chunk.content)}

        except asyncio.TimeoutError:
            yield {"event": "error", "data": "AI 响应超时，请简化问题或稍后重试"}

    @property
    def is_initialized(self) -> bool:
        return self._initialized

    async def close(self):
        """关闭 MCP 连接。"""
        if self._session:
            try:
                await self._session.__aexit__(None, None, None)
            except Exception:
                pass
        if self._sse_context:
            try:
                await self._sse_context.__aexit__(None, None, None)
            except Exception:
                pass
