#!/usr/bin/env python3
"""汇总表生成器：把 dd-analyze.json（逐日读数派生）+ dd-events-*.json（成因权威判定）
拼成报告用的 markdown 表，落 results/tables.md。

★ 窗口标注（AGENTS §九.4）：所有债务/人口数都是**时点快照**（该日关账后的状态）；
  "户绝日 / +120 / +240 / …" 是同一批合同 id 在不同时点的取值序列，不是本期/累计发生额。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "results")
WORLDS = (("sw600", "small-world"), ("tp600", "three-powers"))
GRID = (0, 120, 240, 360, 480, 600)


def fmt(d):
    return "—" if d is None else ("{:,}".format(d) if isinstance(d, int) else str(d))


def main():
    an = json.load(open(os.path.join(RES, "dd-analyze.json")))
    ev = {t: json.load(open(os.path.join(RES, "dd-events-%s.json" % t)))
          for t in ("sw600", "tp600")}
    out = []
    W = out.append

    for tag, world in WORLDS:
        w = an[tag]
        e = {r["hh"]: r for r in ev[tag]["rows"]}
        W("## %s（%s，0→600 逐日）\n" % (world, tag))
        a = w["A_counts"]
        c = w["C_d600"]
        W("### A 户绝清单（population > 0 → 0 的家户）\n")
        W("- 家户行总数 **%d**；运行中真实户绝 **%d**；d600 仍活着 **%d**；"
          "从未有过人口的户槽位 **%d**（创世即空的城市槽位）"
          % (a["households_total"], a["extinct"], a["alive_at_end"], c["neverPopulated"]))
        W("- 成因（权威判据 = `social.populationEvents` 逐户逐日）：%s\n"
          % json.dumps(ev[tag]["byCause"], ensure_ascii=False))
        W("| 户 id | 归零日 | 死前人口 | 成因 | 当日死/移出 | 阶层 | 死前银钱 | 死前粮 | 死前地权 | 银钱分位 | 户绝前一刻未偿 |")
        W("|---|---|---|---|---|---|---|---|---|---|---|")
        axe = {r["hh"]: r for r in w["A_extinct"]}
        for t in sorted(w["B_traj"], key=lambda x: x["zeroDay"]):
            r = e.get(t["hh"], {})
            ax = axe.get(t["hh"], {})
            W("| `%s` | %d | %d | %s | %s/%s | %s | %s | %s | %s | %s | %s |" % (
                t["hh"], t["zeroDay"], t["popBefore"], r.get("cause", "?"),
                fmt(r.get("dayDeaths")), fmt(r.get("dayTransferOut")),
                t["stratum"],
                json.dumps(ax.get("money") or {}, ensure_ascii=False),
                fmt(ax.get("grain")), fmt(ax.get("land")),
                ("%s%%" % ax["moneyPctile"]) if ax.get("moneyPctile") is not None else "—",
                fmt(t["oweAtZeroMinus1"])))
        W("")

        W("### B 户绝之后这些债怎么变（同一户的未偿本金，逐时点）\n")
        W("| 户 id | 户绝日 | 户绝前一刻 | +120 | +240 | +360 | +480 | d600 | 倍率 d600/户绝 |")
        W("|---|---|---|---|---|---|---|---|---|")
        for t in sorted(w["B_traj"], key=lambda x: x["zeroDay"]):
            d0 = t["zeroDay"]
            s = t["series"]

            def g(off):
                T = min(d0 + off, 600)
                return s.get(str(T), {}).get("oweTotal")
            base = t["oweAtZeroMinus1"]
            end = s["600"]["oweTotal"]
            ratio = ("×%.2f" % (end / base)) if base else ("—" if end == 0 else "0→%s" % fmt(end))
            W("| `%s` | %d | %s | %s | %s | %s | %s | **%s** | %s |" % (
                t["hh"], d0, fmt(base), fmt(g(120)), fmt(g(240)), fmt(g(360)), fmt(g(480)),
                fmt(end), ratio))
        W("")

        W("### C d600 未偿总额拆解（时点快照；按标的分别列，**币种不可相加**）\n")
        W("- 全世界货币量（actor 账合计）：%s" % json.dumps(c["worldMoney"], ensure_ascii=False))
        W("- 活着债务人的 户×标的 组合数 **%d**，其中该标的未偿 > 全世界该币种货币量（= 按用户口径"
          "**不可偿还**）的 **%d** 组\n" % (c["aliveDebtorPairs"], c["unrepayablePairs"]))
        W("| 桶 | 合同数 | 未偿（按标的） |")
        W("|---|---|---|")
        names = {"1_debtor_extinct": "① 债务人已户绝（运行中 >0→0）",
                 "1b_debtor_never_populated": "①b 债务人是从未有人口的空户槽位",
                 "2_alive_unrepayable": "② 债务人活着但未偿 > 全世界货币量（不可偿还）",
                 "3_alive_normal_scale": "③ 正常规模",
                 "4_unknown_debtor": "④ 其他"}
        for k in ("1_debtor_extinct", "1b_debtor_never_populated", "2_alive_unrepayable",
                  "3_alive_normal_scale", "4_unknown_debtor"):
            if k in c["buckets"]:
                W("| %s | %d | %s |" % (names[k], c["contracts"][k],
                                        json.dumps(c["buckets"][k], ensure_ascii=False)))
        W("| **扣掉 ①+①b 之后** | — | %s |"
          % json.dumps(c["exclExtinct"], ensure_ascii=False))
        W("")
        W("### D 债权人侧：已户绝家户作为债权人的应收（d600 时点）\n")
        W("- 合同数 **%d**，按标的：%s\n" % (w["D_creditor"]["contracts"],
                                            json.dumps(w["D_creditor"]["by_unit"], ensure_ascii=False)))
        W("| 合同的债务人 | 本金 | 标的 | 起始日 | 状态 |")
        W("|---|---|---|---|---|")
        for r in w["D_creditor"]["rows"][:8]:
            W("| `%s` | %s | %s | %s | %s |" % (r["debtor"], fmt(r["principal"]), r["unit"],
                                                r["opened"], r["status"]))
        W("")
        W("### E d600 未偿额 Top 债务人\n")
        W("| 户 id | 阶层 | 人口 | 活着? | 未偿合计 | 其中标的 | 钱 | 粮 | 地权 |")
        W("|---|---|---|---|---|---|---|---|---|")
        for r in w["E_top"][:12]:
            W("| `%s` | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                r["hh"], r["stratum"], r["pop"], "是" if r["alive"] else "否",
                fmt(r["total"]), json.dumps(r["byUnit"], ensure_ascii=False),
                json.dumps(r["money"], ensure_ascii=False), fmt(r["grain"]), fmt(r["land"])))
        W("")
        f = w["F_rates"]
        W("### F 利率面（d600 活跃合同的条款利率，‰/周期）\n")
        W("- n=%d，min=%s，p50=%s，max=%s；=20‰ 的 %d 条，>1000‰ 的 %d 条，>100000‰ 的 %d 条\n"
          % (f["n"], f["min"], f["p50"], f["max"], f["eq20"], f["gt1000"], f["gt100000"]))
        W("")

    p = os.path.join(RES, "tables.md")
    with open(p, "w") as fh:
        fh.write("\n".join(out) + "\n")
    print("→", p, "(%d lines)" % len(out))


if __name__ == "__main__":
    main()
