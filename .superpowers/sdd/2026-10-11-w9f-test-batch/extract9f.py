#!/usr/bin/env python3
"""W9f 测试批（2026-10-11）读数抽取器 —— 真世界日志 → 六类读数（窗口逐条标注）。

用法：
  python3 extract9f.py <logfile> [--tag X] [--window-end N]
  python3 extract9f.py --conservation <store-dir> <tag>        # 用 checkpoint 1.json / 最新 .json 算守恒

★ 只读；不写任何状态。窗口由 --window-end 标注（§九.4 口径标注）。
★ 与 W9e 的 extract.py 的分工：那份抽"八类"，本份按 W9f 派单的六类重排，
  并补齐"市场轮指纹（轮数/零成交/Σfills/min·中位·max）"与"逐户跑商明细"两块（W9e 未抽）。
"""
import json
import os
import re
import statistics
import sys
from collections import Counter, defaultdict

sys.path.insert(0, "/tmp/w6acc")


def field(line, key, cast=str, default=None):
    m = re.search(r"(?:^|\s)%s=(\S+)" % re.escape(key), line)
    if not m:
        return default
    try:
        return cast(m.group(1))
    except (TypeError, ValueError):
        return default


def event(line):
    return field(line, "event", str, "")


def kv_map(line, key):
    m = re.search(r"(?:^|\s)%s=\{([^}]*)\}" % re.escape(key), line)
    if not m:
        return {}
    out = {}
    for part in m.group(1).split(","):
        if "=" in part:
            k, v = part.split("=", 1)
            try:
                out[k.strip()] = int(v)
            except ValueError:
                pass
    return out


def conservation(store, tag):
    import w6lib

    d = os.path.join(store, "checkpoints", "main")
    files = sorted(((int(n[:-5]), os.path.join(d, n)) for n in os.listdir(d) if n.endswith(".json")))
    if not files:
        print("（%s：没有 checkpoint ⇒ 守恒读不到）" % tag)
        return
    first_tick, first = files[0]
    last_tick, last = files[-1]
    a, b = w6lib.extract(first, first_tick), w6lib.extract(last, last_tick)
    print("=" * 100)
    print("W9f ⑥ 守恒：tag=%s  store=%s  checkpoint tick %d → %d" % (tag, store, first_tick, last_tick))
    print("=" * 100)
    print("\n[货币逐币种]（毫）")
    keys = sorted(set(a["money"]) | set(b["money"]))
    bad = 0
    for k in keys:
        x, y = a["money"].get(k, 0), b["money"].get(k, 0)
        flag = "" if x == y else "  ★ 变了 Δ=%+d" % (y - x)
        if x != y:
            bad += 1
        print("  %-12s %16d → %16d%s" % (k, x, y, flag))
    print("  合计 %d → %d（Δ=%+d）；★ 不等的币种数 = %d" % (a["money_total"], b["money_total"], b["money_total"] - a["money_total"], bad))
    print("  发行记录（按 kind 求和，毫）: %s" % b.get("issuance_by_kind"))
    print("  ★ 发行恒等式 Σ(MINT/ISSUE) − Σ(WITHDRAWAL) = %s" % b.get("issuance_identity"))
    delta = {k: b["money"].get(k, 0) - a["money"].get(k, 0) for k in keys}
    delta = {k: v for k, v in delta.items() if v != 0}
    # 窗口内的发行 = 排除创世两笔（GENESIS_ENDOWMENT / INITIAL_ENDOWMENT）后的发行恒等式
    window_issue = defaultdict(int)
    for kind, per_cur in (b.get("issuance_by_kind") or {}).items():
        if kind in ("GENESIS_ENDOWMENT", "INITIAL_ENDOWMENT"):
            continue
        sign = -1 if kind == "WITHDRAWAL" else 1
        for cur, amt in per_cur.items():
            window_issue[cur] += sign * amt
    window_issue = {k: v for k, v in window_issue.items() if v != 0}
    reconcile = all(delta.get(k, 0) == window_issue.get(k, 0) for k in set(delta) | set(window_issue))
    print("  ★ 逐币种 Δ货币 == 窗口内发行（非创世）⇒ %s（Δ=%s vs 发行=%s）" % (reconcile, delta, window_issue))
    print("\n[商品库存 总量]（毫；★ 生产/消费 ⇒ 逐值不守恒是正常的，这里只作读数）")
    gk = sorted(set(a["goods"]) | set(b["goods"]))
    for k in gk[:40]:
        print("  %-14s %16d → %16d" % (k, a["goods"].get(k, 0), b["goods"].get(k, 0)))
    print("\n[作业资本产权份额（economy.assetShares，按 AssetKind 求和 quantity）]")

    def shares_by_kind(path_):
        env = json.load(open(path_))
        ed = json.loads(env["modules"]["economy"])
        ed = ed.get("data", ed)
        out = defaultdict(int)
        n = 0
        for sh in (ed.get("assetShares") or {}).values():
            out[sh.get("asset")] += sh.get("quantity", 0)
            n += 1
        return out, n

    sa, na = shares_by_kind(first)
    sb, nb = shares_by_kind(last)
    for k in sorted(set(sa) | set(sb)):
        print("  %-10s %16d → %16d%s" % (k, sa.get(k, 0), sb.get(k, 0),
                                         "" if sa.get(k, 0) == sb.get(k, 0) else "  ★ 变了"))
    print("  份额条数：%d → %d" % (na, nb))

    print("\n[人口 / 户数]")
    print("  social 人口 %d → %d；economy 人口 %d → %d；户数 %d → %d" % (
        a["pop"]["social_total"], b["pop"]["social_total"],
        a["pop"]["economy_total"], b["pop"]["economy_total"],
        a["pop"]["households"], b["pop"]["households"]))


def main():
    path = sys.argv[1]
    window_end = int(sys.argv[sys.argv.index("--window-end") + 1]) if "--window-end" in sys.argv else None
    tag = sys.argv[sys.argv.index("--tag") + 1] if "--tag" in sys.argv else path

    events = Counter()
    rounds = []           # (day, fills)
    market_fill_rows = 0
    moves = []
    carried = []
    candidates = []
    applied = []
    lane_trunc = defaultdict(lambda: [0, 0])
    capacity_gap = defaultdict(lambda: [0, 0])
    infeasible = Counter()
    merchant_created = []
    ondemand = []
    per_day_moves = Counter()

    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            name = event(line)
            if not name:
                continue
            day = field(line, "day", int, -1)
            if window_end is not None and day > window_end:
                continue
            events[name] += 1
            if name == "MARKET_REPORT":
                rounds.append((day, field(line, "fills", int, 0)))
            elif name == "MARKET_FILL":
                market_fill_rows += 1
            elif name == "MIGRATION_MOVE":
                moves.append(dict(
                    day=day, source=field(line, "source"), target=field(line, "target"),
                    targetHex=field(line, "targetHex"), targetMode=field(line, "targetMode"),
                    population=field(line, "population", int, 0),
                    speed=field(line, "speedPerMille", int, 0),
                    tier=field(line, "targetTierIndex", int, -1),
                    reason=field(line, "reason"),
                    money=field(line, "moneyMilli", int, 0)))
                per_day_moves[day] += 1
            elif name == "MIGRATION_INVENTORY_CARRIED":
                carried.append(line.strip())
            elif name == "MIGRATION_APPLIED":
                applied.append((day, field(line, "moves", int, 0),
                                field(line, "rowsBefore", int, -1), field(line, "rowsAfter", int, -1)))
            elif name == "MODE_MIGRATION_CANDIDATES":
                candidates.append((day, field(line, "skippedShellNoLabor", int, 0),
                                   field(line, "skippedShellNoPopulation", int, 0)))
            elif name == "MERCHANT_TRADE_INDUSTRY_CREATED":
                merchant_created.append(line.strip())
            elif name.startswith("ONDEMAND_"):
                ondemand.append(line.strip())
            elif name == "MERCHANT_CAPACITY_LANE_TRUNCATED":
                r = field(line, "reason", str, "?")
                lane_trunc[r][0] += 1
                lane_trunc[r][1] += field(line, "unallocatedMilli", int, 0)
            elif name == "CAPACITY_DEMAND_GAP_WHY":
                r = field(line, "reason", str, "?")
                capacity_gap[r][0] += 1
                capacity_gap[r][1] += field(line, "unservedWorkMilli", int, 0)
            elif name in ("MODE_MIGRATION_PROSPECT_INFEASIBLE", "MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED"):
                infeasible[field(line, "reason", str, "?")] += 1

    print("=" * 100)
    print("W9f 读数：tag=%s  window=%s  文件=%s" % (tag, "0..%s" % window_end if window_end else "全窗", path))
    print("=" * 100)

    # ① 市场
    fills = [f for _, f in rounds]
    zero = sum(1 for f in fills if f == 0)
    print("\n[① 市场轮] 轮数 = %d；★ 零成交轮次 = %d；Σfills = %d（MARKET_FILL 行 = %d）" % (
        len(rounds), zero, sum(fills), market_fill_rows))
    if fills:
        print("  min / 中位 / max = %d / %s / %d" % (min(fills), statistics.median(fills), max(fills)))
    days = [d for d, _ in rounds]
    print("  同日两轮 = %s；相邻最大间隔 = %s 天" % (
        "是" if len(days) != len(set(days)) else "否",
        max((b - a for a, b in zip(days, days[1:])), default=0)))
    print("  分布（笔数:轮数）:", ", ".join("%d×%d" % (k, v) for k, v in sorted(Counter(fills).items())))
    print("  ★ Σfills == MARKET_FILL 行数 ⇒ %s" % (sum(fills) == market_fill_rows))

    # ② 转业
    print("\n[② 转业] MIGRATION_MOVE 笔数 = %d" % len(moves))
    print("  reason:", dict(Counter(m["reason"] for m in moves)))
    sp = sorted(m["speed"] for m in moves)
    if sp:
        print("  速度 min/中位/max = %d / %s / %d；1000‰ 笔数 = %d" % (
            sp[0], statistics.median(sp), sp[-1], sum(1 for s in sp if s == 1000)))
    print("  targetMode:", dict(Counter(m["targetMode"] for m in moves)))
    print("  ★ targetTierIndex 分布:", ", ".join(
        "%s×%d" % (k, v) for k, v in sorted(Counter(m["tier"] for m in moves).items(), key=lambda x: str(x[0]))))
    print("  单笔最大搬迁人口 = %d；Σ搬迁人口 = %d" % (
        max((m["population"] for m in moves), default=0), sum(m["population"] for m in moves)))
    print("\n  ★ 落点（MIGRATION_APPLIED，%d 轮）：" % len(applied))
    merged = sum(1 for _, _, b, a in applied if b == a and b >= 0)
    for day, n, b, a in applied:
        print("    day=%-4d moves=%-4d rowsBefore=%d rowsAfter=%d %s" % (
            day, n, b, a, "（并入既有户 ⇒ 行数不变）" if b == a else "★ 新建户！"))
    print("    ⇒ rowsBefore == rowsAfter 的轮数 = %d / %d（新建户行 0 个）" % (merged, len(applied)))
    print("\n  ★ 随迁库存（MIGRATION_INVENTORY_CARRIED）= %d 笔（迁移 %d 笔 ⇒ %s）" % (
        len(carried), len(moves), "每笔都带" if len(carried) == len(moves) else "★ 有迁移不带库存"))
    haul = sum(1 for c in carried if re.search(r"goods=\{[^}]*\bhaul\b", c))
    grain = sum(1 for c in carried if re.search(r"goods=\{[^}]*\bgrain\b", c))
    print("    带 haul 的笔数 = %d（应为 0 = D2 排除生效）；带 grain 的笔数 = %d / %d" % (haul, grain, len(carried)))
    for c in carried[:5]:
        m = re.search(r"day=(\d+).*?source=(\S+) -> (\S+).*?(goods=\{[^}]*\})", c)
        print("    样本:", c[-190:] if not m else c[:220])
    print("\n  ★ 壳户计数（MODE_MIGRATION_CANDIDATES，%d 轮）ΣnoPopulation=%d ΣnoLabor=%d" % (
        len(candidates), sum(c[2] for c in candidates), sum(c[1] for c in candidates)))
    print("  逐户不可行 / 实物腿拒:", dict(infeasible))

    # ③ 运粮
    print("\n[③ 运粮 / 运力]")
    print("  MERCHANT_CAPACITY_LANE_TRUNCATED（逐理由：笔数 / ΣunallocatedMilli）:")
    for r, (n, milli) in sorted(lane_trunc.items(), key=lambda x: -x[1][0]):
        print("    %-46s %8d 笔 / Σ%14d 毫" % (r, n, milli))
    for key in ("no-carrier-with-deliverable-service", "out-of-derived-radius"):
        n, milli = lane_trunc.get(key, [0, 0])
        print("  ★ %s = %d 笔 / %d 毫" % (key, n, milli))
    print("  CAPACITY_DEMAND_GAP_WHY（另一口径，逐理由）:")
    for r, (n, milli) in sorted(capacity_gap.items(), key=lambda x: -x[1][0]):
        print("    %-58s %8d 笔 / Σ%14d 毫" % (r, n, milli))

    # ④ 跑商
    print("\n[④ 跑商]")
    for key in ("MERCHANT_TRADE_INDUSTRY_CREATED", "ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE",
                "ONDEMAND_TRADE_MIGRATION_SKIPPED"):
        print("  %s = %d" % (key, events.get(key, 0)))
    for line in merchant_created[:10]:
        print("    创建:", line[:200])
    for line in ondemand[:10]:
        print("    按需:", line[:220])
    merchants = [m for m in moves if m["targetMode"] == "merchant"]
    print("  MIGRATION_MOVE targetMode=merchant = %d" % len(merchants))
    by_source = Counter((m["source"], m["targetHex"]) for m in merchants)
    for (src, hexv), n in sorted(by_source.items(), key=lambda x: -x[1]):
        days = sorted(m["day"] for m in merchants if m["source"] == src and m["targetHex"] == hexv)
        pop = sum(m["population"] for m in merchants if m["source"] == src and m["targetHex"] == hexv)
        money = sum(m["money"] for m in merchants if m["source"] == src and m["targetHex"] == hexv)
        print("    %-42s → hex=%-6s %3d 笔 / Σ人口 %4d / Σ钱 %d 毫；日=%s" % (
            src, hexv, n, pop, money, days[:12]))

    # ⑤ 不回归（事件计数）
    print("\n[⑤ 不回归（事件计数，全窗）]")
    groups = {
        "W4 阶层滑落": ("CLASS_SLIDE_ROUND", "CLASS_SLIDE_SETTLED", "CLASS_SLIDE", "CLASS_TRANSITIONS"),
        "W5 铸币": ("MINT_SETTLED", "MINT_LIMIT_WINDOW", "MINT_LAST_CYCLE_READOUT", "MINT_PRODUCTION"),
        "W5.5 货币市场": ("MONEY_MARKET_ROUND", "MONEY_MARKET_WHY", "MONEY_MARKET_UNFILLED",
                       "MONEY_MARKET_RATE_WRITTEN", "MONEY_MARKET_GOV_SUPPLY_SUPPRESSED",
                       "MONEY_MARKET_BUDGET_TOPUP"),
        "W7 运力/运费": ("HAUL_PRICE_SIGNAL", "HAUL_SERVICE_DELIVERED", "HAUL_PAID", "HAUL_SETTLED",
                     "HAUL_EXPIRED", "LOGISTICS_ALLOCATED"),
        "W8 债务语义": ("DEBT_REPAY_DECISION", "DEBT_STRESS_THRESHOLD_FIRED", "MIGRATION_DEBT_MOVED",
                     "MIGRATION_DEBT_SELF_NETTED", "DEBT_WRITEOFF"),
        "市场/迁移": ("MARKET", "MARKET_REPORT", "MARKET_FILL", "MIGRATION_PLAN", "MIGRATION_APPLIED",
                   "MIGRATION_MOVE", "MIGRATION_INVENTORY_CARRIED", "MODE_MIGRATION_CANDIDATES"),
        "异常/500": ("GUI_REQUEST_FAILED",),
        "日循环": ("DAY_START", "DAY_END"),
    }
    for g, keys in groups.items():
        print("  %-16s %s" % (g, ", ".join("%s=%d" % (k, events.get(k, 0)) for k in keys)))
    extra = {k: v for k, v in events.items() if any(t in k for t in ("EXCEPTION", "ERROR", "FAILED", "STACK"))}
    print("  ★ 名字里含 EXCEPTION/ERROR/FAILED/STACK 的事件:", dict(extra) if extra else "（无）")


if __name__ == "__main__":
    if sys.argv[1] == "--conservation":
        conservation(sys.argv[2], sys.argv[3])
    else:
        main()
