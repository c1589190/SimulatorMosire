#!/usr/bin/env python3
"""判「preformat 存档 → 交付字节」是否**代码面 token 级零改动**。

为什么需要它：同目录的 check-format-only-comments.sh 按「行首是否注释」判改动行，
它**判不出折行**——`spotless:apply` 会把 `assertThat(x)\n    .as(y)` 收成一行、
把超长签名换行，这些是**同一个 token 序列**换了排版，却被行级判据记成「非注释改动」。
⇒ 本脚本按 token 判：剥离注释（字符串感知）→ 去掉全部空白 → 比对；
  并**逐条比对字符串字面量**（顺序 + 内容），因为「去掉空白」会掩盖字面量内部的空白改动。

用法: python3 check-token-identical.py  → 全同 rc=0 / 有真改动 rc=1
"""
import pathlib
import re
import sys

EV = pathlib.Path(
    "/home/dev/SimulatorMosire/.claude/worktrees/ts+m1/.superpowers/sdd/"
    "2026-09-22-tool-surface/m1-evidence"
)
WT = pathlib.Path("/home/dev/SimulatorMosire/.claude/worktrees/ts+m1")
PRE = EV / "mutants" / "preformat"
APP = WT / "simos-app" / "src"

PAIRS = [
    ("SimosToolSource.java", "main/java/io/mosire/simos/app/tools/SimosToolSource.java"),
    ("MapSetTerrainTool.java", "main/java/io/mosire/simos/app/tools/write/MapSetTerrainTool.java"),
    (
        "AbstractNarrowWriteTool.java",
        "main/java/io/mosire/simos/app/tools/write/AbstractNarrowWriteTool.java",
    ),
    ("SimosToolsTest.java", "test/java/io/mosire/simos/app/tools/SimosToolsTest.java"),
    ("McpServerTest.java", "test/java/io/mosire/simos/app/McpServerTest.java"),
    ("McpPortTopologyTest.java", "test/java/io/mosire/simos/app/McpPortTopologyTest.java"),
    ("MapSetEdgeTool.java", "main/java/io/mosire/simos/app/tools/write/MapSetEdgeTool.java"),
    ("MapCreateRegionTool.java", "main/java/io/mosire/simos/app/tools/write/MapCreateRegionTool.java"),
    ("MapUpdateRegionTool.java", "main/java/io/mosire/simos/app/tools/write/MapUpdateRegionTool.java"),
    ("MapDeleteRegionTool.java", "main/java/io/mosire/simos/app/tools/write/MapDeleteRegionTool.java"),
    (
        "MapRandomizeRegionTool.java",
        "main/java/io/mosire/simos/app/tools/write/MapRandomizeRegionTool.java",
    ),
    (
        "MapRegisterPathwayGroupTool.java",
        "main/java/io/mosire/simos/app/tools/write/MapRegisterPathwayGroupTool.java",
    ),
]

STR = re.compile(r'"(?:[^"\\\n]|\\.)*"')


def strip_comments(text):
    """剥 Java 注释（字符串/字符字面量感知），字面量原样保留。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' or c == "'":
            m = re.compile(r'"(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)*\'').match(text, i)
            if m:
                out.append(m.group(0))
                i = m.end()
                continue
            out.append(c)
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def norm(text):
    return re.sub(r"\s+", "", strip_comments(text))


def lits(text):
    return STR.findall(strip_comments(text))


def selftest():
    """双侧自证：① 自比必「同」（防恒报异）② 已知变异体必「异」（防恒报同）。"""
    a = PRE / "MapSetTerrainTool.java"
    m2 = EV / "mutants" / "whitelist" / "m2" / "MapSetTerrainTool.java"
    t = a.read_text(encoding="utf-8")
    same_self = norm(t) == norm(t)
    mut = m2.read_text(encoding="utf-8")
    diff_mut = (norm(t) == norm(mut)) or (lits(t) == lits(mut))
    print("===== 装置自证 =====")
    print(f"  ① 自比同 = {same_self}（须 True：否则判据恒报异）")
    print(f"  ② 已知变异体(m2 改了 commandType 返回值)被判异 = {not diff_mut}（须 True：否则判据恒报同）")
    return same_self and not diff_mut


def main():
    bad = 0
    if not selftest():
        print("★★ 装置自证失败 ⇒ 本轮读数作废（先怀疑判据，别先怀疑被测物）")
        return 2
    print("文件 / token 级(去空白) / 字符串字面量(条数, 顺序)")
    for base, rel in PAIRS:
        p1, p2 = PRE / base, APP / rel
        if not (p1.is_file() and p2.is_file()):
            p1 = PRE / "renamed-hyph" / base
        if not (p1.is_file() and p2.is_file()):
            print(f"  ★ 缺文件: {base}  pre={p1.is_file()} now={p2.is_file()}")
            bad += 1
            continue
        t1, t2 = p1.read_text(encoding="utf-8"), p2.read_text(encoding="utf-8")
        same_code = norm(t1) == norm(t2)
        l1, l2 = lits(t1), lits(t2)
        same_lit = l1 == l2
        ok = same_code and same_lit
        if not ok:
            bad += 1
        print(
            f"  {base:<34} code={'同' if same_code else '★异'}  "
            f"lits={len(l1)}→{len(l2)} {'同' if same_lit else '★异'}  "
            f"{'OK' if ok else '★★ 真改动'}"
        )
        if not same_lit:
            for k, (a, b) in enumerate(zip(l1, l2)):
                if a != b:
                    print(f"      字面量#{k} pre={a}")
                    print(f"      字面量#{k} now={b}")
                    break
            if len(l1) != len(l2):
                print(f"      条数不同：pre={len(l1)} now={len(l2)}")
    print(
        f"结论：{'代码面 token 级零改动（折行/缩进/注释除外）⇒ 变异轮与门禁证据对交付字节仍成立' if bad == 0 else '★★ 存在真改动 ⇒ 必须按「改动被测文件」重跑相关轮'}"
    )
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
