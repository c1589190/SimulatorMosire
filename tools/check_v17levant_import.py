#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_v17levant_import.py —— T11 的**对拍脚本**（`tools/` 不入 Maven reactor，故靠它自证）。

用法：
    python3 tools/check_v17levant_import.py <源完整档> <产出 resource.json> [<基础档 n0000_map.json>]

它做三件事：
  1. **资源 ↔ 源档逐值对拍**：hex 数 / 区域数 / 河流边数 / 地形直方图 / 区域 tag / 多对多从属样例。
  2. ★ **U4「最全」证明**（给了可选的第 3 个参数时）：源档 = `tools/materialize_v17levant.py`
     物化出的**截至 n0008 回合的最全区域**档；基础档 = `n0000_map.json`。断言若干净名单里的
     实名区域**在基础档没有、在源档与资源里都有**，且基础档区域数严格更少 ⇒ "最全"是实测。
  3. **导入器自身的行为自证**（给它牙齿）：融合优先级、交叉校验、lossy 表——这些是"扫描为空恒真"的
     反面：每条断言都先确认**被断言的东西非空**，再比数。

退出码：0 = 全部 PASS；1 = 有 FAIL。
"""

import json
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gsimap_import as gi  # noqa: E402

#: 源档（= `tools/materialize_v17levant.py` 物化出的**截至 n0008 回合的最全区域**档）的实测值
#: （逐条当场算过，写死作对拍基准）。★ U4：T11 用的是 n0000 基础图（98 区域），现改为此档（252 区域）。
EXPECTED_HEXES = 59223
EXPECTED_PROVINCES = 252
EXPECTED_EDGES = 240
EXPECTED_HISTOGRAM = {
    "ocean": 14927, "plains": 28347, "desert": 746, "low_hills": 14107, "mountains": 1096,
}
EXPECTED_TAGS = {"Nation": 252}
MULTI_OWNER_HEX = (-105, 67)
MULTI_OWNER_REGIONS = {"区域14", "石冠诸部"}

#: ★ 资源里**全部 252 个区域的逐格内容**的冻结摘要（sha256）。
#: 口径：区域 id 字典序，各区域 hex 按 (q,r) 排序拼 `q_r` ⇒ 每行 `id:q_r,q_r,…` ⇒ UTF-8 后 sha256。
#: 它比"只数区域个数"强：**任何**区域 hex 归属/增删/改名都会改摘要（例如"upsert 退化成 add-only"）。
EXPECTED_REGIONS_SHA256 = "44174bb9435eb498b4eb564fd3664703249e0a2a503f32f23a5776b72e187418"

#: ★ U4「最全」的干净名单：n0000 基础图**没有**、物化档/资源**有**的实名区域（逐字取自物化档）。
#: 它们只可能来自 n0001~n0007 的增量 ⇒ 在基础档里**缺失**即证明"用了更全的区域"。
NEW_NAMED_REGIONS = {
    "瓦伦狄乌斯专制国", "霜脊伯国", "大汉都护府政权", "艾达王国", "蒙特卡西诺修道院领",
}

_failures = []


def check(name, ok, detail=""):
    print("  [{}] {}{}".format("PASS" if ok else "FAIL", name, (" — " + detail) if detail else ""))
    if not ok:
        _failures.append(name)


def load_resource_map(path):
    with open(path, encoding="utf-8") as fh:
        envelope = json.load(fh)
    modules = envelope["modules"]
    return envelope, json.loads(modules["map"])["map"]


def simos_histogram(map_payload):
    hist = {}
    for cell in map_payload["hexes"].values():
        hist[cell["terrain"]] = hist.get(cell["terrain"], 0) + 1
    return hist


def owners_by_hex(map_payload):
    owners = {}
    for pid, region in map_payload["regions"].items():
        for h in region["hexes"]:
            owners.setdefault((h["q"], h["r"]), set()).add(pid)
    return owners


def regions_digest(map_payload):
    """区域逐格内容的 sha256（口径见 EXPECTED_REGIONS_SHA256）。"""
    lines = []
    for rid in sorted(map_payload["regions"]):
        hs = sorted((h["q"], h["r"]) for h in map_payload["regions"][rid]["hexes"])
        lines.append(rid + ":" + ",".join("{}_{}".format(q, r) for (q, r) in hs))
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def edges_are_adjacent_and_river(map_payload):
    hexes = set()
    for key in map_payload["hexes"]:
        hexes.add(gi.parse_hex(key))
    bad = 0
    non_river = 0
    for key, tags in map_payload["edges"].items():
        a, b = gi.parse_edge(key)
        if a not in hexes or b not in hexes:
            bad += 1
        dq, dr = b[0] - a[0], b[1] - a[1]
        if (dq, dr) not in gi.DIRECTIONS:
            bad += 1
        if set(tags["byPathway"].keys()) != {"river"}:
            non_river += 1
    return bad, non_river


def importer_self_tests():
    """导入器行为自证：每条都配一个**故意违规**的输入。"""
    hexes = {"0_0": {"terrain": "plains", "edgeTags": {"0": ["river"]}},
             "1_0": {"terrain": "plains", "riverMask": 32}}
    # ① 融合优先级：edges 空 ⇒ 回退 edgeTags（不是空表）；两表示一致 ⇒ 过交叉校验
    edges, source = gi.resolve_edges({}, hexes, {"0_0": {0}}, {"0_0": {0}})
    check("priority.falls-back-to-edgeTags-when-edges-empty", source == "edgeTags" and len(edges) == 1,
          "source={} edges={}".format(source, len(edges)))
    # ② edges 权威：edges 非空 ⇒ 直映、忽略另两份
    src_edges = {"0_0|1_0": {"byPathway": {"road": {}}}}
    edges2, source2 = gi.resolve_edges({"edges": src_edges}, hexes, {"0_0": {0}}, {"0_0": {0}})
    check("priority.edges-wins-when-present", source2 == "edges" and list(edges2) == ["0_0|1_0"],
          "source={}".format(source2))
    # ③ 交叉校验：edgeTags 与 riverMask 不一致 ⇒ 报错（不静默取一）
    try:
        gi.resolve_edges({}, hexes, {"0_0": {0}}, {"0_0": {5}})
        check("cross-check.rejects-mismatch", False, "不一致却通过了")
    except gi.ImportRejected as exc:
        check("cross-check.rejects-mismatch", "交叉校验失败" in str(exc), str(exc)[:40])
    # ④ lossy 表：lowland / swamp 都标 LOSSY 且都落 plains
    check("lossy.lowland-and-swamp-flagged",
          {"lowland", "swamp"} <= gi.LOSSY_KEYS
          and gi.TERRAIN_MAP["lowland"] == "plains" and gi.TERRAIN_MAP["swamp"] == "plains")


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) not in (2, 3):
        print(__doc__)
        return 1
    source_path, resource_path = argv[0], argv[1]
    base_path = argv[2] if len(argv) == 3 else None

    with open(source_path, encoding="utf-8") as fh:
        source = json.load(fh)
    base = None
    if base_path is not None:
        with open(base_path, encoding="utf-8") as fh:
            base = json.load(fh)
    envelope, map_payload = load_resource_map(resource_path)

    print("[check_v17levant_import] 源档   : {}".format(source_path))
    print("[check_v17levant_import] 资源   : {}".format(resource_path))
    print("[check_v17levant_import] 基础档 : {}".format(base_path if base_path else "(未给)"))

    # ── 装置自证：先确认对拍的两侧都非空（"扫描为空恒真"的反面） ──
    check("teeth.source-non-empty", len(source.get("hexes", {})) > 0,
          "source hexes={}".format(len(source.get("hexes", {}))))
    check("teeth.resource-non-empty", len(map_payload.get("hexes", {})) > 0,
          "resource hexes={}".format(len(map_payload.get("hexes", {}))))

    # ── 逐值对拍 ──
    hexes = len(map_payload["hexes"])
    check("hexes.count", hexes == EXPECTED_HEXES == len(source["hexes"]),
          "resource={} source={}".format(hexes, len(source["hexes"])))
    check("provinces.count", len(map_payload["regions"]) == EXPECTED_PROVINCES,
          "{}".format(len(map_payload["regions"])))
    digest = regions_digest(map_payload)
    check("provinces.region-content-digest",
          len(map_payload["regions"]) > 0 and digest == EXPECTED_REGIONS_SHA256,
          "{}".format(digest))
    check("edges.count", len(map_payload["edges"]) == EXPECTED_EDGES,
          "{}（源档 edgeTags 非空格 248 / 480 有向条目 ÷2）".format(len(map_payload["edges"])))
    check("histogram.matches-lossy-merge", simos_histogram(map_payload) == EXPECTED_HISTOGRAM,
          "{}".format(simos_histogram(map_payload)))

    tags = {}
    for region in map_payload["regions"].values():
        tag = region["meta"]["tag"]
        tags[tag] = tags.get(tag, 0) + 1
    check("regions.tags", tags == EXPECTED_TAGS, "{}".format(tags))

    owners = owners_by_hex(map_payload)
    sample = owners.get(MULTI_OWNER_HEX, set())
    check("regions.multi-owner-sample", sample == MULTI_OWNER_REGIONS,
          "{} -> {}".format(MULTI_OWNER_HEX, sorted(sample)))
    check("regions.has-at-least-one-multi-owner", sum(1 for o in owners.values() if len(o) > 1) > 0,
          "multi-owner hexes={}".format(sum(1 for o in owners.values() if len(o) > 1)))

    bad, non_river = edges_are_adjacent_and_river(map_payload)
    check("edges.adjacent-and-all-river", bad == 0 and non_river == 0,
          "bad={} nonRiver={}".format(bad, non_river))

    # ── ★ U4「最全」：给基础档时，证明资源用了 n0000 没有的增量区域 ──
    if base is not None:
        base_regions = set((base.get("provinces") or {}).keys())
        res_regions = set(map_payload["regions"].keys())
        present = sorted(NEW_NAMED_REGIONS & res_regions)
        absent_in_base = sorted(NEW_NAMED_REGIONS - base_regions)
        check("completeness.named-regions-present-in-resource",
              len(present) == len(NEW_NAMED_REGIONS),
              "{}/{} present".format(len(present), len(NEW_NAMED_REGIONS)))
        check("completeness.named-regions-absent-in-base",
              len(absent_in_base) == len(NEW_NAMED_REGIONS),
              "{}/{} absent in base".format(len(absent_in_base), len(NEW_NAMED_REGIONS)))
        check("completeness.base-has-fewer-regions",
              len(base_regions) < len(res_regions),
              "base={} resource={}".format(len(base_regions), len(res_regions)))

    # ── 导入器行为自证 ──
    importer_self_tests()

    print("[check_v17levant_import] 结果   : {}".format("OK" if not _failures else "FAIL"))
    if _failures:
        print("[check_v17levant_import] 失败项 : {}".format(", ".join(_failures)))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
