package io.mosire.simos.economy.land;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>D-032 RA1b2b：旧 D4/production 的瞬态 LAND 投影</b>（2026-10-19）。
 *
 * <p>★★ <b>它解决什么</b>：标量 LAND 切源后，{@code EconomyData.assetShares} 不再包含 LAND 行；而旧 D4 分配器与 production
 * 的试算/执行仍只认 {@code Map<AssetShareId, OwnershipStake>} + {@code Map<AssetKind, Long>}。本类把 {@code
 * landHoldings} 按 unit 的 {@code (industry, operator household, location)} 合成一份 <b>只读、瞬态</b> 的 LAND
 * 份额，供 D4 在<b>单次结算</b>内读取。
 *
 * <p>★★ <b>它不是第二权威、不落盘</b>：
 *
 * <ul>
 *   <li>合成份额只活在调用方传入的合并 map 里，绝不进入 {@code EconomyData} / ChangeSet / Codec / {@code
 *       assetOccupancies}；
 *   <li>合成 {@link AssetShareId} 统一带 {@value #ID_PREFIX} 前缀，且 {@link Projection#transientShareIds()}
 *       是唯一的识别集合 —— 写回状态前必须按它过滤；
 *   <li>可用量 = 持有量 − {@code acquiredThisRound}（部分取得按数量扣，不按整 key 排除）；为 0 时不生成投影，由 D4 正常给出 LAND=0 /
 *       具名拒绝，绝不补 1。
 * </ul>
 *
 * <p>★ <b>只服务过渡桥</b>：RA2 用真实标准农业聚合替换它。
 */
public final class LandProductionProjection {

  /** 合成份额 id 的固定前缀；识别写回过滤只看 {@link Projection#transientShareIds()}，不靠解析本前缀。 */
  public static final String ID_PREFIX = "land-projection-";

  private static final Comparator<ProductionUnitId> UNIT_ORDER =
      Comparator.comparing(ProductionUnitId::value);

  private LandProductionProjection() {}

  /**
   * 一次投影的结果。
   *
   * @param mergedAssetShares 非 LAND 份额 + 本次合成的 LAND 份额（稳定插入序：先原表，再按 unit id 升序）
   * @param transientShareIds 只属于本次结算的合成份额 id；写回状态前必须过滤
   */
  public record Projection(
      Map<AssetShareId, OwnershipStake> mergedAssetShares, Set<AssetShareId> transientShareIds) {

    public Projection {
      Objects.requireNonNull(mergedAssetShares, "mergedAssetShares");
      Objects.requireNonNull(transientShareIds, "transientShareIds");
      mergedAssetShares = Collections.unmodifiableMap(new LinkedHashMap<>(mergedAssetShares));
      transientShareIds = Collections.unmodifiableSet(new LinkedHashSet<>(transientShareIds));
    }
  }

  /** 没有标量土地输入时的缺省投影：原表原样返回，不产生任何合成份额。 */
  public static Projection none(Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(assetShares, "assetShares");
    return new Projection(assetShares, Set.of());
  }

  /**
   * 按 unit 生成瞬态 LAND 投影。
   *
   * <p>★ 只对 {@code industry.capacityPerUnit()} 含 {@link AssetKind#LAND} 的 unit 生效；operator actor
   * 必须能反查到已登记家户， 否则跳过（不伪造家户）。unit 地点必须是真实格；该 hex 不在 {@code landArableSupply} 中 ⇒ 跳过（D4 会给出 LAND=0 /
   * asset-short:LAND）。
   *
   * @param assetShares 持久化的非 LAND 份额（只读）
   * @param units 当刻生产实例（只读）
   * @param industries production 当刻产业定义（只读）
   * @param householdOfActor 家户 actor → 家户身份（键集 = 现存家户行）
   * @param landArableSupply app 从 Map 构造的每 hex 可耕总量瞬态快照
   * @param landHoldings 标量持有（键 = (hex, owner)）
   * @param acquiredThisRound 本轮按 (hex, owner) 取得的数量（瞬态；null/空 = 无排除）
   * @return 合并后的份额表 + 合成 id 识别集合
   */
  public static Projection project(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ActorRef, HouseholdId> householdOfActor,
      LandArableSupply landArableSupply,
      Map<LandHoldingKey, LandHolding> landHoldings,
      Map<LandHoldingKey, Long> acquiredThisRound) {
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(householdOfActor, "householdOfActor");
    Objects.requireNonNull(landArableSupply, "landArableSupply");
    Objects.requireNonNull(landHoldings, "landHoldings");
    if (landArableSupply.arableMilliMuByHex().isEmpty() && landHoldings.isEmpty()) {
      return none(assetShares);
    }
    LandLedgerState ledger = LandLedgerState.from(landArableSupply, landHoldings);
    Map<LandHoldingKey, Long> acquired = acquiredThisRound == null ? Map.of() : acquiredThisRound;

    LinkedHashMap<AssetShareId, OwnershipStake> merged = new LinkedHashMap<>(assetShares);
    LinkedHashSet<AssetShareId> transientIds = new LinkedHashSet<>();
    List<ProductionUnitId> orderedUnits = new ArrayList<>(units.keySet());
    orderedUnits.sort(UNIT_ORDER);
    for (ProductionUnitId unitId : orderedUnits) {
      ProductionProcess unit = units.get(unitId);
      if (unit == null || !unit.id().equals(unitId)) {
        continue;
      }
      IndustryDefinitionView industry = industries.get(unit.industry());
      if (industry == null || !industry.capacityPerUnit().containsKey(AssetKind.LAND)) {
        continue;
      }
      if (!unit.location().isSpecified()) {
        continue;
      }
      HexCoord hex;
      try {
        hex = HexCoord.parse(unit.location().value());
      } catch (IllegalArgumentException malformedLocation) {
        continue; // 地点格式由别处 fail-closed；这里不把坏地点当合法投影
      }
      if (!landArableSupply.containsHex(hex)) {
        continue;
      }
      HouseholdId household = householdOfActor.get(unit.operator());
      if (household == null) {
        continue; // operator 不是已登记家户 actor ⇒ 不伪造
      }
      long usable = ledger.usableQuantity(hex, household, acquired);
      if (usable <= 0L) {
        continue;
      }
      AssetShareId syntheticId = new AssetShareId(ID_PREFIX + unit.id().value());
      ActorRef householdActor = HouseholdActors.of(household);
      OwnershipStake projected =
          new OwnershipStake(
              syntheticId,
              unit.industry(),
              AssetKind.LAND,
              householdActor,
              householdActor,
              usable,
              AssetRightKind.OWNED,
              unit.location());
      if (merged.putIfAbsent(syntheticId, projected) != null) {
        throw new IllegalStateException(
            "land-projection-id-collision: 合成 LAND 份额 id 已存在: " + syntheticId.value());
      }
      transientIds.add(syntheticId);
    }
    return new Projection(merged, transientIds);
  }
}
