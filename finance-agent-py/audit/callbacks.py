"""LangChain audit callbacks — JSONL 结构化日志，schema 与 Java LlmAuditAdvisor 一致。"""
import json
import logging
import os
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.outputs import LLMResult

logger = logging.getLogger(__name__)

AUDIT_DIR = Path(os.environ.get("AUDIT_LOG_DIR", "logs/llm-audit"))
AUDIT_FILE = AUDIT_DIR / "llm-calls.jsonl"

MAX_SYSTEM_PROMPT_LENGTH = 200


def _truncate_system_prompt(prompt: str | None) -> str | None:
    if prompt is None:
        return None
    if len(prompt) <= MAX_SYSTEM_PROMPT_LENGTH:
        return prompt
    return prompt[:MAX_SYSTEM_PROMPT_LENGTH] + f"...[truncated, {len(prompt)} chars]"


class LlmAuditCallback(BaseCallbackHandler):
    """LangChain callback，拦截每次 LLM 调用并写入 JSONL 审计记录。

    Usage:
        llm = ChatOpenAI(...)
        callback = LlmAuditCallback(
            trace_id="uuid-123",
            agent_name="supervisor",
            call_type="classify",
            user_id="user-1",
        )
        llm.invoke(messages, config={"callbacks": [callback]})
    """

    def __init__(
        self,
        trace_id: str | None = None,
        agent_name: str = "unknown",
        call_type: str = "execute",
        user_id: str = "unknown",
    ):
        self.trace_id = trace_id or str(uuid.uuid4())
        self.agent_name = agent_name
        self.call_type = call_type
        self.user_id = user_id
        self._start_time: float | None = None
        self._input_messages: list[dict] = []

    def on_chat_model_start(
        self,
        serialized: dict[str, Any],
        messages: list[list[Any]],
        **kwargs: Any,
    ) -> None:
        self._start_time = time.monotonic()
        self._input_messages = []
        for msg_list in messages:
            for msg in msg_list:
                role = getattr(msg, "type", "unknown")
                content = getattr(msg, "content", "")
                if isinstance(content, list):
                    content = str(content)
                self._input_messages.append(
                    {"role": str(role), "content": str(content)}
                )

    def on_llm_end(self, response: LLMResult, **kwargs: Any) -> None:
        duration_ms = self._compute_duration()
        record = self._build_record(response, duration_ms, None)
        self._write(record)

    def on_llm_error(self, error: Exception, **kwargs: Any) -> None:
        duration_ms = self._compute_duration()
        record = self._build_error_record(duration_ms, str(error))
        self._write(record)

    def _compute_duration(self) -> int:
        if self._start_time is None:
            return 0
        elapsed = time.monotonic() - self._start_time
        self._start_time = None
        return int(elapsed * 1000)

    def _build_record(
        self, response: LLMResult, duration_ms: int, error: str | None
    ) -> dict:
        token_usage = None
        if response.llm_output and "token_usage" in response.llm_output:
            tu = response.llm_output["token_usage"]
            token_usage = {
                "inputTokens": tu.get("prompt_tokens", 0),
                "outputTokens": tu.get("completion_tokens", 0),
                "totalTokens": tu.get("total_tokens", 0),
            }

        content = ""
        finish_reason = "stop"
        if response.generations:
            gen = response.generations[0][0]
            content = gen.text or ""
            if hasattr(gen, "generation_info") and gen.generation_info:
                finish_reason = gen.generation_info.get(
                    "finish_reason", "stop"
                )

        model = (
            response.llm_output.get("model_name", "")
            if response.llm_output
            else ""
        )

        system_prompt = ""
        user_message = ""
        for msg in self._input_messages:
            if msg["role"] in ("system", "SystemMessage"):
                system_prompt = msg["content"]
            elif msg["role"] in ("human", "user", "HumanMessage"):
                user_message = msg["content"]

        return {
            "traceId": self.trace_id,
            "agentName": self.agent_name,
            "callType": self.call_type,
            "userId": self.user_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "durationMs": duration_ms,
            "request": {
                "systemPrompt": _truncate_system_prompt(system_prompt),
                "userMessage": user_message,
                "messages": self._input_messages,
                "tools": [],
            },
            "response": {
                "content": content,
                "toolCalls": [],
                "finishReason": finish_reason,
            },
            "tokenUsage": token_usage,
            "model": model,
            "error": error,
        }

    def _build_error_record(self, duration_ms: int, error: str) -> dict:
        return {
            "traceId": self.trace_id,
            "agentName": self.agent_name,
            "callType": self.call_type,
            "userId": self.user_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "durationMs": duration_ms,
            "request": None,
            "response": None,
            "tokenUsage": None,
            "model": None,
            "error": error,
        }

    def _write(self, record: dict) -> None:
        try:
            AUDIT_DIR.mkdir(parents=True, exist_ok=True)
            with open(AUDIT_FILE, "a") as f:
                f.write(
                    json.dumps(record, ensure_ascii=False, default=str) + "\n"
                )
        except Exception as e:
            logger.debug("写入审计记录失败: %s", e)
