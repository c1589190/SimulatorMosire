package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEfficiencyState;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ {@code economy.ClearRegion}（P1b1，2026-10-01 后端 + MCP 稳定化计划）：<b>GM-only 区域经济数据清空命令</b>—— 按目标
 * Region 的 hex 集清逐格经济记录，不动 map/unit/GOV/决策人结构。
 *
 * <pre>{@code
 * {"regionId":"701"}
 * }</pre>
 *
 * <p>★★ <b>至少清空</b>：{@code industries}（{@link IndustryHexKeys#hexKeyOf} 命中目标格键）、{@code markets}
 * （格键命中）、以及这些 industry 对应 unit 的 {@code relations}/{@code units}/{@code operatorConditions}。
 *
 * <p>★★ <b>逐格可靠可定位的连带记录一并清</b>（不变量要求它们与 industry/class 同生共死，否则下一次构造当场违约）：
 *
 * <ul>
 *   <li>{@code assetShares}（按 {@code industry} 定位）与引用它们的 {@code pledges}；
 *   <li>{@code classes}（按 {@code HouseholdEconomy.view().hex()} 定位）、同键的 {@code flows}、{@code
 *       memberships}、 {@code classStandings}、{@code classShares}、{@code allocations}（按 {@code
 *       household} 定位）；
 *   <li>清空区域内家户所涉的 {@code debtContracts}（按 debtor/creditor 定位）；引用被清合同/份额的 {@code pledges}；
 *   <li>{@code demands}：HEX 范围按格键命中；HOUSEHOLD 范围按被清家户命中；
 *   <li>{@code crisisSignals}：{@code signal.hex()} 命中目标格，或点名的家户被清（避免悬空引用）；
 *   <li>{@code productionOrganizations}：unit / 劳动来源家户 / 资产份额任一被清即清；引用被清组织的 {@code modeTransitions}。
 * </ul>
 *
 * <p>★★ <b>有意不碰的表（未清边界，具名）</b>：
 *
 * <ul>
 *   <li>{@code meta}：世界级激活状态，按区域清会误伤其它区域；
 *   <li>{@code shipments}：跨区在途货物（route 起终点跨区域），按任一区删除会凭空销毁另一区的货权凭据；
 *   <li>{@code laborSupply}：键 {@code PeopleLotId} 只带命名约定——农村批次能解析出格，城镇批次只带 cityId（city→hex 在
 *       social/map 侧，economy 看不见）；只清一半会让同一格城乡 split-brain ⇒ 整表不清。它的悬空由被清的 allocations
 *       解开，剩余行不违反任何构造期不变量；
 *   <li>{@code candidates} / {@code modes} / {@code classStructures} / {@code classPositions} /
 *       {@code assetRules} / {@code liquidationPolicies}：世界级/制度定义，无格；
 *   <li>{@code governments} / {@code moneyIssuances}：世界级发行主体与世界总量审计；发行量不按区域归属，清它会破坏 货币守恒审计。
 * </ul>
 *
 * <p>★★ <b>为什么 classes/memberships 必须同一次构造（其余 28 个组件走 with* 链）</b>：30 个组件里 {@code memberships ↔
 * classes} 有**双向互锁**（membership 必须引用现存家户 ∧ 非空 memberships 的 Σcount 必须等于
 * ΣClassRow.population）——先删哪一侧的中间态都非法。本类先按依赖序对其余组件逐 {@code with*}（先摘引用方、再摘被引用方，
 * 每个中间态都合法），最后把这两张表连同其余 28 个组件的结果**一次规范构造**，再走 {@link EconomyChangeSet#between(EconomyData,
 * EconomyData)} 派生变更集；落盘路径与逐组件替换完全同一条（铁律 2/5）。
 *
 * <p>★ <b>Region 必须先在 {@code map.regions()} 里存在</b>：缺 map 切片/切片类型不对是装配故障（{@link
 * IllegalStateException} 当场炸，不走拒绝路径）；payload 的 regionId 查不到是 {@link HandlerOutcome.Rejected}（零
 * revision）。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 仍注册、仍进 {@code commandTargets}，GM 的 {@code
 * simos.command.submit} 可提交；令白名单/RegisterEffect/决策人目录排除它。
 *
 * <p>★ <b>{@link CommandTargets} 的诚实边界</b>：签名拿不到 state；逐格 economy 资源路径要读目标 Region 的 hex 集才能展开， 本命令
 * GM-only、不进入决策令/裁决目标检查 ⇒ 返回空列表（fail-closed）。
 */
public final class EconomyClearRegionHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：catalog 提示、Shell 注册与组合工具都从这里取/对齐）。 */
  public static final String TYPE = "economy.ClearRegion";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  /** ★ 本命令没有可签名的目标（见类注）；仍解析载荷形状，坏载荷照旧以 {@link IllegalArgumentException} 出面。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    requireRegionId(EconomyCommandPayloads.parseObject(TYPE, payloadJson));
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    String regionId = "-";
    Region region;
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      regionId = requireRegionId(payload);
      region = requireRegion(state, regionId);
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_CLEAR_REGION_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "region",
                  regionId,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "ECONOMY_CLEAR_REGION_CRITERIA",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "region",
                  regionId,
                  "hexes",
                  region.hexes().size(),
                  "industriesBefore",
                  base.industries().size(),
                  "householdsBefore",
                  base.classes().size()));
    }
    try {
      EconomyData next = cleared(base, region.hexes());
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_CLEAR_REGION_APPLIED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "region",
                  regionId,
                  "hexes",
                  region.hexes().size(),
                  "industriesRemoved",
                  base.industries().size() - next.industries().size(),
                  "unitsRemoved",
                  base.units().size() - next.units().size(),
                  "householdsRemoved",
                  base.classes().size() - next.classes().size(),
                  "marketsRemoved",
                  base.markets().size() - next.markets().size(),
                  "demandsRemoved",
                  base.demands().size() - next.demands().size(),
                  "enterprisesRemoved",
                  base.productionOrganizations().size() - next.productionOrganizations().size(),
                  "debtContractsRemoved",
                  base.debtContracts().size() - next.debtContracts().size(),
                  "classSharesRemoved",
                  base.classShares().size() - next.classShares().size()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, next));
    } catch (IllegalArgumentException | IllegalStateException e) {
      // ★ 跨表守卫/旧档迁移在**状态语义**上拒收（如 legacy memberships 无法保持全局守恒）：这是命令可读的
      //   拒绝理由，不是整条推进失败；装配故障（缺 map 切片）已在上面单独抛出。
      // ★ 2026-10-23 用户裁定：被拒绝一律 INFO（reason 过脱敏，载荷明文不进日志）。
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_CLEAR_REGION_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "region",
                  regionId,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 目标 Region 的格键集合（{@link IndustryHexKeys#hexKey(int, int)} 是它的唯一拼写点）。 */
  private static Set<String> targetHexKeys(Set<HexCoord> targetHexes) {
    Set<String> keys = new LinkedHashSet<>();
    for (HexCoord hex : targetHexes) {
      keys.add(IndustryHexKeys.hexKey(hex.q(), hex.r()));
    }
    return keys;
  }

  /**
   * 清空目标 Region 的全部逐格经济记录（纯函数：不碰 {@code base}，返回全新状态；未动的组件逐值带过）。
   *
   * <p>★ 不变量由 {@link EconomyData} 构造器 fail-closed 把守：任何漏清的悬空引用都会在这里当场抛， 不会静默落一条"看起来清了、其实坏了"的
   * revision。
   */
  private static EconomyData cleared(EconomyData base, Set<HexCoord> targetHexes) {
    Set<String> targetHexKeys = targetHexKeys(targetHexes);

    // ① 产业：id 的 <kind>@<q>_<r> 解析命中目标格键（解析不出的 id 不动——定位不可靠，见类注）。
    Set<IndustryId> removedIndustries = new LinkedHashSet<>();
    for (IndustryId id : base.industries().keySet()) {
      if (IndustryHexKeys.hexKeyOf(id).filter(targetHexKeys::contains).isPresent()) {
        removedIndustries.add(id);
      }
    }
    Set<String> removedIndustryValues = idValues(removedIndustries);

    // ② unit / 资产份额：按 unit.industry / share.industry 定位到被清产业。
    Set<ProductionUnitId> removedUnits = new LinkedHashSet<>();
    for (Map.Entry<ProductionUnitId, ProductionProcess> entry : base.units().entrySet()) {
      if (removedIndustries.contains(entry.getValue().industry())) {
        removedUnits.add(entry.getKey());
      }
    }
    Set<String> removedUnitValues = unitIdValues(removedUnits);
    Set<AssetShareId> removedShares = new LinkedHashSet<>();
    for (Map.Entry<AssetShareId, OwnershipStake> entry : base.assetShares().entrySet()) {
      if (removedIndustries.contains(entry.getValue().industry())) {
        removedShares.add(entry.getKey());
      }
    }

    // ③ 家户行：view().hex() 定位（CohortKey 的"家户当前视图"就是区域归属）。
    Set<HouseholdId> removedClasses = new LinkedHashSet<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        base.classes().entrySet()) {
      if (targetHexes.contains(householdEconomyEntry.getValue().view().hex())) {
        removedClasses.add(householdEconomyEntry.getKey());
      }
    }

    // ④ 各表新副本：没提到的组件直接 base 原值（= 逐组件 with* 的"其余原样带过"）。
    Map<IndustryId, Industry> industries = withoutKeys(base.industries(), removedIndustries);
    Map<ProductionUnitId, ProductionProcess> units = withoutKeys(base.units(), removedUnits);
    Map<AssetShareId, OwnershipStake> assetShares = withoutKeys(base.assetShares(), removedShares);
    Map<ProductionUnitId, ProductionRules> relations = withoutKeys(base.relations(), removedUnits);
    Map<ProductionUnitId, OperatorCondition> operatorConditions =
        withoutKeys(base.operatorConditions(), removedUnits);
    Map<HouseholdId, HouseholdEconomy> householdEconomies =
        withoutKeys(base.classes(), removedClasses);
    Map<HouseholdId, FlowRow> flows = withoutKeys(base.flows(), removedClasses);
    Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        new LinkedHashMap<>(base.allocations());
    laborCommitments
        .entrySet()
        .removeIf(
            entry -> {
              HouseholdLaborCommitment laborCommitment = entry.getValue();
              return removedClasses.contains(laborCommitment.household())
                  || isRemovedIndustryActor(laborCommitment, removedIndustryValues)
                  || removedUnitValues.contains(laborCommitment.activity());
            });
    Map<DebtContractId, DebtContract> debtContracts = new LinkedHashMap<>(base.debtContracts());
    debtContracts
        .entrySet()
        .removeIf(
            entry ->
                removedClasses.contains(entry.getValue().debtor())
                    || removedClasses.contains(entry.getValue().creditor()));
    Set<DebtContractId> removedContracts = keysRemoved(base.debtContracts(), debtContracts);
    Map<PledgeId, Pledge> pledges = new LinkedHashMap<>(base.pledges());
    pledges
        .entrySet()
        .removeIf(
            entry ->
                removedContracts.contains(entry.getValue().debtContractId())
                    || removedShares.contains(entry.getValue().assetShareId()));

    Map<ProductionOrganizationId, ProductionEnterprise> enterprises =
        new LinkedHashMap<>(base.productionOrganizations());
    enterprises
        .entrySet()
        .removeIf(
            entry -> {
              ProductionEnterprise enterprise = entry.getValue();
              return enterprise.unitId().map(removedUnits::contains).orElse(false)
                  || intersects(enterprise.laborSources(), removedClasses)
                  || intersects(enterprise.assetSources(), removedShares);
            });
    Set<ProductionOrganizationId> removedEnterprises =
        keysRemoved(base.productionOrganizations(), enterprises);
    // ★★ P10.1：商号表随它指名的组织一起移除 —— 否则新状态会出现"商号指向已删组织"的悬空引用，
    //    EconomyData 构造期守卫会当场 fail-closed（宁可同步摘掉，不把区域清空卡死）。空表时逐值 no-op。
    Map<ProductionOrganizationId, MerchantFirm> merchantFirms =
        withoutKeys(base.merchantFirms(), removedEnterprises);
    Map<ModeTransitionId, ModeTransition> modeTransitions =
        new LinkedHashMap<>(base.modeTransitions());
    modeTransitions
        .entrySet()
        .removeIf(entry -> removedEnterprises.contains(entry.getValue().organizationId()));
    Set<ModeTransitionId> removedTransitions = keysRemoved(base.modeTransitions(), modeTransitions);
    Map<ClassShareId, ClassShare> classShares = new LinkedHashMap<>(base.classShares());
    classShares
        .entrySet()
        .removeIf(
            entry ->
                removedClasses.contains(entry.getValue().householdId())
                    || removedTransitions.contains(entry.getValue().transitionId()));
    Map<HouseholdId, HouseholdClassMembership> classMemberships =
        withoutKeys(base.classStandings(), removedClasses);

    Map<HexCoord, Market> markets = withoutKeys(base.markets(), targetHexes);
    // ★★ Z1：产品产出数量覆盖表按被清产业（`IndustryId` 里的格键）删除 ⇒ 与 industries 同生共死；
    //   生产效率表按被清 unit 删除 ⇒ 与 units 同生共死（unit 的产业已由 removedUnits 定位）。
    Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides =
        withoutKeys(base.outputQuantityOverrides(), removedIndustries);
    Map<ProductionUnitId, ProductionEfficiencyState> productionEfficiency =
        withoutKeys(base.productionEfficiency(), removedUnits);
    Map<DemandId, HouseholdDemand> householdDemands = new LinkedHashMap<>(base.demands());
    householdDemands
        .entrySet()
        .removeIf(
            entry -> {
              HouseholdDemand demand = entry.getValue();
              return demand.household().map(removedClasses::contains).orElse(false)
                  || demand.hex().map(targetHexes::contains).orElse(false);
            });
    Map<CrisisSignalId, HexCrisisSignal> crisisSignals = new LinkedHashMap<>(base.crisisSignals());
    crisisSignals
        .entrySet()
        .removeIf(
            entry ->
                targetHexes.contains(entry.getValue().hex())
                    || intersects(entry.getValue().households(), removedClasses));

    // ⑤ 逐组件 with*：链序 = 依赖序（先摘引用方、再摘被引用方），每个中间态都满足 EconomyData 的构造期守卫。
    //    ★ classes/memberships 不在这条链里——它们有双向互锁（membership 必须引用现存家户 ∧ 非空 memberships 的
    //      Σcount 必须等于 ΣClassRow.population），只有同一次构造才能同时摘掉；见 ⑥。
    EconomyData staged =
        base.withRelations(relations)
            .withOperatorConditions(operatorConditions)
            .withPledges(pledges)
            .withClassShares(classShares)
            .withModeTransitions(modeTransitions)
            .withProductionEnterprises(enterprises)
            .withLaborCommitments(laborCommitments)
            .withFlows(flows)
            .withDebtContracts(debtContracts)
            .withHouseholdDemands(householdDemands)
            .withCrisisSignals(crisisSignals)
            .withClassMemberships(classMemberships)
            .withProcesses(units)
            .withOwnershipStakes(assetShares)
            .withMarkets(markets)
            .withIndustries(industries);

    // ⑥ classes 与 memberships **同一次规范构造**：先删任一侧的中间态都非法（另一侧悬空或 Σ 守恒失衡）。
    //    其余 28 个组件取 staged 的逐 with* 结果 ⇒ 等价于"30 个组件的同时复合"。
    return new EconomyData(
        staged.meta(),
        staged.industries(),
        householdEconomies,
        staged.debtContracts(),
        staged.flows(),
        staged.allocations(),
        staged.relations(),
        staged.markets(),
        staged.shipments(),
        staged.assetShares(),
        staged.operatorConditions(),
        staged.units(),
        staged.demands(),
        staged.candidates(),
        staged.modes(),
        staged.classStructures(),
        staged.classPositions(),
        staged.classStandings(),
        staged.productionOrganizations(),
        staged.assetRules(),
        staged.governments(),
        staged.moneyIssuances(),
        staged.pledges(),
        staged.liquidationPolicies(),
        staged.crisisSignals(),
        staged.modeTransitions(),
        staged.classShares(),
        merchantFirms,
        // ★★ P4a：清区域不碰周期规则，原样带过 staged 的表。
        staged.periodicAdjustments(),
        // ★★ Z1：两个新组件按格键删除（先摘引用方、被引用的 industry/unit 同一次构造里一起摘）。
        outputQuantityOverrides,
        productionEfficiency);
  }

  /** 保序拷贝并删掉给定键（返回可变表，交给下一次过滤；构造器会再冻）。 */
  private static <K, V> Map<K, V> withoutKeys(Map<K, V> source, Set<K> removedKeys) {
    Map<K, V> out = new LinkedHashMap<>(source);
    out.keySet().removeAll(removedKeys);
    return out;
  }

  /** 两份表的键差（before − after）。 */
  private static <K> Set<K> keysRemoved(Map<K, ?> before, Map<K, ?> after) {
    Set<K> removed = new LinkedHashSet<>(before.keySet());
    removed.removeAll(after.keySet());
    return removed;
  }

  /** 产业 id 集合的裸值集合（配额 actor 交叉判据用）。 */
  private static Set<String> idValues(Set<IndustryId> ids) {
    Set<String> values = new LinkedHashSet<>();
    for (IndustryId id : ids) {
      values.add(id.value());
    }
    return values;
  }

  /** unit id 集合的裸值集合（配额 activity 交叉判据用）。 */
  private static Set<String> unitIdValues(Set<ProductionUnitId> ids) {
    Set<String> values = new LinkedHashSet<>();
    for (ProductionUnitId id : ids) {
      values.add(id.value());
    }
    return values;
  }

  /**
   * 这条配额是否指着被清产业的<b>产业型主体</b>（{@link ActorKind#ESTATE}/{@link ActorKind#WORKSHOP}）。
   *
   * <p>★ 只对这两档判：它们必须解析到现存产业，产业一清就悬空（{@code EconomyData} 守卫当场拒）。{@link ActorKind#HOUSEHOLD}
   * 是自由档（actor id 可与产业同名），若它本身与 activity 都未被清则原样保留——不把"家户恰好叫某个产业名"误伤成区域数据。
   */
  private static boolean isRemovedIndustryActor(
      HouseholdLaborCommitment laborCommitment, Set<String> removedIndustryValues) {
    if (!removedIndustryValues.contains(laborCommitment.actor().id())) {
      return false;
    }
    ActorKind kind = laborCommitment.actor().kind();
    return kind == ActorKind.ORGANIZATION;
  }

  /** 两个集合是否有交（保序集合的线性判；生产组织名单都很小）。 */
  private static <T> boolean intersects(Collection<T> values, Set<T> removed) {
    for (T value : values) {
      if (removed.contains(value)) {
        return true;
      }
    }
    return false;
  }

  /** payload 的 regionId：必填、非空文本。 */
  private static String requireRegionId(JsonNode payload) {
    return EconomyCommandPayloads.requireText(TYPE, payload, "regionId");
  }

  /**
   * 从只读 state 的 map 切片里取真档 Region：缺切片/切片类型不对 = 装配故障（{@link IllegalStateException} 当场炸）； regionId
   * 查不到 = 命令载荷错误（{@link IllegalArgumentException}，由 {@link #handle} 折成 Rejected）。
   */
  private static Region requireRegion(SimulationState state, String regionId) {
    Snapshot mapModule =
        state.module("map").orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障）"));
    if (!(mapModule instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + mapModule.getClass().getName());
    }
    Region region = mapSnapshot.map().regions().get(new RegionId(regionId));
    if (region == null) {
      throw new IllegalArgumentException("当前 map 里没有这个 region: " + regionId);
    }
    return region;
  }
}
