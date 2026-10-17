package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.Objects;

/**
 * ★★ <b>D-032 D4 单 unit × 单资产种类的分配读数</b>（D4 §4.6/§5.1）：现有 committed + 本次新授予后的总量。
 *
 * <p>它是 {@link AssetOccupancyBudget} 的只读派生行，不落状态；真正的持久增量只有 {@link
 * io.mosire.simos.economy.model.AssetOccupancyGrant}。逐时段的完整 scope 由预算的 map 键给出。
 *
 * @param unitId 生产实例
 * @param assetKind 资产种类
 * @param committedQuantity 本次分配前已 ACTIVE 的数量
 * @param allocatedQuantity 本次分配后的总量（committed + 本批新授予）
 */
public record AssetOccupancyAllocation(
    ProductionUnitId unitId, AssetKind assetKind, long committedQuantity, long allocatedQuantity) {

  public AssetOccupancyAllocation {
    Objects.requireNonNull(unitId, "AssetOccupancyAllocation.unitId 不得为 null");
    Objects.requireNonNull(assetKind, "AssetOccupancyAllocation.assetKind 不得为 null");
    if (committedQuantity < 0L) {
      throw new IllegalArgumentException(
          "AssetOccupancyAllocation.committedQuantity 不得为负: " + committedQuantity);
    }
    if (allocatedQuantity < committedQuantity) {
      throw new IllegalArgumentException(
          "AssetOccupancyAllocation.allocatedQuantity 不得小于 committedQuantity: "
              + allocatedQuantity
              + " < "
              + committedQuantity);
    }
  }
}
