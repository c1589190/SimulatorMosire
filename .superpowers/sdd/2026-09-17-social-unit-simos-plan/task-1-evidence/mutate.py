# M3 Task 1 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t1v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>  # 打印该轮**声明**要改/新增的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 M2 Task 14（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改 ∪ 新增），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本轮的扫描裁决 R-1-a（**覆盖计划 Step 8 第 3 条的"类名与文件名一致"写法**）：
#   R1 守卫做**逐行子串**搜索 `interface FieldDelta`。若变异体声明成 `interface LegacyFieldDelta`，
#   该行**不含**那个子串 ⇒ 守卫不红，变异白做。故：**文件名**用 LegacyFieldDelta.java（避开与 util
#   那份同名导致的编译错——那是"红在编译"不算数），**声明名必须是 FieldDelta**；且**不写 public**
#   （包级私有），控制器已用 javac 探针实测过这种形态可编译；同包其余文件（MapChangeSet /
#   MapChangeSetTest / RoundTripComponentsTest）都带 `import io.mosire.simos.util.state.FieldDelta;`
#   ——单类型导入按 JLS 6.4.1 **遮蔽**同包同名类型 ⇒ 不引发编译错误（COMPILATION ERROR count = 0 自证）。

import os
import sys

REPO = "/tmp/m3t1lab/repo"
CLS_LEGACY = "simos-map/src/main/java/io/mosire/simos/map/change/LegacyFieldDelta.java"


def files_of(mid):
    return {
        "m3t1v-1": [CLS_LEGACY],
    }[mid]


def mutate(mid):
    # ── 变异表 1：第二份 FieldDelta 声明（R1 的病灶形态，新增文件）──
    if mid == "m3t1v-1":
        write(
            CLS_LEGACY,
            "package io.mosire.simos.map.change;\n"
            "\n"
            "/** 变异 m3t1v-1：第二份 FieldDelta 声明（声明名必须是 FieldDelta——R1 扫描的是逐行子串\n"
            " * `interface FieldDelta`，改名成 LegacyFieldDelta 守卫不会红，见 R-1-a）。包级私有：同包\n"
            " * 文件带单类型导入时按 JLS 6.4.1 被遮蔽，不引发编译错误。 */\n"
            "interface FieldDelta<T> {}\n",
        )
    else:
        raise AssertionError(f"未知变异 id: {mid}")

    for path in files_of(mid):
        if not os.path.isfile(f"{REPO}/{path}"):
            raise AssertionError(f"{mid}: 声明的文件不存在: {path}")


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
