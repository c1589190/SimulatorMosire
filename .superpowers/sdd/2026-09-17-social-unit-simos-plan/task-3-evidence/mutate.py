# M3 Task 3 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t3v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1/2（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务三条变异（R-3-b / R-3-c / 计划 m3）都动 PopulationSeries.java：
#   m3t3v-1（m1）：**删掉 `cuts.add(t);`**——查询点不进切分点，"每区间一次舍入"被破坏。
#     种子表在每区间舍入下全是整数（4000/3500/1336 精确），任何"舍入挪位/改复利"类变异产不出
#     差异（存活 mutant 白做）；能改结果的只有切分结构/基数。删这行后查询点 53 不是切分点，
#     切分集只剩 {0,20,45}：末事件 t=45 成了最后一个切分点，其后的增长**整个不施**（[45,70)
#     不形成——70 被 from ≤ t=53 过滤掉）⇒ valueAt(53)=16700，seedExampleMatchesTheHandComputedTable
#     红（expected: 18036L）。同根因连带（boundary/multi，查询点 20 同样不进切分集）为可接受，
#     实际红点以日志为准（实测 3 红：seed 18036→16700、boundary 6300→2100、multi 15000→5000）。
#   m3t3v-2（m2）：**反转 applyEventsAt 的施加序**（同刻按插入序的破坏）——把 for-each 换成
#     倒序索引循环。种子的单事件不受影响 ⇒ 期望唯一红 multipleEventsAtTheSameTickFollowInsertionOrder
#     （SET 先于 ADD ⇒ t=10 基数 5000+100=5100，[10,20) 增 round(5100×0.2×10)=10200 ⇒ 15300≠15000）。
#   m3t3v-3（m3）：**删掉 `growth.events().isEmpty()` 校验** ⇒ growthCarryingEventsIsRejected 红
#     （非法构造不再抛，assertThatThrownBy 落空）。

import os
import sys

REPO = "/tmp/m3t3lab/repo"
CLS = "simos-social/src/main/java/io/mosire/simos/social/population/PopulationSeries.java"


def files_of(mid):
    return {
        "m3t3v-1": [CLS],
        "m3t3v-2": [CLS],
        "m3t3v-3": [CLS],
    }[mid]


def mutate(mid):
    if mid == "m3t3v-1":
        replace_exactly_once(CLS, "    cuts.add(t);\n", "")
    elif mid == "m3t3v-2":
        replace_exactly_once(
            CLS,
            "    long result = population;\n"
            "    for (Event<Long> event : events) {\n",
            "    long result = population;\n"
            "    for (int i = events.size() - 1; i >= 0; i--) {\n"
            "      Event<Long> event = events.get(i);\n",
        )
    elif mid == "m3t3v-3":
        replace_exactly_once(
            CLS,
            "    if (!growth.events().isEmpty()) {\n"
            "      throw new IllegalArgumentException(\n"
            '          "growth 不得带事件：速率的变更一律用追加段表达（否则段内积分会按左端点取速率、静默算错，spec §3.2 第 3 条）");\n'
            "    }\n",
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
