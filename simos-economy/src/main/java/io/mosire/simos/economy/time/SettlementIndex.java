package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>一次日结算（或一批人口回写）内只读的派生索引</b>（R4-B.3a-perf）。
 *
 * <p>★★ <b>为什么要有它</b>：结算里有几组问题每天、每个市场轮、每个 unit 都要回答很多次，而它们的答案在一天内不变 —— "这个 unit
 * 的可用资产/产能规模"（AssetShare 聚合）、"这个 unit 的家户行"（LaborAllocation 反查）、"这一格有哪些 unit"、 "这家户供给哪些
 * unit"、"每个债务人的债务"、"这一格有哪些产业"。旧实现每次现扫全表：真档新世界 8,940 unit × 44,564 配额、8,940 unit × 8,940 份额，把 0→120
 * 推到 752 秒。索引把这些答案在入口算一次，结算全程只查表。
 *
 * <p>★★ <b>它不是状态</b>：不进 {@link io.mosire.simos.economy.EconomyData}、不进 ChangeSet、不进 Codec，也不跨
 * revision 存活；一次 {@code settleOneDayInto}/{@code applyPopulationChangeInto} 构建一次，方法返回后即可被 GC。
 *
 * <p>★★ <b>并发安全</b>：所有字段在构造后不再变化；每个 {@code Map}/{@code List}/{@code Set} 都是不可修改视图， worker
 * 只读同一份快照。索引在协调器线程构建；worker 不持有可写结构。
 *
 * <p>★★ <b>与状态变更的关系</b>：{@code AssetShare}/{@code Industry}/{@code ProductionUnit}
 * 的身份与份额在一天内不被本切片修改，故 资产/产能/格索引一次构建即可；{@code LaborAllocation} 会在劳动再分配与饿死缩放中被改写，故 {@link #withLabor}
 * 在改写的 阶段边界重建“配额侧”的视图；{@code Debt} 会在借粮/偿还/计息中被改写，故 {@link #withDebts} 在债务阶段边界重建债务视图。
 * 这两次重建都仍是“每阶段一次”，不是逐查询一次 —— 语义由 {@link #build} 中逐字保留的旧遍历序保证。
 */
final class SettlementIndex {

  /** 资产聚合键：{@code ProductionUnitBook.usableAssets} 的归属判据是 (industry, operator)，不是 unit id。 */
  private record AssetScope(IndustryId industry, ActorRef operator) {}

  // ── 资产生成 ─────────────────────────────────────────────────────────────────────────
  private final Map<ProductionUnitId, Map<AssetKind, Long>> usableAssetsByUnit;
  private final Map<ProductionUnitId, Long> capacityScaleByUnit;
  private final Map<ProductionUnitId, List<AssetShareId>> assetShareIdsByUnit;

  // ── 经营者 → 关联经济家户（E1：唯一解析结果的索引化快照）────────────────────────────
  private final Map<ProductionUnitId, HouseholdId> economicHouseholdByUnit;

  // ── 劳动 / 家户 ─────────────────────────────────────────────────────────────────────
  private final Map<ProductionUnitId, List<LaborAllocation>> allocationsByUnit;
  private final Map<ProductionUnitId, List<LaborAllocationId>> allocationIdsByUnit;
  private final Map<PeopleLotId, List<LaborAllocationId>> allocationIdsByGroup;
  private final Map<ProductionUnitId, List<HouseholdId>> householdsByUnit;
  private final Map<HouseholdId, Set<ProductionUnitId>> unitsByHousehold;
  private final Map<PeopleLotId, List<ProductionUnitId>> unitsByGroup;
  private final Map<String, Long> laborByUnit;
  private final Map<ActorRef, HouseholdId> householdByActor;

  // ── 格 / 产业 / 债务 ────────────────────────────────────────────────────────────────
  private final Map<String, List<ProductionUnitId>> unitsByHex;
  private final Map<ProductionUnitId, String> hexByUnit;
  private final Map<String, List<IndustryId>> industriesByHex;
  private final Map<HouseholdId, List<Debt>> debtsByDebtor;

  private SettlementIndex(
      Map<ProductionUnitId, Map<AssetKind, Long>> usableAssetsByUnit,
      Map<ProductionUnitId, Long> capacityScaleByUnit,
      Map<ProductionUnitId, List<AssetShareId>> assetShareIdsByUnit,
      Map<ProductionUnitId, HouseholdId> economicHouseholdByUnit,
      Map<ProductionUnitId, List<LaborAllocation>> allocationsByUnit,
      Map<ProductionUnitId, List<LaborAllocationId>> allocationIdsByUnit,
      Map<PeopleLotId, List<LaborAllocationId>> allocationIdsByGroup,
      Map<ProductionUnitId, List<HouseholdId>> householdsByUnit,
      Map<HouseholdId, Set<ProductionUnitId>> unitsByHousehold,
      Map<PeopleLotId, List<ProductionUnitId>> unitsByGroup,
      Map<String, Long> laborByUnit,
      Map<ActorRef, HouseholdId> householdByActor,
      Map<String, List<ProductionUnitId>> unitsByHex,
      Map<ProductionUnitId, String> hexByUnit,
      Map<String, List<IndustryId>> industriesByHex,
      Map<HouseholdId, List<Debt>> debtsByDebtor) {
    this.usableAssetsByUnit = usableAssetsByUnit;
    this.capacityScaleByUnit = capacityScaleByUnit;
    this.assetShareIdsByUnit = assetShareIdsByUnit;
    this.economicHouseholdByUnit = economicHouseholdByUnit;
    this.allocationsByUnit = allocationsByUnit;
    this.allocationIdsByUnit = allocationIdsByUnit;
    this.allocationIdsByGroup = allocationIdsByGroup;
    this.householdsByUnit = householdsByUnit;
    this.unitsByHousehold = unitsByHousehold;
    this.unitsByGroup = unitsByGroup;
    this.laborByUnit = laborByUnit;
    this.householdByActor = householdByActor;
    this.unitsByHex = unitsByHex;
    this.hexByUnit = hexByUnit;
    this.industriesByHex = industriesByHex;
    this.debtsByDebtor = debtsByDebtor;
  }

  /**
   * ★ <b>构建一份索引</b>。{@code debts} 允许传 {@code null}（人口回写路径不需要债务视图 ⇒ 不强迫拷贝债务表）。
   *
   * <p>★ 输入的 Map 都按“状态插入序”遍历；据此得到的派生表顺序与旧实现逐处相同（例如 {@code AllocationActivity} 列表按全局配额表序、{@code
   * unitsByHex} 按 unit 表序）。这一点是 A/B 逐值等价的一部分，注释在 各 helper 上。
   */
  static SettlementIndex build(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<AssetShareId, AssetShare> assetShares,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<HouseholdId, ClassRow> rows,
      Map<DebtId, Debt> debts,
      Map<ProductionUnitId, ProductionRelation> relations) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(allocations, "allocations");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(relations, "relations");

    Map<ProductionUnitId, Map<AssetKind, Long>> usable = usableAssets(units, assetShares);
    Map<ProductionUnitId, Long> capacity = capacityScale(units, industries, usable);
    Map<ProductionUnitId, List<AssetShareId>> shareIds = assetShareIdsByUnit(units, assetShares);
    Map<ActorRef, HouseholdId> householdOfActor = householdByActor(rows);
    return new SettlementIndex(
        usable,
        capacity,
        shareIds,
        economicHouseholds(units, relations, assetShares, shareIds, householdOfActor),
        allocationsByUnit(allocations),
        allocationIdsByUnit(allocations),
        allocationIdsByGroup(allocations),
        householdsByUnit(allocations, rows),
        unitsByHousehold(units, allocations, rows),
        unitsByGroup(units, allocations),
        laborByUnit(allocations),
        householdOfActor,
        unitsByHex(units),
        hexByUnit(units),
        industriesByHex(industries),
        debtsByDebtor(debts));
  }

  /** ★ 劳动配额被改写之后的阶段边界视图：共享资产/格/产业/债务，只重建配额侧派生量。 */
  SettlementIndex withLabor(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HouseholdId, ClassRow> rows,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(allocations, "allocations");
    return new SettlementIndex(
        usableAssetsByUnit,
        capacityScaleByUnit,
        assetShareIdsByUnit,
        economicHouseholdByUnit,
        allocationsByUnit(allocations),
        allocationIdsByUnit(allocations),
        allocationIdsByGroup(allocations),
        householdsByUnit(allocations, rows),
        unitsByHousehold(units, allocations, rows),
        unitsByGroup(units, allocations),
        laborByUnit(allocations),
        householdByActor,
        unitsByHex,
        hexByUnit,
        industriesByHex,
        debtsByDebtor);
  }

  /** ★ 债务被改写之后的阶段边界视图：共享资产/劳动/格/产业，只重建债务人索引。 */
  SettlementIndex withDebts(Map<DebtId, Debt> debts) {
    Objects.requireNonNull(debts, "debts");
    return new SettlementIndex(
        usableAssetsByUnit,
        capacityScaleByUnit,
        assetShareIdsByUnit,
        economicHouseholdByUnit,
        allocationsByUnit,
        allocationIdsByUnit,
        allocationIdsByGroup,
        householdsByUnit,
        unitsByHousehold,
        unitsByGroup,
        laborByUnit,
        householdByActor,
        unitsByHex,
        hexByUnit,
        industriesByHex,
        debtsByDebtor(debts));
  }

  // ── 读口（一律 O(1) 查表；缺项返回空/0，与旧“无份额/无配额”口径同侧）────────────────────

  Map<AssetKind, Long> usableAssetsOf(ProductionUnit unit) {
    return usableAssetsByUnit.getOrDefault(unit.id(), Map.of());
  }

  Map<AssetKind, Long> usableAssetsOf(ProductionUnitId unit) {
    return usableAssetsByUnit.getOrDefault(unit, Map.of());
  }

  Long capacityScaleOf(ProductionUnitId unit) {
    return capacityScaleByUnit.get(unit);
  }

  /** 某个 unit 名下（{@code industry + operator} 作用域）的份额 id 快照（序 = 全局份额表序）。 */
  List<AssetShareId> assetShareIdsOfUnit(ProductionUnitId unit) {
    return assetShareIdsByUnit.getOrDefault(unit, List.of());
  }

  /**
   * ★★ <b>E1：某个 unit 解析出的关联经济家户</b>（{@link EconomicHouseholdResolver} 的唯一结果；解析不到 ⇒ {@link
   * Optional#empty()}，不伪造）。
   */
  Optional<HouseholdId> economicHouseholdOf(ProductionUnitId unit) {
    return Optional.ofNullable(economicHouseholdByUnit.get(unit));
  }

  List<HouseholdId> householdsOf(ProductionUnitId unit) {
    return householdsByUnit.getOrDefault(unit, List.of());
  }

  List<LaborAllocation> allocationsOfUnit(ProductionUnitId unit) {
    return allocationsByUnit.getOrDefault(unit, List.of());
  }

  List<LaborAllocationId> allocationIdsOfUnit(ProductionUnitId unit) {
    return allocationIdsByUnit.getOrDefault(unit, List.of());
  }

  List<LaborAllocationId> allocationIdsOfGroup(PeopleLotId group) {
    return allocationIdsByGroup.getOrDefault(group, List.of());
  }

  Map<HouseholdId, Set<ProductionUnitId>> unitsByHousehold() {
    return unitsByHousehold;
  }

  Map<PeopleLotId, List<ProductionUnitId>> unitsByGroup() {
    return unitsByGroup;
  }

  Map<String, Long> laborByUnit() {
    return laborByUnit;
  }

  Map<ActorRef, HouseholdId> householdByActor() {
    return householdByActor;
  }

  List<ProductionUnitId> unitsInHex(String hexKey) {
    return unitsByHex.getOrDefault(hexKey, List.of());
  }

  String hexOf(ProductionUnitId unit) {
    return hexByUnit.get(unit);
  }

  Map<String, List<IndustryId>> industriesByHex() {
    return industriesByHex;
  }

  Map<HouseholdId, List<Debt>> debtsByDebtor() {
    return debtsByDebtor;
  }

  // ── 构建（静态 helper；顺序口径都写在注释里）──────────────────────────────────────────

  /**
   * 从 AssetShare 一次聚合出逐 unit 可用资产。
   *
   * <p>★ 一个 (industry, operator) 可以有多个 unit —— 旧 {@code usableAssets} 每次按 unit 的 (industry,
   * operator) 现扫，故同 scope 的每个 unit 得到同一张表；这里一次算好、按 unit 各存一份。
   */
  private static Map<ProductionUnitId, Map<AssetKind, Long>> usableAssets(
      Map<ProductionUnitId, ProductionUnit> units, Map<AssetShareId, AssetShare> assetShares) {
    Map<AssetScope, List<ProductionUnitId>> unitsByScope = new LinkedHashMap<>();
    Map<ProductionUnitId, Map<AssetKind, Long>> raw = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      // ★ 每个 unit 都在表里（无份额 ⇒ 空表），与旧实现的“无份额 ⇒ 空表”逐值相同。
      raw.put(unit.id(), new LinkedHashMap<>());
      unitsByScope
          .computeIfAbsent(
              new AssetScope(unit.industry(), unit.operator()), ignored -> new ArrayList<>())
          .add(unit.id());
    }
    for (AssetShare share : assetShares.values()) {
      List<ProductionUnitId> scoped =
          unitsByScope.get(new AssetScope(share.industry(), share.operator()));
      if (scoped == null) {
        continue; // 没有 unit 用这个 (industry, operator) ⇒ 旧实现也不会命中任何 unit
      }
      for (ProductionUnitId id : scoped) {
        raw.get(id).merge(share.asset(), share.quantity(), Math::addExact);
      }
    }
    Map<ProductionUnitId, Map<AssetKind, Long>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Map<AssetKind, Long>> entry : raw.entrySet()) {
      frozen.put(
          entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /**
   * 逐 unit 的份额 id（{@code (industry, operator)} 作用域，序 = 全局份额表序）。
   *
   * <p>★ 与 {@link #usableAssets} 同一套作用域判定：一个作用域下的每个 unit 都拿到同一串 id（同 scope 多 unit 是合法形态）； 退出处置用 id
   * 回活表取当前行，绝不缓存 record（处置会改 {@code operator}）。
   */
  private static Map<ProductionUnitId, List<AssetShareId>> assetShareIdsByUnit(
      Map<ProductionUnitId, ProductionUnit> units, Map<AssetShareId, AssetShare> assetShares) {
    Map<AssetScope, List<ProductionUnitId>> unitsByScope = new LinkedHashMap<>();
    Map<ProductionUnitId, List<AssetShareId>> raw = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      raw.put(unit.id(), new ArrayList<>());
      unitsByScope
          .computeIfAbsent(
              new AssetScope(unit.industry(), unit.operator()), ignored -> new ArrayList<>())
          .add(unit.id());
    }
    for (AssetShare share : assetShares.values()) {
      List<ProductionUnitId> scoped =
          unitsByScope.get(new AssetScope(share.industry(), share.operator()));
      if (scoped == null) {
        continue;
      }
      for (ProductionUnitId id : scoped) {
        raw.get(id).add(share.id());
      }
    }
    Map<ProductionUnitId, List<AssetShareId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, List<AssetShareId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /**
   * ★★ <b>E1：逐 unit 的关联家户快照</b>——对每个 unit 调用 {@link EconomicHouseholdResolver#resolve} 一次（O(unit +
   * 份额)），结果在整天内共享（unit/relation/份额/行键集在日结算的处置之前不变）。解析不到 ⇒ 不进表。
   */
  private static Map<ProductionUnitId, HouseholdId> economicHouseholds(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<AssetShareId, AssetShare> assetShares,
      Map<ProductionUnitId, List<AssetShareId>> shareIdsByUnit,
      Map<ActorRef, HouseholdId> householdOfActor) {
    Map<ProductionUnitId, HouseholdId> raw = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      List<AssetShare> shares = new ArrayList<>();
      for (AssetShareId id : shareIdsByUnit.getOrDefault(unit.id(), List.of())) {
        AssetShare share = assetShares.get(id);
        if (share != null) {
          shares.add(share);
        }
      }
      EconomicHouseholdResolver.resolve(
              unit, relations.get(unit.id()), List.copyOf(shares), householdOfActor)
          .ifPresent(household -> raw.put(unit.id(), household));
    }
    return Collections.unmodifiableMap(raw);
  }

  /**
   * 逐 unit 产能规模 = 旧 {@code min over industry.capacityPerUnit: ⌊usable[k] ÷ capacityPerUnit[k]⌋}。
   * 缺产业模板的 unit 记 0（调用方在旧路径上会先因缺模板抛，这里不制造第二套公式）。
   */
  private static Map<ProductionUnitId, Long> capacityScale(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, Map<AssetKind, Long>> usable) {
    Map<ProductionUnitId, Long> scaleByUnit = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      Industry industry = industries.get(unit.industry());
      Map<AssetKind, Long> sums = usable.getOrDefault(unit.id(), Map.of());
      long scale = Long.MAX_VALUE;
      if (industry != null) {
        for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
          scale = Math.min(scale, sums.getOrDefault(entry.getKey(), 0L) / entry.getValue());
        }
      }
      scaleByUnit.put(unit.id(), industry == null ? 0L : (scale == Long.MAX_VALUE ? 0L : scale));
    }
    return Collections.unmodifiableMap(scaleByUnit);
  }

  /** 配额全量快照（旧 {@code householdKeysOf} 的“按 activity 归组”方向）；序 = 全局配额表序。 */
  private static Map<ProductionUnitId, List<LaborAllocation>> allocationsByUnit(
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<ProductionUnitId, List<LaborAllocation>> raw = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      raw.computeIfAbsent(new ProductionUnitId(allocation.activity()), ignored -> new ArrayList<>())
          .add(allocation);
    }
    Map<ProductionUnitId, List<LaborAllocation>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, List<LaborAllocation>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 配额 id 快照；缩放路径必须拿 id 回活表取当前值，不能缓存 record（可能已被改写）。 */
  private static Map<ProductionUnitId, List<LaborAllocationId>> allocationIdsByUnit(
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<ProductionUnitId, List<LaborAllocationId>> raw = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      raw.computeIfAbsent(new ProductionUnitId(allocation.activity()), ignored -> new ArrayList<>())
          .add(allocation.id());
    }
    Map<ProductionUnitId, List<LaborAllocationId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, List<LaborAllocationId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 批次 → 该批次配额 id（人口回写缩放按批次查，不再每次扫全表）。 */
  private static Map<PeopleLotId, List<LaborAllocationId>> allocationIdsByGroup(
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<PeopleLotId, List<LaborAllocationId>> raw = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      raw.computeIfAbsent(allocation.group(), ignored -> new ArrayList<>()).add(allocation.id());
    }
    Map<PeopleLotId, List<LaborAllocationId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, List<LaborAllocationId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /**
   * unit → 家户行（旧 {@link EconomySettlement#householdKeysOf} 的精确口径：按 activity 去重、只留真有行的、按
   * HouseholdId.value 升序）。
   */
  private static Map<ProductionUnitId, List<HouseholdId>> householdsByUnit(
      Map<LaborAllocationId, LaborAllocation> allocations, Map<HouseholdId, ClassRow> rows) {
    Map<ProductionUnitId, LinkedHashSet<HouseholdId>> raw = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      raw.computeIfAbsent(
              new ProductionUnitId(allocation.activity()), ignored -> new LinkedHashSet<>())
          .add(allocation.household());
    }
    Map<ProductionUnitId, List<HouseholdId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, LinkedHashSet<HouseholdId>> entry : raw.entrySet()) {
      List<HouseholdId> keys = new ArrayList<>();
      for (HouseholdId household : entry.getValue()) {
        if (rows.containsKey(household)) {
          keys.add(household);
        }
      }
      keys.sort(Comparator.comparing(HouseholdId::value));
      frozen.put(entry.getKey(), List.copyOf(keys));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 家户 → 它供给的 unit 集合（只认现存 unit 与现存行；序 = 全局配额表首次出现序）。 */
  private static Map<HouseholdId, Set<ProductionUnitId>> unitsByHousehold(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<HouseholdId, ClassRow> rows) {
    Map<HouseholdId, LinkedHashSet<ProductionUnitId>> raw = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
      if (!units.containsKey(unitId) || !rows.containsKey(allocation.household())) {
        continue;
      }
      raw.computeIfAbsent(allocation.household(), ignored -> new LinkedHashSet<>()).add(unitId);
    }
    Map<HouseholdId, Set<ProductionUnitId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, LinkedHashSet<ProductionUnitId>> entry : raw.entrySet()) {
      frozen.put(
          entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 批次 → 它供给的 unit（旧 {@code unitsOfHouseholds} 的按 group 口径；序 = 全局配额首次出现序）。 */
  private static Map<PeopleLotId, List<ProductionUnitId>> unitsByGroup(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<PeopleLotId, LinkedHashSet<ProductionUnitId>> raw = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
      if (!units.containsKey(unitId)) {
        continue;
      }
      raw.computeIfAbsent(allocation.group(), ignored -> new LinkedHashSet<>()).add(unitId);
    }
    Map<PeopleLotId, List<ProductionUnitId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, LinkedHashSet<ProductionUnitId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 旧 {@code laborByUnit}：activity → Σ laborMilli；键序 = 首次出现序（调用方只按值查，不依赖键序）。 */
  private static Map<String, Long> laborByUnit(
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<String, Long> byUnit = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      byUnit.merge(allocation.activity(), allocation.laborMilli(), Long::sum);
    }
    return Collections.unmodifiableMap(byUnit);
  }

  /** 旧 {@code householdActorsOf}：家户 actor → 家户身份（键序 = 行表序）。 */
  private static Map<ActorRef, HouseholdId> householdByActor(Map<HouseholdId, ClassRow> rows) {
    Map<ActorRef, HouseholdId> byActor = new LinkedHashMap<>();
    for (HouseholdId key : rows.keySet()) {
      byActor.put(HouseholdActors.of(key), key);
    }
    return Collections.unmodifiableMap(byActor);
  }

  /** unit → 产业 id 里的格键（保序 = unit 表序；无格键的 unit 不进表，与旧 {@code hexKeyOf(...).ifPresent} 同）。 */
  private static Map<ProductionUnitId, String> hexByUnit(
      Map<ProductionUnitId, ProductionUnit> units) {
    Map<ProductionUnitId, String> byUnit = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      IndustryHexKeys.hexKeyOf(unit.industry()).ifPresent(hex -> byUnit.put(unit.id(), hex));
    }
    return Collections.unmodifiableMap(byUnit);
  }

  /** 格 → 该格 unit（序 = unit 表序；旧 {@code participantsFor}/{@code reallocateLabor} 的分组同此序）。 */
  private static Map<String, List<ProductionUnitId>> unitsByHex(
      Map<ProductionUnitId, ProductionUnit> units) {
    Map<String, List<ProductionUnitId>> raw = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      IndustryHexKeys.hexKeyOf(unit.industry())
          .ifPresent(hex -> raw.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(unit.id()));
    }
    Map<String, List<ProductionUnitId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<String, List<ProductionUnitId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 格 → 该格产业（序 = 产业表序；与 {@code EconomySettlement.industriesByHexMap} 逐值同）。 */
  private static Map<String, List<IndustryId>> industriesByHex(
      Map<IndustryId, Industry> industries) {
    Map<String, List<IndustryId>> raw = new LinkedHashMap<>();
    for (IndustryId id : industries.keySet()) {
      IndustryHexKeys.hexKeyOf(id)
          .ifPresent(hex -> raw.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(id));
    }
    Map<String, List<IndustryId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<String, List<IndustryId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 债务人 → 债务（序 = 债务表序；{@code OperatorSettlement.advance} 的旧过滤序同此）。 */
  private static Map<HouseholdId, List<Debt>> debtsByDebtor(Map<DebtId, Debt> debts) {
    if (debts == null || debts.isEmpty()) {
      return Map.of();
    }
    Map<HouseholdId, List<Debt>> raw = new LinkedHashMap<>();
    for (Debt debt : debts.values()) {
      raw.computeIfAbsent(debt.debtor(), ignored -> new ArrayList<>()).add(debt);
    }
    Map<HouseholdId, List<Debt>> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, List<Debt>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }
}
