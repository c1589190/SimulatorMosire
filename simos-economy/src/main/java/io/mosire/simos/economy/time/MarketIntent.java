package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** 市场意向方向（P3.0 冻结；只表达买/卖，不表达价格形成规则）。 */
enum MarketIntentSide {
  BUY,
  SELL
}

/** 市场意向种类（P3.0 冻结）：商品与土地共用同一条转换入口、同一个市场轮。 */
enum MarketIntentKind {
  COMMODITY,
  LAND
}

/**
 * ★★★ <b>D-032 RA1b2c2：M3 预期编排交给 M4 统一市场的唯一意向形状（LAND 已标量化）。</b>
 *
 * <p>它只活在一次 tick 内：不落 {@code EconomyData}、不进 ChangeSet/Codec。商品意向转换成既有 {@link
 * io.mosire.simos.economy.api.market.BuyOrder}/{@link
 * io.mosire.simos.economy.api.market.SellOrder}；土地意向转换成一 张标量 {@link LandOrder}，两者都追加到同一个 {@link
 * MarketSettlement.MarketRound}，由同一个 {@code MarketMatcher.matchAll} 撮合。
 *
 * <p>★★ <b>LAND 不再引用任何 {@code AssetShareId} 整块</b>：{@code location} 就是土地所在的 hex，{@code quantity} 就是
 * 千分亩数量。商品与土地仍共用同一字段族，靠 {@link #kind} 区分语义。
 *
 * <p>★★★ <b>D-032 RF1：BUY 是数量型价格接受者</b> —— {@link #limitPrice()} 只属于 SELL（卖方最低价）；BUY 必须为 {@link
 * OptionalLong#empty()}，因此“需求挂价/预算上限”在本形状里<b>不可表达</b>。需求数量只由真实缺口决定； 现金不足由成交时的既有普通货币债路径处理。
 *
 * <p>★ <b>fail-closed 形状</b>：商品意向必须带 commodity；土地意向必须不带 commodity；数量必须为正、优先级不得为负； SELL 必须带非负限价，BUY
 * 必须不带限价。
 */
record MarketIntent(
    MarketIntentKind kind,
    MarketIntentSide side,
    ActorRef actor,
    HexCoord location,
    Optional<CommodityId> commodity,
    long quantity,
    OptionalLong limitPrice,
    CurrencyId payWith,
    int priority,
    Optional<ProductionUnitId> sourceUnit) {

  MarketIntent {
    Objects.requireNonNull(kind, "MarketIntent.kind 不得为 null");
    Objects.requireNonNull(side, "MarketIntent.side 不得为 null");
    Objects.requireNonNull(actor, "MarketIntent.actor 不得为 null");
    Objects.requireNonNull(location, "MarketIntent.location 不得为 null");
    Objects.requireNonNull(commodity, "MarketIntent.commodity 不得为 null（没有商品请用 Optional.empty()）");
    Objects.requireNonNull(payWith, "MarketIntent.payWith 不得为 null");
    Objects.requireNonNull(
        limitPrice, "MarketIntent.limitPrice 不得为 null（买盘请用 OptionalLong.empty()）");
    Objects.requireNonNull(sourceUnit, "MarketIntent.sourceUnit 不得为 null（没有来源请用 Optional.empty()）");
    if (quantity <= 0L) {
      throw new IllegalArgumentException("MarketIntent.quantity 必须 > 0: " + quantity);
    }
    if (limitPrice.isPresent() && limitPrice.getAsLong() < 0L) {
      throw new IllegalArgumentException("MarketIntent.limitPrice 不得为负: " + limitPrice.getAsLong());
    }
    if (priority < 0) {
      throw new IllegalArgumentException("MarketIntent.priority 不得为负: " + priority);
    }
    if (side == MarketIntentSide.BUY && limitPrice.isPresent()) {
      throw new IllegalArgumentException(
          "BUY MarketIntent 是价格接受者，不得带限价（需求数量只由真实缺口决定）: " + limitPrice.getAsLong());
    }
    if (side == MarketIntentSide.SELL && limitPrice.isEmpty()) {
      throw new IllegalArgumentException("SELL MarketIntent 必须带最低价（0 = 明确免费交易）");
    }
    switch (kind) {
      case COMMODITY -> {
        if (commodity.isEmpty()) {
          throw new IllegalArgumentException("COMMODITY MarketIntent 必须带 commodity");
        }
      }
      case LAND -> {
        if (commodity.isPresent()) {
          throw new IllegalArgumentException("LAND MarketIntent 不得带 commodity（hex 即 location）");
        }
      }
    }
  }
}
