"""加载 .env (LLM key) 和 config.yaml (服务配置)。"""
import os
import re
from pathlib import Path

import yaml
from dotenv import load_dotenv

ROOT_DIR = Path(__file__).resolve().parent.parent
load_dotenv(ROOT_DIR / ".env")


def load_config() -> dict:
    """加载 config.yaml 并替换环境变量引用。"""
    config_path = ROOT_DIR / "config.yaml"
    if not config_path.exists():
        raise FileNotFoundError(f"配置文件不存在: {config_path}")

    with open(config_path, encoding="utf-8") as f:
        raw = f.read()

    # 替换 ${VAR_NAME} 为环境变量值
    def _replace_env(match):
        var_name = match.group(1)
        return os.environ.get(var_name, "")

    resolved = re.sub(r"\$\{(\w+)\}", _replace_env, raw)
    return yaml.safe_load(resolved)


def get_llm_config() -> dict:
    """获取 LLM 配置。"""
    config = load_config()
    llm = config.get("llm", {})
    return {
        "api_key": llm.get("api_key", os.environ.get("LLM_API_KEY", "")),
        "base_url": llm.get("base_url", "https://api.deepseek.com"),
        "model": llm.get("model", "deepseek-chat"),
    }


def get_agent_config() -> dict:
    """获取当前 Agent/MCP 提供者配置。"""
    config = load_config()
    ai = config.get("ai", {})
    services = config.get("services", {})
    agent_type = ai.get("agent", "java")
    mcp_type = ai.get("mcp", "java")
    return {
        "agent": agent_type,
        "mcp": mcp_type,
        "agent_port": services.get(f"agent-{agent_type}", {}).get("port", 8081),
        "mcp_port": services.get(f"mcp-{mcp_type}", {}).get("port", 8082),
        "available_agents": ["java", "python"],
        "available_mcps": ["java", "python"],
    }
