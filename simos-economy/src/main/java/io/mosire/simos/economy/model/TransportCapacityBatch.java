package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.MarketZoneId;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C6：一个临时运输能力批次的当刻值</b>。
 *
 * <p>值只含 {@code marketZone} 与 {@code capacityWorkMilli}；家户 / homeHex / marketRoundId 是键的三段，
 * 不在值里重复存储（避免同一身份两处拼写）。批次不是普通商品库存：不进入 {@code HouseholdInventory}、 不进入 {@code
 * MarketTable.prices}、不可转卖、不可用于自然需求或债务抵物。
 *
 * @param marketZone 生产时 homeHex 所属市场区；不得为 null
 * @param capacityWorkMilli 本批次在该市场轮可用的运输能力（毫服务单位）；不得为负
 */
public record TransportCapacityBatch(MarketZoneId marketZone, long capacityWorkMilli) {

  public TransportCapacityBatch {
    Objects.requireNonNull(marketZone, "TransportCapacityBatch.marketZone 不得为 null");
    if (capacityWorkMilli < 0L) {
      throw new IllegalArgumentException(
          "TransportCapacityBatch.capacityWorkMilli 不得为负: " + capacityWorkMilli);
    }
  }
}
