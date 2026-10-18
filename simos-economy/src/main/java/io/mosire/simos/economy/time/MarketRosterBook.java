package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_LIFE_RESERVE_DAYS;
import static io.mosire.simos.economy.time.MarketIndexBook.hexKeyOf;
import static io.mosire.simos.economy.time.MarketMatchSupport.isUnresolvedAggregateOperator;
import static io.mosire.simos.economy.time.MarketMatchSupport.unresolvedMarketSubject;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketOrderPlanner.HexPlan;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketSettlement.HouseholdMarketRole;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketRosterBook {

  private MarketRosterBook() {}

  /**
   * ★★★ <b>W2（2026-10-11）：一个家户的“生产花费（内含口粮）”—— S-10 的唯一量</b>。
   *
   * <pre>
   * 生产花费(商品) = 本户名下各 unit 的必要投入（recipe.inputPerUnit × 计划规模）
   *                + 本户的口粮/自然需要前瞻（naturalNeeds × {@link #MARKET_LIFE_RESERVE_DAYS}）
   * </pre>
   *
   * <p>★★ <b>用户原话（第五批）</b>：「生产方式也要一并修改，我之前说单个生产方式包括所需要消耗的口粮，因此<b>不需要额外计算"一日口粮"</b>， 只需要<b>增强生产花费 +
   * 只预估生产花费并购买</b>就行」。⇒ 家户的买盘目标 = <b>这一个量</b>（不再"口粮一条账、投入另一条账"）。
   *
   * <p>★★ <b>"无业"也是一种生产方式</b>（0–8 第 2 条）：名下没有 unit 的家户，上式第一项为空 ⇒ 生产花费 = 基本生理需求 （{@code
   * naturalNeeds} 前瞻）—— 同一个算式，没有第二个分支。
   *
   * <p>★★ <b>模块边界（不变）</b>：数量口径仍来自 Social 注入的 {@code naturalNeeds}（人口需求系数的唯一权威是 {@code
   * SocialProvisioning}）；经济侧只是把它<b>归入生产花费</b>、不再单列"口粮"这条独立下单路径。
   *
   * <p>★ <b>合用点（唯一口径）</b>：买盘目标（{@code ordersFor} 的 {@code baseTarget}）、不卖保留（{@code sellable} 的
   * {@code retention}）、以及"生产花费未满足量"读口（{@link #unmetProductionCostOf}）都读它 —— 一个量贯穿买卖、缺口。 ★★
   * <b>v3.6（2026-10-11 用户第六批裁定）起它不再服务还债侧</b>：{@code EconomySettlement.repayDebts} 的"逐商品保留"已整块删除 （还债
   * = 有库存就还、没库存记债，不扣任何前瞻量）—— 本方法本身一字未改，只是少了一个调用点。
   */
  static Map<CommodityId, Long> householdProductionCostOf(
      MarketRound round, Participant participant) {
    Map<CommodityId, Long> cost = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : necessaryInputsOf(round, participant).entrySet()) {
      if (entry.getValue() > 0L) {
        cost.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    HouseholdEconomy row =
        participant.household() == null
            ? null
            : round.householdEconomies.get(participant.household());
    if (row != null) {
      for (Map.Entry<CommodityId, Long> entry : householdLifeReserveOf(row).entrySet()) {
        if (entry.getValue() > 0L) {
          cost.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
      }
    }
    return cost;
  }

  // ── 参与者 / 订单预计算（M2.1/M2.2 的既有算式，逐字保留）────────────────────────────

  /**
   * ★ R2：角色名单在日志里的<b>最多列出条数</b>（超出部分只报条数，不静默丢 —— 串尾写明 {@code (+N more)}）。
   *
   * <p>★ 它是<b>日志专用</b>常量：不参与任何判据/写路径（名单本身是身份集合，与它无关）。
   */
  static final int ROSTER_LOG_LIMIT = 16;

  /** 名册的规范串（id 升序；超过 {@value #ROSTER_LOG_LIMIT} 条 ⇒ 截断并给出被截掉的条数）。 */
  static String rosterSummary(List<String> ids) {
    if (ids.isEmpty()) {
      return "[]";
    }
    List<String> sorted = new ArrayList<>(ids);
    Collections.sort(sorted);
    int shown = Math.min(sorted.size(), ROSTER_LOG_LIMIT);
    StringBuilder text = new StringBuilder("[");
    for (int index = 0; index < shown; index++) {
      if (index > 0) {
        text.append(',');
      }
      text.append(sorted.get(index));
    }
    text.append(']');
    if (sorted.size() > shown) {
      text.append("(+").append(sorted.size() - shown).append(" more)");
    }
    return text.toString();
  }

  /**
   * ★★★ <b>R2 具名日志：本轮（逐市场轮、每轮一条）的"谁能买 / 谁只按授权下单"全局名册</b>（INFO）。
   *
   * <p>★ <b>为什么必须有它</b>（用户/控制方口径："避免下次再从日志里看不出"）：R2 之后"军户/官吏户能不能买、
   * 国库户为什么不挂单"必须**在默认日志级别**就能回答；这一条给出逐角色的户数与被截断的名册，逐户的理由在 {@code
   * MARKET_HOUSEHOLD_ROLE}（TRACE）、逐格的名单在 {@code MARKET_HOUSEHOLD_ROSTER_BY_HEX}（DEBUG）、 被挡下的卖单在
   * {@code MARKET_HOUSEHOLD_SELL_SUPPRESSED}（TRACE）、单位户的买盘在 {@code
   * MARKET_HOUSEHOLD_BUY_ORDER}（DEBUG）。
   *
   * <p>★ 名册来源 = 组合根注入的 Z7b 集合 ∪ 政府国库户名单（两者都按角色现算）—— 空表（没有单位户/政府的世界） ⇒ **一条都不发**（缺省中性：旧世界日志面一个字不改）。
   */
  static void logHouseholdRoster(MarketRound round) {
    List<String> buyOnly = new ArrayList<>();
    List<String> authorizationOnly = new ArrayList<>();
    for (HouseholdId household : round.marketExcludedHouseholds()) {
      if (round.householdMarketRole(household) == HouseholdMarketRole.BUY_ONLY) {
        buyOnly.add(household.value());
      }
    }
    // ★ 国库侧的名单直接取"只按授权下单"计划的权威名单（不按"哪一户有经济行"重算，避免漏报）。
    authorizationOnly.addAll(round.govMandates().authorizationOnlyRoster());
    if (buyOnly.isEmpty() && authorizationOnly.isEmpty()) {
      return; // 缺省中性：没有单位户/国库户的世界不发这一条
    }
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_HOUSEHOLD_ROSTER",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "buyOnlyHouseholds",
                buyOnly.size(),
                "buyOnlyRoster",
                rosterSummary(buyOnly),
                "authorizationOnlyHouseholds",
                authorizationOnly.size(),
                "authorizationOnlyRoster",
                rosterSummary(authorizationOnly),
                "reason",
                "r2-unit-households-buy-only-treasury-households-orders-only-by-explicit-authorization"));
  }

  /** 一个格的参与者：家户（按行）在前，经营者（该格 unit 的 operator，逐 actor 去重）在后。 */
  static List<Participant> participantsFor(
      MarketRound round, HexCoord hex, List<HouseholdId> keys) {
    String hexKey = hexKeyOf(hex);
    Map<ActorRef, List<ProductionUnitId>> unitsByOperator = new LinkedHashMap<>();
    // ★ R4-B.3a-perf：本格 unit 由入口索引一次给出（序 = unit 表序，旧实现的全表过滤同此序）。
    for (ProductionUnitId id : round.index.unitsInHex(hexKey)) {
      ProductionProcess unit = round.units.get(id);
      if (unit == null) {
        continue; // 索引与活表同源；这里只防御手工状态在两者之间被改动
      }
      unitsByOperator.computeIfAbsent(unit.operator(), ignored -> new ArrayList<>()).add(id);
    }
    LinkedHashMap<ActorRef, Participant> byActor = new LinkedHashMap<>();
    int fullHouseholds = 0;
    int buyOnlyHouseholds = 0;
    int authorizationOnlyHouseholds = 0;
    List<String> buyOnlyRoster = new ArrayList<>();
    List<String> authorizationOnlyRoster = new ArrayList<>();
    for (HouseholdId key : keys) {
      // ★★★ R2（2026-10-11）：本格这一户的市场角色 —— **唯一判据**（{@link MarketRound#householdMarketRole}）。
      //   ★ 改前这里是 Z7b 的"单位户 ⇒ 直接 continue（不买、不卖、不挂信用）"；R2 把"排除"收窄成"只买不卖"：
      //     军户/官吏户回到参与表与买盘（口粮通道），卖侧仍不生成（公家/单位实物不被清仓）。
      //   ★ 国库户本来就在参与表里（R1 起它要能按授权挂单）—— 本条一个 continue 都没有，角色只决定"生成哪些订单"。
      HouseholdMarketRole role = round.householdMarketRole(key);
      switch (role) {
        case FULL -> fullHouseholds++;
        case BUY_ONLY -> {
          buyOnlyHouseholds++;
          buyOnlyRoster.add(key.value());
        }
        case AUTHORIZATION_ONLY -> {
          authorizationOnlyHouseholds++;
          authorizationOnlyRoster.add(key.value());
        }
      }
      if (role != HouseholdMarketRole.FULL && MARKET.isTraceEnabled()) {
        // ★§一.9 TRACE 逐户"为什么"：这一户是谁、凭什么拿到这个角色（同一份判据的另一半在 ordersFor 的订单生成处）。
        EventLog.channel(MARKET)
            .trace(
                LogEvent.of(
                    "MARKET_HOUSEHOLD_ROLE",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "household",
                    key.value(),
                    "hex",
                    hex,
                    "role",
                    role.name(),
                    "reason",
                    role == HouseholdMarketRole.BUY_ONLY
                        ? "unit-household-buys-but-does-not-auto-sell"
                        : "government-treasury-orders-only-by-explicit-authorization"));
      }
      ActorRef actor = HouseholdActors.of(key);
      List<ProductionUnitId> units = unitsByOperator.remove(actor);
      if (units != null) {
        // ★ 同一 operator 多 unit 时按 unit id canonical 序累加（必要投入/自留/卖单的确定性口径）。
        units.sort(Comparator.comparing(ProductionUnitId::value));
      }
      byActor.put(
          actor,
          new Participant(
              actor,
              key,
              round.householdEconomies.get(key).view(),
              units == null ? List.of() : units));
    }
    if ((buyOnlyHouseholds > 0 || authorizationOnlyHouseholds > 0) && MARKET.isDebugEnabled()) {
      // ★★ R2 具名日志（§一.9 DEBUG = 逐格池子/理由，"谁在这一格、凭什么角色"）：本格的角色名单 ——
      //   与 {@code MARKET_LIFE_RESERVE} 同族（逐 hex、汇总串）；逐户的理由在 {@code MARKET_HOUSEHOLD_ROLE}（TRACE），
      //   全局名单在每个市场轮一条 {@code MARKET_HOUSEHOLD_ROSTER}（INFO，见 {@code clearOncePerCycle}）。
      //   名单按 id 升序（只日志、不改状态），过长时截断并给出被截掉的条数（不静默丢）。
      //   ★ 事件名与那条 INFO **不同名**：两者的形状不同（本条逐 hex、INFO 那条逐轮），同名会让读者把两种形状当成一种。
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_HOUSEHOLD_ROSTER_BY_HEX",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "hex",
                  hex,
                  "fullHouseholds",
                  fullHouseholds,
                  "buyOnlyHouseholds",
                  buyOnlyHouseholds,
                  "buyOnlyRoster",
                  rosterSummary(buyOnlyRoster),
                  "authorizationOnlyHouseholds",
                  authorizationOnlyHouseholds,
                  "authorizationOnlyRoster",
                  rosterSummary(authorizationOnlyRoster),
                  "reason",
                  "r2-buy-side-open-for-unit-households-sell-side-suppressed-treasury-orders-only-by-authorization"));
    }
    for (List<ProductionUnitId> units : unitsByOperator.values()) {
      units.sort(Comparator.comparing(ProductionUnitId::value));
    }
    // ★★ P2-A §13.3：经营者角色（庄园/作坊/商号）不持账 —— 逐 unit 解析到组织者/经营者家户：
    //   ① 单一家户（operator/relation/份额可解析）⇒ 把 unit 挂到该家户的参与者上；
    //   ② 解析不到单一主体但有名下劳动家户（集体经营，如家户纺织主 unit）⇒ **不是市场主体**（2026-10-23
    //      裁定 B）：不把聚合 unit 当市场参与者，静默跳过（只留默认关闭的 TRACE）；其产出已按劳动分给
    //      各成员家户账，各家按自己的库存与预算买卖；
    //   ③ **没有任何正资产、也没有任何劳动配额**的 unit（合法空壳，B.3b）⇒ WARN `MARKET_SUBJECT_EMPTY_UNIT` + 跳过；
    //   ④ ★★ 2026-10-10 G3-fix-1：**合成聚合经营者 + 名下劳动家户已全部不在行表**（成员户消亡/被迁移删行）
    //      ⇒ 具名 WARN `MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` + 跳过。它与 ③ 的区别是"这个生产组织还在、
    //      但已经没有任何家户能收它的账"——这是合法终态（{@code ModeMigrationSettlement.retireSource}
    //      会删掉人口归零的成员户行），不该以"整条 AdvanceTime 不落 revision"为代价；
    //      详见 {@link #isUnresolvedAggregateOperator}。
    //      ★ 区内路径与跨区路径**共用本方法**（跨区撮合只消费这里建好的槽位）⇒ 两条路径同口径。
    //   ⑤ 其余（经营者指着不存在/非家户的主体等真坏数据）⇒ 具名抛，不静默当成"零库存参与者"。
    for (Map.Entry<ActorRef, List<ProductionUnitId>> entry : unitsByOperator.entrySet()) {
      for (ProductionUnitId unitId : entry.getValue()) {
        Optional<HouseholdId> single = round.index.economicHouseholdOf(unitId);
        if (single.isEmpty()) {
          List<HouseholdId> collective = round.index.householdsOf(unitId);
          if (collective.isEmpty()) {
            // ★★ P2-E：**没有任何正产能、也没有任何劳动配额**的 unit 是合法空壳（B.3b：
            //   "有经营者、无资产、不生产"是合法状态，资产可以被全部转走；0 产能的份额也走这里）。
            //   它没有库存可卖、没有产能可买投入，因此不生成任何订单 —— 具名跳过，不静默当成
            //   "零库存参与者"，也不把它当坏数据。有正资产或有劳动却解析不到家户 ⇒ 仍走下面的具名抛。
            Map<AssetKind, Long> assetAllocation = round.index.assetAllocationOf(unitId);
            boolean hasCapacity =
                assetAllocation.values().stream()
                    .anyMatch(quantity -> quantity != null && quantity > 0L);
            if (!hasCapacity) {
              EventLog.channel(MARKET)
                  .warn(
                      LogEvent.of(
                          "MARKET_SUBJECT_EMPTY_UNIT",
                          EconomyLogSource.ECONOMY_MARKET,
                          "day",
                          round.day,
                          "unit",
                          unitId.value(),
                          "operator",
                          entry.getKey(),
                          "reason",
                          "no-positive-asset-and-no-labor-allocation"));
              continue;
            }
            // ★★ 2026-10-10 G3-fix-1：**合成聚合经营者**（`HOUSEHOLD:weave@<hex>` 一族）且它不是现存家户行
            //   ⇒ 这个 unit 从来不是市场主体（裁定 B：聚合 unit 的产出/库存已按劳动落各成员家户账），
            //   只是"成员户全都没了"让 ② 的判据（`householdsOf` 非空）也失效了。
            //   ⇒ 具名 WARN 跳过，**不抛整轮**：AdvanceTime 必须能落 revision（真实 world 复测 day38 的崩点）。
            //   ★ 不放宽真坏数据：判据要求 operator **恰是**该产业的合成默认经营者（见方法注），
            //     显式换上的组织者家户（farm/craft/trade）走不到这里。
            if (isUnresolvedAggregateOperator(unitId, entry.getKey(), round)) {
              EventLog.channel(MARKET)
                  .warn(
                      LogEvent.of(
                          "MARKET_SUBJECT_COLLECTIVE_UNRESOLVED",
                          EconomyLogSource.ECONOMY_MARKET,
                          "day",
                          round.day,
                          "unit",
                          unitId.value(),
                          "operator",
                          entry.getKey(),
                          "hex",
                          round.index.hexOf(unitId) == null ? "none" : round.index.hexOf(unitId),
                          "industry",
                          round.units.get(unitId) == null
                              ? "none"
                              : round.units.get(unitId).industry(),
                          "assetAllocation",
                          assetAllocation,
                          "laborAllocations",
                          round.index.allocationsOfUnit(unitId).size(),
                          "reason",
                          "aggregate-operator-without-any-household-row-is-not-a-market-subject"));
              continue;
            }
            throw unresolvedMarketSubject(round, unitId, entry.getKey(), assetAllocation);
          }
          // ② 集体经营（如家户纺织主 unit）：**by design 不是市场主体**（2026-10-23 裁定 B）—— 该聚合
          //    unit 的产出/库存已按劳动落各成员家户账，成员家户各自入市；这里静默跳过，只留一条默认
          //    关闭（TRACE）的审计事件，不再发 WARN。不变量：参与者列表与该 unit 仍是 WARN 跳过时逐值一致。
          if (MARKET.isTraceEnabled()) {
            EventLog.channel(MARKET)
                .trace(
                    LogEvent.of(
                        "MARKET_SUBJECT_COLLECTIVE_SKIPPED",
                        EconomyLogSource.ECONOMY_MARKET,
                        "day",
                        round.day,
                        "unit",
                        unitId.value(),
                        "operator",
                        entry.getKey(),
                        "households",
                        collective,
                        "reason",
                        "by-design-not-a-market-subject"));
          }
          continue;
        }
        HouseholdId household = single.get();
        // ★★★ R2（2026-10-11）：经营 unit 解析到**单位户/国库户**时不再把 unit 挡在市场之外 —— 它照旧挂到那个家户的
        //   参与者上（这样"本期生产花费所需现金/口粮前瞻"这条买盘目标**含它的必要投入**，与普通家户同一条算式）；
        //   它不会被自动清仓，靠的是参与者角色的那条判据（{@link MarketRound#householdMarketRole}）：
        //     · BUY_ONLY        ⇒ 这一户（含它的 unit 库存）**不生成任何卖单**；
        //     · AUTHORIZATION_ONLY ⇒ 这一户（含它的 unit）**两侧都不生成自动订单**。
        HouseholdMarketRole operatorRole = round.householdMarketRole(household);
        if (operatorRole != HouseholdMarketRole.FULL && MARKET.isTraceEnabled()) {
          EventLog.channel(MARKET)
              .trace(
                  LogEvent.of(
                      "MARKET_HOUSEHOLD_ROLE",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day,
                      "household",
                      household.value(),
                      "unit",
                      unitId.value(),
                      "hex",
                      round.index.hexOf(unitId) == null ? "none" : round.index.hexOf(unitId),
                      "role",
                      operatorRole.name(),
                      "reason",
                      "unit-operator-household-role-decides-which-orders-are-generated"));
        }
        HouseholdEconomy householdEconomy = round.householdEconomies.get(household);
        if (householdEconomy == null) {
          // ★ fail-closed 边界：普通家户解析不到 ClassRow = 真坏数据 ⇒ 具名抛（改前口径一字不动）。
          //   ★ 单位户/国库户没有经济行时**不能**建参与者（Participant 要那一行的人口/需求视图）⇒ 具名 WARN 跳过：
          //     这与改前"这些户整个被排除"对它们**逐值相同**（它照样不生成订单），但不再静默。
          if (operatorRole == HouseholdMarketRole.FULL) {
            throw new IllegalStateException(
                "市场参与者的组织者家户没有 ClassRow（解析结果必须是现存家户）：household="
                    + household
                    + " unit="
                    + unitId.value());
          }
          EventLog.channel(MARKET)
              .warn(
                  LogEvent.of(
                      "MARKET_SUBJECT_UNIT_HOUSEHOLD_WITHOUT_ROW",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day,
                      "unit",
                      unitId.value(),
                      "household",
                      household.value(),
                      "role",
                      operatorRole.name(),
                      "reason",
                      "operator-household-has-no-economy-row-so-the-unit-cannot-become-a-market-participant"));
          continue;
        }
        Participant existing = byActor.get(HouseholdActors.of(household));
        if (existing == null) {
          byActor.put(
              HouseholdActors.of(household),
              new Participant(
                  HouseholdActors.of(household),
                  household,
                  householdEconomy.view(),
                  List.of(unitId)));
        } else {
          List<ProductionUnitId> merged = new ArrayList<>(existing.units());
          merged.add(unitId);
          merged.sort(Comparator.comparing(ProductionUnitId::value));
          byActor.put(
              existing.actor(),
              new Participant(existing.actor(), household, householdEconomy.view(), merged));
        }
      }
    }
    return List.copyOf(byActor.values());
  }

  /** 一个格的预计算（参与者 + 必要生产投入 + 生活保留 + 有效需求分段）。 */
  static HexPlan planFor(MarketRound round, HexCoord hex, List<HouseholdId> keys) {
    List<Participant> participants = participantsFor(round, hex, keys);
    Map<ActorRef, Map<CommodityId, Long>> necessary = new LinkedHashMap<>();
    Map<ActorRef, Map<CommodityId, Long>> life = new LinkedHashMap<>();
    // ★★ R4-E2：需求账本 → 本格逐户目标量（每 hex 扫一次 demand 表；demands 空时是空表、零行为差异）。
    Map<HouseholdId, Map<CommodityId, List<Long>>> demandParts =
        DemandTargets.partsForHex(
            round.householdDemands(), round.householdEconomies, hex, keys, round.day);
    Map<ActorRef, Map<CommodityId, List<Long>>> demandPartsByActor = new LinkedHashMap<>();
    for (Participant participant : participants) {
      necessary.put(participant.actor(), necessaryInputsOf(round, participant));
      if (participant.household() != null) {
        HouseholdEconomy householdEconomy = round.householdEconomies.get(participant.household());
        life.put(
            participant.actor(),
            householdEconomy == null ? Map.of() : householdLifeReserveOf(householdEconomy));
        demandPartsByActor.put(
            participant.actor(), demandParts.getOrDefault(participant.household(), Map.of()));
      } else {
        life.put(participant.actor(), operatorLifeRetentionOf(round, participant, hex));
        demandPartsByActor.put(participant.actor(), Map.of()); // 需求只归家户；经营者不因 GM 需求加目标
      }
    }
    return new HexPlan(participants, necessary, life, demandPartsByActor);
  }

  /**
   * ★★ <b>S3：一个卖方的单位成本估计</b>（{@link ProducerCostBook} 是唯一拼写点）—— 先在本格 unit 里认出"经营这个卖方的 unit"（优先：同一
   * actor 既是 unit.operator 又产出该商品；其次：本格任一出产该商品的 unit，按 id canonical 取小）， 再按该 unit 的产业模板 + 本格参考价算成本。
   *
   * <p>★★ <b>认不出 unit ⇒ {@link ProducerCostBook.Estimate#unknown()}</b>（排序排在已知成本之后）：不拿 0
   * 冒充"成本很低"（那是本仓禁的"假装便宜"）。
   */
  static ProducerCostBook.Estimate costEstimateOf(
      MarketRound round, MarketTable market, Participant seller, CommodityId commodity) {
    ProductionProcess unit = unitForSeller(round, seller, commodity);
    if (unit == null) {
      return ProducerCostBook.Estimate.unknown();
    }
    IndustryDefinitionView industry = round.industries.get(unit.industry());
    if (industry == null) {
      return ProducerCostBook.Estimate.unknown();
    }
    return ProducerCostBook.estimate(
        unit, industry, round.index, market, round.relations.get(unit.id()));
  }

  /**
   * ★★ <b>"经营这个卖方的 unit"的唯一判定</b>：优先本格 unit 里 {@code unit.operator == seller} 且其模板出产该商品者 （按 unit id
   * canonical 取小），否则本格任一出产该商品者（同序取小）；认不出 ⇒ null（成本未知）。
   */
  static ProductionProcess unitForSeller(
      MarketRound round, Participant seller, CommodityId commodity) {
    ProductionProcess match = null;
    for (ProductionUnitId id : seller.units()) {
      ProductionProcess unit = round.units.get(id);
      IndustryDefinitionView candidate =
          unit == null ? null : round.industries.get(unit.industry());
      if (unit == null
          || candidate == null
          || !candidate.outputPerUnit().containsKey(commodity)
          || !seller.actor().equals(unit.operator())) {
        continue;
      }
      if (match == null || id.value().compareTo(match.id().value()) < 0) {
        match = unit;
      }
    }
    if (match != null) {
      return match;
    }
    for (ProductionUnitId id : seller.units()) {
      ProductionProcess unit = round.units.get(id);
      IndustryDefinitionView candidate =
          unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || candidate == null || !candidate.outputPerUnit().containsKey(commodity)) {
        continue;
      }
      if (match == null || id.value().compareTo(match.id().value()) < 0) {
        match = unit;
      }
    }
    return match;
  }

  static Map<CommodityId, Long> necessaryInputsOf(MarketRound round, Participant participant) {
    Map<CommodityId, Long> necessary = new LinkedHashMap<>();
    for (ProductionUnitId id : participant.units()) {
      ProductionProcess unit = round.units.get(id);
      IndustryDefinitionView industry = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || industry == null) {
        continue;
      }
      ProductionRules relation = round.relations.get(id);
      Payee supplier =
          relation == null ? new Payee.ToActor(unit.operator()) : relation.inputSupplier();
      if (!supplies(participant, supplier)) {
        continue;
      }
      long scale =
          ProductionProcessBook.plannedCapacityScaleOf(
              unit, industry, round.index, round.operatorConditions.get(id));
      if (scale <= 0L) {
        continue; // 本格没有产能 / 已缩到 0 ⇒ 不要料（同 drawCycleInputs 的口径）
      }
      for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
        if (entry.getValue() <= 0L) {
          continue;
        }
        necessary.merge(entry.getKey(), entry.getValue() * scale, Long::sum);
      }
    }
    // ★★★ A3（2026-10-10）：**这里原有一段"给跑商家户下夹一趟工具"的特例（§16 追加裁定 6 的最小第一步，
    //   提交 b22da5b7）——已撤回**。撤回理由（三支直证，见实现账本 §5）：
    //   ① 上面那个 **标准循环** 已经覆盖同一件事：{@code trade} 产业的 {@code cycleInputPerUnit = {CATTLE:{tool:100}}}
    //      经 {@code IndustryDefinitionView.inputPerUnit()} 摊平后**就是** {@code tool: 100}，该户持有 100 单位
    // CATTLE 运力资产
    //      ⇒ {@code scale = 100} ⇒ 标准保留 = 100 × 100 = **10,000 毫工具**，逐值 = 旧特例的 {@code declared}
    //      （旧特例是 {@code max(declared, 1,000)} 的"下夹"，declared=10,000 > 1,000 ⇒ 旧代码那一行 **put 都没执行**）
    //      ⇒ 对**有 trade unit 的跑商家户**，旧特例是**恒等操作**（占位而非贡献）。
    //   ② 它当年唯一真正生效的对象 = "选了跑商但**没有 trade unit**"的家户（旧值 = 1,000）。A2 起供给口径改了：
    //      一条承运要成立必须有**运输服务货**（{@code MerchantCapacityPool} 的服务口径 / I-H2），而服务货只能来自
    //      {@code trade@hex} 的产出（产出全归 operator）⇒ 没有 trade unit 的户手上服务货恒 0 ⇒ 运力预算恒 0
    //      ⇒ **它结构上永远分不到承运**。给一个分不到承运的户留工具，是**死重**（且它挂出的 tool 卖单被少卖 ⇒ 工具市场被冻）。
    //   ③ A3 已删除"每趟烧 1,000 毫工具"的门槛（{@code MerchantHaul}）—— 该特例存在的**唯一目的**就是喂这道门槛
    //      （见旧注：判据就是"可用量 ≥ 一趟"）。门槛没了 ⇒ 特例没有要保护的判据。
    //   ⇒ 预留改由**产业声明的投入**自动覆盖（I-H6），不再有跑商专属的硬编码分支。
    return necessary;
  }

  /**
   * ★★★ <b>A3（2026-10-10）：此处原有 {@code merchantHaulToolReserveMilli(round, participant)} —— "跑商家户在
   * {@code tool} 上保留下限 = 恰好一趟"（§16 追加裁定 6 的最小第一步，提交 {@code b22da5b7}）—— <b>已随其调用点（{@link
   * #necessaryInputsOf} 尾部那段）一并撤回</b>。
   *
   * <p>★ 撤回的三支直证（逐条可核，详见 {@link #necessaryInputsOf} 尾部的注与实现账本 §5）： ① 对**有 {@code trade} unit**
   * 的跑商家户，它是**恒等操作**（标准循环已给 10,000 ≥ 1,000 ⇒ 旧 {@code put} 不执行）； ② 它当年唯一生效的对象（没有 trade unit 的跑商家户）在
   * A2 的**服务货**口径下运力预算恒 0，**结构上分不到承运** ⇒ 那 1,000 毫保留是死重；③ 它要保护的"每趟烧工具"门槛已随 {@code MerchantHaul} 退役。
   *
   * <p>★ 用户原话（2026-10-10，逐字）：「跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？」——
   * 该诉求现在由**产业声明投入**的标准路径满足（I-H6），不再需要跑商专属硬编码。
   */
  static Map<CommodityId, Long> householdLifeReserveOf(HouseholdEconomy householdEconomy) {
    Map<CommodityId, Long> life = new LinkedHashMap<>();
    // ★★ 2026-10-09 Batch 3：保留额 = 本户当前注入 naturalNeeds 在补货窗口上的前瞻（逐户读取），
    //    不再按 population × 人均定额现算。
    // ★★ D2（2026-10-09 口径修正）：键集 = **本户当前注入的 naturalNeeds 全部键**，不再由本方法写死粮/布两行。
    //    写死会让"新增的生活必需商品"永远进不了买目标：Social 侧把它算进了 naturalNeeds，而这里 life 恒空 ⇒
    //    baseTarget 恒 0（见 ordersFor 的 `baseTarget = life`）⇒ desiredQuantity 恒 0 ⇒ 零买单、永不产生有效需求。
    //    ★ 权威方向不变：需求仍是 Social 唯一权威，本方法只按**状态里已有的键**做多日前瞻（不自己造需求、不加默认表）。
    //    ★ 保序：naturalNeeds 本身是保序不可变 LinkedHashMap（键序 = app 注入序）⇒ 照它的键序展开；
    //      值为 0 的键当场跳过（expectedNeedMilli 对 0 份额返回 0，等价于"没有这一行需要"）。
    for (CommodityId commodity : householdEconomy.naturalNeeds().keySet()) {
      long need = householdEconomy.expectedNeedMilli(commodity, MARKET_LIFE_RESERVE_DAYS);
      if (need > 0L) {
        life.put(commodity, need);
      }
    }
    return life;
  }

  static Map<CommodityId, Long> operatorLifeRetentionOf(
      MarketRound round, Participant participant, HexCoord hex) {
    Map<CommodityId, Long> retained = new LinkedHashMap<>();
    for (ProductionUnitId id : participant.units()) {
      ProductionProcess unit = round.units.get(id);
      IndustryDefinitionView industry = unit == null ? null : round.industries.get(unit.industry());
      if (unit == null || industry == null) {
        continue;
      }
      ProductionRules relation = round.relations.get(id);
      if (relation == null) {
        continue;
      }
      if (!unit.operator().equals(participant.actor())
          && !relation.operator().equals(participant.actor())) {
        continue;
      }
      // ★ R4-B.3a-perf：家户行由入口索引给（旧实现每个 unit 现扫全量配额）。
      // ★★ 2026-10-09 Batch 3：逐户用 expectedNeedMilli 求补货窗口保留额再求和，
      //    不再先把人头相加、再乘全局人均定额。
      // ★★ D2（2026-10-09 口径修正）：逐户按**该户注入的 naturalNeeds 全部键**求和（与 householdLifeReserveOf 同一口径），
      //    不再写死粮/布两行。★ 现状如实记：参与者恒为家户（P2-A §13.3 —— participantsFor 的三处构造都带 household）
      //    ⇒ 本方法当前**不可达**；改它是为了"经营者角色重新入市"时这里不再留一处写死的商品表（同一族口径只留一份）。
      //    ★ retentionOf 只在"该关系承诺了给养"的商品上有键（承诺额由 relation.rules() 派生）⇒ 请求键集扩大
      //      不会凭空产生保留额。
      Map<CommodityId, Long> requested = new LinkedHashMap<>();
      for (HouseholdId key : round.index.householdsOf(id)) {
        HouseholdEconomy householdEconomy = round.householdEconomies.get(key);
        if (householdEconomy == null) {
          continue;
        }
        for (CommodityId commodity : householdEconomy.naturalNeeds().keySet()) {
          long need = householdEconomy.expectedNeedMilli(commodity, MARKET_LIFE_RESERVE_DAYS);
          if (need > 0L) {
            requested.merge(commodity, need, Math::addExact);
          }
        }
      }
      Map<CommodityId, Long> one =
          SubsistenceObligation.retentionOf(
              relation,
              EconomySettlement.laborOfCohort(round.householdEconomies, hex, industry.cycleDays()),
              requested);
      for (Map.Entry<CommodityId, Long> entry : one.entrySet()) {
        retained.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return retained;
  }

  /**
   * ★★ <b>§一.9（DEBUG）：本格"生活保留额现在含哪些商品"的读数</b>——生活保留口径由"硬编码粮/布两行"改成"遍历本户注入的 {@code naturalNeeds}
   * 全部键"之后，这一行是"某个商品为什么有/没有买目标"的第一现场（{@code life} 为空 ⇒ {@code baseTarget} 恒 0 ⇒ 该商品零买单、永不产生有效需求）。
   *
   * <p>★ <b>位置与粒度</b>：一条 / 格 / 轮（不是一条 / 户）——保留额逐户算，但按格汇总后既不随家户数放大，又能一眼看出商品集； 也刻意<b>不放进</b> {@link
   * #planFor}：读口 {@code planOrders} 会逐商品调 {@code planFor}，记在那里会按商品数放大日志。 家户与经营者两侧分列（经营者侧当前不可达，见
   * {@link #operatorLifeRetentionOf}）。
   */
  static void logLifeReserves(MarketRound round, HexCoord hex, HexPlan plan) {
    if (!MARKET.isDebugEnabled() || plan.participants.isEmpty()) {
      return;
    }
    Map<CommodityId, Long> householdReserve =
        new TreeMap<>(Comparator.comparing(CommodityId::value));
    Map<CommodityId, Long> operatorReserve =
        new TreeMap<>(Comparator.comparing(CommodityId::value));
    int households = 0;
    int operators = 0;
    for (Participant participant : plan.participants) {
      boolean household = participant.household() != null;
      Map<CommodityId, Long> totals = household ? householdReserve : operatorReserve;
      if (household) {
        households++;
      } else {
        operators++;
      }
      for (Map.Entry<CommodityId, Long> entry :
          plan.lifeReserves.getOrDefault(participant.actor(), Map.of()).entrySet()) {
        totals.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_LIFE_RESERVE",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                round.day,
                "hex",
                hex.toString(),
                "households",
                households,
                "householdReserve",
                reserveSummary(householdReserve),
                "operators",
                operators,
                "operatorReserve",
                reserveSummary(operatorReserve)));
  }

  /** 逐商品保留额的规范串（商品 id 升序的 {@code [commodity=milli,...]}；空表 ⇒ {@code []}）。 */
  static String reserveSummary(Map<CommodityId, Long> reserve) {
    if (reserve.isEmpty()) {
      return "[]";
    }
    StringBuilder text = new StringBuilder("[");
    for (Map.Entry<CommodityId, Long> entry : reserve.entrySet()) {
      if (text.length() > 1) {
        text.append(',');
      }
      text.append(entry.getKey().value()).append('=').append(entry.getValue());
    }
    return text.append(']').toString();
  }

  static boolean supplies(Participant participant, Payee supplier) {
    return switch (supplier) {
      case Payee.ToActor toActor -> participant.actor().equals(toActor.actor());
      case Payee.ToHousehold toHousehold ->
          participant.household() != null
              && participant.household().equals(toHousehold.household());
      // ★ 旧档变体：按**视图**比对（构造期归一化已把一对一转到 ToHousehold；这是兼容读的窄出口）。
      case Payee.ToCohort toCohort ->
          participant.view() != null && participant.view().equals(toCohort.cohort());
    };
  }

  // ── 账户读取 / 冻结写入（唯一拼写点）────────────────────────────────────────────────

  static long stockOf(MarketRound round, Participant participant, CommodityId commodity) {
    // ★★ P2-A §13.3：参与者恒为家户（账户主体只有家户）。
    return round
        .householdGoods
        .getOrDefault(participant.household(), Map.of())
        .getOrDefault(commodity, 0L);
  }

  static long frozenGoodsOf(MarketRound round, Participant participant, CommodityId commodity) {
    return round
        .householdFrozenGoods
        .getOrDefault(participant.household(), Map.of())
        .getOrDefault(commodity, 0L);
  }

  static void setFrozenGoods(
      MarketRound round, Participant participant, CommodityId commodity, long amount) {
    long value = Math.max(0L, amount);
    Map<CommodityId, Long> inner =
        new LinkedHashMap<>(
            round.householdFrozenGoods.getOrDefault(participant.household(), Map.of()));
    inner.put(commodity, value);
    round.householdFrozenGoods.put(participant.household(), inner);
  }

  static long frozenMoneyOf(MarketRound round, Participant participant, CurrencyId currency) {
    return round
        .householdFrozenMoney
        .getOrDefault(participant.household(), Map.of())
        .getOrDefault(currency, 0L);
  }

  static void setFrozenMoney(
      MarketRound round, Participant participant, CurrencyId currency, long amount) {
    long value = Math.max(0L, amount);
    Map<CurrencyId, Long> inner =
        new LinkedHashMap<>(
            round.householdFrozenMoney.getOrDefault(participant.household(), Map.of()));
    inner.put(currency, value);
    round.householdFrozenMoney.put(participant.household(), inner);
  }

  static long spendableMoneyOf(MarketRound round, Participant participant, CurrencyId currency) {
    return spendableMoneyOf(round, participant.household(), currency);
  }

  /**
   * ★★ <b>A2a：家户（而不是参与者）为键的可花额</b> —— 外汇面按"逐格逐户"扫描（政府国库户不在参与者表里，但它 的外汇窗口必须读得到自己的储备）。
   *
   * <p>★ 与上面那个 Participant 版<b>逐值同源</b>（后者直接委托本方法）：同一件事不许有两套算式。
   */
  static long spendableMoneyOf(MarketRound round, HouseholdId household, CurrencyId currency) {
    long balance =
        round.householdMoney.getOrDefault(household, Map.of()).getOrDefault(currency, 0L);
    long frozen =
        round.householdFrozenMoney.getOrDefault(household, Map.of()).getOrDefault(currency, 0L);
    return Math.max(0L, balance - frozen);
  }

  static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1L) / denominator;
  }

  /** 买到手的量冲减当日未满足需求（封顶 = 已记的缺口；**只对家户**，经营者没有那条读数）。 */
  static void reduceUnmet(
      Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
      HouseholdId buyer,
      CommodityId commodity,
      long quantity) {
    long recorded = unmetToday.getOrDefault(buyer, Map.of()).getOrDefault(commodity, 0L);
    long reduced = Math.min(recorded, quantity);
    if (reduced <= 0L) {
      return;
    }
    Map<CommodityId, Long> updated = new LinkedHashMap<>(unmetToday.getOrDefault(buyer, Map.of()));
    if (recorded - reduced <= 0L) {
      updated.remove(commodity);
    } else {
      updated.put(commodity, recorded - reduced);
    }
    unmetToday.put(buyer, updated);
  }

  /**
   * ★★ <b>本世界有没有可收款承运人 = 有没有运力池</b>（M-A1：运力提供者 = 选了跑商的家户，CARRIER_FEE 收款人 = 它的家户账）。
   *
   * <p>★ 旧路径的"第一个有货币账的 ORGANIZATION"承运人**不复存在**（组织不持账）；没有运力池的世界 ⇒ 没有可收款承运人， 跨格路线在候选生成处就不建（具名 {@code
   * LOGISTICS_CAPACITY}），不再有"货照走、运费记未收"的兜底。
   */
  static boolean carrierPresent(MatchContext ctx) {
    return !ctx.carrierPool.isEmpty();
  }

  /** 本轮市场里出现过的全部商品（按 id 字典序；撮合顺序因此是内容的纯函数）。 */
  static List<CommodityId> orderedCommodities(MatchContext ctx) {
    Set<CommodityId> all = new LinkedHashSet<>();
    for (MarketTable market : ctx.marketTables.values()) {
      all.addAll(market.prices().keySet());
    }
    List<CommodityId> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparing(CommodityId::value));
    return sorted;
  }
}
