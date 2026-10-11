#!/usr/bin/env python3
"""解析运力小窗口日志 → 逐格表（只读；输出落本报告目录 evidence/）。

输入：results/sw-replay.log（day 601..689 的过滤日志，白名单见 haul_run.py --log-keep）
输出：evidence/*.md / *.csv
"""
import collections
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.dirname(HERE)
TAG = sys.argv[1] if len(sys.argv) > 1 else "sw-replay"
LOG = os.path.join(D, "results", "%s.log" % TAG)
EV = os.path.join(D, "evidence")
os.makedirs(EV, exist_ok=True)

KV = re.compile(r"(\w+)=(\{[^}]*\}|[^\s]+)")


def parse(line):
    ev = re.search(r"event=([A-Za-z0-9_]+)", line)
    if not ev:
        return None, {}
    d = {}
    for k, v in KV.findall(line):
        d[k] = v
    return ev.group(1), d


def num(d, k):
    try:
        return int(d.get(k, "0"))
    except ValueError:
        return 0


def hexkey(s):
    try:
        a, b = s.split("_")
        return (int(a), int(b))
    except Exception:
        return (999, 999)


def main():
    rows = collections.defaultdict(list)
    with open(LOG) as f:
        for line in f:
            ev, d = parse(line)
            if ev:
                rows[ev].append(d)

    # 窗口（本期）从数据推：DAY_START 的 day 集合
    days = sorted({int(d.get("day", "0")) for d in rows["DAY_END"]})
    win = "day %d..%d（%d 个已完结日）" % (days[0], days[-1], len(days)) if days else "（无 DAY_END）"
    print("窗口:", win)

    # ── A：逐发货格 需求/供给 ────────────────────────────────────────────────
    supply = collections.defaultdict(lambda: collections.Counter())
    supply_days = collections.defaultdict(set)
    for d in rows["CAPACITY_DEMAND_SUPPLY_HEX"]:
        h = d["shippingHex"]
        c = supply[h]
        c["rounds"] += 1
        c["demandInstances"] += num(d, "demandInstances")
        c["demandWorkMilli"] += num(d, "demandWorkMilli")
        c["servedWorkMilli"] += num(d, "servedWorkMilli")
        c["unservedWorkMilli"] += num(d, "unservedWorkMilli")
        c["capacityBudgetMilli"] += num(d, "capacityBudgetMilli")
        c["gapMilli"] += num(d, "gapMilli")
        supply_days[h].add(d.get("day"))
    with open(os.path.join(EV, TAG + "-perhex-demand.md"), "w") as f:
        f.write("# 逐发货格 运力需求/供给（本期 = %s，日志 CAPACITY_DEMAND_SUPPLY_HEX）\n\n" % win)
        f.write("| 发货格 | 有需求轮数 | Σ需求实例 | Σ需求工作量(毫商品·程) | Σ已服务 | Σ未服务 | Σ运力预算 | Σ缺口 |\n")
        f.write("|---|---|---|---|---|---|---|---|\n")
        for h in sorted(supply, key=hexkey):
            c = supply[h]
            f.write("| %s | %d | %d | %d | %d | %d | %d | %d |\n" % (
                h, c["rounds"], c["demandInstances"], c["demandWorkMilli"],
                c["servedWorkMilli"], c["unservedWorkMilli"], c["capacityBudgetMilli"],
                c["gapMilli"]))
        tot = collections.Counter()
        for c in supply.values():
            tot.update(c)
        f.write("| **合计** | %d | %d | %d | %d | %d | %d | %d |\n" % (
            tot["rounds"], tot["demandInstances"], tot["demandWorkMilli"],
            tot["servedWorkMilli"], tot["unservedWorkMilli"], tot["capacityBudgetMilli"],
            tot["gapMilli"]))

    # ── B：逐格运力池（逐轮）────────────────────────────────────────────────
    pool = collections.defaultdict(lambda: collections.Counter())
    pool_rounds = collections.defaultdict(list)
    for d in rows["MERCHANT_CAPACITY_POOL_HEX"]:
        h = d["hex"]
        c = pool[h]
        c["rounds"] += 1
        c["capacityMilli"] += num(d, "capacityMilli")
        c["maxCapacityMilli"] = max(c["maxCapacityMilli"], num(d, "capacityMilli"))
        c["allocatedMilli"] += num(d, "allocatedMilli")
        c["allocatedWorkMilli"] += num(d, "allocatedWorkMilli")
        c["allocations"] += num(d, "allocations")
        c["households"] = max(c["households"], num(d, "households"))
        if num(d, "capacityMilli") > 0:
            c["nonzeroRounds"] += 1
        pool_rounds[h].append((d.get("day"), num(d, "capacityMilli")))
    with open(os.path.join(EV, TAG + "-perhex-capacity-pool.md"), "w") as f:
        f.write("# 逐格运力池（本期 = %s；日志 MERCHANT_CAPACITY_POOL_HEX，每市场轮一行/格）\n\n" % win)
        f.write("| 格 | 轮数 | ΣcapacityMilli | 最大capacityMilli | 非零轮数 | ΣallocatedMilli | ΣallocatedWorkMilli | Σallocations | 池内家户数 |\n")
        f.write("|---|---|---|---|---|---|---|---|---|\n")
        for h in sorted(pool, key=hexkey):
            c = pool[h]
            f.write("| %s | %d | %d | %d | %d | %d | %d | %d | %d |\n" % (
                h, c["rounds"], c["capacityMilli"], c["maxCapacityMilli"], c["nonzeroRounds"],
                c["allocatedMilli"], c["allocatedWorkMilli"], c["allocations"], c["households"]))
    with open(os.path.join(EV, TAG + "-perhex-capacity-timeseries.csv"), "w") as f:
        f.write("hex,day,capacityMilli\n")
        for h in sorted(pool_rounds, key=hexkey):
            for day, cap in pool_rounds[h]:
                f.write("%s,%s,%d\n" % (h, day, cap))

    # ── C：逐买方格 ────────────────────────────────────────────────────────
    buyer = collections.defaultdict(lambda: collections.Counter())
    for d in rows["CAPACITY_DEMAND_BUYER_HEX"]:
        c = buyer[d["buyerHex"]]
        c["rounds"] += 1
        c["demandInstances"] += num(d, "demandInstances")
        c["demandQuantityMilli"] += num(d, "demandQuantityMilli")
        c["servedQuantityMilli"] += num(d, "servedQuantityMilli")
        c["demandWorkMilli"] += num(d, "demandWorkMilli")
        c["servedWorkMilli"] += num(d, "servedWorkMilli")
    with open(os.path.join(EV, TAG + "-perhex-buyer.md"), "w") as f:
        f.write("# 逐买方格 派生运力需求（本期 = %s；CAPACITY_DEMAND_BUYER_HEX）\n\n" % win)
        f.write("| 买方格 | 轮数 | Σ需求实例 | Σ需求量(毫商品) | Σ已服务量 | Σ需求工作 | Σ已服务工作 |\n|---|---|---|---|---|---|---|\n")
        for h in sorted(buyer, key=hexkey):
            c = buyer[h]
            f.write("| %s | %d | %d | %d | %d | %d | %d |\n" % (
                h, c["rounds"], c["demandInstances"], c["demandQuantityMilli"],
                c["servedQuantityMilli"], c["demandWorkMilli"], c["servedWorkMilli"]))

    # ── D：具名拒绝（逐笔）──────────────────────────────────────────────────
    rej = collections.defaultdict(lambda: collections.Counter())
    rej_pairs = collections.Counter()
    for d in rows["LOGISTICS_SERVICE_SUPPLY_REJECTED"]:
        c = rej[d["fromHex"]]
        c["lines"] += 1
        c["truncatedMilli"] += num(d, "truncatedMilli")
        c["requestedMilli"] += num(d, "requestedMilli")
        rej_pairs[(d["fromHex"], d["toHex"], d["commodity"])] += num(d, "truncatedMilli")
    with open(os.path.join(EV, TAG + "-rejections.md"), "w") as f:
        f.write("# 具名业务拒绝（本期 = %s；LOGISTICS_SERVICE_SUPPLY_REJECTED，逐笔 TRACE 原始行）\n\n" % win)
        f.write("★ 口径：逐行 `truncatedMilli` 是**每次观察**的值，同一请求可能被多轮/多次试配反复观察\n"
                "（`MerchantCapacityPool` 类注：Σ 逐行**不可**当真实量）；本表按发货格给的是**行数与原始额**，\n"
                "权威净额以 `MARKET` 行的 `logisticsDetailQuantities` 为准。\n\n")
        f.write("| 发货格 | 行数 | ΣtruncatedMilli(原始) | ΣrequestedMilli(原始) |\n|---|---|---|---|\n")
        for h in sorted(rej, key=hexkey):
            c = rej[h]
            f.write("| %s | %d | %d | %d |\n" % (h, c["lines"], c["truncatedMilli"], c["requestedMilli"]))
        f.write("\n## 车道明细（发货格 → 收货格 / 商品）Top 25\n\n| from | to | commodity | ΣtruncatedMilli(原始) |\n|---|---|---|---|\n")
        for (a, b, cm), v in rej_pairs.most_common(25):
            f.write("| %s | %s | %s | %d |\n" % (a, b, cm, v))

    # ── E：逐轮 MARKET 汇总 ────────────────────────────────────────────────
    with open(os.path.join(EV, TAG + "-market-rounds.md"), "w") as f:
        f.write("# 逐轮 MARKET 汇总（本期 = %s）\n\n" % win)
        f.write("| day | trigger | fills | unfilled | reasons | logisticsDetailQuantities |\n|---|---|---|---|---|---|\n")
        tot_reasons = collections.Counter()
        tot_detail = collections.Counter()
        for d in rows["MARKET"]:
            reasons = d.get("reasons", "")
            detail = d.get("logisticsDetailQuantities", "")
            f.write("| %s | %s | %s | %s | %s | %s |\n" % (
                d.get("day"), d.get("trigger"), d.get("fills"), d.get("unfilled"),
                reasons.replace("|", "/"), detail.replace("|", "/")))
            for k, v in re.findall(r"(\w+)=(\d+)", reasons):
                tot_reasons[k] += int(v)
            for k, v in re.findall(r"([\w-]+)=(\d+)", detail):
                tot_detail[k] += int(v)
        f.write("\n## 本期合计（%s；%d 个市场轮）\n\n" % (win, len(rows["MARKET"])))
        f.write("| reasons 分档 | 合计 |\n|---|---|\n")
        for k, v in tot_reasons.most_common():
            f.write("| %s | %d |\n" % (k, v))
        f.write("\n| logisticsDetail 分档 | 合计（原始，可重复观察） |\n|---|---|\n")
        for k, v in tot_detail.most_common():
            f.write("| %s | %d |\n" % (k, v))

    # ── F：跑商/迁移 ───────────────────────────────────────────────────────
    with open(os.path.join(EV, TAG + "-merchant-and-migration.md"), "w") as f:
        f.write("# 跑商与迁移（本期 = %s）\n\n" % win)
        f.write("## MERCHANT_HAUL_RUN（逐笔承运，TRACE）\n\n")
        f.write("| day | household | pureMerchant | commodity | from | to | quantityMilli | serviceMilli | waivedFreightMilli |\n")
        f.write("|---|---|---|---|---|---|---|---|---|\n")
        for d in rows["MERCHANT_HAUL_RUN"]:
            f.write("| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n" % (
                d.get("day"), d.get("household"), d.get("pureMerchant"), d.get("commodity"),
                d.get("from"), d.get("to"), d.get("quantityMilli"), d.get("serviceMilli"),
                d.get("waivedFreightMilli")))
        f.write("\n## MIGRATION_MOVE / MIGRATION_PLAN / PRIMARY_MODE_*\n\n```\n")
        for ev in ("MIGRATION_PLAN", "MIGRATION_MOVE", "MIGRATION_APPLIED", "PRIMARY_MODE_CHANGED",
                   "PRIMARY_MODE_RANKING_EXCLUDED", "MODE_MIGRATION_BRIDGED"):
            for d in rows[ev]:
                f.write("%s %s\n" % (ev, {k: v for k, v in d.items() if k != "origin"}))
        f.write("```\n")
        f.write("\n## HAUL_SERVICE_*（逐轮对账）\n\n")
        f.write("| day | producedMilli | deliveredMilli | expiredMilli | trades | providers |\n|---|---|---|---|---|---|\n")
        for d in rows["HAUL_SERVICE_SETTLED"]:
            f.write("| %s | %s | %s | %s | %s | %s |\n" % (
                d.get("day"), d.get("producedMilli"), d.get("deliveredMilli"),
                d.get("expiredMilli"), d.get("trades"), d.get("providers")))

    # ── G：逐户池装配（谁在池里、运力多少）────────────────────────────────
    hh = collections.defaultdict(lambda: collections.Counter())
    for d in rows["MERCHANT_CAPACITY_HOUSEHOLD"]:
        c = hh[(d["hex"], d["household"])]
        c["rounds"] += 1
        c["capacityMilli"] += num(d, "capacityMilli")
        c["maxCapacityMilli"] = max(c["maxCapacityMilli"], num(d, "capacityMilli"))
        c["laborMilli"] = num(d, "laborMilli")
        c["toolMilli"] = num(d, "toolMilli")
        c["tier"] = d.get("tier")
        c["askPerMille"] = num(d, "askPerMille")
        if num(d, "capacityMilli") > 0:
            c["nonzeroRounds"] += 1
    with open(os.path.join(EV, TAG + "-perhousehold-pool.md"), "w") as f:
        f.write("# 逐户运力池条目（本期 = %s；MERCHANT_CAPACITY_HOUSEHOLD，每市场轮一行/户）\n\n" % win)
        f.write("| 格 | 家户 | 轮数 | ΣcapacityMilli | 最大 | 非零轮数 | laborMilli | toolMilli | tier | askPerMille |\n")
        f.write("|---|---|---|---|---|---|---|---|---|---|\n")
        for (h, hid), c in sorted(hh.items(), key=lambda kv: (hexkey(kv[0][0]), kv[0][1])):
            f.write("| %s | %s | %d | %d | %d | %d | %d | %d | %s | %d |\n" % (
                h, hid, c["rounds"], c["capacityMilli"], c["maxCapacityMilli"],
                c["nonzeroRounds"], c["laborMilli"], c["toolMilli"], c["tier"],
                c["askPerMille"]))

    print("事件行数：")
    for ev in sorted(rows):
        print("  %-34s %d" % (ev, len(rows[ev])))


if __name__ == "__main__":
    main()
