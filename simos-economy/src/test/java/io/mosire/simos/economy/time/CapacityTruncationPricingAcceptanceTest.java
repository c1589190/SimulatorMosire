package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划的 V-20 判据（W2b 迁移到 W2 的新定价口径）+ §2.4 M2</b>：
 * `docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md`、约束设计书
 * `2026-10-10-unified-price-and-ordered-settlement-design.md` <b>v3.8 §2.1</b>。
 *
 * <pre>
 * 冻结算式（§2.1）：
 *   D &gt; S（缺货）: r=(D−S)/max(1,S)；step‰=min(α,⌊α·r⌋)；p'=max(p+1, ⌊p×(1000+step)/1000⌋)（无上限）
 *   S &gt; D（滞销）: r=(S−D)/max(1,D)；step‰=min(α,⌊α·r⌋)；p'=max(0, ⌊p×(1000−step)/1000⌋)（触底 0）
 *   D = S:         p' = p                                  （缺省中性）
 * </pre>
 *
 * <p>★★★ <b>本类判据的迁移（旧期望 → 新期望）</b>：V-20 的原始裁定是"被运力截断的部分<b>两侧都剔</b>、不提价不压价"； 设计书 **v3.5 §2.1 的
 * ★★**（控制方裁定，回代码核过）把它改写成一条<b>刻意的不对称</b>：
 *
 * <pre>
 *   D = 全额想要量（含被运力截断的、含"买不起"的）      ← 旧口径：减去 Σ min(挂单量, capacityTruncatedMilli)
 *   S = 只算真能上架的（仍扣运力截断，一字未动）        ← 与旧口径相同
 * </pre>
 *
 * ★ 为什么必须不对称：两侧同扣 ⇒ 被运力/口岸拦下的那部分会**同时**从 D 与 S 里消失 ⇒ 比值不变 ⇒ 价格不动，
 * 与用户「缺货价格应该逐步增加，因为这是市场机制」直接矛盾。缺货压力**只能**从这条不对称里来。
 *
 * <p>★ 因此本类的判据变成：<b>"截断量不进 S，但仍全额留在 D 里"</b>，且价格必须等于 {@code adaptiveNextPrice(p, D全额, S扣截断)} ——
 * 而**不等于**两个错误口径各自会得到的值： ① D 也剔（旧 V-20 的对称口径）② S 不剔（把供给侧的口径改宽）。两个反例都在下面逐值钉住。
 *
 * <p>★ <b>夹具（单区两格：卖方/承运在 H1、唯一的买方在 H2）</b>：跨格需求只有一条，量的算式因此是判据级的（不依赖任何撮合内部实现）：
 *
 * <pre>
 * 卖方挂单      S = 4,000 毫粮           买方缺口（生产花费 = 生活保留）D = 2,915 毫粮（7 人 × 583 毫/天 × 5 tick）
 * 参考价 p = 10,000 毫银/单位，α = 50‰（区表缺省，见 MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE）
 *
 * 无运力：跨格量 min(买余 2,915, 卖余 4,000) = 2,915 整条截断（blockLaneWithoutCapacity 两侧各记 2,915）
 *        ⇒ (D, S) = (2,915, 1,085) ⇒ r = 1,830/1,085 = 1.69 ⇒ step = min(50, 84) = 50 ⇒ p_next = 10,500（**涨满 α**）
 *        ★ 旧口径（D 也剔）会得到 (0, 1,085) ⇒ S &gt; D ⇒ step = 50 ⇒ p_next = 9,500 —— **跌**，与"缺货逐步涨"相反
 *        ★ 若把 S 侧的扣除取消：(2,915, 4,000) ⇒ S &gt; D ⇒ step = ⌊50×1,085/2,915⌋ = 18 ⇒ p_next = 9,820
 * 有运力（运力 ≥ D）：一分钱没截断 ⇒ (D, S) = (2,915, 4,000) ⇒ step = 18 ⇒ p_next = 9,820（= 上面第三个候选）
 * </pre>
 *
 * <p>★ 价格表读两处（同一事实的两个读口，必须一致）：{@code report().priceUpdates()} 与 {@code outcome.marketTables()}
 * （W1：区内成员格**逐格同改** —— 区内任何格取价一致是"每区一张"的结构性必然）。
 *
 * <p>★★ <b>判别力</b>：本类的每个期望都逐值写成算式结果（不是宽区间）。把 §2.1 的 {@code step = min(α, ⌊α·r⌋)} 改成常数 α ⇒ 有运力那一案会变成
 * 10,500（≠ 9,820）⇒ 当场红；把 D 侧也扣一遍（旧口径）⇒ 无运力那一案会落回 9,500（= 本类钉住的 {@code bothSidesExcluded}）⇒ 当场红；把 S
 * 侧的扣除删掉 ⇒ 无运力那一案会变成 9,820 ⇒ 当场红。
 */
class CapacityTruncationPricingAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;

  private static final HouseholdId SELLER = HouseholdId.parse("hh-pricing-seller");
  private static final HouseholdId CROSS_BUYER = HouseholdId.parse("hh-pricing-buyer");
  private static final HouseholdId CARRIER = HouseholdId.parse("hh-pricing-carrier");

  private static final long SELLER_GRAIN = 4_000L;
  private static final long BUYER_SILVER = 1_000_000L;
  private static final long PRICE_MILLI = 10_000L;

  /**
   * P2a：补货窗口从 35 tick 收到 5 tick 后，1 人户的保留缺口只有 415 毫粮，无法再触发"运输截断 vs 正常滞销"的 判别区。把买方提高到 7 人（7 × 583 ×
   * 5 = 2,915 毫粮），保持判据所需的量级；这仍是生活保留算式，不是新规则。
   */
  private static final long BUYER_POPULATION = 7L;

  /**
   * ★ W2：本世界的 α = **出厂缺省** {@value MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}‰/轮 —— 夹具走派生市场 （没有声明
   * {@code MarketZone}）⇒ {@code priceAlphaPerMilleOf} 取缺省，与生产路径同一个读口。
   */
  private static final long ALPHA_PER_MILLE = MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE;

  /**
   * 跨格需求 = 买方的"生产花费"（本户名下没有 unit ⇒ 第一项为空 ⇒ 生产花费 = 生活保留 = 当日需求 × 5 tick）。 ★
   * W2/S-10：家户的买盘目标从"只口粮"改成"生产花费（内含口粮）"；本夹具无 unit ⇒ 两种口径**逐值相同**。
   */
  private static final long CROSS_DEMAND =
      MarketSettlementFixtures.lifeReserveGrain(BUYER_POPULATION);

  /**
   * @param carrierLabor 承运户劳动投入；{@code < 0} ⇒ 世界里<b>没有</b>跑商家户（= 发货格零运力，跨格车道根本不建）
   */
  private static MarketSettlementFixtures.World world(long carrierLabor) {
    MarketSettlementFixtures.Builder builder =
        MarketSettlementFixtures.builder()
            .market(H1, PRICE_MILLI)
            .market(H2, PRICE_MILLI)
            .household(SELLER, H1, 0L, SELLER_GRAIN, 0L)
            .household(CROSS_BUYER, H2, BUYER_POPULATION, 0L, BUYER_SILVER);
    if (carrierLabor >= 0L) {
      // ★ A3（2026-10-10）：工具存量**不再是跑商门槛**（旧 MerchantHaul.TOOL_MILLI_PER_HAUL 已退役）——本夹具只用它
      //   让缺省口径（本世界只给 grain 定价 ⇒ 服务不成市）的派生运力 = 劳动 + 工具**逐值可复算**。
      builder.carrier(CARRIER, H1, carrierLabor, MarketSettlementFixtures.CARRIER_TOOL_MILLI);
    }
    return builder.build();
  }

  private static MarketSettlement.MarketOutcome settle(MarketSettlementFixtures.World world) {
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, world.defaultsOf());
    return MarketSettlementFixtures.settleWithPool(world, round, world.carrierPool());
  }

  private static long nextPriceOf(
      MarketSettlement.MarketOutcome outcome, MarketSettlementFixtures.World world, HexCoord hex) {
    return MarketSettlementFixtures.tableAt(outcome, world, hex)
        .priceOf(MarketSettlementFixtures.GRAIN);
  }

  private static long updateNextOf(MarketSettlement.MarketOutcome outcome) {
    return outcome.report().priceUpdates().stream()
        .filter(u -> u.commodity().equals(MarketSettlementFixtures.GRAIN))
        .findFirst()
        .orElseThrow()
        .nextPriceMilli();
  }

  /**
   * ★★ <b>主判据：截断量不进 S，但仍全额留在 D 里</b> —— 无运力那一案的价格必须等于"用 (D = 全额需求, S = 挂单 − 被截断量)
   * 算出来的那个数"，且**不等于**两个错误口径各自会得到的值。
   *
   * <p>★ 本用例里 α = 50‰ 是**区表缺省**（夹具走派生市场 ⇒ 没有 {@code MarketZone} 可挂 α）—— 与生产路径同一条 {@code
   * priceAlphaPerMilleOf} 读口（没有区 ⇒ 出厂缺省）。
   */
  @Test
  void truncatedCrossHexQuantityStaysInDemandButNeverEntersSupply() {
    // ── 案 1：发货格没有运力 ⇒ 跨格量整条截断（两侧各记 2,915；新口径下 D 不剔、S 剔）──────────────
    MarketSettlementFixtures.World noCapacity = world(-1L);
    MarketSettlement.MarketOutcome noCapacityOutcome = settle(noCapacity);

    assertThat(noCapacityOutcome.report().fills()).as("无运力 ⇒ 一笔都不成交（不是「少卖一点」）").isEmpty();
    assertThat(noCapacityOutcome.report().unfilledReasonCounts())
        .as("具名 LOGISTICS_CAPACITY（不是口岸/币种/预算）")
        .containsKey(MarketUnfilledReason.LOGISTICS_CAPACITY);
    assertThat(noCapacityOutcome.report().sellerOutcomes())
        .as("卖方挂单读数（本用例两侧量的算式都从读数取，不从代码推）")
        .hasSize(1);
    long offered = noCapacityOutcome.report().sellerOutcomes().get(0).offeredQty();
    long demand = noCapacityOutcome.report().buyerOutcomes().get(0).orderedQty();
    assertThat(offered).as("卖方挂单量（供给侧算式的原料）").isEqualTo(SELLER_GRAIN);
    assertThat(demand).as("买方缺口（需求侧算式的原料 D；本夹具它的算式 = 生产花费 = 生活保留 5 tick）").isEqualTo(CROSS_DEMAND);
    assertThat(demand).as("★ 有缺口才可能截断（否则本用例测不到东西）").isPositive();
    assertThat(offered).as("★ 供给足够（截断与「供给不足」可区分）").isGreaterThan(demand);

    long truncated = demand; // 跨格量 = min(买余, 卖余) = demand（供给足够）⇒ 供给侧剔这么多（D 侧不剔）
    // ★ 三个候选值：正确的（D 全额 / S 扣截断）与两个错误口径（D 也剔 / S 不剔）。
    long expectedNoCapacity =
        MarketSettlement.adaptiveNextPrice(
            PRICE_MILLI, demand, offered - truncated, ALPHA_PER_MILLE);
    long bothSidesExcluded =
        MarketSettlement.adaptiveNextPrice(PRICE_MILLI, 0L, offered - truncated, ALPHA_PER_MILLE);
    long supplyNotExcluded =
        MarketSettlement.adaptiveNextPrice(PRICE_MILLI, demand, offered, ALPHA_PER_MILLE);

    assertThat(bothSidesExcluded)
        .as("★ 旧 V-20 口径（D 也剔光）= 9,500：S=1,085 > D=0 ⇒ step=50 ⇒ ⌊10,000×950/1000⌋（**跌**）")
        .isEqualTo(9_500L);
    assertThat(supplyNotExcluded)
        .as("★ S 不剔 = 9,820：S=4,000 > D=2,915 ⇒ step=⌊50×1,085/2,915⌋=18 ⇒ ⌊10,000×982/1000⌋")
        .isEqualTo(9_820L);
    assertThat(expectedNoCapacity)
        .as(
            "★ 新口径（D 全额、S 扣截断）= 10,500：D=2,915 > S=1,085 ⇒ r=1,830/1,085=1.69 ⇒ step=min(50, 84)=50 ⇒ 涨满 α")
        .isEqualTo(10_500L);
    assertThat(java.util.List.of(expectedNoCapacity, bothSidesExcluded, supplyNotExcluded))
        .as("★ 三个候选值互不相等 ⇒ 本用例对两个口径方向都有判别力（否则是等价存活）")
        .doesNotHaveDuplicates();

    assertThat(nextPriceOf(noCapacityOutcome, noCapacity, H1))
        .as("★ 缺货压力来自「D 全额 / S 只算能上架的」这条不对称 ⇒ 价格**涨**（不是旧口径的跌到 9,500）")
        .isEqualTo(expectedNoCapacity)
        .isNotEqualTo(bothSidesExcluded)
        .isNotEqualTo(supplyNotExcluded);
    assertThat(updateNextOf(noCapacityOutcome)).as("两个读口一致（区内一张表）").isEqualTo(expectedNoCapacity);
    assertThat(nextPriceOf(noCapacityOutcome, noCapacity, H2))
        .as("区内成员格同改")
        .isEqualTo(expectedNoCapacity);
    assertThat(noCapacityOutcome.report().priceUpdates().get(0).previousPriceMilli())
        .as("改价记录带改前值")
        .isEqualTo(PRICE_MILLI);

    // ── 案 2：有运力（运力 ≥ D）⇒ 一分钱没截断 ⇒ S = 原始挂单 ────────────────────────────────
    MarketSettlementFixtures.World withCapacity = world(10_000L);
    MarketSettlement.MarketOutcome withCapacityOutcome = settle(withCapacity);

    assertThat(withCapacityOutcome.report().fills()).as("有运力 ⇒ 跨格成交发生").hasSize(1);
    assertThat(withCapacityOutcome.report().fills().get(0).quantity())
        .as("★ 有运力那一案把整笔需求运走 ⇒ 无截断（两案只差「运力够不够」这一个事实）")
        .isEqualTo(demand);
    assertThat(withCapacityOutcome.report().unfilledReasonCounts())
        .as("★ 有运力 ⇒ 不许报成运力不足")
        .doesNotContainKey(MarketUnfilledReason.LOGISTICS_CAPACITY);

    long expectedWithCapacity =
        MarketSettlement.adaptiveNextPrice(PRICE_MILLI, demand, offered, ALPHA_PER_MILLE);
    assertThat(expectedWithCapacity)
        .as("★ 没截断 ⇒ (D, S) = (2,915, 4,000) ⇒ 滞销 ⇒ step=18 ⇒ 9,820（= 案 1 的「S 不剔」候选：同一口径）")
        .isEqualTo(9_820L)
        .isEqualTo(supplyNotExcluded);
    assertThat(nextPriceOf(withCapacityOutcome, withCapacity, H1))
        .as("★ 对照案：没截断 ⇒ 统计就是订单本身（价格 = 9,820）")
        .isEqualTo(expectedWithCapacity);
    assertThat(nextPriceOf(withCapacityOutcome, withCapacity, H1))
        .as("★ 两案的价格**逐值不同** ⇒ 本用例真的在测「截断有没有进价格统计」")
        .isNotEqualTo(nextPriceOf(noCapacityOutcome, noCapacity, H1));
  }

  /**
   * ★★ <b>"截断量不进 S"的同一案内可分辨</b>：世界里另有一个<b>同格的</b>买方（不需要运力、真的成交），于是 D 侧的量不再为 0、缺口倍数不再打满
   * α，**供给侧的剔除因此在价格上可见**。
   *
   * <pre>
   * 卖方 S（H1）挂 10,000；本地买方 L（H1）缺口 2,915（同格成交，不受运力影响）
   * 跨格买方 X（H2）缺口 2,915；发货格 H1 无运力 ⇒ X 的跨格需求整条不进 S（但仍在 D 里）
   * ⇒ 正确口径 (D, S) = (5,830, 10,000 − 2,915 = 7,085) ⇒ step = ⌊50×1,255/5,830⌋ = 10 ⇒ 9,900
   *   D 也剔（旧 V-20 对称口径） ⇒ (2,915, 7,085) ⇒ step = min(50, ⌊50×4,170/2,915⌋=71) = 50 ⇒ 9,500
   *   S 不剔                   ⇒ (5,830, 10,000) ⇒ step = ⌊50×4,170/5,830⌋ = 35 ⇒ 9,650
   * ⇒ 三个数互不相等 ⇒「D 全额 / S 扣截断」两侧在同一案内都可分辨
   * </pre>
   */
  @Test
  void theSupplySideExcludesTheTruncatedPartWhileDemandKeepsIt() {
    HouseholdId localBuyer = HouseholdId.parse("hh-pricing-local-buyer");
    long sellerGrain = 10_000L;
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, PRICE_MILLI)
            .market(H2, PRICE_MILLI)
            .household(SELLER, H1, 0L, sellerGrain, 0L)
            .household(localBuyer, H1, BUYER_POPULATION, 0L, BUYER_SILVER)
            .household(CROSS_BUYER, H2, BUYER_POPULATION, 0L, BUYER_SILVER)
            .build();
    MarketSettlement.MarketOutcome outcome = settle(world);

    long offered = outcome.report().sellerOutcomes().get(0).offeredQty();
    long localFilled =
        outcome.report().fills().stream()
            .filter(f -> f.to().equals(H1))
            .mapToLong(MarketReport.Fill::quantity)
            .sum();
    long crossOrdered =
        outcome.report().buyerOutcomes().stream()
            .filter(o -> o.hex().equals(H2))
            .mapToLong(MarketReport.BuyerOutcome::orderedQty)
            .sum();
    assertThat(offered).as("卖方挂单量（S 的原料）").isEqualTo(sellerGrain);
    assertThat(localFilled).as("★ 同格买方被完整服务（它的需求不是「被截断的部分」⇒ 必须留在统计里）").isEqualTo(CROSS_DEMAND);
    assertThat(crossOrdered).as("★ 跨格买方缺口（= 该运走的那份 = 供给侧要剔掉的量；它仍全额在 D 里）").isEqualTo(CROSS_DEMAND);
    assertThat(outcome.report().fills()).as("跨格那笔一笔都不成交（同格那笔照常）").hasSize(1);

    long correct =
        MarketSettlement.adaptiveNextPrice(
            PRICE_MILLI, localFilled + crossOrdered, offered - crossOrdered, ALPHA_PER_MILLE);
    long demandAlsoExcluded =
        MarketSettlement.adaptiveNextPrice(
            PRICE_MILLI, localFilled, offered - crossOrdered, ALPHA_PER_MILLE);
    long supplyNotExcluded =
        MarketSettlement.adaptiveNextPrice(
            PRICE_MILLI, localFilled + crossOrdered, offered, ALPHA_PER_MILLE);
    assertThat(correct)
        .as(
            "★ 正确口径 = 9,900：(D, S) = (5,830, 7,085) ⇒ step = ⌊50×1,255/5,830⌋ = 10 ⇒ ⌊10,000×990/1000⌋")
        .isEqualTo(9_900L);
    assertThat(demandAlsoExcluded)
        .as("★ D 也剔（旧对称口径）= 9,500：(2,915, 7,085) ⇒ step 打满 α")
        .isEqualTo(9_500L);
    assertThat(supplyNotExcluded)
        .as("★ S 不剔 = 9,650：(5,830, 10,000) ⇒ step = ⌊50×4,170/5,830⌋ = 35")
        .isEqualTo(9_650L);
    assertThat(java.util.List.of(correct, demandAlsoExcluded, supplyNotExcluded))
        .as("★ 三个候选值互不相等 ⇒「D 全额 / S 扣截断」两侧都可分辨（否则本用例是等价存活）")
        .doesNotHaveDuplicates();

    assertThat(nextPriceOf(outcome, world, H1))
        .as("★ 价格表 = D 全额 + 供给侧剔掉被截断那一份之后算出的值")
        .isEqualTo(correct)
        .isNotEqualTo(demandAlsoExcluded)
        .isNotEqualTo(supplyNotExcluded);
  }
}
