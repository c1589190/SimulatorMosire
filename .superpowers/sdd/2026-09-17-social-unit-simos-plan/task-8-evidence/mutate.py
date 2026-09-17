# M3 Task 8 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t8v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~7（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务四条变异（计划 Step 5 + R-8-a / R-8-b）：
#   m3t8v-1（m1 原形态，R-8-a ①）：**scale 的 floorDiv 形态 → Math.round(v*‰/1000.0)**。
#     R-8-a 的代数结论：v 恒为 moveCost×1000（1000 的整倍数）⇒ +500 永不进位 ⇒ floorDiv 形态
#     恒等于精确乘法 moveCost×‰；而 v×‰ ≤ 998000×2^31-1 ≈ 2.1e15 < 2^53 ⇒ double 精确，
#     商 ≤ 2.1e12 也精确 ⇒ Math.round 同值。**预计存活（等价变体、无判别输入）**，红点以日志为准。
#   m3t8v-2（m1' 真变异，R-8-a ③）：**costOf 丢 ×1000（单位错误）**：
#     scale(type.moveCost() * 1000L, ...) → scale(type.moveCost(), ...)
#     ⇒ 期望 stepCostsMatchTheFrozenFixture 红（12500→13、32500→33）。**.kept 必须有**。
#   m3t8v-3（m2，R-8-b）：**costOf 的 >= → >** ⇒ 999 不再被判不可通行 ⇒
#     期望 impassableTerrainHasNoCost 红（收到 OptionalLong.of(499500)）。
#   m3t8v-4（m3，R-8-b；fix-1 起的形态）：**删 minStepCostMillis 的"跳过不可通行"守卫**（守卫整块
#     删除）。fix-1 已把"全不可通行 ⇒ 0"探针**转正**为提交用例
#     TerrainMovementCostTest.allImpassableMapHasZeroLowerBound（夹具 MoveFixture.allImpassableMap()）
#     ⇒ 期望红点落**提交在案**的该用例（expected 0L, but was 499500L）。
#     （fix-1 前的形态 = 改 main + 落实验室专用探针文件，其 .kept 已被 fix-1 重跑取代；判别力分析
#     不变：计划自带 minStep 用例对 m3 无判别输入——min 对更大值不敏感 + map(STEEP_65) 无 999 格。）

import os
import sys

REPO = "/tmp/m3t8lab/repo"
COST = "simos-unit/src/main/java/io/mosire/simos/unit/move/TerrainMovementCost.java"


def files_of(mid):
    return {
        "m3t8v-1": [COST],
        "m3t8v-2": [COST],
        "m3t8v-3": [COST],
        "m3t8v-4": [COST],
    }[mid]


def mutate(mid):
    if mid == "m3t8v-1":
        replace_exactly_once(
            COST,
            "    return Math.floorDiv(millis * perMille + 500, 1000);",
            "    return Math.round(millis * perMille / 1000.0); // 变异体 m1：Math.round 形态（R-8-a 预计等价存活）",
        )
    elif mid == "m3t8v-2":
        replace_exactly_once(
            COST,
            "    return OptionalLong.of(scale(type.moveCost() * 1000L, mobilityPerMille));",
            "    return OptionalLong.of(scale(type.moveCost(), mobilityPerMille)); // 变异体 m1'：丢 ×1000（单位错误）",
        )
    elif mid == "m3t8v-3":
        replace_exactly_once(
            COST,
            "    if (type.moveCost() >= TerrainType.IMPASSABLE_MOVE_COST) {",
            "    if (type.moveCost() > TerrainType.IMPASSABLE_MOVE_COST) { // 变异体 m2：>= → >（999 漏判）",
        )
    elif mid == "m3t8v-4":
        replace_exactly_once(
            COST,
            """      TerrainType type = terrainOf(map, cell); // ★ R-8-c：一次遍历里同时判断与取最小，不查两遍表
      if (type.moveCost() < TerrainType.IMPASSABLE_MOVE_COST) {
        best = Math.min(best, scale(type.moveCost() * 1000L, unit.mobilityPerMille()));
      }""",
            """      TerrainType type = terrainOf(map, cell); // 变异体 m3：跳过不可通行的守卫已删除
      best = Math.min(best, scale(type.moveCost() * 1000L, unit.mobilityPerMille()));""",
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
