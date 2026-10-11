#!/usr/bin/env python3
"""户绝⇒债务是否消除 调查 —— 分析器（读 daily/*.hh.jsonl.gz 与 *.debt.jsonl.gz，只读）。

六个口径（AGENTS §九.4：全部为**时点快照**；跨时点的"逐周期变化"由同一 id 跨日取值构成，不与本期/累计混用）：
  A 户绝清单        population 由 >0 首次变 0 的家户（逐户：归零日、死前人口/阶层/财富）
  B 逐周期债务     户绝户名下合同在 户绝日 / +120 / +240 / … / d600 的本金
  C d600 拆解      未偿总额 → ①债务人已户绝 ②活着但不可偿还 ③正常规模
  D 债权人侧       已户绝家户作为债权人的应收
  E Top 债务人     活着债务人未偿额排序（户 id / 阶层 / 本金 / 起始日）
  F 利率面         合同利率分布 + 世界借款利率轨迹（"利滚利"的量级）
"""
import gzip
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DAILY = os.path.join(HERE, "..", "daily")
GRID = (0, 120, 240, 360, 480, 600)
STRATUM_ORDER = ["landlord", "rich_peasant", "middle_peasant", "poor_peasant",
                 "landless_laborer", "merchant", "artisan", "workshop_owner", "other"]


def gz(path):
    with gzip.open(path, "rt") as f:
        for line in f:
            yield json.loads(line)


def load_world(tag):
    """→ hh[day][hh], debt[day][id]（逐日时点快照）。"""
    hh = defaultdict(dict)
    for r in gz(os.path.join(DAILY, "%s.hh.jsonl.gz" % tag)):
        hh[r["d"]][r["hh"]] = r
    debt = defaultdict(dict)
    for r in gz(os.path.join(DAILY, "%s.debt.jsonl.gz" % tag)):
        debt[r["d"]][r["id"]] = r
    return hh, debt


def sum_owe(row, key):
    return sum((row.get(key) or {}).values())


def analyze(tag, days):
    hh, debt = load_world(tag)
    out = {"tag": tag, "days": sorted(days)}
    last = max(days)
    ids = sorted({h for d in days for h in hh[d]})

    # ── A 户绝清单 ─────────────────────────────────────────────────────────
    extinct = []          # (hh, zero_day, pop_before, stratum, money, grain, land)
    for h in ids:
        prev = None
        for d in sorted(days):
            r = hh[d].get(h)
            if r is None:
                continue
            if r["pop"] == 0 and prev is not None and prev["pop"] > 0:
                extinct.append((h, d, prev))
                break
            prev = r
    alive600 = [h for h in ids if hh[last].get(h, {}).get("pop", 0) > 0]
    grid = sorted({0, 120, 240, 360, 480, 600} | {d for _h, d, _p in extinct})
    grid = [d for d in grid if d <= last]
    out["A_counts"] = {
        "households_total": len(ids),
        "extinct": len(extinct),
        "alive_at_end": len(alive600),
        "extinct_with_zero_day": len(extinct),
    }

    # 财富分位（口径写明：**货币持有量**分位；本世界 d600 价表全 0 ⇒ 货物无法折价，故不并入）
    def money_scalar(r):
        return sum((r.get("money") or {}).values())

    ranks = {}
    for d in sorted(days):
        vals = sorted(money_scalar(r) for r in hh[d].values())
        n = len(vals)
        for h, r in hh[d].items():
            v = money_scalar(r)
            lo = sum(1 for x in vals if x < v)
            ranks[(d, h)] = round(100.0 * lo / max(1, n), 1)
    out["A_extinct"] = [{
        "hh": h, "zeroDay": d, "popBefore": prev["pop"], "stratum": prev["stratum"],
        "money": prev["money"], "grain": (prev["bal"] or {}).get("grain", 0),
        "land": (prev["own"] or {}).get("LAND", 0),
        "moneyPctile": ranks.get((d - 1, h)),
        "oweAtZeroDay": {"open": prev["owe_o"], "defaulted": prev["owe_d"],
                         "total": sum_owe(prev, "owe_o") + sum_owe(prev, "owe_d")},
    } for h, d, prev in sorted(extinct, key=lambda t: t[1])]

    # ── B 逐周期债务：户绝户名下（debtor == 户绝户）的合同 ────────────────
    # ★★ 关键分辨：本世界的"人口 → 0"**有两种成因**，必须分开（否则会把阶层流动读成死亡）：
    #   ① slide = `classStandings.lastTransitionDay == 户绝日`（阶层流动把整户人搬去另一个户槽位）
    #   ② death = 当天**没有**阶层流动，人口是被死亡逐日吃掉到 0 的
    #   ③ cliff = 单日跌幅 = 整户人口 且 当天世界人口没跟着掉那么多（迁移/流动的形态学判据，作交叉核对）
    trans = {}          # day -> {hh: lastTransitionDay}
    worldpop = {}
    for d in days:
        worldpop[d] = sum(r["pop"] for r in hh[d].values())
    for r in gz(os.path.join(DAILY, "%s.agg.jsonl.gz" % tag)):
        trans[r["day"]] = r.get("class_last_transition") or {}

    traj = []
    for h, d0, prev in sorted(extinct, key=lambda t: t[1]):
        src = hh.get(d0 - 1, {}).get(h) or prev
        before = hh.get(d0 - 1, {}).get(h) or prev
        orig = {cid: c["p"] for cid, c in debt.get(d0 - 1, {}).items() if c["debtor"] == h}
        if not orig:
            orig = {cid: c["p"] for cid, c in debt.get(d0, {}).items() if c["debtor"] == h}
        slid = trans.get(d0, {}).get(h) == d0
        wdrop = worldpop.get(d0 - 1, 0) - worldpop.get(d0, 0)
        series = {}
        for T in grid:
            r = hh.get(T, {}).get(h, {})
            owe = sum_owe(r, "owe_o") + sum_owe(r, "owe_d")
            origp = 0
            n = 0
            for cid in orig:
                c = debt.get(T, {}).get(cid)
                if c is not None:
                    origp += c["p"]
                    n += 1
            series[str(T)] = {"oweTotal": owe, "origPrincipal": origp,
                              "origPresent": n, "pop": r.get("pop")}
        traj.append({
            "hh": h, "zeroDay": d0, "stratum": src["stratum"], "popBefore": src["pop"],
            "cause": "slide" if slid else "death",
            "worldPopDropThatDay": wdrop,
            "oweAtZeroMinus1": sum_owe(before, "owe_o") + sum_owe(before, "owe_d"),
            "oweByUnitAtZeroMinus1": {**before["owe_o"], **before["owe_d"]},
            "lentAtZeroMinus1": {**before["lent_o"], **before["lent_d"]},
            "contracts": len(orig), "origPrincipalAtZeroMinus1": sum(orig.values()),
            "series": series,
        })
    out["B_traj"] = traj
    out["B_summary"] = {
        "byCause": {c: sum(1 for t in traj if t["cause"] == c)
                    for c in {t["cause"] for t in traj}},
        "extinct_with_debt_at_extinction": sum(1 for t in traj if t["origPrincipalAtZeroMinus1"] > 0),
        "origPrincipal_total": sum(t["origPrincipalAtZeroMinus1"] for t in traj),
    }

    # ── C d600 拆解 ───────────────────────────────────────────────────────
    dead_set = {h for h, _d, _p in extinct}
    alive_set = set(alive600)
    # ★ 本世界 seeding 事实（实测，不是推断）：178 个家户行里有 71 个**从未有过人口**（城市户槽位，
    #   创世即 pop==0），另有 2 个在运行中由 0 变正。⇒ "d600 人口为 0" ≠ "本世纪内户绝"，
    #   两者必须分开：①a = 运行中真实户绝（>0→0，21 户）；①b = 从未有人口的户槽位（71 户）。
    ever_positive = set()
    for d in days:
        for h, r in hh[d].items():
            if r["pop"] > 0:
                ever_positive.add(h)
    never_populated = {h for h in ids if h not in ever_positive}
    pop0_set = {h for h in ids if hh[last].get(h, {}).get("pop", 0) == 0}
    world_money = defaultdict(int)
    for r in hh[last].values():
        for cur, v in (r.get("money") or {}).items():
            world_money[cur] += v
    # ② 的判据 = 用户口径"按全世界货币量看不可偿还"：该户**在该标的上的未偿本金 > 全世界该币种货币量**。
    #   （按户-标的对判：同一户的 silver 债只能用 silver 钱还 —— 币种不可互换，故不跨币相加。）
    per_debtor = defaultdict(lambda: defaultdict(int))
    for cid, c in debt.get(last, {}).items():
        per_debtor[c["debtor"]][c["unit"]] += c["p"]
    unrepayable = set()
    for h, med in per_debtor.items():
        for u, v in med.items():
            cur = u.split(":", 1)[1]
            if u.startswith("money:") and v > world_money.get(cur, 0):
                unrepayable.add((h, u))

    buckets = defaultdict(lambda: defaultdict(int))
    ncontracts = defaultdict(int)
    for cid, c in debt.get(last, {}).items():
        u = c["unit"]
        d = c["debtor"]
        if d in dead_set:
            k = "1_debtor_extinct"
        elif d in never_populated:
            k = "1b_debtor_never_populated"
        elif d in pop0_set:
            k = "1c_debtor_pop0_other"
        elif d in alive_set and (d, u) in unrepayable:
            k = "2_alive_unrepayable"
        elif d in alive_set:
            k = "3_alive_normal_scale"
        else:
            k = "4_unknown_debtor"
        buckets[k][u] += c["p"]
        ncontracts[k] += 1
    out["C_d600"] = {
        "worldMoney": dict(world_money),
        "buckets": {k: dict(v) for k, v in buckets.items()},
        "contracts": dict(ncontracts),
        "neverPopulated": len(never_populated),
        "everPopulated": len(ever_positive),
        "pop0AtEnd": len(pop0_set),
        "unrepayablePairs": len(unrepayable),
        "aliveDebtorPairs": sum(1 for h in per_debtor if h in alive_set
                                for _u in per_debtor[h]),
        "exclExtinct": {u: sum(v.get(u, 0) for k, v in buckets.items() if k != "1_debtor_extinct")
                        for u in {c["unit"] for c in debt.get(last, {}).values()}},
    }

    # ── D 债权人侧：已户绝家户作为债权人 ─────────────────────────────────
    cred = {"contracts": 0, "by_unit": defaultdict(int), "rows": []}
    for cid, c in debt.get(last, {}).items():
        if c["creditor"] in dead_set:
            cred["contracts"] += 1
            cred["by_unit"][c["unit"]] += c["p"]
            cred["rows"].append({"id": cid, "debtor": c["debtor"], "unit": c["unit"],
                                 "principal": c["p"], "opened": c["opened"],
                                 "status": c["status"]})
    cred["by_unit"] = dict(cred["by_unit"])
    cred["rows"].sort(key=lambda r: -r["principal"])
    out["D_creditor"] = cred

    # ── E Top 活着债务人 ─────────────────────────────────────────────────
    top = defaultdict(lambda: defaultdict(int))
    for cid, c in debt.get(last, {}).items():
        top[c["debtor"]][c["unit"]] += c["p"]
    rows = []
    for h, med in top.items():
        r = hh[last].get(h, {})
        rows.append({"hh": h, "alive": h in alive_set, "stratum": r.get("stratum"),
                     "pop": r.get("pop"), "money": r.get("money"),
                     "grain": (r.get("bal") or {}).get("grain", 0),
                     "land": (r.get("own") or {}).get("LAND", 0),
                     "byUnit": dict(med), "total": sum(med.values())})
    rows.sort(key=lambda r: -r["total"])
    out["E_top"] = rows[:25]

    # ── F 利率面 ─────────────────────────────────────────────────────────
    rates = sorted((c["rate"] for c in debt.get(last, {}).values() if c["rate"] is not None))
    out["F_rates"] = {
        "n": len(rates),
        "min": rates[0] if rates else None,
        "p50": rates[len(rates) // 2] if rates else None,
        "max": rates[-1] if rates else None,
        "eq20": sum(1 for r in rates if r == 20),
        "gt1000": sum(1 for r in rates if r > 1000),
        "gt100000": sum(1 for r in rates if r > 100000),
    }
    # 每份合同本金随日的倍率：抽样 3 个户绝户的合同
    out["F_samples"] = []
    for h, d0, prev in sorted(extinct, key=lambda t: t[1])[:400]:
        cand = [(cid, c) for cid, c in debt.get(d0, {}).items() if c["debtor"] == h]
        if not cand:
            continue
        cid, c = cand[0]
        s = {"hh": h, "zeroDay": d0, "id": cid[:60] + "…", "unit": c["unit"],
             "rate": c["rate"], "opened": c["opened"],
             "p_at_zero": c["p"], "p_at_600": debt.get(last, {}).get(cid, {}).get("p"),
             "present_at_600": cid in debt.get(last, {})}
        out["F_samples"].append(s)
    return out


def main():
    tags = sys.argv[1:] or ["sw600", "tp600"]
    res = {}
    for tag in tags:
        p = os.path.join(DAILY, "%s.hh.jsonl.gz" % tag)
        if not os.path.exists(p):
            print("skip (missing):", tag)
            continue
        days = sorted({r["d"] for r in gz(p)})
        print("[%s] days=%d (%d..%d)" % (tag, len(days), days[0], days[-1]))
        res[tag] = analyze(tag, days)
        a = res[tag]["A_counts"]
        print("   A: total=%s extinct=%s alive@end=%s" %
              (a["households_total"], a["extinct"], a["alive_at_end"]))
        c = res[tag]["C_d600"]
        print("   C: worldMoney=%s" % c["worldMoney"])
        for k, v in c["buckets"].items():
            print("      %-20s %s  (%d contracts)" % (k, v, c["contracts"][k]))
        print("   D: extinct-creditor contracts=%s byUnit=%s" %
              (res[tag]["D_creditor"]["contracts"], res[tag]["D_creditor"]["by_unit"]))
        print("   F:", res[tag]["F_rates"])
    outp = os.path.join(HERE, "..", "results", "dd-analyze.json")
    with open(outp, "w") as f:
        json.dump(res, f, ensure_ascii=False, indent=1, sort_keys=True)
    print("→", outp)


if __name__ == "__main__":
    main()
