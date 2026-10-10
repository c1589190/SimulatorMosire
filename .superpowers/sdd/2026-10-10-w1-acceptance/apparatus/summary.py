#!/usr/bin/env python3
"""W1 验收：从两份**同版本投影**里抽"逐项语义量"，输出逐值对照（含 md5 与样例）。

用法：python3 summary.py <pre 投影.json> <post 投影.json> [--json out.json]
"""
import argparse
import hashlib
import json
import sys

COMMODITIES = ["grain", "cloth", "fiber", "tool", "iron"]


def canon(o):
    return json.dumps(o, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def md5o(o):
    return hashlib.md5(canon(o).encode()).hexdigest()


def hex_of_household(social, econ, hh):
    h = social["households"].get(hh)
    if h and isinstance(h.get("location"), dict) and "hex" in h["location"]:
        hx = h["location"]["hex"]
        return "%d_%d" % (hx["q"], hx["r"])
    row = econ["classes"].get(hh)
    if row and isinstance(row.get("view"), str):
        return row["view"].split("|")[0]
    return None


def extract(proj):
    mods = proj["modules"]
    econ = mods["economy"]["data"]
    actor = mods["actor"]["data"]
    social = mods["social"]["data"]

    out = {}

    # ① 人口
    hh_pop_social = {hh: sum(h.get("members", {}).values()) for hh, h in social["households"].items()}
    hh_pop_econ = {hh: row.get("population") for hh, row in econ["classes"].items()}
    pop_by_hex = {}
    for hh, n in hh_pop_social.items():
        hx = hex_of_household(social, econ, hh)
        pop_by_hex[hx] = pop_by_hex.get(hx, 0) + n
    out["population"] = {
        "totalSocialMembers": sum(hh_pop_social.values()),
        "totalEconomyRows": sum(v for v in hh_pop_econ.values() if v is not None),
        "byHouseholdSocial": hh_pop_social,
        "byHouseholdEconomy": hh_pop_econ,
        "byHex": pop_by_hex,
        "md5ByHouseholdSocial": md5o(hh_pop_social),
        "md5ByHouseholdEconomy": md5o(hh_pop_econ),
        "md5ByHex": md5o(pop_by_hex),
        "nHouseholdsSocial": len(hh_pop_social),
        "nHouseholdsEconomy": len(hh_pop_econ),
    }

    # ② 逐格库存（actor 侧 HouseholdInventory.balances 汇总）+ 逐户库存
    goods_hex = {}
    money_hh = {}
    goods_hh = {}
    for hh, acc in actor["accounts"].items():
        hx = hex_of_household(social, econ, hh)
        b = acc.get("balances", {})
        goods_hh[hh] = b
        goods_hex.setdefault(hx, {})
        for c, q in b.items():
            goods_hex[hx][c] = goods_hex[hx].get(c, 0) + q
        money_hh[hh] = acc.get("money", {})
    goods_totals = {}
    for hx, b in goods_hex.items():
        for c, q in b.items():
            goods_totals[c] = goods_totals.get(c, 0) + q
    money_totals = {}
    for hh, m in money_hh.items():
        for c, q in m.items():
            money_totals[c] = money_totals.get(c, 0) + q
    out["goods"] = {
        "nAccounts": len(actor["accounts"]),
        "commodities": {c: goods_totals.get(c, 0) for c in COMMODITIES},
        "allCommodityTotals": goods_totals,
        "byHex": goods_hex,
        "byHousehold": goods_hh,
        "md5ByHex": md5o(goods_hex),
        "md5ByHousehold": md5o(goods_hh),
    }
    out["money"] = {
        "totals": money_totals,
        "byHousehold": money_hh,
        "md5ByHousehold": md5o(money_hh),
        "nHouseholdsWithMoney": sum(1 for m in money_hh.values() if any(v for v in m.values())),
    }

    # ③ 债务合同
    contracts = {}
    for cid, c in econ["debtContracts"].items():
        unit = c["unit"]
        uk = unit.get("@class") if isinstance(unit, dict) else unit
        contracts[cid] = {
            "debtor": c["debtor"],
            "creditor": c["creditor"],
            "unit": canon(unit),
            "principal": c["principal"],
            "openedDay": c["openedDay"],
            "status": c["status"],
        }
    by_status = {}
    principal_by_unit = {}
    for cid, c in contracts.items():
        by_status[c["status"]] = by_status.get(c["status"], 0) + 1
        key = c["unit"]
        principal_by_unit[key] = principal_by_unit.get(key, 0) + int(c["principal"])
    out["debt"] = {
        "count": len(contracts),
        "byStatus": by_status,
        "sumPrincipalByUnit": principal_by_unit,
        "md5": md5o(contracts),
        "nDebtRefs": len(econ.get("householdDebtRefs", {})),
        "md5DebtRefs": md5o(econ.get("householdDebtRefs", {})),
    }

    # ④ 逐户 flows（成交/收入的持久面）
    flows = {}
    for hh, f in econ["flows"].items():
        flows[hh] = {k: v for k, v in f.items() if k != "id"}
    summed = {}
    for hh, f in flows.items():
        for field in ("income", "consumed", "unmetNeed"):
            for c, q in (f.get(field) or {}).items():
                summed.setdefault(field, {}).setdefault(c, 0)
                summed[field][c] += q
        for field in ("taxPaid", "interestDue", "newBorrowing", "repaid", "netSurplus"):
            summed.setdefault(field, 0)
            summed[field] += f.get(field, 0)
    out["flows"] = {
        "nHouseholds": len(flows),
        "worldTotals": summed,
        "md5": md5o(flows),
    }

    # ⑤ 逐格价格
    markets = econ["marketsByHex"]
    out["prices"] = {
        "nHexWithMarket": len(markets),
        "md5": md5o(markets),
        "distinctNumeraire": sorted({m["numeraire"]["value"] for m in markets.values()}),
        "distinctPriceRows": sorted({canon(m["prices"]) for m in markets.values()}),
    }

    # ⑥ 阶层分布
    stratum = {}
    views = {}
    for hh, row in econ["classes"].items():
        v = row.get("view", "")
        parts = v.split("|")
        st = parts[2] if len(parts) > 2 else v
        stratum[st] = stratum.get(st, 0) + 1
        views[hh] = v
    standings = {hh: {"current": s.get("currentPositionId", {}).get("value"),
                      "original": s.get("originalPositionId", {}).get("value"),
                      "stress": s.get("consecutiveDebtStressCycles")}
                 for hh, s in econ.get("classStandings", {}).items()}
    out["classes"] = {
        "stratumCounts": stratum,
        "md5Views": md5o(views),
        "md5Standings": md5o(standings),
        "nStandings": len(standings),
    }

    # ⑦ 全状态（投影后）规范 md5 —— 最强判据
    out["wholeState"] = {
        "md5Modules": md5o(mods),
        "bytes": len(canon(mods)),
        "perNamespace": {ns: md5o(mods[ns]) for ns in sorted(mods)},
    }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pre")
    ap.add_argument("post")
    ap.add_argument("--out")
    ap.add_argument("--samples", type=int, default=3)
    args = ap.parse_args()
    a = extract(json.load(open(args.pre)))
    b = extract(json.load(open(args.post)))

    report = {"pre": a, "post": b, "equal": {}, "samples": {}}
    for cat in a:
        report["equal"][cat] = canon(a[cat]) == canon(b[cat])
    print("== 逐项相等 ==")
    for cat, ok in report["equal"].items():
        print("  %-14s %s" % (cat, "相同" if ok else "★不同★"))
    print()
    print("人口：social Σ=%d / economy Σ=%d / 户数 %d=%d / 逐格 md5 %s=%s"
          % (a["population"]["totalSocialMembers"], a["population"]["totalEconomyRows"],
             a["population"]["nHouseholdsSocial"], a["population"]["nHouseholdsEconomy"],
             a["population"]["md5ByHex"][:12], b["population"]["md5ByHex"][:12]))
    print("库存合计（粮/布/纤维/工具/铁）：pre=%s post=%s  md5ByHex %s=%s"
          % (a["goods"]["commodities"], b["goods"]["commodities"],
             a["goods"]["md5ByHex"][:12], b["goods"]["md5ByHex"][:12]))
    print("货币合计：pre=%s post=%s  md5ByHousehold %s=%s"
          % (a["money"]["totals"], b["money"]["totals"],
             a["money"]["md5ByHousehold"][:12], b["money"]["md5ByHousehold"][:12]))
    print("债务：条数 %d=%d 状态 %s  本金按单位 %s  md5 %s=%s"
          % (a["debt"]["count"], b["debt"]["count"], a["debt"]["byStatus"],
             a["debt"]["sumPrincipalByUnit"], a["debt"]["md5"][:12], b["debt"]["md5"][:12]))
    print("flows 世界合计：pre=%s" % json.dumps(a["flows"]["worldTotals"], ensure_ascii=False))
    print("                  post=%s" % json.dumps(b["flows"]["worldTotals"], ensure_ascii=False))
    print("价格：改前 %d 格 md5=%s ；改后 %d 格 md5=%s ；币种 %s ；价目行 %s"
          % (a["prices"]["nHexWithMarket"], a["prices"]["md5"][:12],
             b["prices"]["nHexWithMarket"], b["prices"]["md5"][:12],
             b["prices"]["distinctNumeraire"], b["prices"]["distinctPriceRows"]))
    print("阶层：pre=%s post=%s  md5Views %s=%s"
          % (a["classes"]["stratumCounts"], b["classes"]["stratumCounts"],
             a["classes"]["md5Views"][:12], b["classes"]["md5Views"][:12]))
    print("全状态（投影后）md5：pre=%s post=%s 相同=%s"
          % (a["wholeState"]["md5Modules"][:16], b["wholeState"]["md5Modules"][:16],
             a["wholeState"]["md5Modules"] == b["wholeState"]["md5Modules"]))
    print("  逐 namespace：%s" % json.dumps({k: v[:12] for k, v in a["wholeState"]["perNamespace"].items()},
                                        ensure_ascii=False))
    print("                %s" % json.dumps({k: v[:12] for k, v in b["wholeState"]["perNamespace"].items()},
                                        ensure_ascii=False))

    n = args.samples
    print()
    print("== 样例（改前 -> 改后）==")
    hh_keys = sorted(a["money"]["byHousehold"])[:n]
    for hh in hh_keys:
        print("  户 %s：钱 %s -> %s ；货 %s -> %s；pop %s -> %s"
              % (hh, a["money"]["byHousehold"].get(hh), b["money"]["byHousehold"].get(hh),
                 a["goods"]["byHousehold"].get(hh), b["goods"]["byHousehold"].get(hh),
                 a["population"]["byHouseholdSocial"].get(hh), b["population"]["byHouseholdSocial"].get(hh)))
    for hx in sorted(a["goods"]["byHex"])[:n]:
        print("  格 %s：库存 %s -> %s ；人口 %s -> %s"
              % (hx, {c: a["goods"]["byHex"][hx].get(c, 0) for c in COMMODITIES},
                 {c: b["goods"]["byHex"][hx].get(c, 0) for c in COMMODITIES},
                 a["population"]["byHex"].get(hx), b["population"]["byHex"].get(hx)))

    if args.out:
        with open(args.out, "w") as f:
            json.dump(report, f, ensure_ascii=False, indent=1, sort_keys=True)
        print("\n明细落盘 %s" % args.out)
    sys.exit(0 if all(report["equal"].values()) else 1)


if __name__ == "__main__":
    main()
