"""Task 14 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

用法：
  python3 mutate.py <m14v-N>      # 把第 N 条变异写进 $REPO
  python3 mutate.py --files <id>  # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

★ 装置形态继承 Task 4~13（同一套坑照旧）：
  ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
     编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
  ② 不按变异文件名拷入 —— 变异体**就写在目标类名的文件里**（新增文件也用规范类名）；
  ③ 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。

★ 本任务的形态注意（与 Task 12/13 的关键差别，R-14-d）：
  - 变异体**包含新增文件**（m14v-3/m14v-4/m14v-9）——md5sum -c 看不见它们，自证走"文件存在且非空"，
    并集断言由 run.sh 做；
  - m14v-2 给 HexCell 加组件但**保留旧签名的二级构造器**——直接加组件会让全仓 new HexCell(...) 编译失败，
    "红在编译错误"不算数；
  - m14v-8 同理：12 参数构造器是**二级**构造器（委托规范构造器），既有调用点一概不动。
"""

import os
import sys

REPO = "/tmp/m14lab/repo"
MAP = "simos-map/src/main/java/io/mosire/simos/map/"
CLS_CHANGESET = MAP + "change/MapChangeSet.java"
CLS_HEXCELL = MAP + "HexCell.java"
CLS_DIRTABLE = MAP + "hex/LegacyDirTable.java"
CLS_PROVINCE = MAP + "region/Province.java"
CLS_REGIONINDEX = MAP + "region/RegionIndex.java"
CLS_CITY = MAP + "City.java"
CLS_MAPGEN = MAP + "generate/MapGenerator.java"
CLS_GAMEMAP = MAP + "GameMap.java"
CLS_BACKUPTABLE = MAP + "terrain/BackupTerrainTable.java"


def read(path):
    return open(f"{REPO}/{path}", encoding="utf-8").read()


def write(path, text):
    with open(f"{REPO}/{path}", "w", encoding="utf-8") as f:
        f.write(text)


def replace_once(path, old, new):
    """改且只改一处：old 必须**恰好出现一次**，否则抛（防"锚点漂移后改错地方"）。"""
    text = read(path)
    n = text.count(old)
    if n != 1:
        raise AssertionError(f"{path}: 锚点出现 {n} 次（要求恰好 1 次）:\n{old}")
    write(path, text.replace(old, new, 1))


def files_of(mid):
    return {
        "m14v-1": [CLS_CHANGESET],
        "m14v-2": [CLS_HEXCELL],
        "m14v-3": [CLS_DIRTABLE],
        "m14v-4": [CLS_PROVINCE],
        "m14v-5": [CLS_REGIONINDEX],
        "m14v-6": [CLS_CITY],
        "m14v-7": [CLS_MAPGEN],
        "m14v-8": [CLS_GAMEMAP],
        "m14v-9": [CLS_BACKUPTABLE],
    }[mid]


def mutate(mid):
    # ── 变异表 1：between 里把 edges 的比较摘掉（L1 的病灶形态）──
    if mid == "m14v-1":
        replace_once(
            CLS_CHANGESET,
            "        diff(base.edges(), target.edges()));",
            "        new FieldDelta.Unchanged<>()); // 变异 m14v-1：edges 的比较被摘掉",
        )

    # ── 变异表 2：HexCell 加回第三份组件 edgeTags（L2 的病灶形态）──
    elif mid == "m14v-2":
        replace_once(
            CLS_HEXCELL,
            "package io.mosire.simos.map;\n",
            "package io.mosire.simos.map;\n\nimport java.util.Map;\n",
        )
        replace_once(
            CLS_HEXCELL,
            "public record HexCell(String terrain, double height) {",
            "public record HexCell(String terrain, double height, Map<String, String> edgeTags) {\n"
            "\n"
            "  /** 变异 m14v-2：旧签名保留（老调用点不破），新组件即 GSimulator 第二份连通性存储的复活。 */\n"
            "  public HexCell(String terrain, double height) {\n"
            "    this(terrain, height, Map.of());\n"
            "  }",
        )

    # ── 变异表 3：第二份方向表（L3 的病灶形态，新增文件）──
    elif mid == "m14v-3":
        write(
            CLS_DIRTABLE,
            "package io.mosire.simos.map.hex;\n"
            "\n"
            "/** 变异 m14v-3：第二份方向表（GSimulator 的 DIRS 形态——各消费方复制一份的旧病）。 */\n"
            "final class LegacyDirTable {\n"
            "\n"
            "  static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};\n"
            "\n"
            "  private LegacyDirTable() {}\n"
            "}\n",
        )

    # ── 变异表 4：第二个 region 类型（L4 的病灶形态，新增文件）──
    elif mid == "m14v-4":
        write(
            CLS_PROVINCE,
            "package io.mosire.simos.map.region;\n"
            "\n"
            "/** 变异 m14v-4：GSimulator 的 Province 复活——第二个 region 概念。 */\n"
            "public record Province(String name) {}\n",
        )

    # ── 变异表 5：regionOf 改线性扫描（L5 的病灶形态）──
    elif mid == "m14v-5":
        replace_once(
            CLS_REGIONINDEX,
            "  public RegionId regionOf(HexCoord c) {\n"
            "    return byHex.get(c);\n"
            "  }",
            "  public RegionId regionOf(HexCoord c) {\n"
            "    for (var entry : byHex.entrySet()) { // 变异 m14v-5：线性扫描代替一次 Map.get（答案不变，计数分叉）\n"
            "      if (entry.getKey().equals(c)) {\n"
            "        return entry.getValue();\n"
            "      }\n"
            "    }\n"
            "    return null;\n"
            "  }",
        )

    # ── 变异表 6：某处手写 q + "_" + r（L6 的病灶形态）──
    elif mid == "m14v-6":
        replace_once(
            CLS_CITY,
            "    props = Collections.unmodifiableMap(copy);\n  }\n}",
            "    props = Collections.unmodifiableMap(copy);\n"
            "  }\n"
            "\n"
            "  /** 变异 m14v-6：手写 q_r 拼接——第二份坐标串实现（L6 的病灶形态）。 */\n"
            "  public String atKey() {\n"
            "    return at.q() + \"_\" + at.r();\n"
            "  }\n"
            "}",
        )

    # ── 变异表 7：结果不带 spec（L7 的病灶形态：种子归零）──
    elif mid == "m14v-7":
        replace_once(
            CLS_MAPGEN,
            "    return new GameMap(\n"
            "        hexes, Map.of(), Map.of(), TerrainCatalog.defaults(), Map.of(), Map.of(), Map.of(), spec);",
            "    return new GameMap(\n"
            "        hexes, Map.of(), Map.of(), TerrainCatalog.defaults(), Map.of(), Map.of(), Map.of(),\n"
            "        GenerationSpec.defaults(0L)); // 变异 m14v-7：结果不带 spec——种子归零，落盘点丢了",
        )

    # ── 变异表 8：12 参数构造器复活（L8 的病灶形态）──
    elif mid == "m14v-8":
        replace_once(
            CLS_GAMEMAP,
            "    if (spec == null) {\n"
            '      throw new IllegalArgumentException("spec 不得为 null：空图的种子用 GenerationSpec.defaults(0L)");\n'
            "    }\n"
            "  }",
            "    if (spec == null) {\n"
            '      throw new IllegalArgumentException("spec 不得为 null：空图的种子用 GenerationSpec.defaults(0L)");\n'
            "    }\n"
            "  }\n"
            "\n"
            "  /** 变异 m14v-8：12 参数构造器复活（GSimulator MapData 的复制形态，多出的 4 个是死值）。 */\n"
            "  public GameMap(\n"
            "      Map<HexCoord, HexCell> hexes,\n"
            "      Map<RegionId, Region> regions,\n"
            "      Map<CityId, City> cities,\n"
            "      Map<String, TerrainType> terrainTypes,\n"
            "      Map<PathwayId, Pathway> pathways,\n"
            "      Map<String, PathwayGroup> pathwayGroups,\n"
            "      Map<EdgeRef, EdgeTags> edges,\n"
            "      GenerationSpec spec,\n"
            "      int gridSize,\n"
            "      boolean hexOrientation,\n"
            "      String rivers,\n"
            "      String roads) {\n"
            "    this(hexes, regions, cities, terrainTypes, pathways, pathwayGroups, edges, spec);\n"
            "  }",
        )

    # ── 变异表 9：第二份地形词表（L9 的病灶形态，新增文件）──
    elif mid == "m14v-9":
        write(
            CLS_BACKUPTABLE,
            "package io.mosire.simos.map.terrain;\n"
            "\n"
            "import java.util.List;\n"
            "\n"
            "/** 变异 m14v-9：第二份地形词表（GSimulator 的 9 份副本形态）。 */\n"
            "public final class BackupTerrainTable {\n"
            "\n"
            "  public static final List<String> KEYS =\n"
            "      List.of(\"ocean\", \"plains\", \"desert\", \"low_hills\", \"mountains\", \"plateau\", \"plateau_mountains\");\n"
            "\n"
            "  private BackupTerrainTable() {}\n"
            "}\n",
        )

    else:
        raise AssertionError(f"未知变异 id: {mid}")

    for path in files_of(mid):
        if not os.path.isfile(f"{REPO}/{path}"):
            raise AssertionError(f"{mid}: 声明的文件不存在: {path}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--files":
        print(" ".join(files_of(sys.argv[2])))
        sys.exit(0)
    mutate(sys.argv[1])
