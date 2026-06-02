"""Session 结束时写 JSON 报告到 evals/reports/eval-python-<ts>.json

被 conftest.py 通过 pytest_sessionfinish 钩子调用。
JSON schema 与 Java EvalReport 完全一致，便于 HTML 报告合并两栈数据。
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def write_report(results: list[dict[str, Any]], reports_dir: Path) -> Path | None:
    """把收集到的结果写成 JSON 报告。

    返回写入的文件路径；没有结果时返回 None。
    """
    if not results:
        return None

    reports_dir.mkdir(parents=True, exist_ok=True)
    pass_count = sum(1 for r in results if r.get("pass"))
    fail_count = len(results) - pass_count

    # ISO-8601 UTC 时间（与 Java Instant.now() 等价）
    run_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    report = {
        "runAt": run_at,
        "model": os.environ.get("LLM_MODEL", "unknown"),
        "stack": "python",
        "totalCases": len(results),
        "passCount": pass_count,
        "failCount": fail_count,
        "results": results,
    }

    fname = "eval-python-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json"
    report_file = reports_dir / fname
    with report_file.open("w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    return report_file


def print_summary(results: list[dict[str, Any]], report_file: Path | None) -> None:
    if not results:
        print("\n[Eval] 没有 case 被执行（可能 LLM 不可用被 skip）")
        return
    pass_count = sum(1 for r in results if r.get("pass"))
    failed_ids = [r["caseId"] for r in results if not r.get("pass")]

    bar = "=" * 60
    print(f"\n{bar}")
    print(f"[Eval-py] {len(results)} cases: ✅ {pass_count} 通过, ❌ {len(failed_ids)} 失败")
    if failed_ids:
        print(f"[Eval-py] 失败 case: {failed_ids}")
    if report_file:
        print(f"[Eval-py] 报告: {report_file.resolve()}")
    print(bar)
