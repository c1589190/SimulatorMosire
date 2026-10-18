package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AssetOccupancyBook.GRANT_ORDER;
import static io.mosire.simos.economy.time.AssetOccupancyBook.LOG;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 资产占用拆分 helper（由 AssetOccupancyBook 抽取；行为不变）。 */
final class AssetOccupancyReadout {

  private AssetOccupancyReadout() {}

  /**
   * 单条释放：ACTIVE → RELEASED，数量原样保留供审计/回填；不修改份额数量。
   *
   * @param grant 当前 ACTIVE 授予
   * @param releasedAtTick 当前 tick（不得为负）
   * @return RELEASED 终态行
   */
  public static AssetOccupancyGrant release(AssetOccupancyGrant grant, long releasedAtTick) {
    Objects.requireNonNull(grant, "grant 不得为 null");
    if (grant.status() != AssetOccupancyStatus.ACTIVE) {
      contractFault(
          "asset-occupancy-release-not-active",
          "只能释放 ACTIVE grant: " + grant.id().value(),
          grant.unitId());
    }
    if (releasedAtTick < 0L) {
      contractFault(
          "asset-occupancy-invalid-release-tick",
          "releasedAtTick 不得为负: " + releasedAtTick,
          grant.unitId());
    }
    AssetOccupancyGrant released =
        new AssetOccupancyGrant(
            grant.id(),
            grant.shareId(),
            grant.unitId(),
            grant.quantity(),
            grant.fromTick(),
            grant.toTickExclusive(),
            AssetOccupancyStatus.RELEASED,
            releasedAtTick);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ASSET_OCCUPANCY_GRANT_RELEASED",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "grant",
                released.id().value(),
                "unit",
                released.unitId().value(),
                "share",
                released.shareId().value(),
                "quantity",
                released.quantity(),
                "fromTick",
                released.fromTick(),
                "toTickExclusive",
                released.toTickExclusive(),
                "releasedAtTick",
                released.releasedAtTick(),
                "why",
                "released-and-returned-to-unallocated"));
    return released;
  }

  /**
   * ★★★ <b>W5-F：份额被非 D4 写者改写后的 grant 数量收口</b>。
   *
   * <p>D4 分配器生成 grant 时已按 {@code share.quantity - committed} 逐 share 上限切分；但同一 revision 内
   * 后面的产权写者（例如迁移拆分份额）仍可能让已 ACTIVE 的 grant 大于该 share 的当前 {@code quantity}。 本方法只做数量收口：<b>不</b>改 grant
   * 的 id/share/unit/区间/状态，只把超过当前 {@link OwnershipStake#quantity()} 的那部分截掉；截掉量以 {@code
   * asset-short:<kind>} 具名记录。
   *
   * <p>★ 单条上限必须成立（不是只看同 share 的总和）：{@code EconomyReferenceValidation} 对每一条 grant 都 直接比较 {@code
   * grant.quantity() <= share.quantity()}。share 已降到 0 时无法保留任何正数 grant，只能移除该行 （并同样具名记录）。
   *
   * @return 实际被收口/移除的 grant 条数
   */
  static int reconcileGrantQuantitiesWithShares(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> grants,
      Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(grants, "grants 不得为 null（无占用用空表）");
    Map<AssetShareId, OwnershipStake> shares = validatedShares(assetShares);
    List<AssetOccupancyGrant> ordered = new ArrayList<>(grants.values());
    ordered.sort(GRANT_ORDER);
    int adjusted = 0;
    for (AssetOccupancyGrant grant : ordered) {
      if (grant == null) {
        contractFault("asset-occupancy-null-grant", "assetOccupancies 的值不得为 null", null);
      }
      OwnershipStake share = shares.get(grant.shareId());
      if (share == null) {
        contractFault(
            "asset-occupancy-share-missing",
            "grant 指名的 share 不存在: " + grant.id().value() + " → " + grant.shareId().value(),
            grant.unitId());
      }
      if (grant.quantity() <= share.quantity()) {
        continue;
      }
      if (share.quantity() <= 0L) {
        grants.remove(grant.id());
        adjusted++;
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ASSET_OCCUPANCY_GRANT_REMOVED_WITHOUT_SHARE_QUANTITY",
                    EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                    "grant",
                    grant.id().value(),
                    "unit",
                    grant.unitId().value(),
                    "share",
                    grant.shareId().value(),
                    "kind",
                    share.asset().name(),
                    "previousQuantity",
                    grant.quantity(),
                    "shareQuantity",
                    share.quantity(),
                    "why",
                    "asset-short:" + share.asset().name()));
        continue;
      }
      AssetOccupancyGrant capped =
          new AssetOccupancyGrant(
              grant.id(),
              grant.shareId(),
              grant.unitId(),
              share.quantity(),
              grant.fromTick(),
              grant.toTickExclusive(),
              grant.status(),
              grant.releasedAtTick());
      grants.put(capped.id(), capped);
      adjusted++;
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ASSET_OCCUPANCY_GRANT_QUANTITY_CAPPED_TO_SHARE",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "grant",
                  grant.id().value(),
                  "unit",
                  grant.unitId().value(),
                  "share",
                  grant.shareId().value(),
                  "kind",
                  share.asset().name(),
                  "previousQuantity",
                  grant.quantity(),
                  "quantity",
                  capped.quantity(),
                  "shareQuantity",
                  share.quantity(),
                  "why",
                  "asset-short:" + share.asset().name()));
    }
    return adjusted;
  }

  static Map<AssetOccupancyGrantId, AssetOccupancyGrant> activeGrants(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> shares,
      long currentTick,
      List<AssetOccupancyGrant> grantsToRelease) {
    Map<AssetOccupancyGrantId, AssetOccupancyGrant> active = new LinkedHashMap<>();
    List<AssetOccupancyGrant> ordered = new ArrayList<>();
    for (Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant> entry : existingGrants.entrySet()) {
      AssetOccupancyGrantId key = entry.getKey();
      AssetOccupancyGrant grant = entry.getValue();
      if (key == null || grant == null) {
        contractFault("asset-occupancy-null-grant", "assetOccupancies 的键/值不得为 null", null);
      }
      if (!key.equals(grant.id())) {
        contractFault(
            "asset-occupancy-key-mismatch", "grant 键必须等于行内 id: " + key.value(), grant.unitId());
      }
      OwnershipStake share = shares.get(grant.shareId());
      if (share == null) {
        contractFault(
            "asset-occupancy-share-missing",
            "grant 指名的 share 不存在: " + grant.id().value() + " → " + grant.shareId().value(),
            grant.unitId());
      }
      if (grant.quantity() > share.quantity()) {
        contractFault(
            "asset-occupancy-quantity-exceeds-share",
            "grant 数量超过 share.quantity: " + grant.id().value(),
            grant.unitId());
      }
      ordered.add(grant);
      if (grant.status() == AssetOccupancyStatus.ACTIVE) {
        if (grant.toTickExclusive() <= currentTick) {
          grantsToRelease.add(release(grant, currentTick));
        } else {
          active.put(grant.id(), grant);
        }
      }
    }
    ordered.sort(GRANT_ORDER);
    for (int i = 0; i < ordered.size(); i++) {
      AssetOccupancyGrant left = ordered.get(i);
      if (left.status() != AssetOccupancyStatus.ACTIVE) {
        continue;
      }
      for (int j = i + 1; j < ordered.size(); j++) {
        AssetOccupancyGrant right = ordered.get(j);
        if (right.status() != AssetOccupancyStatus.ACTIVE) {
          continue;
        }
        if (left.shareId().equals(right.shareId())
            && left.unitId().equals(right.unitId())
            && left.overlaps(right.fromTick(), right.toTickExclusive())) {
          contractFault(
              "asset-occupancy-overlap:" + left.shareId().value() + ":" + left.unitId().value(),
              "同一 share 同一 unit 同时段存在两条 ACTIVE grant: "
                  + left.id().value()
                  + " / "
                  + right.id().value(),
              left.unitId());
        }
      }
    }
    validateActiveShareQuantities(active, shares);
    return active;
  }

  /**
   * 同一 share 上不同 unit 的 ACTIVE grant 可以重叠，但任意时刻的合计数量不得超过 share.quantity。
   *
   * <p>只在 grant 起点处取并集：ACTIVE 合计只在某个 grant 起点单调增加，随后只会在终点减少，因此逐个起点检查即可覆盖所有 时刻的最大并发占用。
   */
  static void validateActiveShareQuantities(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> active,
      Map<AssetShareId, OwnershipStake> shares) {
    Map<AssetShareId, List<AssetOccupancyGrant>> byShare = new LinkedHashMap<>();
    for (AssetOccupancyGrant grant : active.values()) {
      byShare.computeIfAbsent(grant.shareId(), ignored -> new ArrayList<>()).add(grant);
    }
    for (Map.Entry<AssetShareId, List<AssetOccupancyGrant>> entry : byShare.entrySet()) {
      OwnershipStake share = shares.get(entry.getKey());
      List<AssetOccupancyGrant> grants = entry.getValue();
      List<Long> moments = new ArrayList<>();
      for (AssetOccupancyGrant grant : grants) {
        moments.add(grant.fromTick());
      }
      moments.sort(Long::compare);
      for (long moment : moments) {
        long sum = 0L;
        for (AssetOccupancyGrant grant : grants) {
          if (grant.fromTick() <= moment && moment < grant.toTickExclusive()) {
            sum = addExact(sum, grant.quantity(), "activeShareQuantity", null, grant.unitId());
          }
        }
        if (sum > share.quantity()) {
          contractFault(
              "asset-occupancy-overcommit",
              "份额 "
                  + share.id().value()
                  + " 在 tick "
                  + moment
                  + " 的 ACTIVE 合计 "
                  + sum
                  + " 超过 quantity "
                  + share.quantity(),
              null);
        }
      }
    }
  }

  static Map<AssetShareId, OwnershipStake> validatedShares(
      Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(assetShares, "assetShares 不得为 null（无份额用空表）");
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    for (Map.Entry<AssetShareId, OwnershipStake> entry : assetShares.entrySet()) {
      AssetShareId key = entry.getKey();
      OwnershipStake share = entry.getValue();
      if (key == null || share == null || !key.equals(share.id())) {
        contractFault(
            "asset-occupancy-share-key-mismatch",
            "assetShares 的键必须等于行内 id: " + key + " / " + (share == null ? null : share.id()),
            null);
      }
      OwnershipStake.requireSpecifiedLocation(share.location());
      shares.put(key, share);
    }
    return shares;
  }

  static void logBudget(AssetOccupancyBudget budget) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "ASSET_OCCUPANCY_BUDGET",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "scopes",
                budget.available().size(),
                "grantsToWrite",
                budget.grantsToWrite().size(),
                "grantsToRelease",
                budget.grantsToRelease().size(),
                "rejections",
                budget.rejections().size()));
  }

  static void debug(String event, AssetOccupancyScope scope, Object... keyValues) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    Object[] fields = new Object[keyValues.length + 6];
    fields[0] = "industry";
    fields[1] = scope.industry().value();
    fields[2] = "location";
    fields[3] = scope.location().value();
    fields[4] = "kind";
    fields[5] = scope.assetKind().name();
    System.arraycopy(keyValues, 0, fields, 6, keyValues.length);
    EventLog.channel(LOG)
        .debug(LogEvent.of(event, EconomyLogSource.ECONOMY_ASSET_OCCUPANCY, fields));
  }

  static long addExact(
      long left, long right, String operation, AssetOccupancyScope scope, ProductionUnitId unit) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      contractFault(
          "asset-occupancy-quantity-overflow",
          operation + " 溢出: " + left + " + " + right + " scope=" + scope + " unit=" + unit,
          unit);
      return 0L;
    }
  }

  static long subtractExact(
      long left, long right, String operation, AssetOccupancyScope scope, ProductionUnitId unit) {
    try {
      return Math.subtractExact(left, right);
    } catch (ArithmeticException overflow) {
      contractFault(
          "asset-occupancy-quantity-overflow",
          operation + " 溢出: " + left + " - " + right + " scope=" + scope + " unit=" + unit,
          unit);
      return 0L;
    }
  }

  static long multiplyExact(
      long left, long right, String operation, AssetOccupancyScope scope, ProductionUnitId unit) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      contractFault(
          "asset-occupancy-quantity-overflow",
          operation + " 溢出: " + left + " * " + right + " scope=" + scope + " unit=" + unit,
          unit);
      return 0L;
    }
  }

  static void contractFault(String word, String detail, ProductionUnitId unit) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "ASSET_OCCUPANCY_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "failure",
                word,
                "unit",
                unit == null ? "-" : unit.value(),
                "detail",
                detail));
    throw new IllegalStateException(word + ": " + detail);
  }
}
