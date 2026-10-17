package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.InventoryReservationStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.economy.model.WageQuoteSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.BarterDiscountPolicy;
import io.mosire.simos.production.contract.BarterReferencePrice;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W3 真实 tick 验收（D-032 D3 §7、§8.4 P21/P23；B2 回归）</b>。
 *
 * <p>判据：含 {@code MONEY_WAGE} 名册的真实 tick <b>不再被 B2 的 {@code wage-settlement-not-implemented} 整 tick
 * 拒绝</b>；工资按 W3 规则先货币、库存不可支付时余款成 0‰ 工资债、并以 {@code WAGE_PAYMENT} 转移落账；同日 {@code repayDebts}
 * <b>不得</b>把当日新债再付一次。
 *
 * <p>纸面算式：
 *
 * <pre>
 * quote = 1 毫货币/小时，本次 tick 实际劳动 = 100,000 毫小时 ⇒ due = 1 × 100,000 / 1000 = 100
 * 付款人货币 = 4 ⇒ paidMoney = 4
 * 抵物折价 = 0‰ ⇒ 所有可支付商品都具名 wage-barter-discount-zero 并 skip（不是 1:1）
 * ⇒ arrears = 96，工资债 = WAGE_ARREARS + wageArrearsDefault()（0‰），本金 96
 * 同日 repayDebts 若未排除，会用付款人 grain 1,000 把 96 还掉 ⇒ 本金会减少；本用例断言本金仍 = 96。
 * </pre>
 */
class W3WageSettlementTickIntegrationTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId FARM = IndustryHexKeys.id("w3-tick-farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-w3-tick");
  private static final HouseholdId PAYER = HouseholdId.parse("hh-w3-tick-payer");
  private static final HouseholdId WORKER = HouseholdId.parse("hh-w3-tick-worker");
  private static final ActorRef PAYER_ACTOR = HouseholdActors.of(PAYER);
  private static final ActorRef WORKER_ACTOR = HouseholdActors.of(WORKER);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ProductionRoleId ROLE_ORGANIZER = ProductionRoleId.parse("operator");
  private static final ProductionRoleId ROLE_WORKER = ProductionRoleId.parse("hired-hand");
  private static final PeopleLotId WORKER_LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final long WORKER_LABOR_MILLI = 100_000L;
  private static final long PAYER_MONEY = 4L;
  private static final long PAYER_GRAIN = 1_000L;

  @Test
  void moneyWageTickPaysFirstCreatesZeroInterestDebtAndDoesNotRepayItSameDay() {
    EconomyDayStepper stepper = stepper();
    try {
      ProductionLedger ledger = stepper.step(1L);
      List<Transfer> wageTransfers =
          ledger.transfers().stream()
              .filter(transfer -> transfer.reason() == TransferReason.WAGE_PAYMENT)
              .toList();
      assertThat(wageTransfers)
          .as("B2 回归：MONEY_WAGE 名册 tick 必须真实产出一条 WAGE_PAYMENT（不再整 tick 拒绝）")
          .hasSize(1);
      Transfer wage = wageTransfers.get(0);
      assertThat(wage.money())
          .as("U21：先付对应货币 4；库存抵物在折价 0 时全部 skip")
          .containsExactly(Map.entry(SILVER, PAYER_MONEY));
      assertThat(wage.goods()).as("工资角色折价 0‰ ⇒ 库存腿全部 skip（不得 1:1/按余额抵付）").isEmpty();
      assertThat(wage.from()).isEqualTo(PAYER_ACTOR);
      assertThat(wage.to()).isEqualTo(WORKER_ACTOR);

      DebtContractId expectedDebt =
          DebtContractId.idOf(
              PAYER, WORKER, DebtUnit.money(SILVER), DebtTerms.wageArrearsDefault());
      DebtContract debt = stepper.data().debtContracts().get(expectedDebt);
      assertThat(debt).as("余款 96 必须成为工资债").isNotNull();
      assertThat(debt.source()).isEqualTo(DebtSource.WAGE_ARREARS);
      assertThat(debt.terms()).isEqualTo(DebtTerms.wageArrearsDefault());
      assertThat(debt.terms().interestRatePerMillePerCycle()).isZero();
      assertThat(debt.principal())
          .as("P21/D3-I13：同日 repayDebts 必须排除当日新工资债；若未排除会用 grain 1,000 把 96 还掉")
          .isEqualTo(96L);

      assertThat(stepper.lastWageSettlementReadouts())
          .as("W4：同一 tick 的 W3 读数可经只读 accessor 取回")
          .hasSize(1);
      WageSettlementReadout readout = stepper.lastWageSettlementReadouts().get(0);
      assertThat(readout.dueMilli()).isEqualTo(100L);
      assertThat(readout.paidMoneyMilli()).isEqualTo(PAYER_MONEY);
      assertThat(readout.arrearsMilli()).isEqualTo(96L);
      assertThat(readout.barterPaidValueMilli()).isEmpty();
      assertThat(readout.reasons()).contains("wage-barter-discount-zero:grain");
      assertThat(readout.dueMilli())
          .as("D3-I2 三数守恒")
          .isEqualTo(
              readout.paidMoneyMilli()
                  + readout.barterPaidValueMilli().values().stream()
                      .mapToLong(Long::longValue)
                      .sum()
                  + readout.arrearsMilli());
    } finally {
      stepper.close();
    }
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static EconomyDayStepper stepper() {
    IndustryDefinitionView industry = industry();
    ProductionProcess unit =
        new ProductionProcess(
            UNIT,
            FARM,
            ProductionLocation.parse(HEX_KEY),
            PAYER_ACTOR,
            FARM.value(),
            0L,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());
    ProductionData definitions = ProductionData.empty().withIndustry(industry).withInstance(unit);

    OwnershipStake land =
        new OwnershipStake(
            OwnershipStake.idOf(
                FARM,
                AssetKind.LAND,
                PAYER_ACTOR,
                PAYER_ACTOR,
                AssetRightKind.OWNED,
                ProductionLocation.parse(HEX_KEY),
                0L),
            FARM,
            AssetKind.LAND,
            PAYER_ACTOR,
            PAYER_ACTOR,
            1_000L,
            AssetRightKind.OWNED,
            ProductionLocation.parse(HEX_KEY));
    InventoryReservation reservation =
        new InventoryReservation(
            InventoryReservationId.idOf(
                UNIT, PAYER_ACTOR, ProductionLocation.parse(HEX_KEY), GRAIN, 0L),
            UNIT,
            PAYER_ACTOR,
            ProductionLocation.parse(HEX_KEY),
            GRAIN,
            PAYER_GRAIN,
            0L,
            30L,
            InventoryReservationStatus.ACTIVE,
            -1L);
    HouseholdLaborCommitment commitment =
        new HouseholdLaborCommitment(
            new LaborAllocationId("alloc-w3-tick"),
            WORKER_LOT,
            WORKER,
            PAYER_ACTOR,
            UNIT.value(),
            WORKER_LABOR_MILLI,
            1L,
            LaborCommitmentKind.PRODUCTION,
            Optional.of(ROLE_WORKER));
    ProductionWagePosting posting =
        new ProductionWagePosting(
            UNIT,
            ROLE_WORKER,
            1_000L,
            SILVER,
            1L,
            WageQuoteSource.EXPLICIT,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L);
    EconomyData base =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        "w3-tick-integration",
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                        Optional.empty())))
            .withHouseholdEconomies(
                Map.of(
                    PAYER, row(PAYER, PAYER_GRAIN, PAYER_MONEY),
                    WORKER, row(WORKER, 0L, 0L)))
            .withProductionRosters(Map.of(UNIT, roster()))
            .withProductionWagePostings(Map.of(new WagePostingKey(UNIT, ROLE_WORKER), posting))
            .withOwnershipStakes(Map.of(land.id(), land))
            .withInventoryReservations(Map.of(reservation.id(), reservation))
            .withLaborCommitments(Map.of(commitment.id(), commitment))
            .withRelations(
                Map.of(
                    UNIT,
                    new ProductionRules(
                        UNIT, PAYER_ACTOR, new Payee.ToActor(PAYER_ACTOR), List.of(), PAYER_ACTOR)))
            .withMarketTables(
                EconomyFixtures.hexMarkets(
                    Map.of(HEX, EconomyFixtures.quote(SILVER, Map.of(GRAIN, 10L)))));

    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        PAYER, HEX, Map.of(GRAIN, PAYER_GRAIN), Map.of(SILVER, PAYER_MONEY), Map.of(), Map.of());
    accounts.registerHousehold(WORKER, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    return new EconomyDayStepper(
        base,
        definitions,
        new ProductionRuntimeEngine(definitions),
        accounts,
        MarketTopology.singleHex(base.marketTables(), base.marketZones()));
  }

  private static IndustryDefinitionView industry() {
    ProductionRoleDefinition organizer =
        new ProductionRoleDefinition(
            ROLE_ORGANIZER,
            "组织者",
            Optional.empty(),
            1_000L,
            IntraRoleSplitRule.EQUAL,
            0L,
            Optional.empty(),
            List.of(),
            new RolePayment.ProductShare());
    ProductionRoleDefinition worker =
        new ProductionRoleDefinition(
            ROLE_WORKER,
            "雇工",
            Optional.empty(),
            0L,
            IntraRoleSplitRule.EQUAL,
            0L,
            Optional.empty(),
            List.of(),
            new RolePayment.MoneyWage(
                new BarterDiscountPolicy(BarterReferencePrice.LOCAL_ASK, 0L)));
    return new IndustryDefinitionView(
        FARM,
        "W3 tick 夹具",
        new RegimeId("tenant"),
        Optional.empty(),
        1L,
        0L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(GRAIN, 5L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED),
        List.of(organizer, worker),
        RoleShareRemainder.UNALLOCATED,
        List.of(),
        Optional.empty());
  }

  private static ProductionRoster roster() {
    return new ProductionRoster(
        UNIT,
        FARM,
        List.of(
            io.mosire.simos.economy.model.ProductionParticipant.of(PAYER, ROLE_ORGANIZER, true, 0L),
            io.mosire.simos.economy.model.ProductionParticipant.of(
                WORKER, ROLE_WORKER, false, WORKER_LABOR_MILLI)));
  }

  private static HouseholdEconomy row(HouseholdId household, long grain, long money) {
    return new HouseholdEconomy(
        household,
        new io.mosire.simos.economy.api.cohort.CohortKey(
            HEX,
            io.mosire.simos.economy.api.cohort.ResidenceKind.RURAL,
            SocialClassId.POOR_PEASANT),
        100L,
        200_000L,
        1_000,
        money,
        Map.of(GRAIN, grain),
        Map.of(),
        0L);
  }
}
