#!/usr/bin/env python3
"""一次性脚本：给 default 用户造 100 条流水到 backend (port 8080)"""
import json
import random
import urllib.request
from datetime import date, timedelta

random.seed(20260524)

BACKEND = "http://localhost:8080"
USER_ID = "default"
ACCOUNT_ID = 1
DAY_RANGE = 90
TODAY = date.today()

INCOME_TEMPLATES = [
    ("工资", (12000, 20000), ["月度工资", "工资发放", "薪资到账"]),
    ("兼职", (500, 3000), ["技术分享", "外包项目", "代写文章"]),
    ("理财", (100, 2000), ["基金分红", "余额宝收益", "股票分红", "理财赎回"]),
]

EXPENSE_TEMPLATES = [
    ("餐饮", (15, 200), ["早餐豆浆油条", "午餐工作餐", "晚餐外卖", "周末聚餐", "咖啡", "便利店午餐"]),
    ("交通", (5, 100), ["地铁通勤", "打车回家", "高铁出差", "加油", "停车费"]),
    ("购物", (50, 1500), ["京东购物", "淘宝下单", "超市采购", "服装", "数码配件", "家居用品"]),
    ("房租", (4000, 6000), ["月租"]),
    ("娱乐", (30, 500), ["电影票", "游戏充值", "演唱会门票", "桌游"]),
    ("医疗", (50, 500), ["门诊挂号", "药品", "体检"]),
    ("其他", (20, 300), ["快递", "充话费", "公益捐款", "杂项"]),
]

# 流水分布：约 22 收入，78 支出（贴近真实生活）
COUNT_INCOME = 22
COUNT_EXPENSE = 78

# 收入子项分布：工资每月1次，兼职、理财零散
income_plan = (
    [("工资", 0)] * 3            # 90 天 ≈ 3 个月
    + [("兼职", 0)] * 6
    + [("理财", 0)] * 13
)
random.shuffle(income_plan)
income_plan = income_plan[:COUNT_INCOME]

expense_plan = (
    [("餐饮", 0)] * 30
    + [("交通", 0)] * 15
    + [("购物", 0)] * 12
    + [("房租", 0)] * 3
    + [("娱乐", 0)] * 8
    + [("医疗", 0)] * 3
    + [("其他", 0)] * 7
)
random.shuffle(expense_plan)
expense_plan = expense_plan[:COUNT_EXPENSE]


def random_date():
    days_back = random.randint(0, DAY_RANGE - 1)
    return (TODAY - timedelta(days=days_back)).isoformat()


def random_amount(low, high):
    if high - low > 100:
        return round(random.uniform(low, high), 2)
    return random.randint(int(low), int(high)) + round(random.random(), 2)


def make_payload(t_type, category, amount_range, note_pool, fixed_date=None):
    low, high = amount_range
    return {
        "userId": USER_ID,
        "accountId": ACCOUNT_ID,
        "type": t_type,
        "amount": random_amount(low, high),
        "category": category,
        "note": random.choice(note_pool),
        "date": fixed_date or random_date(),
    }


payloads = []

# 工资固定每月 25 号附近
salary_count = sum(1 for c, _ in income_plan if c == "工资")
salary_dates = [
    (TODAY.replace(day=1) - timedelta(days=30 * i)).replace(day=min(25, 28)).isoformat()
    for i in range(salary_count)
]

for category, _ in income_plan:
    tpl = next(t for t in INCOME_TEMPLATES if t[0] == category)
    if category == "工资":
        d = salary_dates.pop()
        payloads.append(make_payload("INCOME", tpl[0], tpl[1], tpl[2], fixed_date=d))
    else:
        payloads.append(make_payload("INCOME", tpl[0], tpl[1], tpl[2]))

# 房租固定每月 1 号附近
rent_count = sum(1 for c, _ in expense_plan if c == "房租")
rent_dates = [
    (TODAY.replace(day=1) - timedelta(days=30 * i)).replace(day=min(3, 28)).isoformat()
    for i in range(rent_count)
]

for category, _ in expense_plan:
    tpl = next(t for t in EXPENSE_TEMPLATES if t[0] == category)
    if category == "房租":
        d = rent_dates.pop() if rent_dates else None
        payloads.append(make_payload("EXPENSE", tpl[0], tpl[1], tpl[2], fixed_date=d))
    else:
        payloads.append(make_payload("EXPENSE", tpl[0], tpl[1], tpl[2]))

assert len(payloads) == 100, f"应该是 100 条但是有 {len(payloads)}"

# 提交
created = 0
failed = 0
for p in payloads:
    body = json.dumps(p).encode("utf-8")
    req = urllib.request.Request(
        f"{BACKEND}/api/transactions",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            if resp.status == 200:
                created += 1
            else:
                failed += 1
                print(f"HTTP {resp.status}: {p}")
    except Exception as e:
        failed += 1
        print(f"EXC: {e} | {p}")

print(f"\n创建成功: {created}/100, 失败: {failed}")
