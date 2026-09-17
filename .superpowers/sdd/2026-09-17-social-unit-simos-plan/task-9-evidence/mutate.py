# M3 Task 9 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t9v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~8（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务四条变异（计划 Step 5 + R-9-b / R-9-c / R-9-d；目标都是 PathFinder.java）：
#   m3t9v-1（m1 ×2 高估，R-9-b 第一级）：两处 h 的计算 minStep × d → minStep × 2 × d。
#     R-9-b 预计：×2 高估在 R-9-a 校正后的夹具上**仍得最优**（追踪：绕行 f 更小先展开、终点 1500 先弹出）
#     ⇒ 预计存活；实际红/绿以日志为准。
#   m3t9v-2（m1' ×1000 近贪心，R-9-b 第二级）：同两处 → minStep × 1000 × d。
#     预期 aStarDetoursAroundExpensiveTerrain 或 matchesDijkstraOnCost 红（返回直线 5500）；
#     实际红点以日志为准。
#   m3t9v-3（m2，R-9-c）：比较器删掉全部平局项（只留 f）。R-9-c 预计：同进程内 PriorityQueue 对固定
#     插入序列是确定的 ⇒ sameInputTwice… 的"重跑相等"可能仍绿；但对称夹具**钉死了精确路径**
#     （全序下 = [(0,0),(1,-1),(2,-1)]）⇒ 若堆序变到另一条等价路径则该断言红。红/绿都写实。
#   m3t9v-4（m3，R-9-d）：删掉弹堆后的 closed 判重（整块 if 删除，closed 不再进账）。
#     R-9-d 预计：一致性 + bestG 守卫下可能仍得同一条最优路径（判重主要是性能护栏）；实际以日志为准。

import os
import sys

REPO = "/tmp/m3t9lab/repo"
FINDER = "simos-unit/src/main/java/io/mosire/simos/unit/move/PathFinder.java"


def files_of(mid):
    return {
        "m3t9v-1": [FINDER],
        "m3t9v-2": [FINDER],
        "m3t9v-3": [FINDER],
        "m3t9v-4": [FINDER],
    }[mid]


def mutate(mid):
    if mid == "m3t9v-1":
        replace_exactly_once(
            FINDER,
            "open.add(new Node(start, 0L, minStep * start.distanceTo(goal)));",
            "open.add(new Node(start, 0L, minStep * 2 * start.distanceTo(goal))); // 变异体 m1：h ×2 高估",
        )
        replace_exactly_once(
            FINDER,
            "open.add(new Node(next, g, minStep * next.distanceTo(goal)));",
            "open.add(new Node(next, g, minStep * 2 * next.distanceTo(goal))); // 变异体 m1：h ×2 高估",
        )
    elif mid == "m3t9v-2":
        replace_exactly_once(
            FINDER,
            "open.add(new Node(start, 0L, minStep * start.distanceTo(goal)));",
            "open.add(new Node(start, 0L, minStep * 1000 * start.distanceTo(goal))); // 变异体 m1'：h ×1000 近贪心",
        )
        replace_exactly_once(
            FINDER,
            "open.add(new Node(next, g, minStep * next.distanceTo(goal)));",
            "open.add(new Node(next, g, minStep * 1000 * next.distanceTo(goal))); // 变异体 m1'：h ×1000 近贪心",
        )
    elif mid == "m3t9v-3":
        replace_exactly_once(
            FINDER,
            """      Comparator.comparingLong(Node::f)
          .thenComparingLong(Node::h)
          .thenComparingInt(node -> node.hex().q())
          .thenComparingInt(node -> node.hex().r());""",
            """      Comparator.comparingLong(Node::f); // 变异体 m2：平局项全删，只留 f""",
        )
    elif mid == "m3t9v-4":
        replace_exactly_once(
            FINDER,
            """      if (!closed.add(current.hex())) {
        continue; // 一致性保证：先进入 closed 的那一份就是最优，后来的同格节点直接丢
      }""",
            """      // 变异体 m3：closed 判重整块删除（closed 不再进账；邻居展开处的 contains 恒 false）""",
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
