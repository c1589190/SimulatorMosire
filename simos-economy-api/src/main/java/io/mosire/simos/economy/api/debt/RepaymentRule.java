package io.mosire.simos.economy.api.debt;

/**
 * ★★ <b>偿还规则</b>（E4a；理想架构 §2.7 的 {@code repaymentRule}）。
 *
 * <p>★★ {@link #AVAILABLE_SURPLUS_SHARE} 是**默认 legacy 行为**：关账日按债务人“可用粮 × 全局偿还比例” 的预算逐条还本（比例的唯一拼写点仍是
 * {@code EconomySettlement.DEBT_REPAYMENT_SHARE_PER_MILLE}）。 它只描述**规则种类**；每期应还比例/固定额等参数留给 E4b 的参数表与
 * terms 扩展 —— E4a 冻结的身份维是 “规则不同 ⇒ 合同不同”，不同比例目前还不会由状态产生。
 *
 * <p>★ 其余三档是留位：{@link #AT_DUE}（到期一次还）、{@link #FIXED_INSTALLMENT}（定额分期）、 {@link
 * #NONE}（只挂账、不主动偿还）。E4a 的结算侧只对默认档走既有的逐条偿还；非默认档不静默当成默认档。
 */
public enum RepaymentRule {
  /** 关账日以“可用余粮 × 全局比例”为预算逐条还本（默认 legacy 行为）。 */
  AVAILABLE_SURPLUS_SHARE,
  /** 到期一次还（留位；E4b+ 接线）。 */
  AT_DUE,
  /** 固定额分期（留位；E4b+ 接线）。 */
  FIXED_INSTALLMENT,
  /** 只挂账、不主动偿还（留位；E4b+ 接线）。 */
  NONE
}
