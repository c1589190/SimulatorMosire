#!/usr/bin/env python3
"""财富聚集调查 —— 汇总分析（只读逐日读数 JSONL，出表）。

口径（每个数字都标窗口）：
  · **时点快照**：钱 / 货 / 资产份额 / 债务余额 / 人口 / 饱食度  ← 取该 day 的 checkpoint
  · **本期发生额**：flows（周期内累计，周期 30 day，关账归零）→ 本脚本按周期边界还原成**历史累计**
  · **历史累计**：populationEvents（创世起）；logs 里逐日事件的求和

用法：
  python3 wr_analyze.py --daily <jsonl> --tag sw [--grid 0,120,240,360,480,600]
                        --outdir <dir> [--topn 10,50]
"""
import argparse
import json
import math
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

CLASS_CN = {
    "rural-landlord": "地主(乡)", "urban-landlord": "地主(城)",
    "rural-rich_peasant": "富农(乡)", "urban-rich_peasant": "富农(城)",
    "rural-middle_peasant": "中农(乡)", "urban-middle_peasant": "中农(城)",
    "rural-poor_peasant": "贫农(乡)", "urban-poor_peasant": "贫农(城)",
    "rural-landless_laborer-displaced": "失地流民(乡)",
    "urban-landless_laborer-displaced": "失地流民(城)",
}
CLASS_ORDER = ["rural-landlord", "urban-landlord", "rural-rich_peasant", "urban-rich_peasant",
               "rural-middle_peasant", "urban-middle_peasant", "rural-poor_peasant",
               "urban-poor_peasant", "rural-landless_laborer-displaced",
               "urban-landless_laborer-displaced", "GOV_TREASURY", "GOV_STAFF"]


def hh_kind(h):
    if h.startswith("hh-gov-"):
        return "GOV_TREASURY"
    if h.startswith("hh-unit:"):
        return "GOV_STAFF"
    m = re.match(r"hh-(-?\d+)_(-?\d+)-(.+)", h)
    return m.group(3) if m else "OTHER"


def hh_hex(h):
    m = re.match(r"hh-(-?\d+)_(-?\d+)-", h)
    return (int(m.group(1)), int(m.group(2))) if m else None


def gini(vals):
    """总体 Gini（未加权）。vals 可含 0，不可含负。"""
    v = sorted(x for x in vals)
    n = len(v)
    if n == 0:
        return None
    s = sum(v)
    if s <= 0:
        return 0.0
    cum = 0.0
    for i, x in enumerate(v, 1):
        cum += i * x
    return (2.0 * cum) / (n * s) - (n + 1.0) / n


def pct(vals, q):
    v = sorted(vals)
    if not v:
        return None
    if len(v) == 1:
        return v[0]
    k = (len(v) - 1) * q
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return v[int(k)]
    return v[f] * (c - k) + v[c] * (k - f)


def top_share(vals, frac):
    v = sorted(vals, reverse=True)
    s = sum(v)
    if s <= 0:
        return None
    k = max(1, int(math.ceil(len(v) * frac)))
    return sum(v[:k]) / s


def load(daily):
    rows = []
    with open(daily) as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    rows.sort(key=lambda r: r["day"])
    return rows


def series(name, rows, fn):
    return [(r["day"], fn(r)) for r in rows]


def w_grain(rec):
    return rec["bal"].get("grain", 0)


def w_land(rec):
    return rec["own"].get("LAND", 0)


def w_money(rec, cur):
    return rec["money"].get(cur, 0)


def write_csv(path, header, rows):
    with open(path, "w") as f:
        f.write(",".join(header) + "\n")
        for r in rows:
            f.write(",".join("" if x is None else str(x) for x in r) + "\n")
    print("  wrote %s (%d rows)" % (path, len(rows)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--daily", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--grid", default="0,120,240,360,480,600")
    ap.add_argument("--topn", default="10,50")
    ap.add_argument("--deaths", default=None,
                    help="wr_events.py 产的 deaths-<tag>.json（逐户死亡事件，历史累计）")
    a = ap.parse_args()
    grid = [int(x) for x in a.grid.split(",")]
    topn = [int(x) for x in a.topn.split(",")]
    os.makedirs(a.outdir, exist_ok=True)
    rows = load(a.daily)
    print("[%s] loaded %d days (%s..%s)" % (a.tag, len(rows), rows[0]["day"], rows[-1]["day"]))
    byday = {r["day"]: r for r in rows}
    have = sorted(byday)
    tag = a.tag

    def nearest_before(d):
        c = [x for x in have if x <= d]
        return byday[c[-1]] if c else None

    # ── 1. 逐户 × 网格日 财富表（时点快照）──────────────────────────────
    allhh = sorted(set().union(*[set(r["hh"]) for r in rows]))
    hdr = ["tag", "day", "household", "kind", "hex", "pop", "satiety",
           "money_silver", "money_copper", "money_gold",
           "grain", "grain_per_cap", "cloth", "fiber", "tool", "iron", "haul",
           "grain_per_cap_rank_pct",
           "land_own", "tool_own", "land_ope", "tool_ope",
           "owe_open_money", "owe_def_money", "lent_open_money", "lent_def_money",
           "owe_open_fiber", "owe_def_fiber", "lent_def_fiber",
           "debt_n", "pos"]
    out = []
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        gp = {}
        for k, rec in hh.items():
            gp[k] = (w_grain(rec) / rec["pop"]) if rec["pop"] else 0.0
        order = sorted(hh, key=lambda k: gp[k])
        rank = {k: (i + 0.5) / len(order) for i, k in enumerate(order)}
        for k in sorted(hh):
            rec = hh[k]
            hx = hh_hex(k)
            owe_o = sum(v for kk, v in rec["owe_o"].items() if kk.startswith("money:"))
            owe_d = sum(v for kk, v in rec["owe_d"].items() if kk.startswith("money:"))
            lent_o = sum(v for kk, v in rec["lent_o"].items() if kk.startswith("money:"))
            lent_d = sum(v for kk, v in rec["lent_d"].items() if kk.startswith("money:"))
            out.append([tag, d, k, hh_kind(k), ("%d_%d" % hx) if hx else "", rec["pop"],
                        rec["sat"], rec["money"].get("silver", 0), rec["money"].get("copper", 0),
                        rec["money"].get("gold", 0),
                        w_grain(rec), round(gp[k], 3),
                        rec["bal"].get("cloth", 0), rec["bal"].get("fiber", 0),
                        rec["bal"].get("tool", 0), rec["bal"].get("iron", 0),
                        rec["bal"].get("haul", 0), round(rank[k], 4),
                        w_land(rec), rec["own"].get("TOOL", 0),
                        rec["ope"].get("LAND", 0), rec["ope"].get("TOOL", 0),
                        owe_o, owe_d, lent_o, lent_d,
                        rec["owe_o"].get("commodity:fiber", 0), rec["owe_d"].get("commodity:fiber", 0),
                        rec["lent_d"].get("commodity:fiber", 0),
                        rec["debt_n"], rec["pos"]])
    write_csv(os.path.join(a.outdir, "wealth-grid-%s.csv" % tag), hdr, out)

    # ── 2. 集中度表（逐网格日；Gini / Top 份额 / 分位数）────────────────
    #   每行的 `basis` 说明这个标量是什么；窗口 = 时点快照。
    chdr = ["tag", "day", "basis", "unit", "n", "n_nonzero", "sum", "mean", "median",
            "p10", "p50", "p90", "p99", "max", "gini", "top1pct", "top10pct", "top1_hh"]
    crows = []
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        hhonly = [k for k in hh if hh_kind(k) not in ("GOV_TREASURY", "GOV_STAFF")]
        for basis, unit, fn in (
                ("grain:household", "milli-grain", lambda k: w_grain(hh[k])),
                ("grain:per-capita", "milli-grain/人", lambda k: (w_grain(hh[k]) / hh[k]["pop"]) if hh[k]["pop"] else 0.0),
                ("land_own:household", "share-qty", lambda k: w_land(hh[k])),
                ("silver:household", "milli-silver", lambda k: w_money(hh[k], "silver")),
        ):
            vals = [fn(k) for k in hhonly]
            tot = sum(vals)
            mx = max(vals) if vals else None
            top1 = max(vals) if vals else None
            crows.append([tag, d, basis, unit, len(vals), sum(1 for x in vals if x > 0),
                          round(tot, 3), round(tot / len(vals), 3) if vals else None,
                          round(pct(vals, .5), 3), round(pct(vals, .1), 3), round(pct(vals, .5), 3),
                          round(pct(vals, .9), 3), round(pct(vals, .99), 3), mx,
                          round(gini(vals), 6), round(top_share(vals, .01), 6) if top_share(vals, .01) is not None else None,
                          round(top_share(vals, .10), 6) if top_share(vals, .10) is not None else None,
                          round(top1 / tot, 6) if tot else None])
        # 含政府口的「钱」集中度（货比货：逐币种，不做跨币种求和）
        for cur in ("silver", "copper", "gold"):
            vals = [w_money(hh[k], cur) for k in hh]
            tot = sum(vals)
            if tot <= 0:
                continue
            crows.append([tag, d, "money:all-holders:" + cur, "milli-" + cur, len(vals),
                          sum(1 for x in vals if x > 0), round(tot, 3),
                          round(tot / len(vals), 3), round(pct(vals, .5), 3),
                          round(pct(vals, .1), 3), round(pct(vals, .5), 3), round(pct(vals, .9), 3),
                          round(pct(vals, .99), 3), max(vals), round(gini(vals), 6),
                          round(top_share(vals, .01), 6), round(top_share(vals, .10), 6),
                          round(max(vals) / tot, 6)])
    write_csv(os.path.join(a.outdir, "concentration-%s.csv" % tag), chdr, crows)

    # ── 3. 按阶层 / 按格 分组（时点快照）────────────────────────────────
    ghdr = ["tag", "day", "dim", "group", "n_hh", "pop", "grain", "grain_share",
            "silver", "copper", "gold", "land_own", "grain_per_cap", "sat_mean",
            "grain_zero_hh"]
    grows = []
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        for dim, keyfn in (("class", hh_kind), ("hex", lambda k: (lambda x: "%d_%d" % x if x else "GOV")(hh_hex(k)))):
            grp = defaultdict(list)
            for k in hh:
                grp[keyfn(k)].append(k)
            totg = sum(w_grain(hh[k]) for k in hh)
            for g, ks in sorted(grp.items()):
                pop = sum(hh[k]["pop"] for k in ks)
                gsum = sum(w_grain(hh[k]) for k in ks)
                sats = [hh[k]["sat"] for k in ks if hh[k]["sat"] is not None]
                grows.append([tag, d, dim, g, len(ks), pop, gsum,
                              round(gsum / totg, 6) if totg else None,
                              sum(w_money(hh[k], "silver") for k in ks),
                              sum(w_money(hh[k], "copper") for k in ks),
                              sum(w_money(hh[k], "gold") for k in ks),
                              sum(w_land(hh[k]) for k in ks),
                              round(gsum / pop, 3) if pop else None,
                              round(sum(sats) / len(sats), 1) if sats else None,
                              sum(1 for k in ks if w_grain(hh[k]) == 0)])
    write_csv(os.path.join(a.outdir, "group-%s.csv" % tag), ghdr, grows)

    # ── 4. 国库/政府口 时间序列（时点快照）──────────────────────────────
    thdr = ["tag", "day", "gov_money_silver", "gov_money_copper", "gov_money_gold",
            "gov_grain", "world_money_silver", "world_money_copper", "world_money_gold",
            "world_grain", "gov_money_share_silver", "gov_grain_share",
            "mint_silver_cum", "mint_copper_cum", "mint_gold_cum"]
    trows = []
    mint = defaultdict(int)
    for r in rows:
        d = r["day"]
        for it in (r.get("issuance_today") or []):
            mint[it["cur"]] += it.get("amt", 0)
        if d not in grid:
            continue
        hh = r["hh"]
        gm = defaultdict(int)
        gg = 0
        for k, rec in hh.items():
            if hh_kind(k) in ("GOV_TREASURY", "GOV_STAFF"):
                for c, v in rec["money"].items():
                    gm[c] += v
                gg += w_grain(rec)
        wm = defaultdict(int)
        wg = 0
        for k, rec in hh.items():
            for c, v in rec["money"].items():
                wm[c] += v
            wg += w_grain(rec)
        trows.append([tag, d, gm["silver"], gm["copper"], gm["gold"], gg,
                      wm["silver"], wm["copper"], wm["gold"], wg,
                      round(gm["silver"] / wm["silver"], 6) if wm["silver"] else None,
                      round(gg / wg, 6) if wg else None,
                      mint["silver"], mint["copper"], mint["gold"]])
    write_csv(os.path.join(a.outdir, "treasury-%s.csv" % tag), thdr, trows)

    # ── 5. 逐户累计发生额（flows 是周期内累计 ⇒ 按周期边界还原历史累计）──
    #    口径：历史累计（创世起）；识别方式 = 计数器在某日回落即为关账归零。
    fields = ["tax", "int", "bor", "rep"]
    lastval = {f: {} for f in fields}
    cum = {f: defaultdict(float) for f in fields}
    for r in rows:
        for k, rec in r["hh"].items():
            fl = rec["flow"]
            for f in fields:
                v = fl.get(f, 0) or 0
                lv = lastval[f].get(k, 0)
                if v < lv:                     # 关账归零 ⇒ 先把上一周期末值结清
                    cum[f][k] += lv
                    lastval[f][k] = v
                else:
                    lastval[f][k] = v
    for f in fields:
        for k, v in lastval[f].items():
            cum[f][k] += v
    fhdr = ["tag", "household", "kind", "hex", "pop_final", "cum_tax_grain",
            "cum_interestDue", "cum_newBorrowing", "cum_repaid", "grain_final",
            "flow_income_grain_lastcycle", "flow_con_grain_lastcycle", "flow_unmet_grain_lastcycle",
            "deaths_flow_book", "births_flow_book"]
    frows = []
    fin = byday[have[-1]]["hh"]
    for k in sorted(set(cum["tax"]) | set(fin)):
        rec = fin.get(k) or {"pop": 0, "flow": {}, "bal": {}}
        hx = hh_hex(k)
        frows.append([tag, k, hh_kind(k), ("%d_%d" % hx) if hx else "", rec.get("pop", 0),
                      cum["tax"].get(k, 0), cum["int"].get(k, 0), cum["bor"].get(k, 0),
                      cum["rep"].get(k, 0), w_grain(rec),
                      (rec.get("flow") or {}).get("inc", {}).get("grain", 0),
                      (rec.get("flow") or {}).get("con", {}).get("grain", 0),
                      (rec.get("flow") or {}).get("unmet", {}).get("grain", 0),
                      (rec.get("flow") or {}).get("deaths", 0),
                      (rec.get("flow") or {}).get("births", 0)])
    frows.sort(key=lambda x: -(x[5] or 0))
    write_csv(os.path.join(a.outdir, "flows-cum-%s.csv" % tag), fhdr, frows)

    # ── 6. 死者 × 死前财富分位（逐户人口事件 × 逐日财富）────────────────
    #    窗口：人口事件 = 历史累计；财富 = 死亡日的**前一日**时点快照（无则取最近可得的前一日）
    dhdr = ["tag", "household", "kind", "death_day", "count", "cause",
            "wealth_day", "grain", "grain_per_cap", "wq_grain", "wq_gpc", "pop_at_wealth"]
    drows = []
    # 逐日财富分位缓存
    qcache = {}
    for r in rows:
        hh = r["hh"]
        gp = {k: ((w_grain(v) / v["pop"]) if v["pop"] else 0.0) for k, v in hh.items()}
        gg = {k: w_grain(v) for k, v in hh.items()}
        n = len(hh)
        og = sorted(hh, key=lambda k: gg[k])
        op = sorted(hh, key=lambda k: gp[k])
        qcache[r["day"]] = ({k: (i + 0.5) / n for i, k in enumerate(og)},
                            {k: (i + 0.5) / n for i, k in enumerate(op)})
    ev = []
    if a.deaths:
        ev = json.load(open(a.deaths))["events"]
    for e in ev:
        d = e["day"]
        wd = None
        for cand in range(d - 1, -1, -1):
            if cand in qcache:
                wd = cand
                break
        if wd is None:
            continue
        qg, qp = qcache[wd]
        hh = byday[wd]["hh"]
        rec = hh.get(e["household"])
        if rec is None:
            continue
        drows.append([tag, e["household"], hh_kind(e["household"]), d, e["count"], e["cause"],
                      wd, w_grain(rec), round((w_grain(rec) / rec["pop"]) if rec["pop"] else 0.0, 3),
                      round(qg.get(e["household"], 0), 4), round(qp.get(e["household"], 0), 4),
                      rec["pop"]])
    if drows:
        write_csv(os.path.join(a.outdir, "deaths-wealth-%s.csv" % tag), dhdr, drows)

    print("[%s] done" % tag)


if __name__ == "__main__":
    main()
