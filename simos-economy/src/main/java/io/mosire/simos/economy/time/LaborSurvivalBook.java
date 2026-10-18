package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class LaborSurvivalBook {
  private LaborSurvivalBook() {}

  // ── D-032 W4：Social 下一 tick 生存投影 → W2b evaluator ─────────────────────────────────────

  /**
   * ★★★ <b>只组装输入，不重写公式</b>：Social 的下一 tick 需求/劳动预算由 app 注入；当前库存/货币来自 {@link AccountSession}
   * 权威快照（扣冻结 + 第 37 组件 ACTIVE 预留）；当地可达价来自本户市场表，可达供给来自市场轮统计簿。 可行替代只由已算出的 production offer 派生，并用 W2b
   * 的 {@link NextTickIncomeProjection} 把下一 tick 工资从周期口径拆出来。
   */
  static SurvivalOutlook survivalOutlookFor(
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      long day,
      List<LaborQueueBook.Offer> offers,
      EconomySession session,
      AccountSession accounts,
      MarketTable market,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industryTemplates,
      Map<ProductionUnitId, ProductionRoster> productionRosters,
      SurvivalSocialFacts survivalFacts) {
    NextTickSubsistenceNeed need = survivalFacts.requireNeed(household);
    if (need.forTick() != survivalFacts.nextTick()) {
      throw new SurvivalOutlookException(
          "survival-need-stale",
          "需求 tick="
              + need.forTick()
              + " 与投影 nextTick="
              + survivalFacts.nextTick()
              + " 不一致: "
              + household.value());
    }
    long laborBudgetMilli = survivalFacts.requireLaborBudgetMilli(household);
    SurvivalResourceFacts resources = currentSurvivalResources(household, day, session, accounts);
    LocalReachabilityFacts localFacts =
        localReachabilityFacts(householdEconomy, market, need, session);
    ExistingClaims claims = existingSurvivalClaims(household, session);
    List<LivelihoodAlternative> alternatives =
        survivalAlternatives(
            household,
            laborBudgetMilli,
            offers,
            units,
            industryTemplates,
            productionRosters,
            session,
            accounts,
            market,
            day,
            survivalFacts.nextTick());
    return SurvivalOutlookEvaluator.standard()
        .evaluate(
            new SurvivalOutlookRequest(
                household,
                need,
                resources,
                laborBudgetMilli,
                alternatives,
                localFacts,
                claims,
                SurvivalOutlookEvaluator.DEFAULT_MAX_COMBINATIONS));
  }

  /** 当前可用库存/可花货币：账户余额 − 冻结 − 第 37 组件该户当前 tick ACTIVE 预留。 */
  static SurvivalResourceFacts currentSurvivalResources(
      HouseholdId household, long day, EconomySession session, AccountSession accounts) {
    if (accounts == null) {
      throw new SurvivalOutlookException(
          "survival-input-invalid", "真实生存投影缺账户会话（不得把余额当 0）: " + household.value());
    }
    AccountSnapshot snapshot = accounts.snapshot();
    AccountPartitionKey key = snapshot.householdKey(household);
    if (key == null) {
      throw new SurvivalOutlookException(
          "survival-input-invalid", "账户快照没有该家户的账（不得把余额当 0）: " + household.value());
    }
    AccountSnapshot.SnapshotAccount account = snapshot.requireAccount(key);
    Map<CommodityId, Long> reserved = new LinkedHashMap<>();
    for (InventoryReservation row : session.sheet().inventoryReservationsOrBase().values()) {
      if (row.payer().equals(HouseholdActors.of(household)) && row.activeAt(day)) {
        reserved.merge(row.commodity(), row.quantity(), Math::addExact);
      }
    }
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    List<CommodityId> commodities = new ArrayList<>(account.goods().keySet());
    commodities.sort(Comparator.comparing(CommodityId::value));
    for (CommodityId commodity : commodities) {
      long available =
          Math.subtractExact(
              Math.subtractExact(
                  account.goods().getOrDefault(commodity, 0L),
                  account.frozenGoods().getOrDefault(commodity, 0L)),
              reserved.getOrDefault(commodity, 0L));
      if (available < 0L) {
        throw new SurvivalOutlookException(
            "survival-input-invalid",
            "可用库存为负（冻结/预留超过余额）: household="
                + household.value()
                + " commodity="
                + commodity.value()
                + " available="
                + available);
      }
      if (available > 0L) {
        goods.put(commodity, available);
      }
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    List<CurrencyId> currencies = new ArrayList<>(account.money().keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      long available =
          Math.subtractExact(
              account.money().getOrDefault(currency, 0L),
              account.frozenMoney().getOrDefault(currency, 0L));
      if (available < 0L) {
        throw new SurvivalOutlookException(
            "survival-input-invalid",
            "可花货币为负（冻结超过余额）: household="
                + household.value()
                + " currency="
                + currency.value()
                + " available="
                + available);
      }
      if (available > 0L) {
        money.put(currency, available);
      }
    }
    return new SurvivalResourceFacts(goods, money);
  }

  /** 逐需求商品的当地购买价/币种/最近一轮可达供给；缺价/缺供给 ⇒ 该商品不走货币腿（不是 0 价/1:1）。 */
  static LocalReachabilityFacts localReachabilityFacts(
      HouseholdEconomy householdEconomy,
      MarketTable market,
      NextTickSubsistenceNeed need,
      EconomySession session) {
    LinkedHashSet<CommodityId> commodities = new LinkedHashSet<>();
    for (SubsistenceNeedLine line : need.lines()) {
      if (line.requiredQuantityMilli() > 0L) {
        commodities.add(line.commodity());
      }
    }
    Map<CommodityId, LocalCommodityReachability> byCommodity = new LinkedHashMap<>();
    if (market == null || commodities.isEmpty()) {
      return new LocalReachabilityFacts(byCommodity);
    }
    MarketZoneId zoneId = null;
    try {
      zoneId =
          MarketZoneBook.zoneOfHex(
                  new WorkingDayView(session.sheet()), householdEconomy.view().hex())
              .map(zone -> zone.zoneId())
              .orElse(null);
    } catch (RuntimeException ignored) {
      // 位置/区表坏形由市场阶段自己 fail-closed；这里只表示"不能确定可达市场"，不伪造价格。
    }
    MarketRoundStats stats =
        zoneId == null ? null : session.sheet().marketRoundStatsOrBase().get(zoneId);
    List<CommodityId> ordered = new ArrayList<>(commodities);
    ordered.sort(Comparator.comparing(CommodityId::value));
    for (CommodityId commodity : ordered) {
      long price = market.askPriceOf(commodity);
      long supply =
          stats == null
              ? 0L
              : stats.lastRound().getOrDefault(commodity, CommodityRound.ZERO).supplyMilli();
      boolean reachable = market.hasPrice(commodity) && price > 0L && supply > 0L;
      byCommodity.put(
          commodity, new LocalCommodityReachability(market.numeraire(), reachable, price, supply));
    }
    return new LocalReachabilityFacts(byCommodity);
  }

  /** 既有货币债务只读事实；不是收入、也不是自动豁免。 */
  static ExistingClaims existingSurvivalClaims(HouseholdId household, EconomySession session) {
    List<DebtClaimFact> debts = new ArrayList<>();
    for (DebtContract debt : session.sheet().debtContracts().values()) {
      if (!debt.debtor().equals(household) || !(debt.unit() instanceof DebtUnit.Money money)) {
        continue;
      }
      long dueTick =
          debt.terms().dueDay().isPresent()
              ? debt.terms().dueDay().getAsLong()
              : debt.dueCycle().orElse(0L);
      debts.add(
          new DebtClaimFact(debt.id(), money.currency(), debt.principal(), Math.max(0L, dueTick)));
    }
    debts.sort(Comparator.comparing(value -> value.debt().value()));
    return new ExistingClaims(debts, Set.of());
  }

  /**
   * 可行替代只从“本户候选 offer”派生：工资腿用 {@link NextTickIncomeProjection}（工资只计下一 tick 可交付量）； 产品份额在 {@code
   * cycleEnd > nextTick} 时本 tick 为 0（W2b §2.5.1.0），不伪造未来收入。
   */
  static List<LivelihoodAlternative> survivalAlternatives(
      HouseholdId household,
      long laborBudgetMilli,
      List<LaborQueueBook.Offer> offers,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industryTemplates,
      Map<ProductionUnitId, ProductionRoster> productionRosters,
      EconomySession session,
      AccountSession accounts,
      MarketTable market,
      long day,
      long nextTick) {
    List<LivelihoodAlternative> alternatives = new ArrayList<>();
    for (LaborQueueBook.Offer offer : offers) {
      if (!offer.queueable() || offer.maxAbsorbableLaborMilli() <= 0L) {
        continue;
      }
      ProductionProcess unit = units.get(offer.unitId());
      if (unit == null) {
        continue;
      }
      IndustryDefinitionView plan = industryTemplates.get(unit.industry());
      if (plan == null) {
        continue;
      }
      io.mosire.simos.production.contract.ProductionRoleDefinition roleDefinition =
          plan.roles().stream()
              .filter(role -> role.id().equals(offer.role()))
              .findFirst()
              .orElse(null);
      if (roleDefinition == null) {
        continue;
      }
      long[] cycle = productionCycleOf(unit, plan, nextTick);
      if (roleDefinition.payment()
          instanceof io.mosire.simos.production.contract.RolePayment.MoneyWage) {
        // ★ P4c3：工资报价唯一来自 U20 锚；目标只走标准劳动/周期天数的工程默认。
        io.mosire.simos.economy.EconomyDayView wageView = new WorkingDayView(session.sheet());
        Optional<WageAnchorKey> anchorKey =
            WageQuoteBridge.wageAnchorKeyOf(wageView, unit, offer.role());
        ProductionWageAnchor anchor =
            anchorKey.map(session.sheet().productionWageAnchorsOrBase()::get).orElse(null);
        long targetLaborMilliPerDay =
            WageQuoteBridge.wageTargetLaborMilliPerDay(
                0L, roleDefinition, plan.cycleDays());
        if (anchor == null || targetLaborMilliPerDay <= 0L) {
          continue; // 匹配阶段会按 wage-quote-missing/wage-target-missing 具名 skip；不在这里伪造收入。
        }
        Optional<ProductionWageQuote> quote =
            Optional.of(
                new ProductionWageQuote(anchor.currency(), anchor.wagePerLaborMilli()));
        long expectedLaborMilli = Math.min(laborBudgetMilli, offer.maxAbsorbableLaborMilli());
        if (expectedLaborMilli <= 0L) {
          continue;
        }
        NextTickIncomeProjectionResult projected =
            NextTickIncomeProjection.standard()
                .project(
                    new NextTickIncomeProjectionRequest(
                        household,
                        nextTick,
                        cycle[0],
                        cycle[1],
                        Map.of(offer.role(), expectedLaborMilli),
                        quote,
                        java.util.OptionalLong.of(targetLaborMilliPerDay),
                        Map.of()));
        alternatives.add(
            new LivelihoodAlternative(
                new LivelihoodAlternativeId(
                    "wage:" + unit.id().value() + ":" + offer.role().value()),
                expectedLaborMilli,
                Set.of(),
                projected.incomeAvailableAtTick(),
                projected.expectedMoneyIncomeMilli(),
                projected.expectedInKindMilli(),
                projected.expectedProductShareMilli()));
        continue;
      }
      if (roleDefinition.payment()
          instanceof io.mosire.simos.production.contract.RolePayment.ProductShare) {
        // ★ 产品份额只在周期关账后的下一 tick 可交付；周期未结束 ⇒ 本 tick 明确 0（不伪造未来收入）。
        if (cycle[1] > nextTick) {
          continue;
        }
        ProductionRoster roster = productionRosters.get(unit.id());
        if (roster == null) {
          throw new SurvivalOutlookException(
              "survival-income-projection-missing",
              "产品份额替代缺名册，无法取 D2 cycle prospect: unit=" + unit.id().value());
        }
        Map<CommodityId, Long> productShare =
            projectedProductShare(
                household, unit, plan, roster, session, accounts, market, day, nextTick);
        if (productShare.isEmpty()) {
          continue;
        }
        long plannedLaborMilli =
            roster.participant(household, offer.role()).stream()
                .mapToLong(
                    io.mosire.simos.economy.model.ProductionParticipant::plannedLaborMilliPerDay)
                .findFirst()
                .orElse(0L);
        alternatives.add(
            new LivelihoodAlternative(
                new LivelihoodAlternativeId(
                    "product-share:" + unit.id().value() + ":" + offer.role().value()),
                Math.min(laborBudgetMilli, Math.max(0L, plannedLaborMilli)),
                Set.of(new ResourceClaimKey("unit:" + unit.id().value())),
                nextTick,
                Map.of(),
                Map.of(),
                productShare));
      }
    }
    alternatives.sort(Comparator.comparing(value -> value.id().value()));
    return List.copyOf(alternatives);
  }

  /** 下一 tick 对应的生产周期窗口：cycleStart = today - progressDays（today = nextTick - 1）。 */
  static long[] productionCycleOf(
      ProductionProcess unit, IndustryDefinitionView plan, long nextTick) {
    long cycleStart;
    long cycleEnd;
    try {
      cycleStart = Math.subtractExact(nextTick - 1L, unit.progressDays());
      cycleEnd = Math.addExact(cycleStart, plan.cycleDays());
    } catch (ArithmeticException overflow) {
      throw new SurvivalOutlookException(
          "survival-income-timing-unknown", "下一 tick 生产周期区间溢出: unit=" + unit.id().value());
    }
    if (cycleStart < 0L || cycleEnd < cycleStart) {
      throw new SurvivalOutlookException(
          "survival-income-timing-unknown",
          "下一 tick 生产周期区间非法: unit="
              + unit.id().value()
              + " cycle=["
              + cycleStart
              + ","
              + cycleEnd
              + "]");
    }
    return new long[] {cycleStart, cycleEnd};
  }

  /**
   * 产品份额下一 tick 预计值：只经 W1 `ExpectedProfitBook.rosterIncomes` 的 D2 cycle prospect 读取；本类不复制角色份额/估值公式。
   */
  static Map<CommodityId, Long> projectedProductShare(
      HouseholdId household,
      ProductionProcess unit,
      IndustryDefinitionView plan,
      ProductionRoster roster,
      EconomySession session,
      AccountSession accounts,
      MarketTable market,
      long day,
      long nextTick) {
    if (accounts == null) {
      throw new SurvivalOutlookException(
          "survival-income-projection-missing", "产品份额投影缺账户会话: unit=" + unit.id().value());
    }
    io.mosire.simos.util.state.RevisionId revision =
        session
            .expectedRevision()
            .orElseThrow(
                () ->
                    new SurvivalOutlookException(
                        "survival-income-projection-missing",
                        "产品份额投影缺权威 revision: unit=" + unit.id().value()));
    Map<HouseholdId, io.mosire.simos.production.contract.ExternalAttributeFacts> attributes =
        new LinkedHashMap<>();
    for (HouseholdId participant : roster.households()) {
      session
          .participantAttributeLookup()
          .factsOf(participant)
          .ifPresent(facts -> attributes.put(participant, facts));
    }
    Set<ProductionLocation> existingLocations =
        ExpectedProfitBook.existingSettlementLocationsOf(
            new WorkingDayView(session.sheet()),
            session.sheet().units(),
            HouseholdActors.of(roster.organizerHousehold()));
    List<ExpectedProfitBook.RosterHouseholdIncome> incomes =
        ExpectedProfitBook.rosterIncomes(
            plan,
            unit,
            roster,
            session.sheet().assetShares(),
            accounts,
            session.sheet().relationsOrBase(),
            market,
            new WorkingDayView(session.sheet()),
            session.sheet().productionWageAnchorsOrBase(),
            attributes,
            session.assetOccupancyLookup(),
            revision,
            existingLocations,
            day,
            session.productionRuntime());
    for (ExpectedProfitBook.RosterHouseholdIncome income : incomes) {
      if (!income.household().equals(household)) {
        continue;
      }
      if (!income.productShareMilli().isEmpty()) {
        return income.productShareMilli();
      }
      if (income.rejection().isPresent()) {
        throw new SurvivalOutlookException(
            "survival-income-projection-missing",
            "产品份额 D2 prospect 拒绝: unit="
                + unit.id().value()
                + " household="
                + household.value()
                + " reason="
                + income.rejection().orElseThrow());
      }
      return Map.of();
    }
    throw new SurvivalOutlookException(
        "survival-income-projection-missing",
        "产品份额 D2 prospect 缺该户行: unit=" + unit.id().value() + " household=" + household.value());
  }
}
