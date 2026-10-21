package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C7b1：一条跨 hex 运输需求的稳定复合键</b>。
 *
 * <p>键 = {@code (marketZone, shippingHex, arrivalHex, commodity)}。{@code shippingHex} 只能来自 商品卖单的
 * {@code dispatchFrom}；不得用承运户的 {@code homeHex} 反推。同 hex 需求在派生阶段就被排除。
 *
 * <p>本类型只活在 {@code SettlementDayContext.transportRoundPlan} 内的瞬态计划中：不落 {@code EconomyData}、不进
 * {@code ChangeSet}、不进 Codec。
 *
 * @param marketZone 需求所属市场区；不得为 null
 * @param shippingHex 发货格；不得为 null
 * @param arrivalHex 到货格；不得为 null
 * @param commodity 商品；不得为 null
 */
public record TransportDemandKey(
    MarketZoneId marketZone, HexCoord shippingHex, HexCoord arrivalHex, CommodityId commodity) {

  public TransportDemandKey {
    Objects.requireNonNull(marketZone, "TransportDemandKey.marketZone 不得为 null");
    Objects.requireNonNull(shippingHex, "TransportDemandKey.shippingHex 不得为 null");
    Objects.requireNonNull(arrivalHex, "TransportDemandKey.arrivalHex 不得为 null");
    Objects.requireNonNull(commodity, "TransportDemandKey.commodity 不得为 null");
  }
}
