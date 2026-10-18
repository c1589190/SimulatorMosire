package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★★ <b>P2f：一张土地订单（整块、定向到具体 {@link AssetShareId}）</b>。
 *
 * <p>★★ <b>它取代了什么</b>：P2f 之前商品订单/撮合/冻结/结算全部以 {@link io.mosire.simos.economy.api.id.CommodityId}
 * 为键，土地没有订单形状，也不可能进入同一个市场轮。本记录让土地成为<b>同一本订单簿里的另一种订单</b> —— 它仍由 {@code
 * MarketSettlement.clearOncePerCycle} 的<b>唯一</b>统一撮合入口 {@code MarketMatcher.matchAll} 处理（土地是入口内部
 * 的 package-private 分支 {@link MarketLandMatcher#match}），没有第二个市场阶段、没有第二个 public matcher。
 *
 * <pre>
 * side              BUY / SELL
 * shareId           具体土地块（P2f 的最小口径 = 逐块定向；不做"按 hex 汇总的匿名需求"）
 * actor             买方或卖方主体（现金腿要求它能解析到家户）
 * location          该块所在格（= 份额的 ProductionLocation，用于定位市场区；必须与份额一致）
 * quantityMilliMu   整块数量（千分亩）—— P2f 只支持整块：必须 == 该份额的 quantity
 * limitPricePerMu   限价（毫计价货币 / 1 个整亩）：BUY = 最高愿付；SELL = 最低愿收
 * payWith           买方支付币 / 卖方收款币（同一笔必须相等：钱腿只铸买方支付币）
 * maxBudget         买方的硬预算上限（毫；土地订单只能用商品结算后的剩余可花额）
 * </pre>
 *
 * <p>★★ <b>不进入 HouseholdGoods</b>：土地是 {@code AssetKind.LAND} 产权份额，成交只改 {@code owner/operator}（唯一写口
 * {@link OwnershipStakeBook#transferLandBlock}），不搬任何商品库存、不生成 Pledge、无运费、P2 不征土地税。
 *
 * <p>★★ <b>稳定排序键</b>（{@link #stableKey()}）：内容的纯函数 —— 同一条订单在任意次运行/任意线程数下给出同一个串（禁 HashMap
 * 迭代序/时钟/随机数）。它只用于撮合的稳定破平与日志，不是新的身份类型。
 *
 * @param side 买/卖
 * @param shareId 具体土地块（P2f 不支持按 hex 的匿名需求）
 * @param actor 挂单主体
 * @param location 该块所在格（必须与份额一致）
 * @param quantityMilliMu 整块数量（千分亩；必须 == 份额数量）
 * @param limitPricePerMu 限价（毫计价货币 / 整亩；≥ 0）
 * @param payWith 支付/收款币
 * @param maxBudget 买方硬预算上限（毫支付币；≥ 0；SELL 忽略）
 */
record LandOrder(
    Side side,
    AssetShareId shareId,
    ActorRef actor,
    HexCoord location,
    long quantityMilliMu,
    long limitPricePerMu,
    CurrencyId payWith,
    long maxBudget) {

  /** 订单方向（土地只有买/卖两向；P2f 不做部分成交）。 */
  enum Side {
    BUY,
    SELL
  }

  LandOrder {
    Objects.requireNonNull(side, "LandOrder.side 不得为 null");
    Objects.requireNonNull(shareId, "LandOrder.shareId 不得为 null");
    Objects.requireNonNull(actor, "LandOrder.actor 不得为 null");
    Objects.requireNonNull(location, "LandOrder.location 不得为 null");
    Objects.requireNonNull(payWith, "LandOrder.payWith 不得为 null");
    if (quantityMilliMu <= 0L) {
      throw new IllegalArgumentException(
          "LandOrder.quantityMilliMu 必须 > 0（整块）: " + quantityMilliMu);
    }
    if (limitPricePerMu < 0L) {
      throw new IllegalArgumentException("LandOrder.limitPricePerMu 不得为负: " + limitPricePerMu);
    }
    if (maxBudget < 0L) {
      throw new IllegalArgumentException("LandOrder.maxBudget 不得为负: " + maxBudget);
    }
  }

  /**
   * ★ 稳定性破平/日志用的 canonical 串（内容的纯函数；不进任何判定，只回答"这是同一张订单"）。
   *
   * <p>★ 它刻意包含全部字段：同一块地、同一主体的两张"同形订单"仍会给出同一个串 —— 那正是需要的语义（重复挂单应当在日志里 一眼看出是重复），而不是造一个随时间漂移的订单 id。
   */
  String stableKey() {
    return side.name()
        + "|"
        + shareId.value()
        + "|"
        + actor.kind()
        + ":"
        + actor.id()
        + "|"
        + location.q()
        + ","
        + location.r()
        + "|"
        + quantityMilliMu
        + "|"
        + limitPricePerMu
        + "|"
        + payWith.value()
        + "|"
        + maxBudget;
  }
}
