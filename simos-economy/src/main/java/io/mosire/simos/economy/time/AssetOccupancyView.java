package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>D-032 D4 占用只读派生视图</b>（D4 §4.3）：把 {@link AssetOccupancyGrant} 引用的份额/实例字段拼出来供读口使用。
 *
 * <p>★ 本类型<b>不是第二份数量账</b>：不进入 {@code EconomyData}/{@code ChangeSet}/{@code Codec}，也不缓存；每次从同一
 * revision 的 grant + {@code OwnershipStake} + {@code ProductionProcess} 派生。数量的唯一权威仍是份额
 * quantity，grant 只表达数量锁。
 *
 * @param grantId 占用授予
 * @param shareId 唯一实物来源
 * @param industry 产业
 * @param assetKind 资产种类
 * @param owner 所有者
 * @param operator 经营者
 * @param location 规范格键
 * @param unitId 占用实例
 * @param quantity 占用数量
 * @param fromTick 半开区间起点（含）
 * @param toTickExclusive 半开区间终点（不含）
 * @param status ACTIVE / RELEASED
 */
public record AssetOccupancyView(
    AssetOccupancyGrantId grantId,
    AssetShareId shareId,
    IndustryId industry,
    AssetKind assetKind,
    ActorRef owner,
    ActorRef operator,
    ProductionLocation location,
    ProductionUnitId unitId,
    long quantity,
    long fromTick,
    long toTickExclusive,
    AssetOccupancyStatus status) {

  private static final Comparator<AssetOccupancyView> STABLE_ORDER =
      Comparator.comparing((AssetOccupancyView view) -> view.unitId().value())
          .thenComparing(view -> view.shareId().value())
          .thenComparingLong(AssetOccupancyView::fromTick)
          .thenComparingLong(AssetOccupancyView::toTickExclusive)
          .thenComparing(view -> view.grantId().value());

  public AssetOccupancyView {
    Objects.requireNonNull(grantId, "AssetOccupancyView.grantId 不得为 null");
    Objects.requireNonNull(shareId, "AssetOccupancyView.shareId 不得为 null");
    Objects.requireNonNull(industry, "AssetOccupancyView.industry 不得为 null");
    Objects.requireNonNull(assetKind, "AssetOccupancyView.assetKind 不得为 null");
    Objects.requireNonNull(owner, "AssetOccupancyView.owner 不得为 null");
    Objects.requireNonNull(operator, "AssetOccupancyView.operator 不得为 null");
    Objects.requireNonNull(location, "AssetOccupancyView.location 不得为 null");
    Objects.requireNonNull(unitId, "AssetOccupancyView.unitId 不得为 null");
    if (quantity <= 0L) {
      throw new IllegalArgumentException("AssetOccupancyView.quantity 必须 > 0: " + quantity);
    }
    if (fromTick < 0L || fromTick >= toTickExclusive) {
      throw new IllegalArgumentException(
          "AssetOccupancyView 区间必须满足 0 ≤ fromTick < toTickExclusive: "
              + fromTick
              + " / "
              + toTickExclusive);
    }
    Objects.requireNonNull(status, "AssetOccupancyView.status 不得为 null");
  }

  /** 从同一 revision 的授权/份额/实例派生一条只读视图；引用或作用域不一致是契约故障。 */
  public static AssetOccupancyView of(
      AssetOccupancyGrant grant, OwnershipStake share, ProductionProcess unit) {
    Objects.requireNonNull(grant, "grant 不得为 null");
    Objects.requireNonNull(share, "share 不得为 null");
    Objects.requireNonNull(unit, "unit 不得为 null");
    if (!grant.shareId().equals(share.id())) {
      throw new IllegalStateException(
          "asset-occupancy-view-share-mismatch: grant="
              + grant.id().value()
              + " share="
              + share.id().value());
    }
    if (!grant.unitId().equals(unit.id())) {
      throw new IllegalStateException(
          "asset-occupancy-view-unit-mismatch: grant="
              + grant.id().value()
              + " unit="
              + unit.id().value());
    }
    if (!share.industry().equals(unit.industry())
        || !share.operator().equals(unit.operator())
        || !share.location().equals(unit.location())) {
      throw new IllegalStateException(
          "asset-occupancy-view-scope-mismatch: grant=" + grant.id().value());
    }
    return new AssetOccupancyView(
        grant.id(),
        grant.shareId(),
        share.industry(),
        share.asset(),
        share.owner(),
        share.operator(),
        share.location(),
        unit.id(),
        grant.quantity(),
        grant.fromTick(),
        grant.toTickExclusive(),
        grant.status());
  }

  /** 批量派生只读视图（稳定序；无行 ⇒ 空表）。 */
  public static List<AssetOccupancyView> listOf(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> grants,
      Map<AssetShareId, OwnershipStake> shares,
      Map<ProductionUnitId, ProductionProcess> units) {
    Objects.requireNonNull(grants, "grants 不得为 null（无占用用空表）");
    Objects.requireNonNull(shares, "shares 不得为 null（无份额用空表）");
    Objects.requireNonNull(units, "units 不得为 null（无实例用空表）");
    List<AssetOccupancyView> views = new ArrayList<>();
    for (Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant> entry : grants.entrySet()) {
      AssetOccupancyGrant grant = entry.getValue();
      if (entry.getKey() == null || grant == null || !entry.getKey().equals(grant.id())) {
        throw new IllegalStateException("asset-occupancy-view-key-mismatch: " + entry.getKey());
      }
      OwnershipStake share = shares.get(grant.shareId());
      ProductionProcess unit = units.get(grant.unitId());
      if (share == null || unit == null) {
        throw new IllegalStateException(
            "asset-occupancy-view-dangling-reference: grant=" + grant.id().value());
      }
      views.add(of(grant, share, unit));
    }
    views.sort(STABLE_ORDER);
    return Collections.unmodifiableList(views);
  }
}
