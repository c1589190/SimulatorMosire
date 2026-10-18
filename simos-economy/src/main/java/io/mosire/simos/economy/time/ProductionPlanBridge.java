package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionRosterProbeParticipant;
import io.mosire.simos.production.contract.ProductionRosterProbeRequest;
import io.mosire.simos.production.contract.ProductionRosterProbeResult;
import io.mosire.simos.production.contract.RoleDistribution;
import io.mosire.simos.production.contract.RoleDistributionParticipant;
import io.mosire.simos.production.contract.RoleDistributionRequest;
import io.mosire.simos.production.contract.RoleDistributionResult;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★★ <b>D-032 P3c3：M3 预期编排与 M6 实际编排共用的只读桥</b>。
 *
 * <p>本类只做四件事：① 从生产运行时取 {@code probeRoster} 只读事实（A2/A3）；② 用 {@link RoleDistribution} 与本地牌价算逐户位置收益
 * （A4 的"产品份额值 + 工资应收 − 工资应付 − 投入负担"）；③ 用 {@link ProductionLandFilter} / 跨 hex 判据判位置可行性；④ 把 M3
 * 的物理缺口/剩余转成 {@link MarketIntent}。
 *
 * <p>★ 本类<b>不</b>写库存、货币、土地、债务、劳动承诺或生产实例；不落 {@code EconomyData}/ChangeSet/Codec。
 */
final class ProductionPlanBridge {

  private static final Logger PRODUCTION = EconomyLog.settlement();

  static final String SELF_LABOR_CROSS_HEX = "cross-hex-self-labor";
  static final String WORKER_NOT_ON_LAND_HEX = "worker-not-on-land-hex";
  static final String LAND_ACQUIRED_THIS_ROUND = "land-acquired-this-round-excluded";
  static final String LAND_LOST = "position-land-lost";
  static final String UNIT_MISSING = "position-unit-missing";

  /** 毫价格 / 整单位 的换算分母（土地为毫价格 / 整亩，见 {@link MarketTable}）。 */
  private static final long MILLI_PER_UNIT = 1000L;

  private ProductionPlanBridge() {}

  // ── 市场/家户事实 ────────────────────────────────────────────────────────────────────────

  static MarketTable marketOf(SettlementDayContext ctx, HouseholdId household) {
    HouseholdEconomy row = ctx.householdEconomies.get(household);
    if (row == null) {
      return null;
    }
    return MarketIndex.of(ctx.markets, ctx.base.marketZones()).at(row.view().hex());
  }

  static CurrencyId payCurrencyOf(MarketTable market) {
    return market == null ? null : market.numeraire();
  }

  static long goodsOf(SettlementDayContext ctx, HouseholdId household, CommodityId commodity) {
    return ctx.accounts
        .householdGoods()
        .getOrDefault(household, Map.of())
        .getOrDefault(commodity, 0L);
  }

  static long moneyOf(SettlementDayContext ctx, HouseholdId household, CurrencyId currency) {
    if (currency == null) {
      return 0L;
    }
    return ctx.accounts
        .householdMoney()
        .getOrDefault(household, Map.of())
        .getOrDefault(currency, 0L);
  }

  static long askValueMilli(MarketTable market, Map<CommodityId, Long> quantities) {
    if (market == null) {
      return 0L;
    }
    long total = 0L;
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (!market.hasPrice(entry.getKey())) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(entry.getValue(), market.askPriceOf(entry.getKey()))
                  / MILLI_PER_UNIT);
    }
    return total;
  }

  static long bidValueMilli(MarketTable market, Map<CommodityId, Long> quantities) {
    if (market == null) {
      return 0L;
    }
    long total = 0L;
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (!market.hasPrice(entry.getKey())) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(entry.getValue(), market.bidPriceOf(entry.getKey()))
                  / MILLI_PER_UNIT);
    }
    return total;
  }

  // ── 地点 / 土地 ──────────────────────────────────────────────────────────────────────────

  static HexCoord hexOf(ProductionLocation location) {
    try {
      return HexCoord.parse(location.value());
    } catch (RuntimeException bad) {
      return null;
    }
  }

  static boolean isLandBased(IndustryDefinitionView industry) {
    return industry.recipe().capacityPerUnit().containsKey(AssetKind.LAND);
  }

  /**
   * ★ 跨 hex 与土地排除的唯一判据：
   *
   * <ul>
   *   <li>operator 自耕（PRODUCT_SHARE 角色）：本户居住 hex 必须 == 土地 hex，否则 {@link #SELF_LABOR_CROSS_HEX}；
   *   <li>hired_laborer（MONEY_WAGE 角色）：雇工居住 hex 必须 == 土地 hex，否则 {@link #WORKER_NOT_ON_LAND_HEX}；
   *   <li>位置所在 hex 的 LAND 份额若全部命中本轮新取得 marker ⇒ {@link #LAND_ACQUIRED_THIS_ROUND}。
   * </ul>
   *
   * <p>非土地型方案（{@code capacityPerUnit} 不含 LAND）不做跨 hex 判据，避免误伤工坊类生产。
   */
  static Optional<String> positionLocationRejection(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry) {
    if (!isLandBased(industry)) {
      return Optional.empty();
    }
    HexCoord unitHex = hexOf(unit.location());
    if (unitHex == null) {
      return Optional.of("asset-location-missing");
    }
    for (ProductionParticipant participant : roster.participants()) {
      ProductionRoleDefinition role = roleOf(industry, participant.role()).orElse(null);
      if (role == null) {
        return Optional.of("role-unknown:" + participant.role().value());
      }
      HouseholdEconomy row = ctx.householdEconomies.get(participant.household());
      if (row == null) {
        return Optional.of("household-missing:" + participant.household().value());
      }
      boolean onHex = unitHex.equals(row.view().hex());
      if (!onHex && role.payment() instanceof RolePayment.ProductShare) {
        return Optional.of(SELF_LABOR_CROSS_HEX);
      }
      if (!onHex && role.payment() instanceof RolePayment.MoneyWage) {
        return Optional.of(WORKER_NOT_ON_LAND_HEX);
      }
    }
    if (landAllExcluded(ctx, unit.location())) {
      return Optional.of(LAND_ACQUIRED_THIS_ROUND);
    }
    if (!landStillOperatedBy(ctx, unit, roster)) {
      return Optional.of(LAND_LOST);
    }
    return Optional.empty();
  }

  /**
   * 卖地/失地判据：土地型 unit 的地点若存在 LAND 份额，则组织者（或 unit operator）必须仍是其中至少一块的 owner/operator，且该块不是本轮新取得。
   *
   * <p>没有任何 LAND 份额 ⇒ 不判（旧档/非地权形态不误伤）。
   */
  static boolean landStillOperatedBy(
      SettlementDayContext ctx, ProductionProcess unit, ProductionRoster roster) {
    io.mosire.simos.actor.api.actor.ActorRef organizerActor =
        HouseholdActors.of(roster.organizerHousehold());
    boolean sawLand = false;
    for (OwnershipStake share : ctx.assetShares.values()) {
      if (share.asset() != AssetKind.LAND
          || !unit.location().value().equals(share.location().value())) {
        continue;
      }
      sawLand = true;
      boolean party =
          share.owner().equals(organizerActor)
              || share.operator().equals(organizerActor)
              || share.operator().equals(unit.operator());
      if (party && ProductionLandFilter.landAllowed(share, ctx.landAcquiredThisRound())) {
        return true;
      }
    }
    return !sawLand;
  }

  /** 该地点是否“有 LAND 份额但全部是本轮新取得”。没有任何 LAND 份额 ⇒ false（不误判非土地方案）。 */
  static boolean landAllExcluded(SettlementDayContext ctx, ProductionLocation location) {
    boolean sawLand = false;
    for (OwnershipStake share : ctx.assetShares.values()) {
      if (share.asset() != AssetKind.LAND
          || !location.value().equals(share.location().value())) {
        continue;
      }
      sawLand = true;
      if (ProductionLandFilter.landAllowed(share, ctx.landAcquiredThisRound())) {
        return false;
      }
    }
    return sawLand;
  }

  static Optional<ProductionRoleDefinition> roleOf(
      IndustryDefinitionView industry, ProductionRoleId role) {
    for (ProductionRoleDefinition candidate : industry.roles()) {
      if (candidate.id().equals(role)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * ★ 有效名册：优先持久 {@link ProductionRoster}；没有时对“单角色方案”合成一份瞬态名册（organizer = unit
   * operator 家户）。
   *
   * <p>为什么不合成多角色名册：多角色无名册在既有路径里就是 {@code roster-required}（不猜角色），P3c3 不改变该语义。
   */
  static ProductionRoster effectiveRoster(
      SettlementDayContext ctx,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      Map<ProductionUnitId, ProductionRoster> persisted) {
    ProductionRoster roster = persisted.get(unit.id());
    if (roster != null) {
      return roster;
    }
    if (industry.roles().size() != 1) {
      return null;
    }
    HouseholdId organizer = ctx.householdOfActor.get(unit.operator());
    if (organizer == null || !ctx.householdEconomies.containsKey(organizer)) {
      return null;
    }
    HouseholdEconomy row = ctx.householdEconomies.get(organizer);
    long dailyLabor = Math.max(0L, row.laborMilli());
    return new ProductionRoster(
        unit.id(),
        industry.id(),
        List.of(
            new ProductionParticipant(
                organizer, industry.roles().get(0).id(), true, dailyLabor, List.of(), 0L)));
  }

  // ── roster 实际计划雇工（P3e：WageDemand target 的事实源）──────────────────────────────

  /**
   * ★★★ <b>P3e：roster 里“实际计划雇工”的逐角色日劳动合计</b>。
   *
   * <p>只统计<b>非 organizer</b> 且角色支付形态为 {@link RolePayment.MoneyWage} 的参与项；单位 = 毫劳动/日。这是 M3
   * 工资成本、{@link WageDemand} target 与 organizer 位置 {@code hiredLaborMilliPerDay} 的共同事实源，不得各自另算一份。
   */
  static Map<ProductionRoleId, Long> hiredWageLaborByRole(
      ProductionRoster roster, IndustryDefinitionView industry) {
    Objects.requireNonNull(roster, "roster");
    Objects.requireNonNull(industry, "industry");
    Map<ProductionRoleId, Long> byRole = new LinkedHashMap<>();
    for (ProductionParticipant participant : roster.participants()) {
      if (participant.household().equals(roster.organizerHousehold())) {
        continue;
      }
      ProductionRoleDefinition role = roleOf(industry, participant.role()).orElse(null);
      if (role == null || !(role.payment() instanceof RolePayment.MoneyWage)) {
        continue;
      }
      byRole.merge(participant.role(), participant.plannedLaborMilliPerDay(), Math::addExact);
    }
    return byRole;
  }

  /** 同上，按当刻 persisted/effective roster 解析（WageDemand 生成用；无 roster ⇒ 空表）。 */
  static Map<ProductionRoleId, Long> hiredWageLaborByRole(
      SettlementDayContext ctx, ProductionProcess unit, IndustryDefinitionView industry) {
    Map<ProductionUnitId, ProductionRoster> rosters =
        ctx.session.sheet().productionRostersOrBase();
    ProductionRoster roster = effectiveRoster(ctx, unit, industry, rosters);
    return roster == null ? Map.of() : hiredWageLaborByRole(roster, industry);
  }

  /** organizer 位置的“实际计划雇工合计”（per day）；只对 organizer 位置写非零。 */
  static long organizerHiredLaborMilliPerDay(Map<ProductionRoleId, Long> hiredWageLaborByRole) {
    long total = 0L;
    for (long labor : hiredWageLaborByRole.values()) {
      total = Math.addExact(total, labor);
    }
    return total;
  }

  // ── probeRoster（A2/A3 的唯一调用点）────────────────────────────────────────────────────

  /**
   * 用 roster 形态调用 production 的只读试算：逐参与户条件、逐户效率、零劳动 organizer + U20 招工目标都在这一条入口表达。
   * 物理产出/投入/规模一律取自 production，不在 economy 复制效率公式。
   */
  static ProductionRosterProbeResult probeRosterOf(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry) {
    return probeRosterOf(ctx, unit, roster, industry, List.of());
  }

  /**
   * ★★★ P4c1 重载：{@code wageTargets} 优先取本 tick 的 {@link WageDemand}（M6 传 M3 的清单），无则按同一
   * target 公式的工程默认（{@link WageQuoteBridge#wageTargetsOf}）；不读旧工资表。
   */
  static ProductionRosterProbeResult probeRosterOf(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry,
      List<WageDemand> wageDemands) {
    Map<HouseholdId, HouseholdProductionConditions> conditions = new LinkedHashMap<>();
    List<ProductionRosterProbeParticipant> participants = new ArrayList<>();
    Map<HouseholdId, ExternalAttributeFacts> attributes = new LinkedHashMap<>();
    long cycleDays = Math.max(1L, industry.cycleDays());
    for (ProductionParticipant participant : roster.participants()) {
      participants.add(
          new ProductionRosterProbeParticipant(
              participant.household(),
              participant.role(),
              Math.multiplyExact(participant.plannedLaborMilliPerDay(), cycleDays)));
      conditions.putIfAbsent(
          participant.household(),
          new HouseholdProductionConditions(
              participant.household(),
              0L,
              Map.of(),
              ProductionProbeInputs.inputsOf(
                  ctx.accounts.householdGoods().get(participant.household()), industry),
              ProductionProbeInputs.masteryOf(
                  ctx.session.productionRuntime(), participant.household(), industry),
              ProductionProbeInputs.experienceOf(
                  ctx.session.productionRuntime(), participant.household(), industry)));
      attributes.putIfAbsent(participant.household(), ExternalAttributeFacts.EMPTY);
    }
    Map<AssetKind, Long> occupancy =
        ProductionProbeInputs.positiveAssets(ctx.settlementIndex.assetAllocationOf(unit.id()));
    // ★★★ P4c3：招工目标优先来自 WageDemand（按 role 聚合），无则工程默认；不读旧工资表。
    Map<ProductionRoleId, Long> wageTargets =
        WageQuoteBridge.wageTargetsOf(ctx, unit, industry, wageDemands);
    return ctx.session
        .productionRuntime()
        .probeRoster(
            new ProductionRosterProbeRequest(
                industry.id(),
                roster.organizerHousehold(),
                participants,
                conditions,
                occupancy,
                ctx.session.productionRuntime().efficiencyOf(unit.id()),
                attributes,
                wageTargets));
  }

  // ── 逐户位置收益（A4）────────────────────────────────────────────────────────────────────

  /** 一次 M3/M6 候选评估的中间事实：位置 + 生产试算（物理量供 M3 转意图、M6 再评估）。 */
  record PositionFacts(
      HouseholdId household,
      PositionPlan position,
      ProductionRosterProbeResult probe,
      HouseholdId organizer,
      Map<CommodityId, Long> expectedOutput,
      Map<CommodityId, Long> inputRequired) {
    PositionFacts {
      Objects.requireNonNull(household, "PositionFacts.household 不得为 null");
      Objects.requireNonNull(position, "PositionFacts.position 不得为 null");
      Objects.requireNonNull(probe, "PositionFacts.probe 不得为 null");
      Objects.requireNonNull(organizer, "PositionFacts.organizer 不得为 null");
      expectedOutput = Map.copyOf(expectedOutput);
      inputRequired = Map.copyOf(inputRequired);
    }
  }

  /**
   * 评估一份 roster 在本遍信息集下的逐参与户位置经济。
   *
   * <p>收入 = 本户产品份额值（本地 bid）+ 工资应收；成本 = 工资应付（organizer）+ 投入负担（组织者/投入供方）。排序键 =
   * {@link PositionEconomics#positionYieldPerLaborScaled()}，M3/M6 共用同一算式。
   */
  static List<PositionFacts> evaluateRoster(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry) {
    return evaluateRoster(ctx, unit, roster, industry, List.of());
  }

  /**
   * ★★★ P4c1 重载：M6 传 M3 的 {@link WageDemand} 清单（只读同一 tick 快照），M3 第一遍传空表。
   *
   * <p>工资报价一律经 {@link WageQuoteBridge#resolveWageQuote}（锚唯一权威；缺锚 ⇒ 具名 {@code
   * wage-anchor-missing} 的位置不可行）。
   */
  static List<PositionFacts> evaluateRoster(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry,
      List<WageDemand> wageDemands) {
    MarketTable market = marketOf(ctx, roster.organizerHousehold());
    ProductionRosterProbeResult probe = probeRosterOf(ctx, unit, roster, industry, wageDemands);
    if (!probe.feasible() || probe.outputScale() <= 0L) {
      return List.of();
    }
    Map<CommodityId, Long> netPools = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, ProductionOutputNetting.Netted> entry :
        ProductionOutputNetting.netAll(probe.expectedOutput()).entrySet()) {
      netPools.put(entry.getKey(), entry.getValue().net());
    }
    List<RoleDistributionParticipant> distributionParticipants = new ArrayList<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> plannedByParticipant =
        new LinkedHashMap<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> rightsWeights =
        new LinkedHashMap<>();
    long cycleDays = Math.max(1L, industry.cycleDays());
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      distributionParticipants.add(
          new RoleDistributionParticipant(
              participant.household(), participant.role(), participant.configuredWeight()));
      plannedByParticipant.put(
          key, Math.multiplyExact(participant.plannedLaborMilliPerDay(), cycleDays));
      long rights = 0L;
      for (AssetShareId shareId : participant.rightsStakes()) {
        OwnershipStake stake = ctx.assetShares.get(shareId);
        if (stake != null) {
          rights = Math.addExact(rights, stake.quantity());
        }
      }
      if (rights > 0L) {
        rightsWeights.put(key, rights);
      }
    }
    RoleDistributionResult distribution =
        RoleDistribution.allocate(
            new RoleDistributionRequest(
                industry, distributionParticipants, plannedByParticipant, rightsWeights, netPools));

    // 组织者工资应付：Σ quote × target × cycleDays / 1000（只读事实；支付在 P4）。
    // ★★★ P4c3/P3e：quote 唯一来自 U20 锚；target 优先 roster 实际计划雇工（per day），
    //   只有确实没有计划雇工时才回退 standardLaborMilliPerCycle / cycleDays 工程默认。
    Map<ProductionRoleId, Long> actualHiredLaborByRole = hiredWageLaborByRole(roster, industry);
    long organizerHiredTotal = organizerHiredLaborMilliPerDay(actualHiredLaborByRole);
    Map<ProductionRoleId, ProductionWageQuote> quotes = new LinkedHashMap<>();
    long organizerWageCost = 0L;
    for (ProductionRoleDefinition role : industry.roles()) {
      if (!(role.payment() instanceof RolePayment.MoneyWage)) {
        continue;
      }
      WageQuoteBridge.WageQuoteResolution resolution =
          WageQuoteBridge.resolveWageQuote(
              ctx.dayView,
              ctx.day,
              "m3-m6-position-economics",
              unit,
              role.id(),
              ctx.dayView.productionWageAnchors(),
              false);
      if (resolution.quote().isEmpty()) {
        // 锚缺 ⇒ 该工资位置不可行（不按 0 报价伪造收益）。
        continue;
      }
      quotes.put(role.id(), resolution.quote().get());
      long target =
          WageQuoteBridge.wageTargetLaborMilliPerDay(
              actualHiredLaborByRole.getOrDefault(role.id(), 0L), role, cycleDays);
      if (target <= 0L) {
        continue;
      }
      organizerWageCost =
          Math.addExact(
              organizerWageCost,
              Math.multiplyExact(
                      resolution.quote().get().wagePerLaborMilli(),
                      Math.multiplyExact(target, cycleDays))
                  / MILLI_PER_UNIT);
    }
    long inputBurden = askValueMilli(market, probe.inputRequired());
    Map<HouseholdId, Long> outputValueByHousehold = new LinkedHashMap<>();
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      Map<CommodityId, Long> share = distribution.shares().getOrDefault(key, Map.of());
      outputValueByHousehold.put(
          participant.household(),
          Math.addExact(
              outputValueByHousehold.getOrDefault(participant.household(), 0L),
              bidValueMilli(market, share)));
    }
    List<PositionFacts> facts = new ArrayList<>();
    for (ProductionParticipant participant : roster.participants()) {
      ProductionRoleDefinition role = roleOf(industry, participant.role()).orElse(null);
      if (role == null) {
        continue;
      }
      if (role.payment() instanceof RolePayment.MoneyWage
          && !quotes.containsKey(participant.role())) {
        // ★ P4c3：锚缺 ⇒ 位置不可行（具名 wage-anchor-missing 已由 resolveWageQuote 记日志）。
        logPositionFailed(ctx, unit.id(), participant.household(), WageQuoteBridge.WAGE_ANCHOR_MISSING);
        continue;
      }
      long productValue = outputValueByHousehold.getOrDefault(participant.household(), 0L);
      long receivable = 0L;
      ProductionWageQuote quote = quotes.get(participant.role());
      if (quote != null) {
        long labor =
            Math.multiplyExact(participant.plannedLaborMilliPerDay(), cycleDays);
        receivable = Math.multiplyExact(quote.wagePerLaborMilli(), labor) / MILLI_PER_UNIT;
      }
      long payable =
          participant.organizer() ? organizerWageCost : 0L;
      long burden = participant.organizer() ? inputBurden : 0L;
      long expectedIncome = Math.addExact(productValue, receivable);
      long labor = Math.max(1L, participant.plannedLaborMilliPerDay());
      PositionEconomics economics =
          new PositionEconomics(expectedIncome, burden, payable, labor);
      if (economics.positionNetMilli() <= 0L) {
        continue;
      }
      // ★★★ P3d：土地需求从 production probe 的 assetRequired().get(LAND) 派生（= baseScale ×
      //   capacityPerUnit[LAND]），不得再固定写 0。这里逐位置写同一个 unit 级土地需求；M3 生成 LAND BUY
      //   意向时按 unit 去重后聚合成 hex 缺口（见 MarketLandIntentBook），不会把同一块地重复计成多份需求。
      long requiredLandMilliMu = probe.assetRequired().getOrDefault(AssetKind.LAND, 0L);
      PositionPlan position =
          PositionPlan.fromEconomics(
              unit.id(),
              industry.id(),
              participant.role(),
              unit.location(),
              probe.outputScale(),
              participant.plannedLaborMilliPerDay(),
              participant.household().equals(roster.organizerHousehold())
                  ? organizerHiredTotal
                  : 0L,
              requiredLandMilliMu,
              economics);
      facts.add(
          new PositionFacts(
              participant.household(),
              position,
              probe,
              roster.organizerHousehold(),
              probe.expectedOutput(),
              probe.inputRequired()));
    }
    return facts;
  }

  // ── EmploymentLock（第一遍雇佣决策的只读事实）──────────────────────────────────────────

  /**
   * ★★★ <b>D-032 P4c1：从 roster 的 MONEY_WAGE 参与项生成不可变雇佣锁</b>。
   *
   * <p>锁里的 {@code wagePerLaborMilli}/{@code currency} 一律来自本 tick 的 U20 锚（{@code (unit 所在市场区,
   * unit.industry, role)}）；锚缺 ⇒ 具名 {@code wage-anchor-missing}，<b>该位置不可行</b>（不伪造锁、不按 0、不读旧表）。
   *
   * <p>★ 锁一旦生成就是本 tick 的不可变事实：M6/M8 只读它（M8 后调价不得回写本 tick 的锁）。
   */
  static List<EmploymentLock> employmentLocksOf(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry) {
    List<EmploymentLock> locks = new ArrayList<>();
    HouseholdId employer = roster.organizerHousehold();
    for (ProductionParticipant participant : roster.participants()) {
      if (participant.household().equals(employer)) {
        continue;
      }
      ProductionRoleDefinition role = roleOf(industry, participant.role()).orElse(null);
      if (role == null || !(role.payment() instanceof RolePayment.MoneyWage)) {
        continue;
      }
      if (participant.plannedLaborMilliPerDay() <= 0L) {
        continue;
      }
      WageQuoteBridge.WageQuoteResolution resolution =
          WageQuoteBridge.resolveWageQuote(
              ctx.dayView,
              ctx.day,
              "m3-employment-lock",
              unit,
              role.id(),
              ctx.dayView.productionWageAnchors(),
              false);
      if (resolution.quote().isEmpty()) {
        logPositionFailed(ctx, unit.id(), participant.household(), WageQuoteBridge.WAGE_ANCHOR_MISSING);
        continue;
      }
      ProductionWageQuote quote = resolution.quote().get();
      locks.add(
          new EmploymentLock(
              unit.id(),
              employer,
              participant.household(),
              participant.role(),
              participant.plannedLaborMilliPerDay(),
              quote.wagePerLaborMilli(),
              quote.currency()));
    }
    return locks;
  }

  static void logExpectedPlan(
      SettlementDayContext ctx,
      List<PositionFacts> chosen,
      List<EmploymentLock> locks,
      List<MarketIntent> intents,
      List<WageDemand> wageDemands) {
    if (!PRODUCTION.isInfoEnabled()) {
      return;
    }
    long households = chosen.stream().map(PositionFacts::household).distinct().count();
    EventLog.channel(PRODUCTION)
        .info(
            LogEvent.of(
                "D032_P3_EXPECTED_PLAN",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                ctx.day,
                "positions",
                chosen.size(),
                "households",
                households,
                "locks",
                locks.size(),
                "intents",
                intents.size(),
                "wageDemands",
                wageDemands.size(),
                "reason",
                "m3-read-only-expected-plan"));
  }

  static void logLocked(SettlementDayContext ctx, EmploymentLock lock) {
    if (!PRODUCTION.isInfoEnabled()) {
      return;
    }
    EventLog.channel(PRODUCTION)
        .info(
            LogEvent.of(
                "D032_P3_EMPLOYMENT_LOCKED",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                ctx.day,
                "unit",
                lock.unit().value(),
                "employer",
                lock.employer().value(),
                "worker",
                lock.worker().value(),
                "role",
                lock.role().value(),
                "laborMilliPerDay",
                lock.laborMilliPerDay(),
                "wagePerLaborMilli",
                lock.wagePerLaborMilli(),
                "currency",
                lock.currency().value()));
  }

  static void logPositionFailed(
      SettlementDayContext ctx, ProductionUnitId unit, HouseholdId household, String reason) {
    if (!PRODUCTION.isInfoEnabled()) {
      return;
    }
    EventLog.channel(PRODUCTION)
        .info(
            LogEvent.of(
                "D032_P3_POSITION_FAILED",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                ctx.day,
                "unit",
                unit == null ? "-" : unit.value(),
                "household",
                household == null ? "-" : household.value(),
                "reason",
                reason));
  }
}
