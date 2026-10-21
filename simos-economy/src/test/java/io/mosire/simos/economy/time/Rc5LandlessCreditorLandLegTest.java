package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
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
 * ★★★ <b>D-032 C1/C7：S7-M2 债偿的 LAND 腿不再按债权人阶级跳过</b>。
 *
 * <p>判据：货币 → 库存 → LAND 的通用清偿顺序对所有债权人成立；PROLETARIAN 债权人收到 LAND 后由资产派生 就地变为
 * OWNER，不经过土地市场、不强制迁人，本金同步冲减且同一 revision 原子。
 */
class Rc5LandlessCreditorLandLegTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final MarketZoneId MARKET = new MarketZoneId("hex:0_0");
  private static final HouseholdId DEBTOR = HouseholdId.parse("hh-rc5-debtor");
  private static final HouseholdId CREDITOR = HouseholdId.parse("hh-rc5-creditor");
  private static final long PRINCIPAL = 100L;
  private static final long LAND = 1_000L;
  private static final long LAND_PRICE = 1_000L;

  @Test
  void proletarianCreditorReceivesLandAndDebtIsReduced() {
    World world =
        world(
            DefaultProductionModes.positionId(
                    DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_HIRED_LABORER)
                .orElseThrow());
    world.settle();

    assertThat(world.landOf(DEBTOR)).as("债务人 LAND 按本金等值转出").isEqualTo(900L);
    assertThat(world.landOf(CREDITOR)).as("PROLETARIAN 债权人不再跳过 LAND 腿").isEqualTo(100L);
    assertThat(world.debt().principal()).as("LAND 价值冲减本金").isZero();
    assertThat(world.ctx.marketRound).as("债偿 LAND 转移是 S7-M2 ledger 直转，不属于市场轮 LAND 成交").isNull();
    assertThat(OwnershipClassBook.classOf(world.ctx, CREDITOR))
        .as("债权人取得 LAND 后由资产派生就地变为 OWNER")
        .isEqualTo(OwnershipClass.OWNER);
    assertThat(world.ctx.session.sheet().classMemberships())
        .as("债偿不强制改 currentPosition；最终 class 由资产派生")
        .doesNotContainKey(CREDITOR);
  }

  @Test
  void ownerCreditorReceivesLandAndDebtIsReduced() {
    World world =
        world(
            DefaultProductionModes.positionId(
                    DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_OPERATOR)
                .orElseThrow());
    world.settle();

    assertThat(world.landOf(DEBTOR)).isEqualTo(900L);
    assertThat(world.landOf(CREDITOR)).isEqualTo(100L);
    assertThat(world.debt().principal()).isZero();
    assertThat(world.ctx.marketRound).isNull();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static final class World {
    final SettlementDayContext ctx;

    World(SettlementDayContext ctx) {
      this.ctx = ctx;
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

    DebtContract debt() {
      return ctx.debts.values().iterator().next();
    }

    long landOf(HouseholdId household) {
      return landOf(ctx.session.sheet().landHoldingsOrBase(), household);
    }

    private static long landOf(Map<LandHoldingKey, LandHolding> holdings, HouseholdId household) {
      return holdings.values().stream()
          .filter(holding -> holding.owner().equals(household))
          .mapToLong(LandHolding::quantityMilliMu)
          .sum();
    }
  }

  private static World world(ClassPositionId creditorPosition) {
    ClassPositionId debtorPosition =
        DefaultProductionModes.positionId(
                DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_OPERATOR)
            .orElseThrow();
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(DEBTOR, row(DEBTOR));
    rows.put(CREDITOR, row(CREDITOR));

    Map<ClassPositionId, io.mosire.simos.economy.model.ProductionRole> positions =
        new LinkedHashMap<>();
    positions.put(debtorPosition, DefaultProductionModes.position(debtorPosition).orElseThrow());
    positions.put(
        creditorPosition, DefaultProductionModes.position(creditorPosition).orElseThrow());

    Map<HouseholdId, HouseholdClassMembership> memberships = new LinkedHashMap<>();
    memberships.put(DEBTOR, membership(DEBTOR, debtorPosition));

    MarketTable market =
        new MarketTable(
            SILVER, Set.of(HEX), Map.of(GRAIN, 1L), OptionalLong.of(LAND_PRICE), 20L, 50L);
    DebtContract debt =
        new DebtContract(
            DebtContractId.idOf(
                DEBTOR, CREDITOR, new DebtUnit.Money(SILVER), DebtTerms.legacyDefault()),
            DEBTOR,
            CREDITOR,
            new DebtUnit.Money(SILVER),
            DebtTerms.legacyDefault(),
            DebtSource.ORDINARY_LOAN,
            PRINCIPAL,
            1L,
            OptionalLong.empty(),
            OptionalLong.empty(),
            DebtStatus.NORMAL);
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    debts.put(debt.id(), debt);
    Map<LandHoldingKey, LandHolding> land =
        Map.of(new LandHoldingKey(HEX, DEBTOR), new LandHolding(DEBTOR, HEX, LAND));

    EconomyData base =
        EconomyData.empty()
            .withHouseholdEconomies(rows)
            .withProductionRoles(positions)
            .withClassMemberships(memberships)
            .withDebtContracts(debts)
            .withMarketTables(Map.of(MARKET, market));
    MarketTopology topology = MarketTopology.singleHex(base.marketTables(), base.marketZones());
    EconomySession session =
        new EconomySession(
            base,
            ProductionData.empty(),
            new ProductionRuntimeEngine(ProductionData.empty()),
            EconomyFixtures.landLedger(land));

    SettlementDayContext ctx =
        new SettlementDayContext(
            session,
            5L,
            AccountSession.empty(),
            topology,
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
    ctx.assetShares = new LinkedHashMap<>();
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

  private static HouseholdEconomy row(HouseholdId id) {
    return new HouseholdEconomy(
        id,
        new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.LANDED_PEASANT),
        100L,
        1_000L,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static HouseholdClassMembership membership(HouseholdId id, ClassPositionId position) {
    return new HouseholdClassMembership(id, position, position, Map.of(), 0L, 0L, "rc5-test");
  }
}
