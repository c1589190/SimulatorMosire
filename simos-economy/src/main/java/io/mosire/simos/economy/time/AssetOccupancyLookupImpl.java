package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.RevisionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★ <b>D-032 D4 资产占用真实只读实现</b>（D4 §7.0）：从第 36 组件 {@code assetOccupancies} 与实物份额构造 {@link
 * AssetOccupancySnapshot}。
 *
 * <p>★★ <b>revision 精确校验</b>：构造时注入本实现的权威 revision；调用方传入的 {@code expectedRevision} 不相等 ⇒ ERROR
 * {@code asset-occupancy-snapshot-mismatch}，绝不返回跨 revision 的陈旧读数。unit/location/interval 同样按
 * production 当刻实例与请求逐值校验。
 *
 * <p>★★ <b>empty 与空 Map</b>：unit 不在 production 当刻投影（无权威事实）⇒ {@link Optional#empty()}；unit 存在但没有
 * grant ⇒ 快照中的空 allocation/committed/unallocated（合法 0）。本实现不返回 {@code Optional.empty()} 冒充 0。
 */
public final class AssetOccupancyLookupImpl implements AssetOccupancyLookup {

  private static final Logger LOG = EconomyLog.assetOccupancy();

  private final Map<AssetOccupancyGrantId, AssetOccupancyGrant> grants;
  private final Map<AssetShareId, OwnershipStake> assetShares;
  private final Map<ProductionUnitId, ProductionProcess> units;
  private final RevisionId revision;

  /** 权威运行时入口：从 production 当刻投影取 unit，从 EconomyData 取 grant/share。 */
  public AssetOccupancyLookupImpl(
      EconomyData data, RevisionId revision, ProductionRuntime runtime) {
    this(
        Objects.requireNonNull(data, "data 不得为 null").assetOccupancies(),
        data.assetShares(),
        Objects.requireNonNull(runtime, "runtime 不得为 null").units(),
        revision);
  }

  /** 显式注入同一 revision 的只读投影（装配/测试可给 production 的 {@code units()} 快照）。 */
  public AssetOccupancyLookupImpl(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> grants,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionProcess> units,
      RevisionId revision) {
    this.grants =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(grants, "grants 不得为 null（无占用用空表）")));
    this.assetShares =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(
                Objects.requireNonNull(assetShares, "assetShares 不得为 null（无份额用空表）")));
    this.units =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(units, "units 不得为 null")));
    this.revision = Objects.requireNonNull(revision, "revision 不得为 null");
  }

  @Override
  public Optional<AssetOccupancySnapshot> occupancyOf(
      ProductionUnitId unit, RevisionId expectedRevision, long fromTick, long toTickExclusive) {
    Objects.requireNonNull(unit, "unit 不得为 null");
    Objects.requireNonNull(expectedRevision, "expectedRevision 不得为 null");
    if (fromTick < 0L) {
      throw new IllegalArgumentException("AssetOccupancyLookup.fromTick 不得为负: " + fromTick);
    }
    if (fromTick >= toTickExclusive) {
      throw new IllegalArgumentException(
          "AssetOccupancyLookup 区间必须满足 fromTick < toTickExclusive: "
              + fromTick
              + " >= "
              + toTickExclusive);
    }
    if (!revision.equals(expectedRevision)) {
      contractFault(
          "asset-occupancy-snapshot-mismatch",
          "revision 不一致: expected="
              + expectedRevision.value()
              + " actual="
              + revision.value()
              + " unit="
              + unit.value(),
          unit);
    }
    ProductionProcess process = units.get(unit);
    if (process == null) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ASSET_OCCUPANCY_SNAPSHOT_UNAVAILABLE",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "unit",
                  unit.value(),
                  "fromTick",
                  fromTick,
                  "toTickExclusive",
                  toTickExclusive,
                  "why",
                  "unit-not-in-production-authority"));
      return Optional.empty();
    }
    ProductionLocation location = process.location();
    OwnershipStake.requireSpecifiedLocation(location);

    Map<AssetShareId, OwnershipStake> matchingShares = new LinkedHashMap<>();
    List<OwnershipStake> orderedShares = new ArrayList<>();
    for (OwnershipStake share : assetShares.values()) {
      if (share.industry().equals(process.industry())
          && share.operator().equals(process.operator())
          && share.location().equals(location)) {
        matchingShares.put(share.id(), share);
        orderedShares.add(share);
      }
    }
    orderedShares.sort(Comparator.comparing(share -> share.id().value()));

    List<AssetOccupancyGrant> allActive = new ArrayList<>();
    for (Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant> entry : grants.entrySet()) {
      AssetOccupancyGrant grant = entry.getValue();
      if (grant == null || !entry.getKey().equals(grant.id())) {
        contractFault(
            "asset-occupancy-snapshot-mismatch", "grant 键必须等于行内 id: " + entry.getKey(), unit);
      }
      OwnershipStake share = matchingShares.get(grant.shareId());
      if (share == null) {
        OwnershipStake anyShare = assetShares.get(grant.shareId());
        if (anyShare == null) {
          contractFault(
              "asset-occupancy-snapshot-mismatch", "grant 指名悬空 share: " + grant.id().value(), unit);
        }
        if (anyShare.industry().equals(process.industry())
            && anyShare.operator().equals(process.operator())
            && anyShare.location().equals(location)) {
          contractFault(
              "asset-occupancy-snapshot-mismatch",
              "grant share 与 unit 作用域不一致: " + grant.id().value(),
              unit);
        }
        continue;
      }
      if (grant.unitId().equals(unit)) {
        ProductionProcess grantUnit = units.get(grant.unitId());
        if (grantUnit == null) {
          contractFault(
              "asset-occupancy-snapshot-mismatch", "grant 指名悬空 unit: " + grant.id().value(), unit);
        }
        if (!grantUnit.location().equals(location)
            || !grantUnit.industry().equals(process.industry())
            || !grantUnit.operator().equals(process.operator())) {
          contractFault(
              "asset-occupancy-snapshot-mismatch",
              "grant unit/share scope 不一致: " + grant.id().value(),
              unit);
        }
      }
      if (grant.status() == AssetOccupancyStatus.ACTIVE
          && grant.overlaps(fromTick, toTickExclusive)) {
        allActive.add(grant);
      }
    }

    Map<AssetKind, Long> supplyByKind = new LinkedHashMap<>();
    for (OwnershipStake share : orderedShares) {
      supplyByKind.merge(share.asset(), share.quantity(), Math::addExact);
    }
    Map<AssetKind, Long> committedByKind = new LinkedHashMap<>();
    Map<AssetKind, Long> allocationByKind = new LinkedHashMap<>();
    for (AssetOccupancyGrant grant : allActive) {
      OwnershipStake share = matchingShares.get(grant.shareId());
      committedByKind.merge(share.asset(), grant.quantity(), Math::addExact);
      if (grant.unitId().equals(unit)) {
        allocationByKind.merge(share.asset(), grant.quantity(), Math::addExact);
      }
    }
    Map<AssetKind, Long> unallocatedByKind = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : supplyByKind.entrySet()) {
      long committed = committedByKind.getOrDefault(entry.getKey(), 0L);
      if (committed > entry.getValue()) {
        contractFault(
            "asset-occupancy-snapshot-mismatch",
            "scope 已占超过 supply: kind="
                + entry.getKey().name()
                + " committed="
                + committed
                + " supply="
                + entry.getValue(),
            unit);
      }
      unallocatedByKind.put(entry.getKey(), entry.getValue() - committed);
    }

    Optional<String> rejection = Optional.empty();
    if (supplyByKind.isEmpty()) {
      rejection = Optional.of("asset-location-scope-missing:" + location.value());
    } else if (allocationByKind.isEmpty()) {
      for (AssetKind kind : committedByKind.keySet()) {
        if (committedByKind.getOrDefault(kind, 0L) > 0L) {
          rejection = Optional.of("asset-short:" + kind.name());
          break;
        }
      }
    }

    AssetOccupancySnapshot snapshot =
        new AssetOccupancySnapshot(
            revision,
            unit,
            location,
            fromTick,
            toTickExclusive,
            allocationByKind,
            committedByKind,
            unallocatedByKind,
            rejection);
    EventLog.channel(LOG)
        .trace(
            LogEvent.of(
                "ASSET_OCCUPANCY_SNAPSHOT",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "unit",
                unit.value(),
                "location",
                location.value(),
                "fromTick",
                fromTick,
                "toTickExclusive",
                toTickExclusive,
                "allocationKinds",
                allocationByKind.size(),
                "committedKinds",
                committedByKind.size(),
                "rejection",
                rejection.orElse("-")));
    return Optional.of(snapshot);
  }

  private static void contractFault(String word, String detail, ProductionUnitId unit) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "ASSET_OCCUPANCY_LOOKUP_CONTRACT_FAULT",
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
