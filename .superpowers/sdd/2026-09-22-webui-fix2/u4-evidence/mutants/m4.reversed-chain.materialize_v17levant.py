#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""materialize_v17levant.py —— 把 v17levant 的 **MapDiff 增量**物化成一份**完整 map.json**（U4）。

背景（U4，用户原话）：「为啥不用截至 n0008 回合的最全所有区域？n0000 的区域构成是缺的」。
T11 复刻用的是 `n0000_map.json`（回合 0 的**基础图**，只有 **98** 个区域）；
`n0001~n0007` 是逐回合的 `*_map_diff.json`（MapDiff 增量），`n0008.json` 是**空壳**
（`checkpoints={}` / `attachments={}`）⇒ 「截至 n0008 回合」的最终区域构成只能**逐 diff 累积**出来。
而 `tools/gsimap_import.py` **拒绝 MapDiff 输入**（顶部 fail-closed：顶层含 `parentNodeId/changed`
即报错）⇒ 必须先在本脚本里物化出一份完整 `map.json`，再交给导入器。

用法：
    python3 tools/materialize_v17levant.py <nodes_dir> <out_map.json>

`<nodes_dir>` 需含 `n0000_map.json` 与 `n0001_map_diff.json` … `n0007_map_diff.json`。

★★ 应用口径（**与 GSimulator 权威实现 `MapResolver.applyDiff` 的 province 段对齐**，`MapResolver.java:224-230`）：
    for p in provinces_removed:  provinces.pop(p)
    for (k, v) in provinces_changed: provinces[k] = v      # upsert = **整对象全量替换**
且 **按 diff 的先后顺序**（n0001 → n0007）逐个应用。

★★ **只物化区域（provinces）**——这是**有裁定的范围**，不是漏做：
  - U4 的对象就是「最全的**区域**构成」；判据锁定 **hex 数不变（59223）/ 地形直方图不变 / 河流边数不变
    （240）**。这三条只有在**不应用 hex 段**时才成立（实测：若连 hex 段一起应用，地形直方图
    会变（`hills 14107→16489`…）、河流有向条目 `480→894`）。
  - GSimulator 的 `applyDiff` **也**应用 hex 段（`removed`/`changed`）；本项目**有意不应用**，
    因为本次交付只是"把区域换成截至 n0008 的最全版本"，地形/连通性仍取 n0000 基础图。
    ⇒ 这是一处**显式取舍**，记在 `u4-evidence/u4-report.md`。

退出码：0 = 成功；1 = 参数/IO/JSON 错；2 = fail-closed 拒绝（diff 链断裂 / 缺文件）。
"""

import argparse
import json
import os
import sys

#: 应用的 diff 链（n0008 是空壳，**不含**它——它自己的 checkpoints 为空，等价于 n0007 的终态）。
DIFF_NODES = ["n0001", "n0002", "n0003", "n0004", "n0005", "n0006", "n0007"]

#: 根/基础图节点 id。
BASE_NODE = "n0000"


class MaterializeRejected(Exception):
    """fail-closed 拒绝（退出码非 0）。"""


def load_json(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        raise MaterializeRejected("缺文件: {}".format(path))
    except json.JSONDecodeError as exc:
        raise MaterializeRejected("不是合法 JSON: {} ({})".format(path, exc))


def apply_diff(base, diff, expected_parent, node_id):
    """按 GSimulator 口径对 **provinces** 应用一份 MapDiff（先 remove 再 put）。

    ★ 只碰 `provinces`：hex 段（`removed`/`changed`）**有意不应用**（见模块 docstring）。
    """
    if diff.get("parentNodeId") != expected_parent:
        raise MaterializeRejected(
            "{} 的 parentNodeId={!r} != 期望 {!r}（diff 链断裂，拒绝拼装）".format(
                node_id, diff.get("parentNodeId"), expected_parent))

    provinces = base["provinces"]

    removed = diff.get("provinces_removed") or []
    if not isinstance(removed, list):
        raise MaterializeRejected("{} 的 provinces_removed 不是数组".format(node_id))
    changed = diff.get("provinces_changed") or {}
    if not isinstance(changed, dict):
        raise MaterializeRejected("{} 的 provinces_changed 不是对象".format(node_id))

    # ★ 口径：**先 remove，再 put**（put 是 upsert / 整对象全量替换）。
    for pid in removed:
        provinces.pop(pid, None)
    for pid, province in changed.items():
        if not isinstance(province, dict):
            raise MaterializeRejected("{} 的 province {} 不是对象".format(node_id, pid))
        provinces[pid] = province

    return len(removed), len(changed)


def materialize(nodes_dir):
    """读基础图 + 依次应用 diff 链，返回 (map_obj, 统计)。"""
    base = load_json(os.path.join(nodes_dir, BASE_NODE + "_map.json"))
    if "parentNodeId" in base or "changed" in base:
        raise MaterializeRejected("基础图 {}_map.json 本身是 MapDiff，不是 full map".format(BASE_NODE))
    if "provinces" not in base or not isinstance(base["provinces"], dict):
        raise MaterializeRejected("基础图缺 provinces 对象")

    stats = {"base_provinces": len(base["provinces"]), "diffs": []}
    prev = BASE_NODE
    for node_id in reversed(DIFF_NODES):
        diff = load_json(os.path.join(nodes_dir, node_id + "_map_diff.json"))
        n_removed, n_changed = apply_diff(base, diff, prev, node_id)
        stats["diffs"].append(
            {"node": node_id, "removed": n_removed, "changed": n_changed,
             "provinces_after": len(base["provinces"])})
        prev = node_id
    stats["final_provinces"] = len(base["provinces"])
    return base, stats


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="v17levant MapDiff 增量 → 完整 map.json（只物化 provinces，U4）")
    parser.add_argument("nodes_dir", help="含 n0000_map.json 与 n000X_map_diff.json 的目录")
    parser.add_argument("out_map", help="输出完整 map.json 路径")
    args = parser.parse_args(argv)

    try:
        map_obj, stats = materialize(args.nodes_dir)

        out_dir = os.path.dirname(os.path.abspath(args.out_map))
        os.makedirs(out_dir, exist_ok=True)
        with open(args.out_map, "w", encoding="utf-8") as fh:
            json.dump(map_obj, fh, ensure_ascii=False)

        print("[materialize_v17levant] 输入目录 : {}".format(os.path.abspath(args.nodes_dir)))
        print("[materialize_v17levant] 输出     : {}".format(os.path.abspath(args.out_map)))
        print("[materialize_v17levant] 基础 provinces : {}".format(stats["base_provinces"]))
        for d in stats["diffs"]:
            print("[materialize_v17levant]   {}  removed={:<3} changed={:<3} ⇒ provinces={}".format(
                d["node"], d["removed"], d["changed"], d["provinces_after"]))
        print("[materialize_v17levant] 最终 provinces : {}".format(stats["final_provinces"]))
        print("[materialize_v17levant] 结果 : OK")
        return 0
    except MaterializeRejected as exc:
        print("[materialize_v17levant] 结果 : REJECTED (fail-closed) — {}".format(exc))
        return 2
    except (OSError, KeyError, TypeError) as exc:
        print("[materialize_v17levant] 结果 : ERROR — {}: {}".format(type(exc).__name__, exc))
        return 1


if __name__ == "__main__":
    sys.exit(main())
