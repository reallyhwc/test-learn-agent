"""加载 .env (LLM key) 和 config.yaml (服务配置)。"""
import functools
import os
import re
from pathlib import Path

import yaml
from dotenv import load_dotenv

ROOT_DIR = Path(__file__).resolve().parent.parent
load_dotenv(ROOT_DIR / ".env")


@functools.lru_cache(maxsize=1)
def load_config() -> dict:
    """加载 config.yaml 并替换环境变量引用。结果被缓存，进程生命周期内只读一次。"""
    config_path = ROOT_DIR / "config.yaml"
    if not config_path.exists():
        raise FileNotFoundError(f"配置文件不存在: {config_path}")

    with open(config_path, encoding="utf-8") as f:
        raw = f.read()

    def _replace_env(match: re.Match) -> str:
        var_name = match.group(1)
        return os.environ.get(var_name, "")

    resolved = re.sub(r"\$\{(\w+)\}", _replace_env, raw)
    return yaml.safe_load(resolved)


def get_llm_config() -> dict[str, str]:
    """获取 LLM 配置。环境变量优先于 config.yaml，api_key 为空时抛出 ValueError。"""
    config = load_config()
    llm = config.get("llm", {})
    api_key = os.environ.get("LLM_API_KEY", "") or llm.get("api_key", "")
    base_url = os.environ.get("LLM_BASE_URL", "") or llm.get("base_url", "https://api.deepseek.com")
    model = os.environ.get("LLM_MODEL", "") or llm.get("model", "deepseek-chat")
    if not api_key:
        raise ValueError(
            "LLM_API_KEY 未配置，请在 .env 文件中设置 LLM_API_KEY 或在 config.yaml 的 llm.api_key 中配置"
        )
    return {
        "api_key": api_key,
        "base_url": base_url,
        "model": model,
    }


def get_agent_config() -> dict[str, object]:
    """获取当前 Agent/MCP 提供者配置。"""
    config = load_config()
    ai = config.get("ai", {})
    services = config.get("services", {})
    agent_type: str = ai.get("agent", "java")
    mcp_type: str = ai.get("mcp", "java")
    mode: str = ai.get("mode", "single")
    return {
        "agent": agent_type,
        "mcp": mcp_type,
        "mode": mode,
        "agent_port": services.get(f"agent-{agent_type}", {}).get("port", 8081),
        "mcp_port": services.get(f"mcp-{mcp_type}", {}).get("port", 8082),
        "available_agents": ["java", "python"],
        "available_mcps": ["java", "python"],
    }
