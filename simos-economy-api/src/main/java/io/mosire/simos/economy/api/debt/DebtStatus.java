package io.mosire.simos.economy.api.debt;

/**
 * ★★ <b>债务合同的状态</b>（E4a；理想架构 §2.7 的词表）。
 *
 * <p>本状态只由**债务路径**写：借入把 {@code SETTLED} 重新激活为 {@code NORMAL}，计息/持债不改变状态； 违约处置（E4a
 * 只在经营者退出这一步）把还不上的合同置 {@link #DEFAULTED}，还清的置 {@link #SETTLED}。 {@link #DELINQUENT} 与 {@link
 * #FORGIVEN} 是枚举位，E4a 没有产生它们的路径（减免属 E4b，逾期判定属 E5）， 但状态类型先固定下来，避免后来者用 {@code boolean defaulted}
 * 再拼一份。
 *
 * <p>★ <b>旧字段的投影</b>：旧 {@code Debt.defaulted} 的两档在本批逐值映射为 {@code true → DEFAULTED}、{@code false →
 * NORMAL/SETTLED}；读口仍发 {@code defaulted} 布尔值 （{@code status == DEFAULTED}），旧读数的形状不变。
 */
public enum DebtStatus {
  /** 正常存续（含本金为 0 但未结清的过渡态？不：本金 0 且未违约一律记 SETTLED，见下）。 */
  NORMAL,
  /** 已逾期、尚未进入违约救济（E4a 无产生路径；E5 的到期判定接入）。 */
  DELINQUENT,
  /** 已违约：还不上的余额挂在合同上，只记状态、不核销本金。 */
  DEFAULTED,
  /** 已结清：本金 0 且未被违约标记（历史事实留在表里）。 */
  SETTLED,
  /** 已减免（E4b 的减免路径接入；E4a 无产生路径）。 */
  FORGIVEN
}
