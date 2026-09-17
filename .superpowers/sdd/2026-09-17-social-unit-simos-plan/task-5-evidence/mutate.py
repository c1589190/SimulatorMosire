# M3 Task 5 变异实验室：按 id 把一条变异写进 /tmp 副本（**工作树一字不动**）。

# 用法：
#   python3 mutate.py <m3t5v-N>      # 把第 N 条变异写进 $REPO
#   python3 mutate.py --files <id>   # 打印该轮**声明**要改的文件集合（run.sh 用它做自证）

# ★ 装置形态继承 Task 1~4（同一套坑照旧）：
#   ① 变异不得留下未使用的 import，也不得让 javac / checkstyle 报错 —— checkstyle 挂在 validate、
#      编译在它之后，"红"必须是**断言红**而不是编译错误（COMPILATION ERROR count 必须为 0）；
#   ② 每轮**显式声明**文件集合（修改），run.sh 的自证是"实际改动 ∪ 实际新增 == 声明"。
#
# ★ 本任务三条变异（计划 Step 6 + R-5-e / R-5-b）：
#   m3t5v-1（m1）：**删掉 resolveHex 的 containsKey 判断**——缺席的格也无条件给候选 ⇒
#     absentHexIsAnEmptyCandidateNotAnError 红（空候选/抛的分工被拆）。
#   m3t5v-2（m2）：**把 single(...) 的 canonical 换成手写字符串拼接**（naiveCanonical 不走 AST 的
#     canonical()，逐段裸拼）⇒ mapId 含 `:` 时丢引号 ⇒ colonMapIdKeepsQuotesInCanonical 的两条断言
#     （根 + hex）都红，同根因；Map1 等裸词 mapId 的输出逐字不变 ⇒ 其余用例不红（这正是 R-5-b 补
#     靶子用例的理由：不加靶子则 m2 存活，白做）。
#   m3t5v-3（m3，二选一里选**删 instanceof 类型判断**）：dataOf 不再校验切片类型、直接强转 ⇒
#     wrongSliceTypeThrows 红（收到 ClassCastException 而非 IllegalArgumentException）；
#     missingSliceThrows 不受影响（orElseThrow 还在）。

import os
import sys

REPO = "/tmp/m3t5lab/repo"
RESOLVER = "simos-social/src/main/java/io/mosire/simos/social/resolve/SocialResolver.java"


def files_of(mid):
    return {
        "m3t5v-1": [RESOLVER],
        "m3t5v-2": [RESOLVER],
        "m3t5v-3": [RESOLVER],
    }[mid]


def mutate(mid):
    if mid == "m3t5v-1":
        replace_exactly_once(
            RESOLVER,
            """    if (!data.populations().containsKey(hex)) {
      return empty(); // 合法但不存在的格：空候选，不是错误
    }
""",
            "",
        )
    elif mid == "m3t5v-2":
        replace_exactly_once(
            RESOLVER,
            "        List.of(new ResolvedSubject(id, canonicalAddress.canonical(), typeName)));",
            "        List.of(new ResolvedSubject(id, naiveCanonical(canonicalAddress), typeName)));",
        )
        replace_exactly_once(
            RESOLVER,
            """  private static QueryResult empty() {""",
            """  /** 变异体 m2：canonical 手写拼接（不走 AST 的 canonical()），mapId 含 ":" 时丢引号。 */
  private static String naiveCanonical(Address address) {
    StringBuilder sb = new StringBuilder();
    for (AddressSegment segment : address.segments()) {
      if (sb.length() > 0) {
        sb.append(':');
      }
      if (segment instanceof Namespace ns) {
        sb.append(ns.ident());
      } else if (segment instanceof Entity e) {
        sb.append(e.kind().map(k -> k + ".").orElse("")).append(e.name());
      } else {
        sb.append(segment);
      }
    }
    return sb.toString();
  }

  private static QueryResult empty() {""",
        )
    elif mid == "m3t5v-3":
        replace_exactly_once(
            RESOLVER,
            """    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();""",
            """    return ((SocialSnapshot) snapshot).data();""",
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
