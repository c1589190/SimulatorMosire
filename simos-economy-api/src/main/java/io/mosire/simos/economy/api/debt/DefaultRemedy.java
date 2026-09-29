package io.mosire.simos.economy.api.debt;

/**
 * ★★ <b>违约救济</b>（E4a；理想架构 §2.7 的 {@code defaultRemedy}）。
 *
 * <p>★★ {@link #MARK_DEFAULTED} 是**默认 legacy 行为**：还不上的余额留在合同上、状态置 {@link
 * DebtStatus#DEFAULTED}，不核销、不转第三人、不没收库存（旧 {@code Debt.defaulted = true} 的逐值投影）。
 *
 * <p>★ 其余四档是留位：宽限/重组、按清算政策处置核心生产资料、把余额转给债权人、减免。 E5 的清算与 E4b 的减免分别接线；E4a 不实现，也不静默降级成默认档。
 */
public enum DefaultRemedy {
  /** 只标记违约：余额不动、不核销（默认 legacy 行为）。 */
  MARK_DEFAULTED,
  /** 宽限/重组（留位；E4b/E5 接线）。 */
  GRACE,
  /** 按清算政策处置核心生产资料（留位；E5 接线）。 */
  LIQUIDATE,
  /** 余额转给债权人（留位；E5 接线）。 */
  TRANSFER_TO_CREDITOR,
  /** 减免（留位；E4b 接线）。 */
  FORGIVE
}
