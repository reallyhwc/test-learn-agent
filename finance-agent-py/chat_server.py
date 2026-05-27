"""FastAPI Chat Server — SSE 流式对话接口。"""
import asyncio
import logging
import re
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from sse_starlette.sse import EventSourceResponse

from agent import FinanceAgent
from config_loader import get_agent_config
from memory_manager import MemoryManager

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

MAX_MESSAGE_LENGTH = 2000
SYNC_TIMEOUT = 60

agent: FinanceAgent | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global agent
    config = get_agent_config()
    mcp_port = config["mcp_port"]
    mcp_url = f"http://localhost:{mcp_port}/sse"
    logger.info("连接 MCP Server: %s", mcp_url)
    agent = FinanceAgent(mcp_sse_url=mcp_url)
    await agent.initialize()
    yield
    if agent:
        await agent.close()


app = FastAPI(title="finance-agent-py", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class ChatRequest(BaseModel):
    user_id: str = Field(default="default", alias="userId")
    message: str = ""


class ChatResponse(BaseModel):
    reply: str


def _sanitize_user_id(user_id: str | None) -> str:
    if not user_id or not user_id.strip():
        return "default"
    sanitized = re.sub(r"[^a-zA-Z0-9_-]", "", user_id)
    if not sanitized or len(sanitized) > 64:
        return "default" if not sanitized else sanitized[:64]
    return sanitized


def _validate_message(message: str | None) -> str:
    if not message or not message.strip():
        raise ValueError("消息内容不能为空")
    if len(message) > MAX_MESSAGE_LENGTH:
        logger.warning("消息超长，截断至 %d 字符", MAX_MESSAGE_LENGTH)
        return message[:MAX_MESSAGE_LENGTH]
    return message


# ──────────── /api/config ────────────

@app.get("/api/config")
def get_config():
    """返回当前 Agent/MCP 提供者信息和可用选项。"""
    return get_agent_config()


# ──────────── /api/chat (同步) ────────────

@app.post("/api/chat")
async def chat(request: ChatRequest):
    user_id = _sanitize_user_id(request.user_id)
    message = _validate_message(request.message)
    logger.info("Chat: userId=%s, message=%s", user_id, message[:50])

    try:
        reply = await asyncio.wait_for(
            agent.chat(user_id, message), timeout=SYNC_TIMEOUT
        )
        return ChatResponse(reply=reply)
    except asyncio.TimeoutError:
        logger.warning("同步 chat 超时: userId=%s", user_id)
        return JSONResponse(
            {"error": "AI 服务响应超时（>60s），请稍后重试"}, status_code=504
        )


# ──────────── /api/chat/stream (SSE 流式) ────────────

@app.post("/api/chat/stream")
async def chat_stream(request: ChatRequest):
    user_id = _sanitize_user_id(request.user_id)
    message = _validate_message(request.message)
    logger.info("Stream: userId=%s, message=%s", user_id, message[:50])

    async def event_generator():
        try:
            async for token in agent.chat_stream(user_id, message):
                escaped = token.replace("\n", "\ndata:")
                yield {"data": escaped}
        except Exception as e:
            logger.error("流式错误: %s", e)
            yield {"event": "error", "data": "AI 服务响应异常，请稍后重试"}

    return EventSourceResponse(event_generator())


# ──────────── /api/memory (记忆管理) ────────────

@app.delete("/api/memory")
def clear_memory(user_id: str = "default"):
    uid = _sanitize_user_id(user_id)
    memory = MemoryManager(uid)
    memory.clear()
    return {"success": True, "message": f"已清除用户 {uid} 的对话记忆"}


@app.get("/api/memory/count")
def memory_count(user_id: str = "default"):
    uid = _sanitize_user_id(user_id)
    memory = MemoryManager(uid)
    return {"count": memory.count(), "maxMessages": 20}


# ──────────── health ────────────

@app.get("/actuator/health")
def health():
    return {"status": "UP"}
