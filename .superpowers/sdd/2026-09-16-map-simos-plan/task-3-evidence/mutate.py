"""Task 3 变异实验室：按 id 把一条变异写进目标源文件。

用法： python3 mutate.py <m3v-N>
每条变异都是**唯一匹配**的精确替换；匹配数 != 1 直接抛，避免"以为改了其实没改"。
变异体**就写在目标文件里**（文件名天然是目标类名），不做"按变异文件名拷入"那种会变成编译错误的事。
"""

import sys

SRC = "/home/cna/SimulatorMosire/simos-map/src/main/java/io/mosire/simos"

MUTATIONS = {
    # ★ U2 的钉子本身：删掉"重算并比对"
    "m3v-1": (
        "map/region/Region.java",
        """    // ★ U2 的钉子：边界必须与 hexes 一致。这一步让"漂移"在构造期就不可能存在。
    RegionBoundary recomputed = RegionBoundary.of(hexes);
    if (!recomputed.equals(boundary)) {
      throw new IllegalArgumentException(
          "boundary 与 hexes 不一致：hexes 重算得 " + recomputed + "，传入的是 " + boundary);
    }
""",
        """    // 变异 m3v-1：删掉"重算并比对"
""",
    ),
    # withHexes 保持旧内容（与边界自洽 ⇒ 不触发构造器，红的必须来自断言）
    "m3v-2": (
        "map/region/Region.java",
        """    return Region.of(id, name, newHexes, meta);""",
        """    return Region.of(id, name, hexes, meta); // 变异 m3v-2：内容取旧的""",
    ),
    # withHexes 原样传旧 boundary（内容新、边界旧 ⇒ 触发构造器校验）
    "m3v-3": (
        "map/region/Region.java",
        """    return Region.of(id, name, newHexes, meta);""",
        """    return new Region(id, name, newHexes, boundary, meta); // 变异 m3v-3：不重算边界""",
    ),
    # withHexes 拿 name 当 id
    "m3v-10": (
        "map/region/Region.java",
        """    return Region.of(id, name, newHexes, meta);""",
        """    return Region.of(new RegionId(name), name, newHexes, meta); // 变异 m3v-10：拿 name 当 id""",
    ),
    # 删掉"度 ≠ 2 则抛"的护栏
    "m3v-4": (
        "map/region/RegionBoundary.java",
        """    for (Map.Entry<HexVertex, List<HexVertex>> entry : adjacency.entrySet()) {
      if (entry.getValue().size() != VERTEX_DEGREE) {
        throw new IllegalStateException(
            "边界顶点 " + entry.getKey() + " 的度为 " + entry.getValue().size() + "，应为 " + VERTEX_DEGREE);
      }
    }
""",
        """    // 变异 m3v-4：删掉"度 ≠ 2 则抛"
""",
    ),
    # 偏移表 W[1]：1 → -1
    "m3v-5": (
        "map/hex/HexVertex.java",
        """  private static final int[] W = {-1, 1, 2, 1, -1, -2};""",
        """  private static final int[] W = {-1, -1, 2, 1, -1, -2};""",
    ),
    # 删掉环表排序
    "m3v-6": (
        "map/region/RegionBoundary.java",
        """    canonical.sort((a, b) -> a.getFirst().compareTo(b.getFirst()));""",
        """    // 变异 m3v-6：删掉环表排序""",
    ),
    # 删掉"取字典序较小方向"
    "m3v-7": (
        "map/region/RegionBoundary.java",
        """    List<HexVertex> canonical = compareSequences(rotated, reversed) <= 0 ? rotated : reversed;""",
        """    List<HexVertex> canonical = rotated; // 变异 m3v-7：不做方向规范化""",
    ),
    # 删掉"旋到最小顶点开头"
    "m3v-8": (
        "map/region/RegionBoundary.java",
        """    List<HexVertex> rotated = rotateToSmallest(ring);""",
        """    List<HexVertex> rotated = ring; // 变异 m3v-8：不做起点规范化""",
    ),
    # regionOf 退化成遍历全表（值仍正确）
    "m3v-9": (
        "map/region/RegionIndex.java",
        """  public RegionId regionOf(HexCoord c) {
    return byHex.get(c);
  }""",
        """  public RegionId regionOf(HexCoord c) {
    // 变异 m3v-9：遍历全表线性找（值仍正确，只有查表次数变了）
    for (Map.Entry<HexCoord, RegionId> entry : byHex.entrySet()) {
      if (entry.getKey().equals(c)) {
        return entry.getValue();
      }
    }
    return null;
  }""",
    ),
    # 删掉"按 id 字典序"的排序（重叠裁决退化成人参顺序）
    "m3v-12": (
        "map/region/RegionIndex.java",
        """    ordered.sort((a, b) -> a.id().value().compareTo(b.id().value()));
""",
        """    // 变异 m3v-12：删掉按 id 字典序的排序
""",
    ),
    # R-48-f 加分项：删掉手写的 toString（退回 record 默认实现）
    "m3v-13": (
        "map/region/RegionId.java",
        """  /** 裸值。见类注释：它是**地址形式**，供变更集的 String key 使用，**不是**给人看的调试输出。 */
  @Override
  public String toString() {
    return value;
  }

""",
        "",
    ),
}


def main() -> None:
    mutation_id = sys.argv[1]
    relative_path, old, new = MUTATIONS[mutation_id]
    path = f"{SRC}/{relative_path}"
    text = open(path, encoding="utf-8").read()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{mutation_id}: 期望 1 处匹配，实得 {count} —— 变异未可靠落盘，作废")
    open(path, "w", encoding="utf-8").write(text.replace(old, new))
    print(f"{mutation_id}: mutated {relative_path}")


main()
