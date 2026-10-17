package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W2「债务式清算」主路径与还债轮的验收用例</b>（本批新增；判据出处逐条列在方法注里）。
 *
 * <p>★★ <b>为什么必须新写一个类</b>：W2b 账本 §5-1 点名的最大缺口 —— 既有市场夹具 {@code MarketSettlementFixtures.round(...)}
 * 一律传 {@code creditConfig=null}/{@code debts=null}，于是 {@code MarketRound.deferredPaymentsEnabled()
 * == false}，走过的是**旧口径回退**（"货款 + 运费 + 税 ≤ 可付"封顶）。 ⇒ 「现金 0 也能拿货、差额记债」「还债 =
 * 有库存就还、介质按估值升序」在测试树里**一条都没被测到**。本类把入口 {@link
 * MarketSettlement.MarketRound#withDeferredPayments(long, Map)} 与还债轮 {@code
 * EconomySettlement.repayDebts} 接上真实代码（不复制撮合/折算算式）。
 *
 * <p>★ <b>判据出处</b>（约束设计书 {@code
 * docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md}）：
 *
 * <ul>
 *   <li>§2 <b>S-5</b>：偿还介质按"该户预估价值最低的商品"优先（{@code DebtValuation.estimatedUnitValueMilli} 升序）——
 *       不是"钱永远第一"；
 *   <li>§6.1 <b>W2 行判据①/③/④</b>：负向「把某户现金清零 ⇒ 它仍拿到货、只记债」；还债介质序 = 升序（逐值表）；按 <b>v3.6</b>「不设任何还债保留窗口 ——
 *       有库存就还、没库存记为债务」；
 *   <li>§1 用户原话（0–8 第 6/7 条）：「先把价格全部记为债务」「然后还债，有库存就该还谁还谁，没库存就记为实际债务」；
 *   <li>§1 用户第六批原话：「为啥要设还债窗口，应当是有库存就还，没库存就不还啊」（v3.6 据此作废 35 天窗口与"一日口粮保留"）。
 * </ul>
 *
 * <p>★★ <b>读数与装置（如实写）</b>：市场侧的"实付现金"直接读 {@code MarketReport.Fill.goodsPaymentMilli()}（★ W2 起这一栏 =
 * **实付现金**，不是名义货价， 见 {@code executeTrade} 的注释）与三张账的余额变化；还债轮的两个**只有日志才有的具名读数**（{@code
 * DEBT_REPAY_ROUND.householdsWithNoStockToRepay} / {@code DEBT_REPAY_MEDIUM_ORDER}）用本模块测试专用的 {@link
 * TestLogCapture}（{@code simos-economy} 的测试类路径上没有 log4j，见该装置自己的类注）。
 *
 * <p>★ 还债轮 {@code EconomySettlement.repayDebts} 是 {@code private static}：本类用**反射直调真方法**（本仓既有先例 =
 * {@code GovServiceCommitmentC7Test}），每条反射都先断言签名找得到（找不到当场抛，不会静默变成空转）。
 */
class DebtBasedClearingAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;

  private static final CommodityId GRAIN = MarketSettlementFixtures.GRAIN;
  private static final CommodityId CLOTH = MarketSettlementFixtures.CLOTH;
  private static final CommodityId TOOL = MarketSettlementFixtures.TOOL;
  private static final CurrencyId SILVER = MarketSettlementFixtures.SILVER;

  private static final HouseholdId SELLER = HouseholdId.parse("hh-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-buyer");

  /** 一 / 数个家户。 */
  private static final HouseholdId DEBTOR = HouseholdId.parse("hh-debtor");

  private static final HouseholdId CREDITOR = HouseholdId.parse("hh-creditor");

  /** 市场日（与夹具同源：只影响 day 字段）。 */
  private static final long DAY = MarketSettlementFixtures.DAY;

  /** 新债务合同的到期周期（夹具值；只进合同的 {@code dueCycle} 字段）。 */
  private static final long DUE_CYCLE = 9L;

  // ── ① 现金 0 的家户仍拿到货（S-6/0–8 第 6 条；§6.1 W2 判据①） ─────────────────────────────

  @Test
  void zeroCashHouseholdStillGetsTheGoodsAndTheUnpaidPriceBecomesDebtToTheSeller() {
    long grainPrice = 10L;
    MarketSettlementFixtures.World world = zeroCashWorld(grainPrice);
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());
    long buyerGrainBefore = world.grainOf(BUYER);
    long sellerGrainBefore = world.grainOf(SELLER);

    // ★ 事件落点 = **market** 分类（{@code MarketSettlement} 的 {@code MARKET = EconomyLog.market()}）；
    //   首轮实测把它挂到 settlement 上 ⇒ 采到 0 行，被下面的 orElseThrow 当场拦下（没有静默通过）。
    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.MARKET_LOGGER_NAME)) {
      MarketSettlement.MarketOutcome outcome =
          MarketSettlementFixtures.settle(
              world,
              new MarketSettlementFixtures.Round(
                  world, round.round().withDeferredPayments(DUE_CYCLE, debts), round.ledger()));

      assertThat(outcome.report().fills()).as("现金 0 的家户必须拿到货（债务式清算：需求全额进撮合）").hasSize(1);
      MarketReport.Fill fill = outcome.report().fills().get(0);
      long expectedQuantity = MarketSettlementFixtures.lifeReserveGrain(1L);
      assertThat(fill.quantity())
          .as("数量只受需求约束（不被'付得起多少'折回）：应 == 1 人的 35 天口粮 %d 毫", expectedQuantity)
          .isEqualTo(expectedQuantity);

      // ★ "实付现金 = 0"：Fill.goodsPaymentMilli 在 W2 起就是**实付现金**那一栏。
      assertThat(fill.goodsPaymentMilli()).as("★ 买方现金 0 ⇒ 实付货款 = 0 毫").isZero();
      assertThat(fill.paymentCurrency()).as("货款/债务都铸在买方支付币上").isEqualTo(SILVER);

      // ★ 名义货价（未付价款）= ⌈数量 × 单价 ÷ 1000⌉（与 executeTrade 同一算式）。
      long nominalPrice = (expectedQuantity * grainPrice + 999L) / 1000L;
      assertThat(outcome.report().unfilled())
          .as("★ 旧拒绝路径已被挡住：整轮不得再出现 NO_BUDGET（W2 起它只表示'现金付不出运费/税'）")
          .noneMatch(unfilled -> unfilled.reason() == MarketUnfilledReason.NO_BUDGET);
      assertThat(debts).as("★ 未付价款必须**全额**记为对该卖方的债务（同一本 debtContracts；不许静默免债）").hasSize(1);
      DebtContract debt = debts.values().iterator().next();
      assertThat(debt.debtor()).as("债务人是买方").isEqualTo(BUYER);
      assertThat(debt.creditor()).as("★ 债权人 = 卖方（卖方直接给予的赊账，不是第三方放贷）").isEqualTo(SELLER);
      assertThat(debt.unit()).as("计价口径 = 买方支付币（货币债）").isEqualTo(new DebtUnit.Money(SILVER));
      assertThat(debt.principal())
          .as(
              "★ 逐值对账：新增债务 == 未付价款 = ⌈%d × %d ÷ 1000⌉ = %d 毫",
              expectedQuantity, grainPrice, nominalPrice)
          .isEqualTo(nominalPrice);
      assertThat(debt.dueCycle()).as("到期周期取自本轮入参").hasValue(DUE_CYCLE);

      // ★ 货腿真的走了、钱腿真的没走（两条互补的守恒面）。
      assertThat(world.grainOf(BUYER))
          .as("买方拿到货：%d → %d", buyerGrainBefore, world.grainOf(BUYER))
          .isEqualTo(buyerGrainBefore + expectedQuantity);
      assertThat(world.grainOf(SELLER))
          .as("卖方按毛量出货")
          .isEqualTo(sellerGrainBefore - expectedQuantity);
      assertThat(world.silverOf(BUYER)).as("买方没付一分钱").isZero();
      assertThat(world.silverOf(SELLER)).as("卖方没收到一分钱（收的是债权）").isZero();
      List<Transfer> transfers = round.ledger().toLedger().transfers();
      assertThat(transfers).as("本轮只有一条货腿（没有任何钱腿；运费/税在本世界恒 0）").hasSize(1);
      assertThat(transfers.get(0).goods()).as("那条腿是货腿").isEqualTo(Map.of(GRAIN, expectedQuantity));
      assertThat(transfers.get(0).money()).as("★ 钱腿为空 = 实付现金 0").isEmpty();

      // ★ 日志侧的同名字段（具名读数）：paymentMilli / paidCashMilli / deferredMilli 三个数逐值对账。
      String deferral =
          log.lines().stream()
              .filter(line -> line.contains("event=MARKET_DEFERRED_PAYMENT "))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("没有 MARKET_DEFERRED_PAYMENT 事件行: " + log.lines()));
      assertThat(fieldOf(deferral, "paymentMilli")).as("名义货价").isEqualTo(nominalPrice);
      assertThat(fieldOf(deferral, "paidCashMilli")).as("★ paidCash = 0").isZero();
      assertThat(fieldOf(deferral, "deferredMilli"))
          .as("★ 记入债务的金额 = 全部未付价款")
          .isEqualTo(nominalPrice);
      assertThat(textFieldOf(deferral, "debtor")).as("债务人").isEqualTo(BUYER.value());
      assertThat(textFieldOf(deferral, "creditor")).as("债权人").isEqualTo(SELLER.value());
    }
  }

  @Test
  void withoutDeferredPaymentAbilityTheSameZeroCashBuyerGetsNothing() {
    MarketSettlementFixtures.World world = zeroCashWorld(10L);
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());

    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(world, round);

    assertThat(round.round().debts()).as("旧口径回退面：本轮没有债务工作副本").isNull();
    assertThat(outcome.report().fills())
        .as("★ 负向对照：没有赊购能力时，同一个现金 0 的买家一笔都拿不到（数量被'付得起'封顶到 0）")
        .isEmpty();
    // ★ 旧口径的可分辨形态（实测）：需求在**订单生成**处就被"按现金买得起量"折回 0 ⇒ 买方连一张买单都不生成，
    //   于是撮合里根本没有它的槽位（未成交表里只有卖方的滞销项），而不是"下了单被拒"。
    assertThat(outcome.report().unfilled())
        .as("★ 旧口径下买方**没有进撮合**（需求被折回 0 ⇒ 不生成买单）：未成交表里不得有买方侧条目")
        .noneMatch(MarketReport.Unfilled::buyerSide);
    assertThat(world.grainOf(BUYER)).as("买家一粒粮都没拿到").isZero();
  }

  /** 现金 0 的买家 + 有货的卖家（同格、无税、无运费 ⇒ 现金腿恒 0 ⇒ 一切差别都来自"能不能赊"）。 */
  private static MarketSettlementFixtures.World zeroCashWorld(long grainPrice) {
    return MarketSettlementFixtures.builder()
        .market(H1, grainPrice)
        .household(
            SELLER,
            H1,
            1L,
            Map.of(GRAIN, 200_000L),
            Map.of(),
            Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
        .household(
            BUYER,
            H1,
            1L,
            Map.of(),
            Map.of(),
            Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)))
        .build();
  }

  // ── ② 还债介质序：预估单位价值升序（S-5 / 第二批第 5 条；§6.1 W2 判据③） ────────────────────

  @Test
  void repaymentMediumOrderIsAscendingEstimatedUnitValueWithMoneyLast() {
    RepayWorld world =
        repayWorld(
            Map.of(GRAIN, 10_000L, CLOTH, 10_000L, TOOL, 10_000L),
            Map.of(SILVER, 10_000L),
            DebtUnit.money(SILVER),
            30L,
            pricedMarket());

    List<DebtUnit> order = mediumOrderOf(world);

    // ★ 逐值表：估值 = DebtValuation.estimatedUnitValueMilli（商品 = 该商品单价；货币 = 1000 毫/单位）。
    assertThat(order)
        .as(
            "★ S-5：介质序必须 = 预估单位价值**升序**（逐值表：grain=5 / cloth=20 / tool=100 / silver=1000）"
                + "，货币**恒最后**（它不是「钱永远第一」的旧口径 D-030 §4）")
        .containsExactly(
            DebtUnit.commodity(GRAIN),
            DebtUnit.commodity(CLOTH),
            DebtUnit.commodity(TOOL),
            DebtUnit.money(SILVER));
  }

  @Test
  void repaymentActuallyPaysWithTheLowestValuedMediumNotWithMoney() {
    RepayWorld world =
        repayWorld(
            Map.of(GRAIN, 10_000L, CLOTH, 10_000L, TOOL, 10_000L),
            Map.of(SILVER, 10_000L),
            DebtUnit.money(SILVER),
            30L,
            pricedMarket());

    ProductionLedger ledger = repay(world);

    // 介质序第一项 = grain（估值 5，最低）⇒ 它先吃：30 合同单位（毫银）折成粮 = 30×1000/5 = 6000 毫，
    //   而实现取"不越过目标的最大量"= ⌊((30+1)×1000−1)/5⌋ = 6199 毫 ⇒ 折算回 30 合同单位，正好还清。
    assertThat(ledger.transfers()).as("还债只铸一条 LOAN_REPAYMENT").hasSize(1);
    Transfer repayment = ledger.transfers().get(0);
    assertThat(repayment.reason()).isEqualTo(TransferReason.LOAN_REPAYMENT);
    assertThat(repayment.from()).as("付方 = 债务人").isEqualTo(HouseholdActors.of(DEBTOR));
    assertThat(repayment.to()).as("收方 = 债权人").isEqualTo(HouseholdActors.of(CREDITOR));
    assertThat(repayment.goods())
        .as("★ 实际还款用的是**估值最低**的 grain（6199 毫），不是钱")
        .isEqualTo(Map.of(GRAIN, 6199L));
    assertThat(repayment.money()).as("★ 货币恒最后 ⇒ 这一笔一分钱都没动（旧口径'钱永远第一'会付 30 毫银）").isEmpty();

    assertThat(world.goods().get(DEBTOR).get(GRAIN)).as("粮被扣掉 6199").isEqualTo(10_000L - 6_199L);
    assertThat(world.goods().get(DEBTOR).get(CLOTH)).as("布没动").isEqualTo(10_000L);
    assertThat(world.goods().get(DEBTOR).get(TOOL)).as("工具没动").isEqualTo(10_000L);
    assertThat(world.money().get(DEBTOR).get(SILVER)).as("银没动").isEqualTo(10_000L);
    assertThat(world.goods().get(CREDITOR).get(GRAIN)).as("债权人收到粮").isEqualTo(6_199L);
    assertThat(principalOf(world)).as("本金还清 ⇒ 0").isZero();
  }

  // ── ③ 无保留窗口（v3.6；§6.1 W2 判据④） ───────────────────────────────────────────────

  @Test
  void noReserveWindowPaysTheWholeStockDownToZero() {
    // ★ 债务人 1 人、当日自然需求 83 毫粮 ⇒ 旧口径（D-030 §4 一日口粮 / v3.5 的 35 天窗口）会留下 83×35 = 2905 毫。
    RepayWorld world =
        repayWorld(
            Map.of(GRAIN, 10_000L),
            Map.of(),
            DebtUnit.money(SILVER),
            1_000L,
            new MarketTable(SILVER, Set.of(), Map.of(GRAIN, 5L)));
    assertThat(world.rows().get(DEBTOR).naturalNeeds())
        .as("夹具前提：这一户当日有口粮需求（否则「没有保留窗口」这条判据是空转）")
        .isEqualTo(Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)));

    ProductionLedger ledger = repay(world);

    assertThat(ledger.transfers()).as("一条还债腿").hasSize(1);
    assertThat(ledger.transfers().get(0).goods())
        .as("★ 有库存就还：整仓 10000 毫粮全部出手（旧口径会留下 2905 毫口粮保留）")
        .isEqualTo(Map.of(GRAIN, 10_000L));
    assertThat(world.goods().get(DEBTOR).getOrDefault(GRAIN, 0L))
        .as("★ 还到 0：库存清空，不留任何保留量")
        .isZero();
    // 10000 毫粮 × 5 毫银/单位 ÷ 1000 = 50 合同单位；本金 1000 − 50 = 950（挂账、不清零）。
    assertThat(principalOf(world)).as("还了 50 毫银的等价物，剩 950 挂账").isEqualTo(950L);
    assertThat(world.debts()).as("合同留在表里（挂账、计息、不清零）").hasSize(1);
  }

  @Test
  void householdWithNothingToPayKeepsTheDebtAndIsCountedAsNoStockToRepay() {
    RepayWorld world = repayWorld(Map.of(), Map.of(), DebtUnit.money(SILVER), 30L, pricedMarket());

    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME)) {
      ProductionLedger ledger = repay(world);

      assertThat(ledger.transfers()).as("没库存 ⇒ 一条腿都不铸（不静默付 0）").isEmpty();
      assertThat(principalOf(world)).as("★ 挂账：本金原样 30（不清零、不核销）").isEqualTo(30L);
      assertThat(world.debts()).as("合同仍在债账里").hasSize(1);

      String roundLine =
          log.lines().stream()
              .filter(line -> line.contains("event=DEBT_REPAY_ROUND "))
              .findFirst()
              .orElseThrow(() -> new AssertionError("没有 DEBT_REPAY_ROUND 事件行: " + log.lines()));
      assertThat(fieldOf(roundLine, "debtorsWithOutstanding")).as("有未偿本金的户数 = 1").isEqualTo(1L);
      assertThat(fieldOf(roundLine, "householdsRepaid")).as("一户都没还上").isZero();
      assertThat(fieldOf(roundLine, "householdsWithNoStockToRepay"))
          .as("★ 具名读数：可动用介质一件都没有的家户数 = 1（这就是「没库存就不还」的现场读数）")
          .isEqualTo(1L);
      assertThat(fieldOf(roundLine, "reserveCommodities")).as("★ 保留量恒 0（v3.6 无保留窗口）").isZero();
      assertThat(roundLine).as("★ 口径具名在日志里（可现场判读）").contains("v3.6-no-reserve-window");
    }
  }

  // ── 还债轮的反射装置（真方法，不是复刻算式） ─────────────────────────────────────────────

  /** 一个"债户世界"：家户行 / 债务表 / 三张账 / 收款 actor 路由 / 价目表。 */
  private record RepayWorld(
      HouseholdId debtor,
      HouseholdId creditor,
      LinkedHashMap<HouseholdId, HouseholdEconomy> rows,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> goods,
      LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> money,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> frozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<HouseholdId, MarketTable> markets,
      ProductionLedger.Accumulator ledger) {}

  /** 只给三种商品定价的市场表（计价币 = 银）。 */
  private static MarketTable pricedMarket() {
    return new MarketTable(SILVER, Set.of(), Map.of(GRAIN, 5L, CLOTH, 20L, TOOL, 100L));
  }

  /**
   * 搭一个"债务人欠债权人一笔债"的世界（家户行 + 债 + 三张账），供 {@code repayDebts} 直调。
   *
   * <p>★ 债务人的 {@code naturalNeeds} 显式给成 1 人 1 天的口粮 —— 让"有没有保留窗口"这条判据可分辨（见上）。
   */
  private static RepayWorld repayWorld(
      Map<CommodityId, Long> debtorGoods,
      Map<CurrencyId, Long> debtorMoney,
      DebtUnit debtUnit,
      long principal,
      MarketTable market) {
    LinkedHashMap<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(DEBTOR, row(DEBTOR, Map.of(GRAIN, MarketSettlementFixtures.dailyRationGrain(1L)), 0L));
    rows.put(CREDITOR, row(CREDITOR, Map.of(), 0L));

    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
    goods.put(DEBTOR, new LinkedHashMap<>(debtorGoods));
    goods.put(CREDITOR, new LinkedHashMap<>());
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    money.put(DEBTOR, new LinkedHashMap<>(debtorMoney));
    money.put(CREDITOR, new LinkedHashMap<>());
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> frozenGoods = new LinkedHashMap<>();
    frozenGoods.put(DEBTOR, new LinkedHashMap<>());
    frozenGoods.put(CREDITOR, new LinkedHashMap<>());
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> frozenMoney = new LinkedHashMap<>();
    frozenMoney.put(DEBTOR, new LinkedHashMap<>());
    frozenMoney.put(CREDITOR, new LinkedHashMap<>());

    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    DebtContractBook.upsert(
        debts,
        DEBTOR,
        CREDITOR,
        debtUnit,
        DebtTerms.legacyDefault(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE),
        DebtSource.ORDINARY_LOAN,
        principal,
        DAY,
        OptionalLong.of(DUE_CYCLE));

    return new RepayWorld(
        DEBTOR,
        CREDITOR,
        rows,
        debts,
        goods,
        money,
        frozenGoods,
        frozenMoney,
        Map.of(HouseholdActors.of(DEBTOR), DEBTOR, HouseholdActors.of(CREDITOR), CREDITOR),
        Map.of(DEBTOR, market, CREDITOR, market),
        new ProductionLedger.Accumulator(DAY));
  }

  /** 一行家户（0 人口：还债轮不读人口，避免夹具引入无关口径；自然需求由调用方给）。 */
  private static HouseholdEconomy row(
      HouseholdId id, Map<CommodityId, Long> naturalNeeds, long money) {
    return new HouseholdEconomy(
        id,
        new CohortKey(H1, ResidenceKind.RURAL, new SocialClassId("poor_peasant")),
        0L,
        0L,
        0,
        money,
        naturalNeeds,
        Map.of(),
        0L);
  }

  /** ★ 反射直调真 {@code EconomySettlement.repayDebts}（找不到签名当场抛 —— 不静默空转）。 */
  private static ProductionLedger repay(RepayWorld world) {
    Method method = declaredRepayDebts();
    try {
      method.invoke(
          null,
          DAY,
          world.rows(),
          world.debts(),
          world.goods(),
          world.money(),
          world.frozenGoods(),
          world.frozenMoney(),
          new LinkedHashMap<HouseholdId, Long>(),
          new LinkedHashMap<HouseholdId, Map<CurrencyId, Long>>(),
          new LinkedHashMap<DebtContractId, Long>(),
          world.householdOfActor(),
          world.markets(),
          DebtValuation.RepayeeResolver.HOUSEHOLD_ACTORS,
          null, // householdPrices = null ⇒ 估值回落"该户市场区默认价目表"（与生产路径同一条回退）
          world.ledger(),
          new EconomySettlement.MoneyIssuanceJournal(Map.of(), 0L),
          Set.of()); // ★ W3：本日无新建工资债 ⇒ 同日排除集为空
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("repayDebts 反射调用失败", cause);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("repayDebts 反射调用失败", failure);
    }
    return world.ledger().toLedger();
  }

  /** ★ 反射直调真 {@code EconomySettlement.repaymentMediumOrder}（S-5 介质序的唯一拼写点）。 */
  private static List<DebtUnit> mediumOrderOf(RepayWorld world) {
    Method method;
    try {
      method =
          EconomySettlement.class.getDeclaredMethod(
              "repaymentMediumOrder",
              HouseholdId.class,
              Map.class,
              Map.class,
              MarketTable.class,
              DebtValuation.HouseholdPriceTable.class);
    } catch (NoSuchMethodException missing) {
      throw new IllegalStateException(
          "EconomySettlement.repaymentMediumOrder 的签名变了（反射找不到）", missing);
    }
    method.setAccessible(true);
    try {
      Object result =
          method.invoke(
              null,
              DEBTOR,
              world.money().get(DEBTOR),
              world.goods().get(DEBTOR),
              pricedMarket(),
              null);
      @SuppressWarnings("unchecked")
      List<DebtUnit> order = (List<DebtUnit>) result;
      return order;
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("repaymentMediumOrder 反射调用失败", cause);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("repaymentMediumOrder 反射调用失败", failure);
    }
  }

  private static Method declaredRepayDebts() {
    try {
      Method method =
          EconomySettlement.class.getDeclaredMethod(
              "repayDebts",
              long.class,
              LinkedHashMap.class,
              LinkedHashMap.class,
              Map.class,
              Map.class,
              Map.class,
              Map.class,
              LinkedHashMap.class,
              LinkedHashMap.class,
              LinkedHashMap.class,
              Map.class,
              Map.class,
              DebtValuation.RepayeeResolver.class,
              DebtValuation.HouseholdPriceTable.class,
              ProductionLedger.Accumulator.class,
              EconomySettlement.MoneyIssuanceJournal.class,
              Set.class);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException missing) {
      throw new IllegalStateException("EconomySettlement.repayDebts 的签名变了（反射找不到）", missing);
    }
  }

  /** 单条债的本金（夹具世界里恒一条）。 */
  private static long principalOf(RepayWorld world) {
    return world.debts().values().stream().mapToLong(DebtContract::principal).sum();
  }

  /** 从事件行里取一个文本字段（读到空白为止）；缺失 ⇒ 当场抛。 */
  private static String textFieldOf(String line, String key) {
    String marker = key + "=";
    int at = line.indexOf(marker);
    if (at < 0) {
      throw new IllegalStateException("事件行缺少字段 " + marker + " : " + line);
    }
    int start = at + marker.length();
    int end = start;
    while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
      end++;
    }
    return line.substring(start, end);
  }

  /** 从事件行里取一个 long 字段；缺失/非整数 ⇒ 当场抛（★ 不静默当 0）。 */
  private static long fieldOf(String line, String key) {
    String marker = key + "=";
    int at = line.indexOf(marker);
    if (at < 0) {
      throw new IllegalStateException("事件行缺少字段 " + marker + " : " + line);
    }
    int start = at + marker.length();
    int end = start;
    while (end < line.length()
        && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
      end++;
    }
    if (end == start) {
      throw new IllegalStateException("事件行的字段不是整数 " + marker + " : " + line);
    }
    return Long.parseLong(line.substring(start, end));
  }
}
