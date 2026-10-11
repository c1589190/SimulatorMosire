#!/usr/bin/env python3
"""W7e 测试批：checkpoint dump → 本批判据要的读数（作业资本 TOOL 守恒 / CATTLE 现状 / 货币守恒 / 服务货 / trade 产业 / 跑商家户）。

用法：
  python3 w7state.py <dump.json> [<dump2.json> ...]

口径（AGENTS §九.4：每个数字标注时点/本期/累计）：
  · 时点读数 = 该 checkpoint 的即时状态（本脚本给的都是**时点**读数）；
  · 累计/本期读数由 w7log.py（日志侧）统计。
★ 与 W7d 版 w7state.py 的差别（W7-fix 后的口径）：
  · 作业资本从 CATTLE 换成 **TOOL 份额**（`cattle_*` 保留为对照读数：它现在**不参与任何算式**）；
  · trade 产业逐格给 `capacityPerUnit` 的 TOOL 值（= 规模锚 120）与是否残留 CATTLE 键；
  · 货币守恒、债务本金（含"空户未结清"明细，W8 用）与人口照旧。
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

    # ── 资产份额：TOOL（作业资本）/ CATTLE（对照）──────────────────────────────
    shares = ed.get("assetShares") or {}
    per_asset = collections.defaultdict(dict)
    for sid, sh in shares.items():
        asset = sh.get("asset")
        if asset not in ("TOOL", "CATTLE"):
            continue
        owner = sh.get("owner", {})
        per_asset[asset][sid] = {
            "qty": sh.get("quantity", 0),
            "owner": owner.get("id"),
            "operator": (sh.get("operator") or {}).get("id"),
            "industry": (sh.get("industry") or {}).get("value"),
            "kind": sh.get("kind"),
        }
    for asset in ("TOOL", "CATTLE"):
        rows = per_asset.get(asset, {})
        out["%s_total_milli" % asset.lower()] = sum(v["qty"] for v in rows.values())
        out["%s_shares" % asset.lower()] = len(rows)
        out["%s_by_owner" % asset.lower()] = dict(
            sorted(collections.Counter({k: v["qty"] for k, v in rows.items()}).items()))
        out["%s_share_rows" % asset.lower()] = [
            {"id": k, **v} for k, v in sorted(rows.items())]

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
    for k, per_cur in iss_by_kind_currency.items():
        if k.endswith("WITHDRAWAL") or k.endswith("BURN"):
            for c, a in per_cur.items():
                issued[c] -= a
        else:
            for c, a in per_cur.items():
                issued[c] += a
    out["money_conservation_delta_by_currency"] = {
        c: money.get(c, 0) - issued.get(c, 0) for c in sorted(set(money) | set(issued))}

    # ── 商品账：运输服务货（haul）与工具（tool）逐户 ────────────────────────────
    goods_by_hh = {}
    for hid, acc in (ad.get("accounts") or {}).items():
        bal = acc.get("balances") or {}
        goods_by_hh[hid] = {"haul": bal.get("haul", 0), "tool": bal.get("tool", 0)}
    out["haul_holders"] = sum(1 for v in goods_by_hh.values() if v["haul"])
    out["haul_total_milli"] = sum(v["haul"] for v in goods_by_hh.values())
    out["tool_goods_holders"] = sum(1 for v in goods_by_hh.values() if v["tool"])
    out["tool_goods_total_milli"] = sum(v["tool"] for v in goods_by_hh.values())

    # ── trade 产业（跑商模板）逐格 ──────────────────────────────────────────────
    trade = {}
    for iid, ind in (ed.get("industries") or {}).items():
        if str(iid).startswith("trade@"):
            per_unit = ind.get("capacityPerUnit") or {}
            trade[iid] = {
                "regime": (ind.get("regime") or {}).get("value"),
                "capacityPerUnit": per_unit,
                "capacityPerUnitTool": per_unit.get("TOOL"),
                "capacityPerUnitCattle": per_unit.get("CATTLE"),
                "laborPerUnit": ind.get("laborPerUnit"),
                "outputPerUnit": ind.get("outputPerUnit"),
                "cycleInputPerUnit": ind.get("cycleInputPerUnit"),
            }
    out["trade_industries"] = dict(sorted(trade.items()))
    out["trade_hexes"] = sorted({k.split("@", 1)[1] for k in trade})

    # ── 选了跑商的家户：逐户（格 / 劳动 / 手上的作业资本 / 服务货 / 工具账）────────
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
        tool_share = sum(v["qty"] for v in per_asset.get("TOOL", {}).values() if v["owner"] == hid)
        cattle_share = sum(
            v["qty"] for v in per_asset.get("CATTLE", {}).values() if v["owner"] == hid)
        goods = goods_by_hh.get(hid, {})
        merchant_hh.append({
            "hh": hid, "hex": hexkey,
            "population": row.get("population"),
            "laborMilli": row.get("laborMilli"),
            "participationPerMille": row.get("participationPerMille"),
            "toolShareMilli": tool_share,
            "cattleShare": cattle_share,
            "toolGoodsMilli": goods.get("tool", 0),
            "haulMilli": goods.get("haul", 0),
        })
    merchant_hh.sort(key=lambda r: (str(r["hex"]), str(r["hh"])))
    out["merchant_households"] = merchant_hh
    out["merchant_households_with_tool_share"] = [
        r for r in merchant_hh if r["toolShareMilli"] > 0]
    out["merchant_households_with_haul"] = [r for r in merchant_hh if r["haulMilli"] > 0]
    out["merchant_households_by_hex"] = dict(sorted(collections.Counter(
        str(r["hex"]) for r in merchant_hh).items()))

    # ── 人口（Social 是唯一权威）＋ 债务本金（含"空户未结清"明细，W8 用）──────────
    pop_by_hh = {}
    for hid, h in (sd.get("households") or {}).items():
        pop_by_hh[hid] = sum((h.get("members") or {}).values())
    # ★ 人口事件表在 SocialData 顶层（`populationEvents`），不在逐户节点里（2026-10-11 实测）
    events_by_hh = collections.Counter()
    for _eid, ev in (sd.get("populationEvents") or {}).items():
        owner = ev.get("householdId")
        if isinstance(owner, dict):
            owner = owner.get("value")
        events_by_hh[owner] += 1
    out["population_events"] = sum(events_by_hh.values())
    out["population_social"] = sum(pop_by_hh.values())
    out["households"] = len(pop_by_hh)
    contracts = ed.get("debtContracts") or {}
    out["debt_contracts"] = len(contracts)
    out["debt_principal_milli"] = sum(c.get("principal", 0) for c in contracts.values())
    empty_open = []
    for cid, c in sorted(contracts.items()):
        debtor = c.get("debtor")  # ★ 实测：debtor 是**裸字符串**（不是 {id:...} 对象）
        if isinstance(debtor, dict):
            debtor = debtor.get("id")
        principal = c.get("principal", 0)
        if principal <= 0:
            continue
        if pop_by_hh.get(debtor, 0) == 0:
            empty_open.append({
                "id": cid, "debtor": debtor, "principal": principal,
                "status": c.get("status"), "openedDay": c.get("openedDay"),
                "lastInterestDay": c.get("lastInterestDay"),
                "creditor": c.get("creditor"),
                "populationEvents": events_by_hh.get(debtor, 0)})
    out["empty_debtor_open_contracts"] = len(empty_open)
    out["empty_debtor_open_principal_milli"] = sum(r["principal"] for r in empty_open)
    out["empty_debtor_samples"] = empty_open[:10]
    return out


def main():
    rows = [report(p) for p in sys.argv[1:]]
    print(json.dumps(rows, ensure_ascii=False, indent=1, sort_keys=False))


if __name__ == "__main__":
    main()
