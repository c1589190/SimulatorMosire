"""Task 12 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

用法：
  python3 mutate.py <m12v-N>      # 把第 N 条变异写进 $REPO
  python3 mutate.py --files <id>  # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

★ 装置形态继承 Task 4~11（同一套坑照旧）：
  ① 变异不得留下**未使用的 import**，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、编译在它之后，
     "红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
     （本仓 checkstyle 只有 AvoidStarImport/RedundantImport/UnusedImports/OneStatementPerLine，
     不查未用局部变量 ⇒ m12v-3 里 rng 声明后不再读也过得了 checkstyle；SpotBugs 只在 verify 跑，本装置跑 test。）
  ② 不按变异文件名拷入 —— 变异体**就写在目标类名的文件里**；
  ③ 每轮**显式声明**文件集合，run.sh 的自证是"只有声明的这些变了"。
"""

import sys

REPO = "/tmp/m12lab/repo"
CLS = "simos-map/src/main/java/io/mosire/simos/map/generate/RegionRandomizer.java"


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
    # ── 变异 1：随机源不看 seed，用 new Random() ──
    # ★ 锚点在 helper `rngFor` 的返回行上（修复 SpotBugs DMI_RANDOM_USED_ONLY_ONCE 时，
    #   构造从 randomize 内联抽成了这个 helper）：改的是**派生本身不看 seed**，与调用点长什么样无关。
    if mid == "m12v-1":
        replace_once(
            CLS,
            "    return new Random(seed * 31L + region.value().hashCode());",
            "    return new Random(); // 变异 m12v-1：随机源不看 seed",
        )

    # ── 变异 2：不看 region，目标改成全图 ──
    elif mid == "m12v-2":
        replace_once(
            CLS,
            "      for (HexCoord at : target.hexes().stream().sorted().toList()) {",
            "      // 变异 m12v-2：不看 region，目标改成全图\n"
            "      for (HexCoord at : map.hexes().keySet().stream().sorted().toList()) {",
        )

    # ── 变异 3：不看 ratioA，恒选 A ──
    elif mid == "m12v-3":
        replace_once(
            CLS,
            "        String terrain = rng.nextDouble() < ratioA ? terrainA : terrainB;",
            "        // 变异 m12v-3：不看 ratioA，恒选 A\n"
            "        String terrain = terrainA;",
        )

    # ── 变异 4：删掉 ratioA 范围校验（含 NaN 那条被守卫挡住的形态）──
    elif mid == "m12v-4":
        replace_once(
            CLS,
            '    if (!(ratioA >= 0.0 && ratioA <= 1.0)) {\n'
            '      // ★ 非"或"形态：NaN 与任何数比较全是 false，`ratioA < 0 || ratioA > 1` 会把 NaN 静默漏过。\n'
            '      throw new IllegalArgumentException("ratioA 必须在 [0,1]: " + ratioA);\n'
            "    }\n",
            "    // 变异 m12v-4：删掉 ratioA 范围校验\n",
        )

    # ── 变异 6（补充轮，超出 brief 4 行表）：范围校验写成"或"形态 ──
    # ★ 为什么需要它：m12v-4（整条校验删掉）在循环第一个值 -0.1 上就红了，
    #   **根本走不到 NaN 那一格** ⇒ 它证明不了"NaN 断言有判别力"。
    #   本轮的形态 `ratioA < 0 || ratioA > 1` 对 -0.1/1.1 照样抛，只有 NaN 静默漏过
    #   —— 唯一能打到 NaN 断言的变异形态（R-12-g 的靶子）。
    elif mid == "m12v-6":
        replace_once(
            CLS,
            "    if (!(ratioA >= 0.0 && ratioA <= 1.0)) {\n"
            '      // ★ 非"或"形态：NaN 与任何数比较全是 false，`ratioA < 0 || ratioA > 1` 会把 NaN 静默漏过。\n',
            "    if (ratioA < 0.0 || ratioA > 1.0) { // 变异 m12v-6：或形态——NaN 静默漏过\n",
        )

    # ── 变异 5：高度也一起改（写死 0.5）──
    elif mid == "m12v-5":
        replace_once(
            CLS,
            "        upserts.put(at.toString(), new HexCell(terrain, cell.height()));",
            "        upserts.put(at.toString(), new HexCell(terrain, 0.5)); // 变异 m12v-5：高度也一起改",
        )

    else:
        raise AssertionError(f"未知变异 id: {mid}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--files":
        print(" ".join(files_of(sys.argv[2])))
        sys.exit(0)
    mutate(sys.argv[1])
