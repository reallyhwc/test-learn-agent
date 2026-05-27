"""Per-user JSON 文件对话记忆，格式与 Java 版 JsonFileChatMemory 兼容。"""
import json
import logging
from pathlib import Path

logger = logging.getLogger(__name__)

MAX_MESSAGES = 20
DATA_DIR = Path(__file__).resolve().parent / "data" / "memory"


class MemoryManager:
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.file_path = DATA_DIR / f"{user_id}.json"
        DATA_DIR.mkdir(parents=True, exist_ok=True)

    def get_messages(self) -> list[dict]:
        """读取记忆，返回 [{"role": "user"/"assistant", "content": "..."}]。"""
        if not self.file_path.exists():
            return []
        try:
            content = self.file_path.read_text(encoding="utf-8")
            if not content.strip():
                return []
            messages = json.loads(content)
            if not isinstance(messages, list):
                return []
            return messages[-MAX_MESSAGES:]
        except (json.JSONDecodeError, OSError) as e:
            logger.warning("读取记忆文件失败: %s", e)
            return []

    def save_messages(self, messages: list[dict]):
        """保存记忆，超过 MAX_MESSAGES 条滚动淘汰。"""
        trimmed = messages[-MAX_MESSAGES:]
        try:
            self.file_path.write_text(
                json.dumps(trimmed, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        except OSError as e:
            logger.error("保存记忆文件失败: %s", e)

    def append(self, role: str, content: str):
        """追加一条消息到记忆。"""
        messages = self.get_messages()
        messages.append({"role": role, "content": content})
        self.save_messages(messages)

    def clear(self):
        """清除该用户记忆。"""
        try:
            self.file_path.unlink(missing_ok=True)
        except OSError as e:
            logger.error("清除记忆文件失败: %s", e)

    def count(self) -> int:
        return len(self.get_messages())
