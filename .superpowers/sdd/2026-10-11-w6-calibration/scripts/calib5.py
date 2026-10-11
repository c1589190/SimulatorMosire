#!/usr/bin/env python3
"""W6 标定读数抽取器（2026-10-11，只读）。

输入：
  · daily JSONL（w6run.py --daily-out 的逐日紧凑读数；由 scripts/w6lib.py extract 生成）
  · 世界日志（DEBUG + TRACE + social DEBUG，--log-keep 过洪泛事件）
  · （可选）--pop-daily：带逐户人口的补充逐日读数（用于 ⑤ 人口闸吃紧比例的精确推导）

输出：readings/<tag>-readings.txt（人读）+ readings/<tag>-readings.json（机读）

★ 口径纪律（AGENTS §九.4）：每条读数都标注窗口（时点 / 本期 / 累计）。
★ 推理与实测分开：本文件里凡带 “【推理】” 前缀的都不是直接观测，算式写在旁边。
"""
import argparse
import json
import re
import statistics
import sys
from collections import Counter, defaultdict

TS = re.compile(r"^\d{2}:\d{2}:\d{2}\.\d{3} ")


# ── 日志字段解析（值取 \S+，遇空格截断；需要整段中文 reason 时只做前缀比对）────────────
def field(line, key, cast=str, default=None):
    m = re.search(r"(?:^|\s)%s=(\S+)" % re.escape(key), line)
    if not m:
        return default
    try:
        return cast(m.group(1))
    except (TypeError, ValueError):
        return default


def event(line):
    return field(line, "event", str, "")


def kv_map(line, key):
    """解析 {a=1, b=2} 形态（EventLog 的 map 渲染无空格）。"""
    m = re.search(r"(?:^|\s)%s=\{([^}]*)\}" % re.escape(key), line)
    if not m:
        return {}
    out = {}
    for part in m.group(1).split(","):
        if "=" in part:
            k, v = part.split("=", 1)
            try:
                out[k.strip()] = int(v)
            except ValueError:
                pass
    return out


def pct(n, d):
    return "%d/%d = %.2f%%" % (n, d, (100.0 * n / d) if d else 0.0)


def dist_line(vals, label="分布"):
    if not vals:
        return "  %s：（空）" % label
    c = Counter(vals)
    items = sorted(c.items())[:24]
    return "  %s（值×次数，前 24 档）: %s" % (label, ", ".join("%s×%d" % (k, v) for k, v in items))


class Out:
    def __init__(self):
        self.lines = []
        self.json = {}

    def w(self, s=""):
        self.lines.append(s)

    def h(self, s):
        self.lines.append("")
        self.lines.append("=" * 104)
        self.lines.append(s)
        self.lines.append("=" * 104)

    def text(self):
        return "\n".join(self.lines) + "\n"


# ── 装置读数 ────────────────────────────────────────────────────────────────────
def sec_device(o, results, daily_path, log_path):
    o.h("§0 装置与窗口")
    with open(results) as f:
        r = json.load(f)
    o.w("世界           : %s（%s）" % (r.get("world"), r.get("config")))
    o.w("jar md5        : %s" % r.get("jarMd5"))
    o.w("store          : %s" % r.get("store"))
    o.w("GUI 端口       : %s（MCP/审批 = +1/+2）" % r.get("guiPort"))
    o.w("JVM 开关       : %s" % r.get("jvmOpts"))
    o.w("log-keep 正则  : %s" % (r.get("logKeep") or "(不过滤)"))
    o.w("PID            : %s（收工 = 装置对该 PID 发 SIGTERM，未用 pkill -f）" % r.get("pid"))
    o.w("启动就绪       : %s s" % r.get("readySeconds"))
    o.w("head revision  : %s（tick=%s，Δrev=%s）" % (r.get("headRevision"), r.get("tick"),
                                                    r.get("revisionDelta")))
    for s in r.get("steps", []):
        if s.get("op") == "advance_daily":
            o.w("advance_daily  : %s→%s 装置累计耗时 %s s" % (s.get("from"), s.get("to"), s.get("seconds")))
        if s.get("op") == "dump":
            o.w("dump           : %s rev=%s bytes=%s md5=%s" % (s.get("label"), s.get("revision"),
                                                                s.get("bytes"), s.get("md5")))
    o.w("日志行数(去掉时间戳后) : %s；去时间戳 md5 = %s" % (r.get("logLines"), r.get("logStrippedMd5")))
    o.json["device"] = {k: r.get(k) for k in ("tag", "world", "jarMd5", "store", "guiPort", "pid",
                                             "readySeconds", "headRevision", "tick", "logStrippedMd5",
                                             "logLines")}


# ── ① 饿死率快慢 ────────────────────────────────────────────────────────────────
def sec_famine(o, rows, log_path):
    o.h("§1 饿死率快慢 —— FAMINE_MAX_PPM_PER_TICK = 1000（常量，simos-social/.../FamineMortality.java:48）")
    o.w("窗口：逐日（day 0..%d）；人口/满足率为**时点**读数，死亡与 faminePpm 为**当日发生额**。" % rows[-1]["day"])
    # 人口曲线（每 30 天）
    o.w("")
    o.w("【人口曲线（每 30 天，时点）】")
    o.w("  day | 社会人口 | 经济人口 | 户数 | 日死亡(famine/base) | 断顿户 lt1000/有读数 | 全额满足户 | Σsatiety/1000n")
    seen = set()
    for r in rows:
        d = r["day"]
        if d % 30 and d != 0:
            continue
        if d in seen:
            continue
        seen.add(d)
        sat = r["satiety"]
        full = sat["n"] - sat["lt1000"]
        o.w("  %4d | %8d | %8d | %4d | %3d / %3d | %4d / %4d | %4d | %s" % (
            d, r["pop"]["social_total"], r["pop"]["economy_total"], r["pop"]["households"],
            r["deaths"].get("BASE_PLUS_FAMINE", 0), r["deaths"].get("BASE", 0),
            sat["lt1000"], sat["n"], full,
            ("%.3f" % (sat["sum"] / (1000.0 * sat["n"]))) if sat["n"] else "-"))
    o.json["pop_curve"] = [{"day": r["day"], "social": r["pop"]["social_total"],
                            "economy": r["pop"]["economy_total"], "households": r["pop"]["households"],
                            "lt1000": r["satiety"]["lt1000"], "sat_n": r["satiety"]["n"],
                            "sum_satiety": r["satiety"]["sum"]} for r in rows if r["day"] % 30 == 0]
    # 死因累计
    fam = sum(r["deaths"].get("BASE_PLUS_FAMINE", 0) for r in rows)
    base = sum(r["deaths"].get("BASE", 0) for r in rows)
    other = sum(v for r in rows for k, v in r["deaths"].items()
                if k not in ("BASE", "BASE_PLUS_FAMINE"))
    o.w("")
    o.w("【死因构成（累计 0..%d）】" % rows[-1]["day"])
    o.w("  BASE_PLUS_FAMINE（饥荒类）= %d；BASE（基准率）= %d；其它 = %d" % (fam, base, other))
    o.w("  ★ 饥荒类占全部死亡 = %s" % pct(fam, fam + base + other))
    o.w("  人口净变化 = %d → %d（Δ=%+d，%.2f%%）" % (
        rows[0]["pop"]["social_total"], rows[-1]["pop"]["social_total"],
        rows[-1]["pop"]["social_total"] - rows[0]["pop"]["social_total"],
        100.0 * (rows[-1]["pop"]["social_total"] - rows[0]["pop"]["social_total"]) / rows[0]["pop"]["social_total"]))
    o.json["deaths_total"] = {"famine": fam, "base": base, "other": other}
    # 断顿户数/日 + 满足率
    lt = [r["satiety"]["lt1000"] for r in rows if r["satiety"]["n"]]
    n_ = [r["satiety"]["n"] for r in rows if r["satiety"]["n"]]
    zero = [r["satiety"]["zero"] for r in rows if r["satiety"]["n"]]
    o.w("")
    o.w("【断顿与满足率（逐日发生额/时点，统计窗口 = 有读数的那 %d 天）】" % len(lt))
    o.w("  断顿户数/日（satiety<1000）：min=%d 中位=%s max=%d；分母 n（有 satiety 读数的户）中位=%s" % (
        min(lt), statistics.median(lt), max(lt), statistics.median(n_)))
    o.w("  其中 satiety==0（一点没吃上）的户/日：中位=%s max=%d" % (statistics.median(zero), max(zero)))
    fullp = [100.0 * (r["satiety"]["n"] - r["satiety"]["lt1000"]) / r["satiety"]["n"]
             for r in rows if r["satiety"]["n"]]
    o.w("  ★ 全额满足户占比（逐日）：min=%.1f%% 中位=%.1f%% max=%.1f%%" % (
        min(fullp), statistics.median(fullp), max(fullp)))
    satr = [r["satiety"]["sum"] / (1000.0 * r["satiety"]["n"]) for r in rows if r["satiety"]["n"]]
    o.w("  ★ 平均满足率 Σsatiety/(1000·n)：min=%.4f 中位=%.4f max=%.4f" % (
        min(satr), statistics.median(satr), max(satr)))
    o.json["satiety_series"] = {"days_with_reading": len(lt), "lt1000_median": statistics.median(lt),
                                "lt1000_max": max(lt), "zero_median": statistics.median(zero),
                                "full_share_median_pct": statistics.median(fullp),
                                "mean_satiety_median": statistics.median(satr)}
    # faminePpm 分布
    ppm = defaultdict(int)
    ratio = defaultdict(int)
    for r in rows:
        for k, v in r["famine_detail"].items():
            if k.startswith("faminePpm="):
                ppm[int(k.split("=")[1])] += v
            elif k.startswith("starvationRatio="):
                ratio[int(k.split("=")[1])] += v
    tot = sum(ppm.values())
    o.w("")
    o.w("【faminePpm 分布（口径 = 死亡事件的 reason 字段，累计 0..%d）】" % rows[-1]["day"])
    o.w("  总死亡事件数（带 faminePpm 字段）= %d" % tot)
    o.w(dist_line(ppm, "faminePpm 值×次数"))
    o.w("  ★ 顶格（== FAMINE_MAX_PPM_PER_TICK = 1000）占比 = %s" % pct(ppm.get(1000, 0), tot))
    o.w(dict_line(ratio, "starvationRatioPerMille 值×次数"))
    o.json["famine_ppm_hist"] = dict(sorted(ppm.items()))
    # 日志：FAMINE_MORTALITY（INFO，逐日）+ FAMINE_MORTALITY_HOUSEHOLD（DEBUG，逐户逐日）
    daily_gap, daily_deaths, daily_max = [], [], []
    hh_ppm = Counter()
    hh_ratio = Counter()
    hh_rows = 0
    days_with_famine = 0
    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            e = event(line)
            if e == "FAMINE_MORTALITY":
                days_with_famine += 1
                daily_gap.append(field(line, "householdsWithRationGap", int, 0))
                daily_deaths.append(field(line, "famineDeaths", int, 0))
                daily_max.append(field(line, "maxStarvationRatioPerMille", int, 0))
            elif e == "FAMINE_MORTALITY_HOUSEHOLD":
                hh_rows += 1
                hh_ppm[field(line, "faminePpmPerTick", int, -1)] += 1
                hh_ratio[field(line, "starvationRatioPerMille", int, -1)] += 1
    o.w("")
    o.w("【日志口径：FAMINE_MORTALITY（INFO，逐日汇总）】")
    o.w("  发过该事件的天数 = %d / %d（每天都有断顿户）" % (days_with_famine, len(rows)))
    if daily_gap:
        o.w("  断顿户数/日：min=%d 中位=%s max=%d（与上面 satiety<1000 同量级）" % (
            min(daily_gap), statistics.median(daily_gap), max(daily_gap)))
        o.w("  maxStarvationRatioPerMille：中位=%s，==1000 的天数 = %d / %d" % (
            statistics.median(daily_max), sum(1 for x in daily_max if x == 1000), len(daily_max)))
        o.w("  日饥荒死亡 famineDeaths：Σ=%d（对照：死亡事件口径 Σ=%d）" % (sum(daily_deaths), fam))
    o.w("")
    o.w("【日志口径：FAMINE_MORTALITY_HOUSEHOLD（DEBUG，逐户逐日；%d 行）】" % hh_rows)
    o.w(dist_line(dict(hh_ppm), "faminePpmPerTick 值×次数"))
    o.w("  ★ 逐户 faminePpm == 1000（顶格）占比 = %s" % pct(hh_ppm.get(1000, 0), hh_rows))
    o.w("  ★ 逐户 faminePpm 均值 = %.1f（上限 1000）" % (
        (sum(k * v for k, v in hh_ppm.items()) / hh_rows) if hh_rows else 0))
    o.w(dict_line(dict(hh_ratio), "逐户 starvationRatioPerMille 值×次数"))
    o.json["famine_household_rows"] = hh_rows
    o.json["famine_household_ppm_hist"] = dict(sorted(hh_ppm.items()))
    o.json["famine_days"] = days_with_famine


def dict_line(d, label):
    if not d:
        return "  %s：（空）" % label
    items = sorted(d.items())[:24]
    return "  %s（前 24 档）: %s" % (label, ", ".join("%s×%d" % (k, v) for k, v in items))


# ── ② 阶层滑落斜率 ──────────────────────────────────────────────────────────────
def sec_slide(o, rows, log_path):
    o.h("§2 阶层滑落斜率 —— SLIDE_SPEED_PER_MILLE_PER_RATIO=100 / SLIDE_COST_PER_LEVEL=1000 / SLIDE_ROUND_DAYS=5")
    o.w("窗口：滑落事件为**当日发生额**（累计 0..%d）；停留天数取自每次滑落事件自带的 elapsedDays。" % rows[-1]["day"])
    slides = []
    rounds = []
    skip_sum = defaultdict(int)
    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            e = event(line)
            if e == "CLASS_SLIDE":
                slides.append(dict(
                    day=field(line, "day", int, -1),
                    hh=field(line, "household"),
                    frm=field(line, "from"), to=field(line, "to"),
                    frmLvl=field(line, "fromLevel"), toLvl=field(line, "toLevel"),
                    levels=field(line, "levels", int, -1),
                    ratio=field(line, "debtRatioPerMille", int, -1),
                    speed=field(line, "speedPerMille", int, -1),
                    elapsed=field(line, "elapsedDays", int, -1),
                    required=field(line, "requiredDays", int, -1),
                    asset=field(line, "assetCommonValue", int, -1),
                    debt=field(line, "debtCommonValue", int, -1)))
            elif e == "CLASS_SLIDE_ROUND":
                rounds.append((field(line, "day", int, -1), field(line, "slid", int, 0),
                               field(line, "evaluated", int, 0)))
                for k in ("evaluated", "slid", "skippedNoDebt", "skippedNoPosition", "skippedCooldown",
                          "skippedNoLowerTarget", "unpricedDebtHouseholds"):
                    skip_sum[k] += field(line, k, int, 0)
    n = len(slides)
    hhs = sorted({s["hh"] for s in slides})
    total_hh = rows[-1]["pop"]["households"]
    total_rounds = len(rounds)
    o.w("")
    o.w("【滑落次数（600 天累计）】总滑落 = %d 次；涉户 = %d 户 / 共 %d 户（%.1f%%）" % (
        n, len(hhs), total_hh, 100.0 * len(hhs) / total_hh if total_hh else 0))
    o.w("  滑落轮数（CLASS_SLIDE_ROUND）= %d ⇒ 平均每轮滑落 %.4f 次；每户每轮滑落率 = %.4f%%" % (
        total_rounds, n / total_rounds if total_rounds else 0,
        100.0 * n / (total_rounds * total_hh) if total_rounds and total_hh else 0))
    o.w("  ★ 每次滑落是否恰好一级：levels 字段分布 = %s（全部 == 1 ⇒ 不跳级）" % dict(Counter(s["levels"] for s in slides)))
    o.w("")
    o.w("【滑落为什么这么少：逐轮评估/跳过原因（Σ 全窗 %d 轮）】" % total_rounds)
    for k in ("evaluated", "slid", "skippedNoDebt", "skippedNoPosition", "skippedCooldown",
              "skippedNoLowerTarget", "unpricedDebtHouseholds"):
        v = skip_sum.get(k, 0)
        o.w("  Σ%-26s = %8d（占 evaluated 的 %.1f%%）" % (
            k, v, 100.0 * v / skip_sum["evaluated"] if skip_sum.get("evaluated") else 0))
    o.json["slide_skip_sum"] = dict(skip_sum)
    o.w("")
    o.w("【各级停留天数（每次滑落携带的 elapsedDays = 在本级停留了几天）】")
    el = [s["elapsed"] for s in slides]
    if el:
        o.w("  min=%d 中位=%s max=%d 均值=%.1f" % (min(el), statistics.median(el), max(el), sum(el) / len(el)))
        o.w(dist_line(el, "elapsedDays 值×次数"))
    o.w("  对照公式（【推理】，非实测）：每级天数 = ⌈SLIDE_ROUND_DAYS × SLIDE_COST_PER_LEVEL ÷ speed⌉"
        " = ⌈5×1000÷speed⌉；speed = min(1000, ⌊ratio×100÷1000⌋)；ratio = ⌊债×1000÷max(1,资产)⌋")
    o.w("")
    o.w("【债务比 ratio / 速度 speed / 所需天数 required（逐次滑落）】")
    for k, lab in (("ratio", "debtRatioPerMille"), ("speed", "speedPerMille"), ("required", "requiredDays")):
        vals = [s[k] for s in slides]
        if vals:
            o.w("  %-18s min=%d 中位=%s max=%d；%s" % (
                lab, min(vals), statistics.median(vals), max(vals),
                "值×次数: " + ", ".join("%s×%d" % kv for kv in sorted(Counter(vals).items())[:12])))
    # 逐值验证 speed 算式
    bad = [s for s in slides if s["speed"] != min(1000, (s["ratio"] * 100) // 1000)]
    o.w("  ★ 逐值核对 speed == min(1000, ⌊ratio×100÷1000⌋)：不符 %d / %d 次" % (len(bad), n))
    bad2 = [s for s in slides if s["required"] != -(-(5 * 1000) // s["speed"]) if s["speed"] > 0]
    o.w("  ★ 逐值核对 requiredDays == ⌈5×1000÷speed⌉：不符 %d / %d 次" % (len(bad2), n))
    o.w("")
    o.w("【层级迁移对（fromLevel → toLevel）】")
    for (a, b), c in Counter((s["frmLvl"], s["toLvl"]) for s in slides).most_common(14):
        o.w("  %-40s → %-40s %d 次" % (a, b, c))
    o.w("")
    o.w("【滑落日分布（前 20 个滑落日）】")
    o.w("  " + ", ".join("d%d×%d" % kv for kv in sorted(Counter(s["day"] for s in slides).items())[:20]))
    per_hh = Counter(s["hh"] for s in slides)
    o.w("  滑落最多的户：%s" % ", ".join("%s×%d" % kv for kv in per_hh.most_common(6)))
    o.json["slide"] = {"total": n, "households": len(hhs), "levels_hist": dict(Counter(s["levels"] for s in slides)),
                       "elapsed_hist": dict(sorted(Counter(el).items())) if el else {},
                       "rounds": total_rounds, "per_hh_top": per_hh.most_common(6)}


# ── ③ 铸币产出率 ────────────────────────────────────────────────────────────────
def sec_mint(o, rows, log_path):
    o.h("§3 铸币产出率 —— MINT_MILLI_PER_FURNACE_CYCLE=500（EconomySeeder.java:4008）/ MINT_LABOR_SHARE_PER_MILLE=30（:599）")
    o.w("窗口：铸币按**产业周期关账日**发生（mint cycleDays=120）⇒ 逐关账日列示；货币总量为**时点**。")
    mints = []
    for r in rows:
        for x in (r.get("issuance_today") or []):
            if x["kind"] == "MINT_PRODUCTION":
                mints.append((r["day"], x["cur"], x["amt"], x["gov"]))
    o.w("")
    o.w("【逐关账日 MINT_PRODUCTION（当日发生额）】")
    if mints:
        for d, cur, amt, gov in mints:
            o.w("  day=%-5d currency=%-8s amount=%d 毫  government=%s" % (d, cur, amt, gov))
        o.w("  ★ 600 天内实际铸币轮数 = %d（理论上限 = 600÷120 = 5 轮）" % len(mints))
        o.w("  ★ 铸币累计 = %d 毫（全部 %s）" % (sum(m[2] for m in mints), mints[0][1]))
    else:
        o.w("  （无）")
    o.w("")
    o.w("【国库曲线（时点；每 60 天）】")
    govs = sorted(rows[-1]["treasury"].keys())
    o.w("  day | money_total | " + " | ".join(govs))
    for r in rows:
        if r["day"] % 60:
            continue
        o.w("  %4d | %11d | %s" % (r["day"], r["money_total"],
                                   " | ".join(str(r["treasury"].get(g, {})) for g in govs)))
    o.w("  day 600 | %11d | %s" % (rows[-1]["money_total"],
                                   " | ".join(str(rows[-1]["treasury"].get(g, {})) for g in govs)))
    m0 = rows[0]["money_total"]
    m1 = rows[-1]["money_total"]
    o.w("")
    o.w("【货币总量曲线（对照 m0 = 创世后 day 0）】")
    o.w("  m0 = %d（%s）" % (m0, rows[0]["money"]))
    o.w("  m600 = %d（%s）" % (m1, rows[-1]["money"]))
    o.w("  ★ Δ = %+d（%+.3f%%）；其中铸币贡献 = %+d（%+.3f%%）" % (
        m1 - m0, 100.0 * (m1 - m0) / m0, sum(m[2] for m in mints), 100.0 * sum(m[2] for m in mints) / m0))
    o.w("  逐 120 天货币总量：" + ", ".join(
        "d%d=%d" % (r["day"], r["money_total"]) for r in rows if r["day"] % 120 == 0))
    o.w("  发行恒等式（累计）: %s" % rows[-1]["issuance_identity"])
    o.w("  按 kind 累计       : %s" % rows[-1]["issuance_by_kind"])
    # 日志侧
    o.w("")
    o.w("【日志侧：铸币关账读数】")
    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            e = event(line)
            if e == "SEED_MINT_LABOR" or e == "SEED_MINT_SKIPPED":
                o.w("  [播种] " + TS.sub("", line.strip())[:300])
            elif e == "MINT_SETTLED":
                o.w("  [关账] day=%s period=%s closedUnits=%s mintedTotal=%s byCurrency=%s kind=%s" % (
                    field(line, "day"), field(line, "period"), field(line, "closedUnits"),
                    field(line, "mintedTotal"), field(line, "byCurrency"), field(line, "kind")))
            elif e in ("MINT_POLICY_ZERO", "MINT_LIMIT_REJECTED", "MINT_CURRENCY_NOT_ISSUABLE",
                       "MINT_CREDIT", "MINT_LAST_CYCLE_READOUT"):
                lim = field(line, "limit")
                o.w("  [%s] day=%s government=%s limit=%s previousCycleMinted=%s mintedTotal=%s" % (
                    e, field(line, "day"), field(line, "government"), lim,
                    field(line, "previousCycleMinted"), field(line, "mintedTotal")))
    # 政策上限 vs 实际
    o.w("")
    o.w("【政策闸门（mintScalePerCycle）vs 实际产出】")
    o.w("  world-silver 的 mintScalePerCycle = 2000 毫/周期（GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI，EconomySeeder.java:1058）")
    o.w("  实际每轮 = 500 毫 ⇒ **上限不是瓶颈（差 4 倍）**；瓶颈是单位规模（=1）×产出率（500）")
    o.json["mint"] = {"records": len(mints), "total_milli": sum(m[2] for m in mints),
                      "days": [m[0] for m in mints], "m0": m0, "m1": m1,
                      "delta_pct": 100.0 * (m1 - m0) / m0}


# ── ④ 借贷利率 ──────────────────────────────────────────────────────────────────
def sec_loan(o, rows, log_path):
    o.h("§4 借贷利率 —— DEFAULT_LOAN_RATE_ALPHA_PER_MILLE=50（MarketTable.java:110）+ MarketTable 默认 20‰")
    o.w("窗口：**逐市场轮**（每轮一行/区）；200‰/50‰ 为政策现值，利率轨迹为实测。")
    whys = []
    unfilled = Counter()
    unfilled_milli = defaultdict(int)
    rounds = []
    skipped = 0
    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            e = event(line)
            if e == "MONEY_MARKET_WHY":
                whys.append(dict(
                    day=field(line, "day", int, -1), zone=field(line, "zone"),
                    cur=field(line, "currency"),
                    borrowers=field(line, "borrowers", int, 0), lenders=field(line, "lenders", int, 0),
                    demand=field(line, "demandMilli", int, 0), supply=field(line, "supplyMilli", int, 0),
                    lent=field(line, "lentMilli", int, 0), loans=field(line, "loans", int, 0),
                    unmet=field(line, "unmetMilli", int, 0),
                    rate=field(line, "ratePerMillePerCycle", int, -1),
                    rateNext=field(line, "rateNextPerMillePerCycle", int, -1),
                    alpha=field(line, "alphaPerMille", int, -1)))
            elif e == "MONEY_MARKET_UNFILLED":
                r = field(line, "reason", str, "?")
                key = r.split("（")[0]
                unfilled[key] += 1
                unfilled_milli[key] += field(line, "unmetMilli", int, 0)
            elif e == "MONEY_MARKET_ROUND":
                rounds.append(dict(day=field(line, "day", int, -1), zones=field(line, "zones", int, 0),
                                   demand=field(line, "demandMilli", int, 0),
                                   supply=field(line, "supplyMilli", int, 0),
                                   lent=field(line, "lentMilli", int, 0),
                                   loans=field(line, "loans", int, 0),
                                   rateChanges=field(line, "rateChanges", int, 0)))
            elif e == "MONEY_MARKET_SKIPPED":
                skipped += 1
    o.w("")
    o.w("【市场轮计数】MONEY_MARKET_WHY（逐区逐轮）= %d；MONEY_MARKET_ROUND = %d；MONEY_MARKET_SKIPPED（无债务工作副本）= %d" % (
        len(whys), len(rounds), skipped))
    if whys:
        dem = [w["demand"] for w in whys]
        sup = [w["supply"] for w in whys]
        lent = [w["lent"] for w in whys]
        unmet = [w["unmet"] for w in whys]
        rates = [w["rate"] for w in whys]
        rnext = [w["rateNext"] for w in whys]
        o.w("  ★ 需求 demandMilli：min=%d 中位=%s max=%d；Σ=%d" % (min(dem), statistics.median(dem), max(dem), sum(dem)))
        o.w("  ★ 供给 supplyMilli：min=%d 中位=%s max=%d；Σ=%d" % (min(sup), statistics.median(sup), max(sup), sum(sup)))
        o.w("  ★ 成交 lentMilli ：min=%d 中位=%s max=%d；Σ=%d（Σlent/Σdemand = %.2f%%）" % (
            min(lent), statistics.median(lent), max(lent), sum(lent),
            100.0 * sum(lent) / sum(dem) if sum(dem) else 0))
        o.w("  ★ 未满足 unmetMilli：min=%d 中位=%s max=%d；Σ=%d" % (min(unmet), statistics.median(unmet), max(unmet), sum(unmet)))
        o.w("  ★ 利率 ratePerMillePerCycle：min=%d 中位=%s max=%d" % (min(rates), statistics.median(rates), max(rates)))
        o.w("    下一轮利率 rateNext：min=%d 中位=%s max=%d" % (min(rnext), statistics.median(rnext), max(rnext)))
        o.w("    利率分布（值×轮数）: %s" % ", ".join("%s×%d" % kv for kv in sorted(Counter(rates).items())[:16]))
        o.w("  ★ 放贷池全空轮次（supply==0 且 demand>0）= %d / %d" % (
            sum(1 for w in whys if w["supply"] == 0 and w["demand"] > 0), len(whys)))
        o.w("  ★ 一笔未成交轮次（loans==0 且 demand>0）= %d / %d" % (
            sum(1 for w in whys if w["loans"] == 0 and w["demand"] > 0), len(whys)))
        o.w("  ★ 实际放款笔数 Σloans = %d；Σ借户 %d / Σ贷户 %d；零需求轮次 = %d / %d（利率顶到无人借）" % (
            sum(w["loans"] for w in whys), sum(w["borrowers"] for w in whys),
            sum(w["lenders"] for w in whys), sum(1 for w in whys if w["demand"] == 0), len(whys)))
        o.w("  ★ 平均每笔放款 = %.1f 毫（Σlent÷Σloans）" % (
            (sum(lent) / sum(w["loans"] for w in whys)) if sum(w["loans"] for w in whys) else 0))
        o.w("  ★ alphaPerMille 分布: %s" % dict(Counter(w["alpha"] for w in whys)))
        o.w("")
        o.w("【逐 10 轮轨迹（day / 需求 / 供给 / 成交 / 未满足 / 利率→下轮 / 借户/贷户）】")
        for w in whys[::max(1, len(whys) // 24)]:
            o.w("  d%-5d %10d %10d %10d %10d  %5d → %-5d  %d/%d" % (
                w["day"], w["demand"], w["supply"], w["lent"], w["unmet"], w["rate"], w["rateNext"],
                w["borrowers"], w["lenders"]))
        o.json["loan"] = {"rounds": len(whys), "sum_demand": sum(dem), "sum_supply": sum(sup),
                          "sum_lent": sum(lent), "sum_unmet": sum(unmet),
                          "rate_min": min(rates), "rate_median": statistics.median(rates), "rate_max": max(rates),
                          "pool_empty_rounds": sum(1 for w in whys if w["supply"] == 0 and w["demand"] > 0),
                          "zero_loan_rounds": sum(1 for w in whys if w["loans"] == 0 and w["demand"] > 0),
                          "rate_hist": dict(sorted(Counter(rates).items()))}
    o.w("")
    o.w("【MONEY_MARKET_UNFILLED 归因（逐户逐轮缺口，累计）】")
    for k, c in unfilled.most_common():
        o.w("  %-24s %6d 条 / Σ缺口 %d 毫" % (k, c, unfilled_milli[k]))
    o.json["loan_unfilled"] = {k: [c, unfilled_milli[k]] for k, c in unfilled.items()}
    # 债务存量 vs 货币总量（利率反馈的后果读数）
    o.w("")
    o.w("【债务存量 vs 货币总量（时点，每 120 天；★ 利率自适应的后果）】")
    o.w("  day | 未偿 money:silver 本金 | 全世界 silver 存量 | 倍数 | 未偿合同数 | 当日 interestDue")
    for r in rows:
        if r["day"] % 120:
            continue
        pb = r["debt"]["principal_by_medium"]
        ms = pb.get("money:silver", 0)
        sil = (r["money"] or {}).get("silver", 0)
        o.w("  %4d | %22d | %18d | %8s | %10d | %s" % (
            r["day"], ms, sil, ("%.3g" % (ms / sil)) if sil else "-",
            r["debt"]["open"], r["flows"].get("interestDue", 0)))
        o.json.setdefault("debt_series", []).append(
            {"day": r["day"], "money_silver": ms, "silver": sil, "open": r["debt"]["open"],
             "interest_due": r["flows"].get("interestDue", 0)})
    o.w("  ★ 旧口径说明：w6lib.extract 的 `loans`（按 kind/origin 认贷款）在当前 DebtContract 形状下**无该字段** ⇒ 恒 0，")
    o.w("    不代表没有放款；放款笔数取上面 MONEY_MARKET_WHY 的 Σloans。")
    # 价表侧（逐日时点）
    o.w("")
    o.w("【市场价表侧（逐日时点，每 60 天）】")
    for r in rows:
        if r["day"] % 60:
            continue
        o.w("  day %4d tables=%s" % (r["day"], json.dumps(r["marketTables"], ensure_ascii=False)[:260]))


# ── ⑤ 转业上限与斜率 ────────────────────────────────────────────────────────────
def sec_migration(o, rows, log_path, pop_daily=None):
    o.h("§5 转业上限与斜率 —— MIGRATION_SPEED_CAP_PER_MILLE=100 / PROFIT_GAP_SPEED_SLOPE_PER_MILLE=100（均**无 GM 写口**）")
    o.w("窗口：转业只在**周期关账钩子**发生（实测每 30 天一轮）；速度为逐笔实测，闸门吃紧为推导（见下）。")
    moves = []
    fail_sum = defaultdict(int)
    cand = []
    applied = []
    rounds = []
    fills = []
    fill_rows = 0
    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            e = event(line)
            if e == "MIGRATION_MOVE":
                moves.append(dict(day=field(line, "day", int, -1), src=field(line, "source"),
                                  tgt=field(line, "target"), hex=field(line, "targetHex"),
                                  mode=field(line, "targetMode"),
                                  pop=field(line, "population", int, 0),
                                  money=field(line, "moneyMilli", int, 0),
                                  debt=field(line, "debtMilli", int, 0),
                                  speed=field(line, "speedPerMille", int, 0),
                                  tier=field(line, "targetTierIndex", int, -1),
                                  reason=field(line, "reason")))
            elif e == "MODE_MIGRATION_CANDIDATES":
                cand.append(dict(day=field(line, "day", int, -1),
                                 rows=field(line, "candidateRows", int, 0),
                                 merchant=field(line, "merchantCandidates", int, 0),
                                 noPop=field(line, "skippedShellNoPopulation", int, 0),
                                 noLabor=field(line, "skippedShellNoLabor", int, 0),
                                 moves=field(line, "moves", int, 0),
                                 moved=field(line, "movedPopulation", int, 0)))
                for k, v in kv_map(line, "failuresByReason").items():
                    fail_sum[k] += v
            elif e == "MIGRATION_APPLIED":
                applied.append(dict(day=field(line, "day", int, -1), moves=field(line, "moves", int, 0),
                                    before=field(line, "rowsBefore", int, -1),
                                    after=field(line, "rowsAfter", int, -1)))
            elif e == "MARKET_REPORT":
                fills.append((field(line, "day", int, -1), field(line, "fills", int, 0)))
            elif e == "MARKET_FILL":
                fill_rows += 1
    n = len(moves)
    hhs = sorted({m["src"] for m in moves})
    o.w("")
    o.w("【转业笔数（累计 0..%d）】MIGRATION_MOVE = %d 笔；涉源户 = %d 户；MIGRATION_APPLIED 轮 = %d" % (
        rows[-1]["day"], n, len(hhs), len(applied)))
    o.w("  逐轮落点：" + ", ".join("d%d:%d笔" % (a["day"], a["moves"]) for a in applied[:24]))
    o.w("  轮次间隔（天）: %s" % sorted({b["day"] - a["day"] for a, b in zip(applied, applied[1:])}))
    o.w("  MIGRATION_APPLIED 中 rowsBefore==rowsAfter（并入既有户）= %d / %d" % (
        sum(1 for a in applied if a["before"] == a["after"]), len(applied)))
    o.w("")
    o.w("【速度分布（逐笔实测，迁移笔数为权重）】")
    sp = [m["speed"] for m in moves]
    if sp:
        o.w("  min=%d 中位=%s max=%d 均值=%.1f" % (min(sp), statistics.median(sp), max(sp), sum(sp) / len(sp)))
        o.w(dist_line(sp, "speedPerMille 值×笔数"))
        cap = 100
        o.w("  ★ speed > MIGRATION_SPEED_CAP_PER_MILLE(%d) 的笔数 = %s（⇒这些笔的**配额可能被人口闸夹过**）" % (
            cap, pct(sum(1 for s in sp if s > cap), n)))
        o.w("  ★ speed == 1000（A 规则满速档）的笔数 = %s" % pct(sum(1 for s in sp if s == 1000), n))
        o.w("  ★ speed ≤ %d（闸门**不可能**生效）的笔数 = %s" % (cap, pct(sum(1 for s in sp if s <= cap), n)))
    o.w("")
    o.w("【搬迁人口与方向（累计）】")
    o.w("  Σ搬迁人口 = %d；单笔最大 = %d；Σ随迁货币 = %d 毫；Σ随迁债务 = %d" % (
        sum(m["pop"] for m in moves), max((m["pop"] for m in moves), default=0),
        sum(m["money"] for m in moves), sum(m["debt"] for m in moves)))
    pop600 = rows[-1]["pop"]["social_total"]
    o.w("  ★ Σ搬迁人口 / 期末人口 = %.2f%%" % (100.0 * sum(m["pop"] for m in moves) / pop600))
    o.w("  reason 分布: %s" % dict(Counter(m["reason"] for m in moves)))
    o.w("  targetMode 分布: %s" % dict(Counter(m["mode"] for m in moves)))
    o.w("  ★ targetTierIndex 分布（0=贫农/兜底档 … 3=地主档）: %s" % dict(Counter(m["tier"] for m in moves)))
    # ── 人口闸吃紧比例 ──
    o.w("")
    o.w("【人口闸吃紧比例】")
    o.w("  【推理】闸门生效当且仅当 max(1,⌊pop×speed÷1000⌋) > max(1,⌊pop×100÷1000⌋)，即需 speed > 100。")
    grp = defaultdict(list)
    for m in moves:
        grp[(m["day"], m["src"])].append(m)
    if pop_daily:
        # 精确推导：源户人口取“迁移发生日的前一日收盘”时点（迁移在关店钩子内发生）
        popb = {}
        for r in pop_daily:
            for hh, v in (r.get("pop_by_hh") or {}).items():
                popb[(r["day"], hh)] = v
        for r in pop_daily:
            for hh, v in (r.get("pop_by_hh") or {}).items():
                popb.setdefault((r["day"] + 1, hh), v)  # 次日开始时的近似
        tight = hit = 0
        tot = 0
        for (day, hh), ms in sorted(grp.items()):
            pop = popb.get((day, hh))
            if pop is None:
                continue
            tot += 1
            mx = max(m["speed"] for m in ms)
            speedMove = max(1, pop * mx // 1000) if pop > 0 and mx > 0 else 0
            capMove = max(1, pop * 100 // 1000) if pop > 0 else 0
            moved = sum(m["pop"] for m in ms)
            if capMove < speedMove:
                tight += 1
                if moved >= capMove:
                    hit += 1
        o.w("  【实测+推导】可推导的 户×轮 组 = %d / %d（逐户人口取自补充逐日读数）" % (tot, len(grp)))
        if tot:
            o.w("  ★ 闸门真的夹过（capMove < speedMove）= %s" % pct(tight, tot))
            o.w("  ★ 夹到天花板（Σ迁出 ≥ capMove）= %s" % pct(hit, tot))
        o.json["gate"] = {"groups": len(grp), "derivable": tot, "tight": tight, "hit": hit}
    else:
        o.w("  ★ 实测（无需人口的上界）：speed>100 的**笔数占比** = %s ⇒ 闸门可能生效的比例上界。" % (
            pct(sum(1 for s in sp if s > 100), n) if sp else "0/0"))
        o.w("  ★ 逐户人口不在逐日紧凑读数里 ⇒ 精确吃紧比例见补充读数（--pop-daily）。")
        o.json["gate"] = {"derivable": 0}
    o.w("")
    o.w("【候选闸（MODE_MIGRATION_CANDIDATES）与壳户过滤】")
    for c in cand[:24]:
        o.w("  d%-5d candidateRows=%-5d merchant=%-4d skippedShellNoPop=%-4d noLabor=%-4d moves=%-3d movedPop=%d" % (
            c["day"], c["rows"], c["merchant"], c["noPop"], c["noLabor"], c["moves"], c["moved"]))
    o.w("  ΣcandidateRows=%d Σmoves=%d ΣmovedPop=%d ΣnoPop=%d ΣnoLabor=%d" % (
        sum(c["rows"] for c in cand), sum(c["moves"] for c in cand), sum(c["moved"] for c in cand),
        sum(c["noPop"] for c in cand), sum(c["noLabor"] for c in cand)))
    o.w("  ★ 逐轮失败理由 failuresByReason（Σ 全窗）:")
    for k, v in sorted(fail_sum.items(), key=lambda x: -x[1])[:14]:
        o.w("      %-52s %8d" % (k, v))
    o.json["migration_failures"] = dict(fail_sum)
    o.w("")
    o.w("【市场 Σfills（转业引起的市场损伤读数）】")
    fv = [f for _, f in fills]
    o.w("  MARKET_REPORT 轮数 = %d；Σfills = %d；MARKET_FILL 行数 = %d ⇒ 一致 = %s" % (
        len(fills), sum(fv), fill_rows, sum(fv) == fill_rows))
    o.w("  ★ 零成交轮次 = %s（W9d 实测的硬牙：上限 100‰ 就是为它加的）" % pct(sum(1 for f in fv if f == 0), len(fv)))
    if fv:
        o.w("  min/中位/max = %d / %s / %d" % (min(fv), statistics.median(fv), max(fv)))
    o.json["market"] = {"rounds": len(fills), "sum_fills": sum(fv), "zero_rounds": sum(1 for f in fv if f == 0),
                        "median": statistics.median(fv) if fv else 0}
    o.json["migration"] = {"moves": n, "households": len(hhs), "rounds": len(applied),
                           "speed_min": min(sp) if sp else 0, "speed_max": max(sp) if sp else 0,
                           "speed_median": statistics.median(sp) if sp else 0,
                           "speed_gt_cap": sum(1 for s in sp if s > 100),
                           "speed_eq_1000": sum(1 for s in sp if s == 1000),
                           "speed_hist": dict(sorted(Counter(sp).items())),
                           "sum_moved_pop": sum(m["pop"] for m in moves),
                           "max_single_pop": max((m["pop"] for m in moves), default=0),
                           "tier_hist": dict(Counter(m["tier"] for m in moves))}


# ── 运维读数 ────────────────────────────────────────────────────────────────────
def sec_ops(o, rows_all, perf):
    o.h("§6 运维读数（性能 3 样本 / 人口曲线 / 货币总量）")
    o.w("【性能：0→360 one-shot，每世界 3 样本（DEBUG+TRACE，与 W9f 同口径；各自独立 store/端口）】")
    o.w("  样本 |   世界 | advance 秒 | 装置累计秒 | ready 秒 | head rev | logStrippedMd5")
    for p in perf:
        with open(p) as f:
            r = json.load(f)
        adv = next((s for s in r.get("steps", []) if s.get("op") == "advance"), {})
        o.w("  %-6s | %-12s | %9s | %10s | %8s | %8s | %s" % (
            r.get("tag"), r.get("world"), adv.get("seconds"), adv.get("seconds"),
            r.get("readySeconds"), r.get("headRevision"), (r.get("logStrippedMd5") or "")[:12]))
    sw = []
    tp = []
    for p in perf:
        with open(p) as f:
            r = json.load(f)
        adv = next((s for s in r.get("steps", []) if s.get("op") == "advance"), {})
        (sw if r.get("world") == "small-world" else tp).append(adv.get("seconds"))
    for lab, v in (("small-world", sw), ("three-powers", tp)):
        if v:
            o.w("  ★ %s：样本 %s；min=%.3f 中位=%.3f max=%.3f 均值=%.3f（W9f 报 %s）" % (
                lab, v, min(v), statistics.median(v), max(v), sum(v) / len(v),
                "10.650" if lab == "small-world" else "48.654"))
    o.json["perf"] = {"small-world": sw, "three-powers": tp}
    o.w("")
    o.w("【两世界人口/货币总量曲线（每 60 天，时点）】")
    for tag, rows in rows_all:
        o.w("  ── %s ──" % tag)
        o.w("    day |    人口 |  户数 |  货币总量 | 铸币累计 | 滑落累计(该日) | 断顿户")
        mint_cum = 0
        for r in rows:
            for x in (r.get("issuance_today") or []):
                if x["kind"] == "MINT_PRODUCTION":
                    mint_cum += x["amt"]
            if r["day"] % 60:
                continue
            o.w("    %4d | %7d | %5d | %10d | %8d | %14s | %6d" % (
                r["day"], r["pop"]["social_total"], r["pop"]["households"], r["money_total"],
                mint_cum, "-", r["satiety"]["lt1000"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--daily", required=True)
    ap.add_argument("--log", required=True)
    ap.add_argument("--results", required=True)
    ap.add_argument("--pop-daily", default=None)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--perf", nargs="*", default=[])
    ap.add_argument("--peer-daily", default=None, help="另一个世界的逐日读数（运维曲线对照）")
    args = ap.parse_args()

    rows = [json.loads(l) for l in open(args.daily, encoding="utf-8") if l.strip()]
    rows_all = [(args.tag, rows)]
    if args.peer_daily:
        peer = [json.loads(l) for l in open(args.peer_daily, encoding="utf-8") if l.strip()]
        rows_all.append((args.peer_daily.split("/")[-1].replace("daily-", "").replace(".jsonl", ""), peer))
    pop_daily = None
    if args.pop_daily:
        pop_daily = [json.loads(l) for l in open(args.pop_daily, encoding="utf-8") if l.strip()]

    o = Out()
    o.w("W6 标定读数（2026-10-11，只读批） —— tag=%s" % args.tag)
    o.w("生成器：scripts/calib5.py；输入 daily=%s log=%s" % (args.daily, args.log))
    sec_device(o, args.results, args.daily, args.log)
    sec_famine(o, rows, args.log)
    sec_slide(o, rows, args.log)
    sec_mint(o, rows, args.log)
    sec_loan(o, rows, args.log)
    sec_migration(o, rows, args.log, pop_daily)
    sec_ops(o, rows_all, args.perf)

    import os
    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "%s-readings.txt" % args.tag), "w", encoding="utf-8") as f:
        f.write(o.text())
    with open(os.path.join(args.out_dir, "%s-readings.json" % args.tag), "w", encoding="utf-8") as f:
        json.dump(o.json, f, ensure_ascii=False, indent=2, sort_keys=True)
    print(o.text())


if __name__ == "__main__":
    main()
