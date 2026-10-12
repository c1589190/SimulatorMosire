package io.mosire.simos.ga.adjudication;

import io.mosire.simos.ga.id.VerdictId;
import io.mosire.simos.ga.model.AdjudicationBreakpoint;
import io.mosire.simos.ga.model.Verdict;
import io.mosire.simos.ga.model.VerdictMeta;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.RevisionId;

/**
 * 判决冻结（spec §三.5 / §八.1，N7）：把**已通过 schema 的载荷**折成 {@link Verdict}——**只读已有数据、不调裁决器**。
 *
 * <p>★ **回放/分岔绝不重跑 LLM**（N7）就落在这里：冻结只做"校验 + 包装 + 记 atRevision"，没有**任何**调用 {@link
 * DecisionAdjudicator} / {@link LlmClient} 的路径。回放读到的是这个 {@link Verdict} 的**数据**。
 *
 * <p>拒绝：载荷不过该断点 schema；subject 不在该断点的裁决面（D1/D3/D6 = {@code army:combat.*}；D2 = {@code
 * sd:decision-maker.*}）。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：文案随判决面重挂而改（零逻辑）</b>——战斗判决面的命名空间 {@code sd:combat.*} → {@code
 * army:combat.*}（旧战斗族退役、交战记录归 army）。判据本身在 {@link Breakpoints#acceptsVerdictSubject}
 * （只改了一个命名空间字面量）；本类的<b>控制流一字未改</b>，只有那句用户可见的拒绝理由随之改写——留着旧串会让调用方照着一条查不到的地址去排错。
 */
public final class VerdictFreezer {

  private VerdictFreezer() {}

  public static Verdict freeze(
      VerdictId id,
      AdjudicationBreakpoint breakpoint,
      Address subject,
      String payloadJson,
      VerdictMeta meta,
      RevisionId atRevision) {
    if (!Breakpoints.acceptsVerdictSubject(breakpoint, subject)) {
      throw new IllegalArgumentException(
          "subject 不在断点 "
              + breakpoint.value()
              + " 的裁决面（D1/D3/D6 只裁决 army:combat.*；D2 只裁决 sd:decision-maker.*）: "
              + subject.canonical());
    }
    AdjudicationSchemas.validate(breakpoint, payloadJson);
    return new Verdict(id, breakpoint, subject, payloadJson, meta, atRevision);
  }
}
