package io.mosire.simos.sd.model;

/**
 * 单条 {@link FormattedCall} 的状态（D2 决策包计划 §1）。
 *
 * <ul>
 *   <li>{@link #PENDING}——待 GM 裁决；
 *   <li>{@link #APPROVED}——批准（D3 由执行器落盘）；
 *   <li>{@link #REJECTED}——拒绝；
 *   <li>{@link #MERGED}——并入合并效果集（D3；必须带 {@code mergedPlanId}）。
 * </ul>
 */
public enum CallStatus {
  PENDING,
  APPROVED,
  REJECTED,
  MERGED
}
