package io.mosire.simos.sd.adjudication;

import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.model.VerdictMeta;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.RevisionId;

/**
 * 判决冻结（spec §三.5 / §八.1，N7）：把**已通过 schema 的载荷**折成 {@link Verdict}——**只读已有数据、不调裁决器**。
 *
 * <p>★ **回放/分岔绝不重跑 LLM**（N7）就落在这里：冻结只做"校验 + 包装 + 记 atRevision"，没有**任何**调用 {@link
 * DecisionAdjudicator} / {@link LlmClient} 的路径。回放读到的是这个 {@link Verdict} 的**数据**。
 *
 * <p>拒绝：载荷不过该断点 schema；subject 不在该断点的裁决面（D1/D3/D6 = {@code sd:combat.*}；D2 = {@code
 * sd:decision-maker.*}）。
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
              + " 的裁决面（D1/D3/D6 只裁决 sd:combat.*；D2 只裁决 sd:decision-maker.*）: "
              + subject.canonical());
    }
    AdjudicationSchemas.validate(breakpoint, payloadJson);
    return new Verdict(id, breakpoint, subject, payloadJson, meta, atRevision);
  }
}
