#!/usr/bin/env python3
"""W7e 测试批：装置 B —— 真世界日志里的**逐市场轮成交笔数分布**（与 EconomyCycleHealthTest 的指纹逐值对拍）。

用法：python3 fingerprint.py <log-file> <day-from> <day-to>

读数（全部标注窗口）：
  · 轮数 / 零成交轮数 / 同日重复 / 相邻间隔最大值 / Σfills / MARKET_FILL 行数（交叉验证）
  · 逐档分布 "笔数:轮数"（升序），min / 中位 / max
★ 与 EconomyCycleHealthTest.assertEveryMarketRoundTraded 的**五条牙同口径**：轮次数、同日不两轮、间隔 ≤ 5、
  零成交轮 = 0、Σ逐轮 fills == MARKET_FILL 行数（本脚本按窗口给同一组自检读数，用于对拍而不是替代测试）。
"""
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
    path, lo, hi = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    rounds = []
    fill_lines = 0
    with open(path, "r", errors="replace") as fh:
        for line in fh:
            at = line.find("event=")
            if at < 0:
                continue
            end = at + 6
            while end < len(line) and not line[end].isspace():
                end += 1
            event = line[at + 6:end]
            if event == "MARKET_REPORT":
                f = fields(line)
                day = num(f.get("day"), -1)
                if lo <= day <= hi:
                    rounds.append((day, num(f.get("fills"))))
            elif event == "MARKET_FILL":
                day = num(fields(line).get("day"), -1)
                if lo <= day <= hi:
                    fill_lines += 1
    rounds.sort()
    fills = [n for _d, n in rounds]
    dist = {}
    for n in fills:
        dist[n] = dist.get(n, 0) + 1
    days = [d for d, _n in rounds]
    gaps = [days[i] - days[i - 1] for i in range(1, len(days))]
    srt = sorted(fills)
    median = (srt[len(srt) // 2 - 1] + srt[len(srt) // 2]) / 2.0 if srt else None
    print("文件            = %s" % path)
    print("窗口（day 闭区间）= %d:%d" % (lo, hi))
    print("轮数            = %d" % len(rounds))
    print("零成交轮数      = %d" % sum(1 for n in fills if n == 0))
    print("同日两轮        = %s" % ("是 !! " if len(set(days)) != len(days) else "否"))
    print("相邻最大间隔(天) = %s" % (max(gaps) if gaps else None))
    print("min/中位/max    = %s / %s / %s" % (srt[0] if srt else None, median,
                                              srt[-1] if srt else None))
    print("Σfills          = %d" % sum(fills))
    print("MARKET_FILL 行数= %d  ⇒ %s" % (
        fill_lines, "一致（同源交叉验证通过）" if fill_lines == sum(fills) else "★ 不一致，必须查"))
    print("分布（笔数:轮数）= %s" % ", ".join("%d×%d" % (k, dist[k]) for k in sorted(dist)))


if __name__ == "__main__":
    main()
