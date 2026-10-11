#!/usr/bin/env python3
"""W10-fix 读数器（只读装置；证据落本批目录）。

用法：
  python3 w10fix-read.py dump <dump.json> [...]                 # 逐 dump 打印 economy.meta.economyPolicies + md5
  python3 w10fix-read.py slides <log> <write-day> <slope-before> <slope-after>
                                                               # CLASS_SLIDE 逐行 + 斜率假设的算术自核
  python3 w10fix-read.py events <log> [name ...]                # 计数 + 首行样例
"""
import hashlib
import json
import math
import re
import sys


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def kv(line):
    """`... - event=NAME k=v k=v` ⇒ dict（值里的空格不出现：EventLog 用空格分隔）。"""
    out = {}
    for token in line.split(" - ", 1)[-1].split():
        if "=" in token:
            k, v = token.split("=", 1)
            out[k] = v
    return out


def cmd_dump(paths):
    for p in paths:
        d = json.load(open(p))
        eco = json.loads(d["modules"]["economy"])
        meta = eco["data"]["meta"]
        print("dump      : %s" % p)
        print("  file md5: %s  bytes=%s" % (md5_file(p), __import__("os").path.getsize(p)))
        ref = d["ref"]
        rev = ref["revision"]
        rev = rev["value"] if isinstance(rev, dict) else rev
        branch = ref["branch"]
        branch = branch["value"] if isinstance(branch, dict) else branch
        print("  tick    : %s  ref=%s@%s" % (d["timestamp"]["tick"], branch, rev))
        print("  meta    : %s" % json.dumps(meta, ensure_ascii=False, sort_keys=True))
        print("  policies: %s" % json.dumps(meta.get("economyPolicies"), ensure_ascii=False,
                                           sort_keys=True))
        print()


def cmd_slides(log, write_day, slope_before, slope_after):
    write_day = int(write_day)
    slopes = (int(slope_before), int(slope_after))
    rows = []
    with open(log, errors="replace") as f:
        for line in f:
            if "event=CLASS_SLIDE " not in line:
                continue
            r = kv(line)
            rows.append({
                "day": int(r["day"]),
                "household": r["household"],
                "ratio": int(r["debtRatioPerMille"]),
                "speed": int(r["speedPerMille"]),
                "requiredDays": int(r["requiredDays"]),
                "elapsedDays": int(r["elapsedDays"]),
                "debt": int(r["debtCommonValue"]),
                "asset": int(r["assetCommonValue"]),
            })
    print("CLASS_SLIDE 总行数 = %d" % len(rows))
    before = [r for r in rows if r["day"] <= write_day]
    after = [r for r in rows if r["day"] > write_day]
    print("  写入日(d%d)之前: %d 行（天窗口 %s..%s）" % (write_day, len(before),
          min([r["day"] for r in before], default="-"), max([r["day"] for r in before], default="-")))
    print("  写入日之后    : %d 行（天窗口 %s..%s）" % (len(after),
          min([r["day"] for r in after], default="-"), max([r["day"] for r in after], default="-")))
    print()
    # 逐窗口算术自核：speed == min(cap, floor(ratio*slope/1000)) 且 requiredDays == ceil(5*1000/speed)
    for label, group, slope in (("改前（slope=%d）" % slopes[0], before, slopes[0]),
                                ("改后（slope=%d）" % slopes[1], after, slopes[1])):
        if not group:
            print("%s: 无行" % label)
            continue
        ok_speed = sum(1 for r in group
                       if r["speed"] == r["ratio"] * slope // 1000)
        ok_days = sum(1 for r in group
                      if r["speed"] > 0
                      and r["requiredDays"] == math.ceil(5 * 1000 / r["speed"]))
        print("%s: %d 行；speed==⌊ratio×%d÷1000⌋ 命中 %d/%d；requiredDays==⌈5000÷speed⌉ 命中 %d/%d"
              % (label, len(group), slope, ok_speed, len(group), ok_days, len(group)))
        s = min(group, key=lambda r: r["day"])
        print("   首行: day=%d hh=%s ratio=%d speed=%d requiredDays=%d elapsedDays=%d"
              % (s["day"], s["household"], s["ratio"], s["speed"], s["requiredDays"],
                 s["elapsedDays"]))
        s = max(group, key=lambda r: r["day"])
        print("   末行: day=%d hh=%s ratio=%d speed=%d requiredDays=%d elapsedDays=%d"
              % (s["day"], s["household"], s["ratio"], s["speed"], s["requiredDays"],
                 s["elapsedDays"]))
    print()
    # 10× 指纹：同一条 ratio 落在两个窗口时的 speed 比值（取 before 里出现过的 ratio）
    print("同一 ratio 的 speed 改前 → 改后（取改前窗口里 speed>0 的行）:")
    seen = {}
    for r in after:
        seen.setdefault(r["ratio"], r)
    shown = 0
    for r in before:
        if shown >= 5:
            break
        a = seen.get(r["ratio"])
        if a is not None:
            print("  ratio=%d: speed %d(day%d, slope=%d) → %d(day%d, slope=%d) ＝ %.1f×  "
                  "requiredDays %d → %d"
                  % (r["ratio"], r["speed"], r["day"], slopes[0], a["speed"], a["day"],
                     slopes[1], a["speed"] / r["speed"] if r["speed"] else float("nan"),
                     r["requiredDays"], a["requiredDays"]))
            shown += 1
    if shown == 0:
        print("  （无同 ratio 配对；用上面的算术自核作为判据）")


def cmd_events(log, names):
    counts = {n: 0 for n in names}
    first = {}
    others = {"ERROR": 0}
    with open(log, errors="replace") as f:
        for line in f:
            if " ERROR " in line:
                others["ERROR"] += 1
            for n in names:
                if ("event=" + n + " ") in line or ("event=" + n + "\n") in line:
                    counts[n] += 1
                    first.setdefault(n, line.rstrip()[:600])
    for n in names:
        print("%-24s %d" % (n, counts[n]))
        if n in first:
            print("   例: %s" % first[n])
    print("%-24s %d" % ("ERROR 行", others["ERROR"]))


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "dump":
        cmd_dump(sys.argv[2:])
    elif mode == "slides":
        cmd_slides(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    elif mode == "events":
        cmd_events(sys.argv[2], sys.argv[3:] or ["SETTLEMENT_END"])
    else:
        raise SystemExit("未知模式: %s" % mode)
