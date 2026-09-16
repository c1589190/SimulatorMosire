"""Task 7 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

用法：
  python3 mutate.py <m7v-N>      # 把第 N 条变异写进 $REPO
  python3 mutate.py --files <id> # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

★ 与 Task 4/5/6 的装置同形，两条继承的坑照旧：
  ① 变异不得留下**未使用的 import**（checkstyle 挂在 validate，会抢在 surefire 之前失败，
     "红"就不是断言红了）；② 不按变异文件名拷入 —— 变异体**就写在目标类名的文件里**。
★ 本任务新增的一处：**有的轮的变异体必须跨文件共适应才编得过**（V3 的"加了字段、改了构造、
  忘了变更集"正是如此）。故每轮**显式声明**文件集合，run.sh 的自证是"只有声明的这些变了"，
  而不是"只有一个文件变了"。声明写在 .kept 里，看得见。
"""

import sys

REPO = "/tmp/m7lab/repo"
MCS = "simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java"
GM = "simos-map/src/main/java/io/mosire/simos/map/GameMap.java"
GMT = "simos-map/src/test/java/io/mosire/simos/map/GameMapTest.java"
MCST = "simos-map/src/test/java/io/mosire/simos/map/change/MapChangeSetTest.java"
RTC = "simos-map/src/test/java/io/mosire/simos/map/change/RoundTripComponentsTest.java"


def read(path):
    return open(f"{REPO}/{path}", encoding="utf-8").read()


def write(path, text):
    open(f"{REPO}/{path}", "w", encoding="utf-8").write(text)


def sub(text, old, new, path):
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: 期望 1 处匹配，实得 {count} —— 变异未可靠落盘，作废\n--- 找的是 ---\n{old}"
        )
    return text.replace(old, new, 1)


def sub_all(text, old, new, path, expect):
    count = text.count(old)
    if count != expect:
        raise SystemExit(f"{path}: 期望 {expect} 处匹配，实得 {count} —— 变异未可靠落盘，作废")
    return text.replace(old, new)


def insert_before_close_paren(text, marker, insert, expect, path):
    """把 insert 插到每个 marker 所开括号的**配对右括号之前**（即最后一个实参之后）。"""
    idx = 0
    done = 0
    while True:
        start = text.find(marker, idx)
        if start < 0:
            break
        depth = 0
        for k in range(start + len(marker) - 1, len(text)):
            if text[k] == "(":
                depth += 1
            elif text[k] == ")":
                depth -= 1
                if depth == 0:
                    text = text[:k] + insert + text[k:]
                    idx = k + len(insert) + 1
                    done += 1
                    break
        else:
            raise SystemExit(f"{path}: {marker} 的括号未闭合 —— 作废")
    if done != expect:
        raise SystemExit(f"{path}: {marker} 期望 {expect} 处，实得 {done} —— 作废")
    return text


# ── V3（"加了字段、改了构造、忘了变更集"）——m7v-3 / m7v-7 / m7v-8 共用的一段 ────────────

V3_HEADER_OLD = """    Map<EdgeRef, EdgeTags> edges,
    GenerationSpec spec) {"""
V3_HEADER_NEW = """    Map<EdgeRef, EdgeTags> edges,
    GenerationSpec spec,
    Map<String, String> foo) {"""

V3_JAVADOC_OLD = """ * @param spec 生成参数；**从不 null**（R-48-e）
 */"""
V3_JAVADOC_NEW = """ * @param spec 生成参数；**从不 null**（R-48-e）
 * @param foo 变异新增的状态组件 —— 变更集**没有**跟上它（这正是 V3 要演的漂移）
 */"""

V3_CTOR_OLD = """    if (spec == null) {
      throw new IllegalArgumentException("spec 不得为 null：空图的种子用 GenerationSpec.defaults(0L)");
    }
  }"""
V3_CTOR_NEW = """    if (spec == null) {
      throw new IllegalArgumentException("spec 不得为 null：空图的种子用 GenerationSpec.defaults(0L)");
    }
    foo = Collections.unmodifiableMap(copyOf(foo, "foo")); // 变异：新组件照规矩冻结
  }"""

V3_EMPTY_OLD = """        Map.of(),
        GenerationSpec.defaults(0L));"""
V3_EMPTY_NEW = """        Map.of(),
        GenerationSpec.defaults(0L),
        Map.of());"""

V3_WITHFOO_OLD = """  public GameMap withSpec(GenerationSpec v) {
    return new GameMap(hexes, regions, cities, terrainTypes, pathways, pathwayGroups, edges, v);
  }"""
V3_WITHFOO_NEW = """  public GameMap withSpec(GenerationSpec v) {
    return new GameMap(hexes, regions, cities, terrainTypes, pathways, pathwayGroups, edges, v, foo);
  }

  /** 逐组件替换。见 {@link #withHexes(Map)}。（变异：新组件的 wither —— "改了构造"那一半。） */
  public GameMap withFoo(Map<String, String> v) {
    return new GameMap(
        hexes, regions, cities, terrainTypes, pathways, pathwayGroups, edges, spec, v);
  }"""

# MapChangeSet 侧的**漂移**：变更集里没有 foo ⇒ 重建时只能从 base 带过来。
V3_DRIFT_OLD = """        base.spec());"""
V3_DRIFT_NEW = """        base.spec(),
        base.foo()); // 变异：新组件没进变更集 ⇒ 只能从 base 原样带过来（这正是"忘了变更集"）"""


def coadapt_gamemaptest_pins():
    """把 GameMapTest 的两条**组件清单钉子**按新世界更新到 9 个组件（"把提示你改的灯都改了"）。

    ★ 这一步是 m7v-7 独有的，存在的理由：不更新它们，V3 那一轮会**同时**红在 GameMapTest
      的两条钉子与 Task 7 的护栏上，"本护栏有独立判别力"就说不干净。更新之后，只剩本护栏红。
    """
    text = read(GMT)
    text = sub(
        text,
        """          "pathwayGroups",
          "edges",
          "spec");""",
        """          "pathwayGroups",
          "edges",
          "spec",
          "foo");""",
        GMT,
    )
    text = sub(
        text,
        """  void componentCountIsExactlyEight() {
    assertThat(GameMap.class.getRecordComponents()).hasSize(8);
  }""",
        """  void componentCountIsExactlyNine() {
    assertThat(GameMap.class.getRecordComponents()).hasSize(9);
  }""",
        GMT,
    )
    write(GMT, text)


def apply_v3(test_switches=False):
    """给 GameMap 加一个组件 foo，并把**所有构造点**改到编译得过；变更集不跟。"""
    text = read(GM)
    text = sub(text, V3_JAVADOC_OLD, V3_JAVADOC_NEW, GM)
    text = sub(text, V3_HEADER_OLD, V3_HEADER_NEW, GM)
    text = sub(text, V3_CTOR_OLD, V3_CTOR_NEW, GM)
    text = sub(text, V3_EMPTY_OLD, V3_EMPTY_NEW, GM)
    text = sub(text, V3_WITHFOO_OLD, V3_WITHFOO_NEW, GM)
    # 另外 7 个 wither：把新组件**原样带过去**（withSpec 已由上面那段整体改写）
    # ★ 分开两步是因为 `withEdges` 的实参是 `v` 而不是 `edges`：写死一个模式会漏掉它。
    text = sub_all(text, "edges, spec);", "edges, spec, foo);", GM, 6)
    text = sub_all(text, "pathwayGroups, v, spec);", "pathwayGroups, v, spec, foo);", GM, 1)
    write(GM, text)

    text = read(MCS)
    text = sub(text, V3_DRIFT_OLD, V3_DRIFT_NEW, MCS)
    write(MCS, text)

    for path, expect in ((GMT, 9), (MCST, 1)):
        text = read(path)
        text = insert_before_close_paren(text, "new GameMap(", ", Map.of()", expect, path)
        write(path, text)

    if test_switches:
        # ★ 也把**能提示的护栏**都更新到新世界（GameMapTest 的两条组件清单钉子），
        #   这样唯一的红就只能来自 Task 7 的护栏本身。
        coadapt_gamemaptest_pins()
        # ★ 也把测试的两个 switch 补上 case（"Failure 层"那一形态）：
        #   两个 switch 分开写，故这里必须**两处都补**。
        text = read(RTC)
        text = sub(
            text,
            """      case "edges" -> base.withEdges(oneEdge());""",
            """      case "edges" -> base.withEdges(oneEdge());
      case "foo" -> base.withFoo(Map.of("k", "v"));""",
            RTC,
        )
        text = sub(
            text,
            """          case "edges" -> cs.edges();""",
            """          case "edges" -> cs.edges();
          case "foo" -> new FieldDelta.Unchanged<String>();""",
            RTC,
        )
        write(RTC, text)


def apply_v3_compile_only():
    """只加 record 组件，**任何构造点都不动** —— 期望 javac 当场接住。"""
    text = read(GM)
    text = sub(text, V3_HEADER_OLD, V3_HEADER_NEW, GM)
    text = sub(text, V3_CTOR_OLD, V3_CTOR_NEW, GM)
    write(GM, text)


# ── 逐轮定义 ────────────────────────────────────────────────────────────────────

MUTATIONS = {
    # brief 表 V1：从 MapChangeSet 的 record 组件里删掉 edges。
    #   ★ 字面形态（组件真没了）。MapChangeSet.java 内部最小共适应 = between/isEmpty/apply 三处
    #     跟着少一路、apply 那一格用 base.edges() 顶上，并删掉随之失效的两个 import
    #     （留着会撞 checkstyle UnusedImports，红的就不是断言了）。
    "m7v-1": {
        "files": [MCS],
        "steps": [
            (
                MCS,
                [
                    (
                        """    FieldDelta<PathwayGroup> pathwayGroups,
    FieldDelta<EdgeTags> edges) {""",
                        """    FieldDelta<PathwayGroup> pathwayGroups) {""",
                    ),
                    (
                        """        diff(base.pathwayGroups(), target.pathwayGroups()),
        diff(base.edges(), target.edges()));""",
                        """        diff(base.pathwayGroups(), target.pathwayGroups()));""",
                    ),
                    (
                        """        || pathwayGroups.changed()
        || edges.changed());""",
                        """        || pathwayGroups.changed());""",
                    ),
                    (
                        """        rebuild(base.pathwayGroups(), cs.pathwayGroups(), STRING_KEY),
        rebuild(base.edges(), cs.edges(), EdgeRef::parse),
        base.spec());""",
                        """        rebuild(base.pathwayGroups(), cs.pathwayGroups(), STRING_KEY),
        base.edges(), // 变异 m7v-1：edges 已从组件里漂移出去，重建时直接拿 base 的
        base.spec());""",
                    ),
                    ("""import io.mosire.simos.map.pathway.EdgeRef;\n""", ""),
                    ("""import io.mosire.simos.map.pathway.EdgeTags;\n""", ""),
                ],
            )
        ],
    },
    # brief 表 V2：删掉 terrainTypes。
    #   ★ 与 m7v-1 不同，这一轮**留一个恒 Unchanged 的占位访问器**：组件真没了、而调用点还在，
    #     是为了把"删组件"这个方向的判别力**推到测试期**（m7v-1 那轮会证明：不留占位是编译期接住的）。
    "m7v-2": {
        "files": [MCS],
        "steps": [
            (
                MCS,
                [
                    (
                        """    FieldDelta<City> cities,
    FieldDelta<TerrainType> terrainTypes,
    FieldDelta<Pathway> pathways,""",
                        """    FieldDelta<City> cities,
    FieldDelta<Pathway> pathways,""",
                    ),
                    (
                        """        diff(base.cities(), target.cities()),
        diff(base.terrainTypes(), target.terrainTypes()),
        diff(base.pathways(), target.pathways()),""",
                        """        diff(base.cities(), target.cities()),
        diff(base.pathways(), target.pathways()),""",
                    ),
                    (
                        """        || cities.changed()
        || terrainTypes.changed()
        || pathways.changed()""",
                        """        || cities.changed()
        || pathways.changed()""",
                    ),
                    (
                        """        rebuild(base.cities(), cs.cities(), CityId::parse),
        rebuild(base.terrainTypes(), cs.terrainTypes(), STRING_KEY),
        rebuild(base.pathways(), cs.pathways(), PathwayId::parse),""",
                        """        rebuild(base.cities(), cs.cities(), CityId::parse),
        base.terrainTypes(), // 变异 m7v-2：terrainTypes 已漂移出去，重建时直接拿 base 的
        rebuild(base.pathways(), cs.pathways(), PathwayId::parse),""",
                    ),
                    (
                        """  private static final Function<String, String> STRING_KEY = Function.identity();""",
                        """  private static final Function<String, String> STRING_KEY = Function.identity();

  /** 变异 m7v-2：组件已漂移出去，此处留一个恒 Unchanged 的占位访问器，好让既有调用点编得过。 */
  public FieldDelta<TerrainType> terrainTypes() {
    return new FieldDelta.Unchanged<>();
  }""",
                    ),
                ],
            )
        ],
    },
    # brief 表 V3：给 GameMap 加一个新组件 foo，**不加进变更集**（主形态：构造点全改到编得过）。
    "m7v-3": {"files": [GM, MCS, GMT, MCST], "special": "v3"},
    # brief 表 V4：between 里 edges 的比较改成恒 Unchanged。
    "m7v-4": {
        "files": [MCS],
        "steps": [
            (
                MCS,
                [
                    (
                        """        diff(base.edges(), target.edges()));""",
                        """        new FieldDelta.Unchanged<EdgeTags>()); // 变异 m7v-4：edges 的比较被摘掉""",
                    )
                ],
            )
        ],
    },
    # brief 表 V5：apply 里重建时丢掉 edges。
    "m7v-5": {
        "files": [MCS],
        "steps": [
            (
                MCS,
                [
                    (
                        """        rebuild(base.edges(), cs.edges(), EdgeRef::parse),""",
                        """        base.edges(), // 变异 m7v-5：重建时丢掉 edges，直接拿 base 的""",
                    ),
                    ("""import io.mosire.simos.map.pathway.EdgeRef;\n""", ""),
                ],
            )
        ],
    },
    # brief 表 V6：往 EXCLUDED_FROM_CHANGE_SET 里加一项 "edges"。
    "m7v-6": {
        "files": [RTC],
        "steps": [
            (
                RTC,
                [
                    (
                        """  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of("spec");""",
                        """  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of("spec", "edges");""",
                    )
                ],
            )
        ],
    },
    # ★ V3 的第三形态（也把测试的两个 switch 补上 case）⇒ 期望落在"第一条断言 Failure"。
    "m7v-7": {"files": [GM, MCS, GMT, MCST, RTC], "special": "v3-switches"},
    # ★ V3 的第一形态（只加组件、构造点一个不动）⇒ 期望落在编译期（本轮按 brief 作废）。
    "m7v-8": {"files": [GM], "special": "v3-compile"},
    # ★ 补充轮（不在 brief 的 V1~V6 表内）：隔离**第二条断言**的判别力 ——
    #   changedOf 里 edges 的映射被复制粘贴成了 pathwayGroups。第一条（isEmpty）照旧绿，
    #   只有第二条该红。它就是"V1/V2 预测红在第二条"那个预测的正面验证。
    "m7v-9": {
        "files": [RTC],
        "steps": [
            (
                RTC,
                [
                    (
                        """          case "edges" -> cs.edges();""",
                        """          case "edges" -> cs.pathwayGroups(); // 变异 m7v-9：映射被复制粘贴错了""",
                    )
                ],
            )
        ],
    },
}


def main():
    if sys.argv[1] == "--files":
        print(" ".join(MUTATIONS[sys.argv[2]]["files"]))
        return
    mutation_id = sys.argv[1]
    spec = MUTATIONS[mutation_id]
    special = spec.get("special")
    if special == "v3":
        apply_v3()
    elif special == "v3-switches":
        apply_v3(test_switches=True)
    elif special == "v3-compile":
        apply_v3_compile_only()
    else:
        for path, steps in spec["steps"]:
            text = read(path)
            for old, new in steps:
                text = sub(text, old, new, path)
            write(path, text)
    print(f"{mutation_id}: mutated {', '.join(spec['files'])}")


main()
