#!/usr/bin/env python3
"""W7 测试批：checkpoint dump → 本批判据要的读数（CATTLE 守恒 / 货币守恒 / 服务货分布 / trade 产业 / 跑商候选）。

用法：
  python3 w7state.py <dump.json> [<dump2.json> ...]

口径（AGENTS §九.4：每个数字标注时点/本期/累计）：
  · 时点读数 = 该 checkpoint 的即时状态（本脚本给的都是**时点**读数）；
  · 累计读数由日志侧统计（本脚本不读日志）。
"""
import json
import sys
import collections


def mod(env, ns):
    m = json.loads(env["modules"][ns])
    return m.get("data") if "data" in m else m.get("state", m)


def load(path):
    env = json.load(open(path))
    return env, mod(env, "economy"), mod(env, "actor"), mod(env, "social")


def report(path):
    env, ed, ad, sd = load(path)
    tick = env.get("timestamp", {}).get("tick")
    rev = env.get("ref", {}).get("revision")
    out = {"file": path.split("/")[-1], "tick": tick, "revision": rev}

    # ── CATTLE 份额（承运资产的唯一形态）────────────────────────────────────────
    shares = ed.get("assetShares") or {}
    cattle = {}
    for sid, sh in shares.items():
        if sh.get("asset") != "CATTLE":
            continue
        owner = sh.get("owner", {})
        cattle[sid] = {
            "qty": sh.get("quantity", 0),
            "owner": owner.get("id"),
            "operator": (sh.get("operator") or {}).get("id"),
            "industry": (sh.get("industry") or {}).get("value"),
        }
    out["cattle_total"] = sum(v["qty"] for v in cattle.values())
    out["cattle_shares"] = len(cattle)
    out["cattle_by_owner"] = dict(
        sorted(collections.Counter(
            {k: v["qty"] for k, v in cattle.items()}).items()))

    # ── 货币（actor 账户是唯一真源）＋ 发行记录（凭空造币的唯一合法口）────────────
    money = collections.Counter()
    for _aid, acc in (ad.get("accounts") or {}).items():
        for cid, amt in (acc.get("money") or {}).items():
            money[cid] += amt
    out["money_total_by_currency"] = dict(sorted(money.items()))
    iss = collections.Counter()
    iss_by_kind_currency = collections.defaultdict(collections.Counter)
    for _rid, rec in (ed.get("moneyIssuances") or {}).items():
        kind = rec.get("kind", "?")
        amount = rec.get("amount", 0)
        cur = (rec.get("currency") or {}).get("value", "?")
        iss[kind] += amount
        iss_by_kind_currency[kind][cur] += amount
    out["issuance_by_kind"] = dict(sorted(iss.items()))
    out["issuance_by_kind_currency"] = {
        k: dict(sorted(v.items())) for k, v in sorted(iss_by_kind_currency.items())}
    out["issuance_records"] = len(ed.get("moneyIssuances") or {})
    # ★ 守恒式（逐币种）：Σ 账户余额 vs Σ 发行记录（发行 = 货币的**唯一**合法来源）
    issued = collections.Counter()
    for _k, per_cur in iss_by_kind_currency.items():
        if _k.endswith("WITHDRAWAL") or _k.endswith("BURN"):
            for c, a in per_cur.items():
                issued[c] -= a
        else:
            for c, a in per_cur.items():
                issued[c] += a
    out["money_conservation_delta_by_currency"] = {
        c: money.get(c, 0) - issued.get(c, 0) for c in sorted(set(money) | set(issued))}

    # ── 运输服务货（haul）逐户 + trade 产业（跑商模板）逐格 ──────────────────────
    haul_by_hh = {}
    for hid, acc in (ad.get("accounts") or {}).items():
        bal = (acc.get("balances") or {}).get("haul", 0)
        if bal:
            haul_by_hh[hid] = bal
    out["haul_holders"] = len(haul_by_hh)
    out["haul_total_milli"] = sum(haul_by_hh.values())
    out["haul_by_household"] = dict(sorted(haul_by_hh.items()))
    trade = {}
    for iid, ind in (ed.get("industries") or {}).items():
        if str(iid).startswith("trade@"):
            trade[iid] = {"regime": (ind.get("regime") or {}).get("value"),
                          "cattlePerUnit": (ind.get("capacityPerUnit") or {}).get("CATTLE"),
                          "laborPerUnit": ind.get("laborPerUnit"),
                          "outputPerUnit": ind.get("outputPerUnit"),
                          "cycleInputPerUnit": ind.get("cycleInputPerUnit")}
    out["trade_industries"] = dict(sorted(trade.items()))

    # ── 选了跑商的家户：逐户（格 / 劳动 / 手上的 CATTLE / 服务货）────────────────
    rows = ed.get("classes") or {}
    positions = ed.get("classPositions") or {}
    standings = ed.get("classStandings") or {}
    merchant_hh = []
    for hid, st in standings.items():
        eff = []
        cur = st.get("currentPositionId")
        if isinstance(cur, dict):
            eff.append(cur.get("value"))
        for p in (st.get("participatingPositionIds") or []):
            eff.append(p.get("value") if isinstance(p, dict) else p)
        if not any(((positions.get(p) or {}).get("modeId") or {}).get("value") == "merchant"
                   for p in eff):
            continue
        row = rows.get(hid) or {}
        view = row.get("view")
        # view 的规范串 = "<q>_<r>|<residence>|<class>"（见 HouseholdEconomy JSON）
        hexkey = str(view).split("|")[0] if view else None
        cattle_qty = sum(v["qty"] for v in cattle.values() if v["owner"] == hid)
        merchant_hh.append({
            "hh": hid, "hex": hexkey,
            "population": row.get("population"),
            "laborMilli": row.get("laborMilli"),
            "participationPerMille": row.get("participationPerMille"),
            "cattle": cattle_qty,
            "haulMilli": haul_by_hh.get(hid, 0),
        })
    merchant_hh.sort(key=lambda r: (str(r["hex"]), str(r["hh"])))
    out["merchant_households"] = merchant_hh
    out["merchant_households_with_cattle"] = [r for r in merchant_hh if r["cattle"] > 0]
    out["merchant_households_with_haul"] = [r for r in merchant_hh if r["haulMilli"] > 0]

    # ── 人口（Social 是唯一权威）＋ 债务本金 ────────────────────────────────────
    pop = sum(sum((h.get("members") or {}).values())
              for h in (sd.get("households") or {}).values())
    out["population_social"] = pop
    out["households"] = len(sd.get("households") or {})
    debt = sum(c.get("principal", 0) for c in (ed.get("debtContracts") or {}).values())
    out["debt_principal_milli"] = debt
    return out


def main():
    rows = [report(p) for p in sys.argv[1:]]
    print(json.dumps(rows, ensure_ascii=False, indent=1, sort_keys=False))


if __name__ == "__main__":
    main()
