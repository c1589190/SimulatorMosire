#!/usr/bin/env python3
"""户绝⇒债务是否消除 调查 —— 读数库（只读 checkpoint 全量信封 → 逐户 / 逐笔债务行）。

读口（唯一来源 = 落盘 checkpoint 全量信封 `store/checkpoints/main/<revision>.json`）：
  · modules.economy.data.classes[<hh>]         → population（经济侧人口 = 本调查判"户绝"的口径）
  · modules.economy.data.debtContracts         → 逐笔债务（debtor/creditor/unit/principal/openedDay/
                                                  lastInterestDay/status/terms.rate）
  · modules.economy.data.assetShares           → 资产份额（owner/asset/quantity）
  · modules.economy.data.class_positions       → 阶层位置（逐户）
  · modules.actor.data.accounts[<hh>]          → 逐户 money / balances（钱与货的权威落点）
  · modules.social.data.households[<hh>]       → members（社会侧人口，用于两侧对拍）

★ 统计窗口（AGENTS §九.4）：
  · population / classes / accounts / assetShares / debtContracts = **时点快照**（该日关账后的状态）
  · flows = **本期发生额**（周期边界归零，本世界周期 = 30 day）
  · social.populationEvents = **历史累计**（创世起）
  本库只产出**时点**读数；"逐周期变化"由调用方对同一 id 跨时点做差，不做跨窗口并排比较。

用法（库）：
    from ddlib import load, hh_rows, debt_rows
    env = load("/path/cp.json")
    for r in hh_rows(env, day=120): ...
用法（命令行自证）：
    python3 ddlib.py <cp.json> [<cp.json> ...]
"""
import json
import sys
from collections import defaultdict


def mod(env, ns):
    """模块信封 → 模块 state/data（与 dd_w6lib.mod 逐字同源）。"""
    m = json.loads(env["modules"][ns])
    return m.get("data") if "data" in m else m.get("state", m)


def load(path):
    with open(path) as f:
        return json.load(f)


def unit_str(u):
    """合同标的 → 'money:<cur>' / 'commodity:<id>'（币与货**不可相加**，逐标的分别统计）。"""
    u = u or {}
    if u.get("@class") == "money":
        cur = (u.get("currency") or {}).get("value", "?")
        return "money:" + cur
    com = u.get("commodity") or {}
    if isinstance(com, dict):
        return "commodity:" + com.get("value", "?")
    return "commodity:" + str(com)


def _view_str(v):
    return v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, sort_keys=True)


def stratum_of(hh, view):
    """阶层档 = view 的第三段（'<hex>|rural|poor_peasant'）；取不到则退回 id 里最后一段。"""
    if isinstance(view, str) and view.count("|") >= 2:
        return view.split("|")[2]
    parts = hh.split("-")
    return parts[-1] if parts else "?"


def debt_rows(env, day):
    """逐笔债务行（**只出 principal > 0 的活跃合同**；本金归零即从本表消失 = 已销/已清）。

    字段：d(day) id debtor creditor unit principal opened last rate status due
    """
    ed = mod(env, "economy")
    out = []
    for cid, c in (ed.get("debtContracts") or {}).items():
        p = c.get("principal", 0)
        if p <= 0:
            continue
        terms = c.get("terms") or {}
        out.append({
            "d": day,
            "id": cid,
            "debtor": c.get("debtor"),
            "creditor": c.get("creditor"),
            "unit": unit_str(c.get("unit")),
            "p": p,
            "opened": c.get("openedDay"),
            "last": c.get("lastInterestDay"),
            "rate": terms.get("interestRatePerMillePerCycle"),
            "status": c.get("status"),
            "due": c.get("dueCycle"),
        })
    out.sort(key=lambda r: r["id"])
    return out


def debt_zeroed_meta(env):
    """**本金已归零但仍留在合同表里的合同** → {id: status}（判"销了没"要用它）。

    ★ 为什么需要：本金 0 的合同仍在 `debtContracts` 里（状态 SETTLED/FORGIVEN），
      `debt_rows` 按 principal>0 过滤 ⇒ 它消失既可能是"被销"也可能是"根本没建过"；
      本函数把"表里确实存在、本金为 0"这一事实单独交出来。
    """
    ed = mod(env, "economy")
    return {cid: c.get("status") for cid, c in (ed.get("debtContracts") or {}).items()
            if c.get("principal", 0) <= 0}


def hh_rows(env, day):
    """逐户时点读数（口径与 2026-10-11-wealth-concentration 的 wrlib.extract_hh 同源，便于交叉比对）。

    字段：d hh pop(经济侧) soc(社会侧) view stratum money bal own pos owe lent
      · owe / lent = 按**标的**与**状态**分别累计的应付/应收本金（DEFAULTED 与其余分开）
      · own = 持有资产份额（按 asset 种类求和）
    """
    ed = mod(env, "economy")
    ad = mod(env, "actor")
    sd = mod(env, "social")
    accounts = ad.get("accounts") or {}
    classes = ed.get("classes") or {}
    positions = ed.get("class_positions") or {}
    soc_hh = sd.get("households") or {}

    owned = defaultdict(lambda: defaultdict(int))
    for sh in (ed.get("assetShares") or {}).values():
        o = (sh.get("owner") or {}).get("id")
        if o:
            owned[o][sh.get("asset")] += sh.get("quantity", 0)

    owe_o = defaultdict(lambda: defaultdict(int))
    owe_d = defaultdict(lambda: defaultdict(int))
    lent_o = defaultdict(lambda: defaultdict(int))
    lent_d = defaultdict(lambda: defaultdict(int))
    for c in (ed.get("debtContracts") or {}).values():
        p = c.get("principal", 0)
        if p <= 0:
            continue
        med = unit_str(c.get("unit"))
        st = c.get("status")
        d, cr = c.get("debtor"), c.get("creditor")
        if st == "DEFAULTED":
            owe_d[d][med] += p
            lent_d[cr][med] += p
        else:
            owe_o[d][med] += p
            lent_o[cr][med] += p

    keys = set(accounts) | set(classes) | set(soc_hh) | set(positions)
    rows = []
    for k in sorted(keys):
        a = accounts.get(k) or {}
        c = classes.get(k) or {}
        s = soc_hh.get(k) or {}
        view = c.get("view")
        rows.append({
            "d": day,
            "hh": k,
            "pop": c.get("population", 0),
            "soc": sum((s.get("members") or {}).values()),
            "view": _view_str(view) if view is not None else None,
            "stratum": stratum_of(k, view),
            "money": {kk: vv for kk, vv in (a.get("money") or {}).items() if vv},
            "bal": {kk: vv for kk, vv in (a.get("balances") or {}).items() if vv},
            "own": dict(owned.get(k) or {}),
            "pos": (positions.get(k) or {}).get("currentPositionId", {}).get("value")
                   if isinstance(positions.get(k), dict) else positions.get(k),
            "owe_o": dict(owe_o.get(k) or {}),
            "owe_d": dict(owe_d.get(k) or {}),
            "lent_o": dict(lent_o.get(k) or {}),
            "lent_d": dict(lent_d.get(k) or {}),
        })
    return rows


if __name__ == "__main__":
    import os
    for p in sys.argv[1:]:
        env = load(p)
        ref = env.get("ref", {})
        tick = (env.get("timestamp") or {}).get("tick")
        print("==", os.path.basename(p), "rev", ref.get("revision"), "tick", tick)
        dr = debt_rows(env, tick)
        print("   debt contracts(principal>0):", len(dr),
              "| zeroed-but-present:", len(debt_zeroed_meta(env)))
        hr = hh_rows(env, tick)
        print("   households:", len(hr),
              "| pop0 rows:", sum(1 for r in hr if r["pop"] == 0),
              "| pop0 with owe:", sum(1 for r in hr if r["pop"] == 0 and (r["owe_o"] or r["owe_d"])))
        bymed = defaultdict(int)
        for r in dr:
            bymed[r["unit"]] += r["p"]
        print("   outstanding by unit:", dict(bymed))
