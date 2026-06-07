#!/usr/bin/env python3
"""
AI Agent 回归测试 — 端到端场景耗时采集 + 审计日志质量校验。

用法:
    python3 scripts/regression-test.py              # 默认: 每场景 3 次
    python3 scripts/regression-test.py --runs 5     # 每场景 5 次
    python3 scripts/regression-test.py --quick       # 快速模式: 每场景 1 次
    python3 scripts/regression-test.py --report-only # 只看最近一次报告

输出:
    - 控制台: 实时进度 + 汇总表格
    - scripts/regression-reports/<timestamp>.json  — 结构化报告
"""

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

# ── 配置 ──────────────────────────────────────────────

AGENT_URL = os.environ.get("AGENT_URL", "http://localhost:8081")
BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
AUDIT_LOG = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "finance-agent/logs/llm-audit/llm-calls.jsonl")
MEMORY_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "finance-agent/data/memory")
REPORT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "regression-reports")

# 测试场景定义
SCENARIOS = [
    {
        "id": "balance-query",
        "name": "单Agent-查余额",
        "endpoint": "/api/chat/stream",
        "body": {"userId": "regression-test", "message": "我的余额是多少"},
        "expect_tool": "query_balance",
        "timeout_s": 60,
    },
    {
        "id": "transaction-list",
        "name": "单Agent-交易明细",
        "endpoint": "/api/chat/stream",
        "body": {"userId": "regression-test", "message": "最近5笔交易明细"},
        "expect_tool": "list_transactions",
        "timeout_s": 60,
    },
    {
        "id": "category-spending",
        "name": "单Agent-分类消费",
        "endpoint": "/api/chat/stream",
        "body": {"userId": "regression-test", "message": "本月餐饮花了多少钱"},
        "expect_tool": "summarize_transactions",
        "timeout_s": 60,
    },
    {
        "id": "multi-booking",
        "name": "MultiAgent-记账",
        "endpoint": "/api/chat/multi-agent/stream",
        "body": {"userId": "regression-test", "message": "记一笔午餐30元"},
        "expect_tool": "add_transaction",
        "expect_agents": ["supervisor", "bookkeeper"],
        "timeout_s": 60,
    },
    {
        "id": "multi-analysis",
        "name": "MultiAgent-汇总分析",
        "endpoint": "/api/chat/multi-agent/stream",
        "body": {"userId": "regression-test", "message": "本月支出汇总"},
        "expect_tool": "summarize_transactions",
        "expect_agents": ["supervisor", "analyst"],
        "timeout_s": 90,
    },
]


# ── 工具函数 ──────────────────────────────────────────

def log(msg):
    print(f"  [{datetime.now().strftime('%H:%M:%S')}] {msg}")


def http_get(url, timeout=5):
    try:
        result = subprocess.run(
            ["curl", "-s", "--max-time", str(timeout), url],
            capture_output=True, text=True, timeout=timeout + 2)
        return result.returncode == 0, result.stdout
    except Exception as e:
        return False, str(e)


def http_post_stream(url, body, timeout_s):
    """POST 流式请求，收集完整响应并计时。"""
    start = time.time()
    response_lines = []
    error_msg = None

    try:
        proc = subprocess.run(
            ["curl", "-s", "--max-time", str(timeout_s),
             "-X", "POST", url,
             "-H", "Content-Type: application/json",
             "-d", json.dumps(body)],
            capture_output=True, text=True, timeout=timeout_s + 10)

        elapsed = time.time() - start

        stdout = proc.stdout or ""
        stderr = proc.stderr or ""

        # 检查 HTTP 错误
        if proc.returncode != 0 and not stdout.strip():
            error_msg = f"curl 返回码 {proc.returncode}: {stderr[:200]}"
            return elapsed, [], error_msg

        # 检查是否为错误响应
        if '"error"' in stdout[:200] or 'AI 服务暂时不可用' in stdout:
            error_msg = f"Agent 返回错误: {stdout[:300]}"

        response_lines = stdout.strip().split('\n') if stdout.strip() else []

    except subprocess.TimeoutExpired:
        elapsed = time.time() - start
        error_msg = f"请求超时 (>{timeout_s}s)"
    except Exception as e:
        elapsed = time.time() - start
        error_msg = f"异常: {str(e)[:200]}"

    return elapsed, response_lines, error_msg


def extract_thinking_events(lines):
    """从 SSE 流中提取 thinking 事件。"""
    events = []
    for line in lines:
        if line.startswith("data:正在由"):
            events.append(line.replace("data:", "").strip())
    return events


# ── 前置检查 ──────────────────────────────────────────

def preflight():
    """检查服务运行状态和测试数据。"""
    print("=" * 60)
    print("  AI Agent 回归测试 — 前置检查")
    print("=" * 60)
    all_ok = True

    # 1. Agent 服务
    ok, resp = http_get(f"{AGENT_URL}/actuator/health")
    if ok:
        log(f"✓ Agent 服务 ({AGENT_URL})")
    else:
        log(f"✗ Agent 服务不可达 ({AGENT_URL})")
        all_ok = False

    # 2. Backend 服务
    ok, resp = http_get(f"{BACKEND_URL}/api/accounts?userId=regression-test")
    if ok:
        log(f"✓ Backend 服务 ({BACKEND_URL})")
    else:
        log(f"✗ Backend 服务不可达 ({BACKEND_URL})")
        all_ok = False

    # 3. 测试账户
    try:
        accounts = json.loads(resp) if ok else []
    except json.JSONDecodeError:
        accounts = []

    if accounts:
        log(f"✓ 测试账户已存在 (regression-test, {len(accounts)}个账户)")
        # 检查交易数据
        ok2, resp2 = http_get(
            f"{BACKEND_URL}/api/transactions?userId=regression-test")
        try:
            txns = json.loads(resp2) if ok2 else []
        except json.JSONDecodeError:
            txns = []
        if len(txns) >= 3:
            log(f"✓ 测试交易数据充足 ({len(txns)}笔)")
        else:
            log(f"⚠ 交易数据不足 ({len(txns)}笔), 将自动创建")
            all_ok = _seed_test_data()
    else:
        log("⚠ 测试账户不存在，自动创建...")
        all_ok = _seed_test_data()

    # 4. 审计日志目录
    audit_dir = os.path.dirname(AUDIT_LOG)
    if audit_dir and os.path.exists(audit_dir):
        log(f"✓ 审计日志目录存在")
    else:
        log(f"⚠ 审计日志目录不存在 (将自动创建)")

    return all_ok


def _seed_test_data():
    """创建回归测试专用的账户和交易数据。"""
    user = "regression-test"
    base = BACKEND_URL

    # 清理旧数据（memory）
    mem_file = os.path.join(MEMORY_DIR, f"{user}.json")
    if os.path.exists(mem_file):
        os.remove(mem_file)

    # 创建账户
    subprocess.run(
        ["curl", "-s", "-X", "POST", f"{base}/api/accounts",
         "-H", "Content-Type: application/json",
         "-d", json.dumps({"userId": user, "name": "测试账户",
                           "type": "BANK", "balance": 10000.00})],
        capture_output=True, timeout=10)

    # 获取账户 ID
    _, resp = http_get(f"{base}/api/accounts?userId={user}")
    try:
        accounts = json.loads(resp)
        acct_id = accounts[0]["id"] if accounts else 1
    except (json.JSONDecodeError, KeyError, IndexError):
        acct_id = 1

    # 创建交易
    txns = [
        {"userId": user, "accountId": acct_id, "type": "INCOME",
         "amount": 5000.00, "category": "工资", "subCategory": "月薪",
         "note": "6月工资", "date": "2026-06-01"},
        {"userId": user, "accountId": acct_id, "type": "EXPENSE",
         "amount": 35.50, "category": "餐饮", "subCategory": "午餐",
         "note": "兰州拉面", "date": "2026-06-05"},
        {"userId": user, "accountId": acct_id, "type": "EXPENSE",
         "amount": 120.00, "category": "购物", "subCategory": "日用品",
         "note": "超市", "date": "2026-06-04"},
        {"userId": user, "accountId": acct_id, "type": "EXPENSE",
         "amount": 68.00, "category": "餐饮", "subCategory": "晚餐",
         "note": "火锅", "date": "2026-06-03"},
    ]
    for t in txns:
        subprocess.run(
            ["curl", "-s", "-X", "POST", f"{base}/api/transactions",
             "-H", "Content-Type: application/json",
             "-d", json.dumps(t)],
            capture_output=True, timeout=10)

    log(f"  ✓ 已创建测试账户和 {len(txns)} 笔交易")
    return True


# ── 审计日志质量校验 ──────────────────────────────────

def check_audit_log(expected_scenarios):
    """校验审计日志质量指标。"""
    issues = []
    if not os.path.exists(AUDIT_LOG):
        return ["审计日志文件不存在"]

    try:
        with open(AUDIT_LOG) as f:
            records = [json.loads(line.strip())
                       for line in f if line.strip()]
    except (json.JSONDecodeError, IOError) as e:
        return [f"审计日志解析失败: {e}"]

    if not records:
        return ["审计日志为空"]

    # 1. 空请求记录（重复注册 bug）
    empty = [r for r in records
             if not r['request'].get('systemPrompt')
             and not r['request'].get('userMessage')]
    if empty:
        issues.append(f"发现 {len(empty)} 条空请求记录（重复注册未修复）")

    # 2. unknown traceId
    unknown = [r for r in records
               if r['traceId'] == 'unknown' or r['agentName'] == 'unknown']
    if unknown:
        issues.append(f"发现 {len(unknown)} 条 unknown traceId/agentName")

    # 3. 零耗时记录
    zero_dur = [r for r in records if r['durationMs'] == 0]
    if zero_dur:
        issues.append(f"发现 {len(zero_dur)} 条 durationMs=0 记录")

    # 4. traceId 关联（multi-agent 场景应共享 traceId）
    by_trace = {}
    for r in records:
        by_trace.setdefault(r['traceId'], []).append(r['agentName'])
    for tid, agents in by_trace.items():
        if 'supervisor' in agents and len(agents) < 2:
            issues.append(f"traceId={tid[:8]} 仅有 supervisor 无 specialist")

    return issues


# ── 核心测试逻辑 ──────────────────────────────────────

def run_scenario(scenario, run_index):
    """运行单个场景的一次测试。"""
    url = f"{AGENT_URL}{scenario['endpoint']}"
    body = dict(scenario['body'])
    body["userId"] = "regression-test"  # 确保使用测试用户

    elapsed, lines, error = http_post_stream(
        url, body, scenario['timeout_s'])

    result = {
        "scenario": scenario["id"],
        "run": run_index,
        "elapsed_s": round(elapsed, 3),
        "status": "pass",
        "error": None,
        "response_lines": len(lines),
        "thinking_events": [],
    }

    if error:
        result["status"] = "fail"
        result["error"] = error
    else:
        result["thinking_events"] = extract_thinking_events(lines)

    return result


def run_regression(runs_per_scenario=3):
    """运行完整回归测试套件。"""
    total_scenarios = len(SCENARIOS)
    total_tests = total_scenarios * runs_per_scenario

    print(f"\n{'=' * 60}")
    print(f"  回归测试: {total_scenarios} 个场景 × {runs_per_scenario} 次")
    print(f"  Agent: {AGENT_URL}  |  Backend: {BACKEND_URL}")
    print(f"{'=' * 60}\n")

    all_results = []
    scenario_results = {}  # 按场景聚合

    for i, scenario in enumerate(SCENARIOS):
        sid = scenario["id"]
        scenario_results[sid] = []
        print(f"[{i+1}/{total_scenarios}] {scenario['name']}")

        # 清理对话记忆（避免历史增长影响每次测试的耗时）
        mem_file = os.path.join(MEMORY_DIR, "regression-test.json")
        if os.path.exists(mem_file):
            os.remove(mem_file)

        for run_idx in range(1, runs_per_scenario + 1):
            result = run_scenario(scenario, run_idx)
            all_results.append(result)
            scenario_results[sid].append(result)

            status_icon = "✓" if result["status"] == "pass" else "✗"
            print(f"  #{run_idx} {status_icon} {result['elapsed_s']:.3f}s"
                  f"{' — ' + result['error'] if result['error'] else ''}")

            # 请求间隔 1s（避免 LLM rate limit）
            if run_idx < runs_per_scenario:
                time.sleep(1)

        print()

    # 审计日志校验
    print("─" * 60)
    print("  审计日志质量校验")
    print("─" * 60)
    audit_issues = check_audit_log(SCENARIOS)
    if audit_issues:
        for issue in audit_issues:
            print(f"  ✗ {issue}")
    else:
        print("  ✓ 审计日志质量正常")

    return all_results, scenario_results, audit_issues


# ── 报告生成 ──────────────────────────────────────────

def generate_report(scenario_results, audit_issues, runs):
    """生成并输出测试报告。"""
    print(f"\n{'=' * 60}")
    print(f"  回归测试报告")
    print(f"{'=' * 60}\n")

    # 汇总表
    header = f"{'场景':<24} {'次数':>4} {'平均':>9} {'P50':>8} {'P95':>8} {'最小':>8} {'最大':>8} {'失败':>4}"
    print(header)
    print("-" * len(header))

    summary = {}
    all_pass = True

    for scenario in SCENARIOS:
        sid = scenario["id"]
        sname = scenario["name"]
        results = scenario_results.get(sid, [])

        if not results:
            continue

        durs = [r["elapsed_s"] for r in results if r["status"] == "pass"]
        failed = [r for r in results if r["status"] == "fail"]

        if not durs:
            print(f"{sname:<24} {'-':>4} {'N/A':>9} {'N/A':>8} {'N/A':>8} "
                  f"{'N/A':>8} {'N/A':>8} {len(failed):>4}")
            for f in failed:
                print(f"    ✗ 失败: {f['error'][:100]}")
            all_pass = False
            continue

        durs_sorted = sorted(durs)
        avg = sum(durs) / len(durs)
        p50 = durs_sorted[len(durs_sorted) // 2]
        p95_idx = max(int(len(durs_sorted) * 0.95) - 1, 0)
        p95 = durs_sorted[p95_idx]
        mn = durs_sorted[0]
        mx = durs_sorted[-1]

        print(f"{sname:<24} {len(durs):>4} {avg:>7.3f}s {p50:>7.3f}s "
              f"{p95:>7.3f}s {mn:>7.3f}s {mx:>7.3f}s {len(failed):>4}")

        summary[sid] = {
            "name": sname,
            "runs": len(results),
            "passed": len(durs),
            "failed": len(failed),
            "avg_s": round(avg, 3),
            "p50_s": round(p50, 3),
            "p95_s": round(p95, 3),
            "min_s": round(mn, 3),
            "max_s": round(mx, 3),
            "durations": [round(d, 3) for d in durs],
        }

        if failed:
            all_pass = False
            for f in failed:
                print(f"    ✗ 失败: {f['error'][:100]}")

    # 整体统计
    print()
    all_durs = [r["elapsed_s"] for r in sum(scenario_results.values(), [])
                if r["status"] == "pass"]
    all_failed = [r for r in sum(scenario_results.values(), [])
                  if r["status"] == "fail"]

    if all_durs:
        print(f"整体: {len(all_durs)} 次通过, "
              f"平均 {sum(all_durs)/len(all_durs):.3f}s, "
              f"总耗时 {sum(all_durs):.1f}s")

    if all_failed:
        print(f"失败: {len(all_failed)} 次")
        for f in all_failed:
            print(f"  ✗ [{f['scenario']}] #{f['run']}: {f['error'][:150]}")

    if audit_issues:
        print(f"\n⚠ 审计日志问题:")
        for issue in audit_issues:
            print(f"  - {issue}")

    # 结果判断
    print()
    if all_pass and not audit_issues:
        print("✓ 回归测试全部通过")
    else:
        print("✗ 回归测试存在问题，请检查上述失败项")
        if all_failed:
            print(f"  失败场景: {', '.join(set(f['scenario'] for f in all_failed))}")

    # 写 JSON 报告
    os.makedirs(REPORT_DIR, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = os.path.join(REPORT_DIR, f"{timestamp}.json")
    report = {
        "timestamp": timestamp,
        "runs_per_scenario": runs,
        "agent_url": AGENT_URL,
        "backend_url": BACKEND_URL,
        "overall": {
            "total_runs": len(all_durs) + len(all_failed),
            "passed": len(all_durs),
            "failed": len(all_failed),
            "avg_s": round(sum(all_durs)/len(all_durs), 3) if all_durs else None,
            "total_s": round(sum(all_durs), 1) if all_durs else None,
        },
        "scenarios": summary,
        "audit_issues": audit_issues,
    }
    with open(report_path, "w") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n结构化报告: {report_path}")

    return 0 if all_pass and not audit_issues else 1


# ── 主入口 ────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="AI Agent 回归测试")
    parser.add_argument("--runs", type=int, default=3,
                        help="每个场景运行次数 (默认 3)")
    parser.add_argument("--quick", action="store_true",
                        help="快速模式: 每个场景 1 次")
    parser.add_argument("--report-only", action="store_true",
                        help="仅查看最近一次报告")
    parser.add_argument("--skip-preflight", action="store_true",
                        help="跳过前置检查")
    args = parser.parse_args()

    if args.report_only:
        _show_last_report()
        return 0

    runs = 1 if args.quick else args.runs

    # 前置检查
    if not args.skip_preflight:
        preflight_ok = preflight()
        if not preflight_ok:
            print("\n⚠ 部分前置检查未通过，继续执行测试...\n")
    else:
        print("(跳过前置检查)")

    # 清空审计日志（仅保留本次回归测试数据）
    audit_dir = os.path.dirname(AUDIT_LOG)
    if audit_dir:
        os.makedirs(audit_dir, exist_ok=True)
    open(AUDIT_LOG, 'w').close()

    # 清理记忆
    mem_file = os.path.join(MEMORY_DIR, "regression-test.json")
    if os.path.exists(mem_file):
        os.remove(mem_file)

    start_time = time.time()

    # 运行回归测试
    all_results, scenario_results, audit_issues = run_regression(runs)

    total_time = time.time() - start_time
    print(f"总耗时: {total_time:.1f}s")

    # 生成报告
    exit_code = generate_report(scenario_results, audit_issues, runs)
    sys.exit(exit_code)


def _show_last_report():
    """显示最近一次报告。"""
    if not os.path.exists(REPORT_DIR):
        print("无历史报告")
        return

    files = sorted(os.listdir(REPORT_DIR), reverse=True)
    if not files:
        print("无历史报告")
        return

    latest = os.path.join(REPORT_DIR, files[0])
    with open(latest) as f:
        report = json.load(f)

    print(f"最近报告: {latest}")
    print(f"时间: {report['timestamp']}")
    print(f"场景数: {len(report['scenarios'])}")
    print(f"通过/失败: {report['overall']['passed']}/{report['overall']['failed']}")
    print(f"平均耗时: {report['overall']['avg_s']}s")
    print()
    for sid, s in report['scenarios'].items():
        print(f"  {s['name']}: avg={s['avg_s']}s, "
              f"range=[{s['min_s']}-{s['max_s']}]s, "
              f"{s['passed']}/{s['runs']} 通过")


if __name__ == "__main__":
    main()
