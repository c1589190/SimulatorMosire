package io.mosire.simos.economy.api.debt;

/**
 * ★★ <b>计息时点</b>（E4a；理想架构 §2.7 的 {@code interestTiming}）。
 *
 * <p>★★ {@link #AFTER_REPAYMENT_ON_CLOSE} 是**默认 legacy 行为**，也是 E4a 唯一接线的档：产业关账日、
 * 偿还之后、以**当日起始本金**为基数计息并并入本金（复利）。它与旧 {@code 旧结算引擎（R3a 已删除）.chargeInterest} 的语义逐值相同。
 *
 * <p>★ 另外两档是**留位**（E4b+ 才接线）：{@link #BEFORE_REPAYMENT_ON_CLOSE} 表示关账日先计息再偿还； {@link #DAILY}
 * 表示按日计息。E4a 的结算侧对非默认档 **fail-closed 具名抛**，不静默当成默认档 —— “看起来在记、其实不生效”是本仓明确禁止的形态。
 */
public enum InterestTiming {
  /** 关账日、偿还后计息并入本金（默认 legacy 行为，E4a 唯一接线档）。 */
  AFTER_REPAYMENT_ON_CLOSE,
  /** 关账日、偿还前计息（留位；E4b+ 接线）。 */
  BEFORE_REPAYMENT_ON_CLOSE,
  /** 按日计息（留位；E4b+ 接线）。 */
  DAILY
}
