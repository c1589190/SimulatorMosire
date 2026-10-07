package io.mosire.simos.app.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.ExpectedProfitBook;
import io.mosire.simos.economy.time.MarketDemandBook;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.economy.time.ModeMigrationPolicy;
import io.mosire.simos.economy.time.ProductionProcessBook;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.household.HouseholdFleeState;
import io.mosire.simos.social.population.SocialVitalRemainder;
import io.mosire.simos.social.population.SocialVitalRemainders;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
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
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * ★★ <b>Z7d-2：官吏户逃亡（flee）的纯函数桥</b>——设计书 §7 的唯一执行语义落点。
 *
 * <pre>
 * 驱动（按户、每日、确定性）：
 *   underpaid = ADMIN_SALARY 该户 shortfall > 0  ∨  ADMIN_STIPEND 该 GOV shortfall > 0
 *   rate' = min(1000, rate + 150)                              , underpaid
 *           + min(1000, ⌊150 × (1000−satiety)/1000⌋)           , 追加（饥饿按比例，与 underpaid 不互斥）
 *         = min(1000, rate + ⌊150 × (1000−satiety)/1000⌋)      , 付足但挨饿
 *         = max(0,    rate − 20)                               , 付足且吃饱
 *   执行（按户、每日、确定性）：
 *   累计毫人 = 户内成员 × rate + 余数（跨日结转）
 *   走人     = min(户内成员, ⌊累计 ÷ 1000⌋)；新余数 = 累计 − 走人 × 1000
 *   去向     = 该 GOV 有效位置格内的经济家户（排除政府/单位户），按 可吸收劳动余量 × 单位劳动预期利润 → 人均（粮+银+资产） → 家户 id
 *   只转成员（Social TRANSFER_MEMBERS 同款）；户空 ⇒ 释放 GOV_SERVICE 承诺 + 请求 unit 侧摘岗位/摘 households
 * </pre>
 *
 * <p>★★ <b>为什么不直接写 state</b>：本类只收"当日事实"（日结后 Social/Economy 工作副本、逐户工资缺口、逐 GOV 俸禄缺口）并返回 {@link
 * Outcome}（新 Social + 承诺缩减表 + 人口 delta + unit 摘除请求 + 危机信号）；真正落 state 由 {@link
 * PopulationEconomyTimeParticipant} 在同一 revision 内编排（铁律 2/3/4）。
 *
 * <p>★ <b>确定性</b>：不碰墙钟/随机；所有遍历按 unit id / household id / lot id 规范序；同一输入同输出。
 */
public final class GovernmentServiceDesertionBridge {

  /** 逃亡快升（欠俸；‰/日，设计书 §9 冻结默认）。 */
  static final long FAST_RISE_PER_DAY_PER_MILLE = 150L;

  /** 饥饿追加（‰/日 × 饥饿比例；设计书 §9 冻结默认）。 */
  static final long HUNGER_RISE_PER_DAY_PER_MILLE = 150L;

  /** 付足且吃饱的慢降（‰/日，下限 0）。 */
  static final long SLOW_FALL_PER_DAY_PER_MILLE = 20L;

  /** 需要建需求簿时的视界（天；与 {@link ExpectedProfitBook#DEFAULT_MERCHANT_CYCLE_DAYS} 同口径）。 */
  static final long DEMAND_HORIZON_DAYS = ExpectedProfitBook.DEFAULT_MERCHANT_CYCLE_DAYS;

  /** 逃亡原因标签（进日志/读口/信号 evidence）。 */
  static final String REASON_UNDERPAID = "underpaid";

  static final String REASON_HUNGER = "hunger";
  static final String REASON_UNDERPAID_HUNGER = "underpaid+hunger";
  static final String REASON_SATISFIED = "satisfied";

  private static final Logger LOG = AppLog.time();

  private GovernmentServiceDesertionBridge() {}

  /** 一条 unit 侧摘除请求（户已空；由 app 组合根在 unit 变更集中原样执行）。 */
  record Eviction(
      UnitId gov,
      HouseholdId household,
      long day,
      String reason,
      long membersBefore,
      long fledCount) {

    Eviction {
      Objects.requireNonNull(gov, "gov");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(reason, "reason");
      if (membersBefore < 0L || fledCount <= 0L) {
        throw new IllegalArgumentException(
            "Eviction 的 membersBefore 必须 ≥ 0、fledCount 必须 > 0: " + membersBefore + "/" + fledCount);
      }
    }
  }

  /** 一次逃亡执行的完整产出（落 state 由调用方编排）。 */
  record Outcome(
      SocialData social,
      List<Eviction> evictions,
      Map<LaborAllocationId, Long> commitmentReductions,
      Map<HouseholdId, Long> populationDeltas,
      List<HouseholdId> emptiedHouseholds,
      List<HexCrisisSignal> signals,
      Report report) {

    Outcome {
      Objects.requireNonNull(social, "social");
      evictions = List.copyOf(Objects.requireNonNull(evictions, "evictions"));
      commitmentReductions =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(
                  Objects.requireNonNull(commitmentReductions, "commitmentReductions")));
      populationDeltas =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(populationDeltas, "populationDeltas")));
      emptiedHouseholds =
          List.copyOf(Objects.requireNonNull(emptiedHouseholds, "emptiedHouseholds"));
      signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
      Objects.requireNonNull(report, "report");
    }
  }

  /** 只进日志/台账的跨 GOV 汇总计数。 */
  record Report(
      long govUnits,
      long householdsEvaluated,
      long rises,
      long falls,
      long tierCrossings,
      long flights,
      long fledPopulation,
      long emptiedHouseholds,
      long noDestinationSkips) {

    Report {
      if (govUnits < 0L
          || householdsEvaluated < 0L
          || rises < 0L
          || falls < 0L
          || tierCrossings < 0L
          || flights < 0L
          || fledPopulation < 0L
          || emptiedHouseholds < 0L
          || noDestinationSkips < 0L) {
        throw new IllegalArgumentException("Report 的计数不得为负: " + this);
      }
    }
  }

  /** 一个去向候选的得分（越大越优先；同分按家户 id 升序）。 */
  record DestinationScore(
      HouseholdId household,
      long score,
      long laborMarginMilli,
      long profitPerLaborScaled,
      boolean fallbackWealth) {

    DestinationScore {
      Objects.requireNonNull(household, "household");
      if (laborMarginMilli < 0L) {
        throw new IllegalArgumentException(
            "DestinationScore.laborMarginMilli 不得为负: " + laborMarginMilli);
      }
    }
  }

  private record DestinationContext(
      Map<HouseholdId, HouseholdEconomy> rows,
      Set<AssetShareId> claimedByEnterprises,
      MarketDemandBook.Book demand,
      AccountSession accounts) {

    DestinationContext {
      Objects.requireNonNull(rows, "rows");
      Objects.requireNonNull(claimedByEnterprises, "claimedByEnterprises");
      Objects.requireNonNull(demand, "demand");
      Objects.requireNonNull(accounts, "accounts");
    }
  }

  /**
   * 执行一次全 GOV 的每日驱动 + 逃亡（纯函数：输入与返回的 {@link SocialData} 不共享可变块，除入参只读外不写任何外部状态）。
   *
   * @param socialIn 当日结算后的 Social（含 Z7d-1 satiety）
   * @param economy 推进基态 Economy（只读；用于候选户/承诺集合 —— GOV_SERVICE 行在当日 step 中不可缩）
   * @param liveEconomy 当日预算执行后的 Economy 工作副本的惰性提供者（只在真需要排序/减承诺时求值； 正常日无真走人 ⇒ 不构造 EconomyData
   *     预览，避免每日全量守卫开销）
   * @param units unit 切片（只读：找 GOV/岗位户/位置）
   * @param salaryShortfallByHousehold 当日逐户 ADMIN_SALARY 缺口（毫；只含 > 0）
   * @param stipendShortfallByUnit 当日逐 GOV ADMIN_STIPEND 缺口（粮/布腿合计；只含 > 0）
   * @param accounts 账户会话（去财富兜底用，只读）
   * @param topology 市场拓扑（{@link ExpectedProfitBook#prospect} 的入参）
   * @param reports 本日市场报告（保序；只取最后一条非 null 建需求簿）
   * @param day 世界日
   */
  static Outcome execute(
      SocialData socialIn,
      EconomyData economy,
      Supplier<EconomyData> liveEconomy,
      UnitState units,
      Map<HouseholdId, Long> salaryShortfallByHousehold,
      Map<UnitId, Long> stipendShortfallByUnit,
      AccountSession accounts,
      MarketTopology topology,
      List<MarketReport> reports,
      long day) {
    Objects.requireNonNull(socialIn, "socialIn");
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(liveEconomy, "liveEconomy");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(salaryShortfallByHousehold, "salaryShortfallByHousehold");
    Objects.requireNonNull(stipendShortfallByUnit, "stipendShortfallByUnit");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(reports, "reports");
    if (day < 0L) {
      throw new IllegalArgumentException(
          "GovernmentServiceDesertionBridge.execute 的 day 不得为负: " + day);
    }

    SocialData social = socialIn;
    Map<HouseholdId, HouseholdFleeState> fleeStates = new LinkedHashMap<>(social.fleeStates());
    Map<LaborAllocationId, Long> commitmentReductions = new LinkedHashMap<>();
    Map<HouseholdId, Long> populationDeltas = new LinkedHashMap<>();
    List<Eviction> evictions = new ArrayList<>();
    List<HouseholdId> emptied = new ArrayList<>();
    List<HexCrisisSignal> signals = new ArrayList<>();
    long govUnits = 0L;
    long evaluated = 0L;
    long rises = 0L;
    long falls = 0L;
    long tierCrossings = 0L;
    long flights = 0L;
    long fledPopulation = 0L;
    long noDestinationSkips = 0L;

    for (Unit unit : sortedGovUnits(units)) {
      GovernmentFormation formation = (GovernmentFormation) unit.module().orElseThrow();
      UnitId gov = unit.id();
      HouseholdId treasury = GovernmentHouseholds.of(gov.value());
      Optional<HexCoord> govSeat =
          units.effectivePosition(gov, io.mosire.simos.util.time.SimosTimestamp.of(day));
      govUnits++;
      LinkedHashSet<HouseholdId> candidates = new LinkedHashSet<>();
      candidates.addAll(formation.governmentPostsOfHousehold().keySet());
      Map<HouseholdId, Long> committed =
          GovernmentServiceLaborBridge.committedLaborByHousehold(economy, gov, day);
      for (HouseholdId household : committed.keySet()) {
        if (unit.households().contains(household) && !treasury.equals(household)) {
          candidates.add(household);
        }
      }
      List<HouseholdId> ordered = new ArrayList<>(candidates);
      ordered.sort(Comparator.comparing(HouseholdId::value));
      for (HouseholdId household : ordered) {
        if (treasury.equals(household) || !social.households().containsKey(household)) {
          continue;
        }
        HouseholdFleeState before = fleeStates.getOrDefault(household, HouseholdFleeState.none());
        long salaryShortfall = Math.max(0L, salaryShortfallByHousehold.getOrDefault(household, 0L));
        long stipendShortfall = Math.max(0L, stipendShortfallByUnit.getOrDefault(gov, 0L));
        boolean underpaid = salaryShortfall > 0L || stipendShortfall > 0L;
        long satiety = social.satietyPerMille(household);
        long rateBefore = before.fleeRatePerMille();
        long rateAfter;
        String driverReason;
        if (underpaid) {
          rateAfter =
              Math.min(
                  HouseholdFleeState.RATE_MAX_PER_MILLE, rateBefore + FAST_RISE_PER_DAY_PER_MILLE);
          if (satiety < SocialData.SATIETY_FULL_PER_MILLE) {
            rateAfter =
                Math.min(
                    HouseholdFleeState.RATE_MAX_PER_MILLE,
                    Math.addExact(rateAfter, hungerRisePerDay(satiety)));
            driverReason = REASON_UNDERPAID_HUNGER;
          } else {
            driverReason = REASON_UNDERPAID;
          }
        } else if (satiety < SocialData.SATIETY_FULL_PER_MILLE) {
          rateAfter =
              Math.min(
                  HouseholdFleeState.RATE_MAX_PER_MILLE,
                  Math.addExact(rateBefore, hungerRisePerDay(satiety)));
          driverReason = REASON_HUNGER;
        } else {
          rateAfter = Math.max(0L, rateBefore - SLOW_FALL_PER_DAY_PER_MILLE);
          driverReason = REASON_SATISFIED;
        }
        evaluated++;
        if (rateAfter > rateBefore) {
          rises++;
        } else if (rateAfter < rateBefore) {
          falls++;
        }
        boolean tierCrossed = rateTier(rateBefore) != rateTier(rateAfter);
        if (tierCrossed) {
          tierCrossings++;
          EventLog.channel(LOG)
              .info(
                  LogEvent.of(
                      "GOV_SERVICE_DESERTION",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "unit",
                      gov.value(),
                      "household",
                      household.value(),
                      "rateBefore",
                      rateBefore,
                      "ratePerMille",
                      rateAfter,
                      "fledCount",
                      0L,
                      "reason",
                      driverReason,
                      "satietyPerMille",
                      satiety,
                      "salaryShortfallMilli",
                      salaryShortfall,
                      "stipendShortfallMilli",
                      stipendShortfall,
                      "trigger",
                      "rate-tier-crossing"));
        }
        HouseholdFleeState state = before.withRateAndRemainder(rateAfter, before.remainderMilli());
        long members = social.householdPopulation(household);
        long accumulated =
            Math.addExact(Math.multiplyExact(members, rateAfter), before.remainderMilli());
        long fled = Math.min(members, accumulated / HouseholdFleeState.MILLI_PER_PERSON);
        long newRemainder =
            Math.subtractExact(
                accumulated, Math.multiplyExact(fled, HouseholdFleeState.MILLI_PER_PERSON));
        if (fled > 0L && govSeat.isEmpty()) {
          noDestinationSkips++;
          state = state.withRateAndRemainder(rateAfter, accumulated).withDriver(day, driverReason);
          fleeStates.put(household, state);
          EventLog.channel(LOG)
              .info(
                  LogEvent.of(
                      "GOV_SERVICE_DESERTION_SKIPPED",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "unit",
                      gov.value(),
                      "household",
                      household.value(),
                      "reason",
                      "gov-has-no-effective-position",
                      "ratePerMille",
                      rateAfter,
                      "accumulatedMilli",
                      accumulated));
          continue;
        }
        if (fled > 0L) {
          // ★ 只有真要走人时才构造 Economy 工作副本预览（正常日 driver 只读基态承诺，不建状态）。
          EconomyData live = liveEconomy.get();
          Objects.requireNonNull(live, "liveEconomy.get()");
          DestinationContext destinationContext =
              buildDestinationContext(live, accounts, topology, reports, day);
          List<DestinationScore> destinations =
              rankDestinations(
                  social, govSeat.get(), live, units, accounts, topology, day, destinationContext);
          if (destinations.isEmpty()) {
            noDestinationSkips++;
            // 没有可去的经济家户 ⇒ 整批人原地累积（不丢人）；余数允许暂时 ≥ 1000，等有去向时再按满人走。
            state =
                state.withRateAndRemainder(rateAfter, accumulated).withDriver(day, driverReason);
            fleeStates.put(household, state);
            EventLog.channel(LOG)
                .info(
                    LogEvent.of(
                        "GOV_SERVICE_DESERTION_SKIPPED",
                        AppLogSource.DAILY_LOOP,
                        "day",
                        day,
                        "unit",
                        gov.value(),
                        "household",
                        household.value(),
                        "reason",
                        "no-economic-household-at-seat",
                        "ratePerMille",
                        rateAfter,
                        "accumulatedMilli",
                        accumulated));
            continue;
          }
          DestinationScore destination = destinations.get(0);
          SocialData beforeTransfer = social;
          List<MemberTake> takes = allocateTakes(beforeTransfer.requireHousehold(household), fled);
          long moved = 0L;
          for (MemberTake take : takes) {
            social =
                HouseholdBook.transferMembers(
                    social,
                    household,
                    destination.household(),
                    take.lot(),
                    take.count(),
                    "gov-service-desertion:" + gov.value() + ":" + driverReason);
            moved = Math.addExact(moved, take.count());
          }
          if (moved != fled) {
            throw new IllegalStateException(
                "逃亡成员分派不守恒: household=" + household + " requested=" + fled + " moved=" + moved);
          }
          state =
              state
                  .withRateAndRemainder(rateAfter, newRemainder)
                  .withDriver(day, driverReason)
                  .withLastFlight(day, fled, driverReason);
          flights++;
          fledPopulation = Math.addExact(fledPopulation, fled);
          mergeDelta(populationDeltas, household, -fled);
          mergeDelta(populationDeltas, destination.household(), fled);
          reduceGovServiceCommitments(live, household, members, fled, commitmentReductions);
          // 整批走光的 lot：清掉源户对应生死余数（不足 1 次事件的分数），避免次日 settle 的
          // POPULATION_SETTLE_REMAINDERS_CLEANED WARN 与跨户半事件漂移。
          List<PeopleLotId> fullyMovedLots = new ArrayList<>();
          Household householdBeforeTransfer = beforeTransfer.requireHousehold(household);
          for (MemberTake take : takes) {
            if (householdBeforeTransfer.memberCount(take.lot()) == take.count()) {
              fullyMovedLots.add(take.lot());
            }
          }
          social = pruneVitalRemainders(social, household, fullyMovedLots);
          boolean emptiedHousehold = fled == members;
          if (emptiedHousehold) {
            // ★ 户空：Social 侧保留 0 人壳户并迁到 GOV 座位格（经济行/账户不造孤儿，避免
            //   CLASSROW_POPULATION_PROJECTION_UNRESOLVED WARN）；单位侧由 Eviction 摘岗位/摘 households。
            social = disbandEmptyHousehold(social, household, govSeat.get(), gov, driverReason);
            // ★ 保留"最近一次逃亡"读数：壳户留一条 rate=0/余数=0 的历史记录（lastFlee* 不丢），
            //   simos.gov.info 的 desertion 视图据此仍能显示最近逃亡（见 GovInfoTool.desertionView）。
            fleeStates.put(household, state.withRateAndRemainder(0L, 0L));
            emptied.add(household);
            evictions.add(new Eviction(gov, household, day, driverReason, members, fled));
          } else {
            fleeStates.put(household, state);
          }
          HexCrisisSignal signal =
              desertionSignal(
                  govSeat.orElse(null),
                  household,
                  day,
                  rateAfter,
                  fled,
                  members,
                  driverReason,
                  satiety,
                  salaryShortfall,
                  stipendShortfall);
          if (signal != null) {
            signals.add(signal);
          }
          EventLog.channel(LOG)
              .info(
                  LogEvent.of(
                      "GOV_SERVICE_DESERTION",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "unit",
                      gov.value(),
                      "household",
                      household.value(),
                      "rateBefore",
                      rateBefore,
                      "ratePerMille",
                      rateAfter,
                      "fledCount",
                      fled,
                      "membersBefore",
                      members,
                      "destination",
                      destination.household().value(),
                      "destinationScore",
                      destination.score(),
                      "destinationBasis",
                      destination.fallbackWealth() ? "per-capita-wealth" : "margin-x-profit",
                      "reason",
                      driverReason,
                      "satietyPerMille",
                      satiety,
                      "salaryShortfallMilli",
                      salaryShortfall,
                      "stipendShortfallMilli",
                      stipendShortfall,
                      "trigger",
                      "members-fled"));
        } else {
          state = state.withRateAndRemainder(rateAfter, newRemainder).withDriver(day, driverReason);
          fleeStates.put(household, state);
        }
      }
    }
    SocialData next = social.withFleeStates(fleeStates);
    Report report =
        new Report(
            govUnits,
            evaluated,
            rises,
            falls,
            tierCrossings,
            flights,
            fledPopulation,
            emptied.size(),
            noDestinationSkips);
    return new Outcome(
        next, evictions, commitmentReductions, populationDeltas, emptied, signals, report);
  }

  /** 饥饿追加速率（‰/日）= ⌊150 × (1000−satiety)/1000⌋；satiety=1000 ⇒ 0。 */
  static long hungerRisePerDay(long satietyPerMille) {
    if (satietyPerMille < 0L || satietyPerMille > SocialData.SATIETY_FULL_PER_MILLE) {
      throw new IllegalArgumentException("satietyPerMille 必须在 0..1000: " + satietyPerMille);
    }
    long hunger = SocialData.SATIETY_FULL_PER_MILLE - satietyPerMille;
    return Math.floorDiv(Math.multiplyExact(HUNGER_RISE_PER_DAY_PER_MILLE, hunger), 1_000L);
  }

  /** 速度档（只在跨档时发一条 INFO）：0 / 1..249 / 250..499 / 500..749 / 750..999 / 1000。 */
  public static int rateTier(long ratePerMille) {
    if (ratePerMille < 0L || ratePerMille > HouseholdFleeState.RATE_MAX_PER_MILLE) {
      throw new IllegalArgumentException("ratePerMille 必须在 0..1000: " + ratePerMille);
    }
    if (ratePerMille == 0L) {
      return 0;
    }
    if (ratePerMille >= HouseholdFleeState.RATE_MAX_PER_MILLE) {
      return 5;
    }
    return (int) ((ratePerMille - 1L) / 250L) + 1;
  }

  /** 逐户人口 delta 合并（0 结果移除；调用方按 `applyHouseholdPopulationDeltas` 的口径使用）。 */
  private static void mergeDelta(Map<HouseholdId, Long> deltas, HouseholdId household, long delta) {
    deltas.merge(household, delta, Math::addExact);
    deltas.entrySet().removeIf(entry -> entry.getValue() == 0L);
  }

  /**
   * 户空：Social 家户保留为 0 人壳户，位置迁到 GOV 座位格（HEX）——人口/批次真值已随 TRANSFER_MEMBERS 全部落在目标户； 这样经济行、actor 账户与
   * Social 家户三方不产生"孤儿行"（否则次日 {@code HouseholdEconomyProjection} 会每日 WARN）， 单位侧的"摘岗位/摘 households"由
   * {@link Eviction} 在同一 revision 完成。
   */
  private static SocialData disbandEmptyHousehold(
      SocialData social, HouseholdId household, HexCoord seat, UnitId gov, String reason) {
    SocialData moved =
        HouseholdBook.setLocation(
            social,
            household,
            new HouseholdLocation.Hex(seat),
            "gov-service-desertion:empty-household:" + gov.value() + ":" + reason);
    Map<HouseholdId, Long> satiety = new LinkedHashMap<>(moved.satietyPerMille());
    satiety.remove(household);
    return moved.withSatietyPerMille(satiety);
  }

  /** 整批走光的 lot：清掉源户对应生死余数（不足 1 次事件的分数），避免次日 settle 的清理 WARN 与跨户半事件漂移。 */
  private static SocialData pruneVitalRemainders(
      SocialData social, HouseholdId household, List<PeopleLotId> fullyMovedLots) {
    if (fullyMovedLots.isEmpty() || social.vitalRemainders().entries().isEmpty()) {
      return social;
    }
    Set<PeopleLotId> remove = new LinkedHashSet<>(fullyMovedLots);
    List<SocialVitalRemainder> kept = new ArrayList<>();
    for (SocialVitalRemainder entry : social.vitalRemainders().entries()) {
      if (entry.householdId().equals(household) && remove.contains(entry.lotId())) {
        continue;
      }
      kept.add(entry);
    }
    return social.withVitalRemainders(new SocialVitalRemainders(kept));
  }

  /**
   * 按离开成员份额显式缩减该户的 {@code GOV_SERVICE} 承诺；{@code remaining == 0} ⇒ 释放（0 = 删行）。 用 BigInteger 做
   * {@code floor(old × remaining / before)}，不溢出、不引入 double。
   */
  private static void reduceGovServiceCommitments(
      EconomyData economy,
      HouseholdId household,
      long membersBefore,
      long fled,
      Map<LaborAllocationId, Long> reductions) {
    if (membersBefore <= 0L) {
      return;
    }
    long remaining = membersBefore - fled;
    List<HouseholdLaborCommitment> rows = new ArrayList<>();
    for (HouseholdLaborCommitment commitment : economy.allocations().values()) {
      if (commitment.household().equals(household)
          && commitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        rows.add(commitment);
      }
    }
    rows.sort(Comparator.comparing(row -> row.id().value()));
    for (HouseholdLaborCommitment row : rows) {
      long next;
      if (remaining <= 0L) {
        next = 0L;
      } else if (remaining >= membersBefore) {
        next = row.laborMilli();
      } else {
        BigInteger numerator =
            BigInteger.valueOf(row.laborMilli()).multiply(BigInteger.valueOf(remaining));
        next = numerator.divide(BigInteger.valueOf(membersBefore)).longValueExact();
      }
      reductions.put(row.id(), next);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "GOV_SERVICE_COMMITMENT_DESERTION_REDUCED",
                  AppLogSource.DAILY_LOOP,
                  "household",
                  household.value(),
                  "commitment",
                  row.id().value(),
                  "membersBefore",
                  membersBefore,
                  "fledCount",
                  fled,
                  "membersAfter",
                  remaining,
                  "laborBefore",
                  row.laborMilli(),
                  "laborAfter",
                  next));
    }
  }

  /** 一次逃亡的成员批次分派（按 lot id 规范序，逐批取 min(份额, 剩余)）；Σ take == count。 */
  private static List<MemberTake> allocateTakes(Household household, long count) {
    List<PeopleLotId> lots = new ArrayList<>(household.members().keySet());
    lots.sort(Comparator.comparing(PeopleLotId::value));
    List<MemberTake> takes = new ArrayList<>();
    long left = count;
    for (PeopleLotId lot : lots) {
      if (left <= 0L) {
        break;
      }
      long share = household.memberCount(lot);
      long take = Math.min(share, left);
      if (take > 0L) {
        takes.add(new MemberTake(lot, take));
        left -= take;
      }
    }
    if (left != 0L) {
      throw new IllegalStateException(
          "逃亡成员分派超出户内份额: household=" + household.id() + " count=" + count + " left=" + left);
    }
    return List.copyOf(takes);
  }

  private record MemberTake(PeopleLotId lot, long count) {

    MemberTake {
      Objects.requireNonNull(lot, "lot");
      if (count <= 0L) {
        throw new IllegalArgumentException("MemberTake.count 必须 > 0: " + count);
      }
    }
  }

  /** 建一次需求簿/占用份额（只在真有逃亡候选时构造；失败退化为空簿，不阻断日推进）。 */
  private static DestinationContext buildDestinationContext(
      EconomyData economy,
      AccountSession accounts,
      MarketTopology topology,
      List<MarketReport> reports,
      long day) {
    Set<AssetShareId> claimed =
        ModeMigrationPolicy.claimedOwnershipStakes(
            economy.productionOrganizations(), economy.units(), economy.assetShares());
    MarketDemandBook.Book demand;
    try {
      demand =
          MarketDemandBook.build(
              reports,
              topology,
              economy.classes(),
              economy.units(),
              economy.industries(),
              economy.assetShares(),
              economy.shipments(),
              accounts,
              economy.markets(),
              day,
              DEMAND_HORIZON_DAYS);
    } catch (RuntimeException failure) {
      demand = MarketDemandBook.Book.empty(day, DEMAND_HORIZON_DAYS);
    }
    return new DestinationContext(economy.classes(), claimed, demand, accounts);
  }

  /**
   * 去向排序（设计书 §7 / 用户原话"肯定优先往理论上加入后赚钱多的走"）：同格经济家户先按 {@code 可吸收劳动余量 × 单位劳动预期利润}
   * 降序；无生产机会（margin/prospect 任一 ≤ 0）退化为 {@code 人均（粮+银+资产）} 降序；同分一律按家户 id 升序。
   */
  private static List<DestinationScore> rankDestinations(
      SocialData social,
      HexCoord hex,
      EconomyData economy,
      UnitState units,
      AccountSession accounts,
      MarketTopology topology,
      long day,
      DestinationContext context) {
    Set<HouseholdId> governmentOrUnitHouseholds = new LinkedHashSet<>();
    for (Unit candidateUnit : units.units().values()) {
      governmentOrUnitHouseholds.addAll(candidateUnit.households());
      if (candidateUnit.module().orElse(null) instanceof GovernmentFormation) {
        governmentOrUnitHouseholds.add(GovernmentHouseholds.of(candidateUnit.id().value()));
      }
    }
    List<DestinationScore> scores = new ArrayList<>();
    for (Household household : social.householdsAt(hex)) {
      HouseholdId destination = household.id();
      if (governmentOrUnitHouseholds.contains(destination)
          || GovernmentHouseholds.isGovernment(destination)) {
        continue;
      }
      HouseholdEconomy destinationRow = context.rows().get(destination);
      if (destinationRow == null || destinationRow.population() <= 0L) {
        continue; // 经济家户必须真的有人可吸收；0 人壳户（含逃亡后的旧官吏户壳）不是去向。
      }
      long margin = absorbableLaborMargin(destination, hex, economy);
      long profit = expectedProfitPerLabor(destination, hex, economy, topology, day, context);
      long score;
      boolean fallback;
      if (margin > 0L && profit > 0L) {
        score = scaledProduct(margin, profit);
        fallback = false;
      } else {
        score = perCapitaWealth(destination, economy, context, accounts);
        margin = 0L;
        profit = 0L;
        fallback = true;
      }
      scores.add(new DestinationScore(destination, score, margin, profit, fallback));
    }
    scores.sort(
        Comparator.comparingLong(DestinationScore::score)
            .reversed()
            .thenComparing(score -> score.household().value()));
    return List.copyOf(scores);
  }

  /** {@code ⌊margin × profit ÷ 1_000_000⌋}（profit 是百万分之一刻度；溢出饱和到 Long.MAX）。 */
  private static long scaledProduct(long margin, long profit) {
    try {
      return Math.floorDiv(Math.multiplyExact(margin, profit), ExpectedProfitBook.PER_LABOR_SCALE);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /** 该户在格上的可吸收劳动余量 = Σ_{自有 unit} max(0, 产能劳动 − 既有承诺劳动)。 */
  private static long absorbableLaborMargin(
      HouseholdId household, HexCoord hex, EconomyData economy) {
    ActorRef operator = HouseholdActors.of(household);
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    long margin = 0L;
    for (ProductionProcess process : economy.units().values()) {
      if (!process.operator().equals(operator)) {
        continue;
      }
      Optional<String> processHex = IndustryHexKeys.hexKeyOf(process.industry());
      if (processHex.isEmpty() || !processHex.get().equals(hexKey)) {
        continue;
      }
      Industry industry = economy.industries().get(process.industry());
      if (industry == null) {
        continue;
      }
      long scale = ProductionProcessBook.capacityScaleOf(process, industry, economy.assetShares());
      long capacityLabor = Math.multiplyExact(scale, industry.recipe().laborPerUnit());
      long committed = 0L;
      for (HouseholdLaborCommitment commitment : economy.allocations().values()) {
        if (commitment.household().equals(household)
            && commitment.activity().equals(process.id().value())) {
          committed = Math.addExact(committed, commitment.laborMilli());
        }
      }
      margin = Math.addExact(margin, Math.max(0L, capacityLabor - committed));
    }
    return margin;
  }

  /** 单位劳动预期利润：该户当前位置的 mode 在该格的 {@link ExpectedProfitBook#prospect}（不可行/≤0 ⇒ 0）。 */
  private static long expectedProfitPerLabor(
      HouseholdId household,
      HexCoord hex,
      EconomyData economy,
      MarketTopology topology,
      long day,
      DestinationContext context) {
    HouseholdClassMembership membership = economy.classStandings().get(household);
    if (membership == null) {
      return 0L;
    }
    ProductionRole role = economy.classPositions().get(membership.currentPositionId());
    if (role == null) {
      return 0L;
    }
    ProductionModeId modeId = role.modeId();
    if (modeId == null || economy.modes().get(modeId) == null) {
      return 0L;
    }
    Market market = economy.markets().get(hex);
    try {
      ExpectedProfitBook.Prospect prospect =
          ExpectedProfitBook.prospect(
              economy,
              household,
              modeId,
              hex,
              null,
              market,
              context.demand(),
              economy.assetShares(),
              context.claimedByEnterprises(),
              context.accounts(),
              economy.units(),
              economy.relations(),
              topology,
              day);
      if (!prospect.feasible() || prospect.netPerLaborScaled() <= 0L) {
        return 0L;
      }
      return prospect.netPerLaborScaled();
    } catch (RuntimeException failure) {
      return 0L;
    }
  }

  /** 无生产机会的退化分：人均（粮 + 银 + 资产份额数量）。 */
  private static long perCapitaWealth(
      HouseholdId household,
      EconomyData economy,
      DestinationContext context,
      AccountSession accounts) {
    long grain =
        accounts
            .householdGoods()
            .getOrDefault(household, Map.of())
            .getOrDefault(EconomySettlement.GRAIN, 0L);
    long silver =
        accounts
            .householdMoney()
            .getOrDefault(household, Map.of())
            .getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L);
    ActorRef owner = HouseholdActors.of(household);
    long assets = 0L;
    for (OwnershipStake share : economy.assetShares().values()) {
      if (share.owner().equals(owner)) {
        assets = Math.addExact(assets, share.quantity());
      }
    }
    long population = context.rows().get(household).population();
    if (population <= 0L) {
      population = 1L;
    }
    long wealth = Math.addExact(Math.addExact(grain, silver), assets);
    return Math.floorDiv(wealth, population);
  }

  /** 真正走人的危机信号（GM/该 GOV 决策人可见；只发信号，不自动补俸/招人）。 */
  private static HexCrisisSignal desertionSignal(
      HexCoord hex,
      HouseholdId household,
      long day,
      long ratePerMille,
      long fledCount,
      long membersBefore,
      String driverReason,
      long satiety,
      long salaryShortfall,
      long stipendShortfall) {
    if (hex == null) {
      // GOV 没有可解析位置时，GovDaily 本身就不产信号；这里退化为"不产信号"（事实已在 INFO 日志）。
      return null;
    }
    Map<String, Long> evidence = new LinkedHashMap<>();
    evidence.put("fleeRatePerMille", ratePerMille);
    evidence.put("fleeCount", fledCount);
    evidence.put("membersBefore", membersBefore);
    evidence.put("satietyPerMille", satiety);
    evidence.put("salaryShortfallMilli", salaryShortfall);
    evidence.put("stipendShortfallMilli", stipendShortfall);
    evidence.put("underpaid", driverReason.contains(REASON_UNDERPAID) ? 1L : 0L);
    evidence.put("hungry", driverReason.contains(REASON_HUNGER) ? 1L : 0L);
    return new HexCrisisSignal(
        CrisisSignalId.idOf(hex, HexCrisisSignal.Kind.GOV_SERVICE_DESERTION.name()),
        hex,
        HexCrisisSignal.Kind.GOV_SERVICE_DESERTION,
        (int) Math.min(Integer.MAX_VALUE, fledCount),
        day,
        evidence,
        List.of(household),
        List.of(),
        "官吏户逃亡：" + driverReason + "（户=" + household.value() + "，走 " + fledCount + " 人；不自动补俸/招人）");
  }

  /** 全部 GOV 单位（按 id 升序；无 GOV ⇒ 空表）。 */
  private static List<Unit> sortedGovUnits(UnitState units) {
    List<Unit> sorted = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation) {
        sorted.add(unit);
      }
    }
    sorted.sort(Comparator.comparing(unit -> unit.id().value()));
    return sorted;
  }
}
