package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.fx.FxFill;
import io.mosire.simos.economy.api.fx.FxVenue;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5 的 T4 判据（换汇循环，端到端）+ F-3「挂单全部」+ V-7「缺价 ⇒ 不换」</b>
 * （`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.3 / §5.1）：
 *
 * <pre>
 * T4  某户持有弱币 ⇒ 出现它的 FX 挂单；部分成交后**下一轮继续**；换完后不再挂
 * F-3 量 = 该弱币**全部可花额**（不扣生活/生产储备）、能换多少换多少
 * V-7 篮子里某商品在该币法定区缺价 ⇒ 该币**整币**不可比 ⇒ 不挂（不猜、不按 1:1 顶上）
 * </pre>
 *
 * <p>★ <b>夹具形状（两个市场区、四个人数极少但需求篮子真实的家户）</b>：
 *
 * <pre>
 * zone-a 锚 H1，法定/计价币 = silver：grain 10,000、cloth 10,000
 * zone-b 锚 H2，法定/计价币 = copper：grain  1,000、cloth 20,000
 * X（H1）需求篮子 = grain 83 ⇒ 付清要 830 毫银 vs 83 毫铜 ⇒ **铜最强** ⇒ 卖银买铜（挂铜的买单、付银）
 * Y（H2）需求篮子 = cloth 83 ⇒ 付清要 830 毫银 vs 1,660 毫铜 ⇒ **银最强** ⇒ 卖铜买银  ← 对手方
 * </pre>
 *
 * <p>★ 两户的篮子不同 ⇒ 两边的"最强币"不同 ⇒ <b>民间簿自己就能撮合</b>（不需要政府窗口）。所有成交仍走真实 {@code FxSettlement.match} + 唯一写口
 * {@code EconomySettlement.applyTransfer}；本类不复制任何撮合/限价算式。
 *
 * <p>★ <b>"下一轮继续"的形态（如实写）</b>：X 的弱币余额在部分成交后仍有剩余 ⇒ 下一轮（新建的轮对象，读的是同一份活余额表） 会按<b>剩下的可花额</b>重新挂单（F-3
 * 每轮现算，不累积余量）。为让第二轮真有对手方，用例把 Y 的铜补回（= 下一轮的产出）—— 那是夹具的输入，不是被测逻辑。
 */
class FxHouseholdRoundAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;

  private static final CommodityId GRAIN = MarketSettlementFixtures.GRAIN;
  private static final CommodityId CLOTH = MarketSettlementFixtures.CLOTH;
  private static final CurrencyId SILVER = MarketSettlementFixtures.SILVER;
  private static final CurrencyId COPPER = new CurrencyId("copper");

  private static final HouseholdId X = HouseholdId.parse("hh-x-weak-silver");
  private static final HouseholdId Y = HouseholdId.parse("hh-y-weak-copper");

  /** 每日需求 83 毫（= 1 人 1 天口粮口径），不是"35 天保留"—— F-1 的篮子就是 naturalNeeds 原文（I-C6）。 */
  private static final long DAILY_NEED = 83L;

  private static final long X_SILVER = 100_000L;
  private static final long X_COPPER = 1_000L;
  private static final long Y_COPPER = 50_000L;
  private static final long Y_SILVER = 1_000L;

  // ── 两个市场区：silver 区（粮便宜）/ copper 区（布贵） ────────────────────────────────

  private static MarketSettlementFixtures.World world(long copperZoneGrainPrice, long yCopper) {
    return MarketSettlementFixtures.builder()
        .region("zone-a", H1)
        .region("zone-b", H2)
        .market(H1, new Market(SILVER, new LinkedHashMap<>(Map.of(GRAIN, 10_000L, CLOTH, 10_000L))))
        .market(
            H2,
            new Market(
                COPPER,
                copperZoneGrainPrice < 0L
                    ? new LinkedHashMap<>(Map.of(CLOTH, 20_000L)) // ★ 缺 grain 的定价行
                    : new LinkedHashMap<>(Map.of(GRAIN, copperZoneGrainPrice, CLOTH, 20_000L))))
        .household(
            X,
            H1,
            1L,
            Map.of(),
            Map.of(SILVER, X_SILVER, COPPER, X_COPPER),
            Map.of(GRAIN, DAILY_NEED))
        .household(
            Y,
            H2,
            1L,
            Map.of(),
            Map.of(COPPER, yCopper, SILVER, Y_SILVER),
            Map.of(CLOTH, DAILY_NEED))
        .build();
  }

  /** ★ 一"轮"的 FX 段：走真实 {@code FxSettlement.match}（民间簿；没有政府窗口 ⇒ {@code FxRoundInput.none()}）。 */
  private static FxRoundResult fxRound(MarketSettlementFixtures.World w) {
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.withFx(
            MarketSettlementFixtures.round(w, MarketRegulation.defaultsFor(w.markets())),
            FxRoundInput.none());
    return FxSettlement.match(round.round(), w.markets(), w.topology());
  }

  private static long silverOf(MarketSettlementFixtures.World w, HouseholdId id) {
    return w.money().getOrDefault(id, Map.of()).getOrDefault(SILVER, 0L);
  }

  private static long copperOf(MarketSettlementFixtures.World w, HouseholdId id) {
    return w.money().getOrDefault(id, Map.of()).getOrDefault(COPPER, 0L);
  }

  /**
   * ★★ <b>T4（挂单 + 换汇）与 F-3（挂单全部）</b>：持弱币的家户（X）挂出买单、与持弱币方向相反的家户（Y）在民间簿成交； 成交量为 <b>X 的弱币全部可花额按自己限价折出的
   * base 量</b> —— 一分钱储备都没扣。
   *
   * <pre>
   * cost(copper) = 83 × 1,000 ÷ 1000 =   83 毫铜     cost(silver) = 83 × 10,000 ÷ 1000 = 830 毫银
   * ⇒ copper 最强（付得最少）；X 卖银买铜 ⇒ base=copper、quote=silver
   * 限价 F-4 = ⌊1000 × cost(silver) ÷ cost(copper)⌋ = 10,000（毫银 / 1000 毫铜）
   * 挂单量      = ⌊100,000 × 1000 ÷ 10,000⌋ = 10,000 毫铜  ⇒ 用掉的银 = 整 100,000（全部）
   * </pre>
   */
  @Test
  void householdConvertsItsEntireWeakBalanceAndTheCounterpartyReceivesIt() {
    MarketSettlementFixtures.World world = world(1_000L, Y_COPPER);

    FxRoundResult fx = fxRound(world);

    assertThat(fx.fills()).as("★ 民间簿自己撮合（没有政府窗口也要能挂能成）").hasSize(1);
    FxFill fill = fx.fills().get(0);
    assertThat(fill.base()).as("X 要买的是铜（最强币）").isEqualTo(COPPER);
    assertThat(fill.quote()).as("X 付出去的是银（弱币）").isEqualTo(SILVER);
    assertThat(fill.venue()).isEqualTo(FxVenue.HOUSEHOLD);
    assertThat(fill.buyer()).isEqualTo(io.mosire.simos.economy.api.cohort.HouseholdActors.of(X));
    assertThat(fill.seller()).isEqualTo(io.mosire.simos.economy.api.cohort.HouseholdActors.of(Y));

    long limitPerMille = HouseholdPurchasingPower.limitPerMille(83L, 830L);
    assertThat(limitPerMille).as("★ F-4：限价来自 F-1 的同一份价格口径").isEqualTo(10_000L);
    long orderedBase = X_SILVER * 1_000L / limitPerMille;
    assertThat(orderedBase).as("★ F-3：挂单量 = 全部 100,000 毫银 ÷ 自己的限价").isEqualTo(10_000L);
    assertThat(fill.baseMilli())
        .as("★ F-3：成交 base = 挂单量（对面的铜足够）⇒ 一分钱生活/生产储备都没扣")
        .isEqualTo(orderedBase);
    assertThat(fill.baseMilli() * limitPerMille / 1_000L)
        .as("★「挂单全部」的逐值形态：base × 自己的限价 ÷ 1000 == 弱币余额（100,000）")
        .isEqualTo(X_SILVER);

    long price = FxPricing.fillPricePerMille(10_000L, 500L);
    assertThat(fill.pricePerMille()).as("★ 自然议价：买卖限价的中点（不另设特权价）").isEqualTo(price);
    assertThat(price).isEqualTo(5_250L);
    assertThat(fill.quoteMilli())
        .as("★ 两腿等值：quote = ⌊base × 价 ÷ 1000⌋")
        .isEqualTo(10_000L * price / 1_000L);

    // ★ 钱真的按两条腿换手（FX 两腿等值，I-C1）：买方少 quote、多 base；卖方相反。
    assertThat(silverOf(world, X)).as("X 付出 quote 腿").isEqualTo(X_SILVER - fill.quoteMilli());
    assertThat(copperOf(world, X)).as("X 收到 base 腿").isEqualTo(X_COPPER + fill.baseMilli());
    assertThat(copperOf(world, Y)).as("Y 付出 base 腿").isEqualTo(Y_COPPER - fill.baseMilli());
    assertThat(silverOf(world, Y)).as("Y 收到 quote 腿").isEqualTo(Y_SILVER + fill.quoteMilli());
  }

  /**
   * ★★ <b>T4（部分成交 ⇒ 下一轮继续）</b>：对手方只能吃下 4,000 毫铜 ⇒ 本轮部分成交；X 手里仍有弱币余额 ⇒
   * <b>下一轮按剩下的可花额重新挂单</b>（不是"沿用上一轮的量"，也不是"一次性清仓"）。
   *
   * <p>★ 判据的形态：第二轮成交的 base 必须等于 {@code ⌊剩余银 × 1000 ÷ 限价⌋ = 7,900}，而**不等于**第一轮的 10,000。
   */
  @Test
  void partialFillLeavesTheRemainderForTheNextRoundWhichRequotesFromWhatIsLeft() {
    MarketSettlementFixtures.World world = world(1_000L, 4_000L);

    FxRoundResult first = fxRound(world);
    assertThat(first.fills()).as("第一轮：对手方只有 4,000 毫铜 ⇒ 部分成交（不是零成交）").hasSize(1);
    long firstBase = first.fills().get(0).baseMilli();
    assertThat(firstBase).as("★ 部分成交的 base = 对手方能给的全部").isEqualTo(4_000L);
    assertThat(firstBase).as("★ 需求侧本来想要 10,000 ⇒「部分」成立（否则本用例测不到循环）").isLessThan(10_000L);

    long silverLeft = silverOf(world, X);
    assertThat(silverLeft).as("★ 未成交部分 = X 手里剩下的弱币（余额 - quote 腿）").isPositive();
    assertThat(silverLeft).isEqualTo(X_SILVER - first.fills().get(0).quoteMilli());

    // ── 下一轮：世界继续走 —— 该户仍持弱币，市场上又有了铜（把对手方的铜补回 = 下一轮的产出）──
    world.money().get(Y).put(COPPER, Y_COPPER);
    FxRoundResult second = fxRound(world);

    assertThat(second.fills()).as("★ 下一轮继续：剩余弱币仍会挂、仍能成交").hasSize(1);
    FxFill secondFill = second.fills().get(0);
    assertThat(secondFill.buyer())
        .as("还是同一户在换（不是别人替它换）")
        .isEqualTo(io.mosire.simos.economy.api.cohort.HouseholdActors.of(X));
    long limitPerMille = HouseholdPurchasingPower.limitPerMille(83L, 830L);
    assertThat(secondFill.baseMilli())
        .as("★ 每轮重算挂单量：⌊剩余银 × 1000 ÷ 限价⌋ = 7,900")
        .isEqualTo(silverLeft * 1_000L / limitPerMille)
        .isEqualTo(7_900L);
    assertThat(secondFill.baseMilli())
        .as("★ 不是沿用第一轮的量（沿用会得到 10,000）——「下一轮继续」= 按剩余额重新挂")
        .isNotEqualTo(firstBase);
    assertThat(silverOf(world, X))
        .as("第二轮又把剩余银花掉一部分 ⇒ 循环真的在推进")
        .isEqualTo(silverLeft - secondFill.quoteMilli());
  }

  /**
   * ★★ <b>T5/V-7（缺价 ⇒ 不换）端到端</b>：copper 区的价表里<b>没有 grain 的定价行</b> ⇒ X 的铜算不出购买力 ⇒ 整币不可比 ⇒
   * <b>不挂单、一分钱不动</b>；对照世界（copper 区给 grain 定价）同一形状立刻成交 —— 两侧对照证明本用例 测的是"缺价"而不是"别的什么挡住了"。
   *
   * <pre>
   * X 的篮子 = {grain 83, cloth 83}（**两件**，否则"缺一件"会被"整个篮子都缺"掩盖）
   * silver 区：grain 20,000 / cloth 10,000 ⇒ cost(silver) = 1,660 + 830 = 2,490
   * copper 区：cloth 20,000、**grain 缺行** ⇒ cost(copper) = 算不出（整币不可比）
   *   ★ 判别力：若把"缺价"当成"这一件不计"（= 按剩下那件的价算），cost(copper) 会变成 1,660 < 2,490
   *     ⇒ 该户会挂单并成交 ⇒ 本用例的"零成交 + 余额不动"当场红
   * Y 的篮子 = {cloth 83}：silver 区 830 < copper 区 1,660 ⇒ silver 最强 ⇒ 卖铜（对手方方向恒定）
   * </pre>
   */
  @Test
  void aZoneWithoutAQuoteForTheNeededGoodPlacesNoOrderAtAll() {
    MarketSettlementFixtures.World missingPrice = twoGoodNeedsWorld(-1L);
    FxRoundResult none = fxRound(missingPrice);

    assertThat(none.fills()).as("★ V-7：铜算不出购买力 ⇒ X 不挂单一笔都不成交").isEmpty();
    assertThat(silverOf(missingPrice, X)).as("★ 不换 ⇒ 弱币余额一个数不动").isEqualTo(X_SILVER);
    assertThat(copperOf(missingPrice, X)).isEqualTo(X_COPPER);
    assertThat(copperOf(missingPrice, Y)).as("★ 对手方的铜也没动（没有对手盘）").isEqualTo(Y_COPPER);
    assertThat(silverOf(missingPrice, Y)).isEqualTo(Y_SILVER);

    // ★ 对照世界：只差"copper 区有没有 grain 的定价行"这一个事实 ⇒ 立刻成交。
    MarketSettlementFixtures.World withPrice = twoGoodNeedsWorld(1_000L);
    assertThat(fxRound(withPrice).fills())
        .as("★ 对照：同一形状、只补上 copper 区的 grain 定价 ⇒ 有成交（证明上面那个 0 是缺价造成的）")
        .hasSize(1);
  }

  /** 缺价用例专用世界：X 的篮子含**两件**商品（grain + cloth），铜区只给 cloth 定价。 */
  private static MarketSettlementFixtures.World twoGoodNeedsWorld(long copperZoneGrainPrice) {
    Map<io.mosire.simos.economy.api.id.CommodityId, Long> copperZonePrices =
        copperZoneGrainPrice < 0L
            ? new LinkedHashMap<>(Map.of(CLOTH, 20_000L)) // ★ 缺 grain 的定价行
            : new LinkedHashMap<>(Map.of(GRAIN, copperZoneGrainPrice, CLOTH, 20_000L));
    return MarketSettlementFixtures.builder()
        .region("zone-a", H1)
        .region("zone-b", H2)
        .market(H1, new Market(SILVER, new LinkedHashMap<>(Map.of(GRAIN, 20_000L, CLOTH, 10_000L))))
        .market(H2, new Market(COPPER, copperZonePrices))
        .household(
            X,
            H1,
            1L,
            Map.of(),
            Map.of(SILVER, X_SILVER, COPPER, X_COPPER),
            Map.of(GRAIN, DAILY_NEED, CLOTH, DAILY_NEED))
        .household(
            Y,
            H2,
            1L,
            Map.of(),
            Map.of(COPPER, Y_COPPER, SILVER, Y_SILVER),
            Map.of(CLOTH, DAILY_NEED))
        .build();
  }
}
