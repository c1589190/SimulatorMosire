# M3 Task 4 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t4v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1/2/3（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#      ⇒ m2 把 unmodifiableMap 换成 Map.copyOf 时必须**连 import java.util.Collections 一起删**，
#        否则 UnusedImports 先红，那不是被保护那行的红（装置内直接处理，避免这轮作废）；
#   ② 每轮**显式声明**文件集合（修改），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务三条变异（计划 Step 5 + R-4-b/c）：
#   m3t4v-1（m1）：**between 的两个实参都传 base.populations()**——没有真的比较两侧 ⇒
#     diff(base, base) 恒为 Unchanged ⇒ 单改/增删/删除用例与往返用例红（同根因，实际红点以日志为准）。
#   m3t4v-2a/2b/2（m2，同一变异的三次观测 = R-4-b 轨迹）：**Collections.unmodifiableMap(copy) 换成
#     Map.copyOf(copy)**——保序冻结被换成散列槽位序。2a=计划现状夹具；2b=夹具加到 5 键；
#     2=补 populationOrderFollowsInsertionOrder 后重跑（5 键非平凡插入序，本机实测 0/20 JVM 保序）。
#   m3t4v-3（m3）：**apply 把 rebuild 的第二个实参换成 new FieldDelta.Unchanged<>()**——apply 不吃
#     delta ⇒ 一切"变了"的用例 apply 后仍返回 base（同根因红，实际红点以日志为准）。

import os
import sys

REPO = "/tmp/m3t4lab/repo"
CS = "simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java"
DATA = "simos-social/src/main/java/io/mosire/simos/social/SocialData.java"


def files_of(mid):
    return {
        "m3t4v-1": [CS],
        "m3t4v-2a": [DATA],
        "m3t4v-2b": [DATA],
        "m3t4v-2": [DATA],
        "m3t4v-3": [CS],
    }[mid]


def mutate(mid):
    if mid == "m3t4v-1":
        replace_exactly_once(
            CS,
            "FieldDelta.diff(base.populations(), target.populations())",
            "FieldDelta.diff(base.populations(), base.populations())",
        )
    elif mid in ("m3t4v-2a", "m3t4v-2b", "m3t4v-2"):
        replace_exactly_once(
            DATA,
            "import java.util.Collections;\n",
            "",
        )
        replace_exactly_once(
            DATA,
            "populations = Collections.unmodifiableMap(copy);",
            "populations = Map.copyOf(copy);",
        )
    elif mid == "m3t4v-3":
        replace_exactly_once(
            CS,
            "FieldDelta.rebuild(base.populations(), cs.populations(), HexCoord::parse)",
            "FieldDelta.rebuild(base.populations(), new FieldDelta.Unchanged<>(), HexCoord::parse)",
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
