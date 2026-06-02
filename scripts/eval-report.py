#!/usr/bin/env python3
"""Eval 报告聚合器 —— 把 evals/reports/eval-*.json 渲染为可读的 HTML 或 Markdown。

用法：
    python3 scripts/eval-report.py             # 生成 evals/reports/index.html
    python3 scripts/eval-report.py --format=markdown   # 输出到 stdout（供 CI STEP_SUMMARY）
    python3 scripts/eval-report.py --reports-dir=/path/to/reports

不引入任何第三方依赖（纯 stdlib）。HTML 内联 Chart.js CDN 做趋势图。
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from string import Template

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_REPORTS_DIR = PROJECT_ROOT / "evals" / "reports"


# ────────────────────────────────────────────────────────────
# 数据加载
# ────────────────────────────────────────────────────────────
def _stack_from_filename(filename: str) -> str:
    """从 eval-java-... / eval-python-... 文件名提取 stack 标识。"""
    m = re.match(r"eval-([a-z]+)-", filename)
    return m.group(1) if m else "unknown"


def load_reports(reports_dir: Path) -> list[dict]:
    """加载并排序所有 eval-*.json 报告。返回按 runAt 升序的字典列表。"""
    files = sorted(glob.glob(str(reports_dir / "eval-*.json")))
    reports = []
    for fp in files:
        try:
            with open(fp, encoding="utf-8") as f:
                data = json.load(f)
            data["_file"] = Path(fp).name
            data.setdefault("stack", _stack_from_filename(Path(fp).name))
            reports.append(data)
        except Exception as e:
            print(f"⚠ 跳过损坏的报告 {fp}: {e}", file=sys.stderr)
    # 按 runAt 升序排序（旧 → 新），以便趋势图 X 轴正序
    reports.sort(key=lambda r: r.get("runAt", ""))
    return reports


def pass_rate(report: dict) -> float:
    total = report.get("totalCases", 0)
    return (report.get("passCount", 0) / total * 100) if total else 0.0


# ────────────────────────────────────────────────────────────
# Markdown 输出（用于 CI GITHUB_STEP_SUMMARY）
# ────────────────────────────────────────────────────────────
def render_markdown(reports: list[dict]) -> str:
    if not reports:
        return "## Eval 报告\n\n_暂无报告数据。_\n"

    latest = reports[-1]
    lines = ["## Eval 报告", ""]
    lines.append(f"**最新运行**：`{latest.get('_file', '')}` · {latest.get('stack', '?')} · "
                 f"`{latest.get('model', '?')}`")
    lines.append("")
    lines.append(f"通过率：**{pass_rate(latest):.1f}%** "
                 f"({latest.get('passCount', 0)}/{latest.get('totalCases', 0)})")
    lines.append("")

    # 失败 case 表
    failed = [r for r in latest.get("results", []) if not r.get("pass")]
    if failed:
        lines.append("### 失败 case")
        lines.append("")
        lines.append("| ID | 类别 | 原因 |")
        lines.append("|----|----|----|")
        for r in failed:
            reason = (r.get("failReason") or "").replace("|", "\\|").replace("\n", " ")
            if len(reason) > 120:
                reason = reason[:120] + "..."
            lines.append(f"| {r.get('caseId', '?')} | {r.get('category', '?')} | {reason} |")
        lines.append("")
    else:
        lines.append("✅ 全部通过！")
        lines.append("")

    # 历史趋势（最近 10 次）
    if len(reports) > 1:
        lines.append("### 历史通过率（最近 10 次）")
        lines.append("")
        lines.append("| 时间 | 栈 | 模型 | 通过率 |")
        lines.append("|----|----|----|----|")
        for r in reports[-10:]:
            lines.append(
                f"| {r.get('runAt', '?')[:19]} | {r.get('stack', '?')} | "
                f"{r.get('model', '?')} | {pass_rate(r):.1f}% "
                f"({r.get('passCount', 0)}/{r.get('totalCases', 0)}) |"
            )
        lines.append("")

    return "\n".join(lines)


# ────────────────────────────────────────────────────────────
# HTML 模板（内联 CSS + Chart.js CDN）
# ────────────────────────────────────────────────────────────
HTML_TEMPLATE = Template(r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>Agent Eval 报告</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", sans-serif;
         max-width: 1100px; margin: 30px auto; padding: 0 20px; color: #2c3e50; background: #f8f9fb; }
  h1 { font-size: 28px; margin-bottom: 6px; }
  .subtitle { color: #7f8c8d; margin-bottom: 24px; font-size: 14px; }
  .banner { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white; padding: 24px 28px; border-radius: 12px;
            margin-bottom: 24px; box-shadow: 0 4px 14px rgba(102,126,234,0.3); }
  .banner h2 { margin: 0 0 8px 0; font-size: 14px; opacity: 0.85; text-transform: uppercase; letter-spacing: 1px; }
  .banner .rate { font-size: 48px; font-weight: 700; margin: 0; }
  .banner .meta { opacity: 0.9; margin-top: 8px; font-size: 14px; }
  .empty { background: white; padding: 40px; text-align: center; border-radius: 12px; color: #95a5a6; }
  .chart-card { background: white; padding: 20px; border-radius: 12px; margin-bottom: 24px;
                box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
  .chart-card h3 { margin-top: 0; }
  details.report { background: white; padding: 16px 20px; border-radius: 8px;
                   margin-bottom: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
  details.report > summary { cursor: pointer; display: flex; align-items: center;
                              gap: 12px; font-size: 14px; }
  details.report > summary::-webkit-details-marker { display: none; }
  details.report > summary::before { content: "▶"; display: inline-block; transition: transform 0.2s; font-size: 10px; }
  details.report[open] > summary::before { transform: rotate(90deg); }
  .badge { padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; }
  .badge-pass { background: #d4edda; color: #155724; }
  .badge-fail { background: #f8d7da; color: #721c24; }
  .badge-stack { background: #e7e9ec; color: #495057; }
  .badge-stack.java { background: #fbe9e7; color: #b71c1c; }
  .badge-stack.python { background: #e3f2fd; color: #0d47a1; }
  .case-detail { padding: 12px 16px; margin-top: 12px; background: #fafbfc;
                 border-left: 3px solid #e74c3c; font-size: 13px; }
  .case-detail.pass { border-left-color: #27ae60; }
  .case-detail .id { font-weight: 600; }
  .case-detail .reason { color: #c0392b; margin-top: 4px; }
  .case-detail .response { color: #7f8c8d; font-style: italic; margin-top: 4px;
                            font-family: ui-monospace, monospace; font-size: 12px;
                            white-space: pre-wrap; word-break: break-word;
                            max-height: 100px; overflow: auto; }
  .tools { font-size: 12px; color: #2980b9; margin-top: 4px; }
</style>
</head>
<body>
<h1>Agent Eval 报告</h1>
<p class="subtitle">生成于 $generated_at · 共 $report_count 次运行</p>

$banner_html

<div class="chart-card">
  <h3>通过率趋势</h3>
  <canvas id="trendChart" height="100"></canvas>
</div>

<h3 style="margin-top: 30px;">历次运行（最新在最上）</h3>
$reports_html

<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<script>
const DATA = $reports_json;

if (DATA.length > 0) {
  // 按 stack 分组
  const byStack = {};
  DATA.forEach(r => {
    if (!byStack[r.stack]) byStack[r.stack] = [];
    byStack[r.stack].push({
      x: r.runAt,
      y: r.totalCases > 0 ? (r.passCount / r.totalCases * 100) : 0
    });
  });

  const colors = { java: '#b71c1c', python: '#0d47a1', unknown: '#7f8c8d' };
  const datasets = Object.keys(byStack).map(stack => ({
    label: stack,
    data: byStack[stack],
    borderColor: colors[stack] || '#7f8c8d',
    backgroundColor: (colors[stack] || '#7f8c8d') + '33',
    tension: 0.2,
    pointRadius: 4
  }));

  new Chart(document.getElementById('trendChart'), {
    type: 'line',
    data: { datasets },
    options: {
      responsive: true,
      scales: {
        x: { type: 'category', title: { display: true, text: '运行时间' }},
        y: { min: 0, max: 100, title: { display: true, text: '通过率 (%)' }}
      },
      plugins: { legend: { position: 'top' }}
    }
  });
}
</script>
</body>
</html>""")


def _esc(s: str) -> str:
    """简化 HTML 转义。"""
    if s is None:
        return ""
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def render_banner(reports: list[dict]) -> str:
    if not reports:
        return '<div class="empty">尚无报告数据。先跑一次 eval：<br><code>cd finance-agent && ./mvnw test -Dgroups=evals -DexcludedGroups=</code></div>'
    latest = reports[-1]
    return f"""<div class="banner">
  <h2>最新运行 · {_esc(latest.get("stack", "?"))} · {_esc(latest.get("model", "?"))}</h2>
  <p class="rate">{pass_rate(latest):.1f}%</p>
  <p class="meta">{_esc(latest.get("passCount", 0))}/{_esc(latest.get("totalCases", 0))} 通过 ·
     {_esc(latest.get("runAt", ""))[:19]} ·
     <code>{_esc(latest.get("_file", ""))}</code></p>
</div>"""


def render_report_card(report: dict) -> str:
    rate = pass_rate(report)
    badge_cls = "badge-pass" if report.get("failCount", 0) == 0 else "badge-fail"
    stack = report.get("stack", "unknown")
    summary = (f'<summary>'
               f'<span class="badge {badge_cls}">{rate:.1f}%</span>'
               f'<span class="badge badge-stack {_esc(stack)}">{_esc(stack)}</span>'
               f'<span>{_esc(report.get("runAt", "?"))[:19]}</span>'
               f'<span style="color:#7f8c8d;">{_esc(report.get("model", "?"))}</span>'
               f'<span style="margin-left:auto;color:#7f8c8d;">'
               f'{_esc(report.get("passCount", 0))}/{_esc(report.get("totalCases", 0))}</span>'
               f'</summary>')
    cases_html = []
    for r in report.get("results", []):
        cls = "pass" if r.get("pass") else ""
        cases_html.append(f'<div class="case-detail {cls}">')
        cases_html.append(f'<div class="id">{_esc(r.get("caseId", "?"))} · '
                          f'<span style="color:#7f8c8d;">{_esc(r.get("category", "?"))}</span> · '
                          f'{("✅" if r.get("pass") else "❌")} · {r.get("durationMs", 0)}ms</div>')
        if r.get("toolsCalled"):
            cases_html.append(f'<div class="tools">tools: {_esc(", ".join(r.get("toolsCalled", [])))}</div>')
        if r.get("failReason"):
            cases_html.append(f'<div class="reason">{_esc(r.get("failReason", ""))}</div>')
        resp = r.get("responseText") or ""
        if resp:
            resp = resp[:500] + ("..." if len(resp) > 500 else "")
            cases_html.append(f'<div class="response">{_esc(resp)}</div>')
        cases_html.append('</div>')
    return f'<details class="report">{summary}{"".join(cases_html)}</details>'


def render_html(reports: list[dict]) -> str:
    # 报告卡片按时间倒序（最新在上）
    cards_html = "\n".join(render_report_card(r) for r in reversed(reports))
    # JSON 注入（仅保留必要字段，避免 HTML 过大）
    chart_data = [{
        "runAt": r.get("runAt", ""),
        "stack": r.get("stack", "unknown"),
        "passCount": r.get("passCount", 0),
        "totalCases": r.get("totalCases", 0),
    } for r in reports]
    return HTML_TEMPLATE.substitute(
        generated_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        report_count=len(reports),
        banner_html=render_banner(reports),
        reports_html=cards_html if reports else "",
        reports_json=json.dumps(chart_data, ensure_ascii=False),
    )


# ────────────────────────────────────────────────────────────
# main
# ────────────────────────────────────────────────────────────
def main() -> int:
    parser = argparse.ArgumentParser(description="生成 Eval 报告聚合视图")
    parser.add_argument("--format", choices=["html", "markdown"], default="html",
                        help="输出格式（默认 html 写入 index.html；markdown 输出到 stdout）")
    parser.add_argument("--reports-dir", type=Path, default=DEFAULT_REPORTS_DIR,
                        help="reports 目录（默认 evals/reports）")
    parser.add_argument("--output", type=Path, default=None,
                        help="HTML 输出路径（默认 <reports-dir>/index.html）")
    args = parser.parse_args()

    if not args.reports_dir.exists():
        print(f"⚠ reports 目录不存在: {args.reports_dir}", file=sys.stderr)
        return 1

    reports = load_reports(args.reports_dir)

    if args.format == "markdown":
        print(render_markdown(reports))
        return 0

    output = args.output or (args.reports_dir / "index.html")
    output.write_text(render_html(reports), encoding="utf-8")
    print(f"✅ HTML 报告已生成: {output.resolve()}")
    print(f"   {len(reports)} 份历史报告")
    return 0


if __name__ == "__main__":
    sys.exit(main())
