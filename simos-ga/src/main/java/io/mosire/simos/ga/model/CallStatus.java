package io.mosire.simos.ga.model;

/**
 * 单条决策包条目的<b>只读展示状态</b>（设计书 §5.4）。
 *
 * <p>精确裁决来自 AgentLib {@code Decision} / {@code DecisionItemVerdict}；本枚举不得作为写状态。
 */
public enum CallStatus {
  PENDING,
  APPROVED,
  REJECTED,
  MERGED
}
