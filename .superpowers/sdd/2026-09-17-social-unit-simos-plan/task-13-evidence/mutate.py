# M3 Task 13 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m13v-1>       # 把该变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~12（同一套坑照旧）：
#   ① 变异不得留下未使用的 import / 局部变量，也不得让 javac / checkstyle 报错 —— "红"必须是
#      **断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务一轮（R-13-b 的靶子）：
#   m13v-1：TerrainMovementCost.costOf 的 scale(type.moveCost() * 1000L, …) → scale(type.moveCost(), …)
#     （丢 ×1000，单位错误）⇒ 两段成本 12500/32500 变 13/33。
#     预期：criterionTwoArithmeticMatchesTheSpecTable 红（budget 40000 − 13 = 39987 ≠ 27500，R-13-b
#     补条的靶子）；stepCostsMatchTheFrozenFixture 同根因连带红（expected 12500 / was 13）；UnitMovesTest
#     里按预算逐段付的用例（atTwentyHours / atTwentyTwoHours）与 PathFinderTest 的成本数断言预计连带红
#     ——全部红点以日志为准，如实列。minStep 用自己的 ×1000L 调用点、impassable 在 scale 之前 return，
#     预计仍绿。

import os
import sys

REPO = "/tmp/m3t13lab/repo"
TERRAIN_COST = "simos-unit/src/main/java/io/mosire/simos/unit/move/TerrainMovementCost.java"


def files_of(mid):
    return {
        "m13v-1": [TERRAIN_COST],
    }[mid]


def mutate(mid):
    if mid == "m13v-1":
        replace_exactly_once(
            TERRAIN_COST,
            "    return OptionalLong.of(scale(type.moveCost() * 1000L, mobilityPerMille));",
            "    return OptionalLong.of(scale(type.moveCost(), mobilityPerMille)); // 变异体 m13v-1：丢 ×1000（单位错误）",
        )
    else:
        raise AssertionError(f"未知变异 id: {mid}")

    for path in files_of(mid):
        if not os.path.isfile(f"{REPO}/{path}"):
            raise AssertionError(f"{mid}: 声明的文件不存在: {path}")


def replace_exactly_once(path, old, new):
    text = read(path)
    n = text.count(old)
    if n != 1:
        raise AssertionError(f"{path}: 替换目标应恰命中 1 处，实际 {n} 处: {old!r}")
    write(path, text.replace(old, new))


def read(path):
    return open(f"{REPO}/{path}", encoding="utf-8").read()


def write(path, text):
    with open(f"{REPO}/{path}", "w", encoding="utf-8") as f:
        f.write(text)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--files":
        print(" ".join(files_of(sys.argv[2])))
        sys.exit(0)
    mutate(sys.argv[1])
