#!/usr/bin/env python3
"""W6 验收读数库：checkpoint 信封 → 紧凑逐日读数（只读）。

用法（库）：
    from w6lib import extract
    row = extract("/path/to/cp.json", day=120)
用法（命令行自证）：
    python3 w6lib.py <cp.json> [<cp.json>...]
"""
import json
import re
import sys
from collections import defaultdict


def _mod(env, ns):
    m = json.loads(env["modules"][ns])
    return m.get("data") if "data" in m else m.get("state", m)


def extract(path, day=None):
    env = json.load(open(path))
    ed = _mod(env, "economy")
    ad = _mod(env, "actor")
    sd = _mod(env, "social")
    ref = env.get("ref", {})
    out = {"day": day, "revision": ref.get("revision")}

    # ── 人口 ──────────────────────────────────────────────────────────────
    soc_by_hh = {}
    for hh, h in (sd.get("households") or {}).items():
        soc_by_hh[hh] = sum((h.get("members") or {}).values())
    eco_by_hh = {}
    for hh, c in (ed.get("classes") or {}).items():
        eco_by_hh[hh] = c.get("population", 0)
    mism = sorted(h for h in set(soc_by_hh) | set(eco_by_hh)
                  if soc_by_hh.get(h, 0) != eco_by_hh.get(h, 0))
    if day is not None and mism:
        pass  # 差异由调用方落盘
    out["pop"] = {
        "social_total": sum(soc_by_hh.values()),
        "economy_total": sum(eco_by_hh.values()),
        "social_groups_total": sum(g.get("count", 0) for g in (sd.get("groups") or {}).values()),
        "households": len(soc_by_hh),
        "mismatch_hh": len(mism),
    }
    out["pop_mismatch"] = mism[:20]

    # ── 人口事件（当日）───────────────────────────────────────────────────
    ev = defaultdict(int)
    deaths = defaultdict(int)
    famine_detail = defaultdict(int)
    for e in (sd.get("populationEvents") or {}).values():
        if day is not None and e.get("day") != day:
            continue
        t = e.get("type")
        ev[t] += e.get("count", 0)
        if t == "DEATH":
            r = e.get("reason") or ""
            m = re.search(r"cause=([A-Z_]+)", r)
            cause = m.group(1) if m else None
            n = e.get("count", 0)
            if cause == "BASE_PLUS_FAMINE":
                deaths["BASE_PLUS_FAMINE"] += n
            elif cause == "BASE":
                deaths["BASE"] += n
            elif cause:
                deaths["CAUSE_" + cause] += n
            elif "famine" in r:
                deaths["FAMINE_UNLABELED"] += n
            else:
                deaths["other"] += n
            fp = re.search(r"faminePpm=(\d+)", r)
            sr = re.search(r"starvationRatioPerMille=(\d+)", r)
            if fp and int(fp.group(1)) > 0:
                famine_detail["faminePpm=%s" % fp.group(1)] += n
            if sr:
                famine_detail["starvationRatio=%s" % sr.group(1)] += n
    out["events"] = dict(sorted(ev.items()))
    out["deaths"] = dict(sorted(deaths.items()))
    out["famine_detail"] = dict(sorted(famine_detail.items()))

    # ── 满足率 / 断顿 ─────────────────────────────────────────────────────
    sat = sd.get("satietyPerMille") or {}
    vals = sorted(sat.values())
    out["satiety"] = {
        "n": len(vals),
        "min": vals[0] if vals else None,
        "p10": vals[len(vals) // 10] if vals else None,
        "median": vals[len(vals) // 2] if vals else None,
        "lt1000": sum(1 for v in vals if v < 1000),
        "lt500": sum(1 for v in vals if v < 500),
        "zero": sum(1 for v in vals if v == 0),
        "sum": sum(vals),
    }

    # ── 货币 ──────────────────────────────────────────────────────────────
    money = defaultdict(int)
    goods = defaultdict(int)
    for row in (ad.get("accounts") or {}).values():
        for cur, amt in (row.get("money") or {}).items():
            money[cur] += amt
        for c, q in (row.get("balances") or {}).items():
            goods[c] += q
    out["money"] = dict(sorted(money.items()))
    out["money_total"] = sum(money.values())
    out["goods"] = dict(sorted(goods.items()))

    by_kind = defaultdict(lambda: defaultdict(int))
    recs = []
    for rec in (ed.get("moneyIssuances") or {}).values():
        cur = rec["currency"]["value"]
        by_kind[rec["kind"]][cur] += rec["amount"]
        recs.append({"day": rec.get("day"), "kind": rec["kind"], "cur": cur,
                     "amt": rec["amount"], "gov": rec.get("governmentId", {}).get("value")})
    out["issuance_by_kind"] = {k: dict(sorted(v.items())) for k, v in sorted(by_kind.items())}
    ident = defaultdict(int)
    for kind, v in by_kind.items():
        sign = -1 if kind == "WITHDRAWAL" else 1
        for cur, amt in v.items():
            ident[cur] += sign * amt
    out["issuance_identity"] = dict(sorted(ident.items()))
    if day is not None:
        out["issuance_today"] = [r for r in recs if r["day"] == day]

    treasury = {}
    for gid, g in (ed.get("governments") or {}).items():
        hh = (g.get("treasury") or {}).get("id")
        row = (ad.get("accounts") or {}).get(hh) or {}
        treasury[gid] = dict(sorted((row.get("money") or {}).items()))
    out["treasury"] = treasury

    # ── 债务 ──────────────────────────────────────────────────────────────
    pr = defaultdict(int)
    n_open = n_settled = 0
    loans_open = loans_new = 0
    loan_principal = defaultdict(int)
    for d in (ed.get("debtContracts") or {}).values():
        if d.get("status") == "SETTLED":
            n_settled += 1
            continue
        u = d.get("unit") or {}
        if not isinstance(u, dict):
            pr["?:" + str(u)] += d.get("principal", 0)
            n_open += 1
            continue
        cls = u.get("@class")
        key = (u.get("commodity") or {}).get("value") if cls == "commodity" else (u.get("currency") or {}).get("value")
        pr["%s:%s" % (cls, key)] += d.get("principal", 0)
        n_open += 1
        if d.get("kind") == "LOAN_PRINCIPAL" or d.get("origin") == "LOAN_PRINCIPAL":
            loans_open += 1
            loan_principal["%s:%s" % (cls, key)] += d.get("principal", 0)
    out["debt"] = {"open": n_open, "settled": n_settled,
                   "principal_by_medium": dict(sorted(pr.items()))}
    out["loans"] = {"open": loans_open, "principal_by_medium": dict(sorted(loan_principal.items()))}

    # ── 市场轮统计 / 价表 ─────────────────────────────────────────────────
    mrs = ed.get("marketRoundStats") or {}
    mk = {}
    for zid, s in mrs.items():
        mk[zid] = {
            "lastRoundDay": s.get("lastRoundDay"),
            "turnover": s.get("lastRoundTurnoverMilli"),
            "cashTurnover": s.get("lastRoundCashTurnoverMilli"),
            "cycleTurnover": s.get("cycleTurnoverMilli"),
            "lastRound": {c: {k: v for k, v in r.items()}
                          for c, r in (s.get("lastRound") or {}).items()},
        }
    out["market"] = mk
    tables = ed.get("marketTables") or {}
    tb = {}
    for zid, t in tables.items():
        tb[zid] = {
            "prices": dict(sorted((t.get("prices") or {}).items())),
            "numeraire": t.get("numeraire"),
            "loanRatePerMille": t.get("loanRatePerMille"),
            "loanRateAlphaPerMille": t.get("loanRateAlphaPerMille"),
        }
    out["marketTables"] = tb

    # ── 阶层 ──────────────────────────────────────────────────────────────
    st = ed.get("classStandings") or {}
    out["class_positions"] = {hh: (s.get("currentPositionId") or {}).get("value")
                              for hh, s in st.items()}
    out["class_last_transition"] = {hh: s.get("lastTransitionDay") for hh, s in st.items()
                                    if s.get("lastTransitionDay") == day}

    # ── 政府/危机（粗读数）───────────────────────────────────────────────
    out["gov_mandates"] = len(ed.get("govMarketMandates") or {})
    out["crisis"] = len(ed.get("crisisSignals") or {})
    out["last_closed_cycle"] = (ed.get("meta") or {}).get("lastClosedCycle")

    # ── 逐户流水（**本期发生额**：flows 是当日口径，见 d360/d361 对照）──────
    fl = ed.get("flows") or {}
    agg = defaultdict(int)
    money_agg = defaultdict(int)
    income = defaultdict(int)
    consumed = defaultdict(int)
    unmet = defaultdict(int)

    def _add(dst, src):
        for k, v in (src or {}).items():
            dst[k] += v

    for v in fl.values():
        agg["repaid"] += v.get("repaid", 0)
        agg["taxPaid"] += v.get("taxPaid", 0)
        agg["interestDue"] += v.get("interestDue", 0)
        agg["newBorrowing"] += v.get("newBorrowing", 0)
        agg["netSurplus"] += v.get("netSurplus", 0)
        agg["deaths"] += v.get("deaths", 0)
        agg["births"] += v.get("births", 0)
        _add(money_agg, v.get("repaidMoney"))
        _add(income, v.get("income"))
        _add(consumed, v.get("consumed"))
        _add(unmet, v.get("unmetNeed"))
    out["flows"] = dict(sorted(agg.items()))
    out["flows"]["repaidMoney"] = dict(sorted(money_agg.items()))
    out["flows"]["income"] = dict(sorted(income.items()))
    out["flows"]["consumed"] = dict(sorted(consumed.items()))
    out["flows"]["unmetNeed"] = dict(sorted(unmet.items()))
    return out


def main():
    for p in sys.argv[1:]:
        r = extract(p)
        print("==", p)
        print("  rev=%(revision)s pop=%(pop)s" % r)
        print("  money=%s total=%s" % (r["money"], r["money_total"]))
        print("  issuance=%s identity=%s" % (r["issuance_by_kind"], r["issuance_identity"]))
        print("  debt=%s" % r["debt"])
        print("  goods=%s" % r["goods"])
        print("  market=%s" % json.dumps(r["market"])[:600])
        print("  tables=%s" % json.dumps(r["marketTables"])[:600])
        print("  satiety=%s" % r["satiety"])
        print("  events=%s deaths=%s" % (r["events"], r["deaths"]))


if __name__ == "__main__":
    main()
