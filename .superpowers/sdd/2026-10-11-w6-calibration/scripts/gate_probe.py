#!/usr/bin/env python3
"""W6 标定批：⑤ 转业人口闸（MIGRATION_SPEED_CAP_PER_MILLE=100）吃紧比例的**逐值判别**（只读 store）。

做法（不需要补充逐日读数）：
  1. 从日志找出所有 `speedPerMille > 100` 的 MIGRATION_MOVE（闸门只有在 speed > 100 时才**可能**生效）；
  2. 对每一笔，从 store 的 `revisions` 表取**该 tick 的 changeset**：
       · `modules.economy.classes.entries[源户].population` = 该 tick 结算后的逐户人口
       · `modules.economy.flows.entries[源户].{births,deaths}` = 该日该户的生/死
     ⇒ 迁出前人口 pre = post − births + deaths；
  3. 逐值复算 src/main 的算式（ModeMigrationPolicy.moveablePopulation）：
       speedMoveable = max(1, ⌊pre × speed ÷ 1000⌋)   （夹到 [0, pre]）
       capMoveable   = max(1, ⌊pre × 100  ÷ 1000⌋)
     闸门**生效** ⇔ capMoveable < speedMoveable；**被闸门限制** ⇔ 该户当日 Σ迁出 ≥ capMoveable。

用法：python3 gate_probe.py <store-dir> <log> [<store-dir> <log> ...]
"""
import json
import re
import sqlite3
import sys
from collections import defaultdict


def field(line, key, cast=str, default=None):
    m = re.search(r"(?:^|\s)%s=(\S+)" % re.escape(key), line)
    if not m:
        return default
    try:
        return cast(m.group(1))
    except (TypeError, ValueError):
        return default


def moveable(pop, speed):
    if pop <= 0 or speed <= 0:
        return 0
    return min(pop, max(1, pop * speed // 1000))


def probe(store, log, cap=100):
    moves = []
    for line in open(log, "r", encoding="utf-8", errors="replace"):
        if "event=MIGRATION_MOVE" not in line:
            continue
        moves.append(dict(day=field(line, "day", int, -1), src=field(line, "source"),
                          pop=field(line, "population", int, 0),
                          speed=field(line, "speedPerMille", int, 0)))
    hot = [m for m in moves if m["speed"] > cap]
    print("=" * 100)
    print("%s：MIGRATION_MOVE = %d 笔；speed > %d‰ 的笔数 = %d" % (store, len(moves), cap, len(hot)))
    print("=" * 100)
    by = defaultdict(list)
    for m in hot:
        by[(m["day"], m["src"])].append(m)
    con = sqlite3.connect("file:%s/simos.db?mode=ro" % store, uri=True)
    tight = capped = 0
    for (day, hh), ms in sorted(by.items()):
        row = con.execute(
            "SELECT changeset_json FROM revisions WHERE tick=? ORDER BY revision DESC LIMIT 1",
            (day,)).fetchone()
        if row is None:
            print("  d%-4d %-40s 取不到 tick=%d 的 changeset" % (day, hh, day))
            continue
        eco = json.loads(row[0])["modules"]["economy"]
        cl = (eco.get("classes") or {}).get("entries", {}).get(hh)
        fl = (eco.get("flows") or {}).get("entries", {}).get(hh) or {}
        if cl is None:
            print("  d%-4d %-40s 该 tick 的 classes 里没有这一户（取不到人口）" % (day, hh))
            continue
        post = cl.get("population")
        pre = post - (fl.get("births") or 0) + (fl.get("deaths") or 0)
        speed = max(m["speed"] for m in ms)
        moved = sum(m["pop"] for m in ms)
        sm, cm = moveable(pre, speed), moveable(pre, cap)
        binds = cm < sm
        tight += 1 if binds else 0
        capped += 1 if (binds and moved >= cm) else 0
        print("  d%-4d %-42s speed=%-4d post=%-4s births=%-2s deaths=%-2s ⇒ pre=%-4d" % (
            day, hh, speed, post, fl.get("births"), fl.get("deaths"), pre))
        print("        Σ迁出=%d；speedMoveable=%d capMoveable=%d ⇒ 闸门%s%s" % (
            moved, sm, cm, "生效" if binds else "不生效",
            "，且 Σ迁出 ≥ capMoveable（结果被闸门限制）" if (binds and moved >= cm) else
            ("，但 Σ迁出 < capMoveable（结果未被闸门限制）" if binds else "")))
        # 人口敏感性：pre ± 3 邻域结论是否稳定
        stable = all((moveable(pre + k, cap) < moveable(pre + k, speed)) == binds for k in range(-3, 4))
        print("        ★ pre±3 邻域结论%s" % ("稳定" if stable else "**不稳定**（需更精确的人口时点）"))
    print("  ⇒ 闸门**在算术上生效**的 户×轮 = %d；其中结果真被闸门限制 = %d；总笔数 = %d" % (tight, capped, len(moves)))
    print("  ⇒ 口径：闸门生效比例 = %d/%d 笔 = %.3f%%（上界）;  %d/%d 户×轮" % (
        len(hot), len(moves), 100.0 * len(hot) / len(moves) if moves else 0, tight, len(by)))


if __name__ == "__main__":
    args = sys.argv[1:]
    for i in range(0, len(args), 2):
        probe(args[i], args[i + 1])
