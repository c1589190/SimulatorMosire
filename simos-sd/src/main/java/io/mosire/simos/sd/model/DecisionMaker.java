package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 决策人（spec §三.2）：归属（Nation / Army）+ 窄工具白名单（N9）+ viewScope（R10 / N6）+ 决策周期（N5）。
 *
 * <p>★ **决策周期与命令延迟分开**（N5）：{@code decisionCadenceTicks} 是"多久能下一次决心"；命令延迟（决定→执行开始）是 **独立通道**（v1 落在
 * {@code Effect.readyAtTick}，见 spec §〇.3）。
 */
public record DecisionMaker(
    DecisionMakerId id,
    Affiliation affiliation,
    Set<String> allowedTools,
    ViewScope viewScope,
    long decisionCadenceTicks) {

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
