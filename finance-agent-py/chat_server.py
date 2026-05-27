"""FastAPI Chat Server — SSE 流式对话接口。"""
import asyncio
import logging
import os
import re
import subprocess
import threading
import time

from fastapi import FastAPI, HTTPException, Query
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


app = FastAPI(title="finance-agent-py")
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
        raise HTTPException(status_code=400, detail="消息内容不能为空")
    if len(message) > MAX_MESSAGE_LENGTH:
        logger.warning("消息超长，截断至 %d 字符", MAX_MESSAGE_LENGTH)
        return message[:MAX_MESSAGE_LENGTH]
    return message


# ──────────── /api/config ────────────

@app.get("/api/config")
def get_config():
    """返回当前 Agent/MCP 提供者信息和可用选项。"""
    config = get_agent_config()
    if agent is not None:
        config["current_mcp_url"] = agent.mcp_sse_url
    return config


# ──────────── /api/switch-mcp ────────────

MCP_PORTS = {"java": 8082, "python": 8083}


class SwitchMcpRequest(BaseModel):
    mcp_type: str = Field(alias="mcpType")


@app.post("/api/switch-mcp")
async def switch_mcp(request: SwitchMcpRequest):
    """切换 MCP Server：先返回响应，然后异步触发进程自重启。
    MCP SDK 使用 anyio cancel scope，运行时无法安全重连，
    因此采用与 Java Agent 一致的进程级重启方案。"""
    mcp_type = request.mcp_type.lower()
    if mcp_type not in MCP_PORTS:
        raise HTTPException(status_code=400, detail=f"不支持的 MCP 类型: {mcp_type}，可选: java, python")

    new_port = MCP_PORTS[mcp_type]
    new_url = f"http://localhost:{new_port}/sse"

    if agent is not None and agent.mcp_sse_url == new_url:
        return {"success": True, "mcpType": mcp_type, "message": "MCP 未变化，无需切换"}

    logger.info("切换 MCP: → %s (port %d)，将触发进程重启", mcp_type, new_port)

    agent_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(agent_dir)
    current_pid = os.getpid()

    def _restart():
        time.sleep(0.5)
        logger.info("正在重启 Python Agent (MCP port=%d)...", new_port)
        try:
            # 构建激活虚拟环境的命令（优先 .venv，fallback 到系统 python3）
            venv_activate = os.path.join(agent_dir, ".venv", "bin", "activate")
            if os.path.isfile(venv_activate):
                activate_cmd = f"source {venv_activate}"
            else:
                activate_cmd = "true"  # 无 venv 时跳过

            subprocess.Popen(
                ["bash", "-c",
                 f"kill {current_pid} 2>/dev/null; sleep 2; "
                 f"cd {agent_dir} && {activate_cmd} && "
                 f"MCP_PORT={new_port} python3 main.py "
                 f"> {project_root}/logs/agent-py.log 2>&1 &"],
                start_new_session=True,
            )
        except Exception as e:
            logger.error("重启失败: %s", e)

    threading.Thread(target=_restart, daemon=True).start()

    return {
        "success": True,
        "mcpType": mcp_type,
        "mcpUrl": new_url,
        "message": "Python Agent 正在重启以连接新 MCP Server，请等待约 3-5 秒",
    }


# ──────────── /api/chat (同步) ────────────

@app.post("/api/chat")
async def chat(request: ChatRequest):
    if agent is None:
        raise HTTPException(status_code=503, detail="Agent 尚未初始化，请稍后重试")
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
    if agent is None:
        raise HTTPException(status_code=503, detail="Agent 尚未初始化，请稍后重试")
    user_id = _sanitize_user_id(request.user_id)
    message = _validate_message(request.message)
    logger.info("Stream: userId=%s, message=%s", user_id, message[:50])

    async def event_generator():
        try:
            async for token in agent.chat_stream(user_id, message):
                yield {"data": token}
        except Exception as e:
            logger.error("流式错误: %s", e)
            yield {"event": "error", "data": "AI 服务响应异常，请稍后重试"}

    return EventSourceResponse(event_generator())


# ──────────── /api/memory (记忆管理) ────────────

@app.delete("/api/memory")
def clear_memory(user_id: str = Query(default="default", alias="userId")):
    uid = _sanitize_user_id(user_id)
    memory = MemoryManager(uid)
    memory.clear()
    return {"success": True, "message": f"已清除用户 {uid} 的对话记忆"}


@app.get("/api/memory/count")
def memory_count(user_id: str = Query(default="default", alias="userId")):
    uid = _sanitize_user_id(user_id)
    memory = MemoryManager(uid)
    return {"count": memory.count(), "maxMessages": 20}


# ──────────── health ────────────

@app.get("/actuator/health")
def health():
    return {"status": "UP"}
