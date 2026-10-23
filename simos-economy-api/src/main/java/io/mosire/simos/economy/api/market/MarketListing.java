package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.inventory.InventoryLotId;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 统一市场挂单：市场轮开始时从唯一库存 lot 视图派生的一行只读快照。
 *
 * <p>一张 listing 只有一个源 lot；{@code estimatedValueMilli} 是估值，不是成交价本身。listing 只在市场轮内存在， 不落盘、不进
 * ChangeSet/Codec。
 *
 * @param sourceLotId 源 lot 身份；不得为 null
 * @param item 物品引用；不得为 null
 * @param quantityMilli 本轮可卖数量（最小计量单位）；必须大于 0
 * @param estimatedValueMilli 纯派生的市场估值；缺价时为空
 */
public record MarketListing(
    InventoryLotId sourceLotId,
    MarketItemRef item,
    long quantityMilli,
    OptionalLong estimatedValueMilli) {

  public MarketListing {
    Objects.requireNonNull(sourceLotId, "MarketListing.sourceLotId");
    Objects.requireNonNull(item, "MarketListing.item");
    Objects.requireNonNull(
        estimatedValueMilli, "MarketListing.estimatedValueMilli（缺价用 OptionalLong.empty()）");
    if (quantityMilli <= 0L) {
      throw new IllegalArgumentException("MarketListing.quantityMilli 必须大于 0: " + quantityMilli);
    }
    if (estimatedValueMilli.isPresent() && estimatedValueMilli.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "MarketListing.estimatedValueMilli 不得为负: " + estimatedValueMilli.getAsLong());
    }
  }
}
