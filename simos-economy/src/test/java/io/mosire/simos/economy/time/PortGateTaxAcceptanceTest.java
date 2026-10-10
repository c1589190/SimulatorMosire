package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.MarketTaxLayer;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.time.MarketSettlementFixtures.Round;
import io.mosire.simos.economy.time.MarketSettlementFixtures.World;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5 的 T1/T2/T3 + N1/N4 + J2
 * 判据</b>（`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md`）：
 *
 * <pre>
 * ① 两侧闸：可通过 = ⌊transit × E_源 × E_目的 ÷ 1e6⌋ —— **相乘不是取 min**；任一侧 0 ⇒ 零候选；
 *    两侧全开（或未注入）⇒ 逐值不变（I-C2/N1）。
 * ② 三层税：每层只收一次；Σ税 == 买方多付；税进**对应政府的国库户**；**税基不含运费**（§2.2）。
 * ③ 币种挂单闸：钱的来源区（买方区）出口 与 目的区（卖方区）入口**都要过**；同区恒放行；
 *    判据只读政策注入表 —— <b>不读币种的法定区</b>（红线负向）。
 * </pre>
 *
 * <p>★ 夹具：两个市场区（zone-a 锚 H1 / zone-b 锚 H2）、卖方在 H1、买方在 H2、承运家户在 H1（发货格）， 两个区各有一个"睡着"的国库户。成交仍走真实
 * {@code MarketSettlement.clearOncePerCycle} + 唯一写口， 测试不复制任何撮合/计税算式。
 */
class PortGateTaxAcceptanceTest {

  private static final HouseholdId SELLER = HouseholdId.parse("hh-seller");
  private static final HouseholdId BUYER = HouseholdId.parse("hh-buyer");
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-carrier");
  private static final HouseholdId TREASURY_A = HouseholdId.parse("hh-gov-gov-a");
  private static final HouseholdId TREASURY_B = HouseholdId.parse("hh-gov-gov-b");

  private static final String ZONE_A = "zone-a";
  private static final String ZONE_B = "zone-b";
  private static final CurrencyId SILVER = MarketSettlementFixtures.SILVER;

  private static final long SELLER_GRAIN = 200_000L;
  private static final long BUYER_SILVER = 1_000_000L;

  /** 两区世界：卖方/承运/国库 A 在 H1（zone-a），买方/国库 B 在 H2（zone-b）。 */
  private static World twoZoneWorld() {
    return MarketSettlementFixtures.builder()
        .region(ZONE_A, MarketSettlementFixtures.H1)
        .region(ZONE_B, MarketSettlementFixtures.H2)
        .market(MarketSettlementFixtures.H1, 10L)
        .market(MarketSettlementFixtures.H2, 10L)
        .household(SELLER, MarketSettlementFixtures.H1, 1L, SELLER_GRAIN, 0L)
        .household(BUYER, MarketSettlementFixtures.H2, 1L, 0L, BUYER_SILVER)
        .carrier(CARRIER, MarketSettlementFixtures.H1, 10_000_000L, 10_000L)
        .dormant(TREASURY_A, MarketSettlementFixtures.H1)
        .dormant(TREASURY_B, MarketSettlementFixtures.H2)
        .build();
  }

  private static MarketSettlement.MarketOutcome settle(
      World world, PortEnforcementInput enforcement, PortTaxInput tax) {
    Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
    Round withInputs = MarketSettlementFixtures.withPortInputs(round, enforcement, tax, null);
    return MarketSettlementFixtures.settleWithCarriers(world, withInputs);
  }

  /** 一个区的商品管制力表（只设该区的一个方向，另一侧不设 ⇒ 开放度 1000‰）。 */
  private static PortEnforcementInput commodityEnforcement(
      String zoneId, long entryPerMille, long exitPerMille) {
    return new PortEnforcementInput(
        Map.of(),
        Map.of(
            zoneId,
            Map.of(
                MarketSettlementFixtures.GRAIN,
                new PortEnforcementInput.Directional(entryPerMille, exitPerMille))));
  }

  // ── ① 两侧闸 ─────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>T1（两侧相乘）+ N1（缺省中性）</b>：一条车道的可通过量 = {@code transit × E_源 × E_目的}；把源区出口 卡到 400‰、目的区入口不设 ⇒
   * 可成交毛量**逐值等于**该公式（不是"变差了"，是算出来的那个数），且严格小于无闸轮的成交毛量。
   */
  @Test
  void twoSidedGateMultipliesBothSidesAndCapsTheFilledQuantityExactly() {
    World world = twoZoneWorld();

    MarketSettlement.MarketOutcome baseline = settle(world, null, null);
    assertThat(baseline.report().fills()).as("无口岸面 ⇒ 跨区成交照跑（baseline）").hasSize(1);
    long freeQuantity = baseline.report().fills().get(0).quantity();
    long transit =
        baseline.report().sellerOutcomes().stream()
            .filter(o -> o.commodity().equals(MarketSettlementFixtures.GRAIN))
            .filter(o -> o.hex().equals(MarketSettlementFixtures.H1))
            .findFirst()
            .orElseThrow()
            .offeredQty();

    // 只卡源区出口 25‰（管制力 975）⇒ E_源 = 25‰、E_目的 = 1000‰（不设）⇒ 可通过 = ⌊transit ÷ 40⌋
    World oneSideWorld = twoZoneWorld();
    MarketSettlement.MarketOutcome oneSide =
        settle(oneSideWorld, commodityEnforcement(ZONE_A, 0L, 975L), null);
    long allowedOneSide = PortThrottle.allowedTransitMilli(transit, 25L, 1_000L);
    assertThat(oneSide.report().fills()).hasSize(1);
    // 单侧设限时闸还没绑住需求（allowed > 需求）⇒ 成交额 = min(需求, 可通过量)：闸只做上限，不放大也不硬砍需求。
    assertThat(oneSide.report().fills().get(0).quantity())
        .as("★ 单侧设限：成交 = min(需求, ⌊transit × E_源 × 1000 ÷ 1e6⌋)")
        .isEqualTo(Math.min(freeQuantity, allowedOneSide));

    // 两侧各 25‰ ⇒ 可通过 = ⌊transit × 25 × 25 ÷ 1e6⌋ —— **相乘不是取 min**（取 min 会得到 allowedOneSide）。
    World bothWorld = twoZoneWorld();
    MarketSettlement.MarketOutcome bothSides =
        settle(
            bothWorld,
            new PortEnforcementInput(
                Map.of(),
                Map.of(
                    ZONE_A,
                    Map.of(
                        MarketSettlementFixtures.GRAIN,
                        new PortEnforcementInput.Directional(0L, 975L)),
                    ZONE_B,
                    Map.of(
                        MarketSettlementFixtures.GRAIN,
                        new PortEnforcementInput.Directional(975L, 0L)))),
            null);
    long allowedTwoSides = PortThrottle.allowedTransitMilli(transit, 25L, 25L);
    assertThat(allowedTwoSides)
        .as("★ 两侧相乘 < 取 min（min 会等于单侧那个数）⇒ 本用例能区分两种口径")
        .isLessThan(allowedOneSide);
    assertThat(allowedTwoSides).as("★ 闸真的绑住了（否则本用例测不到东西）").isLessThan(freeQuantity);
    assertThat(bothSides.report().fills()).as("★ 被闸限住仍有成交（过不去的是超出部分，不是整车）").hasSize(1);
    assertThat(bothSides.report().fills().get(0).quantity())
        .as("★ T1：成交毛量 = ⌊transit × E_源 × E_目的 ÷ 1e6⌋（两侧相乘）")
        .isEqualTo(allowedTwoSides);
    assertThat(bothSides.report().unfilledReasonCounts())
        .as("★ 被拦下的那一份必须有具名归因（不许静默丢）")
        .containsKey(MarketUnfilledReason.PORT_THROTTLED);
    assertThat(oneSide.report().fills().get(0).quantity())
        .as("★ 单侧那一轮与「两侧都设」那一轮成交额不同 ⇒ 另一侧真的也算了")
        .isNotEqualTo(bothSides.report().fills().get(0).quantity());
  }

  /** ★★ <b>T1 的极端：任一侧 E = 0 ⇒ 该车道零候选</b>（不是"少卖一点"）：无成交、卖方与买方两边都具名 {@code PORT_THROTTLED}。 */
  @Test
  void eitherSideFullyClosedLeavesZeroCandidatesNamedPortThrottled() {
    World world = twoZoneWorld();

    // 源区出口全关：管制力 1000‰ ⇒ E_源 = 0 ⇒ 预算 0。
    MarketSettlement.MarketOutcome closedSource =
        settle(world, commodityEnforcement(ZONE_A, 0L, 1_000L), null);
    assertThat(closedSource.report().fills()).as("★ 任一侧 0 ⇒ 零候选").isEmpty();
    assertThat(closedSource.report().unfilledReasonCounts())
        .as("★ 具名 PORT_THROTTLED（不是 LOGISTICS_CAPACITY、不是静默）")
        .containsKey(MarketUnfilledReason.PORT_THROTTLED);
    assertThat(closedSource.report().unfilledReasonCounts())
        .as("★ J2：两种归因互不冒充 —— 有运力 ⇒ 不许报成运力不足")
        .doesNotContainKey(MarketUnfilledReason.LOGISTICS_CAPACITY);

    // 目的区入口全关：另一侧 0，同样零候选。
    World otherWorld = twoZoneWorld();
    MarketSettlement.MarketOutcome closedDestination =
        settle(otherWorld, commodityEnforcement(ZONE_B, 1_000L, 0L), null);
    assertThat(closedDestination.report().fills()).as("★ 另一侧 0 同样零候选").isEmpty();
    assertThat(closedDestination.report().unfilledReasonCounts())
        .containsKey(MarketUnfilledReason.PORT_THROTTLED);
  }

  /** ★★ <b>N1（I-C2 缺省语义中性）</b>：两侧都显式全开（各 1000‰）与"根本不注入口岸面"逐值相同 —— 不含口岸数的读数一个都不许变。 */
  @Test
  void bothSidesFullyOpenIsValueIdenticalToNoPolicyAtAll() {
    World world = twoZoneWorld();

    MarketSettlement.MarketOutcome noPolicy = settle(world, null, null);
    World openWorld = twoZoneWorld();
    // ★ 显式全开：管制力 0 ⇒ 开放度 1000‰ ⇒ `bothFullyOpen` ⇒ 不建预算条目（I-P8）。
    MarketSettlement.MarketOutcome fullyOpen =
        settle(
            openWorld,
            new PortEnforcementInput(
                Map.of(),
                Map.of(
                    ZONE_A,
                    Map.of(MarketSettlementFixtures.GRAIN, PortEnforcementInput.Directional.NONE),
                    ZONE_B,
                    Map.of(MarketSettlementFixtures.GRAIN, PortEnforcementInput.Directional.NONE))),
            null);

    assertThat(fullyOpen.report().fills()).hasSize(noPolicy.report().fills().size());
    assertThat(fullyOpen.report().fills().get(0).quantity())
        .as("★ 两侧全开 ⇒ 逐值不变（T1 的后半）")
        .isEqualTo(noPolicy.report().fills().get(0).quantity());
    assertThat(fullyOpen.report().fills().get(0).goodsPaymentMilli())
        .isEqualTo(noPolicy.report().fills().get(0).goodsPaymentMilli());
    assertThat(fullyOpen.report().fills().get(0).freightMilli())
        .isEqualTo(noPolicy.report().fills().get(0).freightMilli());
    assertThat(fullyOpen.report().fills().get(0).lossMilli())
        .isEqualTo(noPolicy.report().fills().get(0).lossMilli());
    assertThat(fullyOpen.report().unfilledReasonCounts())
        .isEqualTo(noPolicy.report().unfilledReasonCounts());
  }

  // ── ② 三层税 ─────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>T2 + T3 + N4 + I-C1</b>：出口税（源区政府）+ 进口税（目的区政府）各收**一次**，Σ税 = 买方多付部分，
   * 钱进<b>对应政府的国库户</b>，且货款/运费/税三腿货币守恒。
   */
  @Test
  void exitAndEntryTaxAreCollectedOnceEachAndLandInTheirOwnTreasury() {
    World world = twoZoneWorld();
    long exitPerUnit = 5L;
    long entryPerUnit = 7L;
    PortTaxInput tax =
        new PortTaxInput(
            Map.of(
                ZONE_A,
                new PortTaxInput.ZoneTaxTable(
                    SILVER,
                    List.of(
                        new PortTaxInput.GovernmentShare(
                            "gov-a", HouseholdActors.of(TREASURY_A), 1L)),
                    Map.of(
                        MarketSettlementFixtures.GRAIN,
                        new PortTaxInput.CommodityTaxRates(exitPerUnit, 0L, 0L, 0L))),
                ZONE_B,
                new PortTaxInput.ZoneTaxTable(
                    SILVER,
                    List.of(
                        new PortTaxInput.GovernmentShare(
                            "gov-b", HouseholdActors.of(TREASURY_B), 1L)),
                    Map.of(
                        MarketSettlementFixtures.GRAIN,
                        new PortTaxInput.CommodityTaxRates(0L, 0L, entryPerUnit, 0L)))));

    MarketSettlement.MarketOutcome outcome = settle(world, null, tax);

    assertThat(outcome.report().fills()).as("设税不挡路（闸与税各管各的）").hasSize(1);
    MarketReport.Fill fill = outcome.report().fills().get(0);
    long quantity = fill.quantity();
    long expectedExit = quantity * exitPerUnit / 1_000L;
    long expectedEntry = quantity * entryPerUnit / 1_000L;

    List<MarketReport.TaxItem> items = outcome.report().taxItems();
    assertThat(items).as("★ 三层里这一笔只有两层（跨区 = 出口 + 进口；区内税是本地成交那一层）").hasSize(2);
    assertThat(items.stream().filter(i -> i.layer() == MarketTaxLayer.PORT_EXIT).toList())
        .as("★ N4：出口税**恰好一条**（不许双重计税）")
        .hasSize(1);
    assertThat(items.stream().filter(i -> i.layer() == MarketTaxLayer.PORT_ENTRY).toList())
        .as("★ N4：进口税**恰好一条**")
        .hasSize(1);
    MarketReport.TaxItem exit =
        items.stream().filter(i -> i.layer() == MarketTaxLayer.PORT_EXIT).findFirst().orElseThrow();
    MarketReport.TaxItem entry =
        items.stream()
            .filter(i -> i.layer() == MarketTaxLayer.PORT_ENTRY)
            .findFirst()
            .orElseThrow();
    assertThat(exit.amountMilli()).as("★ T2：出口税额逐值 = 毛量 × 出口税率").isEqualTo(expectedExit);
    assertThat(exit.currency()).as("★ I-C10：税项带币种").isEqualTo(SILVER);
    assertThat(exit.government()).isEqualTo("gov-a");
    assertThat(exit.zone()).as("出口税收在**源区**").isEqualTo(ZONE_A);
    assertThat(entry.amountMilli()).as("★ T2：进口税额逐值 = 毛量 × 进口税率").isEqualTo(expectedEntry);
    assertThat(entry.government()).isEqualTo("gov-b");
    assertThat(entry.zone()).as("进口税收在**目的区**").isEqualTo(ZONE_B);

    long taxTotal = expectedExit + expectedEntry;
    // ★ 钱真的进国库户（不是只记读数）。
    assertThat(world.silverOf(TREASURY_A)).as("★ T2：源区国库户收到出口税").isEqualTo(expectedExit);
    assertThat(world.silverOf(TREASURY_B)).as("★ T2：目的区国库户收到进口税").isEqualTo(expectedEntry);
    // ★ I-C1：买方总支出 = 货款 + 运费 + Σ税；卖方与承运方各拿自己那一份（税不是销毁）。
    assertThat(world.silverOf(BUYER))
        .as("★ I-C1：买方总支出 = 货款 + 运费 + 三层税")
        .isEqualTo(BUYER_SILVER - fill.goodsPaymentMilli() - fill.freightMilli() - taxTotal);
    assertThat(world.silverOf(SELLER)).as("卖方仍收原价（买方多付、卖方不亏）").isEqualTo(fill.goodsPaymentMilli());
    assertThat(world.silverOf(CARRIER)).as("承运方收运费").isEqualTo(fill.freightMilli());
    // ★ I-C10：读口按币分列（禁跨币种求和）。
    assertThat(outcome.report().taxByCurrency())
        .as("★ 税读数按币分列")
        .containsExactly(Map.entry(SILVER, taxTotal));
    assertThat(outcome.report().taxByLayerGovernmentCurrency())
        .as("★ 读口能回答「哪一层、进哪个国库、什么币、多少」")
        .hasSize(2);
  }

  /**
   * ★★ <b>T3 + §2.2「税基只含货值，运费不计税」</b>：从价税 10% ⇒ 税额**逐值等于**货款 ÷ 10， 而"（货款 + 运费）÷ 10"必须**不等于**它（本笔运费
   * > 0 ⇒ 两者可区分，判据有判别力）。
   */
  @Test
  void adValoremTaxBaseIsGoodsValueOnlyAndNeverIncludesFreight() {
    World world = twoZoneWorld();
    PortTaxInput tax =
        new PortTaxInput(
            Map.of(
                ZONE_A,
                new PortTaxInput.ZoneTaxTable(
                    SILVER,
                    List.of(
                        new PortTaxInput.GovernmentShare(
                            "gov-a", HouseholdActors.of(TREASURY_A), 1L)),
                    Map.of(
                        MarketSettlementFixtures.GRAIN,
                        new PortTaxInput.CommodityTaxRates(0L, 500L, 0L, 0L)))));

    MarketSettlement.MarketOutcome outcome = settle(world, null, tax);

    MarketReport.Fill fill = outcome.report().fills().get(0);
    assertThat(fill.freightMilli()).as("★ 本笔运费 > 0 ⇒「含运费」与「不含运费」两种口径可区分").isPositive();
    long expected = fill.goodsPaymentMilli() * 500L / 1_000L;
    MarketReport.TaxItem exit =
        outcome.report().taxItems().stream()
            .filter(i -> i.layer() == MarketTaxLayer.PORT_EXIT)
            .findFirst()
            .orElseThrow();
    assertThat(exit.amountMilli()).as("★ 税基 = 货款（从价 500‰ ⇒ 货款的一半）").isEqualTo(expected);
    assertThat(fill.goodsPaymentMilli()).as("★ 货款必须 > 1 毫，否则整数除法会把两种口径抹平").isGreaterThan(1L);
    assertThat(exit.amountMilli())
        .as("★ 税基**不含运费**：把运费算进去会得到另一个数")
        .isNotEqualTo((fill.goodsPaymentMilli() + fill.freightMilli()) * 500L / 1_000L);
    assertThat(world.silverOf(TREASURY_A)).isEqualTo(expected);
  }

  /**
   * ★★ <b>N4（三层各自只算一次）</b>：同一轮的**区内市场税**（降级后的 {@code MarketRegulation.tariffPerUnit}）
   * 只收一次、只进本区国库；跨区那一笔不许再收区内税（层与层互不冒充）。
   */
  @Test
  void inZoneMarketTariffIsChargedOnceAndNotOnCrossRegionFills() {
    long tariffPerUnit = 3L;
    // 区内世界：买卖双方同在 H1（同区 ⇒ 不跨境 ⇒ 收区内市场税、不收口岸税）。
    World localWorld =
        MarketSettlementFixtures.builder()
            .region(ZONE_A, MarketSettlementFixtures.H1)
            .market(MarketSettlementFixtures.H1, 10L)
            .household(SELLER, MarketSettlementFixtures.H1, 1L, SELLER_GRAIN, 0L)
            .household(BUYER, MarketSettlementFixtures.H1, 1L, 0L, BUYER_SILVER)
            .dormant(TREASURY_A, MarketSettlementFixtures.H1)
            .build();
    MarketRegulation regulation =
        new MarketRegulation(
            MarketSettlementFixtures.H1, Map.of(MarketSettlementFixtures.GRAIN, tariffPerUnit));
    // ★★ 区内税也要"谁是收税政府、法定币是什么" ⇒ 该区必须有一条**口岸税制表**（只有政府 + 法定币、**不设口岸税率**：
    //   区内税与口岸税是两层，表里 `ratesByCommodity` 空 ⇒ 口岸两层一分不收）。没有这张表 ⇒ 该区没有政府 ⇒ 收 0（§13.2-3）。
    PortTaxInput zoneTable =
        new PortTaxInput(
            Map.of(
                ZONE_A,
                new PortTaxInput.ZoneTaxTable(
                    SILVER,
                    List.of(
                        new PortTaxInput.GovernmentShare(
                            "gov-a", HouseholdActors.of(TREASURY_A), 1L)),
                    Map.of())));
    Round round = MarketSettlementFixtures.round(localWorld, regulation);
    Round injected = MarketSettlementFixtures.withPortInputs(round, null, zoneTable, null);
    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(localWorld, injected);

    assertThat(outcome.report().fills()).as("区内成交").hasSize(1);
    List<MarketReport.TaxItem> items = outcome.report().taxItems();
    assertThat(items).as("★ 区内那一层恰好一条").hasSize(1);
    MarketReport.TaxItem inZone = items.get(0);
    assertThat(inZone.layer()).isEqualTo(MarketTaxLayer.IN_ZONE_MARKET);
    assertThat(inZone.amountMilli())
        .as("★ 区内税额 = 毛量 × tariffPerUnit")
        .isEqualTo(outcome.report().fills().get(0).quantity() * tariffPerUnit / 1_000L);

    // ★ 同一笔不许既收区内税又收口岸税：跨区世界（上面那类）里 IN_ZONE_MARKET 一条都不该有。
    World crossWorld = twoZoneWorld();
    MarketSettlement.MarketOutcome cross = settle(crossWorld, null, null);
    assertThat(cross.report().taxItems()).as("★ 没有政策 ⇒ 一条税项都没有（I-C2）").isEmpty();
  }

  // ── ③ 币种挂单闸 ─────────────────────────────────────────────────────────────────

  private static PortEnforcementInput currencyEnforcement(
      String zoneId, MarketOrderKind kind, long entryPerMille, long exitPerMille) {
    return new PortEnforcementInput(
        Map.of(
            zoneId,
            Map.of(
                SILVER,
                new PortEnforcementInput.CurrencyEnforcement(
                    Map.of(
                        kind, new PortEnforcementInput.Directional(entryPerMille, exitPerMille))))),
        Map.of());
  }

  /**
   * ★★ <b>③ 币种挂单闸：两侧都要过</b>——钱从买方区（源）流向卖方区（目的）： 目的区对该币"禁止入境" ⇒ 零成交、具名 {@code
   * CURRENCY_NOT_ACCEPTED}；源区"禁止出境"同样。
   */
  @Test
  void currencyGateRequiresBothTheSourceExitAndTheDestinationEntry() {
    // 目的区（卖方所在区 = zone-a）对 silver × COMMODITY 禁入 ⇒ E_目的 = 0。
    World destinationClosed = twoZoneWorld();
    MarketSettlement.MarketOutcome byEntry =
        settle(
            destinationClosed,
            currencyEnforcement(ZONE_A, MarketOrderKind.COMMODITY, 1_000L, 0L),
            null);
    assertThat(byEntry.report().fills()).as("★ 钱进不了卖方区 ⇒ 零候选").isEmpty();
    assertThat(byEntry.report().unfilledReasonCounts())
        .as("★ 具名 CURRENCY_NOT_ACCEPTED（不许报成没预算/没运力）")
        .containsKey(MarketUnfilledReason.CURRENCY_NOT_ACCEPTED);
    assertThat(byEntry.report().unfilledReasonCounts())
        .as("★ J2：与 PORT_THROTTLED / LOGISTICS_CAPACITY 互不冒充")
        .doesNotContainKey(MarketUnfilledReason.PORT_THROTTLED)
        .doesNotContainKey(MarketUnfilledReason.LOGISTICS_CAPACITY);

    // 源区（买方所在区 = zone-b）对 silver × COMMODITY 禁出 ⇒ E_源 = 0。
    World sourceClosed = twoZoneWorld();
    MarketSettlement.MarketOutcome byExit =
        settle(
            sourceClosed, currencyEnforcement(ZONE_B, MarketOrderKind.COMMODITY, 0L, 1_000L), null);
    assertThat(byExit.report().fills()).as("★ 钱出不了买方区 ⇒ 零候选").isEmpty();
    assertThat(byExit.report().unfilledReasonCounts())
        .containsKey(MarketUnfilledReason.CURRENCY_NOT_ACCEPTED);
  }

  /**
   * ★★ <b>③ 同区恒放行 + 挂单类型分维 + 红线"不读币种法定区"</b>：
   *
   * <ol>
   *   <li>同区成交（买卖都在 zone-a）在"该币全禁"的政策下**照常成交**（口岸是边界闸，不是市场管制）；
   *   <li>禁的是 {@code LENDING} 类型 ⇒ 现金货物成交（{@code COMMODITY} 类型）**不受影响**（挂单类型这一维真的分开了）；
   *   <li>★ 红线：判据只看政策注入表 —— 对一种**没有挂单类型规则**的币（{@code gold}）全开放行， 而 silver
   *       即便<b>是两个区的计价币/法定币</b>，一旦被规则禁掉就真的被禁（政策压过"是不是法定币"）。
   * </ol>
   */
  @Test
  void sameRegionAlwaysPassesAndGateDoesNotConsultLegalTenderStatus() {
    // ① 同区：买卖都在 zone-a，policy 对 silver × COMMODITY 双向全禁。
    World sameZone =
        MarketSettlementFixtures.builder()
            .region(ZONE_A, MarketSettlementFixtures.H1)
            .market(MarketSettlementFixtures.H1, 10L)
            .household(SELLER, MarketSettlementFixtures.H1, 1L, SELLER_GRAIN, 0L)
            .household(BUYER, MarketSettlementFixtures.H1, 1L, 0L, BUYER_SILVER)
            .build();
    Round round =
        MarketSettlementFixtures.round(sameZone, MarketRegulation.defaultsFor(sameZone.markets()));
    Round injected =
        MarketSettlementFixtures.withPortInputs(
            round,
            currencyEnforcement(ZONE_A, MarketOrderKind.COMMODITY, 1_000L, 1_000L),
            null,
            null);
    MarketSettlement.MarketOutcome outcome = MarketSettlementFixtures.settle(sameZone, injected);
    assertThat(outcome.report().fills()).as("★ 同区流动不受口岸影响（§10.3/§15）").hasSize(1);

    // ② 挂单类型分维：禁 LENDING 不影响 COMMODITY（现金买货照跑）。
    World typed = twoZoneWorld();
    MarketSettlement.MarketOutcome lendingOnly =
        settle(typed, currencyEnforcement(ZONE_A, MarketOrderKind.LENDING, 1_000L, 1_000L), null);
    assertThat(lendingOnly.report().fills()).as("★ 禁的是「借贷」这一档 ⇒ 货↔钱的现金成交不受影响").hasSize(1);

    // ③ 红线：政策压过"是不是法定币"——silver 是两区的计价币，规则一设就真的挡住（不是"法定币自动放行"）。
    World redLine = twoZoneWorld();
    MarketSettlement.MarketOutcome banned =
        settle(redLine, currencyEnforcement(ZONE_A, MarketOrderKind.COMMODITY, 1_000L, 0L), null);
    assertThat(banned.report().fills()).as("★ 判据不读币种的法定区：即使是本区计价币，政策禁入就必须禁").isEmpty();
    assertThat(banned.report().unfilledReasonCounts())
        .containsKey(MarketUnfilledReason.CURRENCY_NOT_ACCEPTED);
  }

  /**
   * ★★ <b>③ 的负向对侧（"未设 ⇒ 逐值不变"）</b>：币种表为空（{@code none()}）与"币种规则表非空但缺该键" 两种形态都必须**全币放行**（缺键 = 管制力 0 ⇒
   * 开放度 1000‰，I-P1/I-C2）。
   */
  @Test
  void missingCurrencyRuleMeansNoRestrictionNotFullBan() {
    World world = twoZoneWorld();
    // 只给 zone-a 的 **gold** 设规则 ⇒ silver 缺键 ⇒ 放行。
    PortEnforcementInput otherCurrencyOnly =
        new PortEnforcementInput(
            Map.of(
                ZONE_A,
                Map.of(
                    new CurrencyId("gold"),
                    new PortEnforcementInput.CurrencyEnforcement(
                        Map.of(
                            MarketOrderKind.COMMODITY,
                            new PortEnforcementInput.Directional(1_000L, 1_000L))))),
            Map.of());

    MarketSettlement.MarketOutcome outcome = settle(world, otherCurrencyOnly, null);
    assertThat(outcome.report().fills()).as("★ 缺键 = 不限制（不是「没规则就全禁」）").hasSize(1);
    assertThat(outcome.report().fills().get(0).paymentCurrency()).isEqualTo(SILVER);
    assertThat(outcome.report().fills().get(0).quantity()).isPositive();
  }
}
