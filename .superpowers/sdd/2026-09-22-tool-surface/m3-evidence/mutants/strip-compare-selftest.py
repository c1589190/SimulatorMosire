#!/usr/bin/env python3
"""strip-compare.py 的**双侧自证**（纪律：拒收判据与接收判据都要有一条真样本，只证一侧等于没证）。

三个真样本，全部现场造、现场跑：
  ① 只改注释 ⇒ 必须 IDENTICAL（接收侧样本）；
  ② 改一个**代码 token** ⇒ 必须 DIFFERENT 且走 code 分支（拒收侧样本 a）；
  ③ 改一个**字符串字面量里的空格** ⇒ 必须 DIFFERENT 且走 literal 分支（拒收侧样本 b）——
     这是「全局空白折叠」写法会漏掉的那一格，本脚本专门钉它。
  ④ 附带：把同一份文件与自己比 ⇒ 必须被**拒收**（raw md5 相同 ⇒ 证明不了任何事）。
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(HERE, "strip-compare.py")
SRC = os.path.join(HERE, "pre-spotless", "SimosToolsTest.java")

with open(SRC, encoding="utf-8") as handle:
    BASE = handle.read()
# 断言读到非空
assert len(BASE) > 10000, "读到空/过短的样本：%d 字节" % len(BASE)

# 找一个真实的字符串字面量做 ③ 的靶子
probe_literal = '"sd.CreateNation"'
assert probe_literal in BASE, "样本里找不到靶字面量 %s" % probe_literal

CASES = []


def run(label, left, right, expect_rc, expect_word):
    with tempfile.TemporaryDirectory() as tmp:
        la = os.path.join(tmp, "A.java")
        lb = os.path.join(tmp, "B.java")
        with open(la, "w", encoding="utf-8") as handle:
            handle.write(left)
        with open(lb, "w", encoding="utf-8") as handle:
            handle.write(right)
        proc = subprocess.run(
            [sys.executable, CHECKER, la, lb], capture_output=True, text=True, check=False
        )
        out = proc.stdout + proc.stderr
        ok = proc.returncode == expect_rc and expect_word in out
        CASES.append((label, ok, proc.returncode, expect_rc, expect_word))
        print("── %s" % label)
        print("   rc=%d（期望 %d）" % (proc.returncode, expect_rc))
        for line in out.strip().splitlines():
            print("   | %s" % line)
        print("   ⇒ %s" % ("OK" if ok else "**不符合期望**"))
    return ok


run(
    "① 只改注释 ⇒ 期望 IDENTICAL",
    BASE,
    BASE.replace("  @Test\n", "  // 新加的一行注释\n  @Test\n", 1),
    0,
    "IDENTICAL",
)
run(
    "② 改一个代码 token ⇒ 期望 DIFFERENT 走 code 分支",
    BASE,
    BASE.replace("SDL_NARROW_WRITE_NAMES", "SDL_NARROW_WRITE_NAMEZ", 1)
    if "SDL_NARROW_WRITE_NAMES" in BASE
    else BASE.replace("MapCreateRegionTool.NAME", "MapCreateRegionTool.NAME.", 1),
    1,
    "代码被改动",
)
run(
    "③ 改字符串字面量里的一个空格 ⇒ 期望 DIFFERENT 走 literal 分支",
    BASE,
    BASE.replace(probe_literal, '"sd. CreateNation"', 1),
    1,
    "字面量被改动",
)
run("④ 与自己比 ⇒ 期望被拒收", BASE, BASE, 2, "拒收")

print()
bad = [name for name, ok, *_ in CASES if not ok]
print("自证结论: %d/%d 条符合期望" % (len(CASES) - len(bad), len(CASES)))
if bad:
    print("不符合期望的样本: %s" % bad)
    sys.exit(1)
print("strip-compare.py 的双侧自证: OK")
