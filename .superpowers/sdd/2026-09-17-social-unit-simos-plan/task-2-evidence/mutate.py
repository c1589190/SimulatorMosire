# M3 Task 2 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t2v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务两条变异都是**就地数值替换**（999→998），不动 import、不动结构：
#   m3t2v-1（m1）：TerrainType 的常量 999→998 —— 守卫必须读**常量**而非记死字面量：
#     oceanUsesTheImpassableSentinel 断言 isEqualTo(IMPASSABLE_MOVE_COST)，常量改 998 后
#     词表 ocean 仍是 999 ⇒ 红（expected: 998 but was: 999）；everyOtherTerrainIsBelowTheSentinel
#     下非海洋最大值 12 ≤ 997 仍绿 ⇒ 两条用例各管一件事。
#   m3t2v-2（m2）：TerrainCatalog 里 ocean 的取值 999→998 —— 守卫必须钉住**词表与常量同源**：
#     oceanUsesTheImpassableSentinel 红（expected: 999 but was: 998）；
#     everyOtherTerrainIsBelowTheSentinel 的 filteredOn 把 ocean 排除（R-2-a）⇒ 确定性仍绿。

import os
import sys

REPO = "/tmp/m3t2lab/repo"
CLS_TYPE = "simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java"
CLS_CATALOG = "simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainCatalog.java"


def files_of(mid):
    return {
        "m3t2v-1": [CLS_TYPE],
        "m3t2v-2": [CLS_CATALOG],
    }[mid]


def mutate(mid):
    # ── 变异表：就地数值替换（999→998），每条只允许命中一处 ──
    if mid == "m3t2v-1":
        replace_exactly_once(
            CLS_TYPE,
            "public static final int IMPASSABLE_MOVE_COST = 999;",
            "public static final int IMPASSABLE_MOVE_COST = 998;",
        )
    elif mid == "m3t2v-2":
        replace_exactly_once(
            CLS_CATALOG,
            'new TerrainType("ocean", "海洋", "#1F5FA0", 0.00, 0.30, 0, 0, 0, 999,',
            'new TerrainType("ocean", "海洋", "#1F5FA0", 0.00, 0.30, 0, 0, 0, 998,',
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
