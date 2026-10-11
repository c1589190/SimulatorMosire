#!/usr/bin/env python3
"""户绝成因的**权威判定**：用 d600 checkpoint 里 social.populationEvents（创世起累计、逐户逐日逐因）
判每一次"人口 → 0"到底是**死亡**吃掉的、还是**转移/流动**搬走的。

读口（`store/checkpoints/main/<rev>.json` 全量信封，唯一来源）：
  modules.social.data.populationEvents[<id>] → {householdId, type, count, day, reason, source}
     · type ∈ DEATH / BIRTH / TRANSFER_IN / TRANSFER_OUT / WORK_ORDER
     · ★ 这是**历史累计**（创世起）窗口，与时点快照（classes.population）不是同一窗口，
       本脚本只在"某户在某日的死/生/移出/移入各多少"这一层把两者对齐，不做跨窗口并排比较。

用法：python3 dd_events.py <cp.json> <hh.jsonl.gz> [<out.json>]
"""
import gzip
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from ddlib import load, mod  # noqa: E402


def events_by_household_day(env):
    sd = mod(env, "social")
    ev = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    reason = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    for e in (sd.get("populationEvents") or {}).values():
        h = (e.get("householdId") or {}).get("value")
        if h is None:
            continue
        t, d, c = e.get("type"), e.get("day"), e.get("count", 0)
        ev[h][d][t] += c
        if t == "DEATH":
            r = e.get("reason") or ""
            cause = "FAMINE" if "FAMINE" in r else ("BASE" if "BASE" in r else "?")
            reason[h][d][cause] += c
    return ev, reason


def main():
    cp, hhgz = sys.argv[1], sys.argv[2]
    outp = sys.argv[3] if len(sys.argv) > 3 else None
    env = load(cp)
    ev, reason = events_by_household_day(env)

    hh = defaultdict(dict)
    with gzip.open(hhgz, "rt") as f:
        for line in f:
            r = json.loads(line)
            hh[r["d"]][r["hh"]] = r
    days = sorted(hh)
    ids = sorted({h for d in days for h in hh[d]})

    extinct = []
    for h in ids:
        prev = None
        for d in days:
            r = hh[d].get(h)
            if r is None:
                continue
            if r["pop"] == 0 and prev is not None and prev["pop"] > 0:
                extinct.append((h, d, prev["pop"]))
                break
            prev = r

    out = []
    for h, d0, popBefore in extinct:
        e = ev.get(h, {}).get(d0, {})
        deaths = e.get("DEATH", 0)
        tout = e.get("TRANSFER_OUT", 0)
        tin = e.get("TRANSFER_IN", 0)
        births = e.get("BIRTH", 0)
        loss = popBefore + tin + births - (hh[d0][h]["pop"])
        if deaths > 0 and tout == 0:
            cause = "death"
        elif tout > 0 and deaths == 0:
            cause = "transfer"
        elif deaths > 0 and tout > 0:
            cause = "mixed"
        else:
            cause = "unexplained"
        out.append({"hh": h, "zeroDay": d0, "popBefore": popBefore,
                    "dayDeaths": deaths, "dayTransferOut": tout, "dayTransferIn": tin,
                    "dayBirths": births, "loss": loss, "cause": cause,
                    "deathCauses": dict(reason.get(h, {}).get(d0, {}))})
    res = {"cp": os.path.abspath(cp), "hhGz": os.path.abspath(hhgz),
           "extinct": len(out), "byCause": {},
           "rows": out}
    for r in out:
        res["byCause"][r["cause"]] = res["byCause"].get(r["cause"], 0) + 1
    print(json.dumps({k: res[k] for k in ("extinct", "byCause")}, ensure_ascii=False))
    print("%-34s %5s %5s %5s %5s %5s %6s %s" %
          ("hh", "zero", "popB", "loss", "death", "tOut", "tIn", "cause"))
    for r in out:
        print("%-34s %5d %5d %5d %5d %5d %6d %s" %
              (r["hh"][:34], r["zeroDay"], r["popBefore"], r["loss"],
               r["dayDeaths"], r["dayTransferOut"], r["dayTransferIn"], r["cause"]))
    if outp:
        with open(outp, "w") as f:
            json.dump(res, f, ensure_ascii=False, indent=1, sort_keys=True)
        print("→", outp)


if __name__ == "__main__":
    main()
