package io.mosire.simos.economy.api.inventory;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/** 适配层产出的域级动作描述；计划期只描述、不落盘。 */
public sealed interface InventoryLotMutation
    permits InventoryLotMutation.CommodityBalanceMutation,
        InventoryLotMutation.LandComponentMutation {

  /** actor 商品余额从 source 流向 target。 */
  record CommodityBalanceMutation(
      HouseholdId sourceOwner, HouseholdId targetOwner, CommodityId commodity, long quantityMilli)
      implements InventoryLotMutation {

    public CommodityBalanceMutation {
      Objects.requireNonNull(sourceOwner, "sourceOwner");
      Objects.requireNonNull(targetOwner, "targetOwner");
      Objects.requireNonNull(commodity, "commodity");
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException(
            "CommodityBalanceMutation.quantityMilli 必须大于 0: " + quantityMilli);
      }
    }
  }

  /** map 土地 component 的面积从 source component 流向 target owner。 */
  record LandComponentMutation(
      HexCoord hex,
      LandTypeId landType,
      LandComponentId sourceComponentId,
      HouseholdId targetOwner,
      long quantityMilli)
      implements InventoryLotMutation {

    public LandComponentMutation {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(landType, "landType");
      Objects.requireNonNull(sourceComponentId, "sourceComponentId");
      Objects.requireNonNull(targetOwner, "targetOwner");
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException(
            "LandComponentMutation.quantityMilli 必须大于 0: " + quantityMilli);
      }
    }
  }
}
