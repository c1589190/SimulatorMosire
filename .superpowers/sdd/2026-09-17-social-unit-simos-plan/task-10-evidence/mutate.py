# M3 Task 10 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t10v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~9（同一套坑照旧）：
#   ① 变异不得留下未使用的 import / 局部变量，也不得让 javac / checkstyle 报错 —— checkstyle 挂在
#      validate、编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#      因此 m2 把 frozen 拷贝块**整块**删掉（只改 cost 调用会留下未用变量 → checkstyle 红，不算数）；
#   ② 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务五轮（计划 Step 5 + R-10-b / R-10-c；目标都是 UnitMoves.java）：
#   m3t10v-1（m1 原形态，R-10-b 第 1 步）：budget >= edgeCost → budget > edgeCost。
#     预期：**在计划的 8 条测试上存活**（at=23 时第二步 33500 > 32500 仍 ARRIVED；提交套件里
#     没有用例落在"预算恰等于段成本"的等值边界上）。实际红/绿以日志为准。
#   m3t10v-1r（m1 重跑，R-10-b 第 3 步）：同一条变异，此刻工作树已补第 9 条边界用例
#     exactBudgetArrivalIsArrived（speed=5、at=T0+9 ⇒ 预算 45000 == 12500+32500 恰好付清）。
#     预期：`>` 把第 2 段判成"付不起"并试图构造 remaining=0 的 MovementState ⇒ 构造期 IAE，
#     红点落在边界用例上（红理由 = "恰够也是够"）。
#   m3t10v-2（m2，R-10-c）：frozen 机动性冻结视图整块删除，成本函数改读原 unit ⇒
#     预期 mobilityChangeAfterDepartureDoesNotChangeTheResult 红（R10 第二靶）。
#   m3t10v-3（m3，R-10-c）：step.isEmpty() 分支改 continue（跳过不可通行段）⇒
#     预期 impassableNextStepNeedsReplan 红。
#   m3t10v-4（m4，R-10-c）：删掉 at < departedAt 守卫 ⇒
#     预期 noRouteOrEarlierThanDepartureIsACallerBug 第二个断言红。

import os
import sys

REPO = "/tmp/m3t10lab/repo"
UNIT_MOVES = "simos-unit/src/main/java/io/mosire/simos/unit/move/UnitMoves.java"


def files_of(mid):
    return {
        "m3t10v-1": [UNIT_MOVES],
        "m3t10v-1r": [UNIT_MOVES],
        "m3t10v-2": [UNIT_MOVES],
        "m3t10v-3": [UNIT_MOVES],
        "m3t10v-4": [UNIT_MOVES],
    }[mid]


def mutate(mid):
    if mid in ("m3t10v-1", "m3t10v-1r"):
        replace_exactly_once(
            UNIT_MOVES,
            "      if (budget >= edgeCost) {",
            "      if (budget > edgeCost) { // 变异体 m1：>= 变 >，恰够也被判成付不起",
        )
    elif mid == "m3t10v-2":
        replace_exactly_once(
            UNIT_MOVES,
            """    Unit frozen =
        new Unit(
            unit.id(),
            unit.name(),
            unit.parent(),
            unit.position(),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            movement.mobilityAtDeparture(),
            unit.movement());
""",
            """    // 变异体 m2：机动性冻结视图整块删除（成本函数改读原 unit，见下一处变异）
""",
        )
        replace_exactly_once(
            UNIT_MOVES,
            "      OptionalLong step = cost.costMillis(from, to, frozen, map);",
            "      OptionalLong step = cost.costMillis(from, to, unit, map); // 变异体 m2：不再用冻结视图",
        )
    elif mid == "m3t10v-3":
        replace_exactly_once(
            UNIT_MOVES,
            """      if (step.isEmpty()) {
        return new MovementState(
            from, Optional.empty(), OptionalLong.empty(), MovementStatus.NEED_REPLAN);
      }""",
            """      if (step.isEmpty()) {
        continue; // 变异体 m3：不可通行段被跳过而不是中断行程
      }""",
        )
    elif mid == "m3t10v-4":
        replace_exactly_once(
            UNIT_MOVES,
            """    if (at.compareTo(movement.departedAt()) < 0) {
      throw new IllegalArgumentException("查询时刻早于出发时刻: " + at + " < " + movement.departedAt());
    }
""",
            """    // 变异体 m4："早于出发时刻"守卫删除（调用方 bug 不再被拒）
""",
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
