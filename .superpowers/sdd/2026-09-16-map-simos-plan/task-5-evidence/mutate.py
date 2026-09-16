"""Task 5 变异实验室：按 id 把一条变异写进目标源文件。

用法： python3 mutate.py <m5v-N>
每条变异是**若干步唯一匹配**的精确替换（通常是 1 步）；匹配数 != 1 直接抛，避免"以为改了其实没改"。
变异体**就写在目标文件里**（文件名天然是目标类名），不做"按变异文件名拷入"那种会变成编译错误的事。

★ 一处教训（m5v-7 第 1 次跑实测）：把 `Collections.unmodifiableMap(copy)` 换成 `Map.copyOf(copy)`
会**留下未使用的 import**，而 checkstyle 挂在 validate 阶段 ⇒ 构建在 surefire **之前**就失败
（"Unused import - java.util.Collections"）。那不是断言红，本轮必须作废 —— 故 m5v-7 是**两步**：
先摘掉那条 import，再换实现。
"""

import sys

SRC = "/home/cna/SimulatorMosire/simos-map/src/main/java/io/mosire/simos"

MUTATIONS = {
    # ★ L2：给 HexCell 加回一个连通性组件（老仓的 edgeTags 形态）。
    #   加组件会改掉 record 的规范构造器签名 ⇒ 必须同时补一个 2 参数兼容构造器，
    #   否则既有调用点**编译不过**，"红"就变成 COMPILATION ERROR、断言根本没跑（M2 Task 1 踩过的坑）。
    "m5v-1": (
        "map/HexCell.java",
        [
            (
                "public record HexCell(String terrain, double height) {\n",
                """public record HexCell(String terrain, double height, java.util.Map<String, String> edgeTags) {

  /** 变异 m5v-1：2 参数兼容构造器，只为让既有调用点编得过。 */
  public HexCell(String terrain, double height) {
    this(terrain, height, java.util.Map.of());
  }
""",
            )
        ],
    ),
    # ★ 给 GameMap 加回 gridSize 死字段（同样要补 8 参数兼容构造器）
    "m5v-2": (
        "map/GameMap.java",
        [
            (
                """    Map<EdgeRef, EdgeTags> edges,
    GenerationSpec spec) {
""",
                """    Map<EdgeRef, EdgeTags> edges,
    GenerationSpec spec,
    int gridSize) {

  /** 变异 m5v-2：8 参数兼容构造器，只为让既有调用点编得过。 */
  public GameMap(
      Map<HexCoord, HexCell> hexes,
      Map<RegionId, Region> regions,
      Map<CityId, City> cities,
      Map<String, TerrainType> terrainTypes,
      Map<PathwayId, Pathway> pathways,
      Map<String, PathwayGroup> pathwayGroups,
      Map<EdgeRef, EdgeTags> edges,
      GenerationSpec spec) {
    this(hexes, regions, cities, terrainTypes, pathways, pathwayGroups, edges, spec, 30);
  }
""",
            )
        ],
    ),
    # ★ 保序的**实际落点**：拷贝那一步改用 Map.copyOf（不保序）。
    #   现场记（2026-09-17）：目标原本是紧凑构造器里的 `return Collections.unmodifiableMap(copy);`
    #   —— 门禁在 Task 5 收尾时实测出 7 条 EI_EXPOSE_REP，冻结那一步已挪到**赋值处**（SpotBugs 只认看得见的
    #   unmodifiableMap），故此处改指 `copyOf` 的返回。语义不变：都是"把保序的拷贝换成散序的 Map.copyOf"。
    #   此处**不会**留下未使用的 import：Collections 仍被构造器用着。
    "m5v-3": (
        "map/GameMap.java",
        [
            (
                """    return copy;
""",
                """    return Map.copyOf(copy); // 变异 m5v-3：拷贝改用 Map.copyOf（不保序）
""",
            )
        ],
    ),
    # ★ brief 第 3 行的**字面落点**：empty() 里改用 Map.copyOf。
    #   empty() 的 7 个 map 全是空的 ⇒ 预期**不红**（空 map 只有一个迭代序）。
    "m5v-4": (
        "map/GameMap.java",
        [
            (
                """        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
""",
                """        Map.copyOf(Map.of()), // 变异 m5v-4：empty() 里改用 Map.copyOf
        Map.copyOf(Map.of()),
        Map.copyOf(Map.of()),
        Map.copyOf(Map.of()),
        Map.copyOf(Map.of()),
        Map.copyOf(Map.of()),
        Map.copyOf(Map.of()),
""",
            )
        ],
    ),
    # ★ withHexes 顺手把 regions 的第一项丢掉 —— "只动一个组件"的钉子要抓的就是它
    "m5v-5": (
        "map/GameMap.java",
        [
            (
                """  public GameMap withHexes(Map<HexCoord, HexCell> v) {
    return new GameMap(v, regions, cities, terrainTypes, pathways, pathwayGroups, edges, spec);
  }
""",
                """  public GameMap withHexes(Map<HexCoord, HexCell> v) {
    // 变异 m5v-5：顺手把 regions 的第一项丢掉
    Map<RegionId, Region> tampered = new LinkedHashMap<>(regions);
    if (!tampered.isEmpty()) {
      tampered.remove(tampered.keySet().iterator().next());
    }
    return new GameMap(v, tampered, cities, terrainTypes, pathways, pathwayGroups, edges, spec);
  }
""",
            )
        ],
    ),
    # ★ withRegions 把每个 Region 的 boundary 抹成空环（U2 之前的状态）
    "m5v-6": (
        "map/GameMap.java",
        [
            (
                """  public GameMap withRegions(Map<RegionId, Region> v) {
    return new GameMap(hexes, v, cities, terrainTypes, pathways, pathwayGroups, edges, spec);
  }
""",
                """  public GameMap withRegions(Map<RegionId, Region> v) {
    // 变异 m5v-6：把每个 Region 的 boundary 抹成空环
    Map<RegionId, Region> wiped = new LinkedHashMap<>();
    for (Map.Entry<RegionId, Region> e : v.entrySet()) {
      Region r = e.getValue();
      wiped.put(
          e.getKey(),
          new Region(
              r.id(),
              r.name(),
              r.hexes(),
              new io.mosire.simos.map.region.RegionBoundary(java.util.List.of()),
              r.meta()));
    }
    return new GameMap(hexes, wiped, cities, terrainTypes, pathways, pathwayGroups, edges, spec);
  }
""",
            )
        ],
    ),
    # ★ City.props 改用 Map.copyOf（不保序）。**两步**：先摘 import（否则 checkstyle 先于 surefire 报
    #   "Unused import"，红就不是断言红了），再换实现。
    "m5v-7": (
        "map/City.java",
        [
            ("import java.util.Collections;\n", ""),
            (
                """    props = Collections.unmodifiableMap(copy);
""",
                """    props = Map.copyOf(copy); // 变异 m5v-7：改用 Map.copyOf（不保序）
""",
            ),
        ],
    ),
}


def main() -> None:
    mutation_id = sys.argv[1]
    relative_path, steps = MUTATIONS[mutation_id]
    path = f"{SRC}/{relative_path}"
    text = open(path, encoding="utf-8").read()
    for old, new in steps:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{mutation_id}: 期望 1 处匹配，实得 {count} —— 变异未可靠落盘，作废")
        text = text.replace(old, new, 1)
    open(path, "w", encoding="utf-8").write(text)
    print(f"{mutation_id}: mutated {relative_path}（{len(steps)} 步）")


main()
