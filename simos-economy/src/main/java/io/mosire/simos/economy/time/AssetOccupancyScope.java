package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionLocation;
import java.util.Comparator;
import java.util.Objects;

/**
 * ★★ <b>D-032 D4 统一资产预算的作用域</b>（D4 §5.1/§5.3）：同产业、同经营者、同地点、同资产种、同时段。
 *
 * <p>★ 所有者不参与池化键（同一 operator 可同时使用自有/租佃/公共份额，所有者差异由既有关系规则计租/分账）；地点是硬边界，不跨格借用。
 */
public record AssetOccupancyScope(
    IndustryId industry,
    ActorRef operator,
    ProductionLocation location,
    AssetKind assetKind,
    long fromTick,
    long toTickExclusive) {

  /** D4 §5.4 的稳定 scope 序（内容纯函数；不依赖 HashMap 迭代序）。 */
  public static final Comparator<AssetOccupancyScope> STABLE_ORDER =
      Comparator.comparing((AssetOccupancyScope scope) -> scope.industry().value())
          .thenComparing(scope -> scope.operator().kind().name())
          .thenComparing(scope -> scope.operator().id())
          .thenComparing(scope -> scope.location().value())
          .thenComparing(scope -> scope.assetKind().name())
          .thenComparingLong(AssetOccupancyScope::fromTick)
          .thenComparingLong(AssetOccupancyScope::toTickExclusive);

  public AssetOccupancyScope {
    Objects.requireNonNull(industry, "AssetOccupancyScope.industry 不得为 null");
    Objects.requireNonNull(operator, "AssetOccupancyScope.operator 不得为 null");
    Objects.requireNonNull(location, "AssetOccupancyScope.location 不得为 null");
    OwnershipStake.requireSpecifiedLocation(location);
    Objects.requireNonNull(assetKind, "AssetOccupancyScope.assetKind 不得为 null");
    if (fromTick < 0L) {
      throw new IllegalArgumentException("AssetOccupancyScope.fromTick 不得为负: " + fromTick);
    }
    if (fromTick >= toTickExclusive) {
      throw new IllegalArgumentException(
          "AssetOccupancyScope 区间必须满足 fromTick < toTickExclusive: "
              + fromTick
              + " >= "
              + toTickExclusive);
    }
  }

  /** 目标半开区间是否与另一区间重叠。 */
  public boolean overlaps(long otherFromTick, long otherToTickExclusive) {
    return fromTick < otherToTickExclusive && otherFromTick < toTickExclusive;
  }
}
