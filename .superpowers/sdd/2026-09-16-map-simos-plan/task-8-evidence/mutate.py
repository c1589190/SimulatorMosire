"""Task 8 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

用法：
  python3 mutate.py <m8v-N>      # 把第 N 条变异写进 $REPO
  python3 mutate.py --files <id> # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

★ 装置形态继承 Task 4~7（同一套坑照旧）：
  ① 变异不得留下**未使用的 import** —— checkstyle 挂在 validate，会抢在 surefire 之前失败，
     "红"就不是断言红了（m8v-5 删三个 requireNonNull 时必须连 `import java.util.Objects;` 一起删）；
  ② 不按变异文件名拷入 —— 变异体**就写在目标类名的文件里**；
  ③ 有的轮必须**跨文件共适应**才编得过（m8v-3/m8v-4/m8v-7 要把构造点全改掉），
     故每轮**显式声明**文件集合，run.sh 的自证是"只有声明的这些变了"。
★ 本任务与 Task 7 的不同：变异对象是**参数面 record**（`GenerationSpec` + 三个子 record），
  没有"变更集"这一侧；共适应面比 Task 7 窄得多 —— `new GenerationSpec(` 全仓只有 3 处
  （生产 1 处 + 测试夹具 2 处）。这正是把测试构造点集中到 `scalars`/`subRecords` 的目的：
  共适应面越小，"红"越干净。
"""

import sys

REPO = "/tmp/m8lab/repo"
GEN = "simos-map/src/main/java/io/mosire/simos/map/generate/"
GS = GEN + "GenerationSpec.java"
NB = GEN + "NoiseBands.java"
FP = GEN + "FragmentParams.java"
GST = "simos-map/src/test/java/io/mosire/simos/map/generate/GenerationSpecTest.java"


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


def edit(path, steps):
    text = read(path)
    for old, new in steps:
        text = sub(text, old, new, path)
    write(path, text)


# ── m8v-3 / m8v-11：加回 worldId 组件 ────────────────────────────────────────────

HDR_OLD = """public record GenerationSpec(
    long seed,"""
HDR_NEW = """public record GenerationSpec(
    long worldId,
    long seed,"""

# 生产侧唯一构造点：defaults()。worldId 取常数（"只有 seed 随入参变"这条不变量仍成立）。
DEFAULTS_OLD = """    return new GenerationSpec(
        seed,
        80, // MapConfig.DEFAULT.defaultMapRadius（旧 TerrainCanvas.DEFAULT_MAP_RADIUS）"""
DEFAULTS_NEW = """    return new GenerationSpec(
        0L, // 变异 m8v-3：worldId 回来了（GSimulator 的 MapGenerator.java:243 就收它）
        seed,
        80, // MapConfig.DEFAULT.defaultMapRadius（旧 TerrainCanvas.DEFAULT_MAP_RADIUS）"""

# 测试夹具 1：scalars（五个标量的负例都走这一个构造点）
SCALARS_OLD = """    GenerationSpec d = GenerationSpec.defaults(seed);
    return new GenerationSpec(
        seed,"""
SCALARS_NEW = """    GenerationSpec d = GenerationSpec.defaults(seed);
    return new GenerationSpec(
        0L, // 变异 m8v-3：worldId 回来了
        seed,"""

# 测试夹具 2：subRecords（标量取自 base）
SUBREC_OLD = """    return new GenerationSpec(
        base.seed(),"""
SUBREC_NEW = """    return new GenerationSpec(
        base.worldId(), // 变异 m8v-3：worldId 回来了
        base.seed(),"""


# ── m8v-4 / m8v-7：往 NoiseBands 尾部加组件 ─────────────────────────────────────

NB_TAIL_OLD = """    double warpFreq,
    double warpAmplitude) {"""

GS_NB_TAIL_OLD = """            0.018,
            10.0),
        new RidgeParams("""

GST_GAMMA_TAIL_OLD = """        b.warpFreq(),
        b.warpAmplitude());"""

GST_BANDSWITH_OLD = """        0.35, 0.92, warpFreq, 10.0);"""


def add_noise_components(decls, gs_vals, gamma_refs, bandswith_vals):
    """往 NoiseBands 尾部加组件，并把三个构造点（生产 1 + 测试 2）改到编得过。

    decls        —— 追加到 record 头的组件声明（多行）
    gs_vals      —— defaults() 里 NoiseBands 实参的追加值
    gamma_refs   —— noiseWithGamma 的追加访问器调用
    bandswith_vals —— bandsWith 的追加实参
    """
    edit(
        NB,
        [
            (
                NB_TAIL_OLD,
                """    double warpFreq,
    double warpAmplitude,
%s) {"""
                % decls,
            )
        ],
    )
    edit(
        GS,
        [
            (
                GS_NB_TAIL_OLD,
                """            0.018,
            10.0,
%s),
        new RidgeParams("""
                % gs_vals,
            )
        ],
    )
    edit(
        GST,
        [
            (
                GST_GAMMA_TAIL_OLD,
                """        b.warpFreq(),
        b.warpAmplitude(),
%s);"""
                % gamma_refs,
            ),
            (
                GST_BANDSWITH_OLD,
                """        0.35, 0.92, warpFreq, 10.0,
%s);"""
                % bandswith_vals,
            ),
        ],
    )


# ── 逐轮定义 ────────────────────────────────────────────────────────────────────

MUTATIONS = {
    # brief 表 V1：mainRidges 的构造期校验换成 GSimulator 的静默夹取。
    #   ★ 这正是本任务存在的理由（"静默夹取改构造期校验"），故它必须红在 mainRidgesFiveThrows。
    "m8v-1": {
        "files": [GS],
        "steps": [
            (
                GS,
                [
                    (
                        """    // ★ R-48-a：合法区间 [1, 2]。上界与下界都要 —— spec §9.3 要求 mainRidges=5 必须抛，
    //   mainRidgesTwoIsAccepted 反证上界恰为 2（GSimulator 的 Math.min(mainCount, 2) 定的就是这个数）。
    if (mainRidges < 1 || mainRidges > 2) {
      throw new IllegalArgumentException("mainRidges 必须在 [1, 2]: " + mainRidges);
    }""",
                        """    // 变异 m8v-1：GSimulator 的静默夹取原样搬回（MapGenerator.java:61 的
    //   Math.max(1, Math.min(mainCount, 2))）—— 传 5 又变回静默的 2、传 0 又变回静默的 1
    mainRidges = Math.max(1, Math.min(mainRidges, 2));""",
                    )
                ],
            )
        ],
    },
    # brief 表 V2：删掉那条负差值校验的**调用点**（让它在构造期发生的那一行）。
    #   ★ 守卫体本身另有一轮（m8v-12）——两轮合起来才说明"调用点与守卫体都有人守"。
    "m8v-2": {
        "files": [GS],
        "steps": [
            (
                GS,
                [
                    (
                        """    // ★ 那条可为负的差值（fragmentCount - secondary，GSimulator `MapGenerator.java:105`）在
    //   FragmentParams 里被挡（见其 requireNonNegativeRemaining）；这里只负责让它**在构造期发生**。
    fragmentParams.requireNonNegativeRemaining(fragments);""",
                        """    // 变异 m8v-2：构造期不再触发那条负差值校验（守卫体还在，只是没人调用它）""",
                    )
                ],
            )
        ],
    },
    # brief 表 V3：加回 worldId 组件。★ 主形态 —— **构造点全改到编得过**，红该落在测试期。
    "m8v-3": {"files": [GS, GST], "special": "worldid"},
    # brief 表 V4：往 NoiseBands 里加 double mountainAbove + String terrainKey。
    "m8v-4": {
        "files": [NB, GS, GST],
        "special": "noise",
        "args": (
            "    double mountainAbove,\n    String terrainKey",
            '            0.68,\n            "mountains"',
            "        b.mountainAbove(),\n        b.terrainKey()",
            '            0.68,\n            "mountains"',
        ),
    },
    # ★ 补充轮（不在 brief 表内）：三个 requireNonNull 全删。
    #   ★★ 连 `import java.util.Objects;` 一起删 —— 留着会撞 checkstyle UnusedImports，
    #     红的就不是断言了（装置形态 ①，Task 1 在此连污染两轮）。
    "m8v-5": {
        "files": [GS],
        "steps": [
            (
                GS,
                [
                    (
                        """    // ★ 三个子 record 非 null：它们的字段都是基本类型，null 不会在构造期炸，而会在生成时以一个
    //   离现场很远的 NPE 出现 —— 报错的时间点必须落在构造期。
    bands = Objects.requireNonNull(bands, "bands");
    ridges = Objects.requireNonNull(ridges, "ridges");
    fragmentParams = Objects.requireNonNull(fragmentParams, "fragmentParams");
""",
                        """    // 变异 m8v-5：三个 null 守卫全删
""",
                    ),
                    ("""import java.util.Objects;\n""", ""),
                ],
            )
        ],
    },
    # ★ 补充轮：频率守卫被摘掉（它自己变成静默放行）。
    "m8v-6": {
        "files": [NB],
        "steps": [
            (
                NB,
                [
                    (
                        """    if (!(value > 0.0) || !Double.isFinite(value)) {
      throw new IllegalArgumentException(name + " 必须是有限正数: " + value);
    }
    return value;""",
                        """    return value; // 变异 m8v-6：频率守卫被摘掉（0 与 NaN 都静默放行）""",
                    )
                ],
            )
        ],
    },
    # ★ 补充轮：加一个**名字就是词表 key** 的浮点组件 `mountains`。
    #   形态判据（浮点 + String 同处一类型）**抓不到它**（本类型一个 String 组件都没有）
    #   ⇒ 只有名字判据能抓。这一轮证明名字判据不是装饰；m8v-4 证明形态判据不是装饰。两条互不替代。
    "m8v-7": {
        "files": [NB, GS, GST],
        "special": "noise",
        "args": (
            "    double mountains",
            "            0.68",
            "        b.mountains()",
            "            0.68",
        ),
    },
    # ★ 补充轮：contourCacheMax 的静默夹取。
    "m8v-8": {
        "files": [GS],
        "steps": [
            (
                GS,
                [
                    (
                        """    if (contourCacheMax < 1) {
      throw new IllegalArgumentException("contourCacheMax 必须 >= 1: " + contourCacheMax);
    }""",
                        """    contourCacheMax = Math.max(1, contourCacheMax); // 变异 m8v-8：静默夹取""",
                    )
                ],
            )
        ],
    },
    # ★ 补充轮：mapRadius 的静默夹取。
    "m8v-9": {
        "files": [GS],
        "steps": [
            (
                GS,
                [
                    (
                        """    if (mapRadius < 1) {
      throw new IllegalArgumentException("mapRadius 必须 >= 1: " + mapRadius);
    }""",
                        """    mapRadius = Math.max(1, mapRadius); // 变异 m8v-9：静默夹取""",
                    )
                ],
            )
        ],
    },
    # ★ 补充轮：baseSeaLevel 的区间校验换成**自然但错**的那种写法（`a < 0 || a > 1`）。
    #   源码里那句 `!(a >= 0 && a <= 1)` 是**为 NaN 写的**（注释就这么说）—— 这一轮是那句话的判别力证明：
    #   NaN 从 `a < 0 || a > 1` 的缝里溜过去，整张图静默变成没有海。
    "m8v-10": {
        "files": [GS],
        "steps": [
            (
                GS,
                [
                    (
                        """    // ★ 写成 !(a && b) 而不是 (a < 0 || a > 1)：NaN 的任何比较都是 false，于是前者能挡住 NaN、
    //   后者会把 NaN 放行（NaN 海平面让 height < seaLevel 恒为 false ⇒ 整张图静默变成没有海）。
    if (!(baseSeaLevel >= 0.0 && baseSeaLevel <= 1.0)) {""",
                        """    // 变异 m8v-10：换成"自然但错"的那种写法 —— NaN 从此路过
    if (baseSeaLevel < 0.0 || baseSeaLevel > 1.0) {""",
                    )
                ],
            )
        ],
    },
    # ★ 对照轮：只加 record 组件、构造点一个不动 ⇒ 期望落在**编译期**（与 m8v-3 的测试期对照）。
    "m8v-11": {"files": [GS], "special": "worldid-compile"},
    # ★ 补充轮：负差值校验的**守卫体**被掏空（m8v-2 删的是调用点，这一轮删的是守卫本身）。
    "m8v-12": {
        "files": [FP],
        "steps": [
            (
                FP,
                [
                    (
                        """    int secondary = secondaryCount(fragments);
    int remaining = fragments - secondary;
    if (remaining < 0) {
      throw new IllegalArgumentException(
          "fragments 不足以供给次级脊线：fragments=" + fragments + "，次级脊线=" + secondary + "，剩余=" + remaining);
    }
    return remaining;""",
                        """    return fragments - secondaryCount(fragments); // 变异 m8v-12：守卫体被掏空（不再抛）""",
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
    if special == "worldid":
        edit(GS, [(HDR_OLD, HDR_NEW), (DEFAULTS_OLD, DEFAULTS_NEW)])
        edit(GST, [(SCALARS_OLD, SCALARS_NEW), (SUBREC_OLD, SUBREC_NEW)])
    elif special == "worldid-compile":
        edit(GS, [(HDR_OLD, HDR_NEW)])
    elif special == "noise":
        add_noise_components(*spec["args"])
    else:
        for path, steps in spec["steps"]:
            edit(path, steps)
    print(f"{mutation_id}: mutated {', '.join(spec['files'])}")


main()
