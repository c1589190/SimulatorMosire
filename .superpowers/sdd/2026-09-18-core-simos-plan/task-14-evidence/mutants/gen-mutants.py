#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Task 14 的变异体生成器。

纪律（CLAUDE.md 形态 1）：每一处替换**都断言它真的发生了**——否则会安静地做出一个"与原件
字节相同"的变异体，被 mut-round.sh 的门禁 2 拦下，白跑一轮还看不出为什么。
"""
import pathlib
import sys

EVID = pathlib.Path(
    ".superpowers/sdd/2026-09-18-core-simos-plan/task-14-evidence/mutants"
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


# ── m1：Timeline.head 忽略 branch 参数，取全局 MAX(revision)（计划指定的变异体）─────────
# 期望红：判据一③ 的 head 断言（BranchingEndToEndTest.forkEndToEndWithRealRenameCommands
# 的 "③ b2 在分岔后自己推进一格，head 必须是 2"）。
mutate(
    "t14-m1",
    "Timeline.java",
    [
        (
            """  /** head(b)（spec §3.3）：分支不存在 ⇒ 空。 */
  public Optional<RevisionId> head(BranchId branch) {
    return store.inTransaction(connection -> head(connection, branch));
  }""",
            """  /** head(b)（spec §3.3）：分支不存在 ⇒ 空。 */
  public Optional<RevisionId> head(BranchId branch) {
    // 变异 m1：忽略 branch 参数，取全局 MAX(revision)（打判据一③）
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT MAX(revision) FROM " + REVISIONS_TABLE);
              ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              return Optional.empty();
            }
            long max = resultSet.getLong(1);
            if (resultSet.wasNull()) {
              return Optional.empty();
            }
            return Optional.of(new RevisionId(max));
          }
        });
  }""",
            "m1：head 忽略分支，全局 MAX",
        )
    ],
)

# ── m2：Replay 施加变更集的那一步被删掉（本任务新增的逐值断言的变异体）──────────────────
# 期望红：判据一① 的逐值断言（main 的重放状态必须看见 main 自己的改名）。
mutate(
    "t14-m2",
    "Replay.java",
    [
        (
            """      WorldChangeSet changeset = Timeline.readChangeSet(row.changesetJson());
      state = applyWorld(state, changeset, new StateMeta(ref, row.timestamp()));
      applyCount++;""",
            """      WorldChangeSet changeset = Timeline.readChangeSet(row.changesetJson());
      // 变异 m2：不施加变更集（逐值断言应抓到）
      applyCount++;""",
            "m2：重放不施加变更集",
        )
    ],
)

if FAILED:
    print("\n!! 有锚点没命中，变异体不可信：")
    for line in FAILED:
        print("   " + line)
    sys.exit(1)
print("\n全部变异体已生成（锚点逐条命中）")
