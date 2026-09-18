#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Task 13 的变异体生成器。

纪律（CLAUDE.md 形态 1）：每一处替换**都断言它真的发生了**——否则会安静地做出一个"与原件
字节相同"的变异体，被 mut-round.sh 的门禁 2 拦下，白跑一轮还看不出为什么。
"""
import pathlib
import sys

EVID = pathlib.Path(
    ".superpowers/sdd/2026-09-18-core-simos-plan/task-13-evidence/mutants"
)
ORIG = EVID / "orig"
OUT = EVID

FAILED = []


def mutate(name, target, edits):
    """edits: [(旧串, 新串, 说明)]，逐条替换并要求命中恰好一次。"""
    text = (ORIG / target).read_text(encoding="utf-8")
    for old, new, why in edits:
        n = text.count(old)
        if n != 1:
            FAILED.append(f"{name}: 锚点命中 {n} 次（应为 1）—— {why}")
            return
        text = text.replace(old, new)
    (OUT / f"{name}.{target}").write_text(text, encoding="utf-8")
    print(f"  ok  {name}.{target}   ({len(edits)} 处)")


# ── m1：ForkBranch 之后不写源 head 的 checkpoint（计划指定的变异体）───────────────────
mutate(
    "t13-m1",
    "CoreSimos.java",
    [
        (
            """    if (command instanceof ForkBranch fork) {
      // C19 第②项：分岔点必须有一份（写完才让"重放上界 ≤ N"跨分支成立，spec §3.4 ④）
      maybeWriteCheckpoint(new StateRef(fork.source(), fork.expectedRevision()));
      return;
    }""",
            """    if (command instanceof ForkBranch) {
      // 变异 m1：分岔点不写 checkpoint（删掉 C19 第②项的落地）
      return;
    }""",
            "m1：删掉分岔点的 checkpoint 写入",
        )
    ],
)

# ── m2：Timeline.fork 把新分支的 revision 从 1 改成 0（计划指定的变异体）───────────────
mutate(
    "t13-m2",
    "Timeline.java",
    [
        (
            """                  newBranch,
                  new RevisionId(1),""",
            """                  newBranch,
                  new RevisionId(0), // 变异 m2：新分支从 0 起，而不是 1（C13）""",
            "m2：新分支 revision 从 0 起",
        )
    ],
)

# ── m3：封存后静默忽略注册（封存护栏的变异体）──────────────────────────────────────
mutate(
    "t13-m3",
    "CoreSimos.java",
    [
        (
            """  private void requireNotSealed() {
    if (sealed) {
      throw new IllegalStateException(
          "装配已封存（seal）：register 必须在第一次 submit/replay 之前完成"
              + "——CommandRegistry 是构造期不可变的（spec §4.3），封存后再注册会静默不生效");
    }
  }""",
            """  private void requireNotSealed() {
    // 变异 m3：封存后**静默忽略**注册（不抛）
  }""",
            "m3：封存护栏失效",
        )
    ],
)

# ── m4：信封支也**无条件**写 checkpoint（多写文件，break R3 的反方向）──────────────────
mutate(
    "t13-m4",
    "CoreSimos.java",
    [
        (
            """    if (!timeline.hasCheckpoint(ref)) {
      return;
    }
    try {""",
            """    // 变异 m4：不判 C19 谓词，见 ref 就写（多写文件）
    try {""",
            "m4：删掉 hasCheckpoint 守卫",
        )
    ],
)

# ── m5：信封支命中 C19 时**不写** checkpoint（正向行为的变异体）──────────────────────
mutate(
    "t13-m5",
    "CoreSimos.java",
    [
        (
            """    if (command instanceof CommandEnvelope) {
      // C19 第①项：只有周期命中才写——绝不写比谓词更多的文件（否则 R3 的反方向破）
      maybeWriteCheckpoint(committed.ref());
      return;
    }""",
            """    if (command instanceof CommandEnvelope) {
      // 变异 m5：信封支即使命中 C19 也不写 checkpoint（正向行为被删）
      return;
    }""",
            "m5：删掉信封支的 checkpoint 写入",
        )
    ],
)

if FAILED:
    print("\n!! 有锚点没命中，变异体不可信：")
    for line in FAILED:
        print("   " + line)
    sys.exit(1)
print("\n全部变异体已生成（锚点逐条命中）")
