#!/usr/bin/env python3
"""T6 变异体生成器（照 T5 的 make-mutants.py 缩编：靶文件只有两个）。

规则（与 T5 同）：
- 每条 `old` 在原件里必须**恰好出现 1 次**（多了/少了都当场报错，绝不"尽量替换"）；
- 生成物写到证据目录（`t6-evidence/mutants/`），**绝不**写进 `src/`；
- ★ 逐条记 ⑩ 门禁的自证片段 `frag` 与方向：
    revert —— 原件 0 次、变异体 ≥1 次（"把旧的坏写法搬回来"型）；
    delete —— 原件 ≥1 次、变异体 0 次（"删掉校验"型）。
  判据是**逐条代码片段**，不是整份文件计数（T5-L5：注释里提过禁词会把整文件计数判据顶成假阳性）。
"""
import hashlib
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve()
REPO = HERE.parents[5]
OUT = HERE.parent

OPS = "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java"
HANDLER = "simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanSparseRouteHandler.java"

MUTANTS = [
    dict(
        label="t6m1",
        target=OPS,
        name="t6m1_UnitOperations.java",
        reason="去掉 A* 展开：回到 new Route(waypoints, waypoints)（计划 m1）",
        edits=[
            (
                "Route route = new Route(waypoints, expandSparsePath(map, unit, waypoints, cost));",
                "Route route = new Route(waypoints, waypoints);",
            )
        ],
        frag="new Route(waypoints, waypoints)",
        frag_dir="revert",
        expect_red="UnitOperationsTest.planSparseRouteFreezesTheExpandedPath / "
        "UnitCommandHandlersTest.planSparseRouteExpandsNonAdjacentWaypointsIntoAPerHexPath",
        expect_green="相邻 waypoints 的两条等价性用例（该子域上变异体与原件等价）",
    ),
    dict(
        label="t6m2",
        target=OPS,
        name="t6m2_UnitOperations.java",
        reason="段不可达时静默截断（orElse(List.of())，不抛）（计划 m2）",
        edits=[
            (
                ".orElseThrow(() -> new IllegalArgumentException(\"稀疏路线的段不可达: \" + from + \" → \" + to));",
                ".orElse(List.of());",
            )
        ],
        frag=".orElse(List.of())",
        frag_dir="revert",
        expect_red="UnitOperationsTest.expandSparsePathRejectsAnUnreachableSegment / "
        "UnitCommandHandlersTest.planSparseRouteRejectsAnUnreachableSegment",
        expect_green="可达路径的展开用例（截断只在 empty 分支上换行为）",
    ),
    dict(
        label="t6m3",
        target=OPS,
        name="t6m3_UnitOperations.java",
        reason="删掉起点校验（planRoute 里既有那一处；spec §二.2 说它不动）（计划 m3）",
        edits=[
            (
                "    HexCoord routeStart = route.waypoints().get(0);\n"
                "    if (!start.equals(routeStart)) {\n"
                '      throw new IllegalArgumentException("路线起点 " + routeStart + " 不是单位在 " + at + " 的位置 " + start);\n'
                "    }\n",
                "",
            )
        ],
        frag="if (!start.equals(routeStart)) {",
        frag_dir="delete",
        expect_red="UnitOperationsTest.planSparseRouteRequiresAStartThatMatchesTheEffectivePosition / "
        "UnitCommandHandlersTest.planSparseRouteRejectsStartThatIsNotTheEffectivePosition"
        "（★ 同时红既有的 PlanRoute 两条——起点校验在**既有代码**里，本轮没复制第二份）",
        expect_green="不碰起点校验的用例",
    ),
    dict(
        label="t6m4",
        target=OPS,
        name="t6m4_UnitOperations.java",
        reason="跨段重复时顺手去重（R4 的静默变体：把拒绝换成另一条拒绝理由）",
        edits=[
            ("    return List.copyOf(path);", "    return List.copyOf(new LinkedHashSet<>(path));")
        ],
        frag="List.copyOf(new LinkedHashSet<>(path))",
        frag_dir="revert",
        expect_red="UnitOperationsTest.planSparseRouteRejectsACrossSegmentRepeat / "
        "UnitCommandHandlersTest.planSparseRouteRejectsACrossSegmentRepeat（判的是**理由文本**'重复'）",
        expect_green="★ 只断言 Rejected 的写法会放过本变异体（去重后 Route 仍会因'首尾'拒）",
    ),
    dict(
        label="t6m5",
        target=HANDLER,
        name="t6m5_PlanSparseRouteHandler.java",
        reason="handler 不用注入的 MovementCost，写死 TerrainMovementCost.INSTANCE（裁定 U3 的靶子）",
        edits=[
            (
                "import io.mosire.simos.unit.move.MovementCost;",
                "import io.mosire.simos.unit.move.MovementCost;\n"
                "import io.mosire.simos.unit.move.TerrainMovementCost;",
            ),
            (
                "UnitOperations.planSparseRoute(snapshot.state(), id, map, waypoints, cost, at);",
                "UnitOperations.planSparseRoute(\n"
                "              snapshot.state(), id, map, waypoints, TerrainMovementCost.INSTANCE, at);",
            ),
        ],
        frag="waypoints, TerrainMovementCost.INSTANCE, at",
        frag_dir="revert",
        expect_red="UnitCommandHandlersTest.planSparseRouteUsesTheInjectedMovementCost"
        "（同一条载荷：注入封路替身 ⇒ 应拒；写死地形 ⇒ 照走）",
        expect_green="注入地形成本的其余用例（与原件等价）",
    ),
    dict(
        label="t6m6",
        target=HANDLER,
        name="t6m6_PlanSparseRouteHandler.java",
        reason="mapOf 静默兜底（缺切片/类型不符 ⇒ 返回 null，不炸）——裁定 U3 里'不许静默兜底'的靶子",
        edits=[
            (
                "    Snapshot snapshot =\n"
                "        state.module(\"map\").orElseThrow(() -> new IllegalStateException(\"state 里没有 map 切片（装配故障）\"));\n"
                "    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {\n"
                "      throw new IllegalStateException(\n"
                '          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());\n'
                "    }\n"
                "    return mapSnapshot.map();\n",
                "    Snapshot snapshot = state.module(\"map\").orElse(null);\n"
                "    return snapshot instanceof MapSnapshot mapSnapshot ? mapSnapshot.map() : null;\n",
            )
        ],
        # ★ 首轮（t6m6.log.VOID-1）作废的根因：变异体把 Snapshot 变成未用 import ⇒ Checkstyle
        #   UnusedImports ⇒ 构建没跑到 surefire。这里改成仍用 Snapshot 的写法（自证片段随之改为
        #   `state.module("map").orElse(null)`——同样是"只可能出现在代码里"的片段）。
        frag='state.module("map").orElse(null)',
        frag_dir="revert",
        expect_red="UnitCommandHandlersTest.planSparseRouteBlowsUpWhenTheMapSliceIsMissing / "
        "planSparseRouteBlowsUpWhenTheMapSliceIsNotAMapSnapshot（兜底成 null ⇒ 变成 NPE，不再当场炸装配故障）",
        expect_green="装配正常的世界里的全部用例",
    ),
    dict(
        label="t6m7",
        target=OPS,
        name="t6m7_UnitOperations.java",
        reason="withUnit 回到 1 参兼容构造器 new UnitState(next)（T5-L4 靶子；本轮的**新路径**也走这里）",
        edits=[
            (
                "    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）\n"
                "    return state.withUnits(next);",
                "    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）\n"
                "    return new UnitState(next);",
            )
        ],
        frag="return new UnitState(next);",
        frag_dir="revert",
        expect_red="UnitOperationsTest.planSparseRouteKeepsTheCommandChains（本轮新路径）"
        "（★ 同时红 T5 的既有保链三条 + SPI 的命令边界保链用例——这一行是它们的公共路径）",
        expect_green="不带链的状态上的全部用例（清空 Map.of() 与 Map.of() 是恒等）",
    ),
    dict(
        label="t6m8",
        target=HANDLER,
        name="t6m8_PlanSparseRouteHandler.java",
        reason="type() 返回 unit.PlanRoute（复制粘贴型错名；T9 按 type 注册 ⇒ 这条字符串就是命令名）",
        edits=[
            ('    return "unit.PlanSparseRoute";', '    return "unit.PlanRoute";'),
        ],
        frag='return "unit.PlanRoute";',
        frag_dir="revert",
        expect_red="UnitCommandHandlersTest.planSparseRouteTypeNameMatchesTheSpecTable",
        expect_green="其余全部（type 只在注册处用）",
    ),
]


def md5(path):
    return hashlib.md5(path.read_bytes()).hexdigest()


def main():
    manifest = []
    for spec in MUTANTS:
        src_path = REPO / spec["target"]
        orig_text = src_path.read_text(encoding="utf-8")
        text = orig_text
        for old, new in spec["edits"]:
            hits = text.count(old)
            if hits != 1:
                print(f"ABORT({spec['label']}): old 片段出现 {hits} 次（要求恰好 1 次）: {old!r}")
                return 3
            text = text.replace(old, new)
        out_path = OUT / spec["name"]
        out_path.write_text(text, encoding="utf-8")
        if md5(out_path) == md5(src_path):
            print(f"ABORT({spec['label']}): 变异体字节与原件相同 —— 该轮作废")
            return 3
        # ★ ⑩ 门禁自证：逐条代码片段，按方向判（revert：原件 0 / 变异体 ≥1；delete：原件 ≥1 / 变异体 0）
        orig_hits = orig_text.count(spec["frag"])
        mut_hits = text.count(spec["frag"])
        if spec["frag_dir"] == "revert":
            if orig_hits != 0 or mut_hits < 1:
                print(
                    f"ABORT({spec['label']}): 自证未过（revert 要求 原件 0 / 变异体 ≥1）: "
                    f"orig_hits={orig_hits} mut_hits={mut_hits} frag={spec['frag']!r}"
                )
                return 3
        elif spec["frag_dir"] == "delete":
            if orig_hits < 1 or mut_hits != 0:
                print(
                    f"ABORT({spec['label']}): 自证未过（delete 要求 原件 ≥1 / 变异体 0）: "
                    f"orig_hits={orig_hits} mut_hits={mut_hits} frag={spec['frag']!r}"
                )
                return 3
        else:
            print(f"ABORT({spec['label']}): 未知 frag_dir: {spec['frag_dir']!r}")
            return 3
        manifest.append(
            "\n".join(
                [
                    f"label {spec['label']}",
                    f"target {spec['target']}",
                    f"mutant {spec['name']}",
                    f"reason {spec['reason']}",
                    f"frag {spec['frag']}",
                    f"frag_dir {spec['frag_dir']}",
                    f"expect_red {spec['expect_red']}",
                    f"expect_green {spec['expect_green']}",
                    f"orig_md5 {md5(src_path)}",
                    f"mutant_md5 {md5(out_path)}",
                ]
            )
        )
        print(f"OK {spec['label']}: {spec['name']} frag={spec['frag']!r} dir={spec['frag_dir']}")
    (OUT / "manifest.txt").write_text("\n\n".join(manifest) + "\n", encoding="utf-8")
    print(f"manifest={OUT / 'manifest.txt'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
