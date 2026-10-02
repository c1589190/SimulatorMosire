#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gsimap_import.py —— 旧 GSimulator `*_map.json` → simos 可读数据集（M6 一次性迁移脚本）。

用法：
    python3 tools/gsimap_import.py <input_map.json> <outDir>

产出：
    <outDir>/simos.db                        —— 按 SqliteStore 的冻结 DDL 建 2 表 4 索引 + 恰一行创世 revision
    <outDir>/checkpoints/main/1.json         —— (main, 1) 的 checkpoint 信封（modules 的 value 是字符串）

设计约束（见任务书与 CLAUDE.md）：
  * 标准库 only；零 Java 改动。
  * 只吃 **root full map**（含 parentNodeId/changed 的是 MapDiff，一律拒）。
  * 任何映射表外的在用 terrain key ⇒ 报错退出（fail-closed，不猜语义）。
  * ★ **输出 `terrainTypes` = 在用的 ∪ `ALWAYS_EMITTED_TERRAIN_KEYS`**（2026-09-24 起含 `plateau`）：
    调色板只列世界词表里的地形，只输出在用者会让「平缓高原」永远画不出来。
    高度：地形带中点；★ 沙漠取**平原中点 + 0.005**（与 simos 侧 `TerrainHeights.paintHeight` 同口径）。
  * 丢弃的键各自打印一行理由，不静默。
  * ★ **连通性融合优先级**（T11 / spec §五.2 / 裁定 D12）：`edges`（权威）> `edgeTags` > `riverMask`。
    - `edges` 非空 ⇒ 直映（M6 行为）；
    - `edges` 空而 `edgeTags` 非空 ⇒ 以 `edgeTags` 为准，`riverMask` **仅交叉校验**（不一致 ⇒ 报错，不静默取一）；
    - 两者皆空而 `riverMask` 非空 ⇒ 以 `riverMask` 为准；
    - 三者皆空 ⇒ 无连通性。
    `edgeTags[dir] → EdgeRef` 是**忠实转换**：方向码 0~5 = E,SE,SW,W,NW,NE（旧仓 `MapData.HexCell` 的
    `riverMask` 注释原文），kind 取 legacy 的组名（`v17levant` 里只有 `river`）。

权威依据（逐条对照，勿凭记忆改）：
  SqliteStore.java / CoreSimos.java / CheckpointStore.java / CheckpointEncoder.java / Envelope.java /
  Replay.java / MapCodec.java / Region.java / RegionBoundary.java / HexVertex.java / HexDirection.java /
  TerrainCatalog.java / PathwayGroup.java / EdgeTags.java / GenerationSpec.java /
  以及真样本 /tmp/t9b-demo-store/checkpoints/main/1.json。
"""

import argparse
import hashlib
import json
import math
import os
import sqlite3
import sys
import uuid

# ─────────────────────────────────────────────────────────────────────────────
# 1. 地形：旧 key → simos key（U1 已把旧 9 项表整个作废，属性一套取 simos 的）
# ─────────────────────────────────────────────────────────────────────────────

#: 显式映射表（任务书 §3 已裁决）。表外的在用 key ⇒ fail-closed。
TERRAIN_MAP = {
    "water": "ocean",
    "lowland": "plains",
    "plains": "plains",
    "desert": "desert",
    "hills": "low_hills",
    "mountain": "mountains",
    "swamp": "plains",  # ★ lossy：simos 词表是纯高度带的，没有湿地
}

#: lossy 映射（导入报告要逐条打印计数）。
#: ★ T11 补入 `lowland`：simos 的 7 类地形是**纯高度带**的，没有低地/湿地两档。
#: `v17levant` 里 `lowland` 有 16933 格（28.6%）——不标 LOSSY 就是"未声明的静默丢失"。
LOSSY_KEYS = {"swamp", "lowland"}

# ─────────────────────────────────────────────────────────────────────────────
# 1b. 重建模式（D-013 / D-011-R4 前置）：源是 GSim `ContourQueryEngine` 的**再生词表**，
#     与旧图词表**语义相反**——GSim 当前的 `plains` 是"山区/高原"、`mountain` 是"高山"。
#     ★ 重建模式的高度**逐格取自 `RegenSamples` 的 `height`**（不再用高度带中点）；
#       旧图词表的 `plains→plains` 映射在重建里必须改成 `plains→plateau`，否则高原仍会消失。
# ─────────────────────────────────────────────────────────────────────────────

REBUILD_TERRAIN_MAP = {
    "water": "ocean",
    "lowland": "plains",
    "hills": "low_hills",
    "plains": "plateau",      # ★ 关键差别：GSim 当前 plains = 山区/高原
    "mountain": "mountains",
    "desert": "desert",
    "swamp": "plains",        # ★ lossy（simos 词表无湿地）
    "forest": "plains",       # ★ lossy（simos 词表无森林）
    "tundra": "plains",       # ★ lossy（simos 词表无冻土）
}

#: 重建模式的 lossy 映射（逐类打印计数）。
REBUILD_LOSSY_KEYS = {"swamp", "forest", "tundra"}

#: simos 的 7 类地形，整套取自 TerrainCatalog.defaults()（键序 = 高度升序 = 落盘顺序）。
#: 字段名与 TerrainType 的 record 组件一一对应。
TERRAIN_CATALOG = {
    "ocean": {
        "key": "ocean", "name": "海洋", "color": "#1F5FA0",
        "minHeight": 0.00, "maxHeight": 0.30,
        "food": 0, "gold": 0, "stone": 0, "moveCost": 999,
        "description": "海滨与内海的水体，不可通行、无产出",
    },
    "plains": {
        "key": "plains", "name": "平原", "color": "#9CCB5B",
        "minHeight": 0.30, "maxHeight": 0.45,
        "food": 3, "gold": 0, "stone": 0, "moveCost": 1,
        "description": "可耕作的核心地带，产能最高、最好走",
    },
    "desert": {
        "key": "desert", "name": "沙漠", "color": "#E7C86E",
        "minHeight": 0.45, "maxHeight": 0.55,
        "food": 0, "gold": 1, "stone": 1, "moveCost": 3,
        "description": "干旱带上的贫瘠地形，产出少而难走",
    },
    "low_hills": {
        "key": "low_hills", "name": "低矮丘陵", "color": "#A8B36A",
        "minHeight": 0.55, "maxHeight": 0.65,
        "food": 2, "gold": 1, "stone": 1, "moveCost": 2,
        "description": "低地与山地的过渡带，产量中等、略难走",
    },
    "mountains": {
        "key": "mountains", "name": "山地", "color": "#7A7F85",
        "minHeight": 0.65, "maxHeight": 0.78,
        "food": 0, "gold": 2, "stone": 3, "moveCost": 6,
        "description": "石与矿富集，很难走",
    },
    "plateau": {
        "key": "plateau", "name": "平缓高原", "color": "#B99B6B",
        "minHeight": 0.78, "maxHeight": 0.85,
        "food": 1, "gold": 1, "stone": 1, "moveCost": 4,
        "description": "海拔高但地势平坦，相对好走",
    },
    "plateau_mountains": {
        "key": "plateau_mountains", "name": "高原山地", "color": "#68798C",
        "minHeight": 0.85, "maxHeight": 1.00,
        "food": 0, "gold": 1, "stone": 2, "moveCost": 12,
        "description": "海拔最高处，几乎不可通行",
    },
}

#: 缺失 height 的推导：所映射到的 simos 地形高度带的**中点**（确定性）。
#: ★ 2026-09-24：沙漠改为**平原带中点 + 0.005**（与 simos 侧 TerrainHeights.paintHeight 同一口径——
#:   涂色与导入必须写出同一个数，否则同一格因来源不同读出两个高度）。
def representative_height(simos_key):
    t = TERRAIN_CATALOG[simos_key]
    if simos_key == "desert":
        plains = TERRAIN_CATALOG["plains"]
        return (plains["minHeight"] + plains["maxHeight"]) / 2.0 + 0.005
    return (t["minHeight"] + t["maxHeight"]) / 2.0


#: ★ 2026-09-24：世界词表**恒带**的地形（即便本档没有一格用它）。
#:   由来：用户要用手绘/油漆桶补画「平缓高原」，而调色板只列世界词表里有的地形 ⇒
#:   若按"只输出在用者"，高原就**永远画不出来**（词表外 ⇒ 调色板没有；硬画又会缺颜色与移动成本）。
#:   `plateau_mountains`（高原山地）**有意不在此列**：用户裁定「山就是山，不用区分高原山地与平缓高原」，
#:   它是生成器内部的最高带，不作为世界地形。
ALWAYS_EMITTED_TERRAIN_KEYS = ("plateau",)

# ─────────────────────────────────────────────────────────────────────────────
# 2. 六角几何：HexDirection / HexVertex / RegionBoundary 的精确移植
# ─────────────────────────────────────────────────────────────────────────────

#: HexDirection.ALL 的枚举序（索引即边序号）：E, SE, SW, W, NW, NE。
DIRECTIONS = [(1, 0), (0, 1), (-1, 1), (-1, 0), (0, -1), (1, -1)]

#: HexVertex 的 U/W 偏移（corner ∈ [0,6)）。
_VERTEX_U = [1, 1, 0, -1, -1, 0]
_VERTEX_W = [-1, 1, 2, 1, -1, -2]


def vertex_at(hex_coord, corner):
    """HexVertex.at(hex, corner)：u = 2q + r + U[corner]、w = 3r + W[corner]。"""
    q, r = hex_coord
    return (2 * q + r + _VERTEX_U[corner], 3 * r + _VERTEX_W[corner])


def _rotate_to_smallest(ring):
    """旋到字典序最小的顶点开头（compareTo = 先 u 后 w，与 tuple 序一致）。"""
    smallest = 0
    for i in range(1, len(ring)):
        if ring[i] < ring[smallest]:
            smallest = i
    if smallest == 0:
        return list(ring)
    return list(ring[smallest:]) + list(ring[:smallest])


def _compare_sequences(a, b):
    """顶点序列的字典序比较（不是 equals）。"""
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return -1 if a[i] < b[i] else 1
    if len(a) != len(b):
        return -1 if len(a) < len(b) else 1
    return 0


def _canonical_ring(ring):
    """RegionBoundary.canonicalRing：旋到最小顶点后，与反序比、取较小者。"""
    rotated = _rotate_to_smallest(ring)
    reversed_seq = [rotated[0]] + list(reversed(rotated[1:]))
    return rotated if _compare_sequences(rotated, reversed_seq) <= 0 else reversed_seq


def region_boundary(hex_set):
    """RegionBoundary.of(Set<HexCoord>) 的逐行移植，返回 rings（List[List[(u,w)]]）。

    纯函数：结果只由集合内容决定，与迭代序无关（规范性全部落在 canonicalRing）。
    """
    adjacency = {}
    for (q, r) in hex_set:
        for d, (dq, dr) in enumerate(DIRECTIONS):
            neighbor = (q + dq, r + dr)
            if neighbor in hex_set:
                continue  # 邻居也在集合里 ⇒ 该边是内部边，不暴露
            frm = vertex_at((q, r), d)
            to = vertex_at((q, r), (d + 1) % 6)
            adjacency.setdefault(frm, []).append(to)
            adjacency.setdefault(to, []).append(frm)

    # 度恒为 2；≠2 ⇒ 报错，不静默（合法输入下这条永不响）。
    for v, neighbors in adjacency.items():
        if len(neighbors) != 2:
            raise AssertionError(
                "边界顶点 {} 的度为 {}，应为 2（合法 hex 集合上不可能发生）".format(v, len(neighbors)))

    rings = []
    visited = set()
    # 按规范序迭代起点：让 py 侧不依赖 dict 迭代序（Java 侧靠 canonical 抹平）。
    for start in sorted(adjacency.keys()):
        if start in visited:
            continue
        ring = []
        previous = None
        current = start
        while True:
            ring.append(current)
            visited.add(current)
            neighbors = adjacency[current]
            nxt = neighbors[1] if neighbors[0] == previous else neighbors[0]
            previous = current
            current = nxt
            if current == start:
                break
        rings.append(_canonical_ring(ring))

    rings.sort(key=lambda r: r[0])  # 环表按各自首顶点字典序
    return rings


def terrain_blocks_from_map(terrain_by_hex):
    """把 hex → simos terrain key 切成**同地形六邻连通分量**的权威块（Java TerrainBlocks.split 的移植）。

    ★ 线格式与 `MapCodec`/`GameMap`/`TerrainBlock` 逐字段对齐：
      block_id（`<terrain>@q_r`，minHex 为自然序最小格）→
      `{"terrain":…, "hexes":[{"q","r"}…], "boundary":{"rings":[[{"u","w"}…]]}}`。
    ★ 保序：按 (terrain, min_hex) 排序（= Java `TreeMap<BlockId>` 的 `BlockId.compareTo`），
      块内 hexes 按 (q, r) 自然序 —— 同一输入 ⇒ 同一份字节。
    """
    ordered = sorted(terrain_by_hex.keys())  # HexCoord 自然序（q 升、r 升）
    visited = set()
    blocks = {}
    for start in ordered:
        if start in visited:
            continue
        terrain = terrain_by_hex[start]
        component = []
        stack = [start]
        visited.add(start)
        while stack:
            at = stack.pop()
            component.append(at)
            for dq, dr in DIRECTIONS:
                nb = (at[0] + dq, at[1] + dr)
                if nb in visited or terrain_by_hex.get(nb) != terrain:
                    continue  # 图外（None）或异地形 ⇒ 边界
                visited.add(nb)
                stack.append(nb)
        hex_set = set(component)
        min_hex = min(hex_set)
        rings = region_boundary(hex_set)
        blocks[(terrain, min_hex)] = {
            "terrain": terrain,
            "hexes": [{"q": q, "r": r} for (q, r) in sorted(hex_set)],
            "boundary": {"rings": [[{"u": u, "w": w} for (u, w) in ring] for ring in rings]},
        }
    out = {}
    for (terrain, min_hex) in sorted(blocks):
        out["{}@{}_{}".format(terrain, min_hex[0], min_hex[1])] = blocks[(terrain, min_hex)]
    return out

# ─────────────────────────────────────────────────────────────────────────────
# 3. 常量：信封 / DB / spec
# ─────────────────────────────────────────────────────────────────────────────

DB_FILE_NAME = "simos.db"
BRANCH = "main"
REVISION = 1
BOOTSTRAP_COMMAND_TYPE = "core.Bootstrap"
BOOTSTRAP_INITIATOR = "system:bootstrap"

#: 任务书指定的固定字面量（Timeline.changeSetJson(WorldChangeSet.empty()) 的实测形态）。
CHANGESET_JSON = '{"@class":"io.mosire.simos.core.state.WorldChangeSet","modules":{}}'

#: GenerationSpec.defaults(0L) 的精确 JSON（逐字段对照 /tmp/t9b-demo-store/checkpoints/main/1.json）。
#: 旧档没有 spec ⇒ 用合成占位，报告里记一行。
DEFAULT_SPEC = json.loads(
    '{"seed":0,"mapRadius":80,"baseSeaLevel":0.2025,"mainRidges":2,"fragments":5,'
    '"bands":{"shelfFreq":1.8,"lowFreq":3.5,"midFreq":8.0,"highFreq":20.0,"coastFreq":3.5,'
    '"moistureFreq":0.02,"shelfScale":0.35,"shelfOffset":0.15,"shelfHeightWeight":0.35,'
    '"lowWeight":0.4,"midWeight":0.25,"highWeight":0.12,"multiHeightWeight":0.45,'
    '"coastAmplitude":0.35,"gamma":0.92,"warpFreq":0.018,"warpAmplitude":10.0},'
    '"ridges":{"mainCompanionAngleMin":0.3,"mainCompanionAngleSpan":0.5,"mainLengthMin":1.3,'
    '"mainLengthSpan":0.4,"mainOffsetMin":0.2,"mainOffsetSpan":0.3,"mainStartJitter":0.03,'
    '"mainCurveSpan":0.18,"mainTailLength":0.48,"mainTailJitter":0.02,"mainHeadLength":0.52,'
    '"mainHeadJitter":0.03,"mainWeightMin":0.75,"mainWeightSpan":0.25,"secondaryAngleMin":0.12,'
    '"secondaryAngleSpan":0.4,"secondaryOffsetMin":0.1,"secondaryOffsetSpan":0.28,'
    '"secondaryLengthMin":0.5,"secondaryLengthSpan":0.45,"secondaryAlongMin":0.05,'
    '"secondaryAlongSpan":0.22,"secondaryTailLength":0.5,"secondaryHeadLength":0.5,'
    '"secondaryJitter":0.02,"secondaryWeightMin":0.25,"secondaryWeightSpan":0.3,'
    '"heightWeight":0.68,"decayBase":5.5,"decayPerWeight":2.0,"valleyMinRidges":2,'
    '"valleySigma":0.1,"valleyWeight":0.3},'
    '"fragmentParams":{"distMin":0.35,"distSpan":0.5,"lenMin":0.04,"lenSpan":0.08,'
    '"angleJitter":0.5,"tipLength":0.5,"weightMin":0.1,"weightSpan":0.15,'
    '"secondaryCountFloor":2,"secondaryCountDivisor":2},"contourCacheMax":5000}')

#: SqliteStore 的冻结 DDL（逐字对照 Java 源码，含行尾注释）。
REVISIONS_DDL = """
CREATE TABLE IF NOT EXISTS revisions (
  branch          TEXT    NOT NULL,
  revision        INTEGER NOT NULL,
  parent_branch   TEXT,                       -- 创世为 NULL
  parent_revision INTEGER,                    -- 创世为 NULL
  tick            INTEGER NOT NULL,           -- 模拟时刻（SimosTimestamp.tick）
  calendar_label  TEXT,                       -- 可空（SimosTimestamp.calendarLabel）
  command_id      TEXT    NOT NULL,
  correlation_id  TEXT    NOT NULL,
  initiator       TEXT    NOT NULL,
  command_type    TEXT    NOT NULL,
  changeset_json  TEXT    NOT NULL,
  PRIMARY KEY (branch, revision),
  FOREIGN KEY (parent_branch, parent_revision) REFERENCES revisions (branch, revision)
)
"""

EVENTS_DDL = """
CREATE TABLE IF NOT EXISTS events (
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,
  ts             TEXT    NOT NULL,
  type           TEXT    NOT NULL,
  agent          TEXT    NOT NULL,
  payload        TEXT    NOT NULL DEFAULT '',
  correlation_id TEXT    NOT NULL DEFAULT ''
)
"""

INDEX_DDLS = [
    "CREATE INDEX IF NOT EXISTS idx_revisions_correlation "
    "ON revisions (correlation_id, branch, revision)",
    "CREATE INDEX IF NOT EXISTS idx_revisions_parent ON revisions (parent_branch, parent_revision)",
    "CREATE INDEX IF NOT EXISTS idx_events_type_correlation_id_seq "
    "ON events (type, correlation_id, seq)",
    "CREATE INDEX IF NOT EXISTS idx_events_correlation_id_seq ON events (correlation_id, seq)",
]

#: 库级语义标签（2026-09-24 日制裁定）：SqliteStore.ensureTimeBase 对"已有 revision 但无标签"的库 fail-closed，
#: 故导入器产出的库必须自带标签。
STORE_META_DDL = """
CREATE TABLE IF NOT EXISTS store_meta (
  key   TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
)
"""

TIME_BASE_DAY = "DAY"

FORMAT_VERSION_DAY_BASE = "1"


# ─────────────────────────────────────────────────────────────────────────────
# 3b. 重建模式的 8 模块空切片载荷（baseline 4bf60478）
# ─────────────────────────────────────────────────────────────────────────────
#: 由当前 baseline 各模块自己的 codec `encodeSnapshot(empty)` 生成（生成器 EmptySlices.java +
#: 逐字节证据见重建目录 `samples/empty_module_slices.json`）。`map` 由重建器自己产出，不在此列。
#: ★ 只写 map/social/unit 三个切片时，`/api/units` 等读路径会因缺 `sd` 切片报 400
#:   （2026-10-02 实测：`状态里没有 sd 模块切片——装配故障`）——故重建档补齐全部 8 模块；
#:   旧模式（无 --regen-samples）**不补**，保持 M6 逐字节不变。
EMPTY_MODULE_PAYLOADS = json.loads(r'''{
  "actor": "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},\"timestamp\":{\"tick\":0,\"calendarLabel\":null},\"data\":{\"meta\":null,\"actors\":{},\"accounts\":{}}}",
  "army": "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},\"timestamp\":{\"tick\":0,\"calendarLabel\":null},\"data\":{\"combats\":{}}}",
  "economy": "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},\"timestamp\":{\"tick\":0,\"calendarLabel\":null},\"data\":{\"meta\":null,\"industries\":{},\"classes\":{},\"debtContracts\":{},\"flows\":{},\"laborSupply\":{},\"allocations\":{},\"relations\":{},\"markets\":{},\"shipments\":{},\"memberships\":{},\"assetShares\":{},\"operatorConditions\":{},\"units\":{},\"demands\":{},\"candidates\":{},\"modes\":{},\"classStructures\":{},\"classPositions\":{},\"classStandings\":{},\"productionOrganizations\":{},\"assetRules\":{},\"governments\":{},\"moneyIssuances\":{},\"pledges\":{},\"liquidationPolicies\":{},\"crisisSignals\":{},\"modeTransitions\":{},\"classShares\":{},\"classFirst\":{\"modeParticipations\":{},\"classPools\":{},\"householdAccounts\":{},\"assetStateSchemas\":{},\"classBounds\":{},\"mobilityPolicies\":{},\"classFlowEvents\":{},\"accounts\":{},\"lenders\":{},\"meta\":{\"tick\":0,\"landForSale\":0,\"landMarketEscrowGrain\":0,\"landMarketEscrowMoney\":0,\"totalLeaseHolding\":0,\"config\":null,\"totals\":{\"producedGrainTotal\":0,\"seedUsedTotal\":0,\"rationConsumedTotal\":0,\"clothConsumedTotal\":0,\"borrowedGrainTotal\":0,\"borrowedMoneyTotal\":0,\"boughtGrainTotal\":0,\"liquidSeizedTotal\":0,\"landSeizedTotal\":0,\"capitalizedTotal\":0,\"redLightTotal\":0,\"collectionEventCount\":0,\"interestChargedTotal\":0,\"rentPaidTotal\":0,\"wagePaidTotal\":0,\"externalSeedPaidTotal\":0,\"residualPaidTotal\":0,\"taxPaidTotal\":0},\"initial\":{\"grainTotal\":0,\"clothTotal\":0,\"householdMoneyTotal\":0,\"lenderMoneyTotal\":0,\"populationTotal\":0,\"ownedLandTotal\":0,\"toolsTotal\":0,\"claimGrainMilli\":0},\"stockEnrichmentViolations\":0}}}}",
  "gov": "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},\"timestamp\":{\"tick\":0,\"calendarLabel\":null},\"state\":{\"offices\":{}}}",
  "sd": "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},\"timestamp\":{\"tick\":0,\"calendarLabel\":null},\"state\":{\"nations\":{},\"armies\":{},\"combats\":{},\"combatStates\":{},\"decisionMakers\":{},\"directives\":{},\"effects\":{},\"verdicts\":{},\"lossRecords\":{},\"info\":{},\"diplomaticRelations\":{},\"diplomaticEvents\":{}}}",
  "social": "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},\"timestamp\":{\"tick\":0,\"calendarLabel\":null},\"data\":{\"populations\":{},\"cities\":{},\"groups\":{}}}",
  "unit": "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},\"timestamp\":{\"tick\":0,\"calendarLabel\":null},\"state\":{\"units\":{},\"commandChains\":{}}}"
}''')


class ImportRejected(Exception):
    """fail-closed 拒绝（退出码非 0）。"""


def compact_json(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def parse_hex(text):
    """HexCoord.parse：'q_r' → (q, r)。"""
    if not isinstance(text, str):
        raise ImportRejected("坐标不是字符串: {!r}".format(text))
    i = text.find("_")
    if i <= 0 or i == len(text) - 1:
        raise ImportRejected("非法坐标串: {!r}".format(text))
    try:
        return (int(text[:i]), int(text[i + 1:]))
    except ValueError:
        raise ImportRejected("非法坐标串: {!r}".format(text))


def parse_edge(text):
    """EdgeRef.parse + 规范排序（a 恒为 (q,r) 字典序较小者）。"""
    if not isinstance(text, str):
        raise ImportRejected("边 key 不是字符串: {!r}".format(text))
    i = text.find("|")
    if i <= 0 or i == len(text) - 1 or text.find("|", i + 1) >= 0:
        raise ImportRejected("非法边串: {!r}".format(text))
    a = parse_hex(text[:i])
    b = parse_hex(text[i + 1:])
    if a == b:
        raise ImportRejected("边不能自环: {!r}".format(text))
    return (a, b) if a < b else (b, a)


def edge_to_str(edge):
    return "{}_{}|{}_{}".format(edge[0][0], edge[0][1], edge[1][0], edge[1][1])


def directions_of_edge_tags(coord_text, tags):
    """校验并返回该格 edgeTags 出现的方向码集合。"""
    dirs = set()
    for dir_text, kinds in tags.items():
        try:
            direction = int(dir_text)
        except (TypeError, ValueError):
            raise ImportRejected("hex {} 的 edgeTags 方向不是整数: {!r}".format(coord_text, dir_text))
        if not 0 <= direction < 6:
            raise ImportRejected("hex {} 的 edgeTags 方向越界: {!r}".format(coord_text, dir_text))
        if not isinstance(kinds, list) or not kinds:
            raise ImportRejected(
                "hex {} 的方向 {} 的组名不是非空数组: {!r}".format(coord_text, dir_text, kinds))
        dirs.add(direction)
    return dirs


def directions_of_river_mask(coord_text, mask):
    """6-bit riverMask → 方向码集合（bits 0-5 = E,SE,SW,W,NW,NE）。"""
    if not isinstance(mask, int) or isinstance(mask, bool) or not 0 <= mask <= 63:
        raise ImportRejected("hex {} 的 riverMask 非法: {!r}".format(coord_text, mask))
    return set(i for i in range(6) if (mask >> i) & 1)


def _neighbor_text(coord, direction):
    dq, dr = DIRECTIONS[direction]
    return "{}_{}".format(coord[0] + dq, coord[1] + dr)


def _wrap_edges(edges):
    """规范化为 simos 线格式：边键与 kind 都字典序（同一输入 ⇒ 同一份字节）。"""
    out = {}
    for key in sorted(edges):
        by_pathway = edges[key]
        out[key] = {"byPathway": {kind: by_pathway[kind] for kind in sorted(by_pathway)}}
    return out


def edges_from_edge_tags(hexes_src):
    """edgeTags[dir] → EdgeRef 的忠实转换（kind = legacy 的组名，v17levant 里只有 river）。"""
    edges = {}
    for coord_text, cell in hexes_src.items():
        coord = parse_hex(coord_text)
        for dir_text, kinds in (cell.get("edgeTags") or {}).items():
            direction = int(dir_text)
            neighbor_text = _neighbor_text(coord, direction)
            if neighbor_text not in hexes_src:
                raise ImportRejected(
                    "边指向不存在的格: {} --方向{}--> {}".format(coord_text, direction, neighbor_text))
            neighbor = parse_hex(neighbor_text)
            edge = (coord, neighbor) if coord < neighbor else (neighbor, coord)
            by_pathway = edges.setdefault(edge_to_str(edge), {})
            for kind in kinds:
                if not isinstance(kind, str) or not kind:
                    raise ImportRejected("hex {} 的边组名非法: {!r}".format(coord_text, kind))
                by_pathway.setdefault(kind, {})
    return _wrap_edges(edges)


def edges_from_river_mask(river_mask_by_hex, hexes_src):
    """riverMask 位 → EdgeRef（bits 0-5 = E,SE,SW,W,NW,NE），kind 固定 river。"""
    edges = {}
    for coord_text, dirs in river_mask_by_hex.items():
        coord = parse_hex(coord_text)
        for direction in dirs:
            neighbor_text = _neighbor_text(coord, direction)
            if neighbor_text not in hexes_src:
                raise ImportRejected(
                    "riverMask 指向不存在的格: {} --方向{}--> {}".format(
                        coord_text, direction, neighbor_text))
            neighbor = parse_hex(neighbor_text)
            edge = (coord, neighbor) if coord < neighbor else (neighbor, coord)
            by_pathway = edges.setdefault(edge_to_str(edge), {})
            by_pathway.setdefault("river", {})
    return _wrap_edges(edges)


def resolve_edges(data, hexes_src, edge_tags_by_hex, river_mask_by_hex):
    """连通性融合优先级：edges（权威）> edgeTags > riverMask（spec §五.2 / 裁定 D12）。"""
    edges_src = data.get("edges") or {}
    if edges_src:
        # edges 权威：直映（M6 行为）。simos 的 HexCell 不存 edgeTags（只有 height），
        # 故"据 edges 重建 edgeTags"在 simos 侧没有落点，忽略另两份即可。
        edges = {}
        for edge_key, by_pathway in edges_src.items():
            edge = parse_edge(edge_key)
            canonical = edge_to_str(edge)
            if canonical in edges:
                raise ImportRejected("edges 规范化后键冲突: {}".format(canonical))
            if not isinstance(by_pathway, dict):
                raise ImportRejected("edge {} 的值不是对象".format(edge_key))
            edges[canonical] = {"byPathway": by_pathway}
        return edges, "edges"

    for coord_text in sorted(set(edge_tags_by_hex) | set(river_mask_by_hex)):
        et = edge_tags_by_hex.get(coord_text, set())
        rm = river_mask_by_hex.get(coord_text, set())
        if et != rm:
            raise ImportRejected(
                "hex {} 的 edgeTags 与 riverMask 不一致（交叉校验失败，不静默取一）: "
                "edgeTags={} riverMask={}".format(coord_text, sorted(et), sorted(rm)))

    if edge_tags_by_hex:
        return edges_from_edge_tags(hexes_src), "edgeTags"
    if river_mask_by_hex:
        return edges_from_river_mask(river_mask_by_hex, hexes_src), "riverMask"
    return {}, "none"


def build_map_payload(data, report, regen_samples=None):
    """把一份旧 root full map 转成 MapSnapshot 的 JSON 对象。

    ★ 重建模式（`regen_samples` 非 None，D-013/D-011-R4 前置）：高度**逐格取自再生样本**；
      地形按 {@link REBUILD_TERRAIN_MAP} 映射（GSim 当前 `plains` = 高原 ⇒ `plateau`）；
      快照发**权威 terrainBlocks**（新形状：`hexes` 只存 `height`）。
    ★ 旧模式（`regen_samples` 为 None）：行为与 M6 逐字节一致（旧形状：`hexes` 带 `terrain`，
      地形由 `MapCodec` 读入时就地迁移成块）。
    """
    hexes_src = data.get("hexes")
    if not isinstance(hexes_src, dict):
        raise ImportRejected("hexes 缺失或不是对象（这不是一份 full map）")

    rebuild = regen_samples is not None
    mapping = REBUILD_TERRAIN_MAP if rebuild else TERRAIN_MAP
    lossy_keys = REBUILD_LOSSY_KEYS if rebuild else LOSSY_KEYS
    sample_hexes = None
    if rebuild:
        sample_hexes = regen_samples.get("hexes")
        if not isinstance(sample_hexes, dict):
            raise ImportRejected("再生样本缺 hexes 对象")
        base_keys = set(hexes_src)
        sample_keys = set(sample_hexes)
        if base_keys != sample_keys:
            only_base = sorted(base_keys - sample_keys)
            only_sample = sorted(sample_keys - base_keys)
            raise ImportRejected(
                "再生样本 hex 键集与底图不一致：底图 {} / 样本 {} / 仅底图 {}（示例 {}）/ 仅样本 {}（示例 {}）"
                .format(len(base_keys), len(sample_keys), len(only_base), only_base[:5],
                        len(only_sample), only_sample[:5]))

    # ── 逐格：收集 terrain + 连通性三份表示 ──
    old_key_counts = {}      # 重建模式下 = GSim 再生词表
    base_key_counts = {}     # 底图原词表（报告对照用）
    new_hexes = {}
    terrain_by_hex = {}
    edge_tags_by_hex = {}    # coord_text -> set(方向码)
    river_mask_by_hex = {}   # coord_text -> set(方向码)
    height_min = None
    height_max = None
    height_clamped = 0

    for coord_text, cell in hexes_src.items():
        parse_hex(coord_text)
        if not isinstance(cell, dict):
            raise ImportRejected("hex {} 的值不是对象: {!r}".format(coord_text, cell))

        if rebuild:
            sample = sample_hexes[coord_text]
            if not isinstance(sample, dict):
                raise ImportRejected("样本 hex {} 的值不是对象".format(coord_text))
            old_key = sample.get("terrain")
            if old_key is None:
                raise ImportRejected("样本 hex {} 缺 terrain".format(coord_text))
        else:
            old_key = cell.get("terrain")
            if old_key is None:
                raise ImportRejected("hex {} 缺 terrain".format(coord_text))
        base_key = cell.get("terrain", "<missing>")
        base_key_counts[base_key] = base_key_counts.get(base_key, 0) + 1

        tags = cell.get("edgeTags") or {}
        if tags:
            edge_tags_by_hex[coord_text] = directions_of_edge_tags(coord_text, tags)
        mask = cell.get("riverMask", 0) or 0
        if mask:
            river_mask_by_hex[coord_text] = directions_of_river_mask(coord_text, mask)

        old_key_counts[old_key] = old_key_counts.get(old_key, 0) + 1

    # 表外的在用 key ⇒ fail-closed
    unknown = {k: c for k, c in old_key_counts.items() if k not in mapping}
    if unknown:
        raise ImportRejected(
            "地形 key 不在显式映射表内（fail-closed，不编造语义）: " + ", ".join(
                "{}={} 格".format(k, unknown[k]) for k in sorted(unknown)))

    # ── 实际用到的 simos key（只输出用到的） ──
    used_simos = {}
    for old_key, count in old_key_counts.items():
        simos_key = mapping[old_key]
        used_simos.setdefault(simos_key, 0)
        used_simos[simos_key] += count

    for coord_text, cell in hexes_src.items():
        coord = parse_hex(coord_text)
        if rebuild:
            sample = sample_hexes[coord_text]
            simos_key = mapping[sample["terrain"]]
            raw_height = sample.get("height")
            if not isinstance(raw_height, (int, float)) or isinstance(raw_height, bool) \
                    or not math.isfinite(float(raw_height)):
                raise ImportRejected(
                    "再生样本 hex {} 的 height 非有限数: {!r}".format(coord_text, raw_height))
            height = float(raw_height)
            if height < 0.0 or height > 1.0:
                height_clamped += 1
                height = min(1.0, max(0.0, height))
            height_min = height if height_min is None else min(height_min, height)
            height_max = height if height_max is None else max(height_max, height)
            new_hexes["{}_{}".format(coord[0], coord[1])] = {"height": height}
            terrain_by_hex[coord] = simos_key  # ★ 键是 (q,r) 元组：块切分按坐标邻接
        else:
            simos_key = TERRAIN_MAP[cell["terrain"]]
            new_hexes["{}_{}".format(coord[0], coord[1])] = {
                "terrain": simos_key,
                "height": representative_height(simos_key),
            }

    # ── 地形块（仅重建模式）：同地形六邻连通分量，与 Java TerrainBlocks.split 同算法 ──
    terrain_blocks = terrain_blocks_from_map(terrain_by_hex) if rebuild else {}

    # ── terrainTypes：在用的 + ALWAYS_EMITTED（键序 = TerrainCatalog.KEYS 的高度升序） ──
    terrain_types = {}
    for key in ("ocean", "plains", "desert", "low_hills", "mountains", "plateau",
                "plateau_mountains"):
        if key in used_simos or key in ALWAYS_EMITTED_TERRAIN_KEYS:
            terrain_types[key] = dict(TERRAIN_CATALOG[key])

    # ── provinces → regions ──
    provinces = data.get("provinces") or {}
    regions = {}
    ring_total = 0
    region_ring_counts = {}
    region_hex_counts = {}
    for province_id, province in provinces.items():
        if not isinstance(province, dict):
            raise ImportRejected("province {} 的值不是对象".format(province_id))
        raw_hexes = province.get("hexes")
        if not isinstance(raw_hexes, list):
            raise ImportRejected("province {} 的 hexes 不是数组".format(province_id))
        hex_set = set(parse_hex(h) for h in raw_hexes)
        rings = region_boundary(hex_set)
        ring_total += len(rings)
        region_ring_counts[province_id] = len(rings)
        region_hex_counts[province_id] = len(hex_set)
        # Region.hexes 是 JSON 数组、元素是 {q,r} 对象（与 Map 键的 'q_r' 不对称，照做）
        hex_list = [{"q": q, "r": r} for (q, r) in sorted(hex_set)]
        regions[province_id] = {
            "id": {"value": province_id},
            "name": province_id,  # 旧 Province 无 name 字段：名字就是 dict 的键
            "hexes": hex_list,
            "boundary": {"rings": [[{"u": u, "w": w} for (u, w) in ring] for ring in rings]},
            "meta": {
                "color": province.get("color"),
                "tag": province.get("tag"),
                "description": province.get("description"),
                "annexedBy": province.get("annexedBy"),
            },
        }

    # ── pathwayGroups 直映 ──
    pathway_groups = {}
    for group_id, group in (data.get("pathwayGroups") or {}).items():
        if not isinstance(group, dict):
            raise ImportRejected("pathwayGroup {} 的值不是对象".format(group_id))
        props = {}
        for prop_name, prop in (group.get("properties") or {}).items():
            if not isinstance(prop, dict) or "type" not in prop:
                raise ImportRejected(
                    "pathwayGroup {} 的属性 {} 缺 type".format(group_id, prop_name))
            props[prop_name] = {
                "type": prop["type"],
                "defaultValue": prop.get("default"),  # 旧字段 default → simos 组件 defaultValue
                "description": prop.get("description"),
            }
        pathway_groups[group_id] = {
            "id": group["id"],
            "name": group["name"],
            "color": group["color"],
            "description": group.get("description"),
            "visible": group.get("visible", True),
            "properties": props,
        }

    # ── 连通性：融合优先级 edges > edgeTags > riverMask（spec §五.2 / 裁定 D12） ──
    edges, edge_source = resolve_edges(data, hexes_src, edge_tags_by_hex, river_mask_by_hex)

    # 键序与 GameMap record 组件序一致（hexes, terrainBlocks, regions, cities, terrainTypes,
    # pathways, pathwayGroups, edges, spec）；旧模式**不含** terrainBlocks（保持 M6 字节不变）。
    map_obj = {"hexes": new_hexes}
    if rebuild:
        map_obj["terrainBlocks"] = terrain_blocks
    map_obj.update({
        "regions": regions,
        "cities": {},              # 旧 cities 非空在调用方已 fail-closed
        "terrainTypes": terrain_types,
        "pathways": {},            # 旧档无 pathways（rivers/roads 已废弃）
        "pathwayGroups": pathway_groups,
        "edges": edges,
        "spec": DEFAULT_SPEC,
    })

    # 报告数据
    report.update({
        "mode": "rebuild" if rebuild else "legacy",
        "hex_total": len(new_hexes),
        "old_key_counts": old_key_counts,
        "base_key_counts": base_key_counts,
        "used_simos": used_simos,
        "terrain_types_keys": list(terrain_types.keys()),
        "provinces": len(regions),
        "rings": ring_total,
        "region_ring_counts": region_ring_counts,
        "region_hex_counts": region_hex_counts,
        "edges": len(edges),
        "edge_source": edge_source,
        "edge_tags_hexes": len(edge_tags_by_hex),
        "river_mask_hexes": len(river_mask_by_hex),
        "lossy_counts": {k: old_key_counts[k] for k in lossy_keys if k in old_key_counts},
    })
    if rebuild:
        report.update({
            "regen_terrain_counts": dict(old_key_counts),
            "terrain_blocks": len(terrain_blocks),
            "terrain_block_hex_total": sum(len(b["hexes"]) for b in terrain_blocks.values()),
            "height_min": height_min,
            "height_max": height_max,
            "height_clamped": height_clamped,
            "regen_water_hexes": old_key_counts.get("water", 0),
            "regen_land_hexes": len(new_hexes) - old_key_counts.get("water", 0),
        })
    return map_obj


def build_checkpoint(map_obj, extra_modules=None):
    """组装信封（modules 的 value 是**字符串**）。

    `extra_modules`：namespace → 载荷 JSON 文本；重建模式传 {@link EMPTY_MODULE_PAYLOADS}，
    使档补齐全 8 模块；旧模式传 None（保持 map/social/unit 三模块的 M6 字节）。
    """
    nested_ref = {"branch": {"value": BRANCH}, "revision": {"value": REVISION}}
    ts = {"tick": 0, "calendarLabel": None}

    map_payload = compact_json({"ref": nested_ref, "timestamp": ts, "map": map_obj})
    social_payload = compact_json({
        "ref": nested_ref, "timestamp": ts,
        "data": {"populations": {}},   # 空切片：否则 applyWorld 抛"模块不在当前状态里"
    })
    unit_payload = compact_json({
        "ref": nested_ref, "timestamp": ts,
        # ★ commandChains 是 T1 之后 UnitState 的**必填第二组件**（缺 ⇒ 解码期抛"commandChains 不得为 null"）
        "state": {"units": {}, "commandChains": {}},
    })

    # 模块表按 namespace 字典序（map < social < unit），与 CheckpointEncoder 的 TreeMap 一致
    # ★ timeBase 是 2026-09-24 日制裁定的**必填**标签（Envelope.decode 缺它即拒）：新档一律 DAY。
    modules = {"map": map_payload, "social": social_payload, "unit": unit_payload}
    if extra_modules:
        modules.update(extra_modules)
    envelope = {
        "ref": {"branch": BRANCH, "revision": REVISION},
        "timestamp": ts,
        "timeBase": "DAY",
        # 与 CheckpointEncoder 的 TreeMap 一致：namespace 字典序
        "modules": {name: modules[name] for name in sorted(modules)},
        "info": {"bySubject": {}},
    }
    return compact_json(envelope)


def write_db(db_path):
    """建 2 表 4 索引 + store_meta 时间基标签 + 恰一行创世 revision。"""
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        cur = conn.cursor()
        cur.execute("PRAGMA journal_mode = WAL")
        cur.execute("PRAGMA busy_timeout = 5000")
        cur.execute("PRAGMA foreign_keys = ON")
        cur.execute(REVISIONS_DDL)
        cur.execute(EVENTS_DDL)
        cur.execute(STORE_META_DDL)
        for ddl in INDEX_DDLS:
            cur.execute(ddl)
        cur.execute(
            "INSERT INTO store_meta (key, value) VALUES (?, ?)",
            ("time_base", TIME_BASE_DAY))
        cur.execute(
            "INSERT INTO store_meta (key, value) VALUES (?, ?)",
            ("format_version", FORMAT_VERSION_DAY_BASE))
        cur.execute(
            "INSERT INTO revisions (branch, revision, parent_branch, parent_revision, tick,"
            " calendar_label, command_id, correlation_id, initiator, command_type,"
            " changeset_json) VALUES (?, ?, NULL, NULL, ?, NULL, ?, ?, ?, ?, ?)",
            (BRANCH, REVISION, 0, str(uuid.uuid4()), str(uuid.uuid4()),
             BOOTSTRAP_INITIATOR, BOOTSTRAP_COMMAND_TYPE, CHANGESET_JSON))
        conn.commit()
        cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.commit()
    finally:
        conn.close()


def _md5_file(path):
    digest = hashlib.md5()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def print_report(input_path, out_dir, report, rejected=None, samples_path=None):
    print("=" * 72)
    print("[gsimap_import] 输入文件 : {}".format(input_path))
    if samples_path:
        print("[gsimap_import] 再生样本 : {}".format(samples_path))
    print("[gsimap_import] 输出目录 : {}".format(out_dir))
    if rejected is not None:
        print("[gsimap_import] 结果     : REJECTED (fail-closed)")
        print("[gsimap_import] 拒绝理由 : {}".format(rejected))
        print("=" * 72)
        return
    rebuild = report.get("mode") == "rebuild"
    mapping = REBUILD_TERRAIN_MAP if rebuild else TERRAIN_MAP
    lossy_keys = REBUILD_LOSSY_KEYS if rebuild else LOSSY_KEYS
    print("[gsimap_import] 模式     : {}".format(report.get("mode", "legacy")))
    print("[gsimap_import] hex 总数 : {}".format(report["hex_total"]))
    print("[gsimap_import] 地形逐 key 映射（含 lossy 标记）{}:".format(
        "（源 = GSim ContourQueryEngine 再生词表）" if rebuild else ""))
    for old_key in sorted(report["old_key_counts"]):
        simos_key = mapping[old_key]
        flag = "  ★lossy" if old_key in lossy_keys else ""
        if rebuild:
            print("    {:<9} -> {:<14} {:>6} 格（再生）{}".format(
                old_key, simos_key, report["old_key_counts"][old_key], flag))
        else:
            print("    {:<9} -> {:<14} {:>6} 格   代表高度={}{}".format(
                old_key, simos_key, report["old_key_counts"][old_key],
                representative_height(simos_key), flag))
    if rebuild and report.get("base_key_counts"):
        print("[gsimap_import] 底图 n0000 旧词表分布（对照；不参与重建映射）:")
        for old_key in sorted(report["base_key_counts"]):
            print("    {:<9} {:>6} 格".format(old_key, report["base_key_counts"][old_key]))
    print("[gsimap_import] 输出 terrainTypes 键集: {}（= 在用者 ∪ {})".format(
        report["terrain_types_keys"], list(ALWAYS_EMITTED_TERRAIN_KEYS)))
    print("[gsimap_import] LOSSY 映射（★ 有损：simos 词表无对应，合并入 plains）:")
    if report["lossy_counts"]:
        for old_key, count in sorted(report["lossy_counts"].items()):
            print("    {:<9} -> {:<14} {:>6} 格".format(old_key, mapping[old_key], count))
    else:
        print("    （无）")
    print("[gsimap_import] 丢弃的键 + 理由:")
    print("    compressedRegions — 旧仓 CompressionService 明说它是纯渲染缓存，hexes 才是权威")
    print("    rivers / roads    — 旧仓已标 @Deprecated，被 edges+pathwayGroups 取代")
    if rebuild:
        print("    （底图 terrainBlocks 为空也没用：旧仓 TerrainBlockProcessor 已 @Deprecated；"
              "本次由 RebuildSamples 重算权威块）")
    else:
        print("    terrainBlocks     — 旧仓 TerrainBlockProcessor 已 @Deprecated，以 hex 上的 terrain 为权威")
    print("    gridSize          — simos 无对应（恒 30 的死值，与真实半径 80 矛盾）")
    print("    hexOrientation    — simos 无对应；已校验为 false（flat-top 假定）")
    print("[gsimap_import] provinces 数 : {}   环数 : {}".format(
        report["provinces"], report["rings"]))
    print("[gsimap_import] edges 条数   : {}（来源={}；edgeTags 非空格 {} / riverMask 非零格 {}）".format(
        report["edges"], report["edge_source"],
        report["edge_tags_hexes"], report["river_mask_hexes"]))
    if rebuild:
        print("[gsimap_import] terrainBlocks : {} 块 / 覆盖 {} 格"
              "（同地形六邻连通分量；hexes 只存 height，块表为地形权威）".format(
                  report["terrain_blocks"], report["terrain_block_hex_total"]))
        print("[gsimap_import] height       : min={} max={} 越界 clamp={}（逐格取自再生样本）".format(
            report["height_min"], report["height_max"], report["height_clamped"]))
        print("[gsimap_import] 再生水陆比   : water={} ({}%) / land={} ({}%)；contour.landRatio={}".format(
            report["regen_water_hexes"], round(100.0 * report["regen_water_hexes"] / report["hex_total"], 4),
            report["regen_land_hexes"], round(100.0 * report["regen_land_hexes"] / report["hex_total"], 4),
            report.get("sample_landRatio")))
        print("[gsimap_import] 合成项       : spec 为 GenerationSpec.defaults(0) 的占位"
              "（contour→GenerationSpec 映射口径未裁决，未编造）")
    else:
        print("[gsimap_import] 合成项       : spec 为 GenerationSpec.defaults(0) 的占位；"
              "height 为所映射地形高度带中点（★ 沙漠取平原中点 + 0.005，2026-09-24）")
    print("[gsimap_import] 结果         : OK")
    print("=" * 72)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="旧 GSimulator *_map.json → simos 可读数据集（simos.db + checkpoints/main/1.json）；"
                    "传 --regen-samples 走 D-013 重建模式（再生高度 + 新地形映射 + 权威 terrainBlocks）")
    parser.add_argument("input", help="旧 *_map.json 路径（必须是 root full map，不是 MapDiff）")
    parser.add_argument("out_dir", help="输出目录（不存在则创建）")
    parser.add_argument("--regen-samples", metavar="PATH", default=None,
                        help="重建模式：RegenSamples 产出的样本 JSON"
                             "（hex 键集必须与底图完全一致；高度逐格取它、地形按重建映射）")
    parser.add_argument("--report-json", metavar="PATH", default=None,
                        help="统计落盘 JSON（重建模式缺省写 <out_dir>/report.json）")
    args = parser.parse_args(argv)

    report = {}
    try:
        with open(args.input, "r", encoding="utf-8") as fh:
            data = json.load(fh)

        # ── 顶层 fail-closed ──
        if "parentNodeId" in data or "changed" in data:
            raise ImportRejected(
                "顶层含 parentNodeId/changed ⇒ 这是 MapDiff 不是 full map（M6 只吃 root full map）")

        if "hexOrientation" not in data:
            raise ImportRejected("缺 hexOrientation，无法校验 simos 的 flat-top 假定")
        if data["hexOrientation"] is not False:
            raise ImportRejected(
                "hexOrientation 非 false（={!r}）：simos 的方向表假定 flat-top".format(
                    data["hexOrientation"]))

        cities = data.get("cities") or {}
        if cities:
            raise ImportRejected(
                "cities 非空（{} 座）——simos 有 City 类型但映射规则未裁决，不猜".format(len(cities)))

        regen_samples = None
        if args.regen_samples:
            with open(args.regen_samples, "r", encoding="utf-8") as fh:
                regen_samples = json.load(fh)
            if not isinstance(regen_samples, dict):
                raise ImportRejected("再生样本顶层不是对象")
            for field in ("seed", "radius", "landRatio"):
                if field not in regen_samples:
                    raise ImportRejected("再生样本缺 {} 字段".format(field))

        map_obj = build_map_payload(data, report, regen_samples)

        out_dir = os.path.abspath(args.out_dir)
        os.makedirs(os.path.join(out_dir, "checkpoints", BRANCH), exist_ok=True)

        checkpoint_path = os.path.join(out_dir, "checkpoints", BRANCH, "{}.json".format(REVISION))
        extra_modules = EMPTY_MODULE_PAYLOADS if regen_samples is not None else None
        with open(checkpoint_path, "w", encoding="utf-8") as fh:
            fh.write(build_checkpoint(map_obj, extra_modules))

        write_db(os.path.join(out_dir, DB_FILE_NAME))

        report["input_map"] = os.path.abspath(args.input)
        report["out_dir"] = out_dir
        report["checkpoint_path"] = checkpoint_path
        report["checkpoint_bytes"] = os.path.getsize(checkpoint_path)
        report["checkpoint_md5"] = _md5_file(checkpoint_path)
        report["modules"] = (["map"] + sorted(EMPTY_MODULE_PAYLOADS)
                             if regen_samples is not None else ["map", "social", "unit"])
        if regen_samples is not None:
            report["regen_samples_path"] = os.path.abspath(args.regen_samples)
            report["regen_samples_md5"] = _md5_file(args.regen_samples)
            report["sample_seed"] = regen_samples["seed"]
            report["sample_radius"] = regen_samples["radius"]
            report["sample_landRatio"] = regen_samples["landRatio"]
            report["regen_actual_water_ratio"] = (
                report["regen_water_hexes"] / report["hex_total"])
            report["regen_actual_land_ratio"] = (
                report["regen_land_hexes"] / report["hex_total"])
        if args.report_json or regen_samples is not None:
            report_path = args.report_json or os.path.join(out_dir, "report.json")
            with open(report_path, "w", encoding="utf-8") as fh:
                json.dump(report, fh, ensure_ascii=False, indent=2, sort_keys=True)
                fh.write("\n")
            print("[gsimap_import] report.json : {} (md5={})".format(
                os.path.abspath(report_path), _md5_file(report_path)))

        print_report(args.input, out_dir, report, samples_path=args.regen_samples)
        return 0
    except ImportRejected as exc:
        print_report(args.input, args.out_dir, report, rejected=str(exc),
                     samples_path=args.regen_samples)
        return 2
    except (OSError, ValueError, KeyError, AssertionError) as exc:
        print_report(args.input, args.out_dir, report,
                     rejected="{}: {}".format(type(exc).__name__, exc),
                     samples_path=args.regen_samples)
        return 3


if __name__ == "__main__":
    sys.exit(main())
