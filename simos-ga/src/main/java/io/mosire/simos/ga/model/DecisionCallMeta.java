package io.mosire.simos.ga.model;

import io.mosire.simos.util.spi.CommandTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 决策包条目的 Simos 领域扩展（设计书 §5.2）。
 *
 * <p>AgentLib 的 {@code PackageItem} 只承载 {@code index/toolName/args/argsDigest/summary}；本 record 只补
 * AgentLib 装不下的宿主展示与执行信息。{@code toolName/args} 的权威来源永远是 {@link
 * io.mosire.agentlib.decision.DecisionPackage#items()}，这里<b>不复制</b>。
 *
 * @param targets 该调用声明的跨命名空间目标（保序不可变）
 * @param previewJson 真预览 JSON 文本
 * @param draftChecks 拟稿期检查清单（保序不可变）
 * @param mergedPlanId 领域合并计划引用；空 = 未并入
 * @param outcomeJson 执行/批结果摘要；空 = 未执行
 */
public record DecisionCallMeta(
    List<CommandTarget> targets,
    String previewJson,
    List<String> draftChecks,
    Optional<String> mergedPlanId,
    Optional<String> outcomeJson) {

  public DecisionCallMeta {
    Objects.requireNonNull(targets, "targets");
    List<CommandTarget> targetCopy = new ArrayList<>(targets.size());
    for (CommandTarget target : targets) {
      targetCopy.add(Objects.requireNonNull(target, "targets 不得含 null"));
    }
    targets = Collections.unmodifiableList(targetCopy);

    Objects.requireNonNull(previewJson, "previewJson");

    Objects.requireNonNull(draftChecks, "draftChecks");
    List<String> checkCopy = new ArrayList<>(draftChecks.size());
    for (String check : draftChecks) {
      if (check == null || check.isBlank()) {
        throw new IllegalArgumentException("draftChecks 不得含空白");
      }
      checkCopy.add(check);
    }
    draftChecks = Collections.unmodifiableList(checkCopy);

    Objects.requireNonNull(mergedPlanId, "mergedPlanId");
    Objects.requireNonNull(outcomeJson, "outcomeJson");
  }
}
