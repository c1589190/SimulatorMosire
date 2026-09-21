package io.mosire.simos.sd.channel;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.Objects;

/**
 * 一条决策请求（spec §十三.1，N17）：**只承载"actor 想做什么"**——落在哪个分支/版本、用哪条落点命令、载荷是什么。
 *
 * <p>★ **不含视图数据**（N17）：渠道拿不到全量视图；脱敏简报由 sd 侧按 actor 的 {@code viewScope} 构造（{@link
 * ChannelAdmission#redactedBrief}）。
 *
 * <p>★ {@code commandType} 只允许两条落点（R9）：{@code sd.IssueDirective} / {@code sd.SubmitVerdict}——由
 * {@link ChannelAdmission#requireLandingPoint} 在模块侧强制。
 */
public record DecisionRequest(
    BranchId branch, RevisionId expectedRevision, String commandType, String payloadJson) {

  public DecisionRequest {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(expectedRevision, "expectedRevision");
    if (commandType == null || commandType.isBlank()) {
      throw new IllegalArgumentException("commandType 不得为空白");
    }
    Objects.requireNonNull(payloadJson, "payloadJson");
  }
}
