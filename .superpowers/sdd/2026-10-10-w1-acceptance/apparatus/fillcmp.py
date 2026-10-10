#!/usr/bin/env python3
"""W1 验收：逐笔成交（MARKET_FILL）两侧对拍。

用法：python3 fillcmp.py <pre.log> <post.log>
口径：去掉行首时间戳（HH:MM:SS.mmm）后的整行逐字节比较（含全部字段：day/from/to/commodity/seller/buyer/
quantity/unitPriceMilli/goodsPaymentMilli/freightMilli/immediate...）；另给逐商品汇总（笔数/量/额）。
"""
import re
import sys
from collections import defaultdict

TS = re.compile(r"^\d{2}:\d{2}:\d{2}\.\d{3} ")


def fills(path):
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "event=MARKET_FILL " not in line:
                continue
            out.append(TS.sub("", line.rstrip("\n")))
    return out


def field(line, name):
    m = re.search(r"(?:^| )%s=([^ ]*)" % re.escape(name), line)
    return m.group(1) if m else None


def agg(lines):
    a = defaultdict(lambda: {"count": 0, "quantity": 0, "goodsPaymentMilli": 0,
                             "freightMilli": 0, "byPayCurrency": defaultdict(int),
                             "byUnitPrice": defaultdict(int)})
    for ln in lines:
        c = field(ln, "commodity")
        a[c]["count"] += 1
        a[c]["quantity"] += int(field(ln, "quantity") or 0)
        a[c]["goodsPaymentMilli"] += int(field(ln, "goodsPaymentMilli") or 0)
        a[c]["freightMilli"] += int(field(ln, "freightMilli") or 0)
        a[c]["byPayCurrency"][field(ln, "paymentCurrency")] += int(field(ln, "goodsPaymentMilli") or 0)
        a[c]["byUnitPrice"][field(ln, "unitPriceMilli")] += 1
    return {k: {**v, "byPayCurrency": dict(v["byPayCurrency"]),
                "byUnitPrice": dict(v["byUnitPrice"])} for k, v in a.items()}


def main():
    pre, post = sys.argv[1], sys.argv[2]
    a, b = fills(pre), fills(post)
    print("MARKET_FILL 笔数：改前 %d / 改后 %d  ⇒ %s" % (len(a), len(b), "相同" if len(a) == len(b) else "★不同★"))
    diff = [i for i, (x, y) in enumerate(zip(a, b)) if x != y]
    print("逐行逐字节不同：%d 行（比较前 %d 行）" % (len(diff), min(len(a), len(b))))
    for i in diff[:5]:
        print("  第 %d 笔：\n    pre =%s\n    post=%s" % (i + 1, a[i][:260], b[i][:260]))
    print()
    ga, gb = agg(a), agg(b)
    print("%-8s %8s %8s %18s %18s %14s %14s" %
          ("商品", "笔pre", "笔post", "量pre", "量post", "货款pre", "货款post"))
    for c in sorted(set(ga) | set(gb)):
        x, y = ga.get(c, {}), gb.get(c, {})
        print("%-8s %8d %8d %18d %18d %14d %14d" %
              (c, x.get("count", 0), y.get("count", 0), x.get("quantity", 0), y.get("quantity", 0),
               x.get("goodsPaymentMilli", 0), y.get("goodsPaymentMilli", 0)))
    print()
    print("汇总相同：%s" % (ga == gb))
    if ga != gb:
        for c in sorted(set(ga) | set(gb)):
            if ga.get(c) != gb.get(c):
                print("  商品 %s: pre=%s post=%s" % (c, ga.get(c), gb.get(c)))
    # 整块顺序也逐字节比一次
    print("全序列逐字节相同：%s" % (a == b))


if __name__ == "__main__":
    main()
