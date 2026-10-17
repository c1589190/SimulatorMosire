package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
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
 * ★★ <b>生产单元的派生读口</b>（R3B.2）：从 {@code OwnershipStake} 实物总账<b>纯派生</b>一个 unit 的可用资产与规模，不落第二份状态。
 *
 * <p>★★ <b>为什么必须是派生</b>：{@code OwnershipStake} 是唯一的实物总账；若 unit 或 {@code IndustryDefinitionView}
 * 再存一份 {@code capacity}，两处就会漂开（旧代码正是这样：{@code 旧结算引擎（R3a 已删除）.capacityScaleOf} 读 {@code
 * IndustryDefinitionView.capacity}，而 {@code HouseholdClassRule} 另用 {@code OwnershipStake}
 * 近似）。本类只做算术，不含公式常量。
 *
 * <pre>
 * usableAssets(unit)   = Σ OwnershipStake{industry == unit.industry && operator == unit.operator}.quantity  按 AssetKind 汇总
 * capacityScaleOf      = min over k ∈ industry.capacityPerUnit: ⌊usableAssets[k] ÷ capacityPerUnit[k]⌋
 * plannedCapacityScale = capacityScaleOf × StressPolicy.plannedScalePerMille(condition.status)
 * </pre>
 *
 * <p>★ <b>缺项/空表的口径与旧实现逐字一致</b>（迁移期判据）：缺某种资产 ⇒ 该路读到 0 ⇒ 规模 0（"这一格/这个主体没有这类资产"）； {@code
 * capacityPerUnit} 空表 ⇒ 没有"单位规模"的锚 ⇒ 返回 0（旧 {@code capacityScaleOf} 同值）。
 */
public final class ProductionProcessBook {

  private ProductionProcessBook() {}

  /**
   * ★★ <b>一个 unit 在某产业上的可用实物资产</b>：{@code Σ OwnershipStake{industry==unit.industry &&
   * operator==unit.operator}} 按 {@link AssetKind} 汇总。
   *
   * <p>★ <b>只按 operator 汇总</b>（不是 owner）：规模问的是"这个经营者实际能用多少"；所有权只决定收益归属（关系规则）。
   * 旧档一对一迁移（owner==operator）下两者同值，与旧 {@code IndustryDefinitionView.capacity} 逐值相等。
   *
   * <p>★★ <b>R2 窄入口</b>：本重载直接扫描份额，只能用于<b>可证明 scope 内只有一个 unit</b> 的夹具/无索引入口。 生产日、预计、排队、执行、app
   * 读口和债务容量必须使用 {@link SettlementIndex} 或 {@link AssetBudgetBook.AssetBudget} 版本；无法证明单 unit
   * 时必须走索引版，不得用本方法猜。
   *
   * @param assetShares 实物总账（键序 = 状态插入序；返回表按份额首次出现序保序）
   * @return 按资产种类汇总的数量（只含出现过的种类；无份额 ⇒ 空表）
   */
  public static Map<AssetKind, Long> usableAssets(
      ProductionProcess unit, Map<AssetShareId, OwnershipStake> assetShares) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    for (OwnershipStake share : assetShares.values()) {
      if (!share.industry().equals(unit.industry()) || !share.operator().equals(unit.operator())) {
        continue;
      }
      sums.merge(share.asset(), share.quantity(), Math::addExact);
    }
    return sums;
  }

  /**
   * ★★ <b>索引口径的可用资产</b>（R4-B.3a-perf）：日结算入口已按 {@code (industry, operator)} 一次聚合，热路径只查表。
   *
   * <p>★ 与旧签名同值（旧路径逐份额现扫）；本重载只是把同一张表的来源换成 {@link SettlementIndex}，不做第二套算术。
   */
  public static Map<AssetKind, Long> usableAssets(ProductionProcess unit, SettlementIndex index) {
    return index.usableAssetsOf(unit);
  }

  /** ★★ R2：读一次派生资产预算中该 unit 的分配表（共享 scope ⇒ 空表）。 */
  public static Map<AssetKind, Long> usableAssets(
      ProductionProcess unit, AssetBudgetBook.AssetBudget budget) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(budget, "budget");
    return budget.allocationOf(unit.id());
  }

  /**
   * ★★ <b>按 {@code (industry, operator)} 直接聚合可用资产</b>（E2 生产组织阶段的只读入口）：还没有 {@code ProductionProcess}
   * 对象时（组织阶段要先生成/采用 unit 再谈规模），不得在前置阶段另写一份求和。
   *
   * <p>★ 判据与 {@link #usableAssets(ProductionProcess, Map)} 逐字相同：{@code industry} 与 {@code operator}
   * 双等值；唯一拼写点仍是这里。返回表按份额首次出现序保序。
   */
  public static Map<AssetKind, Long> usableAssets(
      IndustryId industry, ActorRef operator, Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(industry, "industry");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(assetShares, "assetShares");
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    for (OwnershipStake share : assetShares.values()) {
      if (!share.industry().equals(industry) || !share.operator().equals(operator)) {
        continue;
      }
      sums.merge(share.asset(), share.quantity(), Math::addExact);
    }
    return sums;
  }

  /**
   * ★★ <b>unit 的产能规模</b> = {@code min over k ∈ capacityPerUnit: ⌊usableAssets[k] ÷
   * capacityPerUnit[k]⌋}。
   *
   * <p>★ 与旧 {@code 旧结算引擎（R3a 已删除）.capacityScaleOf(IndustryDefinitionView)} 同式：缺资产 = 0，空表 =
   * 0，整数向下取整。
   */
  public static long capacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      Map<AssetShareId, OwnershipStake> assetShares) {
    return capacityScaleOf(usableAssets(unit, assetShares), industry);
  }

  /**
   * ★★ <b>索引口径的产能规模</b>（R4-B.3a-perf）：可用资产取一次聚合表；模板 {@code capacityPerUnit} 仍由调用方传入，
   * 算式与旧签名逐字相同（不复制第二份模板，也不改变缺项/取整口径）。
   */
  public static long capacityScaleOf(
      ProductionProcess unit, IndustryDefinitionView industry, SettlementIndex index) {
    return capacityScaleOf(index.usableAssetsOf(unit), industry);
  }

  /** ★★ R2：产能规模读一次派生资产预算的分配表（共享 scope ⇒ 0）。 */
  public static long capacityScaleOf(
      ProductionProcess unit, IndustryDefinitionView industry, AssetBudgetBook.AssetBudget budget) {
    return capacityScaleOf(usableAssets(unit, budget), industry);
  }

  /**
   * ★★ <b>按 {@code (industry, operator)} 直接算产能规模</b>（E2 生产组织阶段的只读入口）：与 {@link
   * #capacityScaleOf(ProductionProcess, IndustryDefinitionView, Map)} 共用同一个私有算式；"规模 =
   * 最紧的资产那一路"只有这一处。
   */
  public static long capacityScaleOf(
      IndustryId industryId,
      ActorRef operator,
      IndustryDefinitionView industry,
      Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(industryId, "industryId");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(industry, "industry");
    Objects.requireNonNull(assetShares, "assetShares");
    return capacityScaleOf(usableAssets(industryId, operator, assetShares), industry);
  }

  /** 产能规模的唯一算式（各重载共用；避免“索引版”和“扫描版”各写一份）。 */
  public static long capacityScaleOf(Map<AssetKind, Long> usable, IndustryDefinitionView industry) {
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      scale = Math.min(scale, usable.getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /**
   * ★★ 计划规模 = 产能规模 × {@link StressPolicy#plannedScalePerMille}（条件缺失 ⇒ ACTIVE ⇒ 1000‰ ⇒ 旧行为逐值相同）。
   */
  public static long plannedCapacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      Map<AssetShareId, OwnershipStake> assetShares,
      OperatorCondition condition) {
    return capacityScaleOf(unit, industry, assetShares)
        * StressPolicy.plannedScalePerMille(
            condition == null ? OperatorCondition.IndustryStatus.ACTIVE : condition.status())
        / 1_000L;
  }

  /** ★★ 索引口径的计划规模（产能规模查 {@link SettlementIndex}；计划系数的算式与旧签名逐字相同）。 */
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

  /** ★★ R2：计划规模读一次派生资产预算的分配表（共享 scope ⇒ 0，规划系数算式不变）。 */
  public static long plannedCapacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      AssetBudgetBook.AssetBudget budget,
      OperatorCondition condition) {
    return capacityScaleOf(unit, industry, budget)
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
