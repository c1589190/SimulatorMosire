# M3 Task 11 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t11v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~10（同一套坑照旧）：
#   ① 变异不得留下未使用的 import / 局部变量，也不得让 javac / checkstyle 报错 —— checkstyle 挂在
#      validate、编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务六轮（计划 Step 5 + R-11-b / R-11-c；目标都是 UnitOperations.java）：
#   m3t11v-m1：删 create 的"同 id ⇒ 抛" ⇒ 预期 createRejectsDuplicateId 红。
#   m3t11v-m2a：删 disband 的"有下属 ⇒ 抛"循环（存在性校验保留为裸语句）⇒
#     预期两个 disband 用例红（同根因：disbandRefusesWhileSubordinatesExist 的负向断言
#     与 disbandIsTimeSensitive 的 T10 负向断言都靠这条循环抛）。
#   m3t11v-m2b：disband 的 valueAt(at) 换成"恒取末段值"（时点不敏感）⇒
#     预期**唯一**红 disbandIsTimeSensitive（时点敏感性的判别力证据，计划点名要）。
#   m3t11v-m3：删 planRoute 的起点相等校验（无位置守卫保留为裸语句）⇒
#     预期 planRouteRequiresAStartThatMatchesTheEffectivePosition 红（起点对不上的负向断言）。
#   m3t11v-m4：placeAt 的 Optional.empty() 换回 unit.movement()（不清路线）⇒
#     预期 R-11-b 补条 placeAtClearsInTransitRoute 红（spec §4.6 第 5 条的靶子）。
#   m3t11v-m5：reparent 不追加段（直接回传旧 parent 序列）⇒
#     预期 reparentAppendsASegmentAndRejectsUnknownParents 红；
#     disbandIsTimeSensitive 预计连带红（同根因：它的前置 reparent 也靠 append 生效）——
#     红点以日志为准，全列如实记录。

import os
import sys

REPO = "/tmp/m3t11lab/repo"
UNIT_OPS = "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java"


def files_of(mid):
    return {
        "m3t11v-m1": [UNIT_OPS],
        "m3t11v-m2a": [UNIT_OPS],
        "m3t11v-m2b": [UNIT_OPS],
        "m3t11v-m3": [UNIT_OPS],
        "m3t11v-m4": [UNIT_OPS],
        "m3t11v-m5": [UNIT_OPS],
    }[mid]


def mutate(mid):
    if mid == "m3t11v-m1":
        replace_exactly_once(
            UNIT_OPS,
            """    if (state.units().containsKey(unit.id())) {
      throw new IllegalArgumentException("单位 id 已存在: " + unit.id());
    }
""",
            """    // 变异体 m1："同 id 已在 ⇒ 抛"删除（重复 id 不再被拒）
""",
        )
    elif mid == "m3t11v-m2a":
        replace_exactly_once(
            UNIT_OPS,
            """    require(state, id); // 存在性校验（查无此人 ⇒ 抛）；unit 本体在解散时无需再取
    for (Unit other : state.units().values()) {
      if (other.id().equals(id)) {
        continue;
      }
      if (other.parent().valueAt(at).filter(id::equals).isPresent()) {
        throw new IllegalArgumentException(
            "单位 " + id + " 在 " + at + " 仍有下属 " + other.id() + "：先改编、再解散");
      }
    }
""",
            """    require(state, id); // 变异体 m2a：「有下属 ⇒ 抛」循环整块删除（存在性校验保留）
""",
        )
    elif mid == "m3t11v-m2b":
        replace_exactly_once(
            UNIT_OPS,
            """      if (other.parent().valueAt(at).filter(id::equals).isPresent()) {
""",
            """      List<Segment<Optional<UnitId>>> segs = other.parent().segments();
      boolean subordinate =
          segs.get(segs.size() - 1).value().filter(id::equals).isPresent(); // 变异体 m2b：恒取末段值
      if (subordinate) {
""",
        )
    elif mid == "m3t11v-m3":
        replace_exactly_once(
            UNIT_OPS,
            """    HexCoord start =
        state
            .effectivePosition(id, at)
            .orElseThrow(
                () -> new IllegalArgumentException("单位 " + id + " 在 " + at + " 没有可确定的位置，无法下达路线"));
    HexCoord routeStart = route.waypoints().get(0);
    if (!start.equals(routeStart)) {
      throw new IllegalArgumentException("路线起点 " + routeStart + " 不是单位在 " + at + " 的位置 " + start);
    }
""",
            """    // 变异体 m3：起点相等校验删除（无位置守卫保留为裸语句；起点对不上不再抛）
    state
        .effectivePosition(id, at)
        .orElseThrow(
            () -> new IllegalArgumentException("单位 " + id + " 在 " + at + " 没有可确定的位置，无法下达路线"));
""",
        )
    elif mid == "m3t11v-m4":
        replace_exactly_once(
            UNIT_OPS,
            """            append(unit.position(), at, hex),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            Optional.empty()));
""",
            """            append(unit.position(), at, hex),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            unit.mobilityPerMille(),
            unit.movement())); // 变异体 m4：位置改了也不清在途路线
""",
        )
    elif mid == "m3t11v-m5":
        replace_exactly_once(
            UNIT_OPS,
            """            append(unit.parent(), at, newParent),
""",
            """            unit.parent(), // 变异体 m5：改编不追加段
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
