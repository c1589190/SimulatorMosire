package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ConsumptionSettlement.reportHouseholdGaps;
import static io.mosire.simos.economy.time.DebtSettlement.chargeInterest;
import static io.mosire.simos.economy.time.DebtSettlement.debtCapacitiesForDay;
import static io.mosire.simos.economy.time.EconomySettlement.GRAIN;
import static io.mosire.simos.economy.time.LaborSettlement.requireGovServiceCommitmentsWithinBudgets;
import static io.mosire.simos.economy.time.PopulationWritebackSettlement.withCycleNaturalNeed;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.settleOperatorExits;
import static io.mosire.simos.economy.time.SettlementLogSupport.RAW;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.logReason;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceMoneyByCurrency;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceTotalGoods;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceTotalLongs;
import static io.mosire.simos.economy.time.SettlementMath.mergeGoods;
import static io.mosire.simos.economy.time.SettlementMath.mergeMoney;
import static io.mosire.simos.economy.time.SettlementMath.mergeNamedQuantities;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.EconomySettlement.ClosedUnit;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/** 日结算阶段执行器（包内瞬态；只由 EconomyDayPipeline 按固定顺序调用）。 */
final class SettlementDayClosePipeline {

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void reportGapsAndInterest(SettlementDayContext ctx) {
    // ── 4d. 报缺口（★★ W3 2026-10-11：原"饿死判据 + 劳动缩放"整段已删 —— 经济侧**不判死、不缩劳动**）──────
    //   ★ 位置不变（★ H5：**所有救济通道之后** —— 市场（4）→ 借（4b）→ 还（4c）之后）：这里算出的缺口就是
    //     "救济全走完还差多少"，与旧判据同一个时点、同一个当日分子（`unmetToday[grain]`）。旧判据另外把
    //     本周期累计（`carried`）折进"饿满整周期"的比例里 —— 那条**周期口径随判死一起走了**：缺口现在按**当日**
    //     报（分子=当日缺口、分母=当日粮需求，同窗口），由 Social 自己决定怎么折成死亡率。
    //   ★ 报出去的形状见 {@link EconomyHouseholdGap}：① 生产花费未满足量（逐商品，取 W2 的市场读口，**不重算**）
    //     ② 口粮缺口那一支（当日）③ 家户稳定身份 + 当日人口。
    //   ★ 它是瞬态 outbox（不进 EconomyData/变更集/Codec），app 组合根在 step(day) 之后取走（S-11 第②半）。
    reportHouseholdGaps(
        ctx.session,
        ctx.householdEconomies,
        ctx.flows,
        ctx.unmetToday,
        ctx.newCycleHouseholds,
        ctx.ledger,
        ctx.day);
    // ★★ Z3a/C7：政府承诺整额保留后若越预算，这里统一具名 ERROR + fail-closed（有 day 上下文 ⇒ 来源 TICK、
    //   事件带 day；与 Z1b 队列口径同事件名）。★ W3 起它不再兼"死亡缩放的收口"——那条缩放已删（见上）。
    requireGovServiceCommitmentsWithinBudgets(
        ctx.laborCommitments, ctx.householdEconomies, EconomyLogSource.ECONOMY_POPULATION, ctx.day);

    // ── 5. 周期末计息（§7.1③ / §四 周期结算第 6 步）────────────────────────────────────
    //   ★ **一天只计一次**：结算日循环里可能有多个产业在同一天关账（各产业 cycleDays 可以不同）⇒ 计息挂在
    //     "今天有产业关账"这个**日级**事实上，不在那个逐产业的 for 里（否则同格的 farm + craft 会各计一遍）。
    //   ★ 次序：**偿还先于计息**（还掉的那部分本金不再生息 —— 这是"先还后计"的标准序，也是改后债务曲线下降的一半原因）。
    if (ctx.anyCycleClosed) {
      chargeInterest(
          ctx.debts, ctx.principalAtDayStart, ctx.interestToday, ctx.householdEconomies, ctx.day);
      long interestTotal = traceTotalLongs(ctx.interestToday);
      if (interestTotal > 0L) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "INTEREST",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    ctx.day,
                    "households",
                    ctx.interestToday.size(),
                    "interestDueTotalMilli",
                    interestTotal,
                    "debtContracts",
                    ctx.debts.size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "INTEREST_DETAIL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    ctx.day,
                    "households",
                    ctx.interestToday.size(),
                    "interestDueTotalMilli",
                    interestTotal));
      }
    }
  }

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void settleOperatorState(SettlementDayContext ctx) {
    // ── 5b. S3 经营者状态机（可观察量 → IndustryStatus；退出处置先偿债、不足才 defaulted）──────────
    //   ★ 只在关账日前进，而且只更新**本日关账**的产业：每个产业的连续计数以它自己的关账周期为单位，
    //     未关账产业的周期证据不能被别人关账时顺手消费掉。
    //   ★ 市场证据取的是**本周期累计**（每轮市场结束后累加到 OperatorCondition.cycle*），不再要求 evidence.day == day；
    //     若本周期一个市场轮都没有（cycleMarketRounds == 0），连续计数保持、不用 0 覆盖。
    //   ★ 缩产/停业只改"计划规模系数"（StressPolicy），capacity 与 OwnershipStake 一字不动。
    ctx.closingUnits = new LinkedHashSet<>();
    if (ctx.anyCycleClosed) {
      Map<ProductionUnitId, Boolean> shortfallByUnit = new LinkedHashMap<>();
      for (ClosedUnit closing : ctx.closed) {
        ctx.closingUnits.add(closing.unit());
        shortfallByUnit.put(closing.unit(), closing.inputShortfall());
      }
      SettlementIndex stateIndex = ctx.settlementIndex.withDebtContracts(ctx.debts);
      List<OperatorSettlement.Exit> exits =
          OperatorSettlement.advance(
              ctx.operatorConditions,
              ctx.units,
              ctx.industries,
              ctx.relations,
              ctx.householdEconomies,
              stateIndex,
              ctx.householdGoods,
              ctx.householdMoney,
              MarketIndex.of(ctx.dayView),
              ctx.closingUnits,
              shortfallByUnit);
      if (!exits.isEmpty()) {
        // ★★ E1：退出事实处置（劳动释放 → 资产退回/留 owner → 债务偿还/违约 → 状态与理由）。
        settleOperatorExits(
            exits,
            ctx.operatorConditions,
            ctx.session.sheet().productionOrganizations(),
            ctx.assetShares,
            ctx.laborCommitments,
            ctx.debts,
            ctx.householdGoods,
            ctx.householdMoney,
            ctx.householdOfActor,
            stateIndex,
            ctx.ledger,
            ctx.day,
            ctx.issuanceJournal);
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "OPERATOR_EXITS",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    ctx.day,
                    "exits",
                    exits.size(),
                    "households",
                    exits.stream().map(OperatorSettlement.Exit::household).distinct().count()));
        if (RAW.isTraceEnabled()) {
          for (OperatorSettlement.Exit exit : exits) {
            EventLog.channel(RAW)
                .trace(
                    LogEvent.of(
                        "OPERATOR_EXIT",
                        EconomyLogSource.ECONOMY_ORGANIZATION,
                        "day",
                        ctx.day,
                        "unit",
                        exit.unit().value(),
                        "industry",
                        exit.industry().value(),
                        "operator",
                        exit.operator().id(),
                        "household",
                        exit.household().value(),
                        "reason",
                        logReason(exit.reason())));
          }
        }
        if (TRACE.isDebugEnabled()) {
          EventLog.channel(TRACE)
              .debug(
                  LogEvent.of(
                      "OPERATOR_EXITS_DETAIL",
                      EconomyLogSource.ECONOMY_ORGANIZATION,
                      "day",
                      ctx.day,
                      "exits",
                      exits.size()));
        }
        // ★ 资产 operator / 劳动配额刚被改写 ⇒ 换一份索引，后面的阶层写回（读 unit 可用资产）不拿旧快照。
        //   这是**退出日的一次重建**（O(unit + 份额 + 配额)），不是逐查询重扫；退出本身是低频事件。
        ctx.settlementIndex =
            SettlementIndex.build(
                ctx.units,
                ctx.industries,
                ctx.assetShares,
                ctx.laborCommitments,
                ctx.householdEconomies,
                ctx.debts,
                ctx.relations,
                ctx.session.sheet().productionOrganizations(),
                ctx.session.sheet().assetOccupanciesOrBase(),
                ctx.session.sheet().productionRostersOrBase(),
                ctx.day,
                ctx.landAcquiredThisRound(),
                ctx.session.sheet().landArableSupply(),
                ctx.session.sheet().landHoldingsOrBase(),
                ctx.landAcquiredQuantityThisRound());
      }
    }

    // ── 5b.5. ★★ E5b（P2d 收窄）：债务压力计数 + hex 危机信号（关账日、退出处置之后、阶层写回之前）──
    //   ★ 位置理由：债务结算（4c 偿还 / 5 计息 / 5b 退出处置）已经结束。
    //   ★ 本阶段不再自动处置资产；只在关账日推进，新表全空 ⇒ 整段 no-op。
    //   ★ F/headroom 用 E4b/D-030 的唯一算法（DebtCapacityBook）；容量按**当刻债务终态**重算。
    // ★ B2-保留段首base：E5b 的**接线闸门**（六张表任一非空即启用），不是逐日读数：段内它只可能因
    //   "全空世界中途长出归属行"而翻转，而那种世界在两条推进路径下都同样不启用 E5b。
    //   ★ B1 账本（2026-10-10 fix-advance-path-dependence §3 末行）已裁定本处**不动**，留待接线面统一裁决。
    if (ctx.anyCycleClosed
        && EconomyLiquidationSettlement.isActive(ctx.base, ctx.dayView.definitions())) {
      Map<HouseholdId, DebtCapacity> closeDebtCapacities =
          debtCapacitiesForDay(
              ctx.householdEconomies,
              ctx.flows,
              ctx.newCycleHouseholds,
              ctx.income,
              ctx.consumedGoods,
              ctx.householdGoods,
              ctx.cycleDaysByHousehold,
              ctx.debts,
              ctx.units,
              ctx.industries,
              ctx.assetShares,
              ctx.operatorConditions,
              ctx.settlementIndex,
              ctx.debtUnitValueLookup);
      EconomyLiquidationSettlement.settle(
          ctx.session,
          // ★★ B5（裁定十八）：生产方式定义的唯一权威在 production ⇒ 显式传当刻定义来源。
          ctx.dayView.definitions(),
          ctx.day,
          ctx.currentCycle,
          ctx.householdEconomies,
          ctx.debts,
          ctx.principalAtDayStart,
          ctx.repaidPrincipalByDebt,
          closeDebtCapacities,
          ctx.ledger);
      int liquidationAudits = ctx.ledger.toLedger().liquidationAudits().size();
      if (liquidationAudits > 0) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "LIQUIDATION",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    ctx.day,
                    "audits",
                    liquidationAudits));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "LIQUIDATION_DETAIL",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    ctx.day,
                    "audits",
                    liquidationAudits));
      }
    }

    // ── 5c. S3 阶层写回（关账日、经营者状态机之后）────────────────────────────────────────
    //   ★ 只改 HouseholdEconomy.view：稳定 HouseholdId、actor/账户/债务/劳动配额/人口/成员份额一字不动（withView 的方法承诺）。
    //   ★ E5b：有 HouseholdClassMembership 的家户以 standing 为权威 —— 把 currentPositionId 投影回 view.stratum；
    //     投影不到的非 legacy 位置保留旧 view 并写具名审计，**不**把该户再交给 HouseholdClassRule（禁止双真相）。
    //   ★ 没有 standing 的旧档才继续走 HouseholdClassRule（投影不到 = 有显式归属但表达不了，不是"退回旧分类器"）。
    //   ★ 分类用本日 ledger（关账日 = 本周期收获/分配的结算账本，租金实付是整周期口径）；读不到时不填 0。
    //   ★ 原四档允许跳变；这里不新增人口、不改任何守恒量。
    if (ctx.anyCycleClosed) {
      ProductionLedger classLedger = ctx.ledger.toLedger();
      Map<HouseholdId, HouseholdClassMembership> classMemberships =
          ctx.session.sheet().classMembershipsOrBase();
      HouseholdClassRule.Index classIndex =
          HouseholdClassRule.Index.of(
              ctx.assetShares,
              ctx.laborCommitments,
              ctx.units,
              ctx.industries,
              ctx.relations,
              ctx.householdEconomies,
              ctx.debts,
              ctx.settlementIndex);
      List<ClassTransition> classTransitions = new ArrayList<>();
      for (HouseholdId key : new ArrayList<>(ctx.householdEconomies.keySet())) {
        HouseholdEconomy householdEconomy = ctx.householdEconomies.get(key);
        if (householdEconomy == null) {
          continue;
        }
        HouseholdClassMembership classMembership = classMemberships.get(key);
        SocialClassId derived;
        String reason;
        if (classMembership != null) {
          Optional<SocialClassId> projected =
              LegacyClassStructure.socialClassOf(classMembership.currentPositionId());
          if (projected.isEmpty()) {
            EconomyLiquidationSettlement.recordClassProjectionFallback(
                ctx.ledger,
                ctx.day,
                key,
                classMembership.currentPositionId(),
                householdEconomy.view().stratum());
            continue; // ★ 保留旧 view，不改旧权威也不另造一个投影
          }
          derived = projected.get();
          reason = "classStanding-authority:" + classMembership.currentPositionId().value();
        } else {
          HouseholdClassRule.Classification classification =
              classIndex.classify(key, Optional.of(classLedger));
          derived = classification.stratum();
          reason = classification.reason();
        }
        if (derived.equals(householdEconomy.view().stratum())) {
          continue;
        }
        ClassTransition transition =
            new ClassTransition(ctx.day, key, householdEconomy.view().stratum(), derived, reason);
        ctx.householdEconomies.put(
            key,
            householdEconomy.withView(
                new CohortKey(
                    householdEconomy.view().hex(), householdEconomy.view().residence(), derived)));
        classTransitions.add(transition);
      }
      // ★ 具名审计读数：即使没有写回也投递空表（读口才分得清"这次关账没有变化"与"没读到"）。
      ClassTransitionFeed.publish(ctx.meta.mapId(), ctx.day, classTransitions);
      if (!classTransitions.isEmpty()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "CLASS_TRANSITIONS",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    ctx.day,
                    "count",
                    classTransitions.size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "CLASS_TRANSITIONS_DETAIL",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    ctx.day,
                    "count",
                    classTransitions.size()));
      }
    }
  }

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void writeFlowsAndFinish(SettlementDayContext ctx) {
    // ── 流水：每行一条（本期发生额；税 v1 恒 0、利息见上一步）────────────────────────────
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        ctx.householdEconomies.entrySet()) {
      HouseholdId key = householdEconomyEntry.getKey();
      // ★★ **M2.7（丙条仪器）：周期累加器的清零点与流水同一天** —— 新周期第一天把
      //   {@code cycleNaturalNeedMilli} 重置为"今天这一份"（消费步刚按注入的 {@code naturalNeeds[grain]} 累加过）。
      //   ★ 放在这里而不是消费步里：只有这里同时看得见 {@code newCycleHouseholds}
      //     （由产业 progressDays 推、且与 FlowRow 的清零同一判据）。重置为"当天那一份"而不是 0 —— 理由见
      //     {@link #withCycleNaturalNeed}。
      if (ctx.newCycleHouseholds.contains(key)) {
        HouseholdEconomy cycleHouseholdEconomy = householdEconomyEntry.getValue();
        if (cycleHouseholdEconomy != null) {
          householdEconomyEntry.setValue(
              withCycleNaturalNeed(
                  cycleHouseholdEconomy,
                  cycleHouseholdEconomy.naturalNeeds().getOrDefault(GRAIN, 0L)));
        }
      }
      // ★★ **T4 起两张实物表的口径都变了**（R1 的"行侧、形状不变、口径改"）：
      //   · `consumed` = 现扣投入 + 日耗 + 同格取材的**转出**（生产损耗**不在里面**了：它只进 ledger）；
      //   · `income`   = **实物入账**（关系给本行的 cohort 入账 + 同格取材的**转入**）—— **不再是**毛产分成。
      Map<CommodityId, Long> consumed = ctx.consumedGoods.getOrDefault(key, Map.of());
      Map<CommodityId, Long> earned = ctx.income.getOrDefault(key, Map.of());
      // ★★ W5.5：`borrowed`（本日新借入粮）随借粮腿一并删除 ⇒ 本行只把上期累计原样带过（见下面 FlowRow 的构造）。
      long repaid = ctx.repaidToday.getOrDefault(key, 0L);
      long interest = ctx.interestToday.getOrDefault(key, 0L);
      // ★ netSurplus = income[grain] − 消费[grain] − 税(0) − 利息（§3.3 的口径；税要等 government 切片）。
      //   ★★ **口径 = 粮**（见 {@link FlowRow#netSurplus()}）：把两种商品折成一个数需要**价格**，而市场与价格属 R4 的 V8
      //     （本轮"不做城乡交换/市场/价格"）⇒ 硬折会编造一个本轮没有的换算率。其余商品的净额在两张表里分别读得到。
      //   ★ 利息是**并入本金**的（没支付、粮库存不动）⇒ 它不进 consumed，守恒式（§6.1）不受影响；
      //     但它照样进"本期盈余/赤字"：债务人**确实**比期初更穷了（欠得更多）。
      long netSurplus =
          earned.getOrDefault(GRAIN, 0L) - consumed.getOrDefault(GRAIN, 0L) - interest;
      Map<CommodityId, Long> dayUnmet = ctx.unmetToday.getOrDefault(key, Map.of());
      // ★★ W3（2026-10-11）：经济侧**不再**产生任何死亡（判死归 Social）⇒ 本步当日死亡恒 0。
      //   原 `deathsToday` 累加器与 `applyFamine` 一并删除（AGENTS §一.11：旧设计直接删）。
      //   ★ W3-fix（2026-10-11）：`FlowRow.deaths` 的**非 0 值来自"社会报来的死亡"**，由逐户回写路径
      //     applyHouseholdPopulationDeltasInto（四参）→ withLifecycle 在**本步之后**写进同一行流水；
      //     故下面的 `acc.deaths() + dayDeaths` 是"把本步没动的那一列原样带过"，不是"清账"
      //     （清账只发生在 newCycleHouseholds 那一支）。
      long dayDeaths = 0L;
      long dayBirths = ctx.birthsToday.getOrDefault(key, 0L);
      // ★★ E4c：货币债偿还与资本化读数的当日量（与其余字段同窗口：本周期累加、新周期第一天归零）。
      Map<CurrencyId, Long> dayRepaidMoney = ctx.repaidMoneyToday.getOrDefault(key, Map.of());
      Map<String, Long> dayCapitalized = ctx.capitalizedArrearsToday.getOrDefault(key, Map.of());
      // ★ 多日推进（§十一）：当天的流水**累加**进本期流水，不能覆盖（否则"推进 100 天"只显示最后一天）。
      //   ★★ **本期口径（§八.5）**：新周期的第一天（progressDays == 0，含创世）该行**整行从 0 重记** ——
      //      上周期末的读数在**关账那一支的 revision 里**读得到（归档），次日才归零（清零）。
      //      清零点必须落在"新周期第一天"而不是"关账那一支"：后者自己产生本周期最大的一笔所得（收获的毛产分配），
      //      在那里清零会把刚收获的那笔当场抹掉。粒度是**按家户**（H0：行就是家户），由它供给的那些产业的 progressDays
      //      决定 ⇒ 与 §十一 等价性相容。
      FlowRow acc = ctx.newCycleHouseholds.contains(key) ? null : ctx.flows.get(key);
      ctx.flows.put(
          key,
          new FlowRow(
              key,
              mergeGoods(acc == null ? null : acc.income(), earned),
              mergeGoods(acc == null ? null : acc.consumed(), consumed),
              acc == null ? 0L : acc.taxPaid(),
              (acc == null ? 0L : acc.interestDue()) + interest,
              // ★★ W5.5：借粮腿已删 ⇒ 本日新借入粮恒 0（货币腿的借入是**钱**，量纲不同，不塞进这个粮字段）。
              acc == null ? 0L : acc.newBorrowing(),
              acc == null ? 0L : acc.repaid() + repaid,
              (acc == null ? 0L : acc.netSurplus()) + netSurplus,
              mergeGoods(acc == null ? null : acc.unmetNeed(), dayUnmet),
              (acc == null ? 0L : acc.deaths()) + dayDeaths,
              (acc == null ? 0L : acc.births()) + dayBirths,
              mergeMoney(acc == null ? null : acc.repaidMoney(), dayRepaidMoney),
              mergeNamedQuantities(acc == null ? null : acc.capitalizedArrears(), dayCapitalized)));
    }
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "FLOWS",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  ctx.day,
                  "rows",
                  ctx.flows.size(),
                  "newCycleHouseholds",
                  ctx.newCycleHouseholds.size()));
    }

    ctx.lastClosed =
        ctx.anyCycleClosed ? OptionalLong.of(ctx.currentCycle) : ctx.meta.lastClosedCycle();
    ctx.nextMeta =
        new EconomyMeta(
            ctx.meta.mapId(),
            ctx.meta.activatedDay(),
            ctx.lastClosed,
            ctx.meta.rulesVersion(),
            ctx.meta.migrationSource(),
            // ★★★ W10-fix（2026-10-11）：政策表**原样带过**。缺这一行 ⇒ 本行用 5 参便捷构造器
            //   （= 空表）重建 meta，逐日结算把 GM 写进 economyPolicies 的政策**静默清空** ——
            //   政策只在"写入的那一个 tick"内成立，下一个 advance 之后就不见了（W10-收口批实测
            //   240/240 个结算日全中）。★ 本类型的"逐字段手写重建"是铁律 5 那一族的老坑：
            //   再加第 7 个组件时，这里同样要跟着加。
            ctx.meta.economyPolicies(),
            // ★★ D-032 B2c：按方案声明的"闲置份额来源主体"**原样带过**（同 W10 政策表那条教训：
            //   漏这一行 = 逐日结算把 GM 的声明静默清空）。
            ctx.meta.planAssetSources(),
            // ★★ D-032 B3（裁定十二）：按方案声明的"劳动来源"**原样带过**（同上一条教训）。
            ctx.meta.planLaborSources());
    // ★ R2：劳动供给表与配额表**原样带过结算的日常部分**（配额属命令层、供给属 social 的人口真值源）；
    //   ★ W3 起结算**不再**按死亡比例缩配额（判死归 Social）⇒ 这里交出的是**工作副本**。
    // ★★ E3：把本次结算日产生的发行审计记录落进状态表（按 id 确定性排序；map 键 = 记录 id）。
    //   ★ 只有真的发生单边发行才有记录；没有发行时连工作副本都不碰（旧路径逐值不变）。
    ctx.issuedToday = ctx.issuanceJournal.drainSorted();
    if (!ctx.issuedToday.isEmpty()) {
      LinkedHashMap<MoneyIssuanceId, MoneyIssuanceRecord> issuanceTable =
          ctx.session.sheet().moneyIssuances();
      for (MoneyIssuanceRecord record : ctx.issuedToday) {
        MoneyIssuanceRecord existing = issuanceTable.putIfAbsent(record.id(), record);
        if (existing != null && !existing.equals(record)) {
          throw new IllegalStateException("同一天同一笔转移产生了两条不同的发行记录（id 冲突）: " + record.id());
        }
      }
    }
    // ★ T2：生产关系表**原样带过**（本任务行为不变：{@code harvest} 还没读它 —— 那时 T4 的事）。
    ctx.session.sheet().meta(Optional.of(ctx.nextMeta));

    // ── ★★ P10.2 周期关账钩子：⑦真实利润汇总 → ⑧迁移计划 → ⑨迁移执行 ─────────────────────────
    //   ★ 位置：在 ⑥饥饿/死亡、5b/5b.5/5c 全部之后、下一个 step() 的"投入开扣"之前。
    //   ★ 闸门：旧档（modes 空）profitCycle 恒 null ⇒ 这一整段不执行，逐值不变。
    if (ctx.profitCycle != null) {
      ctx.profitCycle.recordDay(ctx.day, ctx.ledger.toLedger(), ctx.ledger.marketReport());
    }
    // ★★ B5（§13.15.2 裁定十八）：`modes` 读日视图（default → production 定义权威）⇒ 段内恒定；本闸门判"这个世界接没接线 E1–E6"。
    if (ctx.profitCycle != null && ctx.anyCycleClosed && !ctx.dayView.modes().isEmpty()) {
      ctx.profitCycle.recordCloseFacts(ctx.day, ctx.closedFacts);
      // ⑦a ★★ M-A1：商号周期结算（运费实收/porter 工资/upkeep/运力写回）**已整体退役** —— 它依赖的商号行
      //   （merchantFirms）与 merchant 载体都不存在了：运力改为每轮派生（MerchantCapacityPool），运费在成交当时
      //   直接付给提供运力的家户。★ 「纯商号/顺便分流 + 利润算式（含三层税）」属 **M-C**，本批不发明公式
      //   （见实现账本 D-3）；因此这里不再有任何"周期末把利润写回状态"的动作。
      // ⑦b 真实利润汇总（只读本周期真实账）。
      EnterpriseProfitBook.Book profitBook =
          EnterpriseProfitBook.collect(
              ctx.profitCycle,
              ctx.session.sheet().productionOrganizations(),
              ctx.units,
              ctx.householdEconomies,
              ctx.industries,
              MarketIndex.of(ctx.dayView),
              ctx.accounts);
      // ⑧ 迁移计划（真实利润权重 + A 规则 + 目标选择）。
      //    ★★ D-032 §15.12 B（R1）：把本会话的生产运行时一起交下去 —— 当前户读数与全部候选的
      //      产出/所需劳动/所需投入都取自 production 的试算（熟练度/GM 覆盖/解锁条件因此进入选择链）。
      ModeMigrationPolicy.MigrationPlan migrationPlan =
          ModeMigrationPolicy.plan(
              ctx.dayView,
              ctx.session.sheet().productionOrganizations(),
              ctx.units,
              ctx.householdEconomies,
              ctx.session.sheet().classMemberships(),
              ctx.assetShares,
              ctx.session.sheet().relations(),
              ctx.laborCommitments,
              ctx.markets,
              ctx.session.sheet().debtContracts(),
              ctx.accounts,
              profitBook,
              ctx.day,
              ctx.topology,
              ctx.profitCycle.marketReports(),
              ctx.session.productionRuntime(),
              ctx.settlementIndex
                  .d4AssetOccupancyBudget()
                  .orElseThrow(
                      () -> new IllegalStateException("asset-occupancy-unavailable: migration")));
      // ⑨ 迁移执行（只执行计划；源户 mode/standing/org/unit.modeKey 一字不改）。
      //    ★ D-023：把当天的瞬态 ledger 传进去记“留原户资产/关系模板回退”的具名读数（不新增持久组件）。
      if (!migrationPlan.moves().isEmpty()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "MIGRATION_PLAN",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "moves",
                    migrationPlan.moves().size(),
                    "organizations",
                    profitBook.byOrganization().size(),
                    "modeHexProfits",
                    profitBook.netByModeHex().size()));
        if (RAW.isTraceEnabled()) {
          for (ModeMigrationPolicy.MigrationMove move : migrationPlan.moves()) {
            EventLog.channel(RAW)
                .trace(
                    LogEvent.of(
                        "MIGRATION_MOVE",
                        EconomyLogSource.ECONOMY_MIGRATION,
                        "day",
                        ctx.day,
                        "source",
                        move.source().value(),
                        "target",
                        move.target().value(),
                        "targetHex",
                        move.targetHex().q() + "," + move.targetHex().r(),
                        "targetMode",
                        move.targetMode().value(),
                        "population",
                        move.population(),
                        "moneyMilli",
                        move.moneyMilli(),
                        "moneyByCurrency",
                        move.moneyByCurrency(),
                        "debtMilli",
                        move.debtMilli(),
                        "speedPerMille",
                        move.transferSpeedPerMille(),
                        // ★★★ W9-fix（U3 修复，控制方裁定 v3.32 §7 第 5 条）：**谁落到目标产业的第几档**
                        //   （0 = 贫农/兜底档 … 3 = 地主档；规划期按"带得走的作业资本"算，见
                        //   ModeMigrationPolicy.supportedTierIndex）。改前这一维**只存在于瞬态计划类型里**，
                        //   没有任何日志/落盘 ⇒ 真世界读不到"按库存定档"是否发生（W9d 只能靠单测替代）。
                        //   ★ 同族字段 speedPerMille 已在（上面一行），无需补。
                        "targetTierIndex",
                        move.targetTierIndex(),
                        "reason",
                        move.reason()));
          }
        }
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "PROFIT_MIGRATION_PLAN",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "organizations",
                    profitBook.byOrganization().size(),
                    "modeHexProfits",
                    profitBook.netByModeHex().size(),
                    "moves",
                    migrationPlan.moves().size()));
      }
      int rowsBeforeMigration = ctx.householdEconomies.size();
      ModeMigrationSettlement.apply(
          ctx.session, ctx.accounts, migrationPlan, ctx.dayView, ctx.day, ctx.ledger);
      if (!migrationPlan.moves().isEmpty()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "MIGRATION_APPLIED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "moves",
                    migrationPlan.moves().size(),
                    "rowsBefore",
                    rowsBeforeMigration,
                    "rowsAfter",
                    ctx.householdEconomies.size(),
                    "transfersToday",
                    ctx.ledger.toLedger().transfers().size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MIGRATION_APPLIED_DETAIL",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "rowsAfter",
                    ctx.householdEconomies.size(),
                    "transfersToday",
                    ctx.ledger.toLedger().transfers().size()));
      }
      ctx.profitCycle.resetForNextCycle();
    }
    // ★★★ W5-F：迁移/组织等非 D4 写者可能在本日更早的预算之后拆分份额。grant 一旦大于其 share.quantity，
    //   候选态就会被 EconomyReferenceValidation 整批拒绝；在日结算收尾处按当前份额表统一收口 grant 数量。
    //   ★ 只截掉不可用的超出量并记 asset-short:<kind>；不改变 D4 分配/释放/整池/最大余数语义，也不放宽校验。
    AssetOccupancyBook.reconcileGrantQuantitiesWithShares(
        ctx.session.sheet().assetOccupancies(), ctx.session.sheet().assetShares());
    ctx.dayEndPopulation = 0L;
    for (HouseholdEconomy householdEconomy : ctx.householdEconomies.values()) {
      ctx.dayEndPopulation += householdEconomy.population();
    }
    ctx.dayMarketReport = ctx.ledger.marketReport();
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "DAY_END",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                ctx.day,
                "population",
                ctx.dayEndPopulation,
                "deaths",
                0L, // ★ W3-fix：本字段 = **本阶段（经济日结算）自己产生的死亡数** ⇒ 结构性恒 0（经济侧不判死）。
                //   社会报来的死亡不在这里读：它们由同一次修订里 step 之后的逐户回写写进 FlowRow.deaths，
                //   并另发一条 HOUSEHOLD_VITAL_WRITEBACK（见
                // applyHouseholdPopulationDeltasInto，INFO/DEBUG）。
                //   ⇒ 别把这里的 deaths=0 读成"今天没人死"。
                "unmet",
                traceTotalGoods(ctx.unmetToday),
                "borrowedGrainMilli",
                0L, // ★ W5.5：借粮腿已删 ⇒ 本日新借入粮恒 0（货币腿的借入额见 MONEY_MARKET_ROUND / debtContracts）。
                "repaidGrainMilli",
                traceTotalLongs(ctx.repaidToday),
                "repaidMoneyByCurrency",
                traceMoneyByCurrency(ctx.repaidMoneyToday),
                "debtContracts",
                ctx.debts.size(),
                "transfers",
                ctx.ledger.toLedger().transfers().size(),
                "marketFills",
                ctx.dayMarketReport == null ? 0 : ctx.dayMarketReport.fills().size(),
                "marketUnfilled",
                ctx.dayMarketReport == null ? 0 : ctx.dayMarketReport.unfilled().size()));
    if (TRACE.isDebugEnabled()) {
      Map<TransferReason, Long> transferCounts = new TreeMap<>();
      Map<TransferReason, Long> transferGoods = new TreeMap<>();
      Map<TransferReason, Long> transferMoney = new TreeMap<>();
      for (Transfer transfer : ctx.ledger.toLedger().transfers()) {
        transferCounts.merge(transfer.reason(), 1L, Long::sum);
        long goods = 0L;
        for (long quantity : transfer.goods().values()) {
          goods += quantity;
        }
        long money = 0L;
        for (long quantity : transfer.money().values()) {
          money += quantity;
        }
        transferGoods.merge(transfer.reason(), goods, Long::sum);
        transferMoney.merge(transfer.reason(), money, Long::sum);
      }
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "SETTLEMENT_END_DETAIL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  ctx.day,
                  "rows",
                  ctx.householdEconomies.size(),
                  "units",
                  ctx.units.size(),
                  "debts",
                  ctx.debts.size(),
                  "transfers",
                  ctx.ledger.toLedger().transfers().size(),
                  "byReasonCount",
                  transferCounts,
                  "byReasonGoods",
                  transferGoods,
                  "byReasonMoney",
                  transferMoney));
    }
    // ★★ Z2（§5.2/§6.1）：当日结算成功后清空会话注入集 —— 未再注入的次日 = 1000‰ 中性。
    //   放在本方法（唯一日结算实现）末尾：直接 settleOneDayInto 的调用方也得到同一"当日消费后清空"语义；
    //   抛异常时不清空（本会话随本次推进丢弃，不落 revision）。
    // ★★ 机械修正（D-032 A 批续做）：D2 退役了整条"逐 tick 生产效率修正注入"通路 ⇒ EconomySession 不再有
    //   productionModifiers，也没有 clearProductionModifiers()。**"当日结算成功后清空注入集"这件事随通路一起
    //   消失**（没有注入集可清）—— 不是"漏了一次清空"：注入面已不存在，故不存在"未清空而残留"的路径。
  }
}
