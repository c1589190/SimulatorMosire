package io.mosire.simos.economy.change;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CandidateId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 经济状态的变更集。**组件与 {@link EconomyData} 的 record 组件一一对应**（当前 31 个：{@code meta} / {@code industries} /
 * {@code classes} / {@code debtContracts} / {@code flows} / {@code laborSupply} / {@code
 * allocations} / {@code relations} / {@code markets} / {@code shipments} / {@code memberships} /
 * {@code assetShares} / {@code operatorConditions} / {@code units} / {@code demands} / {@code
 * candidates} / {@code modes} / {@code classStructures} / {@code classPositions} / {@code
 * classStandings} / {@code productionOrganizations} / {@code assetRules} + E3 的 {@code governments}
 * / {@code moneyIssuances} + E4a 的 {@code debtContracts} / {@code pledges} + E5a 的 {@code
 * liquidationPolicies} / {@code crisisSignals} + E6a 的 {@code modeTransitions} / {@code
 * classShares} + R1 的 {@code classFirst} + P10.1 的 {@code merchantFirms}）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code EconomyRoundTripTest} 的**反射枚举**把守——新增状态组件若不进 变更集，那个测试自动红。
 *
 * <p>★★ **E1–E6 追加组件、R1 的 {@code classFirst} 与 {@link EconomyData} 逐条对应**：E1 {@code
 * modes/classStructures/classPositions/classStandings}； E2 {@code
 * productionOrganizations/assetRules}；E3 {@code governments/moneyIssuances}；E4 {@code
 * debtContracts} （替换旧 {@code debts} 槽）/{@code pledges}；E5 {@code
 * liquidationPolicies/crisisSignals}；E6 {@code modeTransitions/classShares}。E6b（GM 经济调整命令）与 E6c（统一
 * dashboard 读口）都只读写既有组件， **零新状态组件**；R1 追加 {@code classFirst}、P10.1 追加 {@code merchantFirms} 后为 **31
 * 个组件**（上面那份逐条清单就是全表）。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code SocialChangeSet} / {@code UnitChangeSet} / {@code SdChangeSet} / {@code
 * LedgerChangeSet} 共用同一份机制，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★★ **{@code meta} 是单值组件，用"单键表"投影进同一份机制**：{@code FieldDelta} 是对**表**的差异（键 → 值），而 {@code meta} 是
 * {@code Optional<EconomyMeta>}。若为它另写一份"单值差异"机制，就有了与 {@code FieldDelta} 分叉的第二份实现。 故把 {@code
 * Optional} 投影成至多一行的表（键 = {@link #META_KEY}），diff/rebuild 全走既有机制，再投影回 {@code Optional}。
 * 语义是纯的：{@code 空 → 有值} = {@code Upsert}、{@code 有值 → 空} = {@code Remove}、{@code 有值 → 另一个值} = {@code
 * Upsert}。同 {@code LedgerChangeSet.economyMeta} 的键选择，记入说明。
 *
 * <p>★ **实现 util 的 {@code ChangeSet} 标记接口**（M4 / spec §十）：该接口已收窄为**标记接口**，实现它不带来任何新义务。
 *
 * <p>★ **R2 的两个新组件走同一份机制**（{@code laborSupply} = 每批次有多少可支配劳动、{@code allocations} = 每批次把多少给了谁）：
 * 它们是普通的"键 → 值"表，故 diff/rebuild 一字不用改——rebuild 的键解析器 = 各自的 {@code parse} （{@link PeopleLotId#parse}
 * / {@link LaborAllocationId#parse}），与 {@link CohortKey} 一族同款。
 *
 * <p>★ **T2 的第 8 个组件同款**（{@code relations} = 每个产业一次生产的结算规则）：键 = {@code IndustryId} （已有键反序列化器），值 =
 * {@link ProductionRelation}（**深层**带一个 sealed 多态类型 {@code Recipient} ⇒ 它的 线格式由类型上的 Jackson
 * 注解把守，见那个接口的类注；此处无须有任何分支）。
 */
public record EconomyChangeSet(
    FieldDelta<EconomyMeta> meta,
    FieldDelta<Industry> industries,
    FieldDelta<ClassRow> classes,
    FieldDelta<DebtContract> debtContracts,
    FieldDelta<FlowRow> flows,
    FieldDelta<LaborSupply> laborSupply,
    FieldDelta<LaborAllocation> allocations,
    FieldDelta<ProductionRelation> relations,
    FieldDelta<Market> markets,
    FieldDelta<ShipmentBatch> shipments,
    FieldDelta<Membership> memberships,
    FieldDelta<AssetShare> assetShares,
    FieldDelta<OperatorCondition> operatorConditions,
    FieldDelta<ProductionUnit> units,
    FieldDelta<DemandEntry> demands,
    FieldDelta<ProductionCandidate> candidates,
    FieldDelta<ProductionMode> modes,
    FieldDelta<ClassStructure> classStructures,
    FieldDelta<ClassPosition> classPositions,
    FieldDelta<ClassStanding> classStandings,
    FieldDelta<ProductionOrganization> productionOrganizations,
    FieldDelta<AssetRule> assetRules,
    FieldDelta<Government> governments,
    FieldDelta<MoneyIssuanceRecord> moneyIssuances,
    FieldDelta<Pledge> pledges,
    FieldDelta<LiquidationPolicy> liquidationPolicies,
    FieldDelta<HexCrisisSignal> crisisSignals,
    FieldDelta<ModeTransition> modeTransitions,
    FieldDelta<ClassShare> classShares,
    FieldDelta<ClassFirstState> classFirst,
    FieldDelta<MerchantFirm> merchantFirms)
    implements ChangeSet {

  /** {@code meta} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String META_KEY = "meta";

  /** {@code classFirst} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String CLASS_FIRST_KEY = "classFirst";

  public EconomyChangeSet {
    // ★ **旧档兼容**（§11 的口径，照 {@code LedgerChangeSet}）：升级前落盘的这条变更集没有这些
    //   键时，Jackson 绑成 null ⇒ 缺省 = Unchanged（"一字未动"），**此处不抛** —— 读成 null 的话 isEmpty()
    //   与 apply 都会 NPE。方向是 fail-closed：旧档没提该组件，就是没动它。
    if (meta == null) {
      meta = new FieldDelta.Unchanged<>();
    }
    if (industries == null) {
      industries = new FieldDelta.Unchanged<>();
    }
    if (classes == null) {
      classes = new FieldDelta.Unchanged<>();
    }
    if (debtContracts == null) {
      debtContracts = new FieldDelta.Unchanged<>();
    }
    if (flows == null) {
      flows = new FieldDelta.Unchanged<>();
    }
    if (laborSupply == null) {
      laborSupply = new FieldDelta.Unchanged<>();
    }
    if (allocations == null) {
      allocations = new FieldDelta.Unchanged<>();
    }
    // ★ 第 8 个组件（T2）：同一口径（旧档没提该组件，就是没动它）。
    if (relations == null) {
      relations = new FieldDelta.Unchanged<>();
    }
    // ★ 第 9 个组件（H4）：同一口径（旧档没提该组件，就是没动它）。
    if (markets == null) {
      markets = new FieldDelta.Unchanged<>();
    }
    // ★ 第 10 个组件（M2.4）：同一口径（旧档没提该组件，就是没动它）。
    if (shipments == null) {
      shipments = new FieldDelta.Unchanged<>();
    }
    // ★ S1 的两个新组件：同一口径（旧档没提该组件，就是没动它）。
    if (memberships == null) {
      memberships = new FieldDelta.Unchanged<>();
    }
    if (assetShares == null) {
      assetShares = new FieldDelta.Unchanged<>();
    }
    // ★ 第 13 个组件（S3.2）：同一口径（旧档没提该组件，就是没动它）。
    if (operatorConditions == null) {
      operatorConditions = new FieldDelta.Unchanged<>();
    }
    // ★ 第 14 个组件（R3B.2）：同一口径（旧档没提该组件，就是没动它）。
    if (units == null) {
      units = new FieldDelta.Unchanged<>();
    }
    // ★★ R4-E2 第 15/16 个组件（需求账本 + 候选预设）：同一口径（旧档没提该组件，就是没动它）。
    if (demands == null) {
      demands = new FieldDelta.Unchanged<>();
    }
    if (candidates == null) {
      candidates = new FieldDelta.Unchanged<>();
    }
    // ★★ E1 的四个新组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (modes == null) {
      modes = new FieldDelta.Unchanged<>();
    }
    if (classStructures == null) {
      classStructures = new FieldDelta.Unchanged<>();
    }
    if (classPositions == null) {
      classPositions = new FieldDelta.Unchanged<>();
    }
    if (classStandings == null) {
      classStandings = new FieldDelta.Unchanged<>();
    }
    // ★★ E2 的两个新组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (productionOrganizations == null) {
      productionOrganizations = new FieldDelta.Unchanged<>();
    }
    if (assetRules == null) {
      assetRules = new FieldDelta.Unchanged<>();
    }
    // ★★ E3 的第 23/24 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (governments == null) {
      governments = new FieldDelta.Unchanged<>();
    }
    if (moneyIssuances == null) {
      moneyIssuances = new FieldDelta.Unchanged<>();
    }
    // ★★ E4a 第 25 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (pledges == null) {
      pledges = new FieldDelta.Unchanged<>();
    }
    // ★★ E5a 第 26/27 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (liquidationPolicies == null) {
      liquidationPolicies = new FieldDelta.Unchanged<>();
    }
    if (crisisSignals == null) {
      crisisSignals = new FieldDelta.Unchanged<>();
    }
    // ★★ E6a 第 28/29 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (modeTransitions == null) {
      modeTransitions = new FieldDelta.Unchanged<>();
    }
    if (classShares == null) {
      classShares = new FieldDelta.Unchanged<>();
    }
    // ★★ R1 第 30 个组件（阶层池经济持久状态）：旧变更集没提该组件，就是没动它。
    if (classFirst == null) {
      classFirst = new FieldDelta.Unchanged<>();
    }
    // ★★ P10.1 第 31 个组件（商号表）：旧变更集没提该组件，就是没动它。
    if (merchantFirms == null) {
      merchantFirms = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static EconomyChangeSet between(EconomyData base, EconomyData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new EconomyChangeSet(
        FieldDelta.diff(metaTable(base.meta()), metaTable(target.meta())),
        FieldDelta.diff(base.industries(), target.industries()),
        FieldDelta.diff(base.classes(), target.classes()),
        FieldDelta.diff(base.debtContracts(), target.debtContracts()),
        FieldDelta.diff(base.flows(), target.flows()),
        FieldDelta.diff(base.laborSupply(), target.laborSupply()),
        FieldDelta.diff(base.allocations(), target.allocations()),
        FieldDelta.diff(base.relations(), target.relations()),
        FieldDelta.diff(base.markets(), target.markets()),
        FieldDelta.diff(base.shipments(), target.shipments()),
        FieldDelta.diff(base.memberships(), target.memberships()),
        FieldDelta.diff(base.assetShares(), target.assetShares()),
        FieldDelta.diff(base.operatorConditions(), target.operatorConditions()),
        FieldDelta.diff(base.units(), target.units()),
        FieldDelta.diff(base.demands(), target.demands()),
        FieldDelta.diff(base.candidates(), target.candidates()),
        FieldDelta.diff(base.modes(), target.modes()),
        FieldDelta.diff(base.classStructures(), target.classStructures()),
        FieldDelta.diff(base.classPositions(), target.classPositions()),
        FieldDelta.diff(base.classStandings(), target.classStandings()),
        FieldDelta.diff(base.productionOrganizations(), target.productionOrganizations()),
        FieldDelta.diff(base.assetRules(), target.assetRules()),
        FieldDelta.diff(base.governments(), target.governments()),
        FieldDelta.diff(base.moneyIssuances(), target.moneyIssuances()),
        FieldDelta.diff(base.pledges(), target.pledges()),
        FieldDelta.diff(base.liquidationPolicies(), target.liquidationPolicies()),
        FieldDelta.diff(base.crisisSignals(), target.crisisSignals()),
        FieldDelta.diff(base.modeTransitions(), target.modeTransitions()),
        FieldDelta.diff(base.classShares(), target.classShares()),
        FieldDelta.diff(classFirstTable(base.classFirst()), classFirstTable(target.classFirst())),
        FieldDelta.diff(base.merchantFirms(), target.merchantFirms()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static EconomyData apply(EconomyChangeSet cs, EconomyData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new EconomyData(
        metaOf(FieldDelta.rebuild(metaTable(base.meta()), cs.meta(), Function.identity())),
        FieldDelta.rebuild(base.industries(), cs.industries(), IndustryId::parse),
        FieldDelta.rebuild(base.classes(), cs.classes(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(base.debtContracts(), cs.debtContracts(), DebtContractId::parse),
        FieldDelta.rebuild(base.flows(), cs.flows(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(base.laborSupply(), cs.laborSupply(), PeopleLotId::parse),
        FieldDelta.rebuild(base.allocations(), cs.allocations(), LaborAllocationId::parse),
        FieldDelta.rebuild(base.relations(), cs.relations(), ProductionUnitId::parse),
        FieldDelta.rebuild(base.markets(), cs.markets(), HexCoord::parse),
        FieldDelta.rebuild(base.shipments(), cs.shipments(), ShipmentId::parse),
        FieldDelta.rebuild(base.memberships(), cs.memberships(), MembershipId::parse),
        FieldDelta.rebuild(base.assetShares(), cs.assetShares(), AssetShareId::parse),
        FieldDelta.rebuild(
            base.operatorConditions(), cs.operatorConditions(), ProductionUnitId::parse),
        FieldDelta.rebuild(base.units(), cs.units(), ProductionUnitId::parse),
        FieldDelta.rebuild(base.demands(), cs.demands(), DemandId::parse),
        FieldDelta.rebuild(base.candidates(), cs.candidates(), CandidateId::parse),
        FieldDelta.rebuild(base.modes(), cs.modes(), ProductionModeId::parse),
        FieldDelta.rebuild(base.classStructures(), cs.classStructures(), ClassStructureId::parse),
        FieldDelta.rebuild(base.classPositions(), cs.classPositions(), ClassPositionId::parse),
        FieldDelta.rebuild(
            base.classStandings(), cs.classStandings(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(
            base.productionOrganizations(),
            cs.productionOrganizations(),
            ProductionOrganizationId::parse),
        FieldDelta.rebuild(base.assetRules(), cs.assetRules(), AssetRuleId::parse),
        FieldDelta.rebuild(base.governments(), cs.governments(), GovernmentId::parse),
        FieldDelta.rebuild(base.moneyIssuances(), cs.moneyIssuances(), MoneyIssuanceId::parse),
        FieldDelta.rebuild(base.pledges(), cs.pledges(), PledgeId::parse),
        FieldDelta.rebuild(
            base.liquidationPolicies(), cs.liquidationPolicies(), AssetRuleId::parse),
        FieldDelta.rebuild(base.crisisSignals(), cs.crisisSignals(), CrisisSignalId::parse),
        FieldDelta.rebuild(base.modeTransitions(), cs.modeTransitions(), ModeTransitionId::parse),
        FieldDelta.rebuild(base.classShares(), cs.classShares(), ClassShareId::parse),
        classFirstOf(
            FieldDelta.rebuild(
                classFirstTable(base.classFirst()), cs.classFirst(), key -> CLASS_FIRST_KEY)),
        FieldDelta.rebuild(
            base.merchantFirms(), cs.merchantFirms(), ProductionOrganizationId::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(meta.changed()
        || industries.changed()
        || classes.changed()
        || debtContracts.changed()
        || flows.changed()
        || laborSupply.changed()
        || allocations.changed()
        || relations.changed()
        || markets.changed()
        || shipments.changed()
        || memberships.changed()
        || assetShares.changed()
        || operatorConditions.changed()
        || units.changed()
        || demands.changed()
        || candidates.changed()
        || modes.changed()
        || classStructures.changed()
        || classPositions.changed()
        || classStandings.changed()
        || productionOrganizations.changed()
        || assetRules.changed()
        || governments.changed()
        || moneyIssuances.changed()
        || pledges.changed()
        || liquidationPolicies.changed()
        || crisisSignals.changed()
        || modeTransitions.changed()
        || classShares.changed()
        || classFirst.changed()
        || merchantFirms.changed());
  }

  /**
   * ★★ R1：{@code classFirst} 是单值组件，与 {@code meta} 同款投影进"至多一行的表"（键固定为 {@link
   * #CLASS_FIRST_KEY}）——差异/重建全走 {@link FieldDelta} 既有机制，不另写一份"单值差异"。
   *
   * <p>{@link ClassFirstState#empty()} 投影成空表（= 该组件缺席）；非空状态投影成一行。语义是纯的：空 → 有值 = Upsert、有值 → 空 =
   * Remove、有值 → 另一个值 = Upsert。
   */
  private static Map<String, ClassFirstState> classFirstTable(ClassFirstState state) {
    if (state == null || state.isEmpty()) {
      return Map.of();
    }
    return Map.of(CLASS_FIRST_KEY, state);
  }

  /** 上一条的逆：空表 ⇒ {@link ClassFirstState#empty()}。 */
  private static ClassFirstState classFirstOf(Map<String, ClassFirstState> table) {
    ClassFirstState state = table.get(CLASS_FIRST_KEY);
    return state == null ? ClassFirstState.empty() : state;
  }

  /** {@code Optional<EconomyMeta>} → 至多一行的表（键固定为 {@link #META_KEY}）。 */
  private static Map<String, EconomyMeta> metaTable(Optional<EconomyMeta> meta) {
    return meta.map(value -> Map.of(META_KEY, value)).orElseGet(Map::of);
  }

  /** 上一条的逆：单键表 → {@code Optional}。空表 ⇒ 未激活。 */
  private static Optional<EconomyMeta> metaOf(Map<String, EconomyMeta> table) {
    return Optional.ofNullable(table.get(META_KEY));
  }

  /**
   * ★★ S1：变更集里的键是 {@code toString()} 的产物 ⇒ 旧档的 CohortKey 串（{@code 0_0|rural|poor}，含 {@code |} 且非
   * legacy- 前缀）必须在**重建时**映射成 {@link HouseholdIds#ofLegacy}，否则它与快照迁移后的 classes 键对不上。 已是 {@code
   * legacy-} 前缀的新键 ⇒ 原样 parse（幂等）。
   */
  private static HouseholdId householdId(String text) {
    if (text != null && !text.startsWith(HouseholdIds.LEGACY_PREFIX) && text.indexOf('|') >= 0) {
      return HouseholdIds.ofLegacy(CohortKey.parse(text));
    }
    return HouseholdId.parse(text);
  }
}
