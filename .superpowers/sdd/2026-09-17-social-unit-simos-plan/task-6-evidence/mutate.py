# M3 Task 6 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t6v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~5（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务四条变异（计划 Step 5 + R-6-b / R-6-c）：
#   m3t6v-1（m1）：**删掉 requireNoEvents 的 events().isEmpty() 判断** ⇒ 期望红
#     parentAndPositionRejectEvents（R5：parent/position 不得带事件）。
#   m3t6v-2（m2，R-6-b 的**合并图形态**）：**requireNoCycleAtKeyTimes 换成"把所有单位的全部段边
#     并进一张图查环"** ⇒ 期望**唯一红点 = legalReparentAcrossTimeIsNotACycle**（t<10 与 t≥10
#     各自合法的改编被误报）；**cycleAcrossUnitsThrowsAtConstruction 在合并图下仍绿**——合并图
#     确实有环、该用例断言的就是"抛"（变异体消息仍含"成环"）。这个反直觉结果就是"为什么不得用
#     合并图"的判别力证据。
#   m3t6v-3（m3）：**effectivePosition 改成"先问父、后问己"** ⇒ 期望红 ownPositionWinsOverParent
#     （父链两用例仍绿：自身无位置时先问父结果相同）。
#   m3t6v-4（m4）：**删掉 Unit 构造器的自环校验**（连同随之失效的 Segment import，否则 checkstyle
#     挂在 validate、"红"不是断言红）⇒ 期望红 parentPointingToItselfThrows。

import os
import sys

REPO = "/tmp/m3t6lab/repo"
UNIT = "simos-unit/src/main/java/io/mosire/simos/unit/Unit.java"
UNITSTATE = "simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java"


def files_of(mid):
    return {
        "m3t6v-1": [UNIT],
        "m3t6v-2": [UNITSTATE],
        "m3t6v-3": [UNITSTATE],
        "m3t6v-4": [UNIT],
    }[mid]


def mutate(mid):
    if mid == "m3t6v-1":
        replace_exactly_once(
            UNIT,
            """    if (!series.events().isEmpty()) {
      throw new IllegalArgumentException(field + " 不得带事件：变化一律用追加段表达（spec §4.1）");
    }
""",
            "",
        )
    elif mid == "m3t6v-2":
        replace_exactly_once(
            UNITSTATE,
            """  private static void requireNoCycleAtKeyTimes(Map<UnitId, Unit> units) {
    Set<SimosTimestamp> keyTimes = new LinkedHashSet<>();
    for (Unit unit : units.values()) {
      for (Segment<Optional<UnitId>> segment : unit.parent().segments()) {
        keyTimes.add(segment.from());
      }
    }
    for (SimosTimestamp at : keyTimes) {
      Map<UnitId, UnitId> parentOf = new LinkedHashMap<>();
      for (Unit unit : units.values()) {
        unit.parent().valueAt(at).ifPresent(parent -> parentOf.put(unit.id(), parent));
      }
      for (UnitId start : parentOf.keySet()) {
        Set<UnitId> seen = new LinkedHashSet<>();
        UnitId current = start;
        while (current != null && parentOf.containsKey(current)) {
          if (!seen.add(current)) {
            throw new IllegalArgumentException("编制树在 " + at + " 成环，环上含 " + current);
          }
          current = parentOf.get(current);
        }
      }
    }
  }""",
            """  /** 变异体 m2：所有单位的全部段边并进一张图查环（错误形态：跨时刻合法改编被误报）。 */
  private static void requireNoCycleAtKeyTimes(Map<UnitId, Unit> units) {
    Map<UnitId, UnitId> parentOf = new LinkedHashMap<>();
    for (Unit unit : units.values()) {
      for (Segment<Optional<UnitId>> segment : unit.parent().segments()) {
        segment.value().ifPresent(parent -> parentOf.put(unit.id(), parent));
      }
    }
    for (UnitId start : parentOf.keySet()) {
      Set<UnitId> seen = new LinkedHashSet<>();
      UnitId current = start;
      while (current != null && parentOf.containsKey(current)) {
        if (!seen.add(current)) {
          throw new IllegalArgumentException("合并编制图成环，环上含 " + current);
        }
        current = parentOf.get(current);
      }
    }
  }""",
        )
    elif mid == "m3t6v-3":
        replace_exactly_once(
            UNITSTATE,
            """      Optional<HexCoord> here = unit.position().valueAt(at);
      if (here.isPresent()) {
        return here;
      }
      // 查无此父（手工拼的状态）⇒ 链到此为止
      unit = unit.parent().valueAt(at).map(units::get).orElse(null);""",
            """      // 变异体 m3：先问父、后问己
      Optional<UnitId> parentId = unit.parent().valueAt(at);
      if (parentId.isPresent() && units.containsKey(parentId.get())) {
        Optional<HexCoord> fromParent = units.get(parentId.get()).position().valueAt(at);
        if (fromParent.isPresent()) {
          return fromParent;
        }
      }
      Optional<HexCoord> here = unit.position().valueAt(at);
      if (here.isPresent()) {
        return here;
      }
      // 查无此父（手工拼的状态）⇒ 链到此为止
      unit = parentId.map(units::get).orElse(null);""",
        )
    elif mid == "m3t6v-4":
        replace_exactly_once(
            UNIT,
            """    for (Segment<Optional<UnitId>> segment : parent.segments()) {
      if (segment.value().filter(id::equals).isPresent()) {
        throw new IllegalArgumentException("parent 不得指向自身: " + id);
      }
    }
""",
            "",
        )
        replace_exactly_once(
            UNIT,
            "import io.mosire.simos.util.time.Segment;\n",
            "",
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
