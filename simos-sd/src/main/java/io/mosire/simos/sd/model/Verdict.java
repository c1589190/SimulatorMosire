package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.RevisionId;

/**
 * 判决（spec §三.5，N7）：**判决是数据**、落进 revision；回放 / 分岔**绝不重跑 LLM**。
 *
 * <p>★ {@code payloadJson} 是判决载荷（可含 {@code PutInfo} 型 action，spec §三.5）；{@code atRevision} 冻结落盘坐标 ⇒
 * 可回放且 逐值相等。subject 的"是否在该断点裁决面"由命令期校验（D3）。
 */
public record Verdict(
    VerdictId id,
    AdjudicationBreakpoint breakpoint,
    Address subject,
    String payloadJson,
    VerdictMeta meta,
    RevisionId atRevision) {

  public Verdict {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (breakpoint == null) {
      throw new IllegalArgumentException("breakpoint 不得为 null");
    }
    if (subject == null) {
      throw new IllegalArgumentException("subject 不得为 null");
    }
    if (payloadJson == null) {
      throw new IllegalArgumentException("payloadJson 不得为 null（无载荷用 \"{}\"）");
    }
    if (meta == null) {
      throw new IllegalArgumentException("meta 不得为 null");
    }
    if (atRevision == null) {
      throw new IllegalArgumentException("atRevision 不得为 null");
    }
  }
}
