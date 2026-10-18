package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.mosire.simos.production.contract.CycleContribution;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W3（D-032 P4a §3/§4/§5）工资<b>只付现金</b> → 未付差额成普通工资债的独立判据</b>。
 *
 * <p>本类直接驱动唯一的 W3 结算口 {@link WageSettlement#settle}，用<b>纸面算式</b>给出每个期望值：
 *
 * <pre>
 * due = quote.wagePerLaborMilli × tickLaborMilli / 1000 + carryBefore/1000
 * 每付款人现金预算 = 账户余额 − 冻结；按稳定 (unit, role, worker) 序逐笔 paidMoney = min(dueRemaining, cash)
 * 未付差额 ⇒ 一条 WAGE_ARREARS 货币普通债（wageArrearsDefault()，0‰）
 * 库存/市场价/barter 一律不参与 ⇒ 商品库存逐键不变
 * </pre>
 *
 * <p>不按实现反推断言：每个数字都从夹具里写下的 quote/labor/现金余额算出。
 *
 * <p>★ P4a 起没有 barter/抵物判据：原“抵物优先级”位改由“库存零变化 + 未付成债”的等价强判据承担。
 *
 * <p>★ 夹具仍暂时用 {@link RolePayment.MoneyWage} 构造（P4d 才删 barter 字段），但本类<b>不断言</b>工资支付读取或使用
 * 任何 barter 折价。
 */
class W3WageSettlementContractTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String HEX_KEY = "0_0";
  private static final IndustryId INDUSTRY = IndustryHexKeys.id("w3-wage-farm", 0, 0);
  private static final ProductionUnitId UNIT = new ProductionUnitId("unit-w3-wage");
  private static final HouseholdId PAYER = HouseholdId.parse("hh-w3-payer");
  private static final HouseholdId WORKER = HouseholdId.parse("hh-w3-worker");
  private static final HouseholdId WORKER_B = HouseholdId.parse("hh-w3-worker-b");
  private static final ActorRef PAYER_ACTOR = HouseholdActors.of(PAYER);
  private static final ActorRef WORKER_ACTOR = HouseholdActors.of(WORKER);
  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final ProductionRoleId ROLE_ORGANIZER = ProductionRoleId.parse("operator");
  private static final ProductionRoleId ROLE_WORKER = ProductionRoleId.parse("hired-hand");
  private static final long DAY = 1L;
  private static final long DUE_CYCLE = 2L;
  private static final long QUOTE = 1L;
  private static final long LABOR_MILLI = 100_000L; // 100 小时 ⇒ due = 1 × 100,000 / 1000 = 100

  /**
   * P4d 前 {@link RolePayment.MoneyWage} 仍带 barter 折价字段；夹具保留占位值，但 P4a 的工资结算不读它
   * （下面每条断言都不依赖它）。
   */
  private static final BarterDiscountPolicy COMPAT_DISCOUNT =
      new BarterDiscountPolicy(BarterReferencePrice.LOCAL_ASK, 500L);

  /** 现金足额：money 腿支付、库存一毫不动、不新债。 */
  @Test
  void sufficientCashPaysMoneyOnlyAndNoDebtIsCreated() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 100L),
            Map.of(GRAIN, 50_000L),
            Map.of(GRAIN, 10L),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).as("due=100 全部由现金支付").isEqualTo(100L);
    assertThat(readout.arrearsMilli()).as("已付清 ⇒ 零欠款").isZero();
    assertThat(readout.debtContractId()).isEmpty();
    assertThat(readout.reasons()).as("P4a：现金足额时没有任何 barter/库存理由").isEmpty();
    assertTwoNumberConservation(readout);
    assertThat(settlement.debts()).as("零欠款 ⇒ 不写工资债").isEmpty();
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
              assertThat(transfer.goods()).as("工资只现金 ⇒ 不得有商品腿").isEmpty();
              assertThat(transfer.from()).isEqualTo(PAYER_ACTOR);
              assertThat(transfer.to()).isEqualTo(WORKER_ACTOR);
            });
    assertInventoryUntouched(settlement, Map.of(GRAIN, 50_000L));
    assertMoney(settlement, Map.of(SILVER, 0L), Map.of(SILVER, 100L));
  }

  /**
   * 部分现金：现金 4 &lt; due 100 ⇒ paidMoney=4、arrears=96 成 0‰ 工资债；即便库存有 50,000 粮也一毫不动。
   *
   * <p>本用例取代旧“抵物按初始数量降序”判据：可判别性改为“库存逐键零变化 + 未付差额精确成债”。
   */
  @Test
  void partialCashCreatesZeroInterestArrearsAndLeavesInventoryUntouched() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 4L),
            Map.of(FIBER, 50_000L),
            Map.of(FIBER, 10L),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).as("现金用完为止：先付 4").isEqualTo(4L);
    assertThat(readout.arrearsMilli()).as("100 − 4 = 96").isEqualTo(96L);
    assertThat(readout.reasons()).as("P4a：不得再出现任何 barter/库存理由").isEmpty();
    assertTwoNumberConservation(readout);

    assertWageDebt(settlement, readout.debtContractId(), WORKER, 96L);
    assertThat(settlement.wageTransfers())
        .as("同一条 WAGE_PAYMENT 只带货币腿，不得带商品腿")
        .hasSize(1)
        .allSatisfy(
            transfer -> {
              assertThat(transfer.reason()).isEqualTo(TransferReason.WAGE_PAYMENT);
              assertThat(transfer.money()).containsExactly(Map.entry(SILVER, 4L));
              assertThat(transfer.goods()).isEmpty();
            });
    assertInventoryUntouched(settlement, Map.of(FIBER, 50_000L));
  }

  /** 零现金：due 全额成 0‰ 工资债；库存无论多少都不动；不铸任何工资转移。 */
  @Test
  void zeroCashCreatesFullArrearsAndLeavesInventoryUntouched() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 0L),
            Map.of(FIBER, 1_000L, GRAIN, 50_000L),
            Map.of(FIBER, 10L, GRAIN, 10L),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).isZero();
    assertThat(readout.arrearsMilli()).as("零现金 ⇒ 100 全额成债").isEqualTo(100L);
    assertThat(readout.reasons()).isEmpty();
    assertTwoNumberConservation(readout);

    assertWageDebt(settlement, readout.debtContractId(), WORKER, 100L);
    assertThat(settlement.wageTransfers()).as("零现金 ⇒ 没有工资转移").isEmpty();
    assertInventoryUntouched(settlement, Map.of(FIBER, 1_000L, GRAIN, 50_000L));
  }

  /**
   * 缺现金时<b>绝不回退到有价库存</b>：商品在市场表里有价（grain=10）也不得被拿去抵工资。
   *
   * <p>本用例取代旧“缺价商品 skip”判据：即使商品有价，工资也只走现金；差额成债。
   */
  @Test
  void cashShortfallNeverFallsBackToValuedInventory() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 0L),
            Map.of(GRAIN, 50_000L),
            Map.of(GRAIN, 10L),
            Map.of());

    WageSettlementReadout readout = onlyReadout(settlement);
    assertThat(readout.paidMoneyMilli()).isZero();
    assertThat(readout.arrearsMilli()).as("有价库存不得抵付 ⇒ 100 全部成债").isEqualTo(100L);
    assertThat(readout.reasons())
        .as("P4a：不再有 wage-barter-price-missing / 库存折价理由")
        .noneMatch(reason -> reason.contains("barter") || reason.contains("inventory"));
    assertTwoNumberConservation(readout);
    assertWageDebt(settlement, readout.debtContractId(), WORKER, 100L);
    assertThat(settlement.wageTransfers()).isEmpty();
    assertInventoryUntouched(settlement, Map.of(GRAIN, 50_000L));
  }

  /**
   * 现金预算按付款人共享、按稳定 {@code (unit, role, worker)} 序逐笔耗尽：两名工人各 due 100、现金 150
   * ⇒ 先者足额 100、后者 50 + 50 欠款；库存不动。
   */
  @Test
  void cashBudgetIsExhaustedInStableObligationOrder() {
    Settlement settlement =
        settle(
            Map.of(SILVER, 150L),
            Map.of(GRAIN, 50_000L),
            Map.of(GRAIN, 10L),
            Map.of(),
            List.of(
                new WorkerObligation(WORKER, LABOR_MILLI),
                new WorkerObligation(WORKER_B, LABOR_MILLI)),
            QUOTE);

    assertThat(settlement.result().readouts()).hasSize(2);
    WageSettlementReadout first = settlement.result().readouts().get(0);
    WageSettlementReadout second = settlement.result().readouts().get(1);
    assertThat(first.worker()).as("稳定序 = (unit, role, worker)").isEqualTo(WORKER);
    assertThat(first.paidMoneyMilli()).isEqualTo(100L);
    assertThat(first.arrearsMilli()).isZero();
    assertThat(first.debtContractId()).isEmpty();
    assertThat(second.worker()).isEqualTo(WORKER_B);
    assertThat(second.paidMoneyMilli()).as("现金只剩 50").isEqualTo(50L);
    assertThat(second.arrearsMilli()).as("100 − 50 = 50").isEqualTo(50L);
    assertTwoNumberConservation(first);
    assertTwoNumberConservation(second);
    assertWageDebt(settlement, second.debtContractId(), WORKER_B, 50L);
    assertThat(settlement.wageTransfers())
        .as("两条 WAGE_PAYMENT：100 + 50，均只带货币腿")
        .hasSize(2)
        .allSatisfy(transfer -> assertThat(transfer.goods()).isEmpty());
    assertInventoryUntouched(settlement, Map.of(GRAIN, 50_000L));
  }

  /**
   * P4a 负向判据：工资可支付库存读口已从类型系统移除，且 {@link WageSettlement#settle} 的签名不再接收任何
   * Lookup 依赖 ⇒ “工资支付不读库存 / 不因 inventory lookup 不可用而阻断”。
   *
   * <p>本用例取代旧“lookup 不可用 ⇒ 跳过抵物且不回退账户库存”判据。
   */
  @Test
  void settlementApiHasNoWagePayableInventoryLookupDependency() {
    for (String retired :
        List.of(
            "io.mosire.simos.economy.time.WagePayableInventoryLookup",
            "io.mosire.simos.economy.time.WagePayableInventoryLookupImpl",
            "io.mosire.simos.economy.time.UnavailableWagePayableInventoryLookup")) {
      assertThatThrownBy(() -> Class.forName(retired))
          .as("P4a：工资可支付库存读口必须已删除: " + retired)
          .isInstanceOf(ClassNotFoundException.class);
    }

    Method settle =
        Arrays.stream(WageSettlement.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("settle"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("WageSettlement.settle 必须存在"));
    assertThat(Arrays.stream(settle.getParameterTypes()).map(Class::getSimpleName).toList())
        .as("P4a：WageSettlement.settle 不得接收任何 Lookup 依赖（工资不读库存）")
        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("lookup"));
    for (Class<?> parameterType : settle.getParameterTypes()) {
      assertThat(parameterType.getName())
          .as("settle 参数里不得出现任何 inventory lookup 类型")
          .doesNotContain("WagePayableInventoryLookup");
    }
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
            Map.of(),
            List.of(new WorkerObligation(WORKER, 500L)),
            QUOTE);
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
            first.result().carryRemainders(),
            List.of(new WorkerObligation(WORKER, 500L)),
            QUOTE);
    WageSettlementReadout secondReadout = onlyReadout(second);
    assertThat(secondReadout.carryBefore()).isEqualTo(500L);
    assertThat(secondReadout.dueMilli()).as("(1 × 500 + 500) / 1000 = 1").isEqualTo(1L);
    assertThat(secondReadout.carryAfter()).isZero();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  /** 一条待结义务的工人 + 本次实际劳动（毫小时）。 */
  private record WorkerObligation(HouseholdId worker, long laborMilli) {}

  private record Settlement(
      WageSettlement.Result result,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      List<Transfer> wageTransfers) {}

  private static Settlement settle(
      Map<CurrencyId, Long> payerMoney,
      Map<CommodityId, Long> payerGoods,
      Map<CommodityId, Long> prices,
      Map<WageCarryKey, Long> carryBefore) {
    return settle(
        payerMoney,
        payerGoods,
        prices,
        carryBefore,
        List.of(new WorkerObligation(WORKER, LABOR_MILLI)),
        QUOTE);
  }

  private static Settlement settle(
      Map<CurrencyId, Long> payerMoney,
      Map<CommodityId, Long> payerGoods,
      Map<CommodityId, Long> prices,
      Map<WageCarryKey, Long> carryBefore,
      List<WorkerObligation> workers,
      long quote) {
    IndustryDefinitionView industry = industry();
    ProductionProcess unit = unit();
    ProductionData definitions = ProductionData.empty().withIndustry(industry).withInstance(unit);

    Map<HouseholdId, HouseholdEconomy> households = new LinkedHashMap<>();
    households.put(PAYER, row(PAYER, payerGoods, payerMoney));
    for (WorkerObligation worker : workers) {
      households.put(worker.worker(), row(worker.worker(), Map.of(), Map.of()));
    }

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
            .withHouseholdEconomies(households)
            .withProductionRosters(Map.of(UNIT, roster(workers)))
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

    Map<AccountPartitionKey, AccountSnapshot.SnapshotAccount> partitions = new LinkedHashMap<>();
    partitions.put(
        new AccountPartitionKey(PAYER),
        new AccountSnapshot.SnapshotAccount(payerGoods, payerMoney, Map.of(), Map.of()));
    for (WorkerObligation worker : workers) {
      partitions.put(
          new AccountPartitionKey(worker.worker()),
          new AccountSnapshot.SnapshotAccount(Map.of(), Map.of(), Map.of(), Map.of()));
    }
    AccountSnapshot snapshot = AccountSnapshot.of(partitions, Map.of());

    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> householdGoods = new LinkedHashMap<>();
    householdGoods.put(PAYER, new LinkedHashMap<>(payerGoods));
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> householdMoney = new LinkedHashMap<>();
    householdMoney.put(PAYER, new LinkedHashMap<>(payerMoney));
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> frozenGoods = new LinkedHashMap<>();
    frozenGoods.put(PAYER, new LinkedHashMap<>());
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> frozenMoney = new LinkedHashMap<>();
    frozenMoney.put(PAYER, new LinkedHashMap<>());
    Map<ActorRef, HouseholdId> householdOfActor = new LinkedHashMap<>();
    householdOfActor.put(PAYER_ACTOR, PAYER);
    for (WorkerObligation worker : workers) {
      householdGoods.put(worker.worker(), new LinkedHashMap<>());
      householdMoney.put(worker.worker(), new LinkedHashMap<>());
      frozenGoods.put(worker.worker(), new LinkedHashMap<>());
      frozenMoney.put(worker.worker(), new LinkedHashMap<>());
      householdOfActor.put(HouseholdActors.of(worker.worker()), worker.worker());
    }

    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(DAY);
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
            householdOfActor,
            debts,
            ledger,
            productionDay(workers));
    List<Transfer> wageTransfers = new ArrayList<>();
    for (Transfer transfer : ledger.toLedger().transfers()) {
      if (transfer.reason() == TransferReason.WAGE_PAYMENT) {
        wageTransfers.add(transfer);
      }
    }
    return new Settlement(result, debts, householdGoods, householdMoney, wageTransfers);
  }

  private static WageSettlementReadout onlyReadout(Settlement settlement) {
    assertThat(settlement.result().readouts()).hasSize(1);
    return settlement.result().readouts().get(0);
  }

  private static void assertTwoNumberConservation(WageSettlementReadout readout) {
    assertThat(readout.dueMilli())
        .as("P4a 守恒：due = paidMoney + arrears")
        .isEqualTo(readout.paidMoneyMilli() + readout.arrearsMilli());
  }

  private static void assertInventoryUntouched(
      Settlement settlement, Map<CommodityId, Long> expectedPayerGoods) {
    assertThat(settlement.householdGoods().get(PAYER))
        .as("P4a：工资支付不得读写商品库存（付款人库存逐键不变）")
        .containsExactlyInAnyOrderEntriesOf(expectedPayerGoods);
    settlement
        .householdGoods()
        .forEach(
            (household, goods) -> {
              if (!household.equals(PAYER)) {
                assertThat(goods).as("收款人库存同样不得因工资而变动").isEmpty();
              }
            });
  }

  private static void assertMoney(
      Settlement settlement,
      Map<CurrencyId, Long> expectedPayerMoney,
      Map<CurrencyId, Long> expectedWorkerMoney) {
    assertMoneyEntry(settlement.householdMoney().get(PAYER), expectedPayerMoney);
    assertMoneyEntry(settlement.householdMoney().get(WORKER), expectedWorkerMoney);
  }

  /** 零余额键会被账户写口移除，因此按币种取值（缺键 = 0）而不是整表逐键比较。 */
  private static void assertMoneyEntry(
      Map<CurrencyId, Long> actual, Map<CurrencyId, Long> expected) {
    for (Map.Entry<CurrencyId, Long> entry : expected.entrySet()) {
      assertThat(actual.getOrDefault(entry.getKey(), 0L))
          .as("币种 %s 余额", entry.getKey().value())
          .isEqualTo(entry.getValue());
    }
    for (Map.Entry<CurrencyId, Long> entry : actual.entrySet()) {
      assertThat(expected.containsKey(entry.getKey()))
          .as("实际余额里出现未预期币种: %s", entry.getKey().value())
          .isTrue();
    }
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
            new RolePayment.MoneyWage(COMPAT_DISCOUNT));
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

  private static ProductionRoster roster(List<WorkerObligation> workers) {
    List<io.mosire.simos.economy.model.ProductionParticipant> participants = new ArrayList<>();
    participants.add(
        io.mosire.simos.economy.model.ProductionParticipant.of(PAYER, ROLE_ORGANIZER, true, 0L));
    for (WorkerObligation worker : workers) {
      participants.add(
          io.mosire.simos.economy.model.ProductionParticipant.of(
              worker.worker(), ROLE_WORKER, false, worker.laborMilli()));
    }
    return new ProductionRoster(UNIT, INDUSTRY, participants);
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

  private static ProductionDayResult productionDay(List<WorkerObligation> workers) {
    Map<ProductionParticipantKey, Long> laborContributions = new LinkedHashMap<>();
    Map<ProductionParticipantKey, CycleContribution> cycleContributions = new LinkedHashMap<>();
    for (WorkerObligation worker : workers) {
      ProductionParticipantKey key = new ProductionParticipantKey(worker.worker(), ROLE_WORKER);
      laborContributions.put(key, worker.laborMilli());
      cycleContributions.put(
          key,
          new CycleContribution(
              worker.worker(), ROLE_WORKER, worker.laborMilli(), worker.laborMilli()));
    }
    long totalLabor = workers.stream().mapToLong(WorkerObligation::laborMilli).sum();
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
            laborContributions,
            cycleContributions,
            totalLabor,
            null,
            Map.of(),
            List.of(),
            Set.of());
    return new ProductionDayResult(List.of(receipt), Map.of(), Map.of(), Map.of(), Map.of());
  }

  private static void assertWageDebt(
      Settlement settlement,
      Optional<DebtContractId> readoutDebtId,
      HouseholdId worker,
      long principal) {
    DebtContractId expectedId =
        DebtContractId.idOf(PAYER, worker, DebtUnit.money(SILVER), DebtTerms.wageArrearsDefault());
    assertThat(readoutDebtId).as("读数必须带工资债 id").contains(expectedId);
    DebtContract contract = settlement.debts().get(expectedId);
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
    Set<DebtContractId> readoutDebtIds = new LinkedHashSet<>();
    for (WageSettlementReadout readout : settlement.result().readouts()) {
      readout.debtContractId().ifPresent(readoutDebtIds::add);
    }
    assertThat(readoutDebtIds).contains(expectedId);
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
