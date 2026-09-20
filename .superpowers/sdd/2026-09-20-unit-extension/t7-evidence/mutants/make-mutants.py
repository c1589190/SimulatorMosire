#!/usr/bin/env python3
# T7 变异体生成器（照 T6 的 make-mutants.py 形制；差异：靶文件四条、片段都是**单行**且逐条断言"只在原位出现一次"）。
#
# 纪律（T5-L5 / 裁定 42）：
#   * 每轮的编辑是**精确文本替换**，替换前断言"目标片段在原件里恰好出现 N 次"，否则当场退出（不猜）。
#   * 每轮同时把 ⑩ 道自证的预检打出来：frag 在 原件 / 变异体 里的命中数必须符合 manifest 里的 frag_dir 方向。
#   * 生成的变异体**只准**落在本目录（证据目录），源树里一个字节都不留（装置第 ④ 道门禁会复查）。
#
# 用法：python3 make-mutants.py            # 生成 + 打印预检 + 覆写 manifest.txt / baseline-md5.txt
import hashlib
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[4]  # …/.superpowers/sdd/<task>/t7-evidence/mutants → 仓根

OPS = "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java"
PART = "simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java"
STATE = "simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java"

REJOIN_GUARD = '      return Optional.empty(); // 裁定 U5：RESTING/ENGAGED 不自动回归（引用不清，回到 MOVING 后恢复）'
TARGET_MISSING = '        throw new IllegalArgumentException("回归目标不存在: " + targetId);'

WRITE_BLOCK_ORIG = """      units.put(
          unit.id(),
          withMovement(
              units.get(unit.id()),
              Optional.of(
                  new Movement(route, to.get(), unit.effectiveSpeed(), unit.mobilityPerMille()))));"""
WRITE_BLOCK_TELEPORT = """      units.put(
          unit.id(),
          withPositionAndMovement(
              units.get(unit.id()),
              to.get(),
              route.path().get(route.path().size() - 1),
              Optional.of(
                  new Movement(route, to.get(), unit.effectiveSpeed(), unit.mobilityPerMille()))));"""

ROUNDS = [
    {
        "label": "t7m1",
        "target": PART,
        "reason": "终点取自第一趟物化**之前**的快照（等价于把终点冻结在旧 hex 上）（计划 m1）",
        "edits": [("revert", "rejoinRoute(materialized, unit.id(), map, cost, to.get())",
                   "rejoinRoute(snapshot.state(), unit.id(), map, cost, to.get())")],
        # ★ 自证片段取**变异体新引入**那一句（比"被删掉的那句"更强：它证的是替换真的落盘了，而不是原件还在）
        "frag": "rejoinRoute(snapshot.state(), unit.id(), map, cost, to.get())",
        "frag_dir": "revert",
        "expect_red": "UnitTimeParticipantTest.rejoinEndpointFollowsTheTargetsCurrentEffectivePosition（第 1 刻的目标位置会读成 H11 = 自己那格 ⇒ 根本不建行程）",
        "expect_green": "目标静止的其余四条（它们的旧格与新格恰好同值）",
    },
    {
        "label": "t7m2",
        "target": OPS,
        "reason": "不判可达：恒建一条 [起点, 终点] 的路线（计划 m2）",
        "edits": [("revert",
                   """    return PathFinder.findPath(map, start.get(), goal.get(), unit, cost)
        .map(path -> new Route(List.of(start.get(), goal.get()), path));""",
                   """    return Optional.of(
        new Route(List.of(start.get(), goal.get()), List.of(start.get(), goal.get())));""")],
        "frag": "new Route(List.of(start.get(), goal.get()), List.of(start.get(), goal.get()))",
        "frag_dir": "revert",
        "expect_red": "UnitTimeParticipantTest.unreachableTargetLeavesTheStateUntouched（相邻两格 ⇒ 那条恒建的路线能过 Route 的全部构造期校验）",
        "expect_green": "可达路径的用例（相邻两格上恒建恰好等于 A* 产物）",
    },
    {
        "label": "t7m3",
        "target": OPS,
        "reason": "删掉“状态允许移动”那条判据（裁定 U5）（计划 m3）",
        "edits": [("delete",
                   """    if (unit.status() != UnitStatus.MOVING) {
""" + REJOIN_GUARD + """
    }
""",
                   "")],
        "frag": "return Optional.empty(); // 裁定 U5",
        "frag_dir": "delete",
        "expect_red": "UnitTimeParticipantTest.restingUnitKeepsTheReferenceButDoesNotRejoin（RESTING 也建出回归行程）",
        "expect_green": "MOVING 的用例（判据在它们身上恒真）",
    },
    {
        "label": "t7m4",
        "target": PART,
        "reason": "返回前重建状态改回 1 参兼容构造器 new UnitState(units)（T5-L4 的第五个站点 / 计划 m4）",
        "edits": [("revert", "    UnitState target = snapshot.state().withUnits(units);",
                   "    UnitState target = new UnitState(units);")],
        "frag": "UnitState target = new UnitState(units);",
        "frag_dir": "revert",
        "expect_red": "UnitTimeParticipantTest.advanceKeepsCommandChainsWhilePositionAndMovementChange（既有 T5 守卫）+ rejoinTickKeepsCommandChains（本轮新增）",
        "expect_green": "不带链的用例（Map.of() 换 Map.of() 是恒等）",
    },
    {
        "label": "t7m5",
        "target": STATE,
        "reason": "给 UnitState 加第三个组件 rejoinAt（另留一个 2 参构造器 ⇒ 既有调用点照旧编译）：**行为等价的变异体**，只有“状态里没有终点存放位置”这条结构判据区分得了它（计划 m5）",
        "edits": [("revert",
                   "public record UnitState(Map<UnitId, Unit> units, Map<CommandChainId, CommandChain> commandChains) {",
                   """public record UnitState(
    Map<UnitId, Unit> units,
    Map<CommandChainId, CommandChain> commandChains,
    Optional<SimosTimestamp> rejoinAt) {"""),
                  ("revert", "  /** 兼容构造器（T1）：旧 1 参签名 ⇒ {@code commandChains} 为空表。 */",
                   """  /** 变异体新增：2 参（{@code rejoinAt} 缺省空）——既有调用点因此照旧编译。 */
  public UnitState(Map<UnitId, Unit> units, Map<CommandChainId, CommandChain> commandChains) {
    this(units, commandChains, Optional.empty());
  }

  /** 兼容构造器（T1）：旧 1 参签名 ⇒ {@code commandChains} 为空表。 */""")],
        "frag": "Optional<SimosTimestamp> rejoinAt) {",
        "frag_dir": "revert",
        "expect_red": "UnitTimeParticipantTest.stateHasNoPlaceToPersistAnEndpointHex（结构判据）+ UnitRoundTripTest 的组件枚举（既有守卫）",
        "expect_green": "行为类用例（新字段无人读 ⇒ 两边的可观测行为逐值相同）",
    },
    {
        "label": "t7m6",
        "target": OPS,
        "reason": "删掉命令期的“目标存在”检查（计划外补轮：命令层判据也要有自己的变异体，裁定 42）",
        "edits": [("delete",
                   """      if (!state.units().containsKey(targetId)) {
""" + TARGET_MISSING + """
      }
""",
                   "")],
        "frag": 'throw new IllegalArgumentException("回归目标不存在: " + targetId);',
        "frag_dir": "delete",
        "expect_red": "UnitCommandHandlersTest.setRejoinTargetRejectsUnknownSelfAndSelfReference（期望 Rejected，实得 Applied + 悬空引用）",
        "expect_green": "目标存在的命令用例（那条守卫在它们身上恒真）",
    },
    {
        "label": "t7m7",
        "target": PART,
        "reason": "回归写回时把**终点**也写进 position（复用第一趟的助手）⇒ 单位瞬移（计划外补轮：给“只写 movement、不碰 position”那条设计决定配一个变异体）",
        "edits": [("revert", WRITE_BLOCK_ORIG, WRITE_BLOCK_TELEPORT)],
        "frag": "route.path().get(route.path().size() - 1),",
        "frag_dir": "revert",
        "expect_red": "UnitTimeParticipantTest.rejoinEndpointFollowsTheTargetsCurrentEffectivePosition（“回归不瞬移”那条断言：T0+1 的有效位置变成 H12）",
        "expect_green": "断言只落在 movement 上的用例（它们的 route 不受影响）",
    },
]


def md5(path):
    return hashlib.md5(pathlib.Path(path).read_bytes()).hexdigest()


def main():
    problems = []
    manifest = []
    baseline = {}
    for round_ in ROUNDS:
        target_path = REPO / round_["target"]
        original = target_path.read_text(encoding="utf-8")
        mutated = original
        for direction, old, new in round_["edits"]:
            hits = mutated.count(old)
            if hits != 1:
                problems.append(f"{round_['label']}: 片段出现 {hits} 次（要求恰好 1 次）: {old.splitlines()[0][:70]}")
                continue
            mutated = mutated.replace(old, new)
        if mutated == original:
            problems.append(f"{round_['label']}: 变异体与原件逐字节相同")
            continue
        mutant_name = f"{round_['label']}_{pathlib.Path(round_['target']).name}"
        mutant_path = HERE / mutant_name
        mutant_path.write_text(mutated, encoding="utf-8")

        frag = round_["frag"]
        orig_hits = original.count(frag)
        mut_hits = mutated.count(frag)
        want = (0, 1) if round_["frag_dir"] == "revert" else (1, 0)
        ok = (orig_hits == want[0] and mut_hits >= want[1]) if round_["frag_dir"] == "revert" else (
            orig_hits >= want[0] and mut_hits == want[1])
        # 逐**行**计数（装置里用的是 grep -cF ≡ 含该片段的行数）
        orig_lines = sum(1 for line in original.splitlines() if frag in line)
        mut_lines = sum(1 for line in mutated.splitlines() if frag in line)
        print(f"{round_['label']}: orig_hits={orig_hits}(lines={orig_lines}) mut_hits={mut_hits}(lines={mut_lines}) "
              f"dir={round_['frag_dir']} {'OK' if ok else 'FAIL'}")
        if not ok:
            problems.append(f"{round_['label']}: ⑩ 预检未过（{round_['frag_dir']} 要求 orig/mut = {want}）")
        if orig_lines != 1 and round_["frag_dir"] == "delete":
            problems.append(f"{round_['label']}: ⑩ 预检（按行）原件命中 {orig_lines} 行，要求恰好 1 行")

        baseline[round_["target"]] = md5(target_path)
        manifest.append(
            f"label {round_['label']}\n"
            f"target {round_['target']}\n"
            f"mutant {mutant_name}\n"
            f"reason {round_['reason']}\n"
            f"frag {frag}\n"
            f"frag_dir {round_['frag_dir']}\n"
            f"expect_red {round_['expect_red']}\n"
            f"expect_green {round_['expect_green']}\n"
            f"orig_md5 {md5(target_path)}\n"
            f"mutant_md5 {md5(mutant_path)}\n")

    if problems:
        print("\n问题：")
        for problem in problems:
            print("  -", problem)
        return 1
    (HERE / "manifest.txt").write_text("\n".join(manifest), encoding="utf-8")
    (HERE / "baseline-md5.txt").write_text(
        "".join(f"{digest}  {path}\n" for path, digest in baseline.items()), encoding="utf-8")
    print("\nmanifest.txt / baseline-md5.txt 已覆写")
    return 0


if __name__ == "__main__":
    sys.exit(main())
