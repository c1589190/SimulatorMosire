#!/usr/bin/env python3
"""「谁死了」交叉表（只读逐日读数 + 逐户死亡事件）。

窗口纪律：
  · 死亡数 / 死因 = **历史累计**（创世起，来自 social.populationEvents）
  · 财富 / 人口 = **时点快照**（标明是 day0 / day360 / day600）
  · 分母一律写明：按阶层用 **day0 人口**，按格同理；另给 day600 人口供对照

用法：
  python3 wr_deaths.py --daily <jsonl> --deaths <deaths-<tag>.json> --tag sw --outdir <dir> \
      [--topn 10,50] [--toptable 10] [--grade-days 0,120,240,360,480,600]
"""
import argparse
import collections
import csv
import json
import os
import re

CLASS_CN = {
    "rural-landlord": "地主(乡)", "urban-landlord": "地主(城)",
    "rural-rich_peasant": "富农(乡)", "urban-rich_peasant": "富农(城)",
    "rural-middle_peasant": "中农(乡)", "urban-middle_peasant": "中农(城)",
    "rural-poor_peasant": "贫农(乡)", "urban-poor_peasant": "贫农(城)",
    "rural-landless_laborer-displaced": "失地流民(乡)",
    "urban-landless_laborer-displaced": "失地流民(城)",
}


def kind(h):
    if h.startswith("hh-gov-"):
        return "GOV_TREASURY"
    if h.startswith("hh-unit:"):
        return "GOV_STAFF"
    m = re.match(r"hh-(-?\d+)_(-?\d+)-(.+)", h)
    return m.group(3) if m else "OTHER"


def hx(h):
    m = re.match(r"hh-(-?\d+)_(-?\d+)-", h)
    return "%d_%d" % (int(m.group(1)), int(m.group(2))) if m else "GOV"


def write_csv(path, header, rows):
    with open(path, "w") as f:
        f.write(",".join(header) + "\n")
        for r in rows:
            f.write(",".join("" if x is None else str(x) for x in r) + "\n")
    print("  wrote %s (%d rows)" % (path, len(rows)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--daily", required=True)
    ap.add_argument("--deaths", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--topn", default="10,50")
    ap.add_argument("--toptable", type=int, default=10)
    a = ap.parse_args()
    topn = [int(x) for x in a.topn.split(",")]
    os.makedirs(a.outdir, exist_ok=True)

    rows = [json.loads(l) for l in open(a.daily) if l.strip()]
    rows.sort(key=lambda r: r["day"])
    byday = {r["day"]: r for r in rows}
    d0, d600 = byday[0], byday[rows[-1]["day"]]
    d360 = byday.get(360) or byday[sorted(byday)[len(byday) // 2]]
    dec = json.load(open(a.deaths))["events"]
    deaths = collections.Counter()
    famine = collections.Counter()
    firstd = {}
    byday_deaths = collections.Counter()
    for e in dec:
        deaths[e["household"]] += e["count"]
        if e["cause"] == "BASE_PLUS_FAMINE":
            famine[e["household"]] += e["count"]
        firstd.setdefault(e["household"], e["day"])
        byday_deaths[e["day"]] += e["count"]
    tag = a.tag
    hh0 = d0["hh"]
    hh6 = byday[rows[-1]["day"]]["hh"]
    panel = sorted(k for k in hh0 if kind(k) not in ("GOV_TREASURY", "GOV_STAFF"))
    live0 = [k for k in panel if hh0[k]["pop"] > 0]
    print("[%s] panel=%d live-at-d0=%d" % (tag, len(panel), len(live0)))

    # ── A. 死亡率 按阶层 / 按格 ──────────────────────────────────────────
    hdr = ["tag", "dim", "group", "n_hh", "hh_pop0", "pop0", "deaths", "famine_deaths",
           "deaths_per_1000_pop0", "pop_final", "hh_died_out", "hh_died_out_frac",
           "grain_final", "silver_final", "sat_mean_final"]
    out = []
    for dim, keyf in (("class", kind), ("hex", hx)):
        pop0 = collections.Counter()
        dth = collections.Counter()
        fam = collections.Counter()
        nhh = collections.Counter()
        popf = collections.Counter()
        diedout = collections.Counter()
        gr = collections.Counter()
        si = collections.Counter()
        sats = collections.defaultdict(list)
        for k in panel:
            g = keyf(k)
            p0 = hh0[k]["pop"]
            pf = hh6[k]["pop"]
            pop0[g] += p0
            nhh[g] += 1
            if p0 > 0:
                popf[g] += pf
                dth[g] += deaths[k]
                fam[g] += famine[k]
                if pf == 0:
                    diedout[g] += 1
                gr[g] += hh6[k]["bal"].get("grain", 0)
                si[g] += hh6[k]["money"].get("silver", 0)
                if hh6[k]["sat"] is not None:
                    sats[g].append(hh6[k]["sat"])
        for g in sorted(pop0, key=lambda x: -(dth[x] / max(1, pop0[x]))):
            n0 = sum(1 for k in panel if keyf(k) == g and hh0[k]["pop"] > 0)
            out.append([tag, dim, g, nhh[g], n0, pop0[g], dth[g], fam[g],
                        round(1000.0 * dth[g] / pop0[g], 1) if pop0[g] else None,
                        popf[g], diedout[g],
                        round(diedout[g] / n0, 4) if n0 else None,
                        gr[g], si[g],
                        round(sum(sats[g]) / len(sats[g]), 1) if sats[g] else None])
    write_csv(os.path.join(a.outdir, "mortality-%s.csv" % tag), hdr, out)

    # ── B. Top N 户：存活 / 财富变化（三个基准日各一份）─────────────────
    hdr2 = ["tag", "basis_day", "basis", "rank", "household", "kind", "hex",
            "basis_value", "pop0", "pop_final", "deaths_total", "famine_deaths",
            "first_death_day", "grain_final", "silver_final", "sat_final",
            "grain_basis", "alive_final"]
    out2 = []
    for bd, bmap, daylbl in (("d0", hh0, "day0"), ("d360", d360["hh"], "day360"), ("d600", hh6, "day600")):
        for basis, valf in (("grain", lambda r: r["bal"].get("grain", 0)),
                            ("grain_per_cap", lambda r: (r["bal"].get("grain", 0) / r["pop"]) if r["pop"] else -1),
                            ("land_own", lambda r: r["own"].get("LAND", 0))):
            cand = [k for k in bmap if kind(k) not in ("GOV_TREASURY", "GOV_STAFF")
                    and bmap[k]["pop"] > 0]
            cand.sort(key=lambda k: -valf(bmap[k]))
            for N in topn:
                for i, k in enumerate(cand[:N], 1):
                    out2.append([tag, daylbl, basis, i, k, kind(k), hx(k),
                                 round(valf(bmap[k]), 2), hh0[k]["pop"], hh6[k]["pop"],
                                 deaths[k], famine[k], firstd.get(k, ""),
                                 hh6[k]["bal"].get("grain", 0), hh6[k]["money"].get("silver", 0),
                                 hh6[k]["sat"], bmap[k]["bal"].get("grain", 0),
                                 1 if hh6[k]["pop"] > 0 else 0])
    write_csv(os.path.join(a.outdir, "topn-%s.csv" % tag), hdr2, out2)

    # ── C. 死者 × day0 财富分位（只在 day0 有人的 105 户里排名）─────────
    hdr3 = ["tag", "basis", "decile", "n_hh", "pop0", "deaths", "famine_deaths",
            "deaths_per_1000_pop0", "pop_final", "sat_mean_final", "grain_final"]
    out3 = []
    for basis, valf in (("grain", lambda k: hh0[k]["bal"].get("grain", 0)),
                        ("grain_per_cap", lambda k: (hh0[k]["bal"].get("grain", 0) / hh0[k]["pop"])
                         if hh0[k]["pop"] else -1)):
        order = sorted(live0, key=lambda k: valf(k))
        n = len(order)
        buck = collections.defaultdict(list)
        for i, k in enumerate(order):
            buck[min(9, int(i * 10 / n))].append(k)
        for d10 in range(10):
            ks = buck.get(d10, [])
            p0 = sum(hh0[k]["pop"] for k in ks)
            pf = sum(hh6[k]["pop"] for k in ks)
            dd = sum(deaths[k] for k in ks)
            ff = sum(famine[k] for k in ks)
            sats = [hh6[k]["sat"] for k in ks if hh6[k]["sat"] is not None]
            out3.append([tag, basis, d10 + 1, len(ks), p0, dd, ff,
                         round(1000.0 * dd / p0, 1) if p0 else None, pf,
                         round(sum(sats) / len(sats), 1) if sats else None,
                         sum(hh6[k]["bal"].get("grain", 0) for k in ks)])
    write_csv(os.path.join(a.outdir, "deathdecile-%s.csv" % tag), hdr3, out3)

    # ── D. 全灭/存活 汇总 + 时间序列（逐日累计死亡）────────────────────
    dout = [[tag, d, byday_deaths.get(d, 0), sum(v for k, v in byday_deaths.items() if k <= d)]
            for d in sorted(byday)]
    write_csv(os.path.join(a.outdir, "deaths-daily-%s.csv" % tag),
              ["tag", "day", "deaths_today", "deaths_cum"], dout)

    print("[%s] live0=%d  died_out(had pop0, pop600==0)=%d  total_deaths=%d  famine=%d"
          % (tag, len(live0),
             sum(1 for k in live0 if hh6[k]["pop"] == 0),
             sum(deaths.values()), sum(famine.values())))


if __name__ == "__main__":
    main()
