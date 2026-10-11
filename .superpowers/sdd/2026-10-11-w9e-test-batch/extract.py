#!/usr/bin/env python3
"""W9e 测试批读数抽取器：从真世界日志（w6run 的 .log / .log.stripped）抽八类读数。

用法：python3 extract.py <logfile> [--window-end N] [--tag X]
★ 只读；不写任何状态。统计窗口由 --window-end 标注（§九.4 口径标注）。
"""
import re
import statistics
import sys
from collections import Counter, defaultdict


def field(line, key, cast=str, default=None):
    m = re.search(r"(?:^|\s)%s=(\S+)" % re.escape(key), line)
    if not m:
        return default
    raw = m.group(1)
    try:
        return cast(raw)
    except (TypeError, ValueError):
        return default


def event(line):
    return field(line, "event", str, "")


def kv_map(line, key):
    """把 reasons={A=1, B=2} 这样的字段解成 dict。"""
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


def main():
    path = sys.argv[1]
    window_end = None
    if "--window-end" in sys.argv:
        window_end = int(sys.argv[sys.argv.index("--window-end") + 1])
    tag = sys.argv[sys.argv.index("--tag") + 1] if "--tag" in sys.argv else path

    moves = []
    carried = []
    candidates = []
    infeasible = Counter()
    events = Counter()
    round_rows = []
    capacity_gap = defaultdict(lambda: [0, 0])  # reason -> [rows, sum unservedWorkMilli]
    lane_trunc = defaultdict(lambda: [0, 0])  # reason -> [rows, sum unallocatedMilli]
    market_fill_rows = 0
    debt_events = Counter()
    reasons_store = Counter()

    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            name = event(line)
            if not name:
                continue
            day = field(line, "day", int, -1)
            if window_end is not None and day > window_end:
                continue
            events[name] += 1
            if name == "MIGRATION_MOVE":
                moves.append(
                    dict(
                        day=day,
                        source=field(line, "source"),
                        target=field(line, "target"),
                        targetHex=field(line, "targetHex"),
                        targetMode=field(line, "targetMode"),
                        population=field(line, "population", int, 0),
                        speed=field(line, "speedPerMille", int, 0),
                        tier=field(line, "targetTierIndex", int, -1),
                        reason=field(line, "reason"),
                    )
                )
            elif name == "MIGRATION_INVENTORY_CARRIED":
                carried.append(line.strip())
            elif name == "MODE_MIGRATION_CANDIDATES":
                candidates.append(dict(
                    day=day,
                    noLabor=field(line, "skippedShellNoLabor", int, 0),
                    noPopulation=field(line, "skippedShellNoPopulation", int, 0),
                    moves=field(line, "moves", int, 0),
                    candidates=field(line, "candidateRows", int, 0),
                    merchant=field(line, "merchantCandidates", int, 0),
                ))
            elif name in (
                "MODE_MIGRATION_PROSPECT_INFEASIBLE",
                "MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED",
            ):
                infeasible[field(line, "reason", str, "?")] += 1
            elif name == "MARKET_REPORT":
                round_rows.append((day, field(line, "fills", int, 0)))
                for k, v in kv_map(line, "reasons").items():
                    reasons_store[k] += v
            elif name == "MARKET_FILL":
                market_fill_rows += 1
            elif name == "CAPACITY_DEMAND_GAP_WHY":
                r = field(line, "reason", str, "?")
                capacity_gap[r][0] += 1
                capacity_gap[r][1] += field(line, "unservedWorkMilli", int, 0)
            elif name == "MERCHANT_CAPACITY_LANE_TRUNCATED":
                r = field(line, "reason", str, "?")
                lane_trunc[r][0] += 1
                lane_trunc[r][1] += field(line, "unallocatedMilli", int, 0)
            elif name.startswith("MIGRATION_DEBT"):
                debt_events[name] += 1
            elif name.startswith("MIGRATION_") and "DEBT" in name:
                debt_events[name] += 1

    print("=" * 100)
    print("W9e 读数：tag=%s  window=%s  文件=%s" % (tag, "0..%s" % window_end if window_end else "全部", path))
    print("=" * 100)

    # ── ① 迁移笔数 / 速度 / 人口 / 档位 ──
    print("\n[① 迁移（MIGRATION_MOVE）] 笔数 = %d" % len(moves))
    by_reason = Counter(m["reason"] for m in moves)
    print("  reason 分布:", dict(by_reason))
    speeds = Counter(m["speed"] for m in moves)
    print("  速度分布(‰):", ", ".join("%d×%d" % (k, v) for k, v in sorted(speeds.items())))
    if moves:
        sp = sorted(m["speed"] for m in moves)
        print("  速度 min/中位/max = %d / %s / %d" % (sp[0], statistics.median(sp), sp[-1]))
        print("  速度=1000‰ 的笔数 = %d" % sum(1 for s in sp if s == 1000))
    pops = Counter(m["population"] for m in moves)
    print("  人口分布:", ", ".join("%d×%d" % (k, v) for k, v in sorted(pops.items())))
    print("  targetMode 分布:", dict(Counter(m["targetMode"] for m in moves)))
    tiers = Counter(m["tier"] for m in moves)
    print("  ★ targetTierIndex 分布:", ", ".join("%s×%d" % (k, v) for k, v in sorted(tiers.items(), key=lambda x: str(x[0]))))

    # ── ② 人口闸：逐笔"速度折算 vs 上限折算" ──
    print("\n[② 人口闸（逐笔样本，最多 20 行）]")
    print("  %-6s %-8s %-10s %-12s %-12s %-8s %s" % ("day", "pop", "speed‰", "速度折算", "上限折算", "实际", "上限生效?"))
    shown = 0
    cap_hit = 0
    cap_unknown = 0
    for m in sorted(moves, key=lambda x: -x["speed"]):
        if m["speed"] <= 0:
            continue
        by_speed = max(1, m["population"] * m["speed"] // 1000)
        src_pop = None
        # 源户人口需要从 MODE_MIGRATION_CANDIDATES 之类读；这里用"实际人口 + 已知速度"反推下界
        cap_hit += 0
        if shown < 20:
            print("  %-6d %-8d %-10d %-12s %-12s %-8d %s" % (
                m["day"], m["population"], m["speed"], by_speed, "?", m["population"],
                "?"))
            shown += 1
    # 逐笔 spop 从 carried 行里读（MIGRATION_INVENTORY_CARRIED 带 source population）
    print("\n  ★ 说明：完整的人口闸判读见 [②b]（用 MIGRATION_INVENTORY_CARRIED 的源户人口对齐）")

    # ── ②b 用 carried 行的源户人口对齐 ──
    per_move = []
    for line in carried:
        per_move.append(dict(
            day=field(line, "day", int, -1),
            source=field(line, "source"),
            target=field(line, "target"),
            population=field(line, "population", int, 0),
            goods=re.search(r"goods=\{([^}]*)\}", line).group(1) if "goods={" in line else "",
        ))
    print("\n[②b 带库存随迁（MIGRATION_INVENTORY_CARRIED，%d 笔）]" % len(per_move))
    for row in per_move[:8]:
        print("  day=%d %s -> %s pop=%d goods={%s}" % (row["day"], row["source"], row["target"], row["population"], row["goods"]))
    print("  ★ goods 里出现 haul 的笔数 = %d（应为 0）" % sum(1 for r in per_move if "haul" in r["goods"]))
    print("  ★ goods 里带 grain 的笔数 = %d / %d" % (sum(1 for r in per_move if "grain" in r["goods"]), len(per_move)))

    # ── ③ 壳户噪声 ──
    print("\n[③ 壳户噪声]")
    print("  轮级 MODE_MIGRATION_CANDIDATES 行数 = %d" % len(candidates))
    if candidates:
        print("  ΣskippedShellNoLabor = %d，ΣskippedShellNoPopulation = %d" % (
            sum(c["noLabor"] for c in candidates), sum(c["noPopulation"] for c in candidates)))
        first, last = candidates[0], candidates[-1]
        print("  首行 day=%d: noPopulation=%d noLabor=%d moves=%d" % (first["day"], first["noPopulation"], first["noLabor"], first["moves"]))
        print("  末行 day=%d: noPopulation=%d noLabor=%d moves=%d" % (last["day"], last["noPopulation"], last["noLabor"], last["moves"]))
    print("  逐户 PROSPECT_INFEASIBLE / IN_KIND_REFUSED 计数:", dict(infeasible))
    print("  ★ no-labor 行数 = %d，no-tool-input 行数 = %d（W9d 对照：sw 8,844 / tp 17,610 行 ⇒ 应 0）" % (
        infeasible.get("no-labor", 0), infeasible.get("no-tool-input", 0)))

    # ── ④ 跑商 / 运力 ──
    print("\n[④ 跑商与运粮]")
    for key in ("MERCHANT_TRADE_INDUSTRY_CREATED", "ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE",
                "ONDEMAND_TRADE_MIGRATION_SKIPPED", "MERCHANT_TRADE_SKIPPED"):
        print("  %s = %d" % (key, events.get(key, 0)))
    print("  MIGRATION_MOVE targetMode=merchant = %d" % sum(1 for m in moves if m["targetMode"] == "merchant"))
    print("  运力归因（CAPACITY_DEMAND_GAP_WHY）:")
    for r, (rows, milli) in sorted(capacity_gap.items(), key=lambda x: -x[1][0]):
        print("    %-36s %8d 笔 / Σ%14d 毫" % (r, rows, milli))
    print("  运力截断（MERCHANT_CAPACITY_LANE_TRUNCATED）:")
    for r, (rows, milli) in sorted(lane_trunc.items(), key=lambda x: -x[1][0]):
        print("    %-36s %8d 笔 / Σ%14d 毫" % (r, rows, milli))
    print("  ★ no-carrier-with-deliverable-service = CAPACITY 口径 %d 笔 / %d 毫；LANE_TRUNCATED 口径 %d 笔 / %d 毫" % (
        capacity_gap["no-carrier-with-deliverable-service"][0], capacity_gap["no-carrier-with-deliverable-service"][1],
        lane_trunc["no-carrier-with-deliverable-service"][0], lane_trunc["no-carrier-with-deliverable-service"][1]))
    print("  ★ out-of-derived-radius = CAPACITY 口径 %d 笔 / %d 毫；LANE_TRUNCATED 口径 %d 笔 / %d 毫" % (
        capacity_gap["out-of-derived-radius"][0], capacity_gap["out-of-derived-radius"][1],
        lane_trunc["out-of-derived-radius"][0], lane_trunc["out-of-derived-radius"][1]))
    print("  HAUL_* 事件:", {k: v for k, v in sorted(events.items()) if k.startswith("HAUL_")})
    print("  MIGRATION_DEBT_* 事件:", dict(debt_events))

    # ── ⑤ 市场轮（若日志含 MARKET_REPORT） ──
    print("\n[⑤ 市场轮（MARKET_REPORT）]")
    round_rows.sort()
    fills = [f for _, f in round_rows]
    if fills:
        dist = Counter(fills)
        print("  轮数 = %d  零成交轮次 = %d %s" % (
            len(fills), sum(1 for f in fills if f == 0), [d for d, f in round_rows if f == 0][:25]))
        print("  Σfills = %d  == MARKET_FILL 行数 %d ? %s" % (sum(fills), market_fill_rows, sum(fills) == market_fill_rows))
        print("  min/中位/max = %d / %s / %d" % (min(fills), statistics.median(fills), max(fills)))
        print("  分布:", ", ".join("%d×%d" % (k, v) for k, v in sorted(dist.items()))[:600])
        dups = len(round_rows) - len(set(d for d, _ in round_rows))
        gaps = [round_rows[i][0] - round_rows[i - 1][0] for i in range(1, len(round_rows))]
        print("  同日两轮 = %s；相邻最大间隔 = %s" % (dups, max(gaps) if gaps else "-"))
    else:
        print("  （本日志无 MARKET_REPORT 行）")

    # ── ⑥ 其他读数事件（不回归面） ──
    print("\n[⑥ 其他事件计数（不回归面）]")
    for prefix in ("MINT_", "MONEY_MARKET", "DEBT_", "CLASS_", "SLIDE_", "STRATUM", "DAY_START",
                   "MIGRATION_IN_KIND", "MODE_MIGRATION", "MIGRATION_CARRY", "MIGRATION_DEBT"):
        hits = {k: v for k, v in events.items() if k.startswith(prefix)}
        if hits:
            print("  %s*: %s" % (prefix, hits))
    print("\n  全部事件（前 60 名）:", Counter(events).most_common(60))


if __name__ == "__main__":
    main()
