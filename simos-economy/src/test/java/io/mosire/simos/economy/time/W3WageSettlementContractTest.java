package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyDayView;
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
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.economy.model.WageQuoteSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.BarterDiscountPolicy;
import io.mosire.simos.production.contract.BarterReferencePrice;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionDayResult;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W3（D-032 D3 §6/§8.4）工资支付 → 抵物 → 余款成债的独立判据</b>。
 *
 * <p>本类直接驱动唯一的 W3 结算口 {@link WageSettlement#settle}，用<b>纸面算式</b>给出每个期望值：
 *
 * <pre>
 * due = quote.wagePerLaborMilli × tickLaborMilli / 1000 + carryBefore/1000
 * 支付顺序 = 对应货币先付 → 可支付库存按“初始可用数量降序、同量 CommodityId 升序”抵物 → 余款成债
 * 抵物单位价值 = floor(当地参考价 × discountPerMille / 1000)
 * </pre>
 *
 * <p>不按实现反推断言：每个数字都从夹具里写下的 quote/labor/价格/折价系数/库存量算出。
 */
class W3WageSettlementContractTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId INDUSTRY = IndustryHexKeys.id("w3-wage-farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-w3-wage");
  private static final HouseholdId PAYER = HouseholdId.parse("hh-w3-payer");
  private static final HouseholdId WORKER = HouseholdId.parse("hh-w3-worker");
  private static final ActorRef PAYER_ACTOR = HouseholdActors.of(PAYER);
  private static final ActorRef WORKER_ACTOR = HouseholdActors.of(WORKER);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final ProductionRoleId ROLE_ORGANIZER = ProductionRoleId.parse("operator");
  private static final ProductionRoleId ROLE_WORKER = ProductionRoleId.parse("hired-hand");
  private static final long DAY = 1L;
  private static final long DUE_CYCLE = 2L;
  private static final long QUOTE = 1L;
  private static final long LABOR_MILLI = 100_000L; // 100 小时 ⇒ due = 1 × 100,000 / 1000 = 100

  /** 货币优先：付款人钱足够 ⇒ 不动库存、不新债。 */
  @Test
  void moneyIsPaidBeforeInventoryAndNoDebtIsCreated() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 100L),
            Map.of(GRAIN, 50_000L),
            Map.of(GRAIN, 25L),
            discount(BarterReferencePrice.LOCAL_ASK, 500L),
            Optional.of(Map.of(GRAIN, 50_000L)),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).as("U21：先付对应货币，due=100 全部由货币支付").isEqualTo(100L);
    assertThat(readout.barterQuantity()).as("钱够 ⇒ 一分库存都不动").isEmpty();
    assertThat(readout.arrearsMilli()).as("已付清 ⇒ 零欠款").isZero();
    assertThat(settlement.result().newWageDebtIds()).as("零欠款 ⇒ 不写工资债").isEmpty();
    assertThat(readout.debtContractId()).isEmpty();
    assertThat(settlement.result().carryRemainders())
        .as("carry 按 (unit, role, worker) 持久；本 tick 恰好整除 ⇒ 0 也保留该键")
        .containsEntry(new WageCarryKey(UNIT, ROLE_WORKER, WORKER), 0L);

    assertThat(settlement.wageTransfers())
        .as("P23：工资只以 WAGE_PAYMENT 转移落账，且只带货币腿")
        .hasSize(1)
        .allSatisfy(
            transfer -> {
              assertThat(transfer.reason()).isEqualTo(TransferReason.WAGE_PAYMENT);
              assertThat(transfer.money()).containsExactly(Map.entry(SILVER, 100L));
              assertThat(transfer.goods()).isEmpty();
              assertThat(transfer.from()).isEqualTo(PAYER_ACTOR);
              assertThat(transfer.to()).isEqualTo(WORKER_ACTOR);
            });
  }

  /**
   * 抵物按<b>初始可用数量降序</b>，不是按价值序：fiber 数量远大于 cloth，但 cloth 单价远高。
   *
   * <pre>
   * 货币 4，due 100 ⇒ 剩 96
   * 参考价 ASK：price=10 ⇒ ask=11；discount=500 ⇒ unitValue=floor(11×500/1000)=5
   * fiber qWanted = floor(96×1000/5)=19,200；fiber 可用 50,000 ⇒ qPay=19,200，paidValue=floor(19,200×5/1000)=96
   * ⇒ 只动 fiber；若按价值序会先吃 cloth（单价 1000、unitValue=505）
   * </pre>
   */
  @Test
  void barterConsumesHighestInitialQuantityFirstNotHighestValue() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 4L),
            Map.of(FIBER, 50_000L, CLOTH, 10L),
            Map.of(FIBER, 10L, CLOTH, 1_000L),
            discount(BarterReferencePrice.LOCAL_ASK, 500L),
            Optional.of(Map.of(FIBER, 50_000L, CLOTH, 10L)),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).as("先付货币 4").isEqualTo(4L);
    assertThat(readout.barterQuantity())
        .as("P18/例 5：数量降序 ⇒ 只消耗 fiber；高价值 cloth 不得先被吃")
        .containsExactly(Map.entry(FIBER, 19_200L));
    assertThat(readout.barterPaidValueMilli())
        .as("P32：unitValue=5 ⇒ 19,200 毫商品抵 96 毫货币")
        .containsExactly(Map.entry(FIBER, 96L));
    assertThat(readout.arrearsMilli()).as("96 = 4 + 96 + 0").isZero();
    assertThreeNumberConservation(readout);
  }

  /**
   * U23-c 折价 500 与余款成债：库存只有 1,000 毫 fiber ⇒ 抵 5 毫，余 91 毫成 0‰ 工资债。
   *
   * <pre>
   * due=100，货币 4 ⇒ 96；fiber 1,000 × 5/1000 = 5 ⇒ arrears = 91
   * </pre>
   */
  @Test
  void fiveHundredDiscountAndRemainderBecomeZeroInterestWageArrears() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 4L),
            Map.of(FIBER, 1_000L),
            Map.of(FIBER, 10L),
            discount(BarterReferencePrice.LOCAL_ASK, 500L),
            Optional.of(Map.of(FIBER, 1_000L)),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).isEqualTo(4L);
    assertThat(readout.barterQuantity()).containsExactly(Map.entry(FIBER, 1_000L));
    assertThat(readout.barterPaidValueMilli()).containsExactly(Map.entry(FIBER, 5L));
    assertThat(readout.arrearsMilli()).as("100 = 4 + 5 + 91").isEqualTo(91L);
    assertThreeNumberConservation(readout);

    assertWageDebt(settlement.debts(), settlement.result().newWageDebtIds(), 91L);
    assertThat(readout.debtContractId()).isPresent();
    assertThat(settlement.result().newWageDebtIds())
        .as("同日还款轮排除集合 = 本日新建工资债 id")
        .containsExactly(readout.debtContractId().orElseThrow());

    assertThat(settlement.wageTransfers())
        .as("同一条 WAGE_PAYMENT 可带钱+物两腿")
        .hasSize(1)
        .allSatisfy(
            transfer -> {
              assertThat(transfer.reason()).isEqualTo(TransferReason.WAGE_PAYMENT);
              assertThat(transfer.money()).containsExactly(Map.entry(SILVER, 4L));
              assertThat(transfer.goods()).containsExactly(Map.entry(FIBER, 1_000L));
            });
  }

  /** D4 读口不可用：库存腿整段跳过（具名理由），不得回退账户余额，余款全部成债。 */
  @Test
  void unavailableInventoryScopeSkipsBarterAndNeverFallsBackToAccountStock() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 4L),
            Map.of(GRAIN, 1_000_000L),
            Map.of(GRAIN, 10L),
            discount(BarterReferencePrice.LOCAL_ASK, 500L),
            Optional.empty(),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).isEqualTo(4L);
    assertThat(readout.barterQuantity()).as("lookup empty ⇒ 不得用账户余额 1,000,000 粮兜底").isEmpty();
    assertThat(readout.arrearsMilli()).isEqualTo(96L);
    assertThat(readout.reasons()).contains("wage-inventory-scope-unavailable");
    assertWageDebt(settlement.debts(), settlement.result().newWageDebtIds(), 96L);
  }

  /** 缺价商品 skip 且保留欠款；不得按 1:1 或任意价抵付。 */
  @Test
  void missingPriceSkipsCommodityAndKeepsArrears() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 0L),
            Map.of(),
            Map.of(), // 故意不给 fiber 定价
            discount(BarterReferencePrice.LOCAL_ASK, 500L),
            Optional.of(Map.of(FIBER, 50_000L)),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).isZero();
    assertThat(readout.barterQuantity()).isEmpty();
    assertThat(readout.arrearsMilli()).as("缺价 ⇒ 不抵付，余款 100 全部成债").isEqualTo(100L);
    assertThat(readout.reasons()).contains("wage-barter-price-missing:fiber");
  }

  /**
   * carry 逐 (unit, role, worker)：quote=1、每 tick 500 毫小时 ⇒ 首 tick due=0/carry=500，次 tick
   * due=1/carry=0。
   */
  @Test
  void carryRemainderIsPerWorkerAndPersistedAcrossTicks() {
    Settlement first =
        settle(
            Map.of(SILVER, 100L),
            Map.of(),
            Map.of(GRAIN, 1_000L),
            discount(BarterReferencePrice.LOCAL_BID, 1_000L),
            Optional.of(Map.of()),
            Map.of(),
            QUOTE,
            500L);
    WageSettlementReadout firstReadout = onlyReadout(first);
    assertThat(firstReadout.dueMilli()).as("1 × 500 / 1000 = 0").isZero();
    assertThat(firstReadout.carryBefore()).isZero();
    assertThat(firstReadout.carryAfter()).isEqualTo(500L);
    assertThat(first.result().carryRemainders())
        .containsEntry(new WageCarryKey(UNIT, ROLE_WORKER, WORKER), 500L);

    Settlement second =
        settle(
            Map.of(SILVER, 100L),
            Map.of(),
            Map.of(GRAIN, 1_000L),
            discount(BarterReferencePrice.LOCAL_BID, 1_000L),
            Optional.of(Map.of()),
            first.result().carryRemainders(),
            QUOTE,
            500L);
    WageSettlementReadout secondReadout = onlyReadout(second);
    assertThat(secondReadout.carryBefore()).isEqualTo(500L);
    assertThat(secondReadout.dueMilli()).as("(1 × 500 + 500) / 1000 = 1").isEqualTo(1L);
    assertThat(secondReadout.carryAfter()).isZero();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static BarterDiscountPolicy discount(BarterReferencePrice reference, long perMille) {
    return new BarterDiscountPolicy(reference, perMille);
  }

  private record Settlement(
      WageSettlement.Result result,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      List<Transfer> wageTransfers) {}

  private static Settlement settle(
      Map<CurrencyId, Long> payerMoney,
      Map<CommodityId, Long> payerGoods,
      Map<CommodityId, Long> prices,
      BarterDiscountPolicy discount,
      Optional<Map<CommodityId, Long>> payableInventory,
      Map<WageCarryKey, Long> carryBefore) {
    return settle(
        payerMoney,
        payerGoods,
        prices,
        discount,
        payableInventory,
        carryBefore,
        QUOTE,
        LABOR_MILLI);
  }

  private static Settlement settle(
      Map<CurrencyId, Long> payerMoney,
      Map<CommodityId, Long> payerGoods,
      Map<CommodityId, Long> prices,
      BarterDiscountPolicy discount,
      Optional<Map<CommodityId, Long>> payableInventory,
      Map<WageCarryKey, Long> carryBefore,
      long quote,
      long laborMilli) {
    IndustryDefinitionView industry = industry(discount);
    ProductionProcess unit = unit();
    ProductionData definitions = ProductionData.empty().withIndustry(industry).withInstance(unit);
    EconomyData base =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        "w3-wage-settlement",
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
                        Optional.empty())))
            .withHouseholdEconomies(
                Map.of(
                    PAYER,
                    row(PAYER, payerGoods, payerMoney),
                    WORKER,
                    row(WORKER, Map.of(), Map.of())))
            .withProductionRosters(Map.of(UNIT, roster()))
            .withProductionWagePostings(Map.of(postingKey(), posting(quote)))
            .withWageCarryRemainders(carryBefore)
            .withMarketTables(EconomyFixtures.hexMarkets(Map.of(HEX, quote(SILVER, prices))))
            .withRelations(
                Map.of(
                    UNIT,
                    new ProductionRules(
                        UNIT,
                        PAYER_ACTOR,
                        new Payee.ToActor(PAYER_ACTOR),
                        List.of(),
                        PAYER_ACTOR)));
    EconomyDayView dayView = EconomyFixtures.dayView(base, definitions);

    AccountSnapshot snapshot =
        AccountSnapshot.of(
            Map.of(
                new AccountPartitionKey(PAYER),
                new AccountSnapshot.SnapshotAccount(payerGoods, payerMoney, Map.of(), Map.of()),
                new AccountPartitionKey(WORKER),
                new AccountSnapshot.SnapshotAccount(Map.of(), Map.of(), Map.of(), Map.of())),
            Map.of());

    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> householdGoods = new LinkedHashMap<>();
    householdGoods.put(PAYER, new LinkedHashMap<>(payerGoods));
    householdGoods.put(WORKER, new LinkedHashMap<>());
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    householdMoney.put(PAYER, new LinkedHashMap<>(payerMoney));
    householdMoney.put(WORKER, new LinkedHashMap<>());
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> frozenGoods = new LinkedHashMap<>();
    frozenGoods.put(PAYER, new LinkedHashMap<>());
    frozenGoods.put(WORKER, new LinkedHashMap<>());
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> frozenMoney = new LinkedHashMap<>();
    frozenMoney.put(PAYER, new LinkedHashMap<>());
    frozenMoney.put(WORKER, new LinkedHashMap<>());

    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(DAY);
    WagePayableInventoryLookup lookup =
        (payer, location) -> {
          assertThat(payer).isEqualTo(PAYER_ACTOR);
          assertThat(location).isEqualTo(ProductionLocation.parse(HEX_KEY));
          return payableInventory;
        };
    WageSettlement.Result result =
        WageSettlement.settle(
            DAY,
            DUE_CYCLE,
            dayView,
            Map.of(UNIT, unit),
            snapshot,
            householdGoods,
            householdMoney,
            frozenGoods,
            frozenMoney,
            Map.of(PAYER_ACTOR, PAYER, WORKER_ACTOR, WORKER),
            debts,
            ledger,
            productionDay(laborMilli),
            MarketTopology.singleHex(base.marketTables(), base.marketZones()),
            lookup);
    List<Transfer> wageTransfers = new ArrayList<>();
    for (Transfer transfer : ledger.toLedger().transfers()) {
      if (transfer.reason() == TransferReason.WAGE_PAYMENT) {
        wageTransfers.add(transfer);
      }
    }
    return new Settlement(result, debts, wageTransfers);
  }

  private static WageSettlementReadout onlyReadout(Settlement settlement) {
    assertThat(settlement.result().readouts()).hasSize(1);
    return settlement.result().readouts().get(0);
  }

  private static void assertThreeNumberConservation(WageSettlementReadout readout) {
    long barter = readout.barterPaidValueMilli().values().stream().mapToLong(Long::longValue).sum();
    assertThat(readout.dueMilli())
        .as("D3-I2 三数守恒：due = paidMoney + Σ paidBarter + arrears")
        .isEqualTo(readout.paidMoneyMilli() + barter + readout.arrearsMilli());
  }

  private static IndustryDefinitionView industry(BarterDiscountPolicy discount) {
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
            new RolePayment.MoneyWage(discount));
    return new IndustryDefinitionView(
        INDUSTRY,
        "W3 工资结算夹具",
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

  private static ProductionProcess unit() {
    return new ProductionProcess(
        UNIT,
        INDUSTRY,
        ProductionLocation.parse(HEX_KEY),
        PAYER_ACTOR,
        INDUSTRY.value(),
        0L,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  private static ProductionRoster roster() {
    return new ProductionRoster(
        UNIT,
        INDUSTRY,
        List.of(
            io.mosire.simos.economy.model.ProductionParticipant.of(PAYER, ROLE_ORGANIZER, true, 0L),
            io.mosire.simos.economy.model.ProductionParticipant.of(
                WORKER, ROLE_WORKER, false, LABOR_MILLI)));
  }

  private static WagePostingKey postingKey() {
    return new WagePostingKey(UNIT, ROLE_WORKER);
  }

  private static ProductionWagePosting posting(long quote) {
    return new ProductionWagePosting(
        UNIT,
        ROLE_WORKER,
        1_000L,
        SILVER,
        quote,
        WageQuoteSource.EXPLICIT,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L);
  }

  private static ProductionDayResult productionDay(long laborMilli) {
    ProductionReceipt receipt =
        new ProductionReceipt(
            UNIT,
            INDUSTRY,
            false,
            false,
            0L,
            1L,
            0L,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(new ProductionParticipantKey(WORKER, ROLE_WORKER), laborMilli),
            Map.of(
                new ProductionParticipantKey(WORKER, ROLE_WORKER),
                new io.mosire.simos.production.contract.CycleContribution(
                    WORKER, ROLE_WORKER, laborMilli, laborMilli)),
            laborMilli,
            null,
            Map.of(),
            List.of(),
            Set.of());
    return new ProductionDayResult(List.of(receipt), Map.of(), Map.of(), Map.of(), Map.of());
  }

  private static void assertWageDebt(
      Map<DebtContractId, DebtContract> debts, Set<DebtContractId> newIds, long principal) {
    DebtContractId expectedId =
        DebtContractId.idOf(PAYER, WORKER, DebtUnit.money(SILVER), DebtTerms.wageArrearsDefault());
    assertThat(newIds).containsExactly(expectedId);
    DebtContract contract = debts.get(expectedId);
    assertThat(contract).as("工资债必须落合同").isNotNull();
    assertThat(contract.source())
        .as("U23-a/R-11：工资债必须带 WAGE_ARREARS 来源（不能只靠 WAGE_PAYMENT/terms）")
        .isEqualTo(DebtSource.WAGE_ARREARS);
    assertThat(contract.terms())
        .as("P22/P29：工资债条款 = wageArrearsDefault()（0‰，不是 legacy 20‰）")
        .isEqualTo(DebtTerms.wageArrearsDefault());
    assertThat(contract.terms().interestRatePerMillePerCycle()).isZero();
    assertThat(contract.unit()).isEqualTo(DebtUnit.money(SILVER));
    assertThat(contract.principal()).as("D3-I8：债务本金 = 未付差额").isEqualTo(principal);
    assertThat(contract.dueCycle()).hasValue(DUE_CYCLE);
  }

  private static HouseholdEconomy row(
      HouseholdId household, Map<CommodityId, Long> goods, Map<CurrencyId, Long> money) {
    return new HouseholdEconomy(
        household,
        new io.mosire.simos.economy.api.cohort.CohortKey(
            HEX,
            io.mosire.simos.economy.api.cohort.ResidenceKind.RURAL,
            SocialClassId.POOR_PEASANT),
        100L,
        200_000L,
        1_000,
        0L,
        goods,
        goods,
        0L);
  }

  private static io.mosire.simos.economy.model.MarketTable quote(
      CurrencyId currency, Map<CommodityId, Long> prices) {
    return EconomyFixtures.quote(currency, prices);
  }
}
