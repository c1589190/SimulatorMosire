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
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
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
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
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
 * ★★★ <b>W3 真实 tick 验收（D-032 P4a §3/§4/§5；B2 回归）</b>。
 *
 * <p>判据：含 {@code MONEY_WAGE} 名册的真实 tick <b>不再被 B2 的 {@code wage-settlement-not-implemented} 整 tick
 * 拒绝</b>；工资只按现金支付（{@code WAGE_PAYMENT} 货币腿），未付差额成 0‰ 工资债；商品库存一毫不动。
 *
 * <p>纸面算式：
 *
 * <pre>
 * quote = 1 毫货币/小时，本次 tick 实际劳动 = 100,000 毫小时 ⇒ due = 1 × 100,000 / 1000 = 100
 * 付款人货币 = 4 ⇒ paidMoney = 4
 * P4a：工资不读库存/市场价/barter ⇒ 付款人 grain 1,000 逐毫不变；没有任何商品腿
 * ⇒ arrears = 96，工资债 = WAGE_ARREARS + wageArrearsDefault()（0‰），本金 96
 * M8 在 M2 之后 ⇒ 本 tick 新工资债不会被同 tick 二次偿还；本金仍 = 96。
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
  private static final long WORKER_LABOR_MILLI = 100_000L;
  private static final long PAYER_MONEY = 4L;
  private static final long PAYER_GRAIN = 1_000L;
  private static final PeopleLotId WORKER_LOT = new PeopleLotId("rural:0_0:MALE:1");

  @Test
  void moneyWageTickPaysFirstCreatesZeroInterestDebtAndDoesNotRepayItSameDay() {
    EconomyDayStepper stepper = stepper(PAYER_MONEY);
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
          .as("P4a：只付现金 4（库存不参与）")
          .containsExactly(Map.entry(SILVER, PAYER_MONEY));
      assertThat(wage.goods()).as("P4a：工资只现金 ⇒ 不得带商品腿").isEmpty();
      assertThat(wage.from()).isEqualTo(PAYER_ACTOR);
      assertThat(wage.to()).isEqualTo(WORKER_ACTOR);
      assertThat(stepper.accounts().householdGoods().get(WORKER)).as("收款人库存不得因工资而变动").isEmpty();

      DebtContractId expectedDebt =
          DebtContractId.idOf(
              PAYER, WORKER, DebtUnit.money(SILVER), DebtTerms.wageArrearsDefault());
      DebtContract debt = stepper.data().debtContracts().get(expectedDebt);
      assertThat(debt).as("余款 96 必须成为工资债").isNotNull();
      assertThat(debt.source()).isEqualTo(DebtSource.WAGE_ARREARS);
      assertThat(debt.terms()).isEqualTo(DebtTerms.wageArrearsDefault());
      assertThat(debt.terms().interestRatePerMillePerCycle()).isZero();
      assertThat(debt.principal()).as("M8 在 M2 之后 ⇒ 本 tick 新工资债不被二次偿还；本金仍 96").isEqualTo(96L);

      assertThat(stepper.lastWageSettlementReadouts())
          .as("W4：同一 tick 的 W3 读数可经只读 accessor 取回")
          .hasSize(1);
      WageSettlementReadout readout = stepper.lastWageSettlementReadouts().get(0);
      assertThat(readout.dueMilli()).isEqualTo(100L);
      assertThat(readout.paidMoneyMilli()).isEqualTo(PAYER_MONEY);
      assertThat(readout.arrearsMilli()).isEqualTo(96L);
      assertThat(readout.debtContractId()).contains(expectedDebt);
      assertThat(readout.reasons()).as("P4a：现金-only ⇒ 无任何 barter/库存理由").isEmpty();
      assertThat(readout.dueMilli())
          .as("P4a 守恒：due = paidMoney + arrears")
          .isEqualTo(readout.paidMoneyMilli() + readout.arrearsMilli());
    } finally {
      stepper.close();
    }
  }

  /**
   * P4a 库存零变化强判据（tick 级）：同一 tick 只把付款人现金从 4 改成足额 100，商品 grain 的最终库存必须逐毫相同 ⇒
   * 工资现金不足没有动用任何库存（生产本身对库存的合法变化被差分消掉）。
   */
  @Test
  void cashShortfallDoesNotChangeTickInventoryComparedToFullyPaid() {
    EconomyDayStepper partial = stepper(PAYER_MONEY);
    EconomyDayStepper fullyPaid = stepper(100L);
    try {
      partial.step(1L);
      fullyPaid.step(1L);
      assertThat(grainOf(partial))
          .as("P4a：现金不足 vs 足额，同 tick 的 grain 库存必须相同 ⇒ 工资未读写库存")
          .isEqualTo(grainOf(fullyPaid));
      assertThat(partial.lastWageSettlementReadouts().get(0).arrearsMilli()).isEqualTo(96L);
      assertThat(fullyPaid.lastWageSettlementReadouts().get(0).arrearsMilli()).isZero();
    } finally {
      partial.close();
      fullyPaid.close();
    }
  }

  private static long grainOf(EconomyDayStepper stepper) {
    return stepper.accounts().householdGoods().get(PAYER).getOrDefault(GRAIN, 0L);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static EconomyDayStepper stepper(long payerMoney) {
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

    // ★ D-032 RA1b2b：LAND 的唯一权威 = landArableSupply + landHoldings（不再进 assetShares）；
    //   SettlementIndex 会按 unit 的 (industry, operator household, location) 合成瞬态 LAND 投影喂 D4。
    LandHolding land = new LandHolding(PAYER, HEX, 1_000L);
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
    // ★★ D-032 T1：不再预种一条 PRODUCTION 劳动配额 —— 名册参与者已声明真实劳动，
    //   本 tick 的配额由劳动队列从 roster 物化（预种会与队列写回同键并存 ⇒ 生产回执 2× 劳动）。
    // ★★★ P4c3：工资报价只读 U20 锚（键 = 市场区 × 产业 × 角色）；缺锚具名拒绝、不按 0 兜底。
    WageAnchorKey anchorKey = new WageAnchorKey(EconomyFixtures.marketIdOf(HEX), FARM, ROLE_WORKER);
    ProductionWageAnchor anchor = new ProductionWageAnchor(SILVER, 1L, 0L, 0L, -1L, 0L, 0L, 0L);
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
                    PAYER, row(PAYER, PAYER_GRAIN, PAYER_MONEY, 200_000L),
                    // ★ RC8b：当前实际劳动的唯一权威是 HouseholdEconomy.laborMilli；WORKER 必须与
                    //   composition/roster 的 100,000 毫小时一致，不能再用旧夹具的 200,000 让 due 变成 200。
                    WORKER, row(WORKER, 0L, 0L, WORKER_LABOR_MILLI)))
            .withProductionRosters(Map.of(UNIT, roster()))
            .withProductionWageAnchors(Map.of(anchorKey, anchor))
            .withInventoryReservations(Map.of(reservation.id(), reservation))
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
        PAYER, HEX, Map.of(GRAIN, PAYER_GRAIN), Map.of(SILVER, payerMoney), Map.of(), Map.of());
    accounts.registerHousehold(WORKER, HEX, Map.of(), Map.of(), Map.of(), Map.of());
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            base,
            definitions,
            new ProductionRuntimeEngine(definitions),
            accounts,
            MarketTopology.singleHex(base.marketTables(), base.marketZones()),
            EconomyFixtures.landLedger(Map.of(land.key(), land)));
    // ★★ D-032 T1：劳动队列要靠 composition 给雇工选批次；旧夹具用一条预种配额"代替" composition，
    //   但那会与新架构的队列写回同键并存 ⇒ 回执 2× 劳动。这里显式给 composition，配额完全由队列物化。
    stepper.updateComposition(Map.of(WORKER, Map.of(WORKER_LOT, 100L)));
    return stepper;
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
            new RolePayment.MoneyWage());
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

  private static HouseholdEconomy row(
      HouseholdId household, long grain, long money, long laborMilli) {
    return new HouseholdEconomy(
        household,
        new io.mosire.simos.economy.api.cohort.CohortKey(
            HEX,
            io.mosire.simos.economy.api.cohort.ResidenceKind.RURAL,
            SocialClassId.POOR_PEASANT),
        100L,
        laborMilli,
        1_000,
        money,
        Map.of(GRAIN, grain),
        Map.of(),
        0L);
  }
}
