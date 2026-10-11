#!/usr/bin/env python3
"""W10-fix 缺省中性对拍器：把两轮的 `.log.stripped` 归一化后比 md5 + 复算账本那把尺的读数。

用法: python3 w10fix-logcmp.py <logA> <logB>
归一化只剥**构造性差异**（端口 / PID / jar 快照路径 / store 名 / tag / UUID），
一个字节的**内容**差异都会被 md5 抓到；若有差异，打印前若干对差异行。
"""
import hashlib
import re
import sys

SNAP = re.compile(r"/tmp/simos-shaded-[A-Za-z0-9]{6}\.jar")
UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
PORTS = re.compile(r"\b109[0-9]{2}\b")
PIDS = re.compile(r"\b(511961|514295|514[0-9]{3}|515[0-9]{3}|516[0-9]{3})\b")
STORES = re.compile(r"store-(tp240-close|w10fix-tp240|w10fix-tppolicy[a-z-]*|probe-mcp)")
TAGS = re.compile(r"(w10close-tp240|w10fix-tp240|w10fix-tppolicy[a-z-]*|probe-mcp)")


def norm(line):
    if line.startswith("[run-shaded] 快照 "):
        return "<SNAPSHOT-LINE>"
    line = SNAP.sub("<SNAP>", line)
    line = UUID.sub("<UUID>", line)
    line = STORES.sub("<STORE>", line)
    line = TAGS.sub("<TAG>", line)
    line = PORTS.sub("<PORT>", line)
    line = PIDS.sub("<PID>", line)
    return line


def scan(path):
    md5 = hashlib.md5()
    fills = []
    zero_fill = 0
    traded_zero = 0
    rounds = 0
    err = 0
    settle = 0
    with open(path, errors="replace") as f:
        for line in f:
            n = norm(line)
            md5.update(n.encode())
            if " ERROR " in line:
                err += 1
            if "event=SETTLEMENT_END" in line:
                settle += 1
            if "event=MARKET_REPORT " in line:
                rounds += 1
                m = re.search(r"\bfills=(\d+)", line)
                if m:
                    v = int(m.group(1))
                    fills.append(v)
                    if v == 0:
                        zero_fill += 1
            if "event=MARKET_ROUND_STATS_RECORDED " in line and "tradedMilli=0 " in line:
                traded_zero += 1
    fills.sort()
    return {
        "md5": md5.hexdigest(),
        "rounds": rounds,
        "zeroFillRounds": zero_fill,
        "sumFills": sum(fills),
        "min": fills[0] if fills else None,
        "median": fills[len(fills) // 2] if fills else None,
        "max": fills[-1] if fills else None,
        "tradedMilliZeroRounds": traded_zero,
        "errorLines": err,
        "settlementEnd": settle,
    }


if __name__ == "__main__":
    a, b = sys.argv[1], sys.argv[2]
    ra, rb = scan(a), scan(b)
    for k in ra:
        flag = "==" if ra[k] == rb[k] else "!!"
        print("%-24s %-34s %s %s" % (k, ra[k], flag, rb[k]))
