#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Task 12 的变异体生成器。

纪律（CLAUDE.md 形态 1）：每一处替换**都断言它真的发生了**——否则会安静地做出一个"与原件
字节相同"的变异体，被 mut-round.sh 的门禁 2 拦下，白跑一轮还看不出为什么。
"""
import pathlib
import sys

EVID = pathlib.Path(".superpowers/sdd/2026-09-18-core-simos-plan/task-12-evidence/mutants")
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


# ── m1：③ 只查一个方向（计划指定的变异体）──────────────────────────────────────────
mutate(
    "t12-m1",
    "TimeProposalResolver.java",
    [
        (
            """    for (TimeProposal reader : proposals) {
      for (TimeProposal writer : proposals) {
        if (reader.namespace().equals(writer.namespace())) {
          continue; // 自交不算冲突
        }
        Set<String> shared = intersection(reader.reads(), writer.writes());""",
            """    for (int ri = 0; ri < proposals.size(); ri++) {
      for (int wi = ri + 1; wi < proposals.size(); wi++) {
        TimeProposal reader = proposals.get(ri);
        TimeProposal writer = proposals.get(wi);
        Set<String> shared = intersection(reader.reads(), writer.writes());""",
            "m1：只查 reads(前) ∩ writes(后) 一个方向",
        )
    ],
)

# ── m2：写-写改成"记警告并放行"（计划指定的变异体）──────────────────────────────────
mutate(
    "t12-m2",
    "TimeProposalResolver.java",
    [
        (
            """    // ── 写-写：任意两两（i<j 天然排除了"自己和自己"，即"自交不算冲突"）──────────────────
    for (int i = 0; i < proposals.size(); i++) {""",
            """    // 变异 m2：warnings 提前声明，好让写-写也能往里塞
    List<AdvanceConflict> warnings = new ArrayList<>();
    for (int i = 0; i < proposals.size(); i++) {""",
            "m2 前置：提声明",
        ),
        (
            """        if (!shared.isEmpty()) {
          return new Outcome.Blocked(
              new AdvanceConflict(
                  AdvanceConflict.WRITE_WRITE,
                  List.of(left.namespace(), right.namespace()),
                  List.copyOf(shared)));
        }""",
            """        if (!shared.isEmpty()) {
          warnings.add(
              new AdvanceConflict(
                  AdvanceConflict.WRITE_WRITE,
                  List.of(left.namespace(), right.namespace()),
                  List.copyOf(shared)));
        }""",
            "m2：写-写不再 Blocked，退化成警告",
        ),
        (
            """    List<AdvanceConflict> warnings = new ArrayList<>();
    for (TimeProposal reader : proposals) {""",
            """    for (TimeProposal reader : proposals) {""",
            "m2 后置：删掉重复声明",
        ),
    ],
)

# ── m3：Validate 去掉第 0 项（计划指定的变异体）─────────────────────────────────────
mutate(
    "t12-m3",
    "TimeAdvance.java",
    [
        (
            """    if (cmd.range().to().isEmpty()) {
      return rejected(cmd, trace, "推进必须有上界（range.to 缺失）：AdvanceTime 是写操作，语义上不允许开区间");
    }""",
            """    // 变异 m3：第 0 项整条去掉""",
            "m3：删掉缺 to 的拒绝",
        ),
        (
            """    StateMeta newMeta = new StateMeta(target, cmd.range().to().orElseThrow());""",
            """    StateMeta newMeta =
        new StateMeta(target, cmd.range().to().orElse(cmd.range().from())); // 变异 m3：缺 to 就退回 from""",
            "m3 配套：让删掉检查之后仍可运行（否则红在 NoSuchElement，红的理由不是被保护的行为）",
        ),
    ],
)

# ── m4：冲突地址列表不排序（计划指定的变异体）★ 对着 SpotBugs 修法后的新字节重跑 ───────────────────────────────────────
mutate(
    "t12-m4",
    "AdvanceConflict.java",
    [
        (
            """    addresses = List.copyOf(sortedCheckedCopy(addresses, "addresses"));""",
            """    addresses = List.copyOf(checkedCopy(addresses, "addresses")); // 变异 m4：不排序""",
            "m4：地址不排序",
        )
    ],
)

# ── m5：namespaces 也排序（★ Task 12 实测缺陷的复原体）★ 对着新字节重跑 ─────────────────────────────
mutate(
    "t12-m5",
    "AdvanceConflict.java",
    [
        (
            """    namespaces = List.copyOf(checkedCopy(namespaces, "namespaces"));""",
            """    namespaces = List.copyOf(sortedCheckedCopy(namespaces, "namespaces")); // 变异 m5：把方向抹掉（Task 12 真实犯过的错）""",
            "m5：namespaces 排序 ⇒ 有向对退化成同一个值",
        )
    ],
)

# ── m6：参与者按注册序，不按 namespace 字典序（C25 / 裁定 44）──────────────────────
# ★ 首轮这个变异体**作废**了：换成 LinkedHashMap 之后 `import java.util.TreeMap;` 变成未使用，
#   而 Checkstyle 的 UnusedImports 会在**跑用例之前**把构建打掉（`Tests run` 行数 = 0）。
#   装置的门禁 4（"一条用例都没跑到 ⇒ 这不叫红"）当场把它判成作废——**这正是那道门禁存在的理由**：
#   它把"红在 Checkstyle"与"红在断言"分开了。修法是把 import 一并删掉。
mutate(
    "t12-m6",
    "TimeAdvance.java",
    [
        (
            """import java.util.TreeMap;
""",
            "",
            "m6 配套：删掉将变成未使用的 import（否则 Checkstyle 先把构建打掉）",
        ),
        (
            """    TreeMap<String, TimeParticipant> sorted = new TreeMap<>();""",
            """    Map<String, TimeParticipant> sorted =
        new LinkedHashMap<>(); // 变异 m6：保注册序，不排序""",
            "m6：参与者清单不排序",
        ),
    ],
)

# ── m7：提交失败无条件折成 Conflict（裁定 48 的要害）───────────────────────────────
# ★ 这一处用**正则整段吞**：锚点跨了 12 行中文注释，而中文注释的折行由 google-java-format 决定
#   （CLAUDE.md：改完跑 spotless:apply）⇒ 逐字节锚点会在下次格式化后失效。非贪婪 + DOTALL 只认
#   两端的代码行，中间的注释怎么折都吃得下。
def mutate_regex(name, target, pattern, replacement, why):
    import re

    path = ORIG / target
    text = path.read_text(encoding="utf-8")
    new_text, n = re.subn(pattern, replacement, text, flags=re.S)
    if n != 1:
        FAILED.append(f"{name}: 正则命中 {n} 次（应为 1）—— {why}")
        return
    (OUT / f"{name}.{target}").write_text(new_text, encoding="utf-8")
    print(f"  ok  {name}.{target}   (正则)")


mutate_regex(
    "t12-m7",
    "TimeAdvance.java",
    r"      // 裁定 48.*?\n      throw e;\n    \}",
    """      // 变异 m7：无条件折成冲突——把**任何**提交失败都当成"别人抢先了"
      List<EventRow> conflictTrace = new ArrayList<>();
      conflictTrace.add(received(cmd));
      return conflict(cmd, conflictTrace, new StateRef(cmd.branch(), cmd.expectedRevision()));
    }""",
    'm7：删掉「动了才折」的判定与「没动就重抛」的那一半',
)

# ── m8：checkpoint 只写动过的模块（丢掉未触碰的）───────────────────────────────────
mutate(
    "t12-m8",
    "TimeAdvance.java",
    [
        (
            """    Map<String, Snapshot> modules = new LinkedHashMap<>(base.modules());
    modules.putAll(applied); // 只覆盖本次推进动过的模块""",
            """    Map<String, Snapshot> modules =
        new LinkedHashMap<>(applied); // 变异 m8：只写动过的模块，未触碰的丢掉""",
            "m8：checkpoint 丢掉未触碰的模块",
        )
    ],
)

# ── m9：信封的 meta 用 base 的坐标而非新坐标 ────────────────────────────────────────
mutate(
    "t12-m9",
    "TimeAdvance.java",
    [
        (
            """      String envelopeJson =
          CheckpointEncoder.encode(
              new SimulationState(newMeta, modules, base.info()), codecs.values());""",
            """      String envelopeJson =
          CheckpointEncoder.encode(
              new SimulationState(
                  base.meta(), modules, base.info()), // 变异 m9：信封坐标用 base 的
              codecs.values());""",
            "m9：信封 meta 写错坐标",
        )
    ],
)

if FAILED:
    print("\n!! 有锚点没命中，变异体不可信：")
    for line in FAILED:
        print("   " + line)
    sys.exit(1)
print("\n全部变异体已生成（锚点逐条命中）")
