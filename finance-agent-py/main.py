"""Python Agent 入口 — uvicorn ASGI 服务器。"""
import uvicorn

if __name__ == "__main__":
    uvicorn.run(
        "chat_server:app",
        host="0.0.0.0",
        port=8084,
        reload=False,
        log_level="info",
    )
