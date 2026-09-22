#!/usr/bin/env python3
"""会话世代变异体定义：把某个**指定文件**改成某个确实不同的字节序列。

每个变异体 = 一组 (old, new) 精确替换；找不到 old（或 old 出现次数不为 1）就**响亮失败**，
绝不"什么都没改却说改了"（那会让整轮退化成假红/假绿）。
"""
import sys

# 文件名 → 该轮的目标（由 mut-round.sh 传入绝对路径，这里只按名字给定义）
MUTANTS = {
    # ── A：会话 id 派生（DecisionAgentRunner.java） ──────────────────────────────
    # m1：世代 0 也加后缀 ⇒ **老会话全部接不上**（现场已落盘的 decision-maker:<id> 找不到）。
    #     这正是本任务最硬的那条兼容要求的反面。
    "m1": [
        (
            """    if (conversationGeneration == 0) {""",
            """    if (false) {""",
        )
    ],
    # m3：重置时**顺手把旧会话改短**（把压缩点推到现在）⇒ 世界事实没错，但审计凭据被动了。
    #     compact 的契约：压缩点之前的行不删、但**不再被 load 返回** ⇒ 观测量就是 load 变短。
    "m3": [
        (
            """    String conversationId = conversationIdOf(dm);""",
            """    String conversationId = conversationIdOf(dm);
    if (dm.conversationGeneration() > 0) {
      conversations.compact(conversationIdOf(dm.id(), 0L), "重置时顺手压缩了旧会话");
    }""",
        )
    ],
    # ── B：重置命令的处理器（ResetDecisionMakerConversationHandler.java） ─────────
    # m2：世代**原地不动**（重置等于没重置）⇒ 下一轮又接回那一段。
    "m2": [
        (
            """              existing.conversationGeneration() + 1);""",
            """              existing.conversationGeneration());""",
        )
    ],
    # m4：**不校验决策人存在** ⇒ 拿一个不存在的 id 去重置，行为从"拒绝"变成别的（这里会 NPE 逃出去）。
    "m4": [
        (
            """      DecisionMaker existing = base.decisionMakers().get(id);
      if (existing == null) {
        return new HandlerOutcome.Rejected("决策人不存在: " + id.value());
      }
""",
            """      DecisionMaker existing = base.decisionMakers().get(id);
""",
        )
    ],
    # m7：重建时**漏带 providerId**（换成空）⇒ 重置会话顺手把 LLM 绑定解绑了（与 M11 的 m2 同族）。
    #     ★ 形态是"换成 Optional.empty()"而不是"删掉那一行"：删了是**编译不过**（7 参构造器少一个实参），
    #       那是 VOID 不是红——本装置第一版就是那么写的，实测被判 VOID（留档见 mutants/m7.log）。
    "m7": [
        (
            """              // ★ 与 M11 同一条纪律：重建路径必须带回 providerId，否则"换个会话"顺手把 provider 解绑了。
              existing.providerId(),""",
            """              java.util.Optional.<String>empty(),""",
        )
    ],
    # ── C：配权的重建路径（SetDecisionMakerAccessHandler.java） ───────────────────
    # m5：配权时**漏带世代** ⇒ 那个人被静默退回第 0 代（再次接回一段作废的会话）。
    "m5": [
        (
            """              // ★ 会话世代同理：漏了它 = 配一次权就把这个人的会话**退回第 0 代**（他又接回一段早已作废的会话），且没有症状。
              existing.conversationGeneration());""",
            """              0L);""",
        )
    ],
    # ── D：失败路径上报的会话 id（DecisionAgentService.java） ─────────────────────
    # m6：**只按 id 派生**（忽略世代）⇒ 重置之后失败路径报的是**早已作废**的那一段会话 id。
    "m6": [
        (
            """    return DecisionAgentRunner.conversationIdOf(maker);""",
            """    return DecisionAgentRunner.conversationIdOf(decisionMakerId, 0L);""",
        )
    ],
}


def main() -> int:
    name, path = sys.argv[1], sys.argv[2]
    edits = MUTANTS[name]
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    for old, new in edits:
        count = text.count(old)
        if count != 1:
            print(f"VOID 变异体 {name} 的锚点命中 {count} 次（必须恰好 1 次）", file=sys.stderr)
            return 3
        text = text.replace(old, new)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
