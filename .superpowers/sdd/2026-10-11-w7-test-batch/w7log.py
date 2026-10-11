#!/usr/bin/env python3
"""W7 测试批：真世界日志 → 本批六类判据的读数（**逐行流式**，不整文件载入 —— 2026-10-11 实测 w6run.py 的
"整份日志读进内存"后处理在 4 GB 的 tp 日志上被 OOM 杀，见批账本 §装置）。

用法：python3 w7log.py <tag> <log-file> [<window-a> <window-b> ...]
  窗口 = 形如 "601:689" 的闭区间（按 event 行里的 day= 字段）；不传则给全程与 0-360 / 361-600 / 601-end。

读数（每个都标窗口 —— AGENTS §九.4）：
  ① HAUL_PRICE_SIGNAL：逐市场逐日 D/S/未服务/α/牌价前后/providersWithService + 轨迹极值
  ② LOGISTICS_SERVICE_SUPPLY_REJECTED / MERCHANT_CAPACITY_LANE_TRUNCATED：按理由计数 + ΣtruncatedMilli
  ③ MERCHANT_TRADE_INDUSTRY_CREATED / ONDEMAND_TRADE_MIGRATION_SKIPPED / MIGRATION_MOVE(targetMode)
  ④ MODE_MIGRATION_CANDIDATES 汇总 + MODE_MIGRATION_PROSPECT_INFEASIBLE 按理由
  ⑤ PRIMARY_MODE_CHANGED + no-bargaining-power 出现次数
  ⑥ MARKET_REPORT（轮数/零成交轮/成交笔数）+ MARKET_FILL（笔数/成交额/运费）
"""
import gzip
import json
import re
import sys
import collections

FIELD = re.compile(r'(\w+)=("[^"]*"|\{[^}]*\}|[^\s]*)')


def fields(line):
    return {m.group(1): m.group(2) for m in FIELD.finditer(line)}


def num(raw, default=0):
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def open_maybe_gz(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", errors="replace")
    return open(path, "r", errors="replace")


def main():
    tag, path = sys.argv[1], sys.argv[2]
    windows = [(int(a), int(b)) for a, b in (w.split(":") for w in sys.argv[3:])]
    out = {
        "tag": tag, "log": path, "windows": windows,
        "haul": collections.defaultdict(dict),          # market -> day -> reading
        "reject": collections.Counter(),                # window|reason -> count
        "reject_milli": collections.Counter(),          # window|reason -> Σ truncatedMilli
        "truncated": collections.Counter(),
        "truncated_milli": collections.Counter(),
        "moves": collections.Counter(),                 # window|targetMode -> count
        "merchant_moves": [],                           # (window, day, source, target, targetHex)
        "trade_created": [],                            # (window, day, hex, industry)
        "ondemand_skipped": [],                         # (window, day, source, targetHex, pop, carried)
        "prospect_infeasible": collections.Counter(),   # window|reason -> count
        "candidates_rounds": 0,
        "candidates_last": None,
        "primary_changed": collections.Counter(),
        "no_bargaining_power": collections.Counter(),
        "rounds": collections.defaultdict(list),        # window -> [(day, fills)]
        "fills": collections.Counter(),                 # window|commodity -> count
        "fills_qty": collections.Counter(),
        "fills_value": collections.Counter(),           # window|paymentCurrency -> Σ goodsPaymentMilli
        "fills_freight": collections.Counter(),         # window|freightCurrency -> Σ freightMilli
        "days_seen": set(),
    }

    def window_of(day):
        for a, b in windows:
            if a <= day <= b:
                return "%d:%d" % (a, b)
        return "other"

    with open_maybe_gz(path) as fh:
        for line in fh:
            at = line.find("event=")
            if at < 0:
                continue
            end = at + 6
            while end < len(line) and not line[end].isspace():
                end += 1
            event = line[at + 6:end]
            if event == "HAUL_PRICE_SIGNAL":
                f = fields(line)
                day = num(f.get("day"))
                market = f.get("market", "?")
                out["haul"][market][day] = {
                    "D": num(f.get("haulDemandWorkMilli")),
                    "S": num(f.get("haulSupplyWorkMilli")),
                    "unserved": num(f.get("unservedWorkMilli")),
                    "alpha": num(f.get("alphaPerMille")),
                    "before": num(f.get("priceMilliBefore")),
                    "after": num(f.get("priceMilliAfter")),
                    "providers": num(f.get("providersWithService")),
                }
            elif event == "LOGISTICS_SERVICE_SUPPLY_REJECTED":
                f = fields(line)
                w = window_of(num(f.get("day")))
                reason = f.get("reason", "?")
                out["reject"][w + "|" + reason] += 1
                out["reject_milli"][w + "|" + reason] += num(f.get("truncatedMilli"))
            elif event == "MERCHANT_CAPACITY_LANE_TRUNCATED":
                f = fields(line)
                w = window_of(num(f.get("day")))
                out["truncated"][w] += 1
                out["truncated_milli"][w] += num(f.get("unallocatedMilli"))
            elif event == "MIGRATION_MOVE":
                f = fields(line)
                day = num(f.get("day"))
                w = window_of(day)
                mode = f.get("targetMode", "?")
                out["moves"][w + "|" + mode] += 1
                if mode == "merchant":
                    out["merchant_moves"].append(
                        [w, day, f.get("source"), f.get("target"), f.get("targetHex")])
            elif event == "MERCHANT_TRADE_INDUSTRY_CREATED":
                f = fields(line)
                out["trade_created"].append(
                    [window_of(num(f.get("day"))), num(f.get("day")), f.get("hex"),
                     f.get("industry")])
            elif event == "ONDEMAND_TRADE_MIGRATION_SKIPPED":
                f = fields(line)
                out["ondemand_skipped"].append(
                    [window_of(num(f.get("day"))), num(f.get("day")), f.get("source"),
                     f.get("targetHex"), num(f.get("population")), num(f.get("carriedCattle"))])
            elif event == "MODE_MIGRATION_PROSPECT_INFEASIBLE":
                f = fields(line)
                out["prospect_infeasible"][
                    window_of(num(f.get("day"))) + "|" + f.get("reason", "?")] += 1
            elif event == "MODE_MIGRATION_CANDIDATES":
                out["candidates_rounds"] += 1
                out["candidates_last"] = line.strip()[-1200:]
            elif event == "PRIMARY_MODE_CHANGED":
                out["primary_changed"][window_of(num(fields(line).get("day")))] += 1
            elif event == "MARKET_REPORT":
                f = fields(line)
                day = num(f.get("day"))
                out["rounds"][window_of(day)].append((day, num(f.get("fills"))))
            elif event == "MARKET_FILL":
                f = fields(line)
                w = window_of(num(f.get("day")))
                commodity = f.get("commodity", "?")
                out["fills"][w + "|" + commodity] += 1
                out["fills_qty"][w + "|" + commodity] += num(f.get("quantity"))
                out["fills_value"][w + "|" + f.get("paymentCurrency", "?")] += num(
                    f.get("goodsPaymentMilli"))
                out["fills_freight"][w + "|" + f.get("freightCurrency", "?")] += num(
                    f.get("freightMilli"))
            elif "no-bargaining-power" in line:
                out["no_bargaining_power"][window_of(num(fields(line).get("day")))] += 1

    # ── 派生汇总 ────────────────────────────────────────────────────────────
    rounds = {}
    for w, rows in out["rounds"].items():
        rows.sort()
        fills = [n for _d, n in rows]
        rounds[w] = {
            "rounds": len(rows),
            "first_day": rows[0][0] if rows else None,
            "last_day": rows[-1][0] if rows else None,
            "zero_fill_rounds": sum(1 for n in fills if n == 0),
            "total_fills": sum(fills),
            "min": min(fills) if fills else None,
            "max": max(fills) if fills else None,
        }
    haul_traj = {}
    for market, series in out["haul"].items():
        days = sorted(series)
        prices = [series[d]["after"] for d in days]
        rises = sum(1 for i in range(1, len(days))
                    if series[days[i]]["after"] > series[days[i - 1]]["after"])
        falls = sum(1 for i in range(1, len(days))
                    if series[days[i]]["after"] < series[days[i - 1]]["after"])
        haul_traj[market] = {
            "rows": len(days), "first_day": days[0] if days else None,
            "last_day": days[-1] if days else None,
            "price_first": prices[0] if prices else None,
            "price_last": prices[-1] if prices else None,
            "price_min": min(prices) if prices else None,
            "price_max": max(prices) if prices else None,
            "rises": rises, "falls": falls, "flat": len(days) - rises - falls - 1,
        }
    result = {
        "tag": out["tag"], "log": out["log"],
        "haul_price_signal": haul_traj,
        "haul_first_days": {m: {str(d): s for d, s in sorted(series.items())[:6]}
                            for m, series in out["haul"].items()},
        "haul_last_days": {m: {str(d): s for d, s in sorted(series.items())[-6:]}
                           for m, series in out["haul"].items()},
        "reject_by_reason": dict(sorted(out["reject"].items())),
        "reject_milli_by_reason": dict(sorted(out["reject_milli"].items())),
        "lane_truncated": dict(sorted(out["truncated"].items())),
        "lane_truncated_milli": dict(sorted(out["truncated_milli"].items())),
        "moves_by_target_mode": dict(sorted(out["moves"].items())),
        "merchant_moves": out["merchant_moves"],
        "trade_created": out["trade_created"],
        "ondemand_skipped": out["ondemand_skipped"],
        "prospect_infeasible_by_reason": dict(sorted(out["prospect_infeasible"].items())),
        "candidates_rounds": out["candidates_rounds"],
        "candidates_last": out["candidates_last"],
        "primary_mode_changed": dict(sorted(out["primary_changed"].items())),
        "no_bargaining_power_lines": dict(sorted(out["no_bargaining_power"].items())),
        "market_rounds": rounds,
        "fills_by_commodity": dict(sorted(out["fills"].items())),
        "fills_qty_by_commodity": dict(sorted(out["fills_qty"].items())),
        "fills_value_by_currency": dict(sorted(out["fills_value"].items())),
        "fills_freight_by_currency": dict(sorted(out["fills_freight"].items())),
    }
    print(json.dumps(result, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
