package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.market.MarketTaxLayer;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.map.hex.HexCoord;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5 判据 ⑧ + N5 + I-C10（读口不混币 / 禁跨币种求和）</b>：
 *
 * <pre>
 * N5  读口/持久账在**混合币**成交后仍分币列出 —— 不许把不同币的金额直接相加
 * I-C10 任何金额读数必须带币种；persistent 账（OperatorCondition.cycleRevenueMilli 一类）按币分列
 * </pre>
 *
 * <p>本类量两件东西：① 值类型的行为（异币成交的"单价 + 运费"**没有定义**，必须具名排除）；② <b>结构</b>—— record
 * 组件/公开方法名里不许再出现"单一标量收入/成交额"那一族（它正是"跨币相加"的入口）。 结构断言由 {@code
 * §一.11}（旧设计类型直接删、不留兼容位）背书：标量列若被加回来，本用例当场红。
 */
class MarketCurrencyReadoutTest {

  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CurrencyId GOLD = new CurrencyId("gold");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);
  private static final ActorRef SELLER = new ActorRef(ActorKind.HOUSEHOLD, "hh-seller");
  private static final ActorRef BUYER = new ActorRef(ActorKind.HOUSEHOLD, "hh-buyer");

  /** ★ 异币成交：单价币 ≠ 实付币 ⇒ {@code landedUnitPriceMilli()} 必须**没有定义**（不许悄悄相加）。 */
  @Test
  void landedUnitPriceIsUndefinedWhenUnitAndPaymentCurrenciesDiffer() {
    MarketReport.Fill mixed = fill(SILVER, 10L, GOLD, 12_000L, 3L, 300L);
    assertThat(mixed.unitCurrency()).isEqualTo(SILVER);
    assertThat(mixed.paymentCurrency()).isEqualTo(GOLD);
    assertThat(mixed.landedUnitPriceMilli()).as("★ 两种钱不许相加 ⇒ 具名「无定义」（empty），而不是算出一个混合数").isEmpty();

    // 同币：有定义，且逐值 = 单价 + 单位运费（单币世界与改前逐值相同）。
    MarketReport.Fill same = fill(SILVER, 10L, SILVER, 120L, 3L, 300L);
    assertThat(same.landedUnitPriceMilli()).as("★ 同币才有定义").isEqualTo(OptionalLong.of(13L));
  }

  /** ★ I-C10：运费读数带币种，且"运费币 = 买方支付币"（唯一拼写点，不是第二个真值）。 */
  @Test
  void freightReadoutsCarryTheirCurrencyAndNeverCollapseIntoAScalar() {
    MarketReport.Fill fill = fill(SILVER, 10L, GOLD, 12_000L, 3L, 300L);
    assertThat(fill.freightCurrency())
        .as("★ 运费币 = 买方支付币（读口能直接回答「运费是哪一币」）")
        .isEqualTo(fill.paymentCurrency())
        .isEqualTo(GOLD);

    MarketReport report = report(List.of(fill));
    assertThat(report.taxByCurrency()).as("没有税项 ⇒ 空表（不是 0 标量）").isEmpty();
    assertThat(noScalarMoneyAccessor("freightPaidMilli"))
        .as("★ N5：'运费已付'的标量访问器必须不存在（混币时它无处安放）")
        .isTrue();
    assertThat(noScalarMoneyAccessor("freightUncollectedMilli")).isTrue();
  }

  // ── 读口：逐层 / 逐政府 / 逐币，三层不许合并 ────────────────────────────────────────

  /**
   * ★★ <b>N5 的正向守卫</b>：三个税项（两层 + 两个币）⇒ {@code taxByCurrency} 按币分列**两条**、逐条等于该币之和； {@code
   * taxByLayerGovernmentCurrency} 按（层 × 政府 × 币）分列**三条** —— 层与币两个维度都不许被压扁。
   */
  @Test
  void taxReadoutsKeepBothTheLayerAndTheCurrencyDimensions() {
    MarketReport withTax =
        reportWithTax(
            List.of(fill(SILVER, 10L, SILVER, 120L, 3L, 300L)),
            List.of(
                new MarketReport.TaxItem(
                    MarketTaxLayer.PORT_EXIT, "gov-a", SILVER, 5L, GRAIN, "zone-a"),
                new MarketReport.TaxItem(
                    MarketTaxLayer.PORT_ENTRY, "gov-b", SILVER, 9L, GRAIN, "zone-b"),
                new MarketReport.TaxItem(
                    MarketTaxLayer.IN_ZONE_MARKET, "gov-b", GOLD, 4L, GRAIN, "zone-b")));

    assertThat(withTax.taxItems()).as("逐项列出：层 / 政府 / 币 / 额").hasSize(3);
    assertThat(withTax.taxByCurrency())
        .as("★ N5：按币分列（silver 5+9=14、gold 4）—— 不许把 14 与 4 相加成 18")
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, 14L, GOLD, 4L));
    assertThat(withTax.taxByLayerGovernmentCurrency())
        .as("★ 层 × 政府 × 币 三条（三层各自只算一次 ⇒ 每条恰好一条）")
        .hasSize(3);
    assertThat(withTax.taxByLayerGovernmentCurrency().keySet())
        .as("★ 层这一维真的在键里（出口/进口/区内不许合并）")
        .extracting(MarketReport.TaxKey::layer)
        .containsExactlyInAnyOrder(
            MarketTaxLayer.PORT_EXIT, MarketTaxLayer.PORT_ENTRY, MarketTaxLayer.IN_ZONE_MARKET);
    // 同币两条被分组到同一个币键上：5 + 9 = 14（这是**同币相加**，合法）。
    assertThat(withTax.taxByCurrency().get(SILVER)).isEqualTo(14L);
  }

  // ── 持久账：OperatorCondition 的收入列按币分列 ───────────────────────────────────────

  /**
   * ★★ <b>I-C10 的持久账面</b>：{@code OperatorCondition} 的两个收入列是**逐币表**，且旧标量组件名 （{@code
   * lastCycleRevenueMilli} / {@code cycleRevenueMilli}）<b>已不存在</b>——它们是"跨币相加"的入口，
   * 加回来本用例当场红（§一.11：旧设计类型直接删、不留兼容位）。
   */
  @Test
  void operatorConditionKeepsRevenuePerCurrencyAndHasNoScalarRevenueComponent() {
    OperatorCondition condition =
        operatorCondition(Map.of(SILVER, 5L, GOLD, 7L), Map.of(GOLD, 11L));

    assertThat(condition.lastCycleRevenueMilliOf(SILVER)).isEqualTo(5L);
    assertThat(condition.lastCycleRevenueMilliOf(GOLD)).isEqualTo(7L);
    assertThat(condition.lastCycleRevenueMilliOf(new CurrencyId("copper")))
        .as("没收到过这种钱 ⇒ 0（缺键 = 0，不是拿别的币顶上）")
        .isZero();
    assertThat(condition.cycleRevenueByCurrency())
        .as("★ 本周期的收入也是逐币表")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GOLD, 11L));
    assertThat(condition.lastCycleRevenueByCurrency())
        .as("★ 上周期的收入逐币保留（5 与 7 分列，没有 12 这个数）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(SILVER, 5L, GOLD, 7L));

    List<String> components = new ArrayList<>();
    for (RecordComponent rc : OperatorCondition.class.getRecordComponents()) {
      components.add(rc.getName());
    }
    assertThat(components)
        .as("★ N5/I-C10：标量收入列不许存在（逐币表是唯一形状）")
        .doesNotContain("lastCycleRevenueMilli", "cycleRevenueMilli");
    assertThat(components)
        .as("★ 逐币列必须在（形状与 P-T4 的冻结口径一致）")
        .contains("lastCycleRevenueByCurrency", "cycleRevenueByCurrency");
  }

  /** ★ 结构性守卫：{@code MarketReport} 不许再出现"单标量金额"访问器（那一族就是跨币求和的入口）。 */
  @Test
  void noSingleScalarMoneyAccessorSurvivesOnTheReport() {
    for (String banned :
        List.of(
            "freightPaidMilli",
            "freightUncollectedMilli",
            "regulatedTariffMilli",
            "worldTurnoverMilli",
            "hexTurnoverMilli")) {
      assertThat(noScalarMoneyAccessor(banned)).as("★ 旧标量读数 %s 必须已删", banned).isTrue();
    }
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  private static boolean noScalarMoneyAccessor(String name) {
    for (var method : MarketReport.class.getMethods()) {
      if (method.getName().equals(name)) {
        return false;
      }
    }
    return true;
  }

  private static MarketReport.Fill fill(
      CurrencyId unitCurrency,
      long unitPriceMilli,
      CurrencyId paymentCurrency,
      long goodsPaymentMilli,
      long freightPerUnitMilli,
      long freightMilli) {
    return new MarketReport.Fill(
        H1,
        H2,
        GRAIN,
        SELLER,
        BUYER,
        100L,
        unitCurrency,
        unitPriceMilli,
        paymentCurrency,
        goodsPaymentMilli,
        freightPerUnitMilli,
        freightMilli,
        7L,
        true,
        "",
        0L);
  }

  /** 带税项的读数（税项是 record 之外的**派生只读件** ⇒ 只能经唯一的工厂入口挂上）。 */
  private static MarketReport reportWithTax(
      List<MarketReport.Fill> fills, List<MarketReport.TaxItem> taxItems) {
    return MarketReport.withRegulatedTariff(
        5L,
        MarketTrigger.PERIODIC,
        false,
        fills,
        List.of(),
        List.of(),
        Map.of(SILVER, 1L),
        Map.of(),
        0L,
        0L,
        0L,
        PriceMode.FIXED,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        FxRoundResult.none(),
        taxItems);
  }

  private static MarketReport report(List<MarketReport.Fill> fills) {
    return new MarketReport(
        5L,
        MarketTrigger.PERIODIC,
        false,
        fills,
        List.of(
            new MarketReport.Unfilled(BUYER, true, GRAIN, 3L, MarketUnfilledReason.NO_BUDGET, H2)),
        List.of(),
        Map.of(SILVER, 1L),
        Map.of(),
        0L,
        0L,
        0L,
        PriceMode.FIXED,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static OperatorCondition operatorCondition(
      Map<CurrencyId, Long> lastCycle, Map<CurrencyId, Long> cycle) {
    return new OperatorCondition(
        new IndustryId("farm"),
        OperatorCondition.IndustryStatus.ACTIVE,
        0L,
        0L,
        0L,
        0L,
        0L,
        new LinkedHashMap<>(lastCycle),
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        "",
        0L,
        0L,
        0L,
        new LinkedHashMap<>(cycle),
        0L,
        0L,
        0L,
        0L);
  }
}
