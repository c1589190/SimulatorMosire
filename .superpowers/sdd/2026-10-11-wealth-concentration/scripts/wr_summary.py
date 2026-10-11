#!/usr/bin/env python3
"""三问直答所需的一页汇总（只读；对 sw / tp 各跑一次，输出可直接贴进报告的文本）。

用法：python3 wr_summary.py --tag sw --daily <jsonl> --deaths <deaths-sw.json> \\
        --log <sw600.log> --regions <regions-sw.json>
"""
import argparse
import collections
import json
import math
import re
import sys

CL = {"rural-landlord": "地主(乡)", "urban-landlord": "地主(城)", "rural-rich_peasant": "富农(乡)",
      "urban-rich_peasant": "富农(城)", "rural-middle_peasant": "中农(乡)",
      "urban-middle_peasant": "中农(城)", "rural-poor_peasant": "贫农(乡)",
      "urban-poor_peasant": "贫农(城)", "rural-landless_laborer-displaced": "失地流民(乡)",
      "urban-landless_laborer-displaced": "失地流民(城)",
      "GOV_TREASURY": "国库户", "GOV_STAFF": "官吏户"}


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


def gini(v):
    v = sorted(v)
    n, s = len(v), sum(v)
    return 0.0 if n == 0 or s <= 0 else (2 * sum((i + 1) * x for i, x in enumerate(v))) / (n * s) - (n + 1.0) / n


def ts(v, f):
    v = sorted(v, reverse=True)
    s = sum(v)
    if s <= 0:
        return None
    k = max(1, math.ceil(len(v) * f))
    return sum(v[:k]) / s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--daily", required=True)
    ap.add_argument("--deaths", required=True)
    ap.add_argument("--log", default=None)
    ap.add_argument("--regions", default=None)
    a = ap.parse_args()
    rows = [json.loads(l) for l in open(a.daily) if l.strip()]
    rows.sort(key=lambda r: r["day"])
    byday = {r["day"]: r for r in rows}
    d0, dl = byday[0], byday[rows[-1]["day"]]
    panel = [k for k, v in d0["hh"].items() if kind(k) not in ("GOV_TREASURY", "GOV_STAFF") and v["pop"] > 0]
    ev = json.load(open(a.deaths))["events"]
    deaths = collections.Counter()
    famine = collections.Counter()
    for e in ev:
        deaths[e["household"]] += e["count"]
        if e["cause"] == "BASE_PLUS_FAMINE":
            famine[e["household"]] += e["count"]
    P = print
    P("################  %s  ################" % a.tag.upper())
    P("[基本] 行数=%d 固定面板(day0 有人)=%d  死亡事件=%d 死亡人数=%d 饿死=%d"
      % (len(d0["hh"]), len(panel), len(ev), sum(deaths.values()), sum(famine.values())))
    P("[人口] day0=%d -> day600=%d (%.1f%%)"
      % (sum(v["pop"] for v in d0["hh"].values()), sum(v["pop"] for v in dl["hh"].values()),
         100.0 * (sum(v["pop"] for v in dl["hh"].values()) / sum(v["pop"] for v in d0["hh"].values()) - 1)))
    # 集中度
    P("\n-- 集中度（固定面板；时点快照）--")
    for d in (0, 120, 240, 360, 480, 600):
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        g = [hh[k]["bal"].get("grain", 0) for k in panel]
        gp = [(hh[k]["bal"].get("grain", 0) / hh[k]["pop"]) if hh[k]["pop"] else 0.0 for k in panel]
        s = [hh[k]["money"].get("silver", 0) for k in panel]
        P("  day%-4d 粮 Gini=%.4f Top1%%=%s Top10%%=%s 非零=%d/%d | 人均粮 Gini=%.4f Top1%%=%s | 银 Gini=%.4f 合计=%s"
          % (d, gini(g), "%.2f%%" % (100 * ts(g, .01)), "%.2f%%" % (100 * ts(g, .10)),
             sum(1 for x in g if x > 0), len(g), gini(gp), "%.2f%%" % (100 * ts(gp, .01)),
             gini(s), f"{sum(s):,}"))
    # 国库
    P("\n-- 国库/政府口（时点快照）--")
    for d in (0, 120, 240, 360, 480, 600):
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        gm = sum(v["money"].get("silver", 0) for k, v in hh.items() if kind(k) in ("GOV_TREASURY", "GOV_STAFF"))
        wm = sum(v["money"].get("silver", 0) for v in hh.values())
        gg = sum(v["bal"].get("grain", 0) for k, v in hh.items() if kind(k) in ("GOV_TREASURY", "GOV_STAFF"))
        wg = sum(v["bal"].get("grain", 0) for v in hh.values())
        P("  day%-4d 政府口银=%s/%s=%.2f%% ; 政府口粮=%s/%s=%.2f%%"
          % (d, f"{gm:,}", f"{wm:,}", 100.0 * gm / wm, f"{gg:,}", f"{wg:,}", 100.0 * gg / wg))
    # 挨饿面
    P("\n-- 挨饿面（时点快照；人口加权；剔除非家户行）--")
    for d in (0, 120, 240, 360, 480, 600):
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        pop = z = gz = 0
        ws = 0
        for k, v in hh.items():
            if kind(k) in ("GOV_TREASURY", "GOV_STAFF"):
                continue
            pop += v["pop"]
            if v["sat"] is not None:
                ws += v["pop"] * v["sat"]
                if v["sat"] == 0:
                    z += v["pop"]
            if v["bal"].get("grain", 0) == 0:
                gz += v["pop"]
        P("  day%-4d pop=%d 加权饱食度=%.1f‰ 饱食度0的人口=%d(%.1f%%) 粮0的人口=%d(%.1f%%)"
          % (d, pop, ws / max(1, pop), z, 100.0 * z / max(1, pop), gz, 100.0 * gz / max(1, pop)))
    # 死亡率
    P("\n-- 死亡率（分子=历史累计死亡；分母=day0 人口）--")
    for dim, keyf in (("阶层", kind), ("格", hx)):
        tab = collections.defaultdict(lambda: [0, 0, 0, 0, 0])
        for k in panel:
            g = keyf(k)
            tab[g][0] += d0["hh"][k]["pop"]
            tab[g][1] += deaths[k]
            tab[g][2] += famine[k]
            tab[g][3] += 1
            if dl["hh"][k]["pop"] == 0:
                tab[g][4] += 1
        P("  [按%s]" % dim)
        for g, (p0, d, f, n, dead) in sorted(tab.items(), key=lambda x: -(x[1][1] / max(1, x[1][0]))):
            P("    %-38s 户=%3d pop0=%5d 死亡=%5d 饿死=%5d %6.1f‰ 全灭=%d/%d 末人口=%d"
              % (g, n, p0, d, f, 1000.0 * d / max(1, p0), dead, n,
                 sum(dl["hh"][k]["pop"] for k in panel if keyf(k) == g)))
    P("    [全世界] pop0=%d 死亡=%d %6.1f‰"
      % (sum(d0["hh"][k]["pop"] for k in panel), sum(deaths[k] for k in panel),
         1000.0 * sum(deaths[k] for k in panel) / sum(d0["hh"][k]["pop"] for k in panel)))
    # TopN
    P("\n-- Top N 户 生存（基准日 / 排序口径；N 集合 = rank<=N）--")
    for bd, bmap in ((0, d0["hh"]), (360, byday[360]["hh"])):
        for basis, vf in (("粮总量", lambda r: r["bal"].get("grain", 0)),
                          ("人均粮", lambda r: (r["bal"].get("grain", 0) / r["pop"]) if r["pop"] else -1)):
            cand = [k for k in bmap if kind(k) not in ("GOV_TREASURY", "GOV_STAFF") and bmap[k]["pop"] > 0]
            cand.sort(key=lambda k: -vf(bmap[k]))
            for N in (10, 50):
                sel = cand[:N]
                p0 = sum(d0["hh"][k]["pop"] for k in sel)
                dd = sum(deaths[k] for k in sel)
                P("  day%-4d %-6s Top%-3d Σpop0=%5d 死亡=%4d %6.1f‰ 全灭户=%2d/%d 末粮=%s"
                  % (bd, basis, N, p0, dd, 1000.0 * dd / max(1, p0),
                     sum(1 for k in sel if dl["hh"][k]["pop"] == 0), N,
                     f"{sum(dl['hh'][k]['bal'].get('grain',0) for k in sel):,}"))
    P("\n  [day0 人均粮 Top10 明细]")
    cand = [k for k in d0["hh"] if kind(k) not in ("GOV_TREASURY", "GOV_STAFF") and d0["hh"][k]["pop"] > 0]
    cand.sort(key=lambda k: -(d0["hh"][k]["bal"].get("grain", 0) / d0["hh"][k]["pop"]))
    for k in cand[:10]:
        P("    %-42s 人均粮0=%8d pop0=%3d 死亡=%3d 饿死=%3d 末粮=%12s 末人口=%4d"
          % (k, d0["hh"][k]["bal"].get("grain", 0) // d0["hh"][k]["pop"], d0["hh"][k]["pop"],
             deaths[k], famine[k], f"{dl['hh'][k]['bal'].get('grain',0):,}", dl["hh"][k]["pop"]))
    P("\n  [day0 粮总量 Top10 明细]")
    cand.sort(key=lambda k: -d0["hh"][k]["bal"].get("grain", 0))
    for k in cand[:10]:
        P("    %-42s 粮0=%12s pop0=%3d 死亡=%3d 饿死=%3d 末粮=%12s 末人口=%4d"
          % (k, f"{d0['hh'][k]['bal'].get('grain',0):,}", d0["hh"][k]["pop"], deaths[k], famine[k],
             f"{dl['hh'][k]['bal'].get('grain',0):,}", dl["hh"][k]["pop"]))
    P("\n  [day600 粮 Top10（终局赢家）]")
    for k in sorted(cand, key=lambda k: -dl["hh"][k]["bal"].get("grain", 0))[:10]:
        P("    %-42s 末粮=%12s 末人口=%4d 死亡=%3d 饿死=%3d"
          % (k, f"{dl['hh'][k]['bal'].get('grain',0):,}", dl["hh"][k]["pop"], deaths[k], famine[k]))
    # 债务
    P("\n-- 债务（时点快照 day600；名义毫币）--")
    P("  " + json.dumps(dl["debt_s"], ensure_ascii=False))
    hh = dl["hh"]
    led = sorted(hh.items(), key=lambda x: -(sum(x[1]["lent_o"].values()) + sum(x[1]["lent_d"].values())))[:5]
    P("  债权人 Top5:")
    for k, v in led:
        P("    %-42s 应收=%s" % (k, f"{sum(v['lent_o'].values()) + sum(v['lent_d'].values()):,}"))
    ded = sorted(hh.items(), key=lambda x: -(sum(x[1]["owe_o"].values()) + sum(x[1]["owe_d"].values())))[:5]
    P("  债务人 Top5:")
    for k, v in ded:
        P("    %-42s 应付=%s (合约 %d)" % (k, f"{sum(v['owe_o'].values()) + sum(v['owe_d'].values()):,}", v["debt_n"]))
    P("\n-- 守恒（时点快照）--")
    for d in (0, 120, 240, 360, 480, 600):
        r = byday.get(d)
        if not r:
            continue
        bad = 0
        hh = r["hh"]
        for cur in ("silver", "copper", "gold"):
            bad += abs(sum(v["money"].get(cur, 0) for v in hh.values()) - r["money"].get(cur, 0))
        for com in ("grain", "cloth", "fiber", "tool", "iron", "haul"):
            bad += abs(sum(v["bal"].get(com, 0) for v in hh.values()) - r["goods"].get(com, 0))
        P("  day%-4d Σ|账户-聚合| = %d" % (d, bad))
    if a.log:
        P("\n-- 日志口径（历史累计；窗口=600 天求和）--")
        tot = collections.Counter()
        n = 0
        for line in open(a.log, errors="replace"):
            if "event=TAX_DAILY_END " in line:
                n += 1
                kv = dict(re.findall(r"(\w+)=([^\s]+)", line.split("TAX_DAILY_END ", 1)[1]))
                for f in ("grainAssessed", "grainCollected", "moneyAssessed", "moneyCollected"):
                    tot[f] += int(kv.get(f, 0))
        P("  TAX_DAILY_END 天数=%d %s" % (n, dict(tot)))
        sal = collections.Counter()
        for line in open(a.log, errors="replace"):
            if "event=GOV_ADMIN_SALARY_DAY " in line:
                kv = dict(re.findall(r"(\w+)=([^\s]+)", line.split("GOV_ADMIN_SALARY_DAY ", 1)[1]))
                for f in ("paidGrain", "paidSilver"):
                    sal[f] += int(kv.get(f, 0))
        P("  GOV_ADMIN_SALARY_DAY 合计 %s" % dict(sal))
        P("  救济类事件命中数 = %d"
          % sum(1 for line in open(a.log, errors="replace")
                if re.search(r"relief|subsid|welfare|allowance|dole|救济|补助", line, re.I)))


if __name__ == "__main__":
    main()
