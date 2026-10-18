package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>D-032 D4 统一资产预算分配器</b>（D4 §5）：一次 revision 内的<b>纯算法</b>，只产出 {@code grantsToWrite}/{@code
 * grantsToRelease}/allocation/unallocated/rejection，<b>不直接写 EconomyData</b>。
 *
 * <p>★★ <b>核心判据</b>：
 *
 * <ul>
 *   <li>scope = {@code (industry, operator, location, assetKind,
 *       [from,to))}；只匹配同产业/同经营者/同地点/同资产种的实物份额，绝不跨格借用；
 *   <li>先认已有 ACTIVE grant（与目标区间重叠），它们计入 committed，后到请求不能抢走；
 *   <li>单 unit scope 拿该 kind 的整个 supply（保持单实例逐值不变）；无请求表只在"该 unit 已有 ACTIVE 事实"时走此兼容路径， 候选/新实例不得默认 1
 *       或默认整池；
 *   <li>多 unit 按请求量比例分配，整数最大余数（{@code r_i desc, unitId asc}），所有加乘使用 exact 算术；
 *   <li>按 share id 升序拆 grant；每份 share 的 ACTIVE + 本批新授予 ≤ share.quantity；不修改 {@code
 *       OwnershipStake.quantity}；
 *   <li>释放/到期把 grant 置 RELEASED 并回填 unallocated；同 share 同 unit 同时段不产生重叠 ACTIVE（不同 unit 可在同一 share
 *       上合法重叠，但同一时刻的合计不得超过 share.quantity）。
 * </ul>
 *
 * <p>★ <b>确定性</b>：所有集合按 D4 §5.4 的稳定序处理；无随机、无时钟、无 UUID、无 HashMap 迭代序参与决策。
 *
 * <p>★ <b>日志</b>：INFO 创建/释放/回填/跨格拒绝，DEBUG 每 scope supply/committed/demand/unallocated/余数，TRACE 逐
 * share 拆行； 业务拒绝 INFO，契约故障 ERROR 后抛出（不降级）。
 */
public final class AssetOccupancyBook {

  static final Logger LOG = EconomyLog.assetOccupancy();

  static final Comparator<AssetKind> KIND_ORDER = Comparator.comparing(AssetKind::name);

  static final Comparator<AssetShareId> SHARE_ORDER = Comparator.comparing(AssetShareId::value);

  static final Comparator<ProductionUnitId> UNIT_ORDER =
      Comparator.comparing(ProductionUnitId::value);

  static final Comparator<AssetOccupancyGrant> GRANT_ORDER =
      Comparator.comparing((AssetOccupancyGrant grant) -> grant.unitId().value())
          .thenComparing(grant -> grant.shareId().value())
          .thenComparingLong(AssetOccupancyGrant::fromTick)
          .thenComparingLong(AssetOccupancyGrant::toTickExclusive)
          .thenComparing(grant -> grant.id().value());

  private AssetOccupancyBook() {}

  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      long currentTick) {
    return AssetOccupancyAllocator.allocate(
        requests, existingGrants, assetShares, Map.of(), Map.of(), currentTick);
  }

  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit,
      long currentTick) {
    return AssetOccupancyAllocator.allocate(
        requests, existingGrants, assetShares, acceptedRightKindsByUnit, Map.of(), currentTick);
  }

  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit,
      Map<ProductionUnitId, ProductionProcess> existingUnits,
      long currentTick) {
    return allocate(
        requests,
        existingGrants,
        assetShares,
        acceptedRightKindsByUnit,
        existingUnits,
        currentTick,
        ProductionLandFilter.none());
  }

  /**
   * ★★★ <b>D-032 P3b：带“本轮新取得土地排除集”的分配入口</b>。
   *
   * <p>★ 被排除的 LAND 份额只在 scope 供给匹配/资产量计算时跳过；{@code assetShares} 表原样保留，ACTIVE grant 引用不会被误报
   * share-missing。空集 ⇒ 与 P2 逐值相同（旧入口即传 {@link ProductionLandFilter#none()}）。
   */
  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit,
      Map<ProductionUnitId, ProductionProcess> existingUnits,
      long currentTick,
      Set<AssetShareId> excludedShareIds) {
    return AssetOccupancyAllocator.allocate(
        requests,
        existingGrants,
        assetShares,
        acceptedRightKindsByUnit,
        existingUnits,
        currentTick,
        excludedShareIds);
  }

  public static AssetOccupancyGrant release(AssetOccupancyGrant grant, long releasedAtTick) {
    return AssetOccupancyReadout.release(grant, releasedAtTick);
  }

  static int reconcileGrantQuantitiesWithShares(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> grants,
      Map<AssetShareId, OwnershipStake> assetShares) {
    return AssetOccupancyReadout.reconcileGrantQuantitiesWithShares(grants, assetShares);
  }
}
