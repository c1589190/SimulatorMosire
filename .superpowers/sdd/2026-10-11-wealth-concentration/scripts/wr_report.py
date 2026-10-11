#!/usr/bin/env python3
"""把逐日读数变成报告用的 markdown 表（固定面板口径；只读）。

口径（每个表头写明）：
  · **时点快照** = 该 day 的 checkpoint；**本期发生额** = flows（周期内累计）；**历史累计** = populationEvents / 逐日事件求和
  · 集中度一律用**固定面板**：day0 人口 > 0 的家户行（sw=105 / tp=198），避免中途人口归零的行
    进出样本把 Gini 的变化混成"样本变化"。另给"全体行"作对照。

用法：python3 wr_report.py --daily <jsonl> --tag sw --out <tables-sw.md>
"""
import argparse
import collections
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

CLASS_CN = {
    "rural-landlord": "地主(乡)", "urban-landlord": "地主(城)",
    "rural-rich_peasant": "富农(乡)", "urban-rich_peasant": "富农(城)",
    "rural-middle_peasant": "中农(乡)", "urban-middle_peasant": "中农(城)",
    "rural-poor_peasant": "贫农(乡)", "urban-poor_peasant": "贫农(城)",
    "rural-landless_laborer-displaced": "失地流民(乡)",
    "urban-landless_laborer-displaced": "失地流民(城)",
    "GOV_TREASURY": "国库户", "GOV_STAFF": "官吏/军户",
}
CLASS_ORDER = ["rural-landlord", "rural-rich_peasant", "rural-middle_peasant",
               "rural-poor_peasant", "rural-landless_laborer-displaced",
               "urban-landlord", "urban-rich_peasant", "urban-middle_peasant",
               "urban-poor_peasant", "urban-landless_laborer-displaced",
               "GOV_STAFF", "GOV_TREASURY"]


def kind(h):
    if h.startswith("hh-gov-"):
        return "GOV_TREASURY"
    if h.startswith("hh-unit:"):
        return "GOV_STAFF"
    m = re.match(r"hh-(-?\d+)_(-?\d+)-(.+)", h)
    return m.group(3) if m else "OTHER"


def hexof(h):
    m = re.match(r"hh-(-?\d+)_(-?\d+)-", h)
    return "%d_%d" % (int(m.group(1)), int(m.group(2))) if m else "GOV"


def gini(v):
    v = sorted(v)
    n = len(v)
    s = sum(v)
    if n == 0 or s <= 0:
        return 0.0
    return (2 * sum((i + 1) * x for i, x in enumerate(v))) / (n * s) - (n + 1.0) / n


def topshare(v, f):
    v = sorted(v, reverse=True)
    s = sum(v)
    if s <= 0:
        return None
    k = max(1, math.ceil(len(v) * f))
    return sum(v[:k]) / s


def pctl(v, q):
    v = sorted(v)
    if not v:
        return None
    k = (len(v) - 1) * q
    f, c = math.floor(k), math.ceil(k)
    return v[int(k)] if f == c else v[f] * (c - k) + v[c] * (k - f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--daily", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--grid", default="0,120,240,360,480,600")
    ap.add_argument("--regions", default=None, help="hex→region JSON（政区/市场区分组用）")
    a = ap.parse_args()
    grid = [int(x) for x in a.grid.split(",")]
    regions = json.load(open(a.regions)) if a.regions else {}
    rows = [json.loads(l) for l in open(a.daily) if l.strip()]
    rows.sort(key=lambda r: r["day"])
    byday = {r["day"]: r for r in rows}
    d0, dl = byday[rows[0]["day"]], byday[rows[-1]["day"]]
    panel = sorted(k for k, v in d0["hh"].items()
                   if kind(k) not in ("GOV_TREASURY", "GOV_STAFF") and v["pop"] > 0)
    assert panel, "固定面板为空 —— day0 没有人口>0 的家户行"
    L = []
    W = L.append

    def money(v):
        return v["money"]

    # ── T1 集中度（固定面板）─────────────────────────────────────────────
    W("#### T1 财富集中度（**时点快照**；固定面板 = day0 人口>0 的家户行，n=%d）\n" % len(panel))
    W("| day | 口径 basis | 合计 Σ | 中位 P50 | P90 | P99 | 最大 | Gini | Top1% 份额 | Top10% 份额 | 非零户 |")
    W("|---|---|---|---|---|---|---|---|---|---|---|")
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        for name, fn in (("粮 grain（毫）", lambda v: v["bal"].get("grain", 0)),
                         ("人均粮（毫/人）", lambda v: (v["bal"].get("grain", 0) / v["pop"]) if v["pop"] else 0.0),
                         ("银 money.silver（毫）", lambda v: v["money"].get("silver", 0)),
                         ("土地份额 LAND（份）", lambda v: v["own"].get("LAND", 0))):
            vals = [fn(hh[k]) for k in panel]
            t1, t10 = topshare(vals, .01), topshare(vals, .10)
            W("| %d | %s | %s | %s | %s | %s | %s | %.4f | %s | %s | %d |"
              % (d, name, f"{sum(vals):,.0f}", f"{pctl(vals,.5):,.0f}", f"{pctl(vals,.9):,.0f}",
                 f"{pctl(vals,.99):,.0f}", f"{max(vals):,.0f}", gini(vals),
                 ("%.2f%%" % (100 * t1)) if t1 is not None else "n/a",
                 ("%.2f%%" % (100 * t10)) if t10 is not None else "n/a",
                 sum(1 for x in vals if x > 0)))
    W("")

    # ── T2 按阶层 ────────────────────────────────────────────────────────
    W("#### T2 按阶层（**时点快照**）\n")
    W("| day | 阶层 | 户行数 | 人口 | 粮（毫） | 粮份额 | 人均粮 | 银（毫） | 土地份额 | 饱食度(人口加权‰) | 粮=0 的户 |")
    W("|---|---|---|---|---|---|---|---|---|---|---|")
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        tot = sum(v["bal"].get("grain", 0) for v in hh.values())
        grp = collections.defaultdict(list)
        for k in hh:
            grp[kind(k)].append(k)
        for g in CLASS_ORDER + [x for x in sorted(grp) if x not in CLASS_ORDER]:
            if g not in grp:
                continue
            ks = grp[g]
            pop = sum(hh[k]["pop"] for k in ks)
            gs = sum(hh[k]["bal"].get("grain", 0) for k in ks)
            ws = sum(hh[k]["pop"] * (hh[k]["sat"] or 0) for k in ks)
            W("| %d | %s `%s` | %d | %d | %s | %s | %s | %s | %s | %s | %d |"
              % (d, CLASS_CN.get(g, g), g, len(ks), pop, f"{gs:,}",
                 ("%.2f%%" % (100 * gs / tot)) if tot else "-", f"{gs//max(1,pop):,}",
                 f"{sum(hh[k]['money'].get('silver',0) for k in ks):,}",
                 f"{sum(hh[k]['own'].get('LAND',0) for k in ks):,}",
                 ("%.1f" % (ws / pop)) if pop else "-",
                 sum(1 for k in ks if hh[k]["bal"].get("grain", 0) == 0)))
        W("")
    W("")

    # ── T3 按格（市场区用格代替：three-powers 的三张价表 hexes 为空，见 §4 缺口）──
    W("#### T3 按格（**时点快照**）\n")
    W("| day | 格 hex | 户行 | 人口 | 粮（毫） | 粮份额 | 人均粮 | 银（毫） | 饱食度(人口加权‰) | 粮=0 户 |")
    W("|---|---|---|---|---|---|---|---|---|---|")
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        tot = sum(v["bal"].get("grain", 0) for v in hh.values())
        grp = collections.defaultdict(list)
        for k in hh:
            grp[hexof(k)].append(k)
        for g, ks in sorted(grp.items(), key=lambda x: -sum(hh[k]["bal"].get("grain", 0) for k in x[1])):
            pop = sum(hh[k]["pop"] for k in ks)
            gs = sum(hh[k]["bal"].get("grain", 0) for k in ks)
            if gs == 0 and d not in (0, 600):
                continue
            ws = sum(hh[k]["pop"] * (hh[k]["sat"] or 0) for k in ks)
            W("| %d | `%s` | %d | %d | %s | %s | %s | %s | %s | %d |"
              % (d, g, len(ks), pop, f"{gs:,}",
                 ("%.2f%%" % (100 * gs / tot)) if tot else "-", f"{gs//max(1,pop):,}",
                 f"{sum(hh[k]['money'].get('silver',0) for k in ks):,}",
                 ("%.1f" % (ws / pop)) if pop else "-",
                 sum(1 for k in ks if hh[k]["bal"].get("grain", 0) == 0)))
        W("")
    W("")

    # ── T3b 按政区（= 税区；three-powers 的三张价表 hexes 为空，故"市场区"只能用政区/格代理）──
    if regions:
        W("#### T3b 按政区（**时点快照**；政区表来自 map.regions，逐格归属）\n")
        W("| day | 政区 | 格数 | 户行 | 人口 | 粮（毫） | 粮份额 | 人均粮 | 银（毫） | 人口加权饱食度(‰) |")
        W("|---|---|---|---|---|---|---|---|---|---|")
        for d in grid:
            r = byday.get(d)
            if not r:
                continue
            hh = r["hh"]
            tot = sum(v["bal"].get("grain", 0) for v in hh.values())
            grp = collections.defaultdict(list)
            for k in hh:
                h = hexof(k)
                grp[regions.get(h, "(无政区:" + h + ")") if h != "GOV" else "GOV(无格)"].append(k)
            for g, ks in sorted(grp.items(), key=lambda x: -sum(hh[k]["bal"].get("grain", 0) for k in x[1])):
                pop = sum(hh[k]["pop"] for k in ks)
                gs = sum(hh[k]["bal"].get("grain", 0) for k in ks)
                ws = sum(hh[k]["pop"] * (hh[k]["sat"] or 0) for k in ks)
                hexes_ = {hexof(k) for k in ks if hexof(k) != "GOV"}
                W("| %d | `%s` | %d | %d | %d | %s | %s | %s | %s | %s |"
                  % (d, g, len(hexes_), len(ks), pop, f"{gs:,}",
                     ("%.2f%%" % (100 * gs / tot)) if tot else "-", f"{gs//max(1,pop):,}",
                     f"{sum(hh[k]['money'].get('silver',0) for k in ks):,}",
                     ("%.1f" % (ws / pop)) if pop else "-"))
            W("")
        W("")

    # ── T4 国库占比时间序列 ──────────────────────────────────────────────
    W("#### T4 国库/政府口占比（**时点快照**）\n")
    W("| day | 政府口银 | 世界银 | 银占比 | 政府口粮（毫） | 世界粮（毫） | 粮占比 | 累计铸币 silver |")
    W("|---|---|---|---|---|---|---|---|")
    mint = collections.Counter()
    for r in rows:
        for it in (r.get("issuance_today") or []):
            mint[it["cur"]] += it.get("amt", 0)
        if r["day"] not in grid:
            continue
        hh = r["hh"]
        gm = sum(v["money"].get("silver", 0) for k, v in hh.items()
                 if kind(k) in ("GOV_TREASURY", "GOV_STAFF"))
        gg = sum(v["bal"].get("grain", 0) for k, v in hh.items()
                 if kind(k) in ("GOV_TREASURY", "GOV_STAFF"))
        wm = sum(v["money"].get("silver", 0) for v in hh.values())
        wg = sum(v["bal"].get("grain", 0) for v in hh.values())
        W("| %d | %s | %s | %s | %s | %s | %s | %s |"
          % (r["day"], f"{gm:,}", f"{wm:,}", "%.2f%%" % (100 * gm / wm) if wm else "-",
             f"{gg:,}", f"{wg:,}", "%.2f%%" % (100 * gg / wg) if wg else "-", f"{mint['silver']:,}"))
    W("")

    # ── T5 逐日饱食度（人口加权）────────────────────────────────────────
    W("#### T5 挨饿面（**时点快照**；按人口加权，剔除非家户行）\n")
    W("| day | 人口 | 人口加权饱食度(‰) | 饱食度=0 的人口 | 占比 | 粮=0 的人口 | 占比 |")
    W("|---|---|---|---|---|---|---|")
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        pop = z = gz = 0
        wsum = 0
        for k, v in hh.items():
            if kind(k) in ("GOV_TREASURY", "GOV_STAFF"):
                continue
            p, s = v["pop"], v["sat"]
            pop += p
            if s is not None:
                wsum += p * s
                if s == 0:
                    z += p
            if v["bal"].get("grain", 0) == 0:
                gz += p
        W("| %d | %s | %.1f | %s | %.1f%% | %s | %.1f%% |"
          % (d, f"{pop:,}", wsum / max(1, pop), f"{z:,}", 100 * z / max(1, pop),
             f"{gz:,}", 100 * gz / max(1, pop)))
    W("")

    # ── T6 逐户累计发生额（历史累计，flows 按周期边界还原）──────────────
    cum = {f: collections.defaultdict(float) for f in ("tax", "int", "bor", "rep")}
    last = {f: {} for f in cum}
    for r in rows:
        for k, rec in r["hh"].items():
            fl = rec["flow"]
            for f in cum:
                v = fl.get(f, 0) or 0
                lv = last[f].get(k, 0)
                if v < lv:
                    cum[f][k] += lv
                last[f][k] = v
    for f in cum:
        for k, v in last[f].items():
            cum[f][k] += v
    W("#### T6 逐户**历史累计**发生额（窗口 = 创世起 600 天；由 flows 的周期边界回零还原）\n")
    W("| 阶层 | 累计纳税(毫粮) | 份额 | 累计应付利息(毫银) | 累计新借(毫粮) | 累计偿还(毫粮) |")
    W("|---|---|---|---|---|---|")
    tot = sum(cum["tax"].values()) or 1
    grp = collections.defaultdict(lambda: [0, 0, 0, 0])
    for k in set().union(*[set(cum[f]) for f in cum]):
        g = kind(k)
        grp[g][0] += cum["tax"].get(k, 0)
        grp[g][1] += cum["int"].get(k, 0)
        grp[g][2] += cum["bor"].get(k, 0)
        grp[g][3] += cum["rep"].get(k, 0)
    for g, (t, i, b, rp) in sorted(grp.items(), key=lambda x: -x[1][0]):
        W("| %s `%s` | %s | %.2f%% | %s | %s | %s |"
          % (CLASS_CN.get(g, g), g, f"{t:,.0f}", 100 * t / tot, f"{i:,.0f}", f"{b:,.0f}", f"{rp:,.0f}"))
    W("| **合计** | **%s** | 100%% | **%s** | **%s** | **%s** |"
      % (f"{sum(cum['tax'].values()):,.0f}", f"{sum(cum['int'].values()):,.0f}",
         f"{sum(cum['bor'].values()):,.0f}", f"{sum(cum['rep'].values()):,.0f}"))
    W("")

    # ── T7 债务（时点快照 + 名义/可执行）────────────────────────────────
    W("#### T7 债务与债权（**时点快照** day%d）\n" % rows[-1]["day"])
    dl_ = dl
    W("```\n" + json.dumps(dl_["debt_s"], ensure_ascii=False, indent=1) + "\n```\n")
    W("| 户 | 阶层 | 应收 lent（毫银，含违约） | 应付 owe（毫银，含违约） | 合约数 |")
    W("|---|---|---|---|---|")
    hh = dl_["hh"]
    top = sorted(hh.items(), key=lambda x: -(sum(x[1]["lent_o"].values()) + sum(x[1]["lent_d"].values())))[:10]
    for k, v in top:
        W("| `%s` | %s | %s | %s | %d |"
          % (k, CLASS_CN.get(kind(k), kind(k)),
             f"{sum(v['lent_o'].values()) + sum(v['lent_d'].values()):,}",
             f"{sum(v['owe_o'].values()) + sum(v['owe_d'].values()):,}", v["debt_n"]))
    W("")
    W("| 户 | 阶层 | 应付 owe（毫银） | 合约数 |")
    W("|---|---|---|---|")
    for k, v in sorted(hh.items(), key=lambda x: -(sum(x[1]["owe_o"].values()) + sum(x[1]["owe_d"].values())))[:10]:
        W("| `%s` | %s | %s | %d |"
          % (k, CLASS_CN.get(kind(k), kind(k)),
             f"{sum(v['owe_o'].values()) + sum(v['owe_d'].values()):,}", v["debt_n"]))
    W("")

    # ── T8 守恒核对 ─────────────────────────────────────────────────────
    W("#### T8 守恒核对（**时点快照**；Σactor.accounts vs 该日聚合读数）\n")
    W("| day | 币/货 | Σ逐户账户 | 聚合读数 | 差 |")
    W("|---|---|---|---|---|")
    for d in grid:
        r = byday.get(d)
        if not r:
            continue
        hh = r["hh"]
        bad = 0
        for cur in ("silver", "copper", "gold"):
            s = sum(v["money"].get(cur, 0) for v in hh.values())
            t = r["money"].get(cur, 0)
            if s or t:
                W("| %d | money.%s | %s | %s | **%d** |" % (d, cur, f"{s:,}", f"{t:,}", s - t))
                bad += abs(s - t)
        for com in ("grain", "cloth", "fiber", "tool", "iron", "haul"):
            s = sum(v["bal"].get(com, 0) for v in hh.values())
            t = r["goods"].get(com, 0)
            if s or t:
                W("| %d | goods.%s | %s | %s | **%d** |" % (d, com, f"{s:,}", f"{t:,}", s - t))
                bad += abs(s - t)
        W("| %d | **该日差额合计** | | | **%d** |" % (d, bad))
    W("")

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    open(a.out, "w").write("\n".join(L) + "\n")
    print("wrote %s (%d lines, panel=%d)" % (a.out, len(L), len(panel)))


if __name__ == "__main__":
    main()
