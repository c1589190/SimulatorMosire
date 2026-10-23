package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Optional;

/**
 * 统一市场订单可引用的物品语义。
 *
 * <p>本类型只有三种语义：商品身份（fungible 商品）、lot 类型（任意同类型 lot）、具体 lot。订单绝不携带 stage/progress/state
 * 快照；有状态物品的状态一律从 source lot 现读。
 */
public sealed interface MarketItemRef
    permits MarketItemRef.Commodity, MarketItemRef.LotType, MarketItemRef.SpecificLot {

  /** 按商品身份引用 fungible 商品。 */
  record Commodity(CommodityId commodityId) implements MarketItemRef {

    public Commodity {
      if (commodityId == null) {
        throw new IllegalArgumentException("MarketItemRef.Commodity.commodityId 不得为 null");
      }
    }
  }

  /** 按粗分类、类型 id 与可选位置引用任意同类型 lot。 */
  record LotType(InventoryLotKind kind, String typeId, Optional<HexCoord> location)
      implements MarketItemRef {

    public LotType {
      if (kind == null) {
        throw new IllegalArgumentException("MarketItemRef.LotType.kind 不得为 null");
      }
      if (typeId == null || typeId.isBlank()) {
        throw new IllegalArgumentException("MarketItemRef.LotType.typeId 不得为空白");
      }
      if (location == null) {
        throw new IllegalArgumentException(
            "MarketItemRef.LotType.location 不得为 null（无位置用 Optional.empty()）");
      }
    }
  }

  /** 按稳定 lot id 引用一个具体 lot。 */
  record SpecificLot(InventoryLotId lotId) implements MarketItemRef {

    public SpecificLot {
      if (lotId == null) {
        throw new IllegalArgumentException("MarketItemRef.SpecificLot.lotId 不得为 null");
      }
    }
  }
}
