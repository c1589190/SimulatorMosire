package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
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

  /**
   * ★ RF3：{@code LAND_OWNER_LABOR_FALLBACK} 的“同一 unit/户每 tick 一条”日志闸门。
   *
   * <p>键是本次结算的瞬态 {@link SettlementDayContext}（弱引用），值是该 tick 已记录的 unit/户；结算上下文回收后闸门随之消失。
   * 它<b>只影响日志</b>，不进任何状态、不改算式、不落 ChangeSet/Codec。
   */
  private static final Map<SettlementDayContext, Set<String>> LAND_OWNER_LABOR_FALLBACK_LOGGED =
      Collections.synchronizedMap(new WeakHashMap<>());

  static final String SELF_LABOR_CROSS_HEX = "cross-hex-self-labor";
  static final String WORKER_NOT_ON_LAND_HEX = "worker-not-on-land-hex";
  static final String LAND_ACQUIRED_THIS_ROUND = "land-acquired-this-round-excluded";
  static final String LAND_LOST = "position-land-lost";
  static final String UNIT_MISSING = "position-unit-missing";
  static final String PROLETARIAN_CANNOT_OWN_POSITION = "proletarian-cannot-own-position";

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
   * ★★★ <b>RC8b：本次试算/排队的有效计划劳动（毫小时/日）</b>。
   *
   * <p>规则（唯一拼写点，M3 试算、M6 位置经济与劳动候选都从这里取值）：
   *
   * <ol>
   *   <li><b>当前权威 = {@link HouseholdEconomy#laborMilli()}</b>（Social 按成员批次/年龄档/性别算好、app 每 tick 注入的当
   *       tick 事实）；<b>不再</b>回读 {@link ProductionParticipant#plannedLaborMilliPerDay()} 的创世快照；
   *   <li>organizer + 土地型方案仍要求该 unit 当刻有 LAND 占用（无地则本户不占自耕位置）；
   *   <li>户行缺失或当前劳动 ≤ 0 ⇒ 0。
   * </ol>
   *
   * <p>★ 这是<b>只读瞬态</b>：不修改 roster、不写回历史计划快照；实际授予量仍由 {@code LaborQueueBook} 按当刻预算封顶。
   * 同输入同结果，不读随机/时间/Map 迭代序。
   */
  static long effectivePlannedLaborMilliPerDay(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionParticipant participant,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      SettlementIndex index) {
    HouseholdEconomy household = householdEconomies.get(participant.household());
    if (household == null || household.laborMilli() <= 0L) {
      return 0L;
    }
    if (participant.organizer() && isLandBased(industry)) {
      long landAllocation = index.assetAllocationOf(unit.id()).getOrDefault(AssetKind.LAND, 0L);
      if (landAllocation <= 0L) {
        return 0L;
      }
    }
    return household.laborMilli();
  }

  /** {@code roleId} 在当前方案里是否 {@link RolePayment.MoneyWage}（RC8b 招工目标门控的唯一拼写点）。 */
  private static boolean isMoneyWageRole(IndustryDefinitionView industry, ProductionRoleId roleId) {
    ProductionRoleDefinition role = roleOf(industry, roleId).orElse(null);
    return role != null && role.payment() instanceof RolePayment.MoneyWage;
  }

  /**
   * ★★★ <b>RC8b：当前 farm 的实际劳动需求（毫小时/日）</b>。
   *
   * <p>唯一来源 = {@link LaborQueueBook#offer} 的 {@code maxAbsorbableLaborMilli}（它内部用 production 的
   * {@code probe} 把产能/投入通道换算成"这个 unit 本 tick 最大可吸收劳动"）。本方法不给 0 伪造需求、不读标准角色固定值。 不是"某户实际总劳动"：worker
   * 有多少人由调用方再按预算封顶。
   */
  static long farmLaborDemandMilliPerDay(
      EconomySession session,
      SettlementIndex index,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      HouseholdId organizer,
      OperatorCondition condition,
      MarketTable market,
      Map<HouseholdId, Map<CommodityId, Long>> householdOnHand) {
    if (industry.roles().isEmpty()) {
      return 0L;
    }
    ProductionRoleId probeRole = industry.roles().get(0).id();
    LaborQueueBook.Offer offer =
        LaborQueueBook.offer(
            unit,
            industry,
            market,
            condition,
            index,
            Optional.empty(),
            unit.modeKey(),
            organizer,
            probeRole,
            session,
            householdOnHand);
    return Math.max(0L, offer.maxAbsorbableLaborMilli());
  }

  /**
   * ★★★ <b>RC8b：本 unit 的 MONEY_WAGE 逐角色招工需求</b>。
   *
   * <pre>
   * farmDemand = 本 unit 实际可吸收劳动（产能/投入通道）
   * organizerSelf = min(organizer 当前 HouseholdEconomy.laborMilli, farmDemand)
   * hiredDemand = max(0, farmDemand − organizerSelf)
   * </pre>
   *
   * <p>★ 这是"farm 需要多少雇工"的唯一来源；<b>不是</b> target 户的实际总劳动，也<b>不是</b>标准角色固定值。多工资角色时按 roster 里各角色当刻
   * worker 劳动比例分摊（缺 worker 劳动才均分）；不重复累加。
   */
  static Map<ProductionRoleId, Long> hiredLaborDemandByRole(
      EconomySession session,
      SettlementIndex index,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionRoster roster,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdOnHand,
      Map<ProductionUnitId, OperatorCondition> conditions,
      MarketTable market) {
    if (roster == null) {
      return Map.of();
    }
    long farmDemand =
        farmLaborDemandMilliPerDay(
            session,
            index,
            unit,
            industry,
            roster.organizerHousehold(),
            conditions == null ? null : conditions.get(unit.id()),
            market,
            householdOnHand);
    if (farmDemand <= 0L) {
      return Map.of();
    }
    long organizerCurrent =
        effectivePlannedLaborMilliPerDay(
            unit, industry, roster.organizer(), householdEconomies, index);
    long organizerSelf = Math.min(organizerCurrent, farmDemand);
    long hiredDemand = farmDemand - organizerSelf;
    if (hiredDemand <= 0L) {
      return Map.of();
    }
    List<ProductionRoleDefinition> wageRoles = new ArrayList<>();
    for (ProductionRoleDefinition role : industry.roles()) {
      if (role.payment() instanceof RolePayment.MoneyWage) {
        wageRoles.add(role);
      }
    }
    if (wageRoles.isEmpty()) {
      return Map.of();
    }
    if (wageRoles.size() == 1) {
      return Map.of(wageRoles.get(0).id(), hiredDemand);
    }
    Map<ProductionRoleId, Long> workerCurrentByRole = new LinkedHashMap<>();
    for (ProductionParticipant participant : roster.participants()) {
      if (participant.organizer() || !isMoneyWageRole(industry, participant.role())) {
        continue;
      }
      long current =
          effectivePlannedLaborMilliPerDay(unit, industry, participant, householdEconomies, index);
      if (current > 0L) {
        workerCurrentByRole.merge(participant.role(), current, Math::addExact);
      }
    }
    long totalWorkerCurrent = 0L;
    for (long current : workerCurrentByRole.values()) {
      totalWorkerCurrent = Math.addExact(totalWorkerCurrent, current);
    }
    Map<ProductionRoleId, Long> targets = new LinkedHashMap<>();
    if (totalWorkerCurrent > 0L) {
      long allocated = 0L;
      for (int indexRole = 0; indexRole < wageRoles.size(); indexRole++) {
        ProductionRoleDefinition role = wageRoles.get(indexRole);
        long current = workerCurrentByRole.getOrDefault(role.id(), 0L);
        long share =
            indexRole == wageRoles.size() - 1
                ? hiredDemand - allocated
                : Math.multiplyExact(hiredDemand, current) / totalWorkerCurrent;
        if (share > 0L) {
          targets.put(role.id(), share);
          allocated = Math.addExact(allocated, share);
        }
      }
    } else {
      long base = hiredDemand / wageRoles.size();
      long remainder = hiredDemand % wageRoles.size();
      for (int indexRole = 0; indexRole < wageRoles.size(); indexRole++) {
        long share = base + (indexRole < remainder ? 1L : 0L);
        if (share > 0L) {
          targets.put(wageRoles.get(indexRole).id(), share);
        }
      }
    }
    return targets;
  }

  /** RC8b ctx 包装：farm 劳动需求/招工目标的唯一读取入口（M3/M6 共用）。 */
  static Map<ProductionRoleId, Long> hiredLaborDemandByRole(
      SettlementDayContext ctx,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionRoster roster) {
    return hiredLaborDemandByRole(
        ctx.session,
        ctx.settlementIndex,
        unit,
        industry,
        roster,
        ctx.householdEconomies,
        ctx.householdGoods,
        ctx.operatorConditions,
        roster == null ? null : marketOf(ctx, roster.organizerHousehold()));
  }

  /**
   * 当前 roster 参与项的 effective 劳动表：organizer 用当前户劳动；非 organizer 的 MONEY_WAGE 参与项再按本 unit 的招工需求封顶 （避免把
   * target 户 180 人的全部劳动塞进只种 681 亩的 farm）。同户同 role 多行的分配按当前劳动比例。
   */
  private static Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
      effectiveLaborByParticipant(
          ProductionProcess unit,
          IndustryDefinitionView industry,
          ProductionRoster roster,
          Map<HouseholdId, HouseholdEconomy> householdEconomies,
          SettlementIndex index,
          Map<ProductionRoleId, Long> hiredLaborDemandByRole) {
    Map<ProductionRoleId, Long> currentByRole = new LinkedHashMap<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> currentByParticipant =
        new LinkedHashMap<>();
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      long current =
          effectivePlannedLaborMilliPerDay(unit, industry, participant, householdEconomies, index);
      currentByParticipant.put(key, current);
      if (!participant.organizer() && isMoneyWageRole(industry, participant.role())) {
        currentByRole.merge(participant.role(), current, Math::addExact);
      }
    }
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> effective =
        new LinkedHashMap<>();
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      long current = currentByParticipant.getOrDefault(key, 0L);
      if (!participant.organizer() && isMoneyWageRole(industry, participant.role())) {
        long demand = hiredLaborDemandByRole.getOrDefault(participant.role(), 0L);
        long roleCurrent = currentByRole.getOrDefault(participant.role(), 0L);
        if (demand <= 0L || roleCurrent <= 0L) {
          current = 0L;
        } else {
          current = Math.min(current, Math.multiplyExact(demand, current) / roleCurrent);
        }
      }
      effective.put(key, current);
    }
    return effective;
  }

  /**
   * ★ <b>RC8b：当前有效参与户判据</b> —— roster 参与项，或同 hex 的 MONEY_WAGE 本地响应者（M6 劳动队列按当前劳动可把这类户写进 PRODUCTION
   * 承诺）。用于 M7 对账/角色重查，不把"当前有效参与户"误判为 {@code role-unknown}。
   */
  static boolean isCurrentEffectiveParticipant(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionRoster roster,
      HouseholdId household,
      ProductionRoleId role,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    if (roster.participant(household, role).isPresent()) {
      return true;
    }
    if (household.equals(roster.organizerHousehold()) || !isMoneyWageRole(industry, role)) {
      return false;
    }
    HouseholdEconomy row = householdEconomies.get(household);
    HexCoord unitHex = hexOf(unit.location());
    return row != null && unitHex != null && unitHex.equals(row.view().hex());
  }

  /**
   * ★ 位置可行性判据（S3/M6 同一入口）：
   *
   * <ul>
   *   <li>位置访问只由角色支付形态 + 家户 ownershipClass 推导：{@code PRODUCT_SHARE} 只允许 {@code OWNER}， {@code
   *       MONEY_WAGE} 允许 {@code OWNER} 与 {@code PROLETARIAN}；{@code PROLETARIAN} 占组织者或产品份额 位置 ⇒
   *       {@link #PROLETARIAN_CANNOT_OWN_POSITION}；有产者占工资位置合法（不再有 LANDED cannot wage 旧门）；
   *   <li>operator 自耕（PRODUCT_SHARE 角色）：本户居住 hex 必须 == 土地 hex，否则 {@link #SELF_LABOR_CROSS_HEX}；
   *   <li>hired_laborer（MONEY_WAGE 角色）：雇工居住 hex 必须 == 土地 hex，否则 {@link #WORKER_NOT_ON_LAND_HEX}；
   *   <li>位置所在 hex 的 LAND 份额若全部命中本轮新取得 marker ⇒ {@link #LAND_ACQUIRED_THIS_ROUND}。
   * </ul>
   *
   * <p>ownershipClass 判据对非土地型产业也生效；跨 hex / 土地 marker 判据仍只对 {@code capacityPerUnit} 含 LAND
   * 的方案执行，避免误伤工坊类生产。
   */
  static Optional<String> positionLocationRejection(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry) {
    boolean landBased = isLandBased(industry);
    HexCoord unitHex = null;
    if (landBased) {
      unitHex = hexOf(unit.location());
      if (unitHex == null) {
        return Optional.of("asset-location-missing");
      }
    }
    for (ProductionParticipant participant : roster.participants()) {
      ProductionRoleDefinition role = roleOf(industry, participant.role()).orElse(null);
      if (role == null) {
        return Optional.of("role-unknown:" + participant.role().value());
      }
      OwnershipClass ownershipClass = OwnershipClassBook.classOf(ctx, participant.household());
      if (ownershipClass == OwnershipClass.PROLETARIAN
          && (participant.organizer() || role.payment() instanceof RolePayment.ProductShare)) {
        return Optional.of(PROLETARIAN_CANNOT_OWN_POSITION);
      }
      if (!landBased) {
        continue;
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
    if (!landBased) {
      return Optional.empty();
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
      if (share.asset() != AssetKind.LAND || !location.value().equals(share.location().value())) {
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
   * ★ 有效名册：优先持久 {@link ProductionRoster}；没有时对“单角色方案”合成一份瞬态名册（organizer = unit operator 家户）。
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

  /**
   * ★★★ <b>RC8b：按当刻 persisted/effective roster 解析的招工需求</b>（M3 工资成本 / WageDemand target / organizer
   * 位置的 {@code hiredLaborMilliPerDay} 共同事实源）。★ 不再读 roster 的 {@code plannedLaborMilliPerDay} 历史快照。
   */
  static Map<ProductionRoleId, Long> hiredWageLaborByRole(
      SettlementDayContext ctx, ProductionProcess unit, IndustryDefinitionView industry) {
    Map<ProductionUnitId, ProductionRoster> rosters = ctx.session.sheet().productionRostersOrBase();
    ProductionRoster roster = effectiveRoster(ctx, unit, industry, rosters);
    return roster == null ? Map.of() : hiredLaborDemandByRole(ctx, unit, industry, roster);
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
   * 用 roster 形态调用 production 的只读试算：逐参与户条件、逐户效率、零劳动 organizer + U20 招工目标都在这一条入口表达。 物理产出/投入/规模一律取自
   * production，不在 economy 复制效率公式。
   */
  static ProductionRosterProbeResult probeRosterOf(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionRoster roster,
      IndustryDefinitionView industry) {
    return probeRosterOf(ctx, unit, roster, industry, List.of());
  }

  /**
   * ★★★ P4c1/RC8b 重载：{@code wageTargets} 优先取本 tick 的 {@link WageDemand}（M6 传 M3 的清单），无则从本 unit 当前
   * 实际劳动需求派生（{@link WageQuoteBridge#wageTargetsOf}）；不读旧工资表、不读标准角色固定值。
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
    // ★★★ RC8b：招工目标先由"本 unit 实际劳动需求 − organizer 当前自耕劳动"派生（M6 用 M3 的 WageDemand 快照），
    //   再把同一目标用于逐参与项 effective 劳动的封顶 ⇒ 试算/位置经济/工资成本只读同一份当前计划。
    Map<ProductionRoleId, Long> wageTargets =
        WageQuoteBridge.wageTargetsOf(ctx, unit, industry, roster, wageDemands);
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> planLaborByParticipant =
        effectiveLaborByParticipant(
            unit, industry, roster, ctx.householdEconomies, ctx.settlementIndex, wageTargets);
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey participantKey =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      long effectiveLabor = planLaborByParticipant.getOrDefault(participantKey, 0L);
      logLandOwnerLaborFallback(ctx, unit, participant, effectiveLabor);
      participants.add(
          new ProductionRosterProbeParticipant(
              participant.household(),
              participant.role(),
              Math.multiplyExact(effectiveLabor, cycleDays)));
      // ★★ RF2：本周期已经扣到 unit 上的投入（production 的 cycleInputUsedMilli）才是"当刻可用投入"；
      //   只看家户在手库存会把 day2 起"粮已变成种子"的 unit 误判成 input-short ⇒ 位置消失、劳动断供。
      //   ★ 有 cycle carry ⇒ 只把它当作该 unit 的输入（不再叠加在手库存，避免同一批种子被算两次）；
      //     无 cycle carry（周期第一天）⇒ 仍用 M5 后的在手库存（与 M6 劳动队列同一口径）。
      Map<CommodityId, Long> probeInputs;
      if (unit.cycleInputUsedMilli().isEmpty()) {
        probeInputs =
            ProductionProbeInputs.inputsOf(
                ctx.accounts.householdGoods().get(participant.household()), industry);
      } else if (participant.household().equals(roster.organizerHousehold())) {
        probeInputs = cycleInputsOf(unit, industry);
      } else {
        probeInputs = Map.of();
      }
      conditions.putIfAbsent(
          participant.household(),
          new HouseholdProductionConditions(
              participant.household(),
              0L,
              Map.of(),
              probeInputs,
              ProductionProbeInputs.masteryOf(
                  ctx.session.productionRuntime(), participant.household(), industry),
              ProductionProbeInputs.experienceOf(
                  ctx.session.productionRuntime(), participant.household(), industry)));
      attributes.putIfAbsent(participant.household(), ExternalAttributeFacts.EMPTY);
    }
    Map<AssetKind, Long> occupancy =
        ProductionProbeInputs.positiveAssets(ctx.settlementIndex.assetAllocationOf(unit.id()));
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

  /**
   * ★★ <b>RF2：unit 本周期已扣到的投入（按方案配方键过滤）</b>。
   *
   * <p>只读 {@link ProductionProcess#cycleInputUsedMilli()}；不复制、不换算投入公式，只把"已经扣到的量"作为 {@code
   * probeRoster} 的输入事实交回去。缺键 = 0；≤ 0 不进表。
   */
  private static Map<CommodityId, Long> cycleInputsOf(
      ProductionProcess unit, IndustryDefinitionView industry) {
    Map<CommodityId, Long> inputs = new LinkedHashMap<>();
    for (CommodityId commodity : industry.inputPerUnit().keySet()) {
      long carried = unit.cycleInputUsedMilli().getOrDefault(commodity, 0L);
      if (carried > 0L) {
        inputs.put(commodity, carried);
      }
    }
    return inputs;
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
   * <p>收入 = 本户产品份额值（本地 bid）+ 工资应收；成本 = 工资应付（organizer）+ 投入负担（组织者/投入供方）。排序键 = {@link
   * PositionEconomics#positionYieldPerLaborScaled()}，M3/M6 共用同一算式。
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
   * <p>工资报价一律经 {@link WageQuoteBridge#resolveWageQuote}（锚唯一权威；缺锚 ⇒ 具名 {@code wage-anchor-missing}
   * 的位置不可行）。
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
    // ★★★ RC8b：工资应付/工资应收/位置经济全部读同一份"当前有效计划劳动"。
    //   hired target = farm 实际劳动需求 − organizer 当前自耕劳动；worker 的 effective 劳动按该 target 封顶。
    Map<ProductionRoleId, Long> actualHiredLaborByRole =
        hiredLaborDemandByRole(ctx, unit, industry, roster);
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> planLaborByParticipant =
        effectiveLaborByParticipant(
            unit,
            industry,
            roster,
            ctx.householdEconomies,
            ctx.settlementIndex,
            actualHiredLaborByRole);
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      distributionParticipants.add(
          new RoleDistributionParticipant(
              participant.household(), participant.role(), participant.configuredWeight()));
      long effectiveLabor = planLaborByParticipant.getOrDefault(key, 0L);
      plannedByParticipant.put(key, Math.multiplyExact(effectiveLabor, cycleDays));
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
    // ★★★ RC8b：quote 唯一来自 U20 锚；target = 本 unit 的当前有效招工需求（farm 实际劳动需求 − organizer 当前自耕劳动），
    //   不再回退 standardLaborMilliPerCycle / cycleDays 的标准角色固定值。
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
      long target = actualHiredLaborByRole.getOrDefault(role.id(), 0L);
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
        logPositionFailed(
            ctx, unit.id(), participant.household(), WageQuoteBridge.WAGE_ANCHOR_MISSING);
        continue;
      }
      io.mosire.simos.production.contract.ProductionParticipantKey participantKey =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      long effectiveLabor = planLaborByParticipant.getOrDefault(participantKey, 0L);
      long productValue = outputValueByHousehold.getOrDefault(participant.household(), 0L);
      long receivable = 0L;
      ProductionWageQuote quote = quotes.get(participant.role());
      if (quote != null) {
        long labor = Math.multiplyExact(effectiveLabor, cycleDays);
        receivable = Math.multiplyExact(quote.wagePerLaborMilli(), labor) / MILLI_PER_UNIT;
      }
      long payable = participant.organizer() ? organizerWageCost : 0L;
      long burden = participant.organizer() ? inputBurden : 0L;
      long expectedIncome = Math.addExact(productValue, receivable);
      long labor = Math.max(1L, effectiveLabor);
      PositionEconomics economics = new PositionEconomics(expectedIncome, burden, payable, labor);
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
              effectiveLabor,
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
    // ★★★ RC8b：锁的劳动 = 当前 worker 的 effective 劳动（按本 unit 招工需求封顶）；target 不是 roster 历史快照。
    Map<ProductionRoleId, Long> hiredDemandByRole =
        hiredLaborDemandByRole(ctx, unit, industry, roster);
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> planLaborByParticipant =
        effectiveLaborByParticipant(
            unit, industry, roster, ctx.householdEconomies, ctx.settlementIndex, hiredDemandByRole);
    for (ProductionParticipant participant : roster.participants()) {
      if (participant.household().equals(employer)) {
        continue;
      }
      ProductionRoleDefinition role = roleOf(industry, participant.role()).orElse(null);
      if (role == null || !(role.payment() instanceof RolePayment.MoneyWage)) {
        continue;
      }
      io.mosire.simos.production.contract.ProductionParticipantKey participantKey =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      long lockLaborMilliPerDay = planLaborByParticipant.getOrDefault(participantKey, 0L);
      if (lockLaborMilliPerDay <= 0L) {
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
        logPositionFailed(
            ctx, unit.id(), participant.household(), WageQuoteBridge.WAGE_ANCHOR_MISSING);
        continue;
      }
      ProductionWageQuote quote = resolution.quote().get();
      locks.add(
          new EmploymentLock(
              unit.id(),
              employer,
              participant.household(),
              participant.role(),
              lockLaborMilliPerDay,
              quote.wagePerLaborMilli(),
              quote.currency()));
    }
    return locks;
  }

  /**
   * ★ RF3：记一条 land-owner zero-planned fallback；同一 unit/户每 tick 只记一次。
   *
   * <p>只在有效值为回退值（roster 原值 = 0 且 effective > 0）时输出；字段固定为 {@code
   * unit/household/landAllocation/plannedRosterLabor/effectiveLabor/reason}。
   */
  private static void logLandOwnerLaborFallback(
      SettlementDayContext ctx,
      ProductionProcess unit,
      ProductionParticipant participant,
      long effectiveLabor) {
    long plannedRosterLabor = participant.plannedLaborMilliPerDay();
    if (plannedRosterLabor > 0L || effectiveLabor <= 0L || !PRODUCTION.isDebugEnabled()) {
      return;
    }
    Set<String> logged =
        LAND_OWNER_LABOR_FALLBACK_LOGGED.computeIfAbsent(
            ctx, ignored -> ConcurrentHashMap.newKeySet());
    if (!logged.add(unit.id().value() + "|" + participant.household().value())) {
      return;
    }
    long landAllocation =
        ctx.settlementIndex.assetAllocationOf(unit.id()).getOrDefault(AssetKind.LAND, 0L);
    EventLog.channel(PRODUCTION)
        .debug(
            LogEvent.of(
                "LAND_OWNER_LABOR_FALLBACK",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                ctx.day,
                "unit",
                unit.id().value(),
                "household",
                participant.household().value(),
                "landAllocation",
                landAllocation,
                "plannedRosterLabor",
                plannedRosterLabor,
                "effectiveLabor",
                effectiveLabor,
                "reason",
                "land-owner-zero-planned-labor"));
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
