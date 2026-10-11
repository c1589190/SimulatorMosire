#!/usr/bin/env python3
"""从落盘 checkpoint 的 social 事件簿里抽出**逐户人口事件**（创世起历史累计）。

用法：python3 wr_events.py --cp <cp.json> --tag sw --out <dir>
产物：deaths-<tag>.json —— [{household, day, count, cause, reason, sex, ageBracketId}, ...]
"""
import argparse
import json
import os
import re
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cp", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    env = json.load(open(a.cp))
    S = json.loads(env["modules"]["social"])
    S = S.get("data", S.get("state", S))
    pe = S.get("populationEvents") or {}
    ev = []
    for v in pe.values():
        if v.get("type") != "DEATH":
            continue
        r = v.get("reason") or ""
        m = re.search(r"cause=([A-Z_]+)", r)
        fm = re.search(r"faminePpm=(-?\d+)", r)
        sr = re.search(r"starvationRatioPerMille=(-?\d+)", r)
        ev.append({"household": v["householdId"]["value"], "day": v.get("day"),
                   "count": v.get("count", 0),
                   "cause": m.group(1) if m else "UNLABELED",
                   "faminePpm": int(fm.group(1)) if fm else None,
                   "starvationPerMille": int(sr.group(1)) if sr else None,
                   "sex": v.get("sex"), "ageBracketId": v.get("ageBracketId"),
                   "source": v.get("source"), "reason": r})
    ev.sort(key=lambda e: (e["day"], e["household"]))
    os.makedirs(a.out, exist_ok=True)
    p = os.path.join(a.out, "deaths-%s.json" % a.tag)
    json.dump({"cp": os.path.abspath(a.cp), "revision": env["ref"]["revision"],
               "tick": env["timestamp"]["tick"], "n_events": len(ev),
               "total_deaths": sum(e["count"] for e in ev),
               "by_cause": dict(Counter(e["cause"] for e in ev)),
               "events": ev}, open(p, "w"), ensure_ascii=False, indent=1)
    print("wrote %s: events=%d deaths=%d causes=%s"
          % (p, len(ev), sum(e["count"] for e in ev),
             dict(Counter(e["cause"] for e in ev))))


if __name__ == "__main__":
    main()
