package io.mosire.simos.ga.model;

/**
 * 决策包<b>只读展示状态</b>（设计书 §5.4）。
 *
 * <p>精确状态只在 AgentLib {@code PackageState} / {@code DecisionRecord.packageState()}；本枚举不得作为写状态。
 *
 * <ul>
 *   <li>{@link #DRAFT}——旧测试/旧线格式留痕；新的 {@code PROPOSED} 包视图统一显示 {@link #PENDING}；
 *   <li>{@link #PENDING}——{@code PROPOSED} 或 {@code DECIDING}；
 *   <li>{@link #APPROVED}——被批子集覆盖全部条目；
 *   <li>{@link #PARTIALLY_APPROVED}——只批了部分条目；
 *   <li>{@link #REJECTED}——全驳 / {@code CONFLICTED} / {@code EXPIRED} 的展示口径；
 *   <li>{@link #MERGED}——领域 {@code mergedPlanId} 存在。
 * </ul>
 */
public enum PacketStatus {
  DRAFT,
  PENDING,
  APPROVED,
  REJECTED,
  MERGED,
  PARTIALLY_APPROVED
}
