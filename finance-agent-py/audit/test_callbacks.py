"""Tests for LlmAuditCallback."""
import json
from unittest.mock import MagicMock

import pytest

from .callbacks import LlmAuditCallback


class TestLlmAuditCallback:
    def test_construct_with_defaults(self):
        cb = LlmAuditCallback()
        assert cb.agent_name == "unknown"
        assert cb.call_type == "execute"
        assert cb.user_id == "unknown"
        assert cb.trace_id is not None  # auto-generated UUID

    def test_construct_with_explicit_params(self):
        cb = LlmAuditCallback(
            trace_id="trace-123",
            agent_name="supervisor",
            call_type="classify",
            user_id="user-1",
        )
        assert cb.trace_id == "trace-123"
        assert cb.agent_name == "supervisor"
        assert cb.call_type == "classify"
        assert cb.user_id == "user-1"

    def test_build_record_contains_all_fields(self):
        cb = LlmAuditCallback(
            trace_id="t1", agent_name="supervisor", call_type="classify"
        )
        cb._start_time = 1000.0
        cb._input_messages = [
            {"role": "system", "content": "You are a classifier."},
            {"role": "human", "content": "what is my balance?"},
        ]

        mock_resp = MagicMock()
        mock_resp.llm_output = {
            "token_usage": {
                "prompt_tokens": 100,
                "completion_tokens": 50,
                "total_tokens": 150,
            },
            "model_name": "deepseek-chat",
        }
        mock_gen = MagicMock()
        mock_gen.text = "booking"
        mock_gen.generation_info = {"finish_reason": "stop"}
        mock_resp.generations = [[mock_gen]]

        record = cb._build_record(mock_resp, 1234, None)

        assert record["traceId"] == "t1"
        assert record["agentName"] == "supervisor"
        assert record["callType"] == "classify"
        assert record["durationMs"] == 1234
        assert record["request"]["systemPrompt"] == "You are a classifier."
        assert record["request"]["userMessage"] == "what is my balance?"
        assert record["response"]["content"] == "booking"
        assert record["response"]["finishReason"] == "stop"
        assert record["tokenUsage"]["inputTokens"] == 100
        assert record["tokenUsage"]["outputTokens"] == 50
        assert record["tokenUsage"]["totalTokens"] == 150
        assert record["model"] == "deepseek-chat"
        assert record["error"] is None

    def test_error_record_has_null_request_response(self):
        cb = LlmAuditCallback(trace_id="t2", agent_name="analyst")
        record = cb._build_error_record(500, "timeout")

        assert record["traceId"] == "t2"
        assert record["error"] == "timeout"
        assert record["durationMs"] == 500
        assert record["request"] is None
        assert record["response"] is None
        assert record["tokenUsage"] is None
        assert record["model"] is None

    def test_on_llm_end_writes_jsonl(self, tmp_path, monkeypatch):
        monkeypatch.setattr("audit.callbacks.AUDIT_DIR", tmp_path)
        monkeypatch.setattr(
            "audit.callbacks.AUDIT_FILE", tmp_path / "llm-calls.jsonl"
        )

        cb = LlmAuditCallback(
            trace_id="t3", agent_name="supervisor", call_type="classify"
        )
        cb._start_time = 1000.0

        mock_resp = MagicMock()
        mock_resp.llm_output = {
            "token_usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15,
            },
            "model_name": "deepseek-chat",
        }
        mock_gen = MagicMock()
        mock_gen.text = "booking"
        mock_gen.generation_info = {"finish_reason": "stop"}
        mock_resp.generations = [[mock_gen]]

        cb.on_llm_end(mock_resp)

        log_file = tmp_path / "llm-calls.jsonl"
        assert log_file.exists()
        line = log_file.read_text().strip()
        record = json.loads(line)
        assert record["traceId"] == "t3"
        assert record["agentName"] == "supervisor"
        assert record["response"]["content"] == "booking"

    def test_on_llm_error_writes_error_record(self, tmp_path, monkeypatch):
        monkeypatch.setattr("audit.callbacks.AUDIT_DIR", tmp_path)
        monkeypatch.setattr(
            "audit.callbacks.AUDIT_FILE", tmp_path / "llm-calls.jsonl"
        )

        cb = LlmAuditCallback(trace_id="t4", agent_name="analyst")
        cb._start_time = 2000.0
        cb.on_llm_error(RuntimeError("timeout"))

        line = (tmp_path / "llm-calls.jsonl").read_text().strip()
        record = json.loads(line)
        assert record["error"] == "timeout"
        assert record["request"] is None

    def test_single_line_output(self, tmp_path, monkeypatch):
        monkeypatch.setattr("audit.callbacks.AUDIT_DIR", tmp_path)
        monkeypatch.setattr(
            "audit.callbacks.AUDIT_FILE", tmp_path / "llm-calls.jsonl"
        )

        cb = LlmAuditCallback()
        cb._start_time = 1000.0
        mock_resp = MagicMock()
        mock_resp.llm_output = {}
        mock_resp.generations = []
        cb.on_llm_end(mock_resp)

        lines = (
            (tmp_path / "llm-calls.jsonl")
            .read_text()
            .strip()
            .split("\n")
        )
        assert len(lines) == 1

    def test_schema_matches_java_side(self):
        cb = LlmAuditCallback()
        cb._input_messages = [
            {"role": "system", "content": "You are a classifier."},
            {"role": "human", "content": "what is my balance?"},
        ]
        cb._start_time = 100.0

        mock_resp = MagicMock()
        mock_resp.llm_output = {
            "token_usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15,
            },
            "model_name": "deepseek-chat",
        }
        mock_gen = MagicMock()
        mock_gen.text = "booking"
        mock_gen.generation_info = {"finish_reason": "stop"}
        mock_resp.generations = [[mock_gen]]

        record = cb._build_record(mock_resp, 1234, None)

        # Verify all Java-schema fields exist
        assert "traceId" in record
        assert "agentName" in record
        assert "callType" in record
        assert "userId" in record
        assert "timestamp" in record
        assert "durationMs" in record
        assert "request" in record
        assert "response" in record
        assert "tokenUsage" in record
        assert "model" in record
        assert "error" in record
        # Nested structure
        assert "systemPrompt" in record["request"]
        assert "userMessage" in record["request"]
        assert "messages" in record["request"]
        assert "tools" in record["request"]
        assert "content" in record["response"]
        assert "toolCalls" in record["response"]
        assert "finishReason" in record["response"]
        assert "inputTokens" in record["tokenUsage"]
        assert "outputTokens" in record["tokenUsage"]
        assert "totalTokens" in record["tokenUsage"]

    def test_construct_is_non_null(self):
        cb = LlmAuditCallback()
        assert cb is not None
        assert cb.agent_name == "unknown"

    def test_on_chat_model_start_captures_messages(self):
        cb = LlmAuditCallback()
        mock_msg = MagicMock()
        mock_msg.type = "system"
        mock_msg.content = "You are a classifier."
        mock_msg2 = MagicMock()
        mock_msg2.type = "human"
        mock_msg2.content = "Hello"

        cb.on_chat_model_start({}, [[mock_msg, mock_msg2]])
        assert len(cb._input_messages) == 2
        assert cb._input_messages[0] == {
            "role": "system",
            "content": "You are a classifier.",
        }
        assert cb._input_messages[1] == {
            "role": "human",
            "content": "Hello",
        }
        assert cb._start_time is not None
