package io.mosire.simos.economy.api.inventory;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Optional;

/**
 * 统一库存视图中的一行。
 *
 * <p>商品行是可聚合的 fungible 余额；土地/资产行保有各自身份与状态，不能静默合并。
 *
 * @param lotId 稳定 lot 身份
 * @param itemKind 粗分类
 * @param itemTypeId 商品/土地类型 id
 * @param owner 家户 owner（v1）
 * @param location 所在地；无位置时为空
 * @param quantityMilli 最小计量单位数量（{@code > 0}）
 * @param state 领域状态
 * @param origin 来源审计
 * @param fungible 是否可与其他同型余额聚合
 */
public record InventoryLot(
    InventoryLotId lotId,
    InventoryLotKind itemKind,
    String itemTypeId,
    HouseholdId owner,
    Optional<HexCoord> location,
    long quantityMilli,
    LotState state,
    LotOrigin origin,
    boolean fungible) {

  public InventoryLot {
    if (lotId == null) {
      throw new IllegalArgumentException("InventoryLot.lotId 不得为 null");
    }
    if (itemKind == null) {
      throw new IllegalArgumentException("InventoryLot.itemKind 不得为 null");
    }
    if (itemTypeId == null || itemTypeId.isBlank()) {
      throw new IllegalArgumentException("InventoryLot.itemTypeId 不得为空白");
    }
    if (owner == null) {
      throw new IllegalArgumentException("InventoryLot.owner 不得为 null");
    }
    if (location == null) {
      throw new IllegalArgumentException("InventoryLot.location 不得为 null（无位置用 Optional.empty()）");
    }
    if (quantityMilli <= 0L) {
      throw new IllegalArgumentException("InventoryLot.quantityMilli 必须大于 0: " + quantityMilli);
    }
    if (state == null) {
      throw new IllegalArgumentException("InventoryLot.state 不得为 null");
    }
    if (origin == null) {
      throw new IllegalArgumentException("InventoryLot.origin 不得为 null");
    }
    if (itemKind == InventoryLotKind.LAND) {
      if (location.isEmpty()) {
        throw new IllegalArgumentException("inventory-lot-land-location-missing: " + lotId.value());
      }
      if (fungible) {
        throw new IllegalArgumentException(
            "InventoryLot LAND 必须是 fungible=false: " + lotId.value());
      }
    }
    if (itemKind == InventoryLotKind.COMMODITY) {
      if (!(state instanceof LotState.Complete)) {
        throw new IllegalArgumentException(
            "InventoryLot COMMODITY 的 state 必须是 Complete: " + lotId.value());
      }
      if (!fungible) {
        throw new IllegalArgumentException(
            "InventoryLot COMMODITY 必须是 fungible=true: " + lotId.value());
      }
    }
  }
}
