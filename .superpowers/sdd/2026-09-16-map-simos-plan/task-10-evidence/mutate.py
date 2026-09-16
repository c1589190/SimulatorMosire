"""Task 10 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

用法：
  python3 mutate.py <m10v-N>      # 把第 N 条变异写进 $REPO
  python3 mutate.py --files <id>  # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

★ 装置形态继承 Task 4~9（同一套坑照旧）：
  ① 变异不得留下**未使用的 import**，也不得让 javac 报错 —— checkstyle 挂在 validate、编译在它之后，
     "红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
  ② 不按变异文件名拷入 —— 变异体**就写在目标类名的文件里**；
  ③ 每轮**显式声明**文件集合，run.sh 的自证是"只有声明的这些变了"。

★ 本任务与 Task 9 的不同：变异对象是**生成器**（不是纯函数分类器）。三条注意：
  - brief-2（给 generate 加形参）的**等价形态**是"新增一个带额外形参的 generate 重载" ——
    字面改签名会让 MapGeneratorTest 编译不过（COMPILATION ERROR），红点就不是断言红；
  - brief-3（结果不带 spec）无法写成"传 null"（GameMap 的构造期守卫会抛 IAE，那是**别的**红）——
    等价形态是"带别的 spec"（空图的规范 spec）：结果不再携带**自己**的 spec，这正是要抓的；
  - m-6（删海平面检查）之后 seaLevel / coastNoise / OCEAN 没有消费者 —— 局部变量与私有常量，
    javac 与 checkstyle 都不报（SpotBugs 只在 verify 跑，本装置的测试命令是 test）。
"""

import sys

REPO = "/tmp/m10lab/repo"
GEN = "simos-map/src/main/java/io/mosire/simos/map/generate/"
CLS = GEN + "MapGenerator.java"

# 变异体共用的锚点（原件文本，逐字取自 spotless 后的工作树）
ANCHOR_MAP_CONSTRUCTION = (
    "        hexes, Map.of(), Map.of(), TerrainCatalog.defaults(), Map.of(), Map.of(), Map.of(), spec);"
)

ANCHOR_GENERATE = """  public static GameMap generate(GenerationSpec spec) {
    return new MapGenerator(spec).build();
  }"""


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
    return [CLS]


def mutate(mid):
    # ── brief-1：脊线布局的 Random 不再从 spec.seed() 派生（噪声那侧仍用入参 seed）──
    if mid == "m10v-1":
        replace_once(
            CLS,
            "    this.rng = new Random(spec.seed());",
            "    this.rng = new Random(); // 变异 brief-1：不用入参 seed",
        )

    # ── brief-2：多出一个带额外形参的 generate 重载（等价形态，见模块注释）──
    elif mid == "m10v-2":
        replace_once(
            CLS,
            ANCHOR_GENERATE,
            ANCHOR_GENERATE
            + """

  // 变异 brief-2 的等价形态：第二个 generate 入口（多一个形参）。
  public static GameMap generate(GenerationSpec spec, int extra) {
    return new MapGenerator(spec).build();
  }""",
        )

    # ── brief-3：结果带的是**别人的** spec（空图的规范 spec），不再是它自己的 ──
    elif mid == "m10v-3":
        replace_once(
            CLS,
            ANCHOR_MAP_CONSTRUCTION,
            """        hexes,
        Map.of(),
        Map.of(),
        TerrainCatalog.defaults(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L)); // 变异 brief-3：结果不带自己的 spec""",
        )

    # ── brief-4：terrainTypes 改用 Map.copyOf（散列序打乱落盘序）──
    elif mid == "m10v-4":
        replace_once(
            CLS,
            ANCHOR_MAP_CONSTRUCTION,
            """        hexes,
        Map.of(),
        Map.of(),
        Map.copyOf(TerrainCatalog.defaults()), // 变异 brief-4：词表改用 Map.copyOf
        Map.of(),
        Map.of(),
        Map.of(),
        spec);""",
        )

    # ── m-5：不接湿度通道 ⇒ 湿度恒 0.5（沙漠门恒不过）──
    elif mid == "m10v-5":
        replace_once(
            CLS,
            """    double moisture =
        noise.noise2(
            px * bands.moistureFreq() + MOISTURE_PHASE, py * bands.moistureFreq() + MOISTURE_PHASE);""",
            "    double moisture = 0.0; // 变异 m-5：湿度恒 (0+1)/2 = 0.5",
        )

    # ── m-6：删掉海平面检查（永不判水）──
    elif mid == "m10v-6":
        replace_once(
            CLS,
            """    // ★ R-10-f：判水在分类**之前**（GSimulator `:167`）。水下的高度**原样记**（不记 0）—— 它是同一根管线的产物。
    if (height < seaLevel) {
      return new HexCell(OCEAN, height);
    }
""",
            "    // 变异 m-6：海平面检查整段删除\n",
        )

    # ── m-7：域扭曲的位移幅度 10 → 0（高度管线改一处）──
    elif mid == "m10v-7":
        replace_once(
            CLS,
            """    double wpx =
        px + noise.noise2(px * bands.warpFreq(), py * bands.warpFreq()) * bands.warpAmplitude();""",
            "    double wpx =\n"
            "        px + noise.noise2(px * bands.warpFreq(), py * bands.warpFreq()) * 0; // 变异 m-7：幅度 10 → 0",
        )

    # ── m-8：格子枚举不排序（直接迭代 withinRadius 的 Set）──
    elif mid == "m10v-8":
        replace_once(
            CLS,
            """    for (HexCoord coord :
        HexGrid.withinRadius(new HexCoord(0, 0), radius).stream().sorted().toList()) {""",
            "    for (HexCoord coord :\n"
            "        HexGrid.withinRadius(new HexCoord(0, 0), radius)) { // 变异 m-8：不排序",
        )

    else:
        raise AssertionError(f"未知变异 id: {mid}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--files":
        print(" ".join(files_of(sys.argv[2])))
        sys.exit(0)
    mutate(sys.argv[1])
