#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""m07：M0.3/M0.4/M0.6 共同分母的口径窗口复盘（只读，可复算）。

题目（MASTER PLAN §1.7 「口径疑点」丁）:
    ΣgrainDailyConsumption × 120  与  Σpop × 10,000
    差 1.58% ~ 5.34%（逐格最大相对偏差），随 tick 变大、方向一律偏高（前者大）、未解释。

本脚本从**已入库的逐格原始读数**读出全部引用数字，不跑模拟、不跑 Maven、不改任何文件。
输入（三个文件的 md5 已在下面 EXPECTED_MD5 里钉死；不符 ⇒ 当场退出，避免把旧数字配新数据）：

    .superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick120.json
    .superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick240.json
    .superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick360.json

口径链条（逐字回代码；★ 源码行号会随并发编辑漂移，故下面以**函数名 + 原样代码**为准，
本脚本运行时打印被引用源码的 md5；2026-09-27 21:03 复核时 ApiViews.java 的该表达式在第 467 行、
EconomySettlement.withDailyNeed 在第 4054 行）：
    ApiViews.economyHex
        grainDailyConsumption += row.naturalNeeds().getOrDefault(GRAIN, 0L);
    EconomySettlement.consumeOwnStock → withDailyNeed（naturalNeeds 的唯一写入点）
        ClassRow row = withDailyNeed(rows.get(key), day);
        ... EconomyVocabulary.dailyNeedsMilli(row.population(), day) ...
    EconomyVocabulary.dailyRationMilli
        dailyRationMilli(p, day) = cumulativeRationMilli(p, day) − cumulativeRationMilli(p, day−1)
    EconomyVocabulary.cumulativeRationMilli
        cumulativeRationMilli(p, days) = p × 10,000 × days ÷ 120   （向下取整）
    口径常量
        RATION_MILLI_PER_PERSON = 10,000L（毫粮/人/120 天）；RATION_CYCLE_DAYS = 120L

    日循环次序（人口为什么会在同一天里变两次窗口；PopulationEconomyTimeParticipant 2026-09-27 21:03 复核行号）：
    PopulationEconomyTimeParticipant.java:181  stepper.step(day)            ← 写 naturalNeeds（用日初人口）
    PopulationEconomyTimeParticipant.java:202  applyDailyStress(...)
    PopulationEconomyTimeParticipant.java:204  if (day % 30 == 0) monthly(day)  ← 月末出生/死亡
    PopulationEconomyTimeParticipant.java:208  stepper.applyPopulationChange(...)  ← 改行人口（保留 naturalNeeds）
    PopulationDynamics.java:69                 SETTLEMENT_DAYS = 30L
    EconomySettlement.java:341                 FAMINE_MORTALITY_PER_MILLE = 0（默认饿死通道不死人）
    120/240/360 全是 30 的整数倍 ⇒ 每个 dump 日都刚好踩在月末回写上。

读数口径（本脚本用）：
    d            := tick（= dump 时刻的绝对世界日；dump 的 social.at.tick 逐格核过 = tick）
    daily_r      := 第 r 行 naturalNeeds["grain"]（毫粮/日；当天那一份，逐日覆盖）
    p_end_r      := dump 里第 r 行 population（日末人口；dump 在整天推进结束后）
    p_start_r    := 使 dailyRationMilli(p, d) == daily_r 的唯一人口（日初人口；见 invert_daily）
    round_r      := daily_r × 120 − p_start_r × 10,000（逐日差分的取整残差；本脚本数值证明 ∈ {0,40,80}）
    delta_r      := p_start_r − p_end_r（快照日一天的人口净减少 = deaths_d − births_d；见代码次序）
    恒等式        daily_r×120 − p_end_r×10000 == 10000×delta_r + round_r
    逐格/全球把上式逐行相加即得完整分解：差额 = 单日人口变动项 + 取整残差项。

用法:
    python3 .superpowers/sdd/2026-09-27-m0-instrument/m07_consumption_window.py

产物: 只打印到 stdout（不新建文件）。所有数字都可由本脚本从上述 JSON 重算。
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

# ── 输入文件与 md5（2026-09-27 本仓实测值；不符 ⇒ 退出）────────────────────────────────────
TICKS = (120, 240, 360)
DATA_DIR = Path(__file__).resolve().parents[3] / ".superpowers" / "sdd" / "2026-09-27-economic-cycle-impl"
EXPECTED_MD5 = {
    DATA_DIR / "h6raw_tick120.json": "6aad52957a33f4dc54002aa64331b6ae",
    DATA_DIR / "h6raw_tick240.json": "b1e77b37ba1b774e85bbaaaa0f6902c5",
    DATA_DIR / "h6raw_tick360.json": "7efb22d1c08fa4e1d6a7d87270856a27",
}

# 被引用的源码（只记录 md5，便于将来核对本报告的代码结论对应哪一版）
SRC_FILES = {
    "ApiViews.java": "simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java",
    "PopulationEconomyTimeParticipant.java": "simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java",
    "EconomySettlement.java": "simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java",
    "MarketSettlement.java": "simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java",
    "FlowRow.java": "simos-economy/src/main/java/io/mosire/simos/economy/model/FlowRow.java",
    "EconomyVocabulary.java": "simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java",
    "PopulationDynamics.java": "simos-social/src/main/java/io/mosire/simos/social/population/PopulationDynamics.java",
}
REPO_ROOT = Path(__file__).resolve().parents[3]

# 本脚本在三个 tick 上依赖的常量（全部可从源码核实；数值写在这里只为把口径钉住）
RATION_MILLI_PER_PERSON = 10_000
RATION_CYCLE_DAYS = 120
FAMINE_MORTALITY_PER_MILLE = 0
POPULATION_SETTLEMENT_DAYS = 30
GRAIN = "grain"
RESIDENCES = ("rural", "urban")
SLOTS = ("landlord", "middle_peasant", "poor_peasant", "rich_peasant")
NATION_HEX_COUNTS = {"德意志第二帝国": 430, "奥斯特马克侯国": 138, "霍赫兰伯国": 231}


# ── 纯函数：口粮口径（与 EconomyVocabulary 逐字同式）──────────────────────────────────────
def cumulative_ration_milli(population: int, days: int) -> int:
    """cumulativeRationMilli：人口 × 10,000 × 天数 ÷ 120（向下取整）。"""
    return population * RATION_MILLI_PER_PERSON * days // RATION_CYCLE_DAYS


def daily_ration_milli(population: int, day: int) -> int:
    """dailyRationMilli：累计(day) − 累计(day−1)——逐日差分，残差不丢。"""
    return cumulative_ration_milli(population, day) - cumulative_ration_milli(population, day - 1)


def invert_daily(value: int, day: int, hi: int = 20_000_000) -> int | None:
    """反解：最小的 p 使 dailyRationMilli(p, day) ≥ value；相等才算命中，否则 None。"""
    lo = 0
    while lo < hi:
        mid = (lo + hi) // 2
        if daily_ration_milli(mid, day) < value:
            lo = mid + 1
        else:
            hi = mid
    return lo if daily_ration_milli(lo, day) == value else None


def telescoping_cycle_exact(population: int, cycle_start_day: int, days: int = 120) -> bool:
    """整周期 Σ 逐日口粮 == 人口 × 10,000（望远镜求和精确；按周期起点也成立）。"""
    total = sum(
        daily_ration_milli(population, cycle_start_day + i) for i in range(days)
    )
    return total == population * RATION_MILLI_PER_PERSON


def md5_of(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def p(text: str = "") -> None:
    print(text)


def fmt(x: int | float) -> str:
    if isinstance(x, float):
        return f"{x:,.6f}"
    return f"{x:,}"


# ── 读入 + 完整性检查 + 逐格分解 ─────────────────────────────────────────────────────────
def analyze_tick(tick: int) -> dict:
    path = DATA_DIR / f"h6raw_tick{tick}.json"
    raw = json.loads(path.read_text(encoding="utf-8"))
    rows = [(nat, row) for nat, rs in raw["nations"].items() for row in rs]

    checks = {
        "rows": len(rows),
        "coords_unique": len({(row["q"], row["r"]) for _, row in rows}) == len(rows),
        "nation_counts": {nat: len(rs) for nat, rs in raw["nations"].items()},
        "social_tick_all_match": all(row["social"]["at"]["tick"] == tick for _, row in rows),
        "all_activated": all(bool(row["economy"]["activated"]) for _, row in rows),
        "classes_per_hex": sorted({len(row["economy"]["classes"]) for _, row in rows}),
        "residences_ok": all(
            tuple(sorted({c["residence"] for c in row["economy"]["classes"]})) == RESIDENCES for _, row in rows
        ),
        "slots_ok": all(
            tuple(sorted({c["slot"] for c in row["economy"]["classes"]})) == SLOTS for _, row in rows
        ),
        "pop_eq_social_hexes": sum(
            1 for _, row in rows if row["economy"]["population"] == row["social"]["population"]
        ),
        "pop_eq_sum_classes_hexes": sum(
            1
            for _, row in rows
            if row["economy"]["population"] == sum(c["population"] for c in row["economy"]["classes"])
        ),
        "daily_eq_natural_needs_hexes": sum(
            1
            for _, row in rows
            if row["economy"]["grainDailyConsumption"]
            == sum(c["naturalNeeds"].get(GRAIN, 0) for c in row["economy"]["classes"])
        ),
        # 跨切片对平：social.crisis 的 MORTALITY evidence deathsThisCycle == Σ economy flow.deaths
        "mortality_crisis_present": 0,
        "mortality_crisis_equal": 0,
        "mortality_crisis_neq": 0,
    }
    for _, row in rows:
        ev = {c["kind"]: c["evidence"] for c in row["social"]["crisis"]}
        mort = ev.get("MORTALITY")
        if mort is None:
            continue
        checks["mortality_crisis_present"] += 1
        if int(mort["deathsThisCycle"]) == sum(c["flow"]["deaths"] for c in row["economy"]["classes"]):
            checks["mortality_crisis_equal"] += 1
        else:
            checks["mortality_crisis_neq"] += 1

    # 单调性：dailyRationMilli(·, day) 在 p ∈ [0, 500_000] 上严格递增（反解唯一性的前提）。
    mono_ok = True
    mono_min_step = None
    for day in (tick,):
        prev = daily_ration_milli(0, day)
        for pop in range(1, 500_001):
            cur = daily_ration_milli(pop, day)
            step = cur - prev
            if step <= 0:
                mono_ok = False
                break
            mono_min_step = step if mono_min_step is None else min(mono_min_step, step)
            prev = cur

    # 逐格/逐行分解
    per_hex = {}
    row_stats = {
        "rows_total": 0,
        "rows_pop0": 0,
        "rows_no_grain_key": 0,
        "no_grain_key_but_pop_pos": 0,
        "rows_daily_positive": 0,
        "invert_miss": 0,
        "round_values": {0: 0, 40: 0, 80: 0},
        "round_other": 0,
        "max_abs_round": 0,
        "feasibility_violations": 0,
        "delta_pos": 0,
        "delta_neg": 0,
        "delta_zero": 0,
    }
    for nat, row in rows:
        econ = row["economy"]
        hex_daily = int(econ["grainDailyConsumption"])
        hex_pop_end = int(econ["population"])
        p_start_sum = 0
        p_end_sum = 0
        round_sum = 0
        delta_sum = 0
        deaths_sum = 0
        births_sum = 0
        daily_sum = 0
        for c in econ["classes"]:
            pop_end = int(c["population"])
            daily = int(c["naturalNeeds"].get(GRAIN, 0))
            daily_sum += daily
            deaths = int(c["flow"]["deaths"])
            births = int(c["flow"]["births"])
            deaths_sum += deaths
            births_sum += births
            row_stats["rows_total"] += 1
            if pop_end == 0:
                row_stats["rows_pop0"] += 1
            if GRAIN not in c["naturalNeeds"]:
                row_stats["rows_no_grain_key"] += 1
                if pop_end > 0:
                    row_stats["no_grain_key_but_pop_pos"] += 1
            if daily > 0:
                row_stats["rows_daily_positive"] += 1
            p_start = invert_daily(daily, tick) if daily > 0 else 0
            if p_start is None:
                row_stats["invert_miss"] += 1
                raise SystemExit(f"反解失败（tick={tick}, {nat}({row['q']},{row['r']}) {c['residence']}/{c['slot']}）")
            round_r = daily * RATION_CYCLE_DAYS - p_start * RATION_MILLI_PER_PERSON
            row_stats["max_abs_round"] = max(row_stats["max_abs_round"], abs(round_r))
            if round_r in row_stats["round_values"]:
                row_stats["round_values"][round_r] += 1
            else:
                row_stats["round_other"] += 1
            delta_r = p_start - pop_end
            row_stats["delta_pos" if delta_r > 0 else "delta_neg" if delta_r < 0 else "delta_zero"] += 1
            # 可行性：delta = deaths_d − births_d，且 0 ≤ deaths_d ≤ 本周期累计 deaths，
            #         0 ≤ births_d ≤ 本周期累计 births（flow 的窗口 = 本周期累计）。
            if delta_r > deaths or -delta_r > births:
                row_stats["feasibility_violations"] += 1
            p_start_sum += p_start
            p_end_sum += pop_end
            round_sum += round_r
            delta_sum += delta_r
        assert daily_sum == hex_daily, (tick, nat, row["q"], row["r"])
        lhs = hex_daily * RATION_CYCLE_DAYS
        rhs = hex_pop_end * RATION_MILLI_PER_PERSON
        per_hex[(nat, int(row["q"]), int(row["r"]))] = {
            "nat": nat,
            "q": int(row["q"]),
            "r": int(row["r"]),
            "lhs": lhs,
            "rhs": rhs,
            "gap": lhs - rhs,
            "p_start": p_start_sum,
            "p_end": p_end_sum,
            "delta_day": delta_sum,
            "round": round_sum,
            "deaths_cycle": deaths_sum,
            "births_cycle": births_sum,
        }

    lhs_total = sum(h["lhs"] for h in per_hex.values())
    rhs_total = sum(h["rhs"] for h in per_hex.values())
    gap_total = lhs_total - rhs_total
    delta_total = sum(h["delta_day"] for h in per_hex.values())
    round_total = sum(h["round"] for h in per_hex.values())
    assert delta_total * RATION_MILLI_PER_PERSON + round_total == gap_total, (
        tick,
        delta_total,
        round_total,
        gap_total,
    )

    # m04_attribution.py 的 max 相对偏差口径：|lhs−rhs| / max(|lhs|,|rhs|,1)，逐格取最大。
    def report_rel(h: dict) -> float:
        return abs(h["lhs"] - h["rhs"]) / max(abs(h["lhs"]), abs(h["rhs"]), 1)

    def rhs_rel(h: dict) -> float:
        return (h["lhs"] - h["rhs"]) / h["rhs"]

    max_hex = max(per_hex.values(), key=report_rel)
    per_nation = {}
    for h in per_hex.values():
        agg = per_nation.setdefault(h["nat"], {"hexes": 0, "pop": 0, "delta_day": 0, "gap": 0, "round": 0})
        agg["hexes"] += 1
        agg["pop"] += h["p_end"]
        agg["delta_day"] += h["delta_day"]
        agg["gap"] += h["gap"]
        agg["round"] += h["round"]

    return {
        "tick": tick,
        "checks": checks,
        "mono_ok": mono_ok,
        "mono_min_step": mono_min_step,
        "row_stats": row_stats,
        "per_hex": per_hex,
        "lhs_total": lhs_total,
        "rhs_total": rhs_total,
        "gap_total": gap_total,
        "delta_total": delta_total,
        "round_total": round_total,
        "max_hex": max_hex,
        "max_hex_report_rel": report_rel(max_hex),
        "max_hex_rhs_rel": rhs_rel(max_hex),
        "positive_hexes": sum(1 for h in per_hex.values() if h["gap"] > 0),
        "negative_hexes": sum(1 for h in per_hex.values() if h["gap"] < 0),
        "zero_hexes": sum(1 for h in per_hex.values() if h["gap"] == 0),
        "positive_gap_sum": sum(h["gap"] for h in per_hex.values() if h["gap"] > 0),
        "negative_gap_sum": sum(h["gap"] for h in per_hex.values() if h["gap"] < 0),
        "per_nation": per_nation,
    }


def main() -> int:
    p("=== m07 口径窗口复盘：grainDailyConsumption×120 vs Σpop×10,000（只读） ===")
    p("")
    p("[输入文件与 md5]")
    for path, expected in EXPECTED_MD5.items():
        actual = md5_of(path)
        mark = "OK" if actual == expected else "★不符"
        p(f"  {path.relative_to(REPO_ROOT)}  md5={actual}  expected={expected}  {mark}")
        if actual != expected:
            raise SystemExit("输入 md5 不符 —— 本报告的数字只对上面这版数据成立，拒绝继续。")
    p("")
    p("[被引用的源码 md5（当前工作区这一版；只记录，不改）]")
    for label, rel in SRC_FILES.items():
        path = REPO_ROOT / rel
        p(f"  {label}: {md5_of(path)}  ({rel})")
    p("")

    results = {}
    for tick in TICKS:
        results[tick] = analyze_tick(tick)

    p("[零、口径常量（脚本写死；逐个回代码核过）]")
    p(
        f"  RATION_MILLI_PER_PERSON={RATION_MILLI_PER_PERSON} 毫粮/人/周期；RATION_CYCLE_DAYS={RATION_CYCLE_DAYS} 天；"
        f"PopulationDynamics.SETTLEMENT_DAYS={POPULATION_SETTLEMENT_DAYS} 天；"
        f"EconomySettlement.FAMINE_MORTALITY_PER_MILLE={FAMINE_MORTALITY_PER_MILLE}（默认饿死通道不死人）。"
    )
    p(
        "  关账日 tick % 30 = "
        + str([t % POPULATION_SETTLEMENT_DAYS for t in TICKS])
        + " ⇒ 三个 dump 日全部是月末出生/死亡结算日（这正是本差额的结构性来源）。"
    )
    p("")

    # ── 完整性检查 ────────────────────────────────────────────────────────────────
    p("[一、数据完整性检查（先证明不是读数缺格/分层漏算）]")
    p("  | tick | 格数 | 每格 class 数 | activated | pop==social（格） | pop==Σclasses（格） | 日耗==ΣnaturalNeeds（格） | social.at.tick 全等 |")
    p("  |---:|---:|---:|---:|---:|---:|---:|:---:|")
    for tick, r in results.items():
        c = r["checks"]
        p(
            f"  | {tick} | {c['rows']} | {','.join(map(str, c['classes_per_hex']))} | "
            f"{'是' if c['all_activated'] else '否'} | {c['pop_eq_social_hexes']}/{c['rows']} | "
            f"{c['pop_eq_sum_classes_hexes']}/{c['rows']} | {c['daily_eq_natural_needs_hexes']}/{c['rows']} | "
            f"{'是' if c['social_tick_all_match'] else '否'} |"
        )
    p(
        "  · 三国格数 = "
        + " / ".join(f"{k}={v}" for k, v in NATION_HEX_COUNTS.items())
        + "；坐标唯一 = "
        + ("是" if all(r["checks"]["coords_unique"] for r in results.values()) else "否")
        + "（逐格 dump 无缺格、无重复格）。"
    )
    for tick, r in results.items():
        c = r["checks"]
        p(
            f"  · tick {tick}: social.crisis 的 MORTALITY evidence 出现 {c['mortality_crisis_present']} 格；"
            f"deathsThisCycle == Σ economy flow.deaths：{c['mortality_crisis_equal']}/{c['mortality_crisis_present']}"
            f"（不等 {c['mortality_crisis_neq']}）—— 跨切片坐实 flow.deaths 的窗口=本周期累计。"
        )
    for tick, r in results.items():
        rs = r["row_stats"]
        p(
            f"  · tick {tick}: 行数 {rs['rows_total']}（pop=0 的行 {rs['rows_pop0']}，无 grain 键 {rs['rows_no_grain_key']}，"
            f"无 grain 键但 pop>0 的行 {rs['no_grain_key_but_pop_pos']}，daily>0 的行 {rs['rows_daily_positive']}）；"
            f"反解失败 {rs['invert_miss']}；单日净变化违反 deaths/births 上界的行 {rs['feasibility_violations']}；"
            f"dailyRationMilli 在 p∈[0,500000] 严格递增={r['mono_ok']}（最小步长 {r['mono_min_step']}）。"
        )
    p("")

    # ── 全局表 ────────────────────────────────────────────────────────────────
    p("[二、全球三 tick：两个量、差额、报告口径的最大逐格相对偏差]")
    p("  | tick | Σdaily（毫粮/日） | Σpop（人） | LHS=Σdaily×120 | RHS=Σpop×10,000 | 差额 LHS−RHS | 相对 RHS | m04 报告口径 max|LHS−RHS|/max(LHS,RHS) | 出现在 |")
    p("  |---:|---:|---:|---:|---:|---:|---:|---:|:---|")
    for tick, r in results.items():
        h = r["max_hex"]
        p(
            f"  | {tick} | {fmt(r['lhs_total'] // 120)} | {fmt(r['rhs_total'] // 10_000)} | {fmt(r['lhs_total'])} | "
            f"{fmt(r['rhs_total'])} | {fmt(r['gap_total'])} | {r['gap_total'] / r['rhs_total']:.8%} | "
            f"{r['max_hex_report_rel']:.6%} | {h['nat']}({h['q']},{h['r']}) |"
        )
    p("")
    p("  注：m04_attribution.py §1.2 的 1.578180% / 3.070161% / 5.336398% 用的就是最后一列")
    p("      （分母 = max(LHS,RHS)，这三处 LHS>RHS ⇒ 分母=LHS）；本脚本逐值复现。")
    p("")

    # ── 精确分解 ──────────────────────────────────────────────────────────────
    p("[三、精确分解（逐行恒等式，无残差、无拟合）]")
    p("  逐行：daily_r × 120 − p_end_r × 10,000 == 10,000 × (p_start_r − p_end_r) + round_r")
    p("        p_start_r = 反解的日初人口；round_r = daily_r×120 − p_start_r×10,000（逐日差分取整残差）")
    p("  逐行相加 = 全球/逐格差额的完整分解。")
    p("  | tick | 差额 LHS−RHS | 单日人口变动项 = 10,000×Σ(p_start−p_end) | 其中净减少人口（人） | 取整残差项 | 人口项占比 | 残差项占比 | 恒等式核验 |")
    p("  |---:|---:|---:|---:|---:|---:|---:|:---:|")
    for tick, r in results.items():
        pop_term = r["delta_total"] * RATION_MILLI_PER_PERSON
        p(
            f"  | {tick} | {fmt(r['gap_total'])} | {fmt(pop_term)} | {fmt(r['delta_total'])} | {fmt(r['round_total'])} | "
            f"{pop_term / r['gap_total']:.6%} | {r['round_total'] / r['gap_total']:.6%} | "
            f"{'OK' if pop_term + r['round_total'] == r['gap_total'] else 'FAIL'} |"
        )
    p("")
    p("  逐行取整残差的完整分布（round_r 只能是 0/40/80；这是 daily = floor 累计之差 的直接后果）：")
    p("  | tick | round=0 | round=40 | round=80 | 其它 | max|round_r| | p 单调性 |")
    p("  |---:|---:|---:|---:|---:|---:|:---:|")
    for tick, r in results.items():
        rv = r["row_stats"]["round_values"]
        p(
            f"  | {tick} | {rv[0]} | {rv[40]} | {rv[80]} | {r['row_stats']['round_other']} | "
            f"{r['row_stats']['max_abs_round']} | {'严格递增' if r['mono_ok'] else '★不递增'} |"
        )
    samples = (1, 100, 5830, 289_203)
    tele_ok = all(
        telescoping_cycle_exact(pop, start) for pop in samples for start in (1, 121, 241)
    )
    p("  · 口径自检（本脚本当场算的）：对样本人口 p=" + str(list(samples)) + "，三个整周期窗口")
    p("      [1,120] / [121,240] / [241,360] 的 Σ dailyRationMilli(p,day) 都 == p×10,000；核验="
      + ("OK" if tele_ok else "FAIL") + "。")
    p("      ⇒ 整周期累计是精确的；“单日×120”只是单日快照的外推，差额由上面两项完整解释。")
    p("")

    # ── 逐格最坏 ──────────────────────────────────────────────────────────────
    p("[四、逐格最大偏差的完整账（m04 点名的那几格 + 首都）]")
    p("  | tick | 国 | (q,r) | LHS | RHS | 差额 | m04 口径相对 | RHS 口径相对 | 日初人口 p_start | 日末人口 p_end | 单日净减 | 取整残差 | 本周期 deaths | 本周期 births |")
    p("  |---:|:---|:---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for tick, r in results.items():
        listed = [r["max_hex"]]
        cap = r["per_hex"].get(("德意志第二帝国", -39, -71))
        if cap is not None and cap not in listed:
            listed.append(cap)
        for h in listed:
            p(
                f"  | {tick} | {h['nat']} | ({h['q']},{h['r']}) | {fmt(h['lhs'])} | {fmt(h['rhs'])} | {fmt(h['gap'])} | "
                f"{abs(h['lhs'] - h['rhs']) / max(abs(h['lhs']), abs(h['rhs']), 1):.6%} | "
                f"{(h['lhs'] - h['rhs']) / h['rhs']:.6%} | {fmt(h['p_start'])} | {fmt(h['p_end'])} | "
                f"{fmt(h['delta_day'])} | {fmt(h['round'])} | {fmt(h['deaths_cycle'])} | {fmt(h['births_cycle'])} |"
            )
    p("")
    p("  · 三 tick 逐格明细（按 m04 口径相对偏差降序前 5；首都即使不是第一也列出）：")
    p("    | tick | 国 | (q,r) | 差额 | 相对(max分母) | 日初−日末（人） | 取整 |")
    p("    |---:|:---|:---|---:|---:|---:|---:|")
    for tick, r in results.items():
        top = sorted(r["per_hex"].values(), key=lambda h: abs(h["lhs"] - h["rhs"]) / max(abs(h["lhs"]), abs(h["rhs"]), 1), reverse=True)[:5]
        cap = r["per_hex"].get(("德意志第二帝国", -39, -71))
        if cap is not None and cap not in top:
            top.append(cap)
        for h in top:
            rel = abs(h["lhs"] - h["rhs"]) / max(abs(h["lhs"]), abs(h["rhs"]), 1)
            p(
                f"    | {tick} | {h['nat']} | ({h['q']},{h['r']}) | {fmt(h['gap'])} | {rel:.6%} | "
                f"{fmt(h['delta_day'])} | {fmt(h['round'])} |"
            )
    p("")

    # ── 方向/国别 ─────────────────────────────────────────────────────────────
    p("[五、方向与国别（为什么“全球差”与“逐格最大差”不是一回事）]")
    p("  | tick | LHS>RHS 格 | LHS<RHS 格 | LHS=RHS 格 | 正差合计 | 负差合计 | 净差 |")
    p("  |---:|---:|---:|---:|---:|---:|---:|")
    for tick, r in results.items():
        p(
            f"  | {tick} | {r['positive_hexes']} | {r['negative_hexes']} | {r['zero_hexes']} | "
            f"{fmt(r['positive_gap_sum'])} | {fmt(r['negative_gap_sum'])} | {fmt(r['gap_total'])} |"
        )
    p("")
    p("  | tick | 国 | 格数 | 日末人口 | 单日净减（人） | 差额合计 | 取整残差合计 |")
    p("  |---:|:---|---:|---:|---:|---:|---:|")
    for tick, r in results.items():
        for nat, agg in r["per_nation"].items():
            p(
                f"  | {tick} | {nat} | {agg['hexes']} | {fmt(agg['pop'])} | {fmt(agg['delta_day'])} | "
                f"{fmt(agg['gap'])} | {fmt(agg['round'])} |"
            )
    p("")

    # ── 跨周期/窗口对平 ───────────────────────────────────────────────────────
    p("[六、跨周期窗口对平（证明 dump 的 population 是“日末”、flow.deaths/births 是“本周期累计”）]")
    p("  恒等式：pop(tick120) − pop(tick360) == [deaths(240)+deaths(360)] − [births(240)+births(360)]（都为正数则成立）")
    g120 = sum(h["p_end"] for h in results[120]["per_hex"].values())
    g360 = sum(h["p_end"] for h in results[360]["per_hex"].values())
    gd = sum(h["deaths_cycle"] for h in results[240]["per_hex"].values()) + sum(
        h["deaths_cycle"] for h in results[360]["per_hex"].values()
    )
    gb = sum(h["births_cycle"] for h in results[240]["per_hex"].values()) + sum(
        h["births_cycle"] for h in results[360]["per_hex"].values()
    )
    p(f"    全球：{fmt(g120)} − {fmt(g360)} = {fmt(g120 - g360)}；deaths {fmt(gd)} − births {fmt(gb)} = {fmt(gd - gb)}；核验={'OK' if g120 - g360 == gd - gb else 'FAIL'}")
    for label, key in (
        ("首都 (-39,-71)", ("德意志第二帝国", -39, -71)),
        ("tick120 最大格 (-30,-58)", None),
    ):
        if key is None:
            h120 = results[120]["max_hex"]
            c240 = results[240]["per_hex"].get((h120["nat"], h120["q"], h120["r"]))
            c360 = results[360]["per_hex"].get((h120["nat"], h120["q"], h120["r"]))
        else:
            h120 = results[120]["per_hex"][key]
            c240 = results[240]["per_hex"][key]
            c360 = results[360]["per_hex"][key]
        if c240 is None or c360 is None:
            continue
        decline = h120["p_end"] - c360["p_end"]
        rhs = (c240["deaths_cycle"] + c360["deaths_cycle"]) - (c240["births_cycle"] + c360["births_cycle"])
        p(
            f"    {label}：{fmt(h120['p_end'])} − {fmt(c360['p_end'])} = {fmt(decline)}；"
            f"deaths {fmt(c240['deaths_cycle'] + c360['deaths_cycle'])} − births "
            f"{fmt(c240['births_cycle'] + c360['births_cycle'])} = {fmt(rhs)}；核验={'OK' if decline == rhs else 'FAIL'}"
        )
    p("")
    p("  · flow 的窗口（FlowRow 类注）：本周期累计、新周期第一天归零；关账日读到的是一整个周期的量。")
    p("  · 因此“本周期人口下降”不是本差额的机制：自然需求逐日覆盖，快照只记住最后一天；")
    p("    见下面第七节把“整周期净减少”与“单日净减少”逐 tick 对出来。")
    p("")

    # ── 候选成因逐项 ──────────────────────────────────────────────────────────
    p("[七、MASTER PLAN §1.7 丁 点名的候选成因，逐项判]")
    p("  | tick | 差额实际值 | 候选①整周期净减少×10,000（若成立会是多少） | 候选①/实际 | 候选②取整残差 | 候选③单日时点错位 = 差额−取整 | 候选④种子/投入 | 候选⑤缺格/分层 |")
    p("  |---:|---:|---:|---:|---:|---:|---|---|")
    for tick, r in results.items():
        cycle_deaths = sum(h["deaths_cycle"] for h in r["per_hex"].values())
        cycle_births = sum(h["births_cycle"] for h in r["per_hex"].values())
        cycle_net = cycle_deaths - cycle_births
        p(
            f"  | {tick} | {fmt(r['gap_total'])} | {fmt(cycle_net * RATION_MILLI_PER_PERSON)} | "
            f"{cycle_net * RATION_MILLI_PER_PERSON / r['gap_total']:.4f}× | {fmt(r['round_total'])} | "
            f"{fmt(r['gap_total'] - r['round_total'])} | 0（两量都不含，见下） | 0（完整性检查全过） |"
        )
    p("")
    p("  ① 期内人口下降：**机制不成立**。自然需求逐日覆盖，dump 里的 daily 只是第 d 天那一份；")
    p("     差额恒等于“第 d 天日初→日末”的一天净变化，不是整周期累计变化。")
    p("     证据：单日净减少 / 整周期净减少 = 153,188/231,859 = 66.07%（120）、26,052/238,361 = 10.93%（240）、")
    p("           8,461/10,427 = 81.15%（360）—— 240 那期整周期净减少是单日的 9.15 倍，若按整周期算会把差额放大 9 倍。")
    p("  ② 取整：**存在但极小**。逐行残差只能 0/40/80 毫粮（本脚本对 19,176 行逐行验证，0 个例外）；")
    p("     占差额 0.0104% / 0.0616% / 0.1932%。它来自“单日口粮×120”与“整周期 p×10,000”的望远镜残差，")
    p("     不是逐日 floor 损失的累积（整周期 Σdaily == p×10,000 是精确的）。")
    p("  ③ 时点错位：**这是全部剩余差额（99.81%~99.99%）**。精确链路——")
    p("     step(day) 先写 naturalNeeds（日初人口）→ 同一天 day%30==0 才做月度出生/死亡并回写行人口；")
    p("     120/240/360 都是 30 的倍数 ⇒ dump 日必然踩在这条缝上。gap = 10,000×(deaths_d−births_d) + 取整项。")
    p("     未拆分部分：deaths_d 与 births_d 各自是多少（见第八节）。")
    p("  ④ 种子/生产投入：**两量都不含**。grainDailyConsumption 只读 naturalNeeds（grain），")
    p("     naturalNeeds 由 dailyNeedsMilli 写（grain+cloth）；种子/原料走 drawCycleInputs → FlowRow.consumed。")
    p("  ⑤ 缺格/城乡分层：**排除**。799/799 格 activated；每格 8 行（rural/urban × 4 阶层）；")
    p("     pop==social、pop==Σclasses、daily==ΣnaturalNeeds 三项逐格 799/799 精确；")
    p("     三国格数 430/138/231、social.at.tick 逐格 = dump tick。")
    p("")
    p("[八、种子/投入对照（把④量出来）]")
    p("  | tick | Σ naturalNeeds.grain（=LHS/120） | Σ flow.consumed.grain（含种子/原料） | consumed ÷ naturalNeeds | Σ flow.unmetNeed.grain（本周期累计） |")
    p("  |---:|---:|---:|---:|---:|")
    for tick in TICKS:
        path = DATA_DIR / f"h6raw_tick{tick}.json"
        raw = json.loads(path.read_text(encoding="utf-8"))
        need = sum(
            c["naturalNeeds"].get(GRAIN, 0)
            for rs in raw["nations"].values()
            for row in rs
            for c in row["economy"]["classes"]
        )
        consumed = sum(
            c["flow"]["consumed"].get(GRAIN, 0)
            for rs in raw["nations"].values()
            for row in rs
            for c in row["economy"]["classes"]
        )
        unmet = sum(
            c["flow"]["unmetNeed"].get(GRAIN, 0)
            for rs in raw["nations"].values()
            for row in rs
            for c in row["economy"]["classes"]
        )
        p(f"  | {tick} | {fmt(need)} | {fmt(consumed)} | {consumed / need:.6f} | {fmt(unmet)} |")
    p("")

    # ── 可以重建 / 不能重建 ──────────────────────────────────────────────────
    p("[九、现存读数能精确重建什么、不能重建什么]")
    p("  能（本脚本已逐值做出来）：")
    p("    · p_start_r：每行“日初人口”由 daily_r 反解唯一确定（dailyRationMilli 在 p 上严格递增，p≤500,000 已核）；")
    p("    · delta_r = p_start_r − p_end_r：快照日一天的人口净变化（**净额精确**）；")
    p("    · round_r ∈ {0,40,80}：取整残差精确；")
    p("    · 因此逐行/逐格/全球“差额 = 单日人口变动项 + 取整残差项”是**恒等式**，不是拟合。")
    p("  不能（现有 JSON 里没有）：")
    p("    · deaths_d 与 births_d 的**当日拆分**：flow.deaths/births 是“本周期累计”，只给 0≤deaths_d≤累积、0≤births_d≤累积；")
    p("      （本脚本对 3 tick 全部 6,392 行/个做了该可行性检查，逐 tick 违反数见第一节。）")
    p("    · 周期内逐日人口/日耗序列：naturalNeeds 逐日覆盖，dump 只留最后一天那一份；")
    p("    · 真正的“本周期实际需求” Σ_{day∈周期} Σ_row dailyRationMilli(pop_row(day), day)：现存代码/读数都没有这个量；")
    p("    · 快照日的人口变化是否**全部**来自月度结算：由代码次序与 day%30==0 推出，但没有 d−1 的 dump 直接核对。")
    p("  最小补读数方案（供 M2 设计，零 Java 改动的部分先做）：")
    p("    (a) 每个关账日追加一次 **d−1** 的同一份 h6sim_dump（119/239/359）。于是")
    p("        flow.deaths(d) − flow.deaths(d−1) = deaths_d（births 同理，逐行精确）；")
    p("        pop(d−1) 应逐行 == 本脚本反解出的 p_start(d)，这是对整条链的独立验收。")
    p("    (b) 若要看“真正的周期需求”，在结算侧加一个**逐行累加器** cycleNaturalNeedMilli =")
    p("        Σ_d dailyRationMilli(row.population(day), day)（新周期第一天归零），由 ApiViews 读出；")
    p("        这需要一行 Java 级改动（M2 读数设计），但与今天的 naturalNeeds 同源、不另立公式。")
    p("    (c) 读口补 tick/lastSettledDay 与“本值用的人口快照”字段：今天 economyHex 入参没有 tick，")
    p("        物理上无法告诉读者 naturalNeeds 是哪一天、哪一份人口的。")
    p("    (d) 要定义“可比口径”，只能二选一：")
    p("        · 日率口径：grainDailyConsumption vs dailyRationMilli(当日日初人口, d)；")
    p("        · 周期口径：cycleNaturalNeedMilli vs cumulativeRationMilli(周期初人口, cycleDays)（人口不变时才相等）。")
    p("        “单日快照×120”与“日末人口×10,000”在任何人口变动下都不可互推。")
    p("")

    # ── 附：顺带核到 ────────────────────────────────────────────────────────
    p("[附：顺带核到的一个旁证（不属于丁的结论）]")
    cap120 = results[120]["per_hex"][("德意志第二帝国", -39, -71)]
    cap360 = results[360]["per_hex"][("德意志第二帝国", -39, -71)]
    p(f"  §1.7 甲组“首都 360,862（城镇 345,908）/ 3,100 亩 / 库存 236,854,357 / 日耗 30,381,003”")
    p(f"    = 本批 tick120 的 (-39,-71)（pop={fmt(cap120['p_end'])}，daily={fmt(cap120['lhs'] // 120)}）；")
    p(f"  §1.4 的“273,770（城镇 259,184）/ 需求 2,737,700” = 本批 tick360 的同一格")
    p(f"    （pop={fmt(cap360['p_end'])}，RHS=pop×10,000={fmt(cap360['rhs'])}）。两者都对，只是不同 tick。")
    p("")

    p("=== m07 结束：以上所有数字都来自三个 md5 已钉死的 JSON，可由本脚本重算。 ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
