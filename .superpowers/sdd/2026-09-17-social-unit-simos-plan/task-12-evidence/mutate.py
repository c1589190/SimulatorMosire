# M3 Task 12 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t12v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~11（同一套坑照旧）：
#   ① 变异不得留下未使用的 import / 局部变量，也不得让 javac / checkstyle 报错 —— checkstyle 挂在
#      validate、编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务五轮（计划 Step 5 + R-12-b / R-12-c；目标都是 UnitResolver.java）：
#   m3t12v-m1：resolveChain 的 canonical 改成"把链原样回显" ⇒
#     预期 chainFormCanonicalisesToTheId 红（R13 的靶子）；
#     chainWithMultipleHitsIsOrderedByUnitId 预计连带红（其 get(0) canonical 断言同样吃的是 ID 形）——
#     红点以日志为准，全列如实记录。
#   m3t12v-m2：childrenAt 的 valueAt(at) 改"恒取首段值"（时点不敏感）⇒
#     预期**唯一**红 R-12-b 补条 chainFollowsTheParentAtTheQueryTime（1连 parent=[T0→1营, TS→空]：
#     valueAt(TS)=空 vs 首段值=1营 分叉）；计划原建议的夹具（T0→A、T10 无父）不分叉已被 R-12-b 指出并修正。
#   m3t12v-m3：删 hits.sort（多解按 UnitId 字典序保序）⇒
#     预期 chainWithMultipleHitsIsOrderedByUnitId 红（夹具插入序 u-b 在前、期望 u-a 先）。
#   m3t12v-m4：删 equipment 的 containsKey 判断 ⇒
#     预期 equipmentResolvesOnlyWhenTheNameExists 红（"没有该装备 ⇒ 空候选"断言收不到空）。
#   m3t12v-m3b：计划原建议的"根候选改所有单位（不筛无父）"——R-12-c 预言它在当前用例下**很可能存活**
#     （唯一能分叉的输入"链首是非根名"未被测）⇒ 选做，绿/红如实记录，存活不是失败。

import os
import sys

REPO = "/tmp/m3t12lab/repo"
UNIT_RESOLVER = "simos-unit/src/main/java/io/mosire/simos/unit/resolve/UnitResolver.java"


def files_of(mid):
    return {
        "m3t12v-m1": [UNIT_RESOLVER],
        "m3t12v-m2": [UNIT_RESOLVER],
        "m3t12v-m3": [UNIT_RESOLVER],
        "m3t12v-m4": [UNIT_RESOLVER],
        "m3t12v-m3b": [UNIT_RESOLVER],
    }[mid]


def mutate(mid):
    if mid == "m3t12v-m1":
        replace_exactly_once(
            UNIT_RESOLVER,
            """        for (Unit hit : hits) {
          subjects.add(subjectOf(hit, "Unit"));
        }
""",
            """        for (Unit hit : hits) { // 变异体 m1：canonical 改成链原样回显（R13 的靶子）
          subjects.add(
              new ResolvedSubject(
                  new SubjectId(NAMESPACE, hit.id().value()),
                  new Address(List.of(new Namespace(NAMESPACE), Entity.of(String.join(".", names))))
                      .canonical(),
                  "Unit"));
        }
""",
        )
    elif mid == "m3t12v-m2":
        replace_exactly_once(
            UNIT_RESOLVER,
            """import io.mosire.simos.util.time.SimosTimestamp;
""",
            """import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SimosTimestamp;
""",
        )
        replace_exactly_once(
            UNIT_RESOLVER,
            """      Optional<UnitId> parent = unit.parent().valueAt(at);
""",
            """      List<Segment<Optional<UnitId>>> parentSegments = unit.parent().segments();
      Optional<UnitId> parent = parentSegments.get(0).value(); // 变异体 m2：恒取首段值（不跟随查询时刻）
""",
        )
    elif mid == "m3t12v-m3":
        replace_exactly_once(
            UNIT_RESOLVER,
            """        hits.sort(Comparator.comparing(unit -> unit.id().value())); // 多解按 UnitId 字典序保序
""",
            """        // 变异体 m3：多解按 UnitId 字典序保序删除（保留插入序）
""",
        )
        replace_exactly_once(
            UNIT_RESOLVER,
            """import java.util.Comparator;
""",
            "",
        )
    elif mid == "m3t12v-m4":
        replace_exactly_once(
            UNIT_RESOLVER,
            """    if (!unit.equipment().containsKey(entity.name())) {
      return empty(); // 合法但没有该装备：空候选，不是错误
    }
""",
            """    // 变异体 m4：装备名存在性判断删除（没有该装备也解析出候选）
""",
        )
    elif mid == "m3t12v-m3b":
        replace_exactly_once(
            UNIT_RESOLVER,
            """    List<Unit> frontier = new ArrayList<>();
    for (Unit unit : state.units().values()) {
      if (unit.parent().valueAt(at).isEmpty()) {
        frontier.add(unit);
      }
    }
""",
            """    List<Unit> frontier = new ArrayList<>(); // 变异体 m3b：根候选不再筛"无父"，全部单位入围
    for (Unit unit : state.units().values()) {
      frontier.add(unit);
    }
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
