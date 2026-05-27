"""Per-user JSON 文件对话记忆，格式与 Java 版 JsonFileChatMemory 兼容。"""
import json
import logging
import threading
from pathlib import Path

logger = logging.getLogger(__name__)

MAX_MESSAGES = 20
VALID_ROLES = {"user", "assistant", "system"}
DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "memory"

# per-user 锁，防止并发读写竞态（使用 threading.Lock 因为文件 I/O 是同步的）
_user_locks: dict[str, threading.Lock] = {}
_locks_guard = threading.Lock()


def _get_lock(user_id: str) -> threading.Lock:
    """获取指定用户的线程锁（惰性创建，线程安全）。"""
    if user_id not in _user_locks:
        with _locks_guard:
            if user_id not in _user_locks:
                _user_locks[user_id] = threading.Lock()
    return _user_locks[user_id]


# 内存缓存，减少文件 I/O
_cache: dict[str, list[dict]] = {}


class MemoryManager:
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.file_path = DATA_DIR / f"{user_id}.json"
        DATA_DIR.mkdir(parents=True, exist_ok=True)

    def get_messages(self) -> list[dict]:
        """读取记忆，优先从内存缓存读取。返回 [{"role": "...", "content": "..."}]。"""
        if self.user_id in _cache:
            return list(_cache[self.user_id])

        if not self.file_path.exists():
            return []
        try:
            content = self.file_path.read_text(encoding="utf-8")
            if not content.strip():
                return []
            messages = json.loads(content)
            if not isinstance(messages, list):
                return []
            trimmed = messages[-MAX_MESSAGES:]
            _cache[self.user_id] = trimmed
            return list(trimmed)
        except (json.JSONDecodeError, OSError) as e:
            logger.warning("读取记忆文件失败: %s", e)
            return []

    def save_messages(self, messages: list[dict]) -> None:
        """保存记忆到缓存和文件，超过 MAX_MESSAGES 条滚动淘汰。加锁防止并发写入。"""
        trimmed = messages[-MAX_MESSAGES:]
        lock = _get_lock(self.user_id)
        with lock:
            _cache[self.user_id] = trimmed
            try:
                tmp_path = self.file_path.with_suffix(".tmp")
                tmp_path.write_text(
                    json.dumps(trimmed, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
                tmp_path.replace(self.file_path)
            except OSError as e:
                logger.error("保存记忆文件失败: %s", e)

    def append(self, role: str, content: str) -> None:
        """追加一条消息到记忆。role 必须是 user/assistant/system。"""
        if role not in VALID_ROLES:
            raise ValueError(f"无效的 role '{role}'，允许值: {VALID_ROLES}")
        if not content or not content.strip():
            logger.warning("跳过空内容消息: role=%s", role)
            return
        messages = self.get_messages()
        messages.append({"role": role, "content": content})
        self.save_messages(messages)

    def clear(self) -> None:
        """清除该用户记忆（缓存 + 文件）。加锁防止并发操作。"""
        lock = _get_lock(self.user_id)
        with lock:
            _cache.pop(self.user_id, None)
            try:
                self.file_path.unlink(missing_ok=True)
            except OSError as e:
                logger.error("清除记忆文件失败: %s", e)

    def count(self) -> int:
        return len(self.get_messages())
