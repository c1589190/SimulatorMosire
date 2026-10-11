#!/usr/bin/env python3
"""财富聚集调查 —— 逐户读数库（只读 checkpoint 信封 → 逐户一行）。

用到的读口（全部来自落盘 checkpoint 全量信封，即 store/checkpoints/main/<rev>.json）：
  · modules.economy.data.classes[<hh>]        → population / naturalNeeds / cycleNaturalNeedMilli
  · modules.economy.data.flows[<hh>]          → 本期（本周期，周期=30 day，关账即归零）发生额
  · modules.economy.data.class_positions      → 阶层位置（逐户）
  · modules.economy.data.debtContracts        → 逐笔债务（debtor/creditor/unit/principal/status）
  · modules.economy.data.assetShares          → 资产份额（owner/operator/asset/quantity）
  · modules.economy.data.governments          → 国库户 + 发行
  · modules.economy.data.marketTables         → 价表（numeraire/hexes/prices/loanRate）
  · modules.actor.data.accounts[<hh>]         → 逐户货物 balances + 逐户货币 money（★ 钱与货的权威落点）
  · modules.social.data.households[<hh>]      → members（逐户人口）
  · modules.social.data.satietyPerMille       → 逐户饱食度（只含有需求的家户）
  · modules.social.data.populationEvents      → 逐户生死/迁移事件簿（历史累计，带 day/reason）

★ 口径：`flows` 是**本期发生额**（周期边界归零，本世界周期=30 day）；`populationEvents` 是
**历史累计**（创世起）；`classes/accounts/assetShares/debtContracts` 是**时点快照**。
"""
import json
from collections import defaultdict

GRID_DAYS = (0, 120, 240, 360, 480, 600)


def mod(env, ns):
    m = json.loads(env["modules"][ns])
    return m.get("data") if "data" in m else m.get("state", m)


def envelope(path):
    env = json.load(open(path))
    return env


def extract_hh(path):
    """→ (dayless) 逐户读数 dict。调用方自己补 day/revision。"""
    env = json.load(open(path))
    ed = mod(env, "economy")
    ad = mod(env, "actor")
    sd = mod(env, "social")

    accounts = ad.get("accounts") or {}
    classes = ed.get("classes") or {}
    flows = ed.get("flows") or {}
    positions = ed.get("class_positions") or {}
    soc_hh = sd.get("households") or {}
    sat = sd.get("satietyPerMille") or {}

    # ── 资产份额：逐户 owned / operated（按资产种类求和）────────────────
    owned = defaultdict(lambda: defaultdict(int))
    operated = defaultdict(lambda: defaultdict(int))
    for sh in (ed.get("assetShares") or {}).values():
        q = sh.get("quantity", 0)
        kind = sh.get("asset")
        o = (sh.get("owner") or {}).get("id")
        op = (sh.get("operator") or {}).get("id")
        if o:
            owned[o][kind] += q
        if op:
            operated[op][kind] += q

    # ── 债务：逐户应收（creditor）/ 应付（debtor）────────────────────────
    owe_open = defaultdict(lambda: defaultdict(int))     # debtor 侧，NORMAL
    owe_def = defaultdict(lambda: defaultdict(int))      # debtor 侧，DEFAULTED
    lent_open = defaultdict(lambda: defaultdict(int))    # creditor 侧，NORMAL
    lent_def = defaultdict(lambda: defaultdict(int))     # creditor 侧，DEFAULTED
    debt_n = defaultdict(int)
    for c in (ed.get("debtContracts") or {}).values():
        u = c.get("unit") or {}
        med = ("money:" + u["currency"]["value"]) if u.get("@class") == "money" \
            else ("commodity:" + (u.get("commodity") or {}).get("value", "?"))
        p = c.get("principal", 0)
        st = c.get("status")
        d, cr = c.get("debtor"), c.get("creditor")
        debt_n[d] += 1
        if st == "DEFAULTED":
            owe_def[d][med] += p
            lent_def[cr][med] += p
        elif st == "SETTLED":
            pass
        else:
            owe_open[d][med] += p
            lent_open[cr][med] += p

    keys = set(accounts) | set(classes) | set(soc_hh) | set(positions) | set(flows)
    hh = {}
    for k in sorted(keys):
        a = accounts.get(k) or {}
        c = classes.get(k) or {}
        f = flows.get(k) or {}
        s = soc_hh.get(k) or {}
        pop_soc = sum((s.get("members") or {}).values())
        rec = {
            "pop": c.get("population", 0),
            "pop_soc": pop_soc,
            "sat": sat.get(k),
            "pos": positions.get(k),
            "money": {kk: vv for kk, vv in (a.get("money") or {}).items() if vv},
            "bal": {kk: vv for kk, vv in (a.get("balances") or {}).items() if vv},
            "frz_bal": {kk: vv for kk, vv in (a.get("frozenBalances") or {}).items() if vv},
            "frz_money": {kk: vv for kk, vv in (a.get("frozenMoney") or {}).items() if vv},
            "own": dict(owned.get(k) or {}),
            "ope": dict(operated.get(k) or {}),
            "owe_o": dict(owe_open.get(k) or {}),
            "owe_d": dict(owe_def.get(k) or {}),
            "lent_o": dict(lent_open.get(k) or {}),
            "lent_d": dict(lent_def.get(k) or {}),
            "debt_n": debt_n.get(k, 0),
            "need_cycle": c.get("cycleNaturalNeedMilli", 0),
            "flow": {
                "tax": f.get("taxPaid", 0),
                "int": f.get("interestDue", 0),
                "bor": f.get("newBorrowing", 0),
                "rep": f.get("repaid", 0),
                "surplus": f.get("netSurplus", 0),
                "inc": dict(f.get("income") or {}),
                "con": dict(f.get("consumed") or {}),
                "unmet": dict(f.get("unmetNeed") or {}),
                "deaths": f.get("deaths", 0),
                "births": f.get("births", 0),
                "cap": dict(f.get("capitalizedArrears") or {}),
                "rep_money": dict(f.get("repaidMoney") or {}),
            },
        }
        hh[k] = rec
    return hh


def gov_totals(env_or_path):
    """国库 / 政府口的钱货合计（时点快照）。"""
    env = envelope(env_or_path) if isinstance(env_or_path, str) else env_or_path
    ad = mod(env, "actor")
    ed = mod(env, "economy")
    govs = ed.get("governments") or {}
    acc = ad.get("accounts") or {}
    out = {"treasuries": {}, "money": defaultdict(int), "goods": defaultdict(int)}
    ids = set()
    for gid, g in govs.items():
        th = (g.get("treasury") or {}).get("id")
        if th:
            ids.add(th)
            out["treasuries"][gid] = th
    for k, a in acc.items():
        if k.startswith("hh-gov-") or k.startswith("hh-unit:"):
            ids.add(k)
    for k in sorted(ids):
        a = acc.get(k) or {}
        for cur, v in (a.get("money") or {}).items():
            out["money"][cur] += v
        for com, v in (a.get("balances") or {}).items():
            out["goods"][com] += v
    out["money"] = dict(out["money"])
    out["goods"] = dict(out["goods"])
    out["ids"] = sorted(ids)
    return out


def debt_summary(env_or_path):
    env = envelope(env_or_path) if isinstance(env_or_path, str) else env_or_path
    ed = mod(env, "economy")
    by_status = defaultdict(int)
    by_med_open = defaultdict(int)
    by_med_def = defaultdict(int)
    by_med_settled = defaultdict(int)
    n_by_status = defaultdict(int)
    for c in (ed.get("debtContracts") or {}).values():
        u = c.get("unit") or {}
        med = ("money:" + u["currency"]["value"]) if u.get("@class") == "money" \
            else ("commodity:" + (u.get("commodity") or {}).get("value", "?"))
        p = c.get("principal", 0)
        st = c.get("status")
        by_status[st] += p
        n_by_status[st] += 1
        {"NORMAL": by_med_open, "DEFAULTED": by_med_def,
         "SETTLED": by_med_settled}.get(st, by_med_open)[med] += p
    return {"n": dict(n_by_status), "sum": dict(by_status),
            "open": dict(by_med_open), "defaulted": dict(by_med_def),
            "settled": dict(by_med_settled),
            "pledges": len(ed.get("pledges") or {})}


if __name__ == "__main__":
    import sys
    for p in sys.argv[1:]:
        env = envelope(p)
        print("==", p, "rev", env["ref"]["revision"], "tick", env["timestamp"]["tick"])
        hh = extract_hh(p)
        print("   households", len(hh))
        print("   gov", json.dumps(gov_totals(env), ensure_ascii=False)[:400])
        print("   debt", json.dumps(debt_summary(env), ensure_ascii=False)[:600])
