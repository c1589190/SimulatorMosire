#!/usr/bin/env python3
"""把若干**已有 checkpoint 全量信封**转成与逐日读数同形的 JSONL（只读；给对拍与免重跑读数用）。

用法：
  python3 wr_fromdumps.py --out <jsonl> <cp.json> [<cp.json> ...]
  python3 wr_fromdumps.py --grid-dir <dir> --out <jsonl> --prefix sw600
"""
import argparse
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from w6lib import extract as extract_agg            # noqa: E402
from wrlib import extract_hh, gov_totals, debt_summary  # noqa: E402


def md5_file(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def row_for(path):
    env = json.load(open(path))
    day = env["timestamp"]["tick"]
    row = extract_agg(path, day=day)
    row["hh"] = extract_hh(path)
    row["gov"] = gov_totals(path)
    row["debt_s"] = debt_summary(path)
    row["cpMd5"] = md5_file(path)
    row["cpBytes"] = os.path.getsize(path)
    row["srcFile"] = os.path.abspath(path)
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--grid-dir", default=None)
    ap.add_argument("--prefix", default=None)
    ap.add_argument("files", nargs="*")
    a = ap.parse_args()
    files = list(a.files)
    if a.grid_dir:
        for f in sorted(os.listdir(a.grid_dir)):
            if f.endswith(".json") and (a.prefix is None or f.startswith(a.prefix)):
                files.append(os.path.join(a.grid_dir, f))
    rows = [row_for(p) for p in files]
    rows.sort(key=lambda r: r["day"])
    with open(a.out, "w") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    print("wrote %d rows to %s: days=%s" % (len(rows), a.out, [r["day"] for r in rows]))


if __name__ == "__main__":
    main()
