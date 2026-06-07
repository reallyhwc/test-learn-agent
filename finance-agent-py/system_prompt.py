"""System prompt 模板 — 使用 prompt_loader 从 prompts/ 加载。"""
import logging
from datetime import date
from pathlib import Path

import httpx

from circuit_breaker import SimpleCircuitBreaker
from config_loader import load_config
from memory_manager import MemoryManager
from prompt_loader import PromptLoader

logger = logging.getLogger(__name__)

# 模块级 httpx 客户端，复用连接池
_http_client: httpx.AsyncClient | None = None

# 模块级熔断器，保护后端不可用时的快速失败
_account_circuit_breaker = SimpleCircuitBreaker("account-context", 3, 30_000)

# 项目根 prompts/ 目录 — Path(__file__).parent.parent 是 finance-agent-py/, prompts/ 在上一层
_PROMPTS_DIR = Path(__file__).parent.parent / "prompts"


def _get_prompt_loader() -> PromptLoader:
    """惰性创建 PromptLoader（读取 config.yaml 中的版本）。"""
    config = load_config()
    version = config.get("prompt", {}).get("version", "v1")
    return PromptLoader(str(_PROMPTS_DIR), version)


def _get_backend_url() -> str:
    """从 config.yaml 读取 Backend 端口，构建 URL。"""
    config = load_config()
    port = config.get("services", {}).get("backend", {}).get("port", 8080)
    return f"http://localhost:{port}"


def _get_http_client() -> httpx.AsyncClient:
    """获取模块级 httpx 客户端（惰性创建，复用连接池）。"""
    global _http_client
    if _http_client is None or _http_client.is_closed:
        _http_client = httpx.AsyncClient(
            base_url=_get_backend_url(),
            timeout=5.0,
        )
    return _http_client

def build_system_prompt(
    user_id: str,
    memory: MemoryManager,
    account_summary: str = "",
) -> str:
    """构建完整 system prompt — 从 prompts/ 加载并拼装。"""
    loader = _get_prompt_loader()
    memory_count = memory.count()
    context_info = (
        f"当前对话记忆: {memory_count} 条 / 上限 20 条"
        if memory_count > 0
        else ""
    )

    return loader.assemble("single-agent", {
        "userId": user_id,
        "accountSummary": account_summary,
        "safetyRules": loader.load_shared("safety-rules"),
        "categorySystem": loader.load_shared("category-system"),
        "currentDate": date.today().isoformat(),
        "contextInfo": context_info,
    })


async def fetch_account_summary(user_id: str) -> str:
    """从 Backend 拉取账户摘要注入 system prompt。
    失败时返回空字符串，让 LLM 自己调工具。
    使用熔断器保护后端不可用时的快速失败。"""
    if not _account_circuit_breaker.is_call_permitted():
        logger.warning("熔断器 %s 已打开，跳过账户上下文拉取", _account_circuit_breaker.name)
        return ""

    try:
        client = _get_http_client()
        resp = await client.get(
            "/api/accounts",
            params={"userId": user_id},
        )
        resp.raise_for_status()
        accounts = resp.json()
        _account_circuit_breaker.record_success()
        return _format_account_summary(accounts)
    except Exception as e:
        _account_circuit_breaker.record_failure()
        logger.warning("拉取账户上下文失败 userId=%s: %s", user_id, e)
        return ""


def _format_account_summary(accounts: list[dict]) -> str:
    """格式化账户摘要，Java 版 formatSummary() 的 Python 移植。"""
    if not accounts:
        return "**用户上下文**: 当前用户暂无账户。\n"

    sorted_accounts = sorted(
        accounts, key=lambda a: _balance_of(a), reverse=True
    )
    total = sum(_balance_of(a) for a in sorted_accounts)

    lines = [
        "**用户上下文（实时数据，简单查询直接读取，不用调用工具）**",
        f"- 账户数: {len(sorted_accounts)}",
        f"- 总余额: ¥{total:,.2f}",
    ]

    threshold = 5
    listed = min(len(sorted_accounts), threshold)
    label = "账户列表" if len(sorted_accounts) <= threshold else "主要账户（按余额前 5）"
    lines.append(f"- {label}:")

    for a in sorted_accounts[:listed]:
        lines.append(
            f"  - ID={a.get('id')} {a.get('name', '')}"
            f"（{a.get('type', '')}）"
            f" 余额 ¥{_balance_of(a):,.2f}"
        )

    rest = len(sorted_accounts) - listed
    if rest > 0:
        rest_sum = sum(_balance_of(a) for a in sorted_accounts[listed:])
        lines.append(
            f"  - …另有 {rest} 个账户余额合计 ¥{rest_sum:,.2f}，详情请调用 list_accounts"
        )

    return "\n".join(lines) + "\n"


def _balance_of(account: dict) -> float:
    """安全提取余额。"""
    v = account.get("balance", 0)
    if v is None:
        return 0.0
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0
