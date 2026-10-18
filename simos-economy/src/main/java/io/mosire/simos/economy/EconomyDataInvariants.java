package io.mosire.simos.economy;

import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.migrate.DebtReferenceReconciler;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdDebtReference;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** EconomyData 规范构造期的本域不变量与归一化 helper（机械拆分；行为不变）。 */
final class EconomyDataInvariants {
  private EconomyDataInvariants() {}

  /** 缺键（null）⇒ 空表；与既有 compact 构造器同一口径。 */
  static <K, V> Map<K, V> emptyIfNull(Map<K, V> value) {
    return value == null ? Map.of() : value;
  }

  /** classes：键/值非空、键 == 行内稳定身份，并冻结。 */
  static Map<HouseholdId, HouseholdEconomy> validateClasses(
      Map<HouseholdId, HouseholdEconomy> classes) {
    Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry : classes.entrySet()) {
      if (householdEconomyEntry.getKey() == null || householdEconomyEntry.getValue() == null) {
        throw new IllegalArgumentException(
            "classes 的键与值都不得为 null: " + householdEconomyEntry.getKey());
      }
      if (!householdEconomyEntry.getKey().equals(householdEconomyEntry.getValue().id())) {
        throw new IllegalArgumentException(
            "classes 的键必须与 ClassRow.id 一致（S1 起键 = 家户稳定身份）：键="
                + householdEconomyEntry.getKey()
                + "，行内 id="
                + householdEconomyEntry.getValue().id());
      }
      HouseholdEconomy householdEconomy = householdEconomyEntry.getValue();
      // ★★ R4-B.4（R3 决策单 §0.1/§1.5，R3B.4）：**view 不再受 IndustryDefinitionView.slots 约束**。
      //   旧守卫（view 必须命中该格产业的 slots 且 participationPerMille ≤ 该 slot 的
      //   laborParticipationPerMille）已删除；IndustryDefinitionView.slots / ClassSlot
      // 只作为生产方式内部的角色/劳动配置。
      //   HouseholdEconomy 自己的 [0,1000] 参与率守卫仍在（见 HouseholdEconomy 构造期）。
      householdEconomiesCopy.put(householdEconomyEntry.getKey(), householdEconomy);
    }
    classes = Collections.unmodifiableMap(householdEconomiesCopy); // ★ 冻在赋值处
    return classes;
  }

  /** debtContracts：键/值非空、键 == 行内 id、id 由四元组确定性派生，并冻结。 */
  static Map<DebtContractId, DebtContract> validateDebtContracts(
      Map<DebtContractId, DebtContract> debtContracts) {
    Map<DebtContractId, DebtContract> debtContractsCopy = new LinkedHashMap<>();
    for (Map.Entry<DebtContractId, DebtContract> entry : debtContracts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("debtContracts 的键与值都不得为 null: " + entry.getKey());
      }
      DebtContract contract = entry.getValue();
      if (!entry.getKey().equals(contract.id())) {
        throw new IllegalArgumentException(
            "debtContracts 的键必须与 DebtContract.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + contract.id());
      }
      // ★★ 冻结的条件：id 必须确实是 (debtor, creditor, unit, terms) 的确定性派生 —— 合同的“连续身份”
      //   一旦可以手写，同一四元组就能在两条记录里各写一个 id，跨周期连续这条地基当场失效。
      if (!contract.idMatchesIdentity()) {
        throw new IllegalArgumentException(
            "DebtContract.id 必须由 (debtor, creditor, unit, terms) 确定性派生："
                + entry.getKey()
                + " ≠ "
                + DebtContractId.idOf(
                    contract.debtor(), contract.creditor(), contract.unit(), contract.terms()));
      }
      debtContractsCopy.put(entry.getKey(), contract);
    }
    debtContracts = Collections.unmodifiableMap(debtContractsCopy); // ★ 冻在赋值处
    return debtContracts;
  }

  /** householdDebtRefs：形状守卫 + 由权威合同表整表对账 + 引用可解析兜底。 */
  static Map<HouseholdDebtReference, Boolean> reconcileDebtReferences(
      Map<DebtContractId, DebtContract> debtContractsCopy,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy,
      Map<HouseholdDebtReference, Boolean> householdDebtRefs) {
    Map<HouseholdDebtReference, Boolean> householdDebtRefsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdDebtReference, Boolean> entry : householdDebtRefs.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("householdDebtRefs 的键与值都不得为 null: " + entry.getKey());
      }
      if (!Boolean.TRUE.equals(entry.getValue())) {
        throw new IllegalArgumentException(
            "householdDebtRefs 的值只允许标记位 TRUE（存在即引用）: " + entry.getKey() + " = " + entry.getValue());
      }
      householdDebtRefsCopy.put(entry.getKey(), Boolean.TRUE);
    }
    householdDebtRefs = Collections.unmodifiableMap(householdDebtRefsCopy); // ★ 冻在赋值处
    // ★★ B.3b（R3 决策单 §0.3）+ 2026-10-09 选项 A：孤儿债对账 —— 以 debtContracts 表为唯一权威，按 debtor 分组、
    //   DebtContractId canonical 升序重建**引用表**（改前是重建每一行的 debts 列表；结论逐字不变）。
    //   ★ 必须在**跨表守卫之前**：守卫要求“引用的合同存在”，而孤儿债是“合同存在、引用缺失”；对账不碰合同表本身
    //   （principal/status 守恒），只在 debtor/creditor 家户行缺失时 fail-closed 具名抛。
    householdDebtRefs =
        DebtReferenceReconciler.reconcile(
            debtContractsCopy, householdEconomiesCopy, householdDebtRefs);
    householdDebtRefs = Collections.unmodifiableMap(householdDebtRefs); // ★ 冻在赋值处（可能是同一实例）
    // ★ v2 spec §八.2：两张表的**交叉引用完整性**。★ 必须等两张表都建完再判 ——
    //   在任一段内查对方会陷入循环依赖（debtContracts 要查 classes、classes 要查 debtContracts），故不能靠调顺序解决。
    //   ★ B.3b 起引用侧已由上面的 DebtReferenceReconciler 重建过，本循环是对账后的兜底断言。
    for (Map.Entry<DebtContractId, DebtContract> entry : debtContractsCopy.entrySet()) {
      DebtContract contract = entry.getValue();
      if (!householdEconomiesCopy.containsKey(contract.debtor())
          || !householdEconomiesCopy.containsKey(contract.creditor())) {
        throw new IllegalArgumentException(
            "债务的 debtor/creditor 必须是已存在的阶层行（v2 spec §八.2）："
                + entry.getKey()
                + " "
                + contract.debtor()
                + " → "
                + contract.creditor());
      }
    }
    // ★ 改前这里逐行扫 ClassRow.debts；拆表后同一判据收成一个具名方法（引用表 ⊆ 合同表 + 挂对债务人 + 家户行存在）。
    //   ⇒ 悬空引用仍然具名抛，不因拆表而放松；且它可被单独调用（自证探针就是这么打这句话的）。
    DebtReferenceReconciler.requireReferencesResolvable(
        debtContractsCopy, householdEconomiesCopy, householdDebtRefs);
    return householdDebtRefs;
  }

  /** flows：键/值非空、键 == 行内 id、家户行必须已存在，并冻结。 */
  static Map<HouseholdId, FlowRow> validateFlows(
      Map<HouseholdId, FlowRow> flows, Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy) {
    Map<HouseholdId, FlowRow> flowsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("flows 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "flows 的键必须与 FlowRow.id 一致（S1 起键 = 家户稳定身份）：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      HouseholdEconomy flowHouseholdEconomy = householdEconomiesCopy.get(entry.getKey());
      if (flowHouseholdEconomy == null) {
        throw new IllegalArgumentException("flows 的键必须是已存在的家户（S1 起两表同键）：" + entry.getKey());
      }
      // ★★★ D-032 B4（§13.14.2 裁定十五）：本行的"flow 落点必须登记过产业"判据**已平移**到 state-aware 守卫
      //   `EconomyReferenceValidation.validateFlowsOnIndustryHex`（读 production 当刻定义权威；由
      //   `EconomyProductionReferenceValidator` 在每条命令 / submitBatch 最终候选态 / restore / Advance
      // 候选态落库前执行）。
      //   ★ 构造期看不见 production ⇒ 这里不再判（判据没删，换了读源与落点）。
      flowsCopy.put(entry.getKey(), entry.getValue());
    }
    flows = Collections.unmodifiableMap(flowsCopy); // ★ 冻在赋值处
    return flows;
  }

  /** allocations：键/值非空、键 == 行内 id、家户存在，并判 PRODUCTION 时间预算上限。 */
  static Map<LaborAllocationId, HouseholdLaborCommitment> validateAllocations(
      Map<LaborAllocationId, HouseholdLaborCommitment> allocations,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy) {
    Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitmentsCopy = new LinkedHashMap<>();
    Map<HouseholdId, Long> productionPerHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> govServicePerHousehold = new LinkedHashMap<>();
    for (Map.Entry<LaborAllocationId, HouseholdLaborCommitment> laborCommitmentEntry :
        allocations.entrySet()) {
      if (laborCommitmentEntry.getKey() == null || laborCommitmentEntry.getValue() == null) {
        throw new IllegalArgumentException(
            "allocations 的键与值都不得为 null: " + laborCommitmentEntry.getKey());
      }
      HouseholdLaborCommitment laborCommitment = laborCommitmentEntry.getValue();
      if (!laborCommitmentEntry.getKey().equals(laborCommitment.id())) {
        throw new IllegalArgumentException(
            "allocations 的键必须与 LaborAllocation.id 一致：键="
                + laborCommitmentEntry.getKey()
                + "，行内 id="
                + laborCommitment.id());
      }
      // ①-b ★★ S1：这份劳动必须属于一个已存在的家户。★★ B2c（§13.12.5）：迁移期 pending 占位的豁免已删
      //   （占位整套删除 ⇒ 没有"合法的不存在家户"这一档）—— 旧档缺 household 在 codec 边界就 fail-closed。
      HouseholdId allocatedHousehold = laborCommitment.household();
      if (!householdEconomiesCopy.containsKey(allocatedHousehold)) {
        throw new IllegalArgumentException(
            "劳动分配的家户必须是已存在的家户（S1 起身份与视图分离）："
                + laborCommitmentEntry.getKey()
                + " 的 household="
                + allocatedHousehold
                + " 不在 classes 里");
      }
      // ① actor ↔ 产业 的**撞名判据**（结算按 actor id 归属劳动）：
      //    非产业型不得与产业 id 撞名 —— 撞名会让"家户/单位的配额"静默算进那个产业。
      //   ★★ **R3 起 HOUSEHOLD 是"自由档"**：家户 actor id **可以**命名一个产业（那时配额照进该产业），也可以不命名。
      //   ★★ **P2-A §13.3**：ESTATE/WORKSHOP 退役 ⇒ 生产主体身份改走 ORGANIZATION（产业经营者）与 HOUSEHOLD；
      //     两者都允许命中产业 id（不再有"必须命中"的 kind —— ORGANIZATION 也可用来表示非产业组织）。
      //   ★★★ D-032 B4（§13.14.2 裁定十五）：判据本体**平移**到 state-aware 守卫
      //     `EconomyReferenceValidation.validateLaborActorIndustryCollision`（它逐条比 production 当刻定义
      // id）；
      //     构造期不再持有产业定义副本 ⇒ 判不了，也不许"因为看不见就放过"——落库前的候选态校验面照旧拦。
      // ①-c ★★ R3B.2：activity 若指名了现存 unit，则收劳动的主体必须就是该 unit 的 operator。
      //   ★ 判据**没有删**：它要读 production 的实例投影 ⇒ 落在跨片校验面
      //     （{@code EconomyReferenceValidation.validateUnitCrossReferences}，每次候选提交前跑）。
      //   ★ activity 不命中 unit 的配额合法（自由家户劳动）：它不喂任何生产，只进守恒与读口。
      // ★★ P2-A A4：配额上限改为**家户每 tick 时间预算**（{@code HouseholdEconomy.laborMilli}，毫小时）——
      //   不再有"每批次供给表"这第二权威（LaborSupply 已删除；预算每 tick 由 Social 人口组成重算）。
      //   ★★ Z7d-1：按 kind 分账 —— GOV_SERVICE 是"职位/诉求"（允许超过饿少后的预算，有效供给由 app
      //   供给桥 cap），PRODUCTION 才受"扣除政府承诺预留后的可用时间"约束。
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        govServicePerHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Long::sum);
      } else {
        productionPerHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Long::sum);
      }
      laborCommitmentsCopy.put(laborCommitmentEntry.getKey(), laborCommitment);
    }
    allocations = Collections.unmodifiableMap(laborCommitmentsCopy); // ★ 冻在赋值处
    // ③ ★★ **Σ PRODUCTION ≤ 时间预算 − min(Σ GOV_SERVICE, 时间预算)**（本阶段最重要的不变量，见类注释）。
    //   ★★ Z7d-1 口径：GOV_SERVICE 承诺行保留为职位（C7 不静默缩/删），允许 `Σ GOV_SERVICE > laborMilli`
    //   （饥饿把预算饿少后的具名 underfed 状态）；此时 PRODUCTION 可用量 = 0。有效供给 = min(承诺, 实际劳动)
    //   由 app 的 GovernmentServiceLaborBridge 逐户 cap，不在这里改任何承诺行。
    for (Map.Entry<HouseholdId, Long> entry : productionPerHousehold.entrySet()) {
      HouseholdEconomy householdEconomy = householdEconomiesCopy.get(entry.getKey());
      if (householdEconomy == null) {
        continue; // ★ B2b：旧档迁移器已删 ⇒ pending 家户不再被自动改写（旧档作废；真实家户由守卫把守）
      }
      long budget = householdEconomy.laborMilli();
      long govService = govServicePerHousehold.getOrDefault(entry.getKey(), 0L);
      long reservedGovService = Math.min(govService, budget);
      long productionBudget = budget - reservedGovService;
      if (entry.getValue() > productionBudget) {
        throw new IllegalArgumentException(
            "家户 "
                + entry.getKey()
                + " 的生产劳动配额之和 "
                + entry.getValue()
                + " 超过它的每 tick 时间预算 "
                + budget
                + " 中扣除 GOV_SERVICE 最高优先预留后的可用部分 "
                + productionBudget
                + "（GOV_SERVICE 承诺 "
                + govService
                + " 毫小时；同一份家户时间不得被多个生产活动各算一次满额，计划 §13.5）");
      }
    }
    return allocations;
  }

  /** assetShares：键/值非空、键 == 行内 id，并冻结。 */
  static Map<AssetShareId, OwnershipStake> validateAssetShares(
      Map<AssetShareId, OwnershipStake> assetShares) {
    Map<AssetShareId, OwnershipStake> ownershipStakesCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetShareId, OwnershipStake> entry : assetShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("assetShares 的键与值都不得为 null: " + entry.getKey());
      }
      OwnershipStake share = entry.getValue();
      if (!entry.getKey().equals(share.id())) {
        throw new IllegalArgumentException(
            "assetShares 的键必须与 OwnershipStake.id 一致：键=" + entry.getKey() + "，行内 id=" + share.id());
      }
      // ★★★ D-032 B4（§13.14.2 裁定十五）：本行的 assetShare→industry 存在性判据**已平移**到 state-aware 守卫
      //   `EconomyReferenceValidation.validateUnitsAndShares`（份额侧仍读 economy 的 assetShares，产业侧读
      //   production 当刻定义权威）；构造期没有定义副本可查 ⇒ 不再判。
      ownershipStakesCopy.put(entry.getKey(), share);
    }
    assetShares = Collections.unmodifiableMap(ownershipStakesCopy); // ★ 冻在赋值处
    return assetShares;
  }

  /** relations：Payee 归一 + 键 == 行内 activity，并冻结。 */
  static Map<ProductionUnitId, ProductionRules> validateRelations(
      Map<ProductionUnitId, ProductionRules> relations,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy) {
    Map<ProductionUnitId, ProductionRules> relationsRaw = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : relations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("relations 的键与值都不得为 null: " + entry.getKey());
      }
      relationsRaw.put(entry.getKey(), entry.getValue());
    }
    // ★★ S1：把旧档/默认关系里的 {@code ToCohort(view)} 一对一归一到 {@code ToHousehold(id)}
    //   （同一 view 恰有一个家户时才归一 —— 一一对应是旧档的既有事实；view 有歧义时保留 ToCohort
    //   作为 S3 的视图选择器，不在构造期猜）。归一化是"键/受方迁移"的唯一落点，幂等。
    Map<ProductionUnitId, ProductionRules> relationsCopy =
        normalizePayees(relationsRaw, householdEconomiesCopy);
    relations = Collections.unmodifiableMap(relationsCopy); // ★ 冻在赋值处
    // ★★ 两条跨表守卫（R3B.2 改口径）：① 键 == 值内 activity（同 classes/flows 的键身份口径）；
    //   ② 关系挂在一个**已存在的 unit** 上、且 operator 与 unit.operator 逐值相等
    //      （"谁经营"若能在关系表里另写一遍，两边不一致时没有任何一处能判谁对）。
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : relationsCopy.entrySet()) {
      ProductionRules relation = entry.getValue();
      if (!entry.getKey().equals(relation.activity())) {
        throw new IllegalArgumentException(
            "relations 的键必须与 ProductionRules.activity 一致：键="
                + entry.getKey()
                + "，行内 activity="
                + relation.activity());
      }
      // ★ B2b：原判据 ②（关系指名的 unit 存在、且 operator == unit.operator）已移到跨片校验面
      //   （{@code EconomyReferenceValidation.validateUnitCrossReferences}）——它要读 production 的实例投影。
    }
    return relations;
  }

  /**
   * ★★ S1：把 {@link Payee.ToCohort} 一对一归一到 {@link Payee.ToHousehold}。
   *
   * <p>判据：<b>该 view 恰有一个家户</b>（旧档的既有事实）⇒ 迁移；view 有歧义（S3 才允许）⇒ 保留旧变体交由 S3
   * 的视图选择器解释。归一化是构造期的纯函数、幂等（归一后的规则不再含 ToCohort）。
   *
   * <p>★ <b>S3 审计</b>：索引每次都用**当前** {@code classes} 行集合重建，不缓存历史 view。默认关系在第一次构造时就已归一为 {@code
   * ToHousehold(稳定身份)} ⇒ 之后的阶层写回只改 {@code HouseholdEconomy.view}，不会让这些规则改指到别的家户；仍保留的 {@code
   * ToCohort} 是旧档/多义视图的兼容窄口，按当前行集合解释。
   */
  private static Map<ProductionUnitId, ProductionRules> normalizePayees(
      Map<ProductionUnitId, ProductionRules> raw,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<CohortKey, HouseholdId> unique = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      if (unique.putIfAbsent(householdEconomy.view(), householdEconomy.id()) != null) {
        ambiguous.add(householdEconomy.view());
      }
    }
    for (CohortKey view : ambiguous) {
      unique.remove(view);
    }
    Map<ProductionUnitId, ProductionRules> normalized = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : raw.entrySet()) {
      ProductionRules relation = entry.getValue();
      List<CompensationRule> rules = new ArrayList<>(relation.rules().size());
      boolean changed = false;
      for (CompensationRule rule : relation.rules()) {
        if (rule.recipient() instanceof Payee.ToCohort toCohort) {
          HouseholdId household = unique.get(toCohort.cohort());
          if (household != null) {
            rules.add(
                new CompensationRule(
                    rule.type(),
                    new Payee.ToHousehold(household),
                    rule.pool(),
                    rule.weight(),
                    rule.ratePerMille(),
                    rule.fixedAmount(),
                    rule.commodity(),
                    rule.currency(),
                    rule.priority()));
            changed = true;
            continue;
          }
        }
        rules.add(rule);
      }
      normalized.put(
          entry.getKey(),
          changed
              ? new ProductionRules(
                  relation.activity(),
                  relation.operator(),
                  relation.inputSupplier(),
                  rules,
                  relation.residualOwner(),
                  relation.laborSource())
              : relation);
    }
    return normalized;
  }

  /** marketTables：键/值非空、规范序冻结。 */
  static Map<MarketZoneId, MarketTable> validateMarketTables(
      Map<MarketZoneId, MarketTable> marketTables) {
    if (marketTables == null) {
      marketTables = Map.of();
    }
    for (Map.Entry<MarketZoneId, MarketTable> entry : marketTables.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("marketTables 的键与值都不得为 null: " + entry.getKey());
      }
    }
    marketTables = MarketTables.frozen(marketTables); // ★ 规范序 + 冻在赋值处
    return marketTables;
  }

  /** shipments：键/值非空并冻结。 */
  static Map<ShipmentId, ShipmentBatch> validateShipments(
      Map<ShipmentId, ShipmentBatch> shipments) {
    if (shipments == null) {
      shipments = Map.of();
    }
    Map<ShipmentId, ShipmentBatch> shipmentsCopy = new LinkedHashMap<>();
    for (Map.Entry<ShipmentId, ShipmentBatch> entry : shipments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("shipments 的键与值都不得为 null: " + entry.getKey());
      }
      shipmentsCopy.put(entry.getKey(), entry.getValue());
    }
    shipments = Collections.unmodifiableMap(shipmentsCopy); // ★ 冻在赋值处
    return shipments;
  }

  /** operatorConditions：键/值非空并冻结。 */
  static Map<ProductionUnitId, OperatorCondition> validateOperatorConditions(
      Map<ProductionUnitId, OperatorCondition> operatorConditions) {
    Map<ProductionUnitId, OperatorCondition> operatorConditionsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, OperatorCondition> entry : operatorConditions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("operatorConditions 的键与值都不得为 null: " + entry.getKey());
      }
      // ★ B2b：原"键的 unit 存在 + 值内 industry == unit.industry"两条守卫已移到跨片校验面
      //   （{@code EconomyReferenceValidation.validateUnitCrossReferences}）——它们要读 production 的实例投影。
      operatorConditionsCopy.put(entry.getKey(), entry.getValue());
    }
    operatorConditions = Collections.unmodifiableMap(operatorConditionsCopy); // ★ 冻在赋值处
    return operatorConditions;
  }

  /** demands：键身份 + HOUSEHOLD/HEX 引用完整性，并冻结。 */
  static Map<DemandId, HouseholdDemand> validateDemands(
      Map<DemandId, HouseholdDemand> demands,
      Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy,
      Map<MarketZoneId, MarketTable> marketTables,
      Map<MarketZoneId, MarketZone> marketZones) {
    Map<DemandId, HouseholdDemand> householdDemandsCopy = new LinkedHashMap<>();
    for (Map.Entry<DemandId, HouseholdDemand> householdDemand : demands.entrySet()) {
      if (householdDemand.getKey() == null || householdDemand.getValue() == null) {
        throw new IllegalArgumentException("demands 的键与值都不得为 null: " + householdDemand.getKey());
      }
      HouseholdDemand demand = householdDemand.getValue();
      if (!householdDemand.getKey().equals(demand.id())) {
        throw new IllegalArgumentException(
            "demands 的键必须与 DemandEntry.id 一致：键="
                + householdDemand.getKey()
                + "，行内 id="
                + demand.id());
      }
      if (demand.scope() == HouseholdDemand.DemandScope.HOUSEHOLD) {
        HouseholdId household = demand.household().orElseThrow();
        if (!householdEconomiesCopy.containsKey(household)) {
          throw new IllegalArgumentException(
              "HOUSEHOLD 范围的需求指名的家户不存在: " + demand.id().value() + " → " + household.value());
        }
      } else {
        HexCoord hex = demand.hex().orElseThrow();
        // ★★★ D-032 B4（§13.14.2 裁定十五）：本行的产业维（"该格登记过产业"）**已平移**到 state-aware 守卫
        //   `EconomyReferenceValidation.validateDemandHexes`（读 production 当刻定义权威）；构造期只剩本域两维
        //   （家户行 / 市场），判据没删、也没放宽 —— 少了"产业"这一维只会更严，而更严的那一维由落库前校验面补齐。
        if (!hexRegistered(householdEconomiesCopy, marketTables, marketZones, hex)) {
          throw new IllegalArgumentException(
              "HEX 范围的需求指名的格没有经济状态（该格不存在/未播种，也不在任何市场键里）: " + demand.id().value() + " → " + hex);
        }
      }
      householdDemandsCopy.put(householdDemand.getKey(), demand);
    }
    demands = Collections.unmodifiableMap(householdDemandsCopy); // ★ 冻在赋值处
    return demands;
  }

  /**
   * ★★ R4-E2：一个格在 economy 侧是否"可定位" —— 该格至少有一行家户，或一张市场。
   *
   * <p>★ <b>为什么市场也算</b>：{@code economy.SetMarketPrice} 可以先给一个尚无家户/产业的格建市场（命令只要求格存在），其后落在该格的 HEX
   * 需求不应因为"还没有家户行"被判成坏状态 —— 需求路径会在订单生成时按当时人口摊到 0 户（合法）。
   *
   * <p>★★ <b>D-032 B4（§13.14.2 裁定十五）/ B5（§13.15.4 裁定二十）</b>：产业那一维（"该格登记过产业定义"）已随 economy 的定义副本删除 ——
   * 它现在由 state-aware 守卫 {@code EconomyReferenceValidation.validateDemandHexes} 读 production
   * 当刻定义权威判： <b>落点 = {@code EconomyReferenceValidation.java} 里 {@code definitions.industries()}
   * 的那一支</b>（与家户行 / 市场两支合成同一个三元判据）。 本方法只判本域（家户行 / 市场）。
   *
   * <p>★ <b>边界（裁定二十明文要求写明，如实记）</b>：{@code EconomyCodec} <b>单独 decode</b>（不经候选态校验面）时， "HEX
   * 需求指名的格只有产业、没有家户/市场"这一形态<b>不再被拦</b>；该维只在候选态校验面补齐（注册点 {@code Shell.java:1199}， 含创世态 {@code
   * CoreSimos.bootstrapGenesisInternal:399-402}）。⇒ 落库前仍然 fail-closed，V10 往返由测试 owner 覆盖。
   */
  private static boolean hexRegistered(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<MarketZoneId, MarketTable> marketTables,
      Map<MarketZoneId, MarketZone> marketZones,
      HexCoord hex) {
    // ★★★ D-032 B4（§13.14.2 裁定十五）：产业维（"该格登记过产业定义"）已不在本域可查（定义副本已删）⇒
    //   本方法只判 economy 侧两维；那一维由 state-aware 守卫 `EconomyReferenceValidation.validateDemandHexes` 补齐。
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      if (householdEconomy.view().hex().equals(hex)) {
        return true;
      }
    }
    // ★★ W1：价表按**区**存之后，"这一格有市场"= 这一格在任一张价表的成员格里（派生形态），
    //   或属于某个市场区（持久形态 —— 区成员格都有本区价表，见 MarketTable 的类注）。
    for (MarketTable table : marketTables.values()) {
      if (table.hexes().contains(hex)) {
        return true;
      }
    }
    if (marketZones != null) {
      for (MarketZone zone : marketZones.values()) {
        if (zone.hexes().contains(hex)) {
          return true;
        }
      }
    }
    return false;
  }

  /** commodityFreightBaseMilli：键/值非空、值域 ≥ 0，并冻结。 */
  static Map<CommodityId, Long> validateCommodityFreight(
      Map<CommodityId, Long> commodityFreightBaseMilli) {
    Map<CommodityId, Long> commodityFreightCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : commodityFreightBaseMilli.entrySet()) {
      CommodityId commodity = entry.getKey();
      Long baseMilli = entry.getValue();
      if (commodity == null || baseMilli == null) {
        throw new IllegalArgumentException("commodityFreightBaseMilli 的键与值都不得为 null: " + commodity);
      }
      if (baseMilli < 0L) {
        throw new IllegalArgumentException(
            EconomyData.COMMODITY_FREIGHT_CONTRACT_PREFIX
                + "基础运费必须 ≥ 0（0 = 明确的'该商品免基础费'，不是'说不出价'）: 商品="
                + commodity.value()
                + "，基础运费="
                + baseMilli);
      }
      commodityFreightCopy.put(commodity, baseMilli);
    }
    commodityFreightBaseMilli = Collections.unmodifiableMap(commodityFreightCopy); // ★ 冻在赋值处
    return commodityFreightBaseMilli;
  }
}
