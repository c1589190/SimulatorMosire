package io.mosire.simos.economy.api.debt;

/**
 * ★★ <b>货币折偿条款</b>（E4a；理想架构 §2.7 的 {@code monetaryConversion}）。
 *
 * <p>★★ {@link #NOT_ALLOWED} 是**默认 legacy 行为**：没有有效价格/约定价格时不得把实物债硬折成钱 （设计 §5.3 的“不硬折”）。E4a
 * 没有任何折偿路径；枚举先固定身份维，E4b 再把价格口径与币种接进来 （届时可把本枚举扩成带 {@code CurrencyId} 的形状，或用显式条款记录承载）。
 *
 * <p>★ 两档带价格来源的留位：{@link #AT_AGREED_PRICE}（合同价）、{@link #AT_MARKET_PRICE} （市场价）。E4a
 * 的结算侧对非默认档不静默当成默认档。
 */
public enum MonetaryConversion {
  /** 不允许货币折偿（默认 legacy 行为：无有效价格不折）。 */
  NOT_ALLOWED,
  /** 允许按合同约定价折偿（留位；E4b+ 接线）。 */
  AT_AGREED_PRICE,
  /** 允许按市场有效价折偿（留位；E4b+ 接线）。 */
  AT_MARKET_PRICE
}
