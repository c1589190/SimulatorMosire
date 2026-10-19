package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.HouseholdClassRule.Classification;
import static io.mosire.simos.economy.time.HouseholdClassRule.DEBT_STRESS_THRESHOLD_PER_MILLE;
import static io.mosire.simos.economy.time.HouseholdClassRule.SELF_CULTIVATION_THRESHOLD_MILLI_MU;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** 阶层投影/Index 实现（由 HouseholdClassRule.Index 抽取；行为不变）。 */

/**
 * ★★ <b>一次性索引</b>：把 {@code OwnershipStake} / 生产关系 / {@code HouseholdLaborCommitment} / 租规则 /
 * 债务折成逐家户可 O(1) 分类的判据。
 *
 * <p>★★ <b>为什么必须一次建索引</b>：关账日要对全部家户写回（真档 4,000+ 行），若每户都重扫全部关系与配额， 就是 O(行 × 关系 × 规则)
 * 的重复劳动；而且"同一事实只算一次"也要求这些派生量在一处生成。
 */
class ClassProjectionBook {

  final Map<HouseholdId, HouseholdEconomy> householdEconomies;

  /** 产业+经营者 → 该 (industry, operator) 的 unit 列表（份额事实折算到 unit 的桥；见 mergeDirectShare）。 */
  final Map<String, List<ProductionUnitId>> unitsByIndustryOperator = new LinkedHashMap<>();

  // ── 直接资产份额（owner/operator 能反解为本状态里的家户）────────────────────────────
  final Map<HouseholdId, Long> directRightQuantity = new LinkedHashMap<>();
  final Map<HouseholdId, Long> directLandMilliMu = new LinkedHashMap<>();
  final Map<HouseholdId, Long> directToolOrWorkshop = new LinkedHashMap<>();
  final Map<HouseholdId, Boolean> communalRight = new LinkedHashMap<>();
  final Map<HouseholdId, Set<ProductionUnitId>> directActivities = new LinkedHashMap<>();
  // ── 本户是 operator / inputSupplier / residualOwner 的产业（OwnershipStake 可能不在本户名下）────
  final Map<HouseholdId, Long> operatedLandMilliMu = new LinkedHashMap<>();
  final Map<HouseholdId, Long> operatedToolOrWorkshop = new LinkedHashMap<>();
  final Map<HouseholdId, Set<ProductionUnitId>> operatedActivities = new LinkedHashMap<>();
  // ── 租规则受方（只作 LANDLORD 辅助证据 + 推定控制活动）────────────────────────────────
  final Map<HouseholdId, Long> rentInferredLandMilliMu = new LinkedHashMap<>();
  final Map<HouseholdId, Boolean> rentEntitled = new LinkedHashMap<>();
  final Map<HouseholdId, Set<CompensationRule>> rentRulesByHousehold = new LinkedHashMap<>();
  final Map<CompensationRule, HouseholdId> rentRulePayee = new LinkedHashMap<>();
  final Set<CompensationRule> ambiguousRentRules = new LinkedHashSet<>();
  // ── 产出/投入占有（TENANT 的"占有部分投入/产出"判据）──────────────────────────────────
  final Map<HouseholdId, Set<ProductionUnitId>> productionStakeActivities = new LinkedHashMap<>();
  // ── 净劳动：控制的活动里"别人出的劳动"算雇入（真档的农场全部由庄园雇入）────────────────
  final Map<HouseholdId, Set<ProductionUnitId>> controlledActivities = new LinkedHashMap<>();
  final Map<HouseholdId, Long> laborSold = new LinkedHashMap<>();
  final Map<HouseholdId, Long> laborHired = new LinkedHashMap<>();
  final Map<HouseholdId, Map<LaborSource, Long>> laborBySource = new LinkedHashMap<>();
  final Map<HouseholdId, Boolean> tenantStake = new LinkedHashMap<>();
  // ── 原因字符串的证据（保序；只读口/审计使用，不参与分类算术）──────────────────────────
  final Map<HouseholdId, Map<String, Long>> laborEvidence = new LinkedHashMap<>();
  final Map<HouseholdId, Set<String>> roleEvidence = new LinkedHashMap<>();
  // ── 债务：本金合计（分类只用"本金 ÷ 资产数量"这一个比值）────────────────────────────
  final Map<HouseholdId, Long> debtPrincipal = new LinkedHashMap<>();

  /** ★ R4-B.3a-perf：结算入口的只读派生索引；{@code null} = 读口/测试的无索引窄入口。 */
  final SettlementIndex settlementIndex;

  /** 无 SettlementIndex 的读口所持同一 revision D4 预算；null = 不可用（读取资产时 fail-closed）。 */
  final AssetOccupancyBudget assetOccupancyBudget;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "派生索引构造期必须对 D4 资产预算缺失/状态坏形 fail-closed（assetAllocationOf 抛具名 IllegalStateException），失败即整批不产出索引；本类不定义 finalizer，具体子类只有包内 Index 壳且不暴露半成品 ⇒ finalizer 攻击面不存在")
  ClassProjectionBook(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      SettlementIndex settlementIndex,
      AssetOccupancyBudget assetOccupancyBudget) {
    this.settlementIndex = settlementIndex;
    this.assetOccupancyBudget = assetOccupancyBudget;
    this.householdEconomies = new LinkedHashMap<>(householdEconomies);
    for (ProductionProcess unit : units.values()) {
      unitsByIndustryOperator
          .computeIfAbsent(
              industryOperatorKey(unit.industry(), unit.operator()), ignored -> new ArrayList<>())
          .add(unit.id());
    }
    Map<ActorRef, HouseholdId> householdByActor = new LinkedHashMap<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      householdByActor.put(HouseholdActors.of(householdEconomy.id()), householdEconomy.id());
    }
    Map<CohortKey, HouseholdId> householdByView = new LinkedHashMap<>();
    Set<CohortKey> ambiguousViews = new LinkedHashSet<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      if (householdByView.putIfAbsent(householdEconomy.view(), householdEconomy.id()) != null) {
        ambiguousViews.add(householdEconomy.view());
      }
    }
    for (CohortKey view : ambiguousViews) {
      householdByView.remove(view);
    }
    // ★ S3 审计：这张 view 索引每次 HouseholdClassRule.Index 构造都从**当前** HouseholdEconomy 行集合重建，只服务旧档
    // ToCohort
    //   的兼容解析（默认关系已在 EconomyData 构造期归一为 ToHousehold 稳定身份）；S3 阶层写回后它不会
    //   被当成身份缓存，也不会让新阶层"漏行"。

    // ① 直接资产份额（R3B.1）：份额的 owner（所有权）与 operator（实际经营）任一能反解为本户，就计入本户。
    //    ★ 旧 holder 的读取按语义拆到这两栏：owner == operator（旧档一对一迁移/创世整额）时只计一次，不双计。
    for (OwnershipStake share : assetShares.values()) {
      HouseholdId owner = householdOf(share.owner(), householdEconomies, householdByActor);
      HouseholdId operator = householdOf(share.operator(), householdEconomies, householdByActor);
      if (owner != null) {
        mergeDirectShare(owner, share);
      }
      if (operator != null && !operator.equals(owner)) {
        mergeDirectShare(operator, share);
      }
    }

    // ② 生产关系：本户是 operator/inputSupplier/residualOwner 的产业（它的 OwnershipStake 可能在经营者 actor 名下），
    //    以及所有租规则受方。★ 这里**不**把森林式的"每个受方都是地主"折成所有权，只记事实。
    Map<ProductionUnitId, Set<HouseholdId>> rentPayeesByActivity = new LinkedHashMap<>();
    for (ProductionRules relation : relations.values()) {
      ProductionUnitId activity = relation.activity();
      HouseholdId operator = householdOf(relation.operator(), householdEconomies, householdByActor);
      if (operator != null) {
        operatedActivities(operator).add(activity);
        controlledActivities(operator).add(activity);
        roleEvidence(operator).add("operator@" + activity.value());
      }
      HouseholdId inputSupplier =
          payeeHousehold(
              relation.inputSupplier(), householdEconomies, householdByActor, householdByView);
      if (inputSupplier != null) {
        operatedActivities(inputSupplier).add(activity);
        controlledActivities(inputSupplier).add(activity);
        roleEvidence(inputSupplier).add("inputSupplier@" + activity.value());
      }
      HouseholdId residualOwner =
          householdOf(relation.residualOwner(), householdEconomies, householdByActor);
      if (residualOwner != null) {
        operatedActivities(residualOwner).add(activity);
        controlledActivities(residualOwner).add(activity);
        roleEvidence(residualOwner).add("residualOwner@" + activity.value());
      }
      for (CompensationRule rule : relation.rules()) {
        HouseholdId recipient =
            payeeHousehold(rule.recipient(), householdEconomies, householdByActor, householdByView);
        if (recipient == null) {
          continue;
        }
        if (isRentShaped(rule)) {
          rentEntitled.put(recipient, true);
          rentRulesByHousehold
              .computeIfAbsent(recipient, ignored -> new LinkedHashSet<>())
              .add(rule);
          rentPayeesByActivity
              .computeIfAbsent(activity, ignored -> new LinkedHashSet<>())
              .add(recipient);
          roleEvidence(recipient).add("rentRecipient@" + activity.value());
          HouseholdId previous = rentRulePayee.putIfAbsent(rule, recipient);
          if (previous != null && !previous.equals(recipient)) {
            ambiguousRentRules.add(rule);
          }
        } else {
          // ★ TENANT 的"占有部分投入/产出"判据：本户是该关系里非租形状规则的受方（自留/分成/劳动报酬）。
          productionStakeActivities(recipient).add(activity);
          roleEvidence(recipient).add("compensated@" + activity.value());
        }
      }
    }

    // ②a 经营身份对应的技术产能：即使 OwnershipStake 的 owner/operator 是经营者
    // actor，operator/inputSupplier/residualOwner
    //    的 LAND / SMALL_TOOL / WORKSHOP 也要算进该家户的分类资产（计划 §S3.4 "不得因权利不在本户名下直接判 landless"）。
    for (Map.Entry<HouseholdId, Set<ProductionUnitId>> entry : operatedActivities.entrySet()) {
      for (ProductionUnitId activity : entry.getValue()) {
        ProductionProcess unit = units.get(activity);
        if (unit == null) {
          continue;
        }
        // ★★ 经营身份对应的产能只读同一 revision 的 D4 budget allocation；不再读
        //   {@code IndustryDefinitionView.capacity}，也不扫描整池份额。
        for (Map.Entry<AssetKind, Long> usable : assetAllocationOf(unit).entrySet()) {
          if (usable.getValue() <= 0L) {
            continue;
          }
          if (usable.getKey() == AssetKind.LAND) {
            operatedLandMilliMu.merge(entry.getKey(), usable.getValue(), Math::addExact);
          } else if (usable.getKey() == AssetKind.SMALL_TOOL
              || usable.getKey() == AssetKind.WORKSHOP) {
            operatedToolOrWorkshop.merge(entry.getKey(), usable.getValue(), Math::addExact);
          }
        }
      }
    }

    // ②b 租权推定控制：地租受方按"该产业有几个受方"均分该产业的 LAND，只用于把租权受方的雇入劳动算进净劳动
    //    （真档一格一个地主，故就是整块地）；不写进 ownLand，避免再用"租受方=土地所有者"的旧近似。
    for (Map.Entry<ProductionUnitId, Set<HouseholdId>> entry : rentPayeesByActivity.entrySet()) {
      ProductionProcess unit = units.get(entry.getKey());
      if (unit == null || entry.getValue().isEmpty()) {
        continue;
      }
      long land = assetAllocationOf(unit).getOrDefault(AssetKind.LAND, 0L);
      if (land <= 0L) {
        continue;
      }
      long share = land / entry.getValue().size();
      for (HouseholdId household : entry.getValue()) {
        rentInferredLandMilliMu.merge(household, share, Math::addExact);
        controlledActivities(household).add(entry.getKey());
      }
    }

    // ③ 劳动：本户卖出的劳动 + 本户控制/经营产业里雇入的劳动（含直接以本户为雇主的配额）。
    Map<ProductionUnitId, Long> activityLaborTotal = new LinkedHashMap<>();
    Map<ProductionUnitId, Map<HouseholdId, Long>> activityLaborByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Map<ProductionUnitId, Long>> directHiredByActivity = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      HouseholdId household = laborCommitment.household();
      ProductionUnitId activity = new ProductionUnitId(laborCommitment.activity());
      if (!units.containsKey(activity)) {
        // ★ activity 不是现存 unit（自由家户劳动/旧档未接线档）：只进 laborSold（守恒/读口），不进按 unit 的派生表。
        laborSold.merge(household, laborCommitment.laborMilli(), Math::addExact);
        continue;
      }
      long laborMilli = laborCommitment.laborMilli();
      laborSold.merge(household, laborMilli, Math::addExact);
      activityLaborTotal.merge(activity, laborMilli, Math::addExact);
      activityLaborByHousehold
          .computeIfAbsent(activity, ignored -> new LinkedHashMap<>())
          .merge(household, laborMilli, Math::addExact);
      HouseholdId employer =
          householdOf(laborCommitment.actor(), householdEconomies, householdByActor);
      if (employer != null && !employer.equals(household)) {
        directHiredByActivity
            .computeIfAbsent(employer, ignored -> new LinkedHashMap<>())
            .merge(activity, laborMilli, Math::addExact);
      }
      ProductionRules relation = relations.get(activity);
      if (relation == null) {
        continue;
      }
      LaborSource source = relation.laborSource();
      laborBySource
          .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
          .merge(source, laborMilli, Math::addExact);
      laborEvidence
          .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
          .merge(source.name() + "@" + activity.value(), laborMilli, Math::addExact);
      if (source == LaborSource.TENANT
          && (productionStakeActivitiesOrEmpty(household).contains(activity)
              || directActivitiesOrEmpty(household).contains(activity)
              || operatedActivitiesOrEmpty(household).contains(activity))) {
        tenantStake.put(household, true);
      }
    }
    for (HouseholdId household : householdEconomies.keySet()) {
      long hired = 0L;
      Set<ProductionUnitId> controlled = controlledActivitiesOrEmpty(household);
      for (ProductionUnitId activity : controlled) {
        long total = activityLaborTotal.getOrDefault(activity, 0L);
        long self =
            activityLaborByHousehold.getOrDefault(activity, Map.of()).getOrDefault(household, 0L);
        long other = total - self;
        if (other > 0L) {
          hired += other;
        }
      }
      Map<ProductionUnitId, Long> direct = directHiredByActivity.getOrDefault(household, Map.of());
      for (Map.Entry<ProductionUnitId, Long> entry : direct.entrySet()) {
        if (!controlled.contains(entry.getKey())) {
          hired += entry.getValue();
        }
      }
      laborHired.put(household, hired);
    }

    // ④ 债务：本金合计（分类只用"本金 ÷ 资产数量"这一个比值）。
    for (DebtContract debt : debts.values()) {
      debtPrincipal.merge(debt.debtor(), debt.principal(), Math::addExact);
    }
  }

  public static HouseholdClassRule.Index build(
      EconomyData data, Map<ProductionUnitId, ProductionProcess> units) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(units, "units（production 实例的只读投影，B2b 起必填）");
    return build(
        data.assetShares(),
        data.allocations(),
        units,
        // ★★★ D-032 B4（§13.14.2 裁定十五）：economy 不再持有产业定义副本 ⇒ 本形参传空表。
        //   ★ 判据一字未变：下面 7 参重载的注释早已写明"industries 形参保留（结算/读口的公开重载不换签名），
        //     但 HouseholdClassRule.Index 不再读 IndustryDefinitionView.slots" —— 它从来没读过这个值（只被
        // requireNonNull 检查）。
        Map.of(),
        data.relations(),
        data.classes(),
        data.debtContracts());
  }

  public static HouseholdClassRule.Index build(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts) {
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(laborCommitments, "allocations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(householdEconomies, "classes");
    Objects.requireNonNull(debts, "debts");
    // ★ B.4：industries 形参保留（结算/读口的公开重载不换签名），但 HouseholdClassRule.Index 不再读
    // IndustryDefinitionView.slots。
    return new HouseholdClassRule.Index(
        assetShares, laborCommitments, units, relations, householdEconomies, debts, null, null);
  }

  /** 无索引读口：显式要求同一 revision 的 D4 预算；缺预算不得回退整池扫描。 */
  public static HouseholdClassRule.Index build(
      EconomyData data,
      Map<ProductionUnitId, ProductionProcess> units,
      AssetOccupancyBudget assetOccupancyBudget) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(units, "units（production 实例的只读投影，B2b 起必填）");
    Objects.requireNonNull(assetOccupancyBudget, "assetOccupancyBudget（D4 预算不可用时 fail-closed）");
    return build(
        data.assetShares(),
        data.allocations(),
        units,
        Map.of(),
        data.relations(),
        data.classes(),
        data.debtContracts(),
        assetOccupancyBudget);
  }

  /** 结算/读口显式预算入口（7 个状态分量 + 同一 revision D4 预算）。 */
  public static HouseholdClassRule.Index build(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      AssetOccupancyBudget assetOccupancyBudget) {
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(laborCommitments, "allocations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(householdEconomies, "classes");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(assetOccupancyBudget, "assetOccupancyBudget（D4 预算不可用时 fail-closed）");
    return new HouseholdClassRule.Index(
        assetShares,
        laborCommitments,
        units,
        relations,
        householdEconomies,
        debts,
        null,
        assetOccupancyBudget);
  }

  /**
   * ★★ <b>结算侧入口（R4-B.3a-perf）</b>：产能/资产从日结算入口的 {@link SettlementIndex} 查，不再为每个 {@code
   * operatedActivity} 现扫整张 {@code OwnershipStake}。其余输入与旧重载逐字相同。
   */
  public static HouseholdClassRule.Index build(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      SettlementIndex settlementIndex) {
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(laborCommitments, "allocations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(householdEconomies, "classes");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(settlementIndex, "settlementIndex");
    // ★ B.4：industries 形参保留（结算侧公开重载不换签名），但 HouseholdClassRule.Index 不再读
    // IndustryDefinitionView.slots。
    return new HouseholdClassRule.Index(
        assetShares,
        laborCommitments,
        units,
        relations,
        householdEconomies,
        debts,
        settlementIndex,
        settlementIndex.d4AssetOccupancyBudget().orElse(null));
  }

  /** 资产分配的唯一取值口：同一 revision 的 D4 预算；不可用 ⇒ 具名 fail-closed，绝不扫描份额。 */
  Map<AssetKind, Long> assetAllocationOf(ProductionProcess unit) {
    if (settlementIndex != null) {
      if (settlementIndex.d4AssetOccupancyBudget().isEmpty()) {
        throw new IllegalStateException(
            "asset-occupancy-unavailable: household-class unit=" + unit.id().value());
      }
      // budget 可用时，该 unit 的具名 rejection 对应权威零分配（不是“读口不可用”）。
      return settlementIndex.assetAllocationOf(unit);
    }
    if (assetOccupancyBudget == null) {
      throw new IllegalStateException(
          "asset-occupancy-unavailable: household-class unit=" + unit.id().value());
    }
    return assetOccupancyBudget.allocationOf(unit.id());
  }

  /** 按上面的可观察量给一家户分档；{@code ledger} 只用于租金实付读数（读不到 ⇒ empty，不填 0）。 */
  public Classification classify(HouseholdId household, Optional<ProductionLedger> ledger) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(ledger, "ledger");
    HouseholdEconomy householdEconomy = householdEconomies.get(household);
    if (householdEconomy == null) {
      throw new IllegalArgumentException("分类的家户没有 ClassRow 行: " + household);
    }
    long directLand = directLandMilliMu.getOrDefault(household, 0L);
    long operatedLand = operatedLandMilliMu.getOrDefault(household, 0L);
    long ownLand = directLand + operatedLand;
    long directToolOrWorkshopQuantity = directToolOrWorkshop.getOrDefault(household, 0L);
    long operatedToolOrWorkshopQuantity = operatedToolOrWorkshop.getOrDefault(household, 0L);
    long toolOrWorkshop = directToolOrWorkshopQuantity + operatedToolOrWorkshopQuantity;
    long directRights = directRightQuantity.getOrDefault(household, 0L);
    long inferredLand = rentInferredLandMilliMu.getOrDefault(household, 0L);
    long assetBase = directRights + operatedLand + operatedToolOrWorkshopQuantity + inferredLand;
    long sold = laborSold.getOrDefault(household, 0L);
    long hired = laborHired.getOrDefault(household, 0L);
    long netLaborSold = sold - hired;
    boolean rent = rentEntitled.getOrDefault(household, false);
    long debt = debtPrincipal.getOrDefault(household, 0L);
    long debtStress = debtStressPerMille(debt, assetBase);
    boolean debtStressed = debtStress >= DEBT_STRESS_THRESHOLD_PER_MILLE;
    Map<LaborSource, Long> sources = laborBySource.getOrDefault(household, Map.of());
    boolean communal = communalRight.getOrDefault(household, false);
    boolean tenantStakeOfHousehold = tenantStake.getOrDefault(household, false);

    OptionalLong rentPaid = OptionalLong.empty();
    boolean rentPaidComplete = false;
    if (ledger.isPresent()) {
      Set<CompensationRule> entitled = rentRulesByHousehold.getOrDefault(household, Set.of());
      Set<CompensationRule> settled = new LinkedHashSet<>();
      long paid = 0L;
      boolean unmappedLedgerRule = false;
      for (ProductionLedger.RuleSettlement reading : ledger.orElseThrow().ruleSettlements()) {
        if (!isRentShaped(reading.rule())) {
          continue;
        }
        if (ambiguousRentRules.contains(reading.rule())) {
          unmappedLedgerRule = true;
          continue;
        }
        HouseholdId recipient = rentRulePayee.get(reading.rule());
        if (recipient == null) {
          unmappedLedgerRule = true;
          continue;
        }
        if (recipient.equals(household)) {
          settled.add(reading.rule());
          paid = Math.addExact(paid, reading.paidNow());
        }
      }
      // ★ 只有"本户全部租权规则都在本账本里被结算过"才算完整；否则宁可不给数（Optional.empty），
      //   不拿"部分规则的和"或 0 冒充整周期实付。
      rentPaidComplete = !unmappedLedgerRule && settled.containsAll(entitled);
      rentPaid = rentPaidComplete ? OptionalLong.of(paid) : OptionalLong.empty();
    }

    SocialClassId stratum;
    String reason;
    String evidence = compactEvidence(household);
    if (rent && netLaborSold <= 0L) {
      stratum = SocialClassId.LANDLORD;
      reason =
          "landlord:rentEntitled;netLaborSoldMilli="
              + netLaborSold
              + ";ownLandMilliMu="
              + ownLand
              + ";inferredRentLandMilliMu="
              + inferredLand
              + ";debtStressPerMille="
              + debtStress
              + ";"
              + evidence;
    } else if (ownLand > 0L) {
      // 有 LAND：净雇工 ⇒ 富农；自耕平衡 ⇒ 中农/贫农（看债务）；净卖劳动 ⇒ 地少或欠债才贫农。
      if (netLaborSold < 0L) {
        stratum = SocialClassId.RICH_PEASANT;
        reason =
            "rich_peasant:ownLandMilliMu="
                + ownLand
                + ";directLandMilliMu="
                + directLand
                + ";operatedLandMilliMu="
                + operatedLand
                + ";netLaborSoldMilli="
                + netLaborSold
                + ";debtStressPerMille="
                + debtStress
                + ";"
                + evidence;
      } else if (netLaborSold == 0L) {
        if (debtStressed) {
          stratum = SocialClassId.POOR_PEASANT;
          reason =
              "poor_peasant:ownLandMilliMu="
                  + ownLand
                  + ";selfCultivatingButDebtStressed;debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        } else {
          stratum = SocialClassId.MIDDLE_PEASANT;
          reason =
              "middle_peasant:ownLandMilliMu="
                  + ownLand
                  + ";selfCultivating;debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        }
      } else if (debtStressed || ownLand <= SELF_CULTIVATION_THRESHOLD_MILLI_MU) {
        stratum = SocialClassId.POOR_PEASANT;
        reason =
            "poor_peasant:ownLandMilliMu="
                + ownLand
                + ";netLaborSoldMilli="
                + netLaborSold
                + ";smallholdingOrDebtStressed;debtStressPerMille="
                + debtStress
                + ";"
                + evidence;
      } else {
        stratum = SocialClassId.MIDDLE_PEASANT;
        reason =
            "middle_peasant:ownLandMilliMu="
                + ownLand
                + ";netLaborSoldMilli="
                + netLaborSold
                + ";sellsSomeLaborButLanded;debtStressPerMille="
                + debtStress
                + ";"
                + evidence;
      }
    } else if (toolOrWorkshop > 0L) {
      stratum = SocialClassId.ARTISAN;
      reason =
          "artisan:directToolOrWorkshop="
              + directToolOrWorkshopQuantity
              + ";operatedToolOrWorkshop="
              + operatedToolOrWorkshopQuantity
              + ";netLaborSoldMilli="
              + netLaborSold
              + ";debtStressPerMille="
              + debtStress
              + ";"
              + evidence;
    } else if (communal) {
      stratum = SocialClassId.OFFICIAL;
      reason = "official:communalRight;netLaborSoldMilli=" + netLaborSold + ";" + evidence;
    } else if (directRights > 0L || !operatedActivitiesOrEmpty(household).isEmpty()) {
      // 其它尚未启用的资产（CATTLE/MACHINE/SHIP）或没有可计产能的经营身份：有资产事实，但无法套 LAND/SMALL_TOOL 档。
      if (debtStressed || netLaborSold > 0L) {
        stratum = SocialClassId.POOR_PEASANT;
        reason =
            "poor_peasant:assetHolder;directRightQuantity="
                + directRights
                + ";netLaborSoldMilli="
                + netLaborSold
                + ";debtStressPerMille="
                + debtStress
                + ";"
                + evidence;
      } else {
        stratum = SocialClassId.MIDDLE_PEASANT;
        reason =
            "middle_peasant:assetHolder;directRightQuantity="
                + directRights
                + ";netLaborSoldMilli="
                + netLaborSold
                + ";"
                + evidence;
      }
    } else if (!sources.isEmpty()) {
      if (sources.containsKey(LaborSource.TENANT)) {
        if (debtStressed || !tenantStakeOfHousehold) {
          stratum = SocialClassId.POOR_PEASANT;
          reason =
              "poor_peasant:tenantNoStakeOrDebtStressed;tenantStake="
                  + tenantStakeOfHousehold
                  + ";debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        } else {
          stratum = SocialClassId.MIDDLE_PEASANT;
          reason =
              "middle_peasant:tenantWithInputOutputStake;debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        }
      } else if (sources.containsKey(LaborSource.FAMILY) || sources.containsKey(LaborSource.SELF)) {
        // ★ 混合身份（真档：SERF 庄园劳动 + FAMILY 家庭纺织）：自营是"本户自己的生产"，不因同时给庄园出劳
        //   就消失；债务压力高时才退回贫农。依附身份（SERF）仍是它的兜底，reason 里两条关系都写出来。
        if (debtStressed) {
          stratum = SocialClassId.POOR_PEASANT;
          reason =
              "poor_peasant:selfEmploymentButDebtStressed;debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        } else {
          stratum = SocialClassId.MIDDLE_PEASANT;
          reason =
              "middle_peasant:familySelfEmployment;debtStressPerMille="
                  + debtStress
                  + ";"
                  + evidence;
        }
      } else if (sources.containsKey(LaborSource.SERF)) {
        stratum = SocialClassId.POOR_PEASANT;
        reason = "poor_peasant:serfDependent;debtStressPerMille=" + debtStress + ";" + evidence;
      } else if (sources.containsKey(LaborSource.WAGE)) {
        stratum = SocialClassId.LANDLESS_LABORER;
        reason =
            "landless_laborer:wageLabor;netLaborSoldMilli="
                + netLaborSold
                + ";debtStressPerMille="
                + debtStress
                + ";"
                + evidence;
      } else {
        stratum = householdEconomy.view().stratum();
        reason =
            "retainedCurrentView:unmappedLaborSource(current=" + stratum.value() + ");" + evidence;
      }
    } else {
      // ★★ 没有任何可观察证据（无权利、无租、无劳动、无关系）⇒ 保留当前视图；不凭空把空行判成 landless。
      stratum = householdEconomy.view().stratum();
      reason =
          "retainedCurrentView:noObservableEvidence(current="
              + stratum.value()
              + ");ownLandMilliMu="
              + ownLand
              + ";rightQuantity="
              + directRights
              + ";laborSoldMilli="
              + sold
              + ";rentEntitled="
              + rent;
    }
    return new Classification(
        stratum,
        reason,
        ownLand,
        directRights,
        sold,
        hired,
        netLaborSold,
        rent,
        rentPaid,
        rentPaidComplete,
        debt,
        debtStress);
  }

  /** 保序取一条家户的证据串（原因字符串用；不参与算术）。 */
  String compactEvidence(HouseholdId household) {
    List<String> parts = new ArrayList<>();
    Map<String, Long> labor = laborEvidence.getOrDefault(household, Map.of());
    for (Map.Entry<String, Long> entry : labor.entrySet()) {
      if (parts.size() >= 4) {
        parts.add("…+" + (labor.size() - 4));
        break;
      }
      parts.add(entry.getKey() + "=" + entry.getValue());
    }
    Set<String> roles = roleEvidence.getOrDefault(household, Set.of());
    parts.addAll(roles);
    return parts.isEmpty() ? "noRelationEvidence" : "evidence[" + String.join(",", parts) + "]";
  }

  /** (industry, operator) 的字符串键（只作本类内部索引，不持久化、不参与排序语义）。 */
  static String industryOperatorKey(IndustryId industry, ActorRef operator) {
    return industry.value() + "|" + operator.kind() + "|" + operator.id();
  }

  Set<ProductionUnitId> directActivities(HouseholdId household) {
    return directActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
  }

  /**
   * 把一条 OwnershipStake 的资产事实记到某个家户名下（owner 与 operator 都调用它；同一家户同一份只调一次）。
   *
   * <p>★ 判据与旧实现逐字对应，只把 {@code holder/activity} 换成 {@code owner|operator/industry}：
   * 数量进资产基数、产业进直接/控制活动、LAND/SMALL_TOOL/WORKSHOP 分类累计、COMMUNAL 记名。
   */
  void mergeDirectShare(HouseholdId household, OwnershipStake share) {
    directRightQuantity.merge(household, share.quantity(), Math::addExact);
    // ★★ R3B.2：份额只带 (industry, operator)；折算到 unit = 该 (industry, operator) 的所有 unit
    //   （operator 一侧命中本户 actor 时才有；owner 一侧通常没有 unit，份额事实仍进 directRightQuantity）。
    List<ProductionUnitId> matching =
        unitsByIndustryOperator.getOrDefault(
            industryOperatorKey(share.industry(), share.operator()), List.of());
    directActivities(household).addAll(matching);
    controlledActivities(household).addAll(matching);
    if (share.asset() == AssetKind.LAND) {
      directLandMilliMu.merge(household, share.quantity(), Math::addExact);
    } else if (share.asset() == AssetKind.SMALL_TOOL || share.asset() == AssetKind.WORKSHOP) {
      directToolOrWorkshop.merge(household, share.quantity(), Math::addExact);
    }
    if (share.kind() == AssetRightKind.COMMUNAL && share.quantity() > 0L) {
      communalRight.put(household, true);
    }
  }

  Set<ProductionUnitId> directActivitiesOrEmpty(HouseholdId household) {
    return directActivities.getOrDefault(household, Set.of());
  }

  Set<ProductionUnitId> operatedActivities(HouseholdId household) {
    return operatedActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
  }

  Set<ProductionUnitId> operatedActivitiesOrEmpty(HouseholdId household) {
    return operatedActivities.getOrDefault(household, Set.of());
  }

  Set<ProductionUnitId> controlledActivities(HouseholdId household) {
    return controlledActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
  }

  Set<ProductionUnitId> controlledActivitiesOrEmpty(HouseholdId household) {
    return controlledActivities.getOrDefault(household, Set.of());
  }

  Set<ProductionUnitId> productionStakeActivities(HouseholdId household) {
    return productionStakeActivities.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
  }

  Set<ProductionUnitId> productionStakeActivitiesOrEmpty(HouseholdId household) {
    return productionStakeActivities.getOrDefault(household, Set.of());
  }

  Set<String> roleEvidence(HouseholdId household) {
    return roleEvidence.computeIfAbsent(household, ignored -> new LinkedHashSet<>());
  }

  /** 债务压力（千分）：{@code debt × 1000 ÷ max(1, 资产数量)}；无资产但欠债 ⇒ {@link Long#MAX_VALUE}。 */
  static long debtStressPerMille(long debt, long assetBase) {
    if (debt <= 0L) {
      return 0L;
    }
    if (assetBase <= 0L) {
      return Long.MAX_VALUE;
    }
    BigInteger ratio =
        BigInteger.valueOf(debt)
            .multiply(BigInteger.valueOf(1_000L))
            .divide(BigInteger.valueOf(assetBase));
    return ratio.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
        ? Long.MAX_VALUE
        : ratio.longValue();
  }

  /** 规则受方是不是租金形状：固定实物/货币租，或毛产分成（feudal 的"地租 300‰"就是后者）。 */
  static boolean isRentShaped(CompensationRule rule) {
    return rule.type() == RuleType.FIXED_IN_KIND_RENT
        || rule.type() == RuleType.FIXED_MONEY_RENT
        || (rule.type() == RuleType.OUTPUT_SHARE && rule.pool() == Pool.GROSS_OUTPUT);
  }

  /** actor 引用 → 本状态里真实存在的家户（经营者 actor 与产业型 HOUSEHOLD actor 都不算）。 */
  static HouseholdId householdOf(
      ActorRef actor,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ActorRef, HouseholdId> byActor) {
    HouseholdId exact = byActor.get(actor);
    if (exact != null) {
      return exact;
    }
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      return null;
    }
    HouseholdId parsed = HouseholdActors.householdOf(actor);
    return householdEconomies.containsKey(parsed) ? parsed : null;
  }

  /** 规则受方 → 本状态里真实存在的家户（{@code ToCohort} 走视图索引；歧义视图 ⇒ null，不猜）。 */
  static HouseholdId payeeHousehold(
      Payee recipient,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ActorRef, HouseholdId> byActor,
      Map<CohortKey, HouseholdId> byView) {
    return switch (recipient) {
      case Payee.ToHousehold toHousehold ->
          householdEconomies.containsKey(toHousehold.household()) ? toHousehold.household() : null;
      case Payee.ToActor toActor -> householdOf(toActor.actor(), householdEconomies, byActor);
      case Payee.ToCohort toCohort -> byView.get(toCohort.cohort());
    };
  }
}
