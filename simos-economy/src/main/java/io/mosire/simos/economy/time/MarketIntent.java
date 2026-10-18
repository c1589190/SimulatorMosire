package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;
import java.util.Optional;

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
 * ★★★ <b>P3.0：M3 预期编排交给 M4 统一市场的唯一意向形状。</b>
 *
 * <p>它只活在一次 tick 内：不落 {@code EconomyData}、不进 ChangeSet/Codec。P3.0 只把意向转换成既有 {@link
 * io.mosire.simos.economy.api.market.BuyOrder}/{@link io.mosire.simos.economy.api.market.SellOrder}
 * 或整块 {@link LandOrder}，追加到同一个 {@link MarketSettlement.MarketRound}；订单排序、共享预算优先级与土地部分成交口径留给 P3c。
 *
 * <p>★ <b>fail-closed 形状</b>：商品意向必须带 commodity 且不带 landShare，土地意向反之；数量必须为正、限价/预算/优先级不得为负。
 */
record MarketIntent(
    MarketIntentKind kind,
    MarketIntentSide side,
    ActorRef actor,
    HexCoord location,
    Optional<CommodityId> commodity,
    Optional<AssetShareId> landShare,
    long quantity,
    long limitPrice,
    CurrencyId payWith,
    long budgetMilli,
    int priority,
    Optional<ProductionUnitId> sourceUnit) {

  MarketIntent {
    Objects.requireNonNull(kind, "MarketIntent.kind 不得为 null");
    Objects.requireNonNull(side, "MarketIntent.side 不得为 null");
    Objects.requireNonNull(actor, "MarketIntent.actor 不得为 null");
    Objects.requireNonNull(location, "MarketIntent.location 不得为 null");
    Objects.requireNonNull(commodity, "MarketIntent.commodity 不得为 null（没有商品请用 Optional.empty()）");
    Objects.requireNonNull(landShare, "MarketIntent.landShare 不得为 null（没有土地请用 Optional.empty()）");
    Objects.requireNonNull(payWith, "MarketIntent.payWith 不得为 null");
    Objects.requireNonNull(sourceUnit, "MarketIntent.sourceUnit 不得为 null（没有来源请用 Optional.empty()）");
    if (quantity <= 0L) {
      throw new IllegalArgumentException("MarketIntent.quantity 必须 > 0: " + quantity);
    }
    if (limitPrice < 0L) {
      throw new IllegalArgumentException("MarketIntent.limitPrice 不得为负: " + limitPrice);
    }
    if (budgetMilli < 0L) {
      throw new IllegalArgumentException("MarketIntent.budgetMilli 不得为负: " + budgetMilli);
    }
    if (priority < 0) {
      throw new IllegalArgumentException("MarketIntent.priority 不得为负: " + priority);
    }
    switch (kind) {
      case COMMODITY -> {
        if (commodity.isEmpty()) {
          throw new IllegalArgumentException("COMMODITY MarketIntent 必须带 commodity");
        }
        if (landShare.isPresent()) {
          throw new IllegalArgumentException("COMMODITY MarketIntent 不得带 landShare");
        }
      }
      case LAND -> {
        if (landShare.isEmpty()) {
          throw new IllegalArgumentException("LAND MarketIntent 必须带 landShare");
        }
        if (commodity.isPresent()) {
          throw new IllegalArgumentException("LAND MarketIntent 不得带 commodity");
        }
      }
    }
  }
}
