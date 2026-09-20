#!/usr/bin/env python3
"""T11/T12 字面形式（视口分块传输）不可达性的量化证明。

输入：真档 19441 格上 /api/map/overview 的响应 JSON（紧凑编码版）。
输出：stdout 的实测数字（同时写 raw/bbox-chunking.json）。

论证目标：
  1) 载荷 99% 是**块多边形**（逐格地形已不存在 ⇒ "屏外 hex 不传"以**更强形式**达成：压根没有逐格通道）。
  2) 块是**同地形连通分量** ⇒ 最大块（plains，10496 格）的 bbox 覆盖全图 ~86% 面积，
     而 bbox 交集是"这块要不要传"的最宽判据 ⇒ 任一视口几乎必然命中它 ⇒ **分块几乎省不了**；
     且 pan 会在"跨块边界"时反复请求（当前 pan 的请求数 = 0，分块反而变差）。
  3) 给出量化上界：对全图 bbox 的各种视口尺寸，被请求块所带顶点的最小占比（最坏/平均）。
"""
import json
import sys
from itertools import product


def load(path):
    with open(path, "rb") as fh:
        return json.loads(fh.read())


def bbox(block):
    xs = [p["x"] for r in block["boundaries"] for p in r]
    ys = [p["y"] for r in block["boundaries"] for p in r]
    return min(xs), min(ys), max(xs), max(ys)


def main(path, out=None):
    raw = open(path, "rb").read()
    doc = json.loads(raw)
    blocks = doc["blocks"]
    total_verts = sum(len(r) for b in blocks for r in b["boundaries"])
    block_bytes = len(json.dumps(blocks, separators=(",", ":")).encode())
    non_block_bytes = len(raw) - block_bytes

    boxes = [(b["id"], bbox(b), sum(len(r) for r in b["boundaries"])) for b in blocks]
    gx0 = min(b[1][0] for b in boxes)
    gy0 = min(b[1][1] for b in boxes)
    gx1 = max(b[1][2] for b in boxes)
    gy1 = max(b[1][3] for b in boxes)
    gw, gh = gx1 - gx0, gy1 - gy0
    tot_area = gw * gh

    report = {
        "overviewBytes": len(raw),
        "blockBytes": block_bytes,
        "blockByteShare": round(block_bytes / len(raw), 4),
        "blockCount": len(blocks),
        "ringCount": sum(len(b["boundaries"]) for b in blocks),
        "vertexCount": total_verts,
        "worldW": round(gw, 2),
        "worldH": round(gh, 2),
    }

    # 最大块（按顶点数）
    biggest = max(boxes, key=lambda b: b[2])
    report["biggestBlock"] = {
        "id": biggest[0],
        "bboxAreaFraction": round(
            (biggest[1][2] - biggest[1][0]) * (biggest[1][3] - biggest[1][1]) / tot_area, 4
        ),
        "vertexShare": round(biggest[2] / total_verts, 4),
    }

    # 分块收益上界：视口面积占全图比例 f，遍历若干放置，计算"与视口 bbox 相交的块"的顶点占比。
    # 取所有放置中的**最小**占比 = 分块能省到的最好情况。
    grid = 8
    rows = []
    for f in (0.02, 0.05, 0.10, 0.25, 0.5, 1.0):
        vw, vh = gw * f ** 0.5, gh * f ** 0.5
        best = 1.0
        best_pos = None
        for i, j in product(range(grid), repeat=2):
            x0 = gx0 + (gw - vw) * i / (grid - 1)
            y0 = gy0 + (gh - vh) * j / (grid - 1)
            x1, y1 = x0 + vw, y0 + vh
            verts = sum(
                v
                for (_id, bx, v) in boxes
                if not (bx[2] < x0 or bx[0] > x1 or bx[3] < y0 or bx[1] > y1)
            )
            share = verts / total_verts
            if share < best:
                best = share
                best_pos = (round(x0, 1), round(y0, 1))
        rows.append(
            {
                "viewportAreaFraction": f,
                "minFetchedVertexShare": round(best, 4),
                "at": best_pos,
            }
        )
    report["chunkingBestCase"] = rows

    print(json.dumps(report, ensure_ascii=False, indent=2))
    out = out or (path.rsplit("/", 1)[0] + "/raw-bbox-chunking.json")
    with open(out, "w") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print("wrote", out, file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
