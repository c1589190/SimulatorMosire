package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.id.CurrencyId;

/**
 * ★★ <b>一张买订单的独立预算</b>（M2.1）：这张订单<b>最多能动用多少钱</b>（{@link #amountMilli()}，最小币值）以及<b>动的是哪一种钱</b>
 * （{@link #currency()}）。
 *
 * <p>★★ <b>为什么预算必须与数量分开</b>（用户 2026-09-27 裁定）：需求是"我缺多少货"，预算是"我付得起多少" —— 两者是两个独立的事实。把 {@code
 * quantity} 当信用额度（"要 100 就代表能付 100 的价"）会让"买不起的缺口"变成一笔 付不出的承诺；到了结算那一步才炸，而账面在此之前看不出任何异常。 ⇒ {@link
 * BuyOrder#quantity()} 与 {@link #amountMilli()} <b>各算各的</b>，撮合时再取"两者都满足"的那一部分。
 *
 * <p>★★ <b>3c（2026-10-10 订单可选币）：预算的币 = 订单的支付币</b> —— 它从 M1.1 的 {@code InstrumentId}（哪一种钱"工具"） 收窄为
 * {@link CurrencyId}（币种）：钱在账上是<b>按币种记的</b>（{@code HouseholdInventory.money} / {@code
 * Transfer.money} / 结算的钱腿 {@code Map.of(buy.currency, payment)} 全是 {@code
 * CurrencyId}），而工具那一维今天<b>没有任何余额表</b>读它。 ⇒ 订单路径只留<b>一处币种真值</b>：{@link
 * BuyOrder#payWith()}，本字段是它在预算上的同一事实（构造期判两者逐值相等，不许两处各写一份）。 ★ 工具维（{@code InstrumentId}）仍是 {@code
 * EconomyData.moneyInstruments} 的稳定身份（铸熔/兑现属 M4+），只是不再经订单携带。
 *
 * <p>★ <b>它只描述"可动用"，不占用任何账</b>：真正的扣款仍走唯一写口（{@code EconomySettlement.applyTransfer}）；本类型不冻结、不预留、不落账。
 *
 * @param amountMilli 可动用的金额（最小币值；{@code ≥ 0}；0 = 这张订单买不到任何东西）
 * @param currency 可动用的币种；不得为 null（说不出是哪种钱就不是一笔预算）
 */
public record Budget(long amountMilli, CurrencyId currency) {

  public Budget {
    if (amountMilli < 0L) {
      throw new IllegalArgumentException("Budget.amountMilli 不得为负（预算不是负债）: " + amountMilli);
    }
    if (currency == null) {
      throw new IllegalArgumentException("Budget.currency 不得为 null（说不出是哪种钱就不是一笔预算）");
    }
  }
}
