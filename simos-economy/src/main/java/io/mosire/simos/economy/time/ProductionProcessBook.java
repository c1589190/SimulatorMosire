package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ <b>生产单元的派生读口</b>：资产只从同一 revision 的 D4 {@link AssetOccupancyBudget} 分配表读取，不落第二份状态。
 *
 * <p>★★ <b>为什么必须是派生</b>：{@code OwnershipStake} 是唯一的实物数量真相，D4 grant 表达占用；unit 或 {@code
 * IndustryDefinitionView} 都不存第二份 {@code capacity}。本类只做 D4 分配 → 产能规模的算术，不含公式常量。
 *
 * <pre>
 * allocation           = AssetOccupancyBudget.allocationOf(unit)
 * capacityScaleOf      = min over k ∈ industry.capacityPerUnit: ⌊allocation[k] ÷ capacityPerUnit[k]⌋
 * plannedCapacityScale = capacityScaleOf × StressPolicy.plannedScalePerMille(condition.status)
 * </pre>
 *
 * <p>★ <b>缺项/空表</b>：缺某种资产 ⇒ 该路读到 0 ⇒ 规模 0；{@code capacityPerUnit} 空表 ⇒ 返回 0。
 */
public final class ProductionProcessBook {

  private ProductionProcessBook() {}

  /** D4 统一预算中该 unit 的资产分配；没有预算条目 ⇒ 权威空表（合法 0）。 */
  public static Map<AssetKind, Long> allocationOf(
      ProductionProcess unit, AssetOccupancyBudget budget) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(budget, "budget");
    return budget.allocationOf(unit.id());
  }

  /** 结算索引中该 unit 的 D4 资产分配；索引无 D4 预算时由调用方先读 rejection 并 fail-closed。 */
  public static Map<AssetKind, Long> allocationOf(ProductionProcess unit, SettlementIndex index) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(index, "index");
    return index.assetAllocationOf(unit);
  }

  /**
   * unit 的产能规模 = {@code min over k ∈ capacityPerUnit: ⌊allocation[k] ÷ capacityPerUnit[k]⌋}。
   *
   * <p>分配表只来自同一 revision 的 D4 {@link AssetOccupancyBudget}；缺项 = 0，空表 = 0。不得回退整池份额扫描。
   */
  public static long capacityScaleOf(
      ProductionProcess unit, IndustryDefinitionView industry, AssetOccupancyBudget budget) {
    return capacityScaleOf(allocationOf(unit, budget), industry);
  }

  /** 索引口径的产能规模（分配表来自同一 revision 的 D4 预算）。 */
  public static long capacityScaleOf(
      ProductionProcess unit, IndustryDefinitionView industry, SettlementIndex index) {
    return capacityScaleOf(allocationOf(unit, index), industry);
  }

  /** 产能规模的唯一算式（各重载共用）。 */
  public static long capacityScaleOf(
      Map<AssetKind, Long> allocation, IndustryDefinitionView industry) {
    Objects.requireNonNull(allocation, "allocation");
    Objects.requireNonNull(industry, "industry");
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      scale = Math.min(scale, allocation.getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /**
   * 计划规模 = 产能规模 × {@link StressPolicy#plannedScalePerMille}（条件缺失 ⇒ ACTIVE ⇒ 1000‰）。
   *
   * <p>产能只读同一 revision 的 D4 {@link AssetOccupancyBudget} 分配，不回退整池扫描。
   */
  public static long plannedCapacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      AssetOccupancyBudget budget,
      OperatorCondition condition) {
    return capacityScaleOf(unit, industry, budget)
        * StressPolicy.plannedScalePerMille(
            condition == null ? OperatorCondition.IndustryStatus.ACTIVE : condition.status())
        / 1_000L;
  }

  /** 索引口径的计划规模（产能规模查 D4 预算；计划系数的算式不变）。 */
  public static long plannedCapacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    return capacityScaleOf(unit, industry, index)
        * StressPolicy.plannedScalePerMille(
            condition == null ? OperatorCondition.IndustryStatus.ACTIVE : condition.status())
        / 1_000L;
  }

  /**
   * ★★★ <b>D-032 D4-Ib2a：同一 revision/tick 的唯一资产占用预算入口</b>。
   *
   * <p>请求量只从<b>非资产通道</b>派生：名册计划劳动 / 实例已累计的实际劳动，以及实例已有周期投入；绝不读 {@code capacityPerUnit}
   * 反推资产供给，也绝不扫描份额当请求。逐资产 kind 的 horizon 由 {@link AssetOccupancyHorizonPolicy} 唯一决定，因此 CYCLE/TICK
   * 会形成不同半开区间请求。
   *
   * <p>★ <b>缺请求边界</b>：既没有名册计划劳动、也没有劳动/投入事实的已存在实例，会生成“空请求表”交由 {@link AssetOccupancyBook}
   * 的单实例整池兼容判据处理；scope 内多实例时该空请求表会被具名拒绝 {@code asset-occupancy-request-missing:<unit>}，不会默认
   * 1、也不会复制整池。
   *
   * @param units 当刻生产实例（只读）
   * @param assetShares 实物份额唯一数量账（只读）
   * @param existingGrants 第 36 组件当刻值（只读）
   * @param industries production 当刻产业定义（只读）
   * @param rosters 名册工作副本/只读值（无名册用空表）
   * @param today 当前世界日
   */
  public static AssetOccupancyBudget assetOccupancyBudget(
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionRoster> rosters,
      long today) {
    return assetOccupancyBudget(
        units, assetShares, existingGrants, industries, rosters, Map.of(), today);
  }

  /** ★★★ 带当刻劳动配额快照的入口：R2/D4 请求量可用实际 laborMilli 作为非资产事实。 */
  public static AssetOccupancyBudget assetOccupancyBudget(
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionRoster> rosters,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      long today) {
    Objects.requireNonNull(units, "units 不得为 null");
    Objects.requireNonNull(assetShares, "assetShares 不得为 null");
    Objects.requireNonNull(existingGrants, "existingGrants 不得为 null（无占用用空表）");
    Objects.requireNonNull(industries, "industries 不得为 null");
    Objects.requireNonNull(rosters, "rosters 不得为 null（无名册用空表）");
    Objects.requireNonNull(laborCommitments, "laborCommitments 不得为 null（无配额用空表）");
    if (today < 0L) {
      throw new IllegalArgumentException("asset-occupancy-invalid-tick: today 不得为负: " + today);
    }
    List<ProductionUnitId> orderedUnits = new ArrayList<>(units.keySet());
    orderedUnits.sort(java.util.Comparator.comparing(ProductionUnitId::value));
    List<AssetOccupancyRequest> requests = new ArrayList<>();
    Map<ProductionUnitId, Set<io.mosire.simos.economy.api.asset.AssetRightKind>> accepted =
        new LinkedHashMap<>();
    for (ProductionUnitId unitId : orderedUnits) {
      ProductionProcess unit = Objects.requireNonNull(units.get(unitId), "units 的值不得为 null");
      if (!unit.id().equals(unitId)) {
        throw new IllegalStateException(
            "asset-occupancy-unit-key-mismatch: " + unitId.value() + " / " + unit.id().value());
      }
      IndustryDefinitionView industry = industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException(
            "asset-occupancy-unavailable: 产业定义缺失 unit="
                + unit.id().value()
                + " plan="
                + unit.industry().value());
      }
      accepted.put(unit.id(), industry.acceptedRightKinds());
      long cycleStart;
      try {
        cycleStart = Math.subtractExact(today, unit.progressDays());
      } catch (ArithmeticException overflow) {
        throw new IllegalStateException(
            "asset-occupancy-horizon-invalid: cycleStart 溢出 unit=" + unit.id().value(), overflow);
      }
      if (cycleStart < 0L) {
        throw new IllegalStateException(
            "asset-occupancy-horizon-invalid: cycleStart 不得为负 unit="
                + unit.id().value()
                + " cycleStart="
                + cycleStart);
      }

      OptionalLong requestedScale =
          requestedScaleFromNonAssetFacts(unit, industry, rosters.get(unit.id()), laborCommitments);
      Map<AssetOccupancyHorizonPolicy.Interval, Map<AssetKind, Long>> byInterval =
          new LinkedHashMap<>();
      for (AssetKind kind : sortedKinds(industry.capacityPerUnit())) {
        AssetOccupancyHorizonPolicy.Interval interval =
            AssetOccupancyHorizonPolicy.intervalOf(kind, today, cycleStart, industry.cycleDays());
        Map<AssetKind, Long> quantities =
            byInterval.computeIfAbsent(interval, ignored -> new LinkedHashMap<>());
        long perScale = industry.capacityPerUnit().get(kind);
        if (requestedScale.isPresent() && requestedScale.getAsLong() > 0L) {
          long quantity;
          try {
            quantity = Math.multiplyExact(requestedScale.getAsLong(), perScale);
          } catch (ArithmeticException overflow) {
            throw new IllegalStateException(
                "asset-occupancy-quantity-overflow: unit="
                    + unit.id().value()
                    + " kind="
                    + kind.name(),
                overflow);
          }
          if (quantity > 0L) {
            quantities.put(kind, quantity);
          }
        }
      }
      List<AssetOccupancyHorizonPolicy.Interval> intervals = new ArrayList<>(byInterval.keySet());
      intervals.sort(
          java.util.Comparator.comparingLong(AssetOccupancyHorizonPolicy.Interval::fromTick)
              .thenComparingLong(AssetOccupancyHorizonPolicy.Interval::toTickExclusive));
      if (intervals.isEmpty()) {
        // 没有 capacityPerUnit 不可能（定义构造期拒绝空表）；保留 fail-closed。
        throw new IllegalStateException(
            "asset-occupancy-horizon-unsupported: 无资产 kind unit=" + unit.id().value());
      }
      for (AssetOccupancyHorizonPolicy.Interval interval : intervals) {
        requests.add(
            new AssetOccupancyRequest(
                unit.id(),
                unit.industry(),
                unit.operator(),
                unit.location(),
                interval.fromTick(),
                interval.toTickExclusive(),
                byInterval.get(interval),
                requestedScale,
                java.util.Optional.empty()));
      }
    }
    return AssetOccupancyBook.allocate(
        requests, existingGrants, assetShares, accepted, units, today);
  }

  private static List<AssetKind> sortedKinds(Map<AssetKind, Long> capacityPerUnit) {
    List<AssetKind> kinds = new ArrayList<>(capacityPerUnit.keySet());
    kinds.sort(java.util.Comparator.comparing(AssetKind::name));
    return kinds;
  }

  /** 从非资产事实派生计划规模：名册计划劳动优先；否则用实例已累计实际劳动/周期投入作“已存在事实”的保守日规模。 两条通道都读不到 ⇒ empty（不是默认 1，也不是 0）。 */
  private static OptionalLong requestedScaleFromNonAssetFacts(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionRoster roster,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    long days = Math.max(1L, unit.progressDays());
    boolean anyLaborFact = false;
    long laborScale = Long.MAX_VALUE;
    if (industry.recipe().laborPerUnit() > 0L) {
      long plannedLaborPerDay = 0L;
      for (HouseholdLaborCommitment commitment : laborCommitments.values()) {
        if (commitment.activity().equals(unit.id().value()) && commitment.laborMilli() > 0L) {
          plannedLaborPerDay = Math.addExact(plannedLaborPerDay, commitment.laborMilli());
          anyLaborFact = true;
        }
      }
      if (!anyLaborFact && roster != null) {
        for (var participant : roster.participants()) {
          plannedLaborPerDay =
              Math.addExact(plannedLaborPerDay, participant.plannedLaborMilliPerDay());
        }
        anyLaborFact = true;
      }
      if (!anyLaborFact && unit.cycleLaborMilli() > 0L) {
        plannedLaborPerDay = unit.cycleLaborMilli() / days;
        anyLaborFact = true;
      }
      if (anyLaborFact) {
        laborScale = plannedLaborPerDay / industry.recipe().laborPerUnit();
      }
    }

    boolean anyInputFact = false;
    long inputScale = Long.MAX_VALUE;
    Map<io.mosire.simos.economy.api.id.CommodityId, Long> inputs = unit.cycleInputUsedMilli();
    for (Map.Entry<io.mosire.simos.economy.api.id.CommodityId, Long> line :
        industry.recipe().inputPerUnit().entrySet()) {
      long perUnit = line.getValue() == null ? 0L : line.getValue();
      if (perUnit <= 0L) {
        continue;
      }
      long drawn = inputs.getOrDefault(line.getKey(), 0L);
      if (drawn <= 0L) {
        // 实例累计投入为空/该路尚未扣料 = “本 tick 之前没有投入事实”，不能据此把计划规模压成 0；
        // 真正的投入约束由执行段 finalInputsMilli 再夹。多实例比例按可得的劳动/正投入事实算。
        continue;
      }
      anyInputFact = true;
      inputScale = Math.min(inputScale, (drawn / days) / perUnit);
    }
    if (!anyLaborFact && !anyInputFact) {
      return OptionalLong.empty();
    }
    long scale =
        Math.min(
            anyLaborFact ? laborScale : Long.MAX_VALUE, anyInputFact ? inputScale : Long.MAX_VALUE);
    return OptionalLong.of(scale == Long.MAX_VALUE ? 0L : Math.max(0L, scale));
  }
}
