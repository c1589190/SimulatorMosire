package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC4-D：同 key 合户编排（R1）</b>。
 *
 * <p>判据：人口只走 outbox（不写 HouseholdEconomy.population）；钱/货随人走；LAND 全量并入 survivor 且每 hex 守恒；
 * source↔survivor 双向债务自净、不落 debtor==creditor；source 0 人口壳由 App 桥接（本类只验证经济侧与 outbox）。
 */
class HouseholdMergeSettlementTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HouseholdId SOURCE = HouseholdId.parse("hh-merge-source-small");
  private static final HouseholdId SURVIVOR = HouseholdId.parse("hh-merge-survivor-large");
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ClassPositionId LANDED_POSITION =
      DefaultProductionModes.positionId(
              DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_OPERATOR)
          .orElseThrow();
  private static final HouseholdCultureKey CULTURE =
      new HouseholdCultureKey("latium", "roman", List.of(), "本地农户");
  private static final HouseholdCultureKeyLookup CULTURE_LOOKUP = household -> Optional.of(CULTURE);

  @Test
  void sameKeyMergeMovesPopulationViaOutboxAssetsAndLandWithoutSelfDebt() {
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(SOURCE, row(SOURCE, 80L, SocialClassId.LANDED_PEASANT));
    rows.put(SURVIVOR, row(SURVIVOR, 100L, SocialClassId.LANDED_PEASANT));
    Map<HouseholdId, HouseholdClassMembership> memberships = new LinkedHashMap<>();
    memberships.put(SOURCE, membership(SOURCE));
    memberships.put(SURVIVOR, membership(SURVIVOR));

    DebtContract sourceToSurvivor = debt(SOURCE, SURVIVOR, 50L, DebtTerms.legacyDefault());
    DebtContract survivorToSource = debt(SURVIVOR, SOURCE, 30L, DebtTerms.legacyDefault());
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    debts.put(sourceToSurvivor.id(), sourceToSurvivor);
    debts.put(survivorToSource.id(), survivorToSource);
    Map<LandHoldingKey, LandHolding> land =
        Map.of(
            new LandHoldingKey(HEX, SOURCE),
            new LandHolding(SOURCE, HEX, 1_000L),
            new LandHoldingKey(HEX, SURVIVOR),
            new LandHolding(SURVIVOR, HEX, 500L));

    EconomyData base =
        EconomyData.empty()
            .withClassStructures(DefaultProductionModes.classStructures())
            .withProductionRoles(DefaultProductionModes.classPositions())
            .withHouseholdEconomies(rows)
            .withClassMemberships(memberships)
            .withDebtContracts(debts);
    EconomySession session =
        new EconomySession(
            base,
            ProductionData.empty(),
            new ProductionRuntimeEngine(ProductionData.empty()),
            new UnavailableAssetOccupancyLookup(),
            new UnavailableParticipantAttributeLookup(),
            null,
            CULTURE_LOOKUP,
            EconomyFixtures.landLedger(land));
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        SOURCE, HEX, Map.of(GRAIN, 100L), Map.of(SILVER, 1_000L), Map.of(), Map.of());
    accounts.registerHousehold(
        SURVIVOR, HEX, Map.of(GRAIN, 20L), Map.of(SILVER, 100L), Map.of(), Map.of());

    HouseholdMergeSettlement.MergeResult result =
        HouseholdMergeSettlement.mergeAll(session, accounts, CULTURE_LOOKUP, 5L, 0);

    assertThat(result.changed()).isTrue();
    assertThat(result.plans()).isEqualTo(1);
    assertThat(result.selfNettedDebtContracts()).as("source↔survivor 两条债务都自净").isEqualTo(2);
    assertThat(result.selfNettedDebtPrincipal()).isEqualTo(80L);
    assertThat(session.sheet().debtContracts()).as("不落 debtor==creditor 自持合同").isEmpty();
    assertThat(result.populationTransfers())
        .as("人口只走 outbox")
        .hasSize(1)
        .allMatch(
            transfer -> transfer.source().equals(SOURCE) && transfer.target().equals(SURVIVOR))
        .allMatch(transfer -> transfer.population() == 80L);
    assertThat(session.sheet().householdEconomies())
        .as("source 经济行保留（人口权威在 Social，App 桥接后才是 0 人口壳）")
        .containsKey(SOURCE);
    assertThat(session.sheet().householdEconomies().get(SOURCE).population())
        .as("本类不写 HouseholdEconomy.population")
        .isEqualTo(80L);
    assertThat(accounts.householdMoney().get(SURVIVOR).get(SILVER))
        .as("钱随人走：100 + 1,000")
        .isEqualTo(1_100L);
    assertThat(accounts.householdGoods().get(SURVIVOR).get(GRAIN))
        .as("货随人走：20 + 100")
        .isEqualTo(120L);
    assertThat(accounts.householdMoney().get(SOURCE)).as("source 币种清空").isEmpty();
    assertThat(accounts.householdGoods().get(SOURCE)).as("source 商品清空").isEmpty();

    long targetLand =
        session.sheet().landHoldings().values().stream()
            .filter(holding -> holding.owner().equals(SURVIVOR))
            .mapToLong(LandHolding::quantityMilliMu)
            .sum();
    assertThat(targetLand).as("LAND 全量并入 survivor：既有 500 + source 1,000").isEqualTo(1_500L);
    assertThat(
            session.sheet().landHoldings().values().stream()
                .filter(holding -> holding.owner().equals(SOURCE))
                .mapToLong(LandHolding::quantityMilliMu)
                .sum())
        .as("source LAND 清零")
        .isZero();
    assertThat(
            session.sheet().landHoldings().values().stream()
                .mapToLong(LandHolding::quantityMilliMu)
                .sum())
        .as("每 hex LAND 总量守恒")
        .isEqualTo(1_500L);
  }

  private static DebtContract debt(
      HouseholdId debtor, HouseholdId creditor, long principal, DebtTerms terms) {
    return new DebtContract(
        DebtContractId.idOf(debtor, creditor, new DebtUnit.Money(SILVER), terms),
        debtor,
        creditor,
        new DebtUnit.Money(SILVER),
        terms,
        DebtSource.ORDINARY_LOAN,
        principal,
        1L,
        OptionalLong.empty(),
        OptionalLong.empty(),
        DebtStatus.NORMAL);
  }

  private static HouseholdEconomy row(HouseholdId id, long population, SocialClassId stratum) {
    return new HouseholdEconomy(
        id,
        new CohortKey(HEX, ResidenceKind.RURAL, stratum),
        population,
        1_000L,
        1_000,
        0L,
        Map.of(GRAIN, 100L),
        Map.of(),
        0L);
  }

  private static HouseholdClassMembership membership(HouseholdId id) {
    return new HouseholdClassMembership(
        id, LANDED_POSITION, LANDED_POSITION, Map.of(), 0L, 0L, "merge-settlement-test");
  }
}
