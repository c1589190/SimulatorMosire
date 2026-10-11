#!/usr/bin/env python3
"""W7e 测试批：逐事件 × 逐窗口 × 逐 reason 的计数与小计（流式）。

用法：python3 eventsplit.py <log-file> <event> [<sum-field> ...] -- <a:b> <c:d> ...

例：python3 eventsplit.py logs/sw360.log MERCHANT_CAPACITY_LANE_TRUNCATED unallocatedMilli -- 0:360 361:600 601:689
输出每个窗口：总笔数、按 reason 的笔数、按 reason 的 Σ<sum-field>。
"""
import collections
import re
import sys

FIELD = re.compile(r'(\w+)=("[^"]*"|\{[^}]*\}|[^\s]*)')


def fields(line):
    return {m.group(1): m.group(2) for m in FIELD.finditer(line)}


def num(raw, default=0):
    try:
        return int(raw)
    except (TypeError, ValueError):
        return default


def main():
    path, event = sys.argv[1], sys.argv[2]
    rest = sys.argv[3:]
    cut = rest.index("--") if "--" in rest else len(rest)
    sum_fields = rest[:cut]
    windows = [(int(a), int(b)) for a, b in (w.split(":") for w in rest[cut + 1:])]

    def window_of(day):
        for a, b in windows:
            if a <= day <= b:
                return "%d:%d" % (a, b)
        return "other"

    count = collections.Counter()
    totals = {f: collections.Counter() for f in sum_fields}
    with open(path, "r", errors="replace") as fh:
        for line in fh:
            at = line.find("event=" + event)
            if at < 0:
                continue
            end = at + 6 + len(event)
            if end < len(line) and not line[end].isspace():
                continue  # 前缀命中（如 MARKET_REPORT vs MARKET_REPORT_X）
            f = fields(line)
            key = window_of(num(f.get("day"), -1)) + "|" + str(f.get("reason", "?"))
            count[key] += 1
            for sf in sum_fields:
                totals[sf][key] += num(f.get(sf))
    print("event = %s   窗口 = %s" % (event, windows))
    for key in sorted(count):
        extra = "".join("  Σ%s=%d" % (sf, totals[sf][key]) for sf in sum_fields)
        print("  %-58s 笔数=%-8d%s" % (key, count[key], extra))
    for w, _b in windows:
        label = "%d:%d" % (w, _b)
        n = sum(v for k, v in count.items() if k.startswith(label + "|"))
        extra = "".join(
            "  Σ%s=%d" % (sf, sum(v for k, v in totals[sf].items() if k.startswith(label + "|")))
            for sf in sum_fields)
        print("  [窗口小计 %s] 笔数=%d%s" % (label, n, extra))


if __name__ == "__main__":
    main()
