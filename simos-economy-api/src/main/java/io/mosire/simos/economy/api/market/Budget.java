package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;

/**
 * ★★ <b>一张买订单的独立预算</b>（M2.1）：这张订单<b>最多能动用多少钱</b>（{@link #amountMilli()}，最小币值）以及<b>动的是哪一种钱</b>
 * （{@link #instrument()}）。
 *
 * <p>★★ <b>为什么预算必须与数量分开</b>（用户 2026-09-27 裁定）：需求是"我缺多少货"，预算是"我付得起多少" —— 两者是两个独立的事实。把 {@code
 * quantity} 当信用额度（"要 100 就代表能付 100 的价"）会让"买不起的缺口"变成一笔 付不出的承诺；到了结算那一步才炸，而账面在此之前看不出任何异常。 ⇒ {@link
 * BuyOrder#quantity()} 与 {@link #amountMilli()} <b>各算各的</b>，撮合时再取"两者都满足"的那一部分。
 *
 * <p>★ <b>它为什么带 {@link InstrumentId}</b>：币种（{@code CurrencyId}，如 {@code silver}）只回答"这是银"；工具 （{@link
 * InstrumentId}，如银币）才回答"这是哪一种银"。M1.1 起两者是两个命名空间，而订单要能指名<b>用哪种工具支付</b> ⇒ 预算必须带上工具那一维。 {@link
 * BuyOrder#payWith()} 是同一事实在订单上的显式一栏（构造期判两者逐值相等，不许两处各写一份）。
 *
 * <p>★ <b>它只描述"可动用"，不占用任何账</b>：真正的扣款仍走 {@code 旧结算引擎（R3a 已删除）.applyTransfer}（唯一写口）；本类型不冻结、不预留、不落账。
 *
 * @param amountMilli 可动用的金额（最小币值；{@code ≥ 0}；0 = 这张订单买不到任何东西）
 * @param instrument 可动用的货币工具；不得为 null（说不出是哪种钱就不是一笔预算）
 */
public record Budget(long amountMilli, InstrumentId instrument) {

  public Budget {
    if (amountMilli < 0L) {
      throw new IllegalArgumentException("Budget.amountMilli 不得为负（预算不是负债）: " + amountMilli);
    }
    if (instrument == null) {
      throw new IllegalArgumentException("Budget.instrument 不得为 null（说不出是哪种钱就不是一笔预算）");
    }
  }

  /** ★ 本世界唯一在用的货币工具（银币）的预算 —— 生产侧生成订单时的唯一入口，避免各处再拼一次 {@code "silver-specie"}。 */
  public static Budget silver(long amountMilli) {
    return new Budget(amountMilli, MoneyVocabulary.SILVER_SPECIE.id());
  }
}
