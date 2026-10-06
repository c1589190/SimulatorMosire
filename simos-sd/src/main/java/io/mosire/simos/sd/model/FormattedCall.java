package io.mosire.simos.sd.model;

import io.mosire.simos.util.spi.CommandTarget;
import java.util.List;
import java.util.Optional;

/**
 * 一条**格式化调用**（D2 决策包计划 §1）：决策人在拟稿期把一个**真实工具**与它的 JSON 参数、目标资源、预览 结果一起写进决策包——GM 看到的就是执行时会跑的那条调用。
 *
 * <p>★ {@code targets} 是**跨命名空间**的目标声明（{@link CommandTarget}）：目标校验在拟稿与执行两层都做。它进 JSON 持久化（{@code
 * SdCodec} 的 mapper），故本 record 的字段类型必须可往返——D2 已用 {@code /tmp} 冒烟覆盖。
 *
 * <p>★ {@code draftChecks} 是拟稿期的具名检查清单（本批为 {@code ["scope-ok","preview-ok"]}）；{@code mergedPlanId}
 * 只在 {@link CallStatus#MERGED} 时存在（D3）。
 *
 * <p>★ 保序不可变：{@code targets}/{@code draftChecks} 冻在赋值处。
 */
public record FormattedCall(
    int callIndex,
    String toolName,
    String argsJson,
    List<CommandTarget> targets,
    String previewJson,
    List<String> draftChecks,
    CallStatus status,
    Optional<String> mergedPlanId) {

  public FormattedCall {
    if (callIndex < 0) {
      throw new IllegalArgumentException("FormattedCall.callIndex 必须 ≥ 0: " + callIndex);
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("FormattedCall.toolName 不得为空白");
    }
    if (argsJson == null) {
      throw new IllegalArgumentException("FormattedCall.argsJson 不得为 null（无参数用 {}）");
    }
    if (targets == null) {
      throw new IllegalArgumentException("FormattedCall.targets 不得为 null（无目标用空表）");
    }
    if (previewJson == null) {
      throw new IllegalArgumentException("FormattedCall.previewJson 不得为 null（无预览用 {}）");
    }
    if (draftChecks == null) {
      throw new IllegalArgumentException("FormattedCall.draftChecks 不得为 null（无检查用空表）");
    }
    if (status == null) {
      throw new IllegalArgumentException("FormattedCall.status 不得为 null");
    }
    if (mergedPlanId == null) {
      throw new IllegalArgumentException(
          "FormattedCall.mergedPlanId 不得为 null（无合并用 Optional.empty()）");
    }
    for (CommandTarget target : targets) {
      if (target == null) {
        throw new IllegalArgumentException("FormattedCall.targets 不得含 null");
      }
    }
    for (String check : draftChecks) {
      if (check == null || check.isBlank()) {
        throw new IllegalArgumentException("FormattedCall.draftChecks 不得含空白");
      }
    }
    targets = List.copyOf(targets); // ★ 冻在赋值处（保序、不可变）
    draftChecks = List.copyOf(draftChecks);
    if (status == CallStatus.MERGED && mergedPlanId.isEmpty()) {
      throw new IllegalArgumentException("MERGED 的 FormattedCall 必须带 mergedPlanId");
    }
  }

  /** 仅换裁决状态的那一版（D2 的 APPROVE/DENY 用；不碰其余字段）。 */
  public FormattedCall withStatus(CallStatus newStatus) {
    if (newStatus == null) {
      throw new IllegalArgumentException("newStatus 不得为 null");
    }
    return new FormattedCall(
        callIndex, toolName, argsJson, targets, previewJson, draftChecks, newStatus, mergedPlanId);
  }

  /** 仅换合并计划引用的那一版（D3 用）。 */
  public FormattedCall withMergedPlanId(Optional<String> newMergedPlanId) {
    if (newMergedPlanId == null) {
      throw new IllegalArgumentException("newMergedPlanId 不得为 null");
    }
    return new FormattedCall(
        callIndex, toolName, argsJson, targets, previewJson, draftChecks, status, newMergedPlanId);
  }
}
