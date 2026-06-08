"""Prompt 加载器 — 从文件系统读取 Markdown 文件，缓存并拼装 System Prompt。"""
import logging
from pathlib import Path

logger = logging.getLogger(__name__)


class PromptLoader:
    """从文件系统加载 Markdown 格式的 Prompt 模板文件。

    支持按 agent 和版本组织文件，模板变量使用 {{varName}} 语法。
    首次加载后缓存到内存，调用 clear_cache() 可强制刷新。
    """

    def __init__(self, base_dir: str, version: str = "v1"):
        self.base_dir = Path(base_dir)
        self.version = version
        self._cache: dict[str, str] = {}

    def load(self, agent: str, file: str) -> str:
        """加载单个 Prompt 文件。

        Args:
            agent: Agent 子目录名（supervisor/bookkeeper/analyst/single-agent）
            file: 文件名（不含 .md 后缀）

        Returns:
            文件内容，文件不存在时返回空字符串
        """
        key = f"{self.version}/{agent}/{file}"
        if key not in self._cache:
            path = self.base_dir / self.version / agent / f"{file}.md"
            self._cache[key] = self._read(path)
        return self._cache[key]

    def load_shared(self, file: str) -> str:
        """加载 shared 目录下的文件（跨版本共享）。"""
        key = f"shared/{file}"
        if key not in self._cache:
            path = self.base_dir / "shared" / f"{file}.md"
            self._cache[key] = self._read(path)
        return self._cache[key]

    def assemble(self, agent: str, vars: dict[str, str] | None = None) -> str:
        """拼装完整 System Prompt，替换模板变量。

        Args:
            agent: Agent 名称
            vars: 模板变量键值对（如 userId, accountSummary 等）

        Returns:
            拼装后的完整 Prompt
        """
        agent_parts = {
            "supervisor": ["classify"],
            "bookkeeper": ["system", "tool-rules", "response-format"],
            "analyst": ["system", "tool-rules", "response-format"],
            "single-agent": ["system", "tool-rules", "response-format"],
        }

        files = agent_parts.get(agent)
        if files is None:
            raise ValueError(f"Unknown agent: {agent}")

        parts = [self.load(agent, f) for f in files]
        prompt = "\n\n".join(p for p in parts if p)

        if vars:
            for key, value in vars.items():
                prompt = prompt.replace("{{" + key + "}}", value)

        if "{{" in prompt:
            import re
            remaining = re.findall(r"\{\{(\w+)\}\}", prompt)
            if remaining:
                logger.warning("Prompt 中存在未替换的模板变量: %s", remaining)

        return prompt

    def clear_cache(self) -> None:
        """清空缓存（测试/热重载用）。"""
        self._cache.clear()

    def _read(self, path: Path) -> str:
        """读取文件内容，文件不存在时返回空字符串。"""
        try:
            if path.exists():
                return path.read_text("utf-8").strip()
        except Exception as e:
            logger.warning("读取 Prompt 文件失败: %s — %s", path, e)
        logger.warning("Prompt 文件未找到: %s", path)
        return ""
