"""
Personal Finance MCP Server (Python 版)
通过 SSE 暴露 5 个财务工具，调用 Java Backend REST API。
与 Java 版 finance-mcp-server 功能完全对等。
"""

import json
import logging
import os
import re
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
SAFE_USER_ID = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")
DEFAULT_PAGE_SIZE = 50

mcp = FastMCP("finance-mcp-server-py", host="0.0.0.0", port=8083)

# 模块级 httpx 客户端，复用连接池
_http_client: httpx.AsyncClient | None = None


def _get_http_client() -> httpx.AsyncClient:
    """获取模块级 httpx 客户端（惰性创建，复用连接池）。"""
    global _http_client
    if _http_client is None or _http_client.is_closed:
        _http_client = httpx.AsyncClient(base_url=BACKEND_URL, timeout=10.0)
    return _http_client


def validate_user_id(user_id: str | None) -> str:
    """校验 userId 格式，防止注入攻击。"""
    if not user_id or not user_id.strip():
        raise ValueError("userId 不能为空")
    if not SAFE_USER_ID.match(user_id):
        raise ValueError("userId 格式非法，仅允许字母、数字、下划线和短横线")
    return user_id


# ──────────── query_balance ────────────

@mcp.tool()
async def query_balance(user_id: str, account_id: int) -> Any:
    """按 accountId 查询单个账户余额。注意：list_accounts 返回的对象已含 balance 字段，
    查询余额时优先用 list_accounts 一次拿全，不要重复调用此工具。"""
    try:
        user_id = validate_user_id(user_id)
    except ValueError as e:
        return str(e)
    logger.info("query_balance: userId=%s, accountId=%s", user_id, account_id)
    try:
        client = _get_http_client()
        resp = await client.get(f"/api/accounts/{account_id}/balance")
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.error("查询余额失败: %s", e)
        return "查询余额失败，请检查账户ID是否正确"


# ──────────── list_transactions ────────────

@mcp.tool()
async def list_transactions(user_id: str, filters: str) -> Any:
    """查询交易记录明细列表。仅 userId 必填，其余过滤条件通过 filters JSON 传入。
    filters 示例: {"category":"餐饮","subCategory":"外卖","type":"EXPENSE"}
    filters 可用字段: startDate、endDate(yyyy-MM-dd)、category(一级分类)、
    subCategory(二级分类)、type(INCOME/EXPENSE)、accountId"""
    try:
        user_id = validate_user_id(user_id)
    except ValueError as e:
        return str(e)
    logger.info("list_transactions: userId=%s, filters=%s", user_id, filters)

    filter_map = _parse_filters(filters)
    limit = int(filter_map.pop("limit", DEFAULT_PAGE_SIZE))
    if limit <= 0 or limit > 500:
        limit = DEFAULT_PAGE_SIZE
    params: dict[str, Any] = {"userId": user_id, "pageSize": limit}
    for key in ("startDate", "endDate", "category", "subCategory", "type", "accountId"):
        if key in filter_map and filter_map[key] is not None:
            params[key] = filter_map[key]

    try:
        client = _get_http_client()
        resp = await client.get("/api/transactions", params=params)
        resp.raise_for_status()
        data = resp.json()
        items = data.get("items", []) if isinstance(data, dict) else []
        total = data.get("total", len(items)) if isinstance(data, dict) else len(items)
        return {
            "items": items,
            "total": total,
            "showing": len(items),
            "summary": f"共 {total} 条记录，已展示前 {len(items)} 条",
        }
    except Exception as e:
        logger.error("查询交易记录失败: %s", e)
        return "查询交易记录失败，请稍后重试"


# ──────────── summarize_transactions ────────────

@mcp.tool()
async def summarize_transactions(user_id: str, filters: str) -> Any:
    """按分类汇总交易金额统计。返回每个分类的总金额和笔数及合计。
    适用于'赚了多少''花了多少''收支汇总'类问题。仅 userId 必填，filters 可选。
    filters 可用字段: type(INCOME/EXPENSE)、startDate、endDate(yyyy-MM-dd)、
    groupBy('category'按一级分类汇总，'subCategory'按二级分类汇总，默认category)"""
    try:
        user_id = validate_user_id(user_id)
    except ValueError as e:
        return str(e)
    logger.info("summarize_transactions: userId=%s, filters=%s", user_id, filters)

    filter_map = _parse_filters(filters)
    params: dict[str, Any] = {"userId": user_id}
    for key in ("type", "startDate", "endDate", "groupBy"):
        if key in filter_map and filter_map[key] is not None:
            params[key] = filter_map[key]

    try:
        client = _get_http_client()
        resp = await client.get("/api/transactions/summary", params=params)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.error("汇总交易统计失败: %s", e)
        return "汇总交易统计失败，请稍后重试"


# ──────────── add_transaction ────────────

@mcp.tool()
async def add_transaction(
    user_id: str,
    account_id: int,
    transaction_type: str,
    amount: str,
    category: str,
    sub_category: str,
    note: str = "",
) -> Any:
    """添加一笔交易记录。category 和 sub_category 必须同时提供。
    transaction_type 为交易类型，取值 INCOME 或 EXPENSE。amount 为金额字符串如 "100.50"。
    支出一级分类: 餐饮(外卖/食堂/聚餐/日常餐饮)、交通(公交/打车/加油/日常出行)、
    购物(日用品/服饰/数码)、房租(房租/物业/水电)、娱乐(电影/游戏/旅行)、
    医疗(门诊/药品/体检)、其他(其他支出)。
    收入一级分类: 工资(基本工资/奖金/补贴)、兼职(兼职收入)、理财(利息/分红/基金)。"""
    try:
        user_id = validate_user_id(user_id)
    except ValueError as e:
        return str(e)

    if not account_id:
        return "添加交易失败，账户ID不能为空"
    try:
        amount_decimal = float(amount)
    except (TypeError, ValueError):
        return "添加交易失败，金额格式不正确"
    if amount_decimal <= 0:
        return "添加交易失败，金额必须大于0"
    type_upper = transaction_type.upper() if transaction_type else ""
    if type_upper not in ("INCOME", "EXPENSE"):
        return "添加交易失败，交易类型必须是 INCOME 或 EXPENSE"
    if not category or not category.strip():
        return "添加交易失败，一级分类不能为空"
    if not sub_category or not sub_category.strip():
        return "添加交易失败，二级分类不能为空"

    logger.info(
        "add_transaction: userId=%s, accountId=%s, type=%s, amount=%s, category=%s/%s",
        user_id, account_id, type_upper, amount, category, sub_category,
    )

    from datetime import date

    body = {
        "userId": user_id,
        "accountId": account_id,
        "type": type_upper,
        "amount": amount_decimal,
        "category": category,
        "subCategory": sub_category,
        "note": note or "",
        "date": date.today().isoformat(),
    }

    try:
        client = _get_http_client()
        resp = await client.post("/api/transactions", json=body)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.error("添加交易失败: %s", e)
        return "添加交易失败，请检查参数是否完整"


# ──────────── list_accounts ────────────

@mcp.tool()
async def list_accounts(user_id: str) -> Any:
    """查询用户的全部账户列表。返回字段：id、name、type、balance（实时余额）、userId。
    balance 已包含在返回中，无需再调用 query_balance。"""
    try:
        user_id = validate_user_id(user_id)
    except ValueError as e:
        return str(e)
    logger.info("list_accounts: userId=%s", user_id)
    try:
        client = _get_http_client()
        resp = await client.get("/api/accounts", params={"userId": user_id})
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.error("查询账户列表失败: %s", e)
        return "查询账户列表失败，请稍后重试"


# ──────────── helpers ────────────

def _parse_filters(filters: str) -> dict[str, Any]:
    """解析 filters JSON 字符串。"""
    if not filters or filters.strip() in ("", "{}"):
        return {}
    try:
        return json.loads(filters)
    except json.JSONDecodeError:
        logger.warning("解析 filters JSON 失败: %s", filters)
        return {}


# ──────────── entry ────────────

if __name__ == "__main__":
    mcp.run(transport="sse")
