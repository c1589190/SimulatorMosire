package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionOrganization;
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
 * 的可用资产/产能规模"（AssetShare 聚合）、"这个 unit 的家户行"（HouseholdLaborCommitment 反查）、"这一格有哪些 unit"、 "这家户供给哪些
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
 * 的身份与份额在一天内不被本切片修改，故 资产/产能/格索引一次构建即可；{@code HouseholdLaborCommitment} 会在劳动再分配与饿死缩放中被改写，故 {@link #withLabor}
 * 在改写的 阶段边界重建“配额侧”的视图；{@code DebtContract} 会在借粮/偿还/计息中被改写，故 {@link #withDebtContracts}
 * 在债务阶段边界重建债务视图。 这两次重建都仍是“每阶段一次”，不是逐查询一次 —— 语义由 {@link #build} 中逐字保留的旧遍历序保证。
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
  private final Map<ProductionUnitId, List<HouseholdLaborCommitment>> laborCommitmentsByUnit;
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
  private final Map<HouseholdId, List<DebtContract>> debtsByDebtor;

  // ── E4c/P2：主体 → 相关生产 unit / 生产组织（只读；旧 DebtPartyResolver 解析器已随旧结算引擎删除）────────
  private final Map<ActorRef, List<ProductionUnitId>> unitsByParty;
  private final Map<ActorRef, List<ProductionOrganization>> organizationsByActor;

  private SettlementIndex(
      Map<ProductionUnitId, Map<AssetKind, Long>> usableAssetsByUnit,
      Map<ProductionUnitId, Long> capacityScaleByUnit,
      Map<ProductionUnitId, List<AssetShareId>> assetShareIdsByUnit,
      Map<ProductionUnitId, HouseholdId> economicHouseholdByUnit,
      Map<ProductionUnitId, List<HouseholdLaborCommitment>> laborCommitmentsByUnit,
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
      Map<HouseholdId, List<DebtContract>> debtsByDebtor,
      Map<ActorRef, List<ProductionUnitId>> unitsByParty,
      Map<ActorRef, List<ProductionOrganization>> organizationsByActor) {
    this.usableAssetsByUnit = usableAssetsByUnit;
    this.capacityScaleByUnit = capacityScaleByUnit;
    this.assetShareIdsByUnit = assetShareIdsByUnit;
    this.economicHouseholdByUnit = economicHouseholdByUnit;
    this.laborCommitmentsByUnit = laborCommitmentsByUnit;
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
    this.unitsByParty = unitsByParty;
    this.organizationsByActor = organizationsByActor;
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
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      Map<ProductionUnitId, ProductionRelation> relations) {
    return build(units, industries, assetShares, laborCommitments, householdEconomies, debts, relations, Map.of());
  }

  /**
   * ★★ <b>P2：带生产组织只读视图的构建重载</b>。旧调用点（不关心组织的主体解析）继续走上面的重载， 行为逐值不变；日结算入口传 {@code
   * session.sheet().productionOrganizations()}，让今天的 E2 组织也能被旧 DebtPartyResolver 的同类解析看见。
   */
  static SettlementIndex build(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<AssetShareId, AssetShare> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(laborCommitments, "allocations");
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(organizations, "organizations");

    Map<ProductionUnitId, Map<AssetKind, Long>> usable = usableAssets(units, assetShares);
    Map<ProductionUnitId, Long> capacity = capacityScale(units, industries, usable);
    Map<ProductionUnitId, List<AssetShareId>> shareIds = assetShareIdsByUnit(units, assetShares);
    Map<ActorRef, HouseholdId> householdOfActor = householdByActor(householdEconomies);
    return new SettlementIndex(
        usable,
        capacity,
        shareIds,
        economicHouseholds(units, relations, assetShares, shareIds, householdOfActor),
        laborCommitmentsByUnit(laborCommitments),
        allocationIdsByUnit(laborCommitments),
        allocationIdsByGroup(laborCommitments),
        householdsByUnit(laborCommitments, householdEconomies),
        unitsByHousehold(units, laborCommitments, householdEconomies),
        unitsByGroup(units, laborCommitments),
        laborByUnit(laborCommitments),
        householdOfActor,
        unitsByHex(units),
        hexByUnit(units),
        industriesByHex(industries),
        debtsByDebtor(debts),
        unitsByParty(units, relations, assetShares, shareIds),
        organizationsByActor(organizations, units));
  }

  /** ★ 劳动配额被改写之后的阶段边界视图：共享资产/格/产业/债务，只重建配额侧派生量。 */
  SettlementIndex withLabor(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(laborCommitments, "allocations");
    return new SettlementIndex(
        usableAssetsByUnit,
        capacityScaleByUnit,
        assetShareIdsByUnit,
        economicHouseholdByUnit,
        laborCommitmentsByUnit(laborCommitments),
        allocationIdsByUnit(laborCommitments),
        allocationIdsByGroup(laborCommitments),
        householdsByUnit(laborCommitments, householdEconomies),
        unitsByHousehold(units, laborCommitments, householdEconomies),
        unitsByGroup(units, laborCommitments),
        laborByUnit(laborCommitments),
        householdByActor,
        unitsByHex,
        hexByUnit,
        industriesByHex,
        debtsByDebtor,
        unitsByParty,
        organizationsByActor);
  }

  /** ★ 债务被改写之后的阶段边界视图：共享资产/劳动/格/产业，只重建债务人索引。 */
  SettlementIndex withDebtContracts(Map<DebtContractId, DebtContract> debts) {
    Objects.requireNonNull(debts, "debts");
    return new SettlementIndex(
        usableAssetsByUnit,
        capacityScaleByUnit,
        assetShareIdsByUnit,
        economicHouseholdByUnit,
        laborCommitmentsByUnit,
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
        debtsByDebtor(debts),
        unitsByParty,
        organizationsByActor);
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

  List<HouseholdLaborCommitment> allocationsOfUnit(ProductionUnitId unit) {
    return laborCommitmentsByUnit.getOrDefault(unit, List.of());
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

  /**
   * ★★ <b>P2：某个主体关联到的生产 unit 列表</b>（operator / relation.operator / relation.residualOwner /
   * relation.inputSupplier(ToActor) / 份额 owner / 份额 operator 六路命中；序 = unit 表首次出现序）。
   *
   * <p>只读查询；空白主体（不在任何 unit 里）⇒ 空表。调用方用它回答"这个 actor 在哪些 unit 里出现"。
   */
  List<ProductionUnitId> unitsRelatedTo(ActorRef actor) {
    return unitsByParty.getOrDefault(actor, List.of());
  }

  /**
   * ★★ <b>P2：某个主体关联到的生产组织列表</b>（{@code organizer == actor} 或对应 unit 的 {@code operator == actor}；序 =
   * 组织表首次出现序）。只读查询；空白 ⇒ 空表。
   */
  List<ProductionOrganization> organizationsOf(ActorRef actor) {
    return organizationsByActor.getOrDefault(actor, List.of());
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

  Map<HouseholdId, List<DebtContract>> debtsByDebtor() {
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
  private static Map<ProductionUnitId, List<HouseholdLaborCommitment>> laborCommitmentsByUnit(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Map<ProductionUnitId, List<HouseholdLaborCommitment>> rawLaborCommitment = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      rawLaborCommitment.computeIfAbsent(new ProductionUnitId(laborCommitment.activity()), ignored -> new ArrayList<>())
          .add(laborCommitment);
    }
    Map<ProductionUnitId, List<HouseholdLaborCommitment>> frozenLaborCommitments = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, List<HouseholdLaborCommitment>> laborCommitmentEntry : rawLaborCommitment.entrySet()) {
      frozenLaborCommitments.put(laborCommitmentEntry.getKey(), List.copyOf(laborCommitmentEntry.getValue()));
    }
    return Collections.unmodifiableMap(frozenLaborCommitments);
  }

  /** 配额 id 快照；缩放路径必须拿 id 回活表取当前值，不能缓存 record（可能已被改写）。 */
  private static Map<ProductionUnitId, List<LaborAllocationId>> allocationIdsByUnit(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Map<ProductionUnitId, List<LaborAllocationId>> raw = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      raw.computeIfAbsent(new ProductionUnitId(laborCommitment.activity()), ignored -> new ArrayList<>())
          .add(laborCommitment.id());
    }
    Map<ProductionUnitId, List<LaborAllocationId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, List<LaborAllocationId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 批次 → 该批次配额 id（人口回写缩放按批次查，不再每次扫全表）。 */
  private static Map<PeopleLotId, List<LaborAllocationId>> allocationIdsByGroup(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Map<PeopleLotId, List<LaborAllocationId>> raw = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      raw.computeIfAbsent(laborCommitment.group(), ignored -> new ArrayList<>()).add(laborCommitment.id());
    }
    Map<PeopleLotId, List<LaborAllocationId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, List<LaborAllocationId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /**
   * unit → 家户行（旧 旧结算引擎的 householdKeysOf（R3a 已删除） 的精确口径：按 activity 去重、只留真有行的、按 HouseholdId.value
   * 升序）。
   */
  private static Map<ProductionUnitId, List<HouseholdId>> householdsByUnit(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments, Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<ProductionUnitId, LinkedHashSet<HouseholdId>> raw = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      raw.computeIfAbsent(
              new ProductionUnitId(laborCommitment.activity()), ignored -> new LinkedHashSet<>())
          .add(laborCommitment.household());
    }
    Map<ProductionUnitId, List<HouseholdId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, LinkedHashSet<HouseholdId>> entry : raw.entrySet()) {
      List<HouseholdId> keys = new ArrayList<>();
      for (HouseholdId household : entry.getValue()) {
        if (householdEconomies.containsKey(household)) {
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
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<HouseholdId, LinkedHashSet<ProductionUnitId>> raw = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
      if (!units.containsKey(unitId) || !householdEconomies.containsKey(laborCommitment.household())) {
        continue;
      }
      raw.computeIfAbsent(laborCommitment.household(), ignored -> new LinkedHashSet<>()).add(unitId);
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
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Map<PeopleLotId, LinkedHashSet<ProductionUnitId>> raw = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
      if (!units.containsKey(unitId)) {
        continue;
      }
      raw.computeIfAbsent(laborCommitment.group(), ignored -> new LinkedHashSet<>()).add(unitId);
    }
    Map<PeopleLotId, List<ProductionUnitId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, LinkedHashSet<ProductionUnitId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 旧 {@code laborByUnit}：activity → Σ laborMilli；键序 = 首次出现序（调用方只按值查，不依赖键序）。 */
  private static Map<String, Long> laborByUnit(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Map<String, Long> byUnit = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      byUnit.merge(laborCommitment.activity(), laborCommitment.laborMilli(), Long::sum);
    }
    return Collections.unmodifiableMap(byUnit);
  }

  /** 旧 {@code householdActorsOf}：家户 actor → 家户身份（键序 = 行表序）。 */
  private static Map<ActorRef, HouseholdId> householdByActor(Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<ActorRef, HouseholdId> byActor = new LinkedHashMap<>();
    for (HouseholdId key : householdEconomies.keySet()) {
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

  /** 格 → 该格产业（序 = 产业表序；与 {@code 旧结算引擎（R3a 已删除）.industriesByHexMap} 逐值同）。 */
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
  private static Map<HouseholdId, List<DebtContract>> debtsByDebtor(
      Map<DebtContractId, DebtContract> debts) {
    if (debts == null || debts.isEmpty()) {
      return Map.of();
    }
    Map<HouseholdId, List<DebtContract>> raw = new LinkedHashMap<>();
    for (DebtContract debt : debts.values()) {
      raw.computeIfAbsent(debt.debtor(), ignored -> new ArrayList<>()).add(debt);
    }
    Map<HouseholdId, List<DebtContract>> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, List<DebtContract>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /**
   * ★★ <b>P2：主体 → 它在哪些 unit 里出现</b>（只读派生）。
   *
   * <p>命中集合固定为：{@code unit.operator}、{@code relation.operator}、{@code relation.residualOwner}、
   * {@code relation.inputSupplier(ToActor)}、该 unit 名下 AssetShare 的 {@code owner} / {@code
   * operator}。 一条 unit 对同一 actor 只记一次；最终 List 的序 = unit 表首次出现序（可复现）。
   *
   * <p>★ 为什么把份额 owner/operator 也收进来：E1 的解析顺序里份额是第③档，而 {@code AssetShare} 的归属判据是 {@code (industry,
   * operator)} 作用域 —— 同一作用域下的每个 unit 拿到同一串份额，故这里逐 unit 展开， 与 {@link #assetShareIdsByUnit} 的口径保持一致。
   */
  private static Map<ActorRef, List<ProductionUnitId>> unitsByParty(
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<AssetShareId, AssetShare> assetShares,
      Map<ProductionUnitId, List<AssetShareId>> shareIdsByUnit) {
    Map<ActorRef, LinkedHashSet<ProductionUnitId>> raw = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      addPartyUnit(raw, unit.operator(), unit.id());
      ProductionRelation relation = relations.get(unit.id());
      if (relation != null) {
        addPartyUnit(raw, relation.operator(), unit.id());
        addPartyUnit(raw, relation.residualOwner(), unit.id());
        if (relation.inputSupplier() instanceof Recipient.ToActor toActor) {
          addPartyUnit(raw, toActor.actor(), unit.id());
        }
      }
      for (AssetShareId shareId : shareIdsByUnit.getOrDefault(unit.id(), List.of())) {
        AssetShare share = assetShares.get(shareId);
        if (share == null) {
          continue;
        }
        addPartyUnit(raw, share.owner(), unit.id());
        addPartyUnit(raw, share.operator(), unit.id());
      }
    }
    Map<ActorRef, List<ProductionUnitId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, LinkedHashSet<ProductionUnitId>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 同一 (actor, unit) 只记一次（保序）。 */
  private static void addPartyUnit(
      Map<ActorRef, LinkedHashSet<ProductionUnitId>> raw, ActorRef actor, ProductionUnitId unit) {
    if (actor == null || unit == null) {
      return;
    }
    raw.computeIfAbsent(actor, ignored -> new LinkedHashSet<>()).add(unit);
  }

  /**
   * ★★ <b>P2：主体 → 关联生产组织</b>（{@code organizer == actor} 或组织对应 unit 的 {@code operator == actor}；同一
   * actor 只记一次，序 = 组织表首次出现序）。
   *
   * <p>它服务旧 DebtPartyResolver 的聚合主体解析（R3a 已删除）：优先用 E2 自动组织登记的 {@code laborSources} / 家户归属，
   * 而不是直接按阶层人口猜。
   */
  private static Map<ActorRef, List<ProductionOrganization>> organizationsByActor(
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      Map<ProductionUnitId, ProductionUnit> units) {
    Map<ActorRef, LinkedHashSet<ProductionOrganization>> raw = new LinkedHashMap<>();
    for (ProductionOrganization organization : organizations.values()) {
      addOrganizationActor(raw, organization.organizer(), organization);
      organization
          .unitId()
          .map(units::get)
          .ifPresent(unit -> addOrganizationActor(raw, unit.operator(), organization));
    }
    Map<ActorRef, List<ProductionOrganization>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, LinkedHashSet<ProductionOrganization>> entry : raw.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  /** 同一 (actor, 组织) 只记一次（保序）。 */
  private static void addOrganizationActor(
      Map<ActorRef, LinkedHashSet<ProductionOrganization>> raw,
      ActorRef actor,
      ProductionOrganization organization) {
    if (actor == null || organization == null) {
      return;
    }
    raw.computeIfAbsent(actor, ignored -> new LinkedHashSet<>()).add(organization);
  }
}
