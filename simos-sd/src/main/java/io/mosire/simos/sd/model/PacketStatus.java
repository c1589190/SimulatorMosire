package io.mosire.simos.sd.model;

/**
 * 决策包状态（D2 决策包计划 §1）：一决策人 × 一 tick 一个 packet 的生命周期。
 *
 * <p>取值语义：
 *
 * <ul>
 *   <li>{@link #DRAFT}——拟稿中（propose/intent 可追加/改写）；
 *   <li>{@link #PENDING}——已提交待 GM 裁决；
 *   <li>{@link #APPROVED}——整包批准（逐 call 以各自状态为准）；
 *   <li>{@link #REJECTED}——整包拒绝；
 *   <li>{@link #PARTIALLY_APPROVED}——部分 call 批准（D2 的 GM 可给 {@code callIndexes}）；
 *   <li>{@link #MERGED}——走合并效果集（D3）。
 * </ul>
 *
 * <p>★ 逐 call 的真值在 {@link CallStatus}；包级状态是**汇总口径**（无 callIndexes 的全部批准 ⇒ {@code APPROVED}）。
 */
public enum PacketStatus {
  DRAFT,
  PENDING,
  APPROVED,
  REJECTED,
  MERGED,
  PARTIALLY_APPROVED
}
