# M3 Task 7 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t7v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~6（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务四条变异（计划 Step 5 + R-7-b / R-7-c）：
#   m3t7v-1（m1）：**UnitChangeSet.between 两侧对调**（diff(target, base)）⇒ 期望红
#     removalWithoutUpsertIsRemoveAndSurvivesApply（删除侧变成 Unchanged）与
#     addAndRemoveTogetherIsAPatch（Patch 变 Upsert、apply 丢删除侧）。
#   m3t7v-2（m2-A，R-7-c 必做）：**UnitState 加第二组件 tag + 旧签名二级构造器** ⇒ 测试不改，
#     期望红 changeSetHasExactlyOneComponent（反向 subset）+
#     everyUnitStateComponentParticipatesInTheChangeSet（mutate 的 default 抛）。
#   m3t7v-3（m2-B，R-7-c 可选）：**在 m2-A 之上把 changedOf 的 default 改成 true 并给 mutate
#     注册 tag** ⇒ 期望 everyUnitStateComponentParticipatesInTheChangeSet 转绿（泄漏），
#     changeSetHasExactlyOneComponent 仍红。
#   m3t7v-4（m3，R-7-b）：**UnitState 的 unmodifiableMap → Map.copyOf**（连同失效的 Collections
#     import，否则 checkstyle 挂在 validate、"红"不是断言红）⇒ 期望唯一红 =
#     unitOrderFollowsInsertionOrder（6 键夹具实测 20/20 JVM 全 SHUFFLED）。

import os
import sys

REPO = "/tmp/m3t7lab/repo"
CHANGESET = "simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java"
UNITSTATE = "simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java"
ROUNDTRIP = "simos-unit/src/test/java/io/mosire/simos/unit/change/UnitRoundTripTest.java"


def files_of(mid):
    return {
        "m3t7v-1": [CHANGESET],
        "m3t7v-2": [UNITSTATE],
        "m3t7v-3": [UNITSTATE, ROUNDTRIP],
        "m3t7v-4": [UNITSTATE],
    }[mid]


def mutate(mid):
    if mid == "m3t7v-1":
        replace_exactly_once(
            CHANGESET,
            "return new UnitChangeSet(FieldDelta.diff(base.units(), target.units()));",
            "return new UnitChangeSet(FieldDelta.diff(target.units(), base.units())); // 变异体 m1：两侧对调",
        )
    elif mid == "m3t7v-2":
        replace_exactly_once(
            UNITSTATE,
            "public record UnitState(Map<UnitId, Unit> units) {",
            "public record UnitState(Map<UnitId, Unit> units, String tag) { // 变异体 m2-A：新增第二组件",
        )
        replace_exactly_once(
            UNITSTATE,
            """    requireNoCycleAtKeyTimes(units);
  }""",
            """    requireNoCycleAtKeyTimes(units);
  }

  /** 变异体 m2-A：旧签名二级构造器，保全部旧调用点可编译（tag 缺省 ""）。 */
  public UnitState(Map<UnitId, Unit> units) {
    this(units, "");
  }""",
        )
    elif mid == "m3t7v-3":
        mutate("m3t7v-2")  # 先落 m2-A 的两处
        replace_exactly_once(
            ROUNDTRIP,
            """      case "units" -> base.withUnits(oneUnit());
      default -> throw new IllegalStateException("未登记的组件: " + name);""",
            """      case "units" -> base.withUnits(oneUnit());
      case "tag" -> base.withUnits(oneUnit()); // 变异体 m2-B：把 tag 注册进 mutate
      default -> throw new IllegalStateException("未登记的组件: " + name);""",
        )
        replace_exactly_once(
            ROUNDTRIP,
            """      case "units" -> cs.units().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);""",
            """      case "units" -> cs.units().changed();
      default -> true; // 变异体 m2-B：温和兜底""",
        )
    elif mid == "m3t7v-4":
        replace_exactly_once(
            UNITSTATE,
            "    units = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）",
            "    units = Map.copyOf(copy); // 变异体 m3：Map.copyOf 的槽位序",
        )
        replace_exactly_once(
            UNITSTATE,
            "import java.util.Collections;\n",
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
