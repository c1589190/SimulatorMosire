package io.mosire.simos.economy.api.fx;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>一笔外汇委托（FxOrder）</b>：阶段 2-A2a 的对外契约（约束设计书 §3.2）。它是<b>币对标的</b>的订单 —— 现货订单的标的 是 {@code
 * CommodityId}，外汇订单的标的是 {@code (base, quote)} 这一对币种。
 *
 * <pre>
 * FxOrder(owner, hex, side, base, quote, limitPrice, quantity)
 *   BUY  = 买入 base、付出 quote（limitPrice = 愿付<b>上限</b>）
 *   SELL = 卖出 base、收进 quote（limitPrice = 愿收<b>下限</b>）
 *   quantity  = base 的数量（最小单位）
 *   限价单位  = per-mille：每 1000 个 base 最小单位应付/应收多少个 quote 最小单位
 * </pre>
 *
 * <p>★★ <b>★ 汇率 = 成交价（I17）</b>：世界上<b>不存</b>"1 金 = 12 银"这种全局标量。本类型只是"某户在某格挂的一笔委托"，
 * 它<b>不进任何持久状态</b>（逐轮瞬态、由 {@code MarketSettlement} 的市场轮现算现撮合）；汇率是<b>成交的结果</b>， 不是状态里的一行。
 *
 * <p>★★ <b>★ 限价的量纲为什么是 per-mille（而不是"quote 最小单位 per base 单位"）</b>（一处如实的口径选择）：
 *
 * <ul>
 *   <li>约束设计书 §3.2 写的是"成交价 = quote per base（定点整数，最小单位）"，§3.3 的官方汇率写的是 {@code buyPerMille /
 *       sellPerMille}（微刻度定点）。两者若各按字面实现，在 <b>base 与 quote 精度不同</b>的世界里会 出现两套量纲（"per base 单位"要除以
 *       {@code 10^baseScale}，"per-mille"不用）⇒ 同一件事两个拼写；
 *   <li>本批取<b>一套量纲</b>：{@code limitPrice} = <b>每 1000 个 base 最小单位应付的 quote 最小单位数</b>（{@code
 *       perMille}）。于是 {@code 1000} = 两种钱"最小单位 1:1"，且 §3.4 的报价 {@code bidP = officialBuy} / {@code
 *       askP = officialSell} 是<b>直接赋值</b>（不需要任何换算）；
 *   <li>A 阶段两个币种的 {@code scale} 都是 3 ⇒ 三种读法（per-mille / per base 单位 / per base 最小单位 ×1000）
 *       在数值上逐值相同，本选择不改变 A 阶段的任何读数。
 * </ul>
 *
 * <p>★ <b>不变量（构造期判死，fail-closed）</b>：{@code base != quote}（同币对没有汇率可言）、{@code limitPrice > 0}、
 * {@code quantity > 0}、{@code owner}/{@code hex} 非 null。
 *
 * @param owner 委托主体（家户或政府国库；两端恒为 actor —— 与 {@code Transfer} 同制）
 * @param hex 委托所在格（决定它属于哪个市场区、以及"本地法定币"是哪种）
 * @param side 方向（见 {@link FxSide}）
 * @param base 标的币（买它 / 卖它）
 * @param quote 计价币（付出它 / 收进它）
 * @param limitPrice 限价（per-mille：每 1000 个 base 最小单位应付/应收的 quote 最小单位数；&gt; 0）
 * @param quantity base 的数量（最小单位；&gt; 0）
 */
public record FxOrder(
    ActorRef owner,
    HexCoord hex,
    FxSide side,
    CurrencyId base,
    CurrencyId quote,
    long limitPrice,
    long quantity) {

  public FxOrder {
    Objects.requireNonNull(owner, "FxOrder.owner 不得为 null（两端恒为 actor）");
    Objects.requireNonNull(hex, "FxOrder.hex 不得为 null（委托属某一格市场）");
    Objects.requireNonNull(side, "FxOrder.side 不得为 null（方向必须显式）");
    Objects.requireNonNull(base, "FxOrder.base 不得为 null");
    Objects.requireNonNull(quote, "FxOrder.quote 不得为 null");
    if (base.equals(quote)) {
      throw new IllegalArgumentException(
          "FxOrder 的 base 与 quote 不得是同一种钱（同币对没有汇率可言）: " + base.value());
    }
    if (limitPrice <= 0L) {
      throw new IllegalArgumentException(
          "FxOrder.limitPrice 必须 > 0（per-mille：每 1000 个 base 最小单位应付的 quote 最小单位数）: " + limitPrice);
    }
    if (quantity <= 0L) {
      throw new IllegalArgumentException("FxOrder.quantity 必须 > 0（base 最小单位）: " + quantity);
    }
  }

  /** 本委托的币对键（与 {@link OfficialRate#key()} 同一拼写：{@code base|quote}）。 */
  public String pairKey() {
    return OfficialRate.keyOf(base, quote);
  }
}
