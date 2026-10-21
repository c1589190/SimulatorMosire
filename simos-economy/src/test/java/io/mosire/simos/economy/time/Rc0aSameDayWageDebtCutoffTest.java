package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>RC0a：同日工资债 cutoff 只按合同 id 排除</b>。
 *
 * <p>判据：本 tick S6 新建的 WAGE_ARREARS 合同 id 在 S7-M2 被跳过；旧工资债（不在集合里）照常处理；不得按 source=WAGE_ARREARS 一刀切。
 */
class Rc0aSameDayWageDebtCutoffTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final MarketZoneId MARKET = new MarketZoneId("hex:0_0");
  private static final HouseholdId DEBTOR = HouseholdId.parse("hh-rc0a-debtor");
  private static final HouseholdId CREDITOR = HouseholdId.parse("hh-rc0a-creditor");
  private static final ClassPositionId LANDED_POSITION =
      DefaultProductionModes.positionId(
              DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_OPERATOR)
          .orElseThrow();

  @Test
  void sameDayWageDebtIsSkippedWhileOldWageDebtAndOrdinaryDebtAreProcessed() {
    DebtContract ordinary =
        debt(DEBTOR, CREDITOR, 100L, DebtTerms.legacyDefault(), DebtSource.ORDINARY_LOAN);
    DebtContract sameDayWage =
        debt(DEBTOR, CREDITOR, 100L, DebtTerms.wageArrearsDefault(), DebtSource.WAGE_ARREARS);
    DebtContract oldWage =
        debt(DEBTOR, CREDITOR, 100L, DebtTerms.wageArrearsDefault(), DebtSource.WAGE_ARREARS, 2L);
    // 同 debtor/creditor/unit 的 wage 债同 terms 会生成同 id ⇒ 用不同 openedDay 不改变 id；这里用不同本金也仍是同 id。
    // 为避免两个工资债同 id，oldWage 改用另一债权人。
    HouseholdId oldWageCreditor = HouseholdId.parse("hh-rc0a-old-wage-creditor");
    oldWage =
        debt(
            DEBTOR,
            oldWageCreditor,
            100L,
            DebtTerms.wageArrearsDefault(),
            DebtSource.WAGE_ARREARS,
            2L);
    World world = World.of(ordinary, sameDayWage, oldWage, oldWageCreditor);
    world.ctx.sameDayWageDebtIds = Set.of(sameDayWage.id());

    world.settle();

    assertThat(world.debt(sameDayWage.id()).principal())
        .as("★ 同日新建工资债不得在本 tick S7-M2 偿还")
        .isEqualTo(100L);
    assertThat(world.debt(ordinary.id()).principal()).as("普通债照常处理").isZero();
    assertThat(world.debt(oldWage.id()).principal()).as("旧工资债照常处理（不按 source 一刀切）").isZero();
  }

  @Test
  void withoutCutoffTheSameWageDebtWouldBePaid() {
    HouseholdId oldWageCreditor = HouseholdId.parse("hh-rc0a-old-wage-creditor");
    DebtContract ordinary =
        debt(DEBTOR, CREDITOR, 100L, DebtTerms.legacyDefault(), DebtSource.ORDINARY_LOAN);
    DebtContract wage =
        debt(DEBTOR, CREDITOR, 100L, DebtTerms.wageArrearsDefault(), DebtSource.WAGE_ARREARS);
    DebtContract oldWage =
        debt(
            DEBTOR,
            oldWageCreditor,
            100L,
            DebtTerms.wageArrearsDefault(),
            DebtSource.WAGE_ARREARS,
            2L);
    World world = World.of(ordinary, wage, oldWage, oldWageCreditor);
    world.ctx.sameDayWageDebtIds = Set.of();

    world.settle();

    assertThat(world.debt(wage.id()).principal()).as("负向：不加入 cutoff 集合时同一条工资债必须被正常处理").isZero();
  }

  private static final class World {
    final SettlementDayContext ctx;

    World(SettlementDayContext ctx) {
      this.ctx = ctx;
    }

    static World of(DebtContract... contracts) {
      HouseholdId oldWageCreditor = HouseholdId.parse("hh-rc0a-old-wage-creditor");
      return of(contracts, oldWageCreditor);
    }

    static World of(DebtContract[] contracts, HouseholdId oldWageCreditor) {
      return build(contracts, oldWageCreditor);
    }

    static World of(
        DebtContract first, DebtContract second, DebtContract third, HouseholdId oldWageCreditor) {
      return build(new DebtContract[] {first, second, third}, oldWageCreditor);
    }

    void settle() {
      PureInventoryWealthBook.Snapshot wealth =
          PureInventoryWealthBook.snapshot(
              5L,
              ctx.householdEconomies,
              ctx.householdGoods,
              ctx.householdMoney,
              ctx.session.sheet().landHoldingsOrBase(),
              ctx.markets,
              ctx.topology,
              CurrencyValuation.none());
      DebtFirstSettlement.settleAtMarketOpen(ctx, wealth, CurrencyValuation.none());
    }

    DebtContract debt(DebtContractId id) {
      return ctx.debts.get(id);
    }
  }

  private static World build(DebtContract[] contracts, HouseholdId oldWageCreditor) {
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(DEBTOR, row(DEBTOR));
    rows.put(CREDITOR, row(CREDITOR));
    rows.put(oldWageCreditor, row(oldWageCreditor));
    Map<HouseholdId, HouseholdClassMembership> memberships = new LinkedHashMap<>();
    memberships.put(DEBTOR, membership(DEBTOR));
    memberships.put(CREDITOR, membership(CREDITOR));
    memberships.put(oldWageCreditor, membership(oldWageCreditor));
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    for (DebtContract contract : contracts) {
      debts.put(contract.id(), contract);
    }
    Map<LandHoldingKey, LandHolding> land =
        Map.of(new LandHoldingKey(HEX, DEBTOR), new LandHolding(DEBTOR, HEX, 1_000L));
    MarketTable market =
        new MarketTable(SILVER, Set.of(HEX), Map.of(), OptionalLong.of(1_000L), 20L, 50L);
    EconomyData base =
        EconomyData.empty()
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(rows)
            .withClassMemberships(memberships)
            .withDebtContracts(debts)
            .withMarketTables(Map.of(MARKET, market));
    EconomySession session =
        new EconomySession(
            base,
            ProductionData.empty(),
            new ProductionRuntimeEngine(ProductionData.empty()),
            EconomyFixtures.landLedger(land));
    AccountSession accounts = AccountSession.empty();
    for (HouseholdId household : rows.keySet()) {
      accounts.registerHousehold(household, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    }
    SettlementDayContext ctx =
        new SettlementDayContext(
            session,
            5L,
            accounts,
            MarketTopology.singleHex(base.marketTables(), base.marketZones()),
            false,
            new ProductionLedger.Accumulator(5L),
            EconomyParallelism.singleThreaded(),
            null,
            Map.of(),
            Set.of(),
            null,
            null,
            null,
            null);
    ctx.dayView = base;
    ctx.householdEconomies = new LinkedHashMap<>(rows);
    ctx.debts = new LinkedHashMap<>(debts);
    ctx.markets = new LinkedHashMap<>(base.marketTables());
    ctx.householdGoods = new LinkedHashMap<>();
    ctx.householdMoney = new LinkedHashMap<>();
    ctx.householdFrozenGoods = new LinkedHashMap<>();
    ctx.householdFrozenMoney = new LinkedHashMap<>();
    ctx.householdOfActor = new LinkedHashMap<>();
    ctx.repaidToday = new LinkedHashMap<>();
    ctx.repaidMoneyToday = new LinkedHashMap<>();
    ctx.repaidPrincipalByDebt = new LinkedHashMap<>();
    ctx.capitalizedArrearsToday = new LinkedHashMap<>();
    ctx.deficitToday = new LinkedHashMap<>();
    ctx.interestToday = new LinkedHashMap<>();
    ctx.sameDayWageDebtIds = Set.of();
    return new World(ctx);
  }

  private static DebtContract debt(
      HouseholdId debtor,
      HouseholdId creditor,
      long principal,
      DebtTerms terms,
      DebtSource source) {
    return debt(debtor, creditor, principal, terms, source, 1L);
  }

  private static DebtContract debt(
      HouseholdId debtor,
      HouseholdId creditor,
      long principal,
      DebtTerms terms,
      DebtSource source,
      long openedDay) {
    return new DebtContract(
        DebtContractId.idOf(debtor, creditor, new DebtUnit.Money(SILVER), terms),
        debtor,
        creditor,
        new DebtUnit.Money(SILVER),
        terms,
        source,
        principal,
        openedDay,
        OptionalLong.empty(),
        OptionalLong.empty(),
        DebtStatus.NORMAL);
  }

  private static HouseholdEconomy row(HouseholdId household) {
    return new HouseholdEconomy(
        household,
        new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.LANDED_PEASANT),
        10L,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static HouseholdClassMembership membership(HouseholdId id) {
    return new HouseholdClassMembership(
        id, LANDED_POSITION, LANDED_POSITION, Map.of(), 0L, 0L, "rc0a-test");
  }
}
