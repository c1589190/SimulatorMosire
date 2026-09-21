package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 决策人（spec §三.2）：归属（Nation / Army）+ 窄工具白名单（N9）+ viewScope（R10 / N6）+ 决策周期（N5）+ LLM provider
 * 引用（M11）。
 *
 * <p>★ **决策周期与命令延迟分开**（N5）：{@code decisionCadenceTicks} 是"多久能下一次决心"；命令延迟（决定→执行开始）是 **独立通道**（v1 落在
 * {@code Effect.readyAtTick}，见 spec §〇.3）。
 *
 * <p>★ **{@code providerId} 只是基础设施引用**（M11 spec §1.1）：它指向 app 层 LLM provider 注册表里的一条记录， sd
 * **不解释、不校验其存在性**（注册表在 app，sd 看不见——铁律 3 的结构化）。存在性在**使用期**由 {@code LlmProviderResolver} 强制（解析不到 ⇒
 * 明确报错，不静默兜底）。空 {@link Optional} = 未绑定。
 */
public record DecisionMaker(
    DecisionMakerId id,
    Affiliation affiliation,
    Set<String> allowedTools,
    ViewScope viewScope,
    long decisionCadenceTicks,
    Optional<String> providerId) {

  /**
   * 5 参重载：不绑定 provider（{@code Optional.empty()}）。保住既有构造点，且让"未绑定"是**显式**的。
   *
   * <p>★ 只应被**创建**路径使用；**重建**既有决策人的处理器（如 {@code SetViewScopeHandler}）必须传第 6 参 {@code
   * existing.providerId()}，否则会静默丢绑定（M11 变异靶子）。
   */
  public DecisionMaker(
      DecisionMakerId id,
      Affiliation affiliation,
      Set<String> allowedTools,
      ViewScope viewScope,
      long decisionCadenceTicks) {
    this(id, affiliation, allowedTools, viewScope, decisionCadenceTicks, Optional.empty());
  }

  public DecisionMaker {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (affiliation == null) {
      throw new IllegalArgumentException("affiliation 不得为 null");
    }
    if (viewScope == null) {
      throw new IllegalArgumentException("viewScope 不得为 null");
    }
    if (decisionCadenceTicks < 1) {
      throw new IllegalArgumentException("decisionCadenceTicks 必须 ≥ 1: " + decisionCadenceTicks);
    }
    if (allowedTools == null) {
      throw new IllegalArgumentException("allowedTools 不得为 null");
    }
    if (providerId == null) {
      throw new IllegalArgumentException("providerId 不得为 null（未绑定用 Optional.empty()）");
    }
    if (providerId.isPresent() && providerId.get().isBlank()) {
      throw new IllegalArgumentException("providerId 不得为空白（未绑定用 Optional.empty()）");
    }
    Set<String> tools = new LinkedHashSet<>();
    for (String tool : allowedTools) {
      if (tool == null || tool.isBlank()) {
        throw new IllegalArgumentException("allowedTools 不得含空白");
      }
      tools.add(tool);
    }
    allowedTools = Collections.unmodifiableSet(tools); // ★ 冻在赋值处
  }
}
