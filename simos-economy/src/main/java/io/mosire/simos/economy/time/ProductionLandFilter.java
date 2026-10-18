package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★★ <b>D-032 P3b：本轮新取得土地的唯一排除判据</b>。
 *
 * <pre>
 * landAllowed(share, acquiredThisRound) =
 *     share.asset() != LAND  ||  acquiredThisRound 为空  ||  !acquiredThisRound.contains(share.id())
 * </pre>
 *
 * <p>★★ <b>为什么只排除 LAND</b>：{@link SettlementDayContext#landAcquiredThisRound} 装的是本轮 M2 抵债 / M4
 * 买地取得的土地块；其他资产种类没有“本轮取得”标记，不能被这条规则误伤。空集 ⇒ 全部允许 ⇒ 与 P2 逐值相同 （旧调用方走不带该参数的入口，等价于传 {@link #none()}）。
 *
 * <p>★★ <b>为什么不直接过滤 {@code assetShares} 表</b>：ACTIVE {@code AssetOccupancyGrant} 仍可能引用这块新土地
 * （上一轮遗留的占用）。把份额从表里删掉会让 {@code AssetOccupancyAllocator} 报 {@code
 * asset-occupancy-share-missing}（契约故障）。本 helper 只让供给匹配跳过它，份额表本身保持完整。
 *
 * <p>★ 逐轮瞬态、只读：不写状态、不进 ChangeSet/Codec。
 */
final class ProductionLandFilter {

  private static final Logger LOG = EconomyLog.assetOccupancy();

  private ProductionLandFilter() {}

  /** 没有新取得土地（与 P2 逐值相同的缺省）。 */
  static Set<AssetShareId> none() {
    return Set.of();
  }

  /** 空集/null ⇒ {@link #none()}；非空 ⇒ 保序不可变副本（不丢首次出现序）。 */
  static Set<AssetShareId> normalize(Set<AssetShareId> acquiredThisRound) {
    if (acquiredThisRound == null || acquiredThisRound.isEmpty()) {
      return Set.of();
    }
    return Collections.unmodifiableSet(new LinkedHashSet<>(acquiredThisRound));
  }

  /** 唯一判据：只有 LAND 且 marker 命中才不允许；空集 ⇒ 一律允许。 */
  static boolean landAllowed(
      AssetShareId shareId, AssetKind assetKind, Set<AssetShareId> acquiredThisRound) {
    if (shareId == null || assetKind != AssetKind.LAND) {
      return true;
    }
    return acquiredThisRound == null
        || acquiredThisRound.isEmpty()
        || !acquiredThisRound.contains(shareId);
  }

  /** {@link #landAllowed(AssetShareId, AssetKind, Set)} 的份额重载（资产种类取自份额本身）。 */
  static boolean landAllowed(OwnershipStake share, Set<AssetShareId> acquiredThisRound) {
    Objects.requireNonNull(share, "ProductionLandFilter.landAllowed 的 share 不得为 null");
    return landAllowed(share.id(), share.asset(), acquiredThisRound);
  }

  /**
   * ★ M7 的 fail-closed 守卫：本批 {@code grantsToWrite} 里出现“本轮新土地”的授予 ⇒ 契约故障。
   *
   * <p>正常路径上不可达：{@link AssetOccupancyAllocator} 已在 scope 供给匹配时把新土地剔除，绝不会对它建 grant。
   * 留这道守卫是为了让“过滤被绕过”显式爆炸（ERROR，不降级），而不是让生产执行悄悄使用新土地。
   */
  static void requireNoGrantOnExcludedLand(
      long day,
      AssetOccupancyBudget budget,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<AssetShareId> acquiredThisRound) {
    if (budget == null || acquiredThisRound == null || acquiredThisRound.isEmpty()) {
      return;
    }
    for (AssetOccupancyGrant grant : budget.grantsToWrite()) {
      OwnershipStake share = assetShares.get(grant.shareId());
      if (share != null && !landAllowed(share, acquiredThisRound)) {
        EventLog.channel(LOG)
            .error(
                LogEvent.of(
                    "D032_P3_LAND_FILTER_BYPASSED",
                    EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                    "day",
                    day,
                    "unit",
                    grant.unitId().value(),
                    "share",
                    grant.shareId().value(),
                    "quantity",
                    grant.quantity(),
                    "why",
                    "grant-on-land-acquired-this-round"));
        throw new IllegalStateException(
            "本轮新取得土地被用于生产授予（land-acquired-this-round-excluded）: day="
                + day
                + " unit="
                + grant.unitId().value()
                + " share="
                + grant.shareId().value());
      }
    }
  }
}
