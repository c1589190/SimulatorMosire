package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5 的 T5 判据（单元级）+ F-1/F-2/F-4 的冻结口径</b>
 * （`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.3 / §5.1）：
 *
 * <pre>
 * F-1  "用哪个货币付的最少，付得最少的那个购买力最强"
 *      cost(c) = Σ_{naturalNeeds[g] &gt; 0} naturalNeeds[g] × price_{c 的法定区}(g) ÷ 1000     // 毫 c
 * F-2  换汇顺序 = 最弱先换；同强度按币种 id 升序
 * F-4  限价 = ⌊1000 × cost(quote) ÷ cost(base)⌋；说不出价 ⇒ 0 = 不挂（绝不猜 1:1）
 * V-7  篮子里任一件需求量 &gt; 0 的商品在该币法定区缺价 ⇒ 该币**整币**不可比 ⇒ 不换
 * </pre>
 *
 * <p>★ 本类直测唯一拼写点 {@link HouseholdPurchasingPower}（{@link MarketPayChoice} 与 {@code FxSettlement}
 * 共用它 ⇒ "换成哪种币"与"用哪种币付"不可能漂开）：不起整个世界，只喂"家户行 + 该币法定区价表"。 端到端（真挂单/真成交/下一轮继续）由 {@code
 * FxHouseholdRoundAcceptanceTest} 承担。
 */
class FxPurchasingPowerAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;

  private static final CommodityId GRAIN = MarketSettlementFixtures.GRAIN;
  private static final CommodityId CLOTH = MarketSettlementFixtures.CLOTH;
  private static final CurrencyId SILVER = MarketSettlementFixtures.SILVER;
  private static final CurrencyId COPPER = new CurrencyId("copper");

  /** 需求篮子 = grain 200 毫 + cloth 100 毫（F-1 的篮子就是 naturalNeeds 全部键，I-C6）。 */
  private static HouseholdEconomy basketRow() {
    return row(Map.of(GRAIN, 200L, CLOTH, 100L));
  }

  private static HouseholdEconomy row(Map<CommodityId, Long> naturalNeeds) {
    return new HouseholdEconomy(
        HouseholdId.parse("hh-fx-power"),
        new CohortKey(H1, ResidenceKind.RURAL, new SocialClassId("poor_peasant")),
        1L,
        0L,
        0,
        0L,
        naturalNeeds,
        Map.of(),
        0L);
  }

  /** ★ F-2 的挂单顺序落成"排序结果"再断言（{@code weakestFirstOrder} 返回的是比较器，不是序列）。 */
  private static java.util.List<CurrencyId> weakestFirst(Map<CurrencyId, Long> power) {
    java.util.List<CurrencyId> ordered = new java.util.ArrayList<>(power.keySet());
    ordered.sort(HouseholdPurchasingPower.weakestFirstOrder(power));
    return ordered;
  }

  private static HouseholdPurchasingPower.ZoneTable table(
      CurrencyId currency, HexCoord anchor, Map<CommodityId, Long> prices) {
    return new HouseholdPurchasingPower.ZoneTable(currency, anchor, new Market(currency, prices));
  }

  // ── F-1：购买力 = "付清生活消费品需求要付多少"，付得最少者最强 ────────────────────────────

  /**
   * ★★ <b>T5 前半（逐值手算）</b>：两种币各自的"付清需求所需额"逐值等于篮子手算值，且<b>付得最少的那种 = 购买力最强</b>。
   *
   * <pre>
   * silver 价表 {grain=10, cloth=20} ⇒ cost = 200×10÷1000 + 100×20÷1000 = 2 + 2 = 4  （毫银）
   * copper 价表 {grain= 5, cloth=40} ⇒ cost = 200× 5÷1000 + 100×40÷1000 = 1 + 4 = 5  （毫铜）
   * ⇒ silver 付得少 ⇒ silver 最强（★ 不是「币值高者强」，是「付得少者强」）
   * </pre>
   */
  @Test
  void needCostIsTheBasketSumAndTheCheapestToPayIsTheStrongest() {
    HouseholdEconomy row = basketRow();
    HouseholdPurchasingPower.ZoneTable silverTable =
        table(SILVER, H1, Map.of(GRAIN, 10L, CLOTH, 20L));
    HouseholdPurchasingPower.ZoneTable copperTable =
        table(COPPER, H2, Map.of(GRAIN, 5L, CLOTH, 40L));

    assertThat(HouseholdPurchasingPower.needCostMilli(row, silverTable))
        .as("★ F-1：付清需求要付多少毫银（逐值手算：2 + 2）")
        .hasValue(4L);
    assertThat(HouseholdPurchasingPower.needCostMilli(row, copperTable))
        .as("★ F-1：付清需求要付多少毫铜（逐值手算：1 + 4）")
        .hasValue(5L);

    Map<CurrencyId, Long> power = new java.util.LinkedHashMap<>();
    power.put(SILVER, 4L);
    power.put(COPPER, 5L);
    assertThat(HouseholdPurchasingPower.strongest(power))
        .as("★ F-1：付得最少者（cost 4 < 5）= 最强 ⇒ silver")
        .isEqualTo(SILVER);
    assertThat(HouseholdPurchasingPower.strengthOrder(power).compare(SILVER, COPPER))
        .as("★ 强度全序：cost 升序 ⇒ silver 在前")
        .isNegative();
    assertThat(weakestFirst(power))
        .as("★ F-2：换汇顺序 = 最弱先换 ⇒ 首项是 copper（cost 大者）")
        .first()
        .isEqualTo(COPPER);
  }

  /** ★★ <b>F-2 破平：同强度（cost 相同）按币种 id 升序</b>——换汇顺序与"谁最强"都不得依赖任何 map 的迭代序（I7/I-C5）。 */
  @Test
  void tiesOnPurchasingPowerBreakByCurrencyId() {
    Map<CurrencyId, Long> tie = new java.util.LinkedHashMap<>();
    // ★ 刻意把「字典序更大」的币先塞进表：破平若用了迭代序，本用例会得到另一个答案。
    tie.put(SILVER, 7L);
    tie.put(COPPER, 7L);

    assertThat(HouseholdPurchasingPower.strongest(tie))
        .as("★ 同强度 ⇒ 币种 id 升序取小 ⇒ copper（'c' < 's'，与插入序无关）")
        .isEqualTo(COPPER);
    assertThat(weakestFirst(tie))
        .as("★ 挂单顺序同强度按 id 升序 ⇒ copper 先挂")
        .containsExactly(COPPER, SILVER);
  }

  // ── V-7：缺价 ⇒ 整币不可比 ⇒ 不换（不猜、不按 1:1 顶上） ──────────────────────────────

  /**
   * ★★ <b>T5 后半（V-7 冻结）</b>：篮子里任一件需求 &gt; 0 的商品在该币法定区<b>缺定价行</b> ⇒ 该币算不出购买力。
   *
   * <p>★ <b>"定价为 0" 与 "从未定价" 是两件事</b>：0 价是明确免费（照算 0），缺行才是不可比 —— 两侧都断言。
   */
  @Test
  void aMissingPriceInTheBasketMakesTheWholeCurrencyUncomparableButFreeIsComparable() {
    HouseholdEconomy row = basketRow();

    HouseholdPurchasingPower.ZoneTable missingCloth =
        table(COPPER, H2, Map.of(GRAIN, 5L)); // ★ 有 grain 的价、没有 cloth 的价
    assertThat(HouseholdPurchasingPower.needCostMilli(row, missingCloth))
        .as("★ V-7：篮子里的 cloth 缺价 ⇒ 整币算不出（不是「用 grain 的价格凑一个」）")
        .isEmpty();

    HouseholdPurchasingPower.ZoneTable freeCloth =
        table(COPPER, H2, Map.of(GRAIN, 5L, CLOTH, 0L)); // ★ 明确 0 价 = 免费，不是缺价
    assertThat(HouseholdPurchasingPower.needCostMilli(row, freeCloth))
        .as("★ 明确 0 价照算（200×5÷1000 + 0 = 1）—— 不许把 0 价误判成缺价")
        .hasValue(1L);

    // ★ 需求量为 0 的键不进篮子 ⇒ 缺它的价不影响「付清需求」。
    HouseholdEconomy noClothNeeded = row(Map.of(GRAIN, 200L, CLOTH, 0L));
    assertThat(HouseholdPurchasingPower.needCostMilli(noClothNeeded, missingCloth))
        .as("★ 需求量为 0 的商品缺价不算缺（篮子只看需求 > 0 的键）")
        .hasValue(1L);

    assertThat(HouseholdPurchasingPower.needCostMilli(row, null))
        .as("★ 缺价表（该币没有法定区价表）⇒ 也算不出")
        .isEmpty();
  }

  /** ★★ <b>F-4：限价 = {@code ⌊1000 × cost(quote) ÷ cost(base)⌋}</b>；说不出价 ⇒ 0 = 不挂单（fail-closed）。 */
  @Test
  void limitComesFromTheSamePurchasingPowerAndZeroMeansDoNotQuote() {
    assertThat(HouseholdPurchasingPower.limitPerMille(4L, 5L))
        .as("★ F-4：1000 × 5 ÷ 4 = 1250（毫 quote / 1000 毫 base）")
        .isEqualTo(1_250L);
    assertThat(HouseholdPurchasingPower.limitPerMille(5L, 4L))
        .as("★ 反向 = 800 ⇒ 两种口径可区分（不是恒 1000）")
        .isEqualTo(800L);
    assertThat(HouseholdPurchasingPower.limitPerMille(0L, 5L))
        .as("★ 分母 ≤ 0（免费篮子 / 没有读数）⇒ 0 = 不挂单，绝不猜一个价")
        .isZero();
    assertThat(HouseholdPurchasingPower.limitPerMille(4L, 0L))
        .as("★ 分子 ≤ 0（目标币免费）⇒ 0 = 不挂单")
        .isZero();
  }

  // ── 装配：币种 → 法定区锚格价表（缺锚格市场 ⇒ 说不出价，绝不拿另一币的价表冒充） ──────────────

  /** ★★ <b>F-1 的取价点 = 该币法定区的<b>锚格</b>市场</b>；锚格没有市场 / 计价币与法定币漂开 ⇒ 该币没有价表。 */
  @Test
  void zoneTablesTakeTheAnchorMarketAndRefuseMismatchedNumeraires() {
    Market silverMarket = new Market(SILVER, Map.of(GRAIN, 10L));
    Market copperMarket = new Market(COPPER, Map.of(GRAIN, 5L));
    Map<HexCoord, Market> markets =
        new java.util.LinkedHashMap<>(Map.of(H1, silverMarket, H2, copperMarket));
    MarketTopology topology =
        MarketSettlementFixtures.builder()
            .region("zone-a", H1)
            .region("zone-b", H2)
            .market(H1, silverMarket)
            .market(H2, copperMarket)
            .household(HouseholdId.parse("hh-x"), H1, 1L, 0L, 0L)
            .build()
            .topology();

    Map<CurrencyId, HouseholdPurchasingPower.ZoneTable> tables =
        HouseholdPurchasingPower.zoneTables(markets, topology);

    assertThat(tables.keySet()).as("★ 逐法定币各一张价表（锚格 = 取价点）").contains(SILVER, COPPER);
    assertThat(tables.get(SILVER).anchor()).isEqualTo(H1);
    assertThat(tables.get(COPPER).anchor()).isEqualTo(H2);
    assertThat(tables.get(COPPER).market().priceOf(GRAIN)).as("copper 区锚格的 grain 价").isEqualTo(5L);

    // ★ 锚格市场的计价币与该区法定币漂开 ⇒ 不拿另一币的价表冒充（fail-closed）。
    Map<HexCoord, Market> drifted =
        new java.util.LinkedHashMap<>(Map.of(H1, silverMarket, H2, silverMarket));
    assertThat(HouseholdPurchasingPower.zoneTables(drifted, topology))
        .as("★ 锚格是 silver 价表却挂在 copper 法定区 ⇒ copper 没有价表")
        .doesNotContainKey(COPPER);

    // ★ 家户自己所在区的表优先（"我自己的市场上要付多少"）。
    MarketRegion own = topology.regionOf(H1);
    assertThat(HouseholdPurchasingPower.tableFor(SILVER, own, tables, markets).anchor())
        .isEqualTo(H1);
    assertThat(HouseholdPurchasingPower.tableFor(COPPER, own, tables, markets).anchor())
        .as("★ 自己区不是 copper 区 ⇒ 回落到规范选取（copper 区锚格）")
        .isEqualTo(H2);
  }

  /** ★ 空篮子 ⇒ 空读数（没有需求就没有「付清需求要付多少」⇒ 不换；这也是「未注入 naturalNeeds 的旧世界一个数都不动」的落点）。 */
  @Test
  void anEmptyBasketHasNoReadingAtAll() {
    HouseholdEconomy empty = row(Map.of());
    assertThat(HouseholdPurchasingPower.needCostMilli(empty, table(SILVER, H1, Map.of(GRAIN, 10L))))
        .as("★ 空篮子 ⇒ 没有「付清需求」这件事")
        .isEmpty();
  }
}
