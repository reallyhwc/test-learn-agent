"""Pytest fixtures for Python Agent Eval.

设计原则（与 Java AgentEvalTest 对齐）：
- 旁路 FinanceAgent 类：自建 LangChain agent，不走 Guardrails / Memory
- 复用 evals/golden-dataset.json（两栈共享）
- LLM 不可用时自动跳过所有 case（对齐 Java LlmCondition）
- 收集所有 case 结果，pytest_sessionfinish 钩子写 JSON 报告
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
from pathlib import Path
from typing import Any

import pytest
from dotenv import load_dotenv

# ────────────────────────────────────────────────────────────
# 路径常量
# ────────────────────────────────────────────────────────────
EVAL_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = EVAL_DIR.parent.parent
GOLDEN_DATASET = PROJECT_ROOT / "evals" / "golden-dataset.json"
REPORTS_DIR = PROJECT_ROOT / "evals" / "reports"

# 加载项目根 .env（含 LLM_API_KEY/BASE_URL/MODEL）
load_dotenv(PROJECT_ROOT / ".env")

# 默认连 Python MCP Server (:8083)；可通过 MCP_SSE_URL 覆盖
MCP_SSE_URL = os.environ.get("MCP_SSE_URL", "http://localhost:8083/sse")


# ────────────────────────────────────────────────────────────
# 显式加载同目录的 report_writer 模块
# （不依赖 sys.path 行为，更稳健）
# ────────────────────────────────────────────────────────────
def _load_sibling_module(name: str):
    spec = importlib.util.spec_from_file_location(name, EVAL_DIR / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


_report_writer = _load_sibling_module("report_writer")


# ────────────────────────────────────────────────────────────
# LLM 可用性 gate（对齐 Java LlmCondition）
# ────────────────────────────────────────────────────────────
def _llm_available() -> bool:
    key = os.environ.get("LLM_API_KEY", "").strip()
    return bool(key and key != "your-api-key-here")


@pytest.fixture(scope="session", autouse=True)
def skip_if_no_llm():
    """LLM 未配置时跳过所有 case（同 Java LlmCondition）。"""
    if not _llm_available():
        pytest.skip(
            "LLM_API_KEY 未配置，跳过所有 eval case。\n"
            "修复：在项目根 .env 中填写 LLM_API_KEY"
        )


# ────────────────────────────────────────────────────────────
# Golden Dataset
# ────────────────────────────────────────────────────────────
@pytest.fixture(scope="session")
def golden_dataset() -> list[dict[str, Any]]:
    with GOLDEN_DATASET.open(encoding="utf-8") as f:
        return json.load(f)["cases"]


# ────────────────────────────────────────────────────────────
# 旁路 LangChain Agent（与 Java EvalChatClientConfig 对称）
# ────────────────────────────────────────────────────────────
class EvalAgentContext:
    """持有 LangChain agent + 底层 MCP session，方便 ainvoke 与清理。"""

    def __init__(self):
        self.agent = None
        self._session = None
        self._sse_context = None

    async def initialize(self):
        # 延迟 import：未装依赖时 fixture 会通过 pytest.skip 而不是 ImportError
        from langchain_mcp_adapters.tools import load_mcp_tools
        from langchain_openai import ChatOpenAI
        from langgraph.prebuilt import create_react_agent
        from mcp import ClientSession
        from mcp.client.sse import sse_client

        api_key = os.environ["LLM_API_KEY"]
        base_url = os.environ.get("LLM_BASE_URL", "https://api.deepseek.com").rstrip("/")
        if not base_url.endswith("/v1"):
            base_url += "/v1"
        model = os.environ.get("LLM_MODEL", "deepseek-chat")

        chat = ChatOpenAI(
            model=model,
            api_key=api_key,
            base_url=base_url,
            temperature=0.1,
        )

        self._sse_context = sse_client(MCP_SSE_URL)
        read, write = await self._sse_context.__aenter__()
        self._session = ClientSession(read, write)
        await self._session.__aenter__()
        await self._session.initialize()
        tools = await load_mcp_tools(self._session)

        self.agent = create_react_agent(chat, tools)

    async def close(self):
        if self._session is not None:
            try:
                await self._session.__aexit__(None, None, None)
            except Exception:
                pass
        if self._sse_context is not None:
            try:
                await self._sse_context.__aexit__(None, None, None)
            except Exception:
                pass


@pytest.fixture(scope="session")
async def eval_agent():
    """整个 session 复用一个独立 LangChain agent。

    pytest-asyncio 9.x：session-scope async fixture 需要 pytest.ini 中配置
    `asyncio_default_fixture_loop_scope = session`（已配置）。
    """
    try:
        from langchain_mcp_adapters.tools import load_mcp_tools  # noqa: F401
    except ImportError:
        pytest.skip(
            "缺少 langchain-mcp-adapters 等依赖。"
            "修复：cd finance-agent-py && source .venv/bin/activate"
        )

    ctx = EvalAgentContext()
    try:
        await ctx.initialize()
    except Exception as e:
        pytest.skip(
            f"无法连接 MCP Server ({MCP_SSE_URL}): {e}\n"
            f"修复：./start-all.sh --dual 启动 mcp-server-py(:8083)"
        )
    yield ctx
    await ctx.close()


# ────────────────────────────────────────────────────────────
# 结果收集（pytest_sessionfinish 写 JSON 报告，见 report_writer.py）
# ────────────────────────────────────────────────────────────
# 模块级 bucket：各 test 通过 results_bucket fixture 拿到引用并 append
_RESULTS_BUCKET: list[dict[str, Any]] = []


@pytest.fixture(scope="function")
def results_bucket() -> list[dict[str, Any]]:
    """function-scope fixture，返回模块级 _RESULTS_BUCKET 的引用。"""
    return _RESULTS_BUCKET


def pytest_sessionfinish(session, exitstatus):
    """Session 结束时把收集到的结果写 JSON 报告（位置：evals/reports/）。"""
    report_file = _report_writer.write_report(_RESULTS_BUCKET, REPORTS_DIR)
    _report_writer.print_summary(_RESULTS_BUCKET, report_file)
