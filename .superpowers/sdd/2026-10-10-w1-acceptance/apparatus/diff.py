#!/usr/bin/env python3
"""W1 验收：两份投影状态的**逐叶深比对**（先形状投影，再逐值）。

用法：python3 diff.py <pre.json> <post.json> [--max 200] [--out diffs.json]
输出：差异条数 + 逐条路径（JSON pointer 风格）与前/后值；0 条 = 逐值相同。
"""
import argparse
import json
import sys


def walk(a, b, path, out, limit):
    if len(out) >= limit:
        return
    if type(a) is not type(b):
        out.append({"path": path, "pre": a, "post": b, "why": "type"})
        return
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a:
                out.append({"path": path + "/" + k, "pre": None, "post": b[k], "why": "post-only"})
            elif k not in b:
                out.append({"path": path + "/" + k, "pre": a[k], "post": None, "why": "pre-only"})
            else:
                walk(a[k], b[k], path + "/" + k, out, limit)
            if len(out) >= limit:
                return
    elif isinstance(a, list):
        if len(a) != len(b):
            out.append({"path": path, "pre": "len=%d" % len(a), "post": "len=%d" % len(b),
                        "why": "list-length"})
            return
        for i, (x, y) in enumerate(zip(a, b)):
            walk(x, y, "%s/%d" % (path, i), out, limit)
            if len(out) >= limit:
                return
    else:
        if a != b:
            out.append({"path": path, "pre": a, "post": b, "why": "value"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pre")
    ap.add_argument("post")
    ap.add_argument("--max", type=int, default=200)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    a = json.load(open(args.pre))
    b = json.load(open(args.post))
    diffs = []
    walk(a, b, "", diffs, args.max)
    print("差异叶：%d（上限 %d）" % (len(diffs), args.max))
    for d in diffs[:40]:
        print("  %s\n     pre =%s\n     post=%s" % (
            d["path"], json.dumps(d["pre"], ensure_ascii=False)[:220],
            json.dumps(d["post"], ensure_ascii=False)[:220]))
    if args.out:
        with open(args.out, "w") as f:
            json.dump(diffs, f, ensure_ascii=False, indent=1, sort_keys=True)
    sys.exit(0 if not diffs else 1)


if __name__ == "__main__":
    main()
