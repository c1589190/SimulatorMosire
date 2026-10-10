package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.time.MerchantCapacityPool.CarrierAllocation;
import io.mosire.simos.economy.time.MerchantCapacityPool.CarrierChoice;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5 判据 ⑦（运力池）+ §2.4 M0/M0b/M0c/M2/M3 的冻结口径</b>：
 *
 * <pre>
 * 派生运力    = 劳动 + 工具（MerchantCapacity；**不再有商号行 / 不再 ±5 自增长**）
 * 分配序      = 报价口径按**限价升序**（买方从最低价起买）→ 同价按**市场议价权**（本格占比‰降序）→ 家户 id 升序（I7）
 * 不变量      = **分配总量 ≤ 运力总量**（A3 退化式）；未分到的部分 unallocated（K-4/Q-27：不成交、不成债、不计价）
 * 门槛        = 手里工具不够一趟 ⇒ 该次跑商**不成立**（具名，绝不静默跳过）
 * </pre>
 *
 * <p>★ 本类直测唯一拼写点 {@link MerchantCapacityPool}（不复制算式、不起整个市场轮）：池的成员判据是 "家户的<b>有效位置</b>里含 merchant
 * 生产方式的位置"（M-A1/M0c：商号行退役后运力是派生量）。 端到端的截断/归因由 {@code MarketSettlement} 的用例承担。
 */
class MerchantCapacityAcceptanceTest {

  private static final HexCoord H1 = MarketSettlementFixtures.H1;
  private static final HexCoord H2 = MarketSettlementFixtures.H2;
  private static final HexCoord FAR = new HexCoord(9, 9);

  private static final CommodityId TOOL = MerchantCapacityPool.TOOL_COMMODITY;

  private static HouseholdId hh(String id) {
    return HouseholdId.parse(id);
  }

  /** ★★ <b>M0/M0c：运力 = 劳动 + 工具（派生量），tier / 半径由规模派生</b>——不再有"商号行的 capacityPerRound"。 */
  @Test
  void capacityIsDerivedFromLaborPlusToolAndTierFollowsScale() {
    MerchantCapacity small = MerchantCapacity.of(hh("hh-a"), H1, 700L, 300L);
    assertThat(small.capacityMilli()).as("★ 派生式：运力 = 劳动 + 工具").isEqualTo(1_000L);
    assertThat(small.tier()).as("小规模 = 脚夫").isEqualTo(MerchantPolicy.MerchantTier.PORTER);

    MerchantCapacity large = MerchantCapacity.of(hh("hh-b"), H1, 300_000L, 200_000L);
    assertThat(large.capacityMilli()).isEqualTo(500_000L);
    assertThat(large.tier()).as("★ tier 只是派生读数（按规模分档）").isNotEqualTo(small.tier());
    assertThat(large.serviceRadiusHex())
        .as("★ 半径由规模派生（不是外生常量）")
        .isGreaterThan(small.serviceRadiusHex());
    assertThat(MerchantCapacity.of(hh("hh-c"), H1, 0L, 0L).capacityMilli())
        .as("零投入 ⇒ 零运力（不进池）")
        .isZero();
  }

  /** 池成员判据：有效位置含 merchant 生产方式；不是跑商的富户不进池（不许把"有钱"当运力）。 */
  @Test
  void onlyHouseholdsThatPickedMerchantPositionsEnterThePool() {
    HouseholdId merchant = hh("hh-merchant");
    HouseholdId richNonMerchant = hh("hh-rich");
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(merchant, row(merchant, H1, 5_000L));
    rows.put(richNonMerchant, row(richNonMerchant, H1, 5_000L));
    Map<HouseholdId, Map<CommodityId, Long>> goods =
        Map.of(merchant, Map.of(TOOL, 5_000L), richNonMerchant, Map.of(TOOL, 5_000L));

    MerchantCapacityPool pool =
        MerchantCapacityPool.of(
            Map.of(merchant, merchantStanding(merchant, true)),
            Map.of(merchantPosition(merchant), merchantRole(merchant)),
            rows,
            goods);

    assertThat(pool.householdIds()).as("★ 只有选了跑商的家户进池").containsExactly(merchant);
    assertThat(pool.totalCapacityAt(H1)).isEqualTo(10_000L);
    assertThat(pool.totalCapacityAt(H2)).as("另一格没有跑商家户 ⇒ 零运力").isZero();
  }

  /** ★★ <b>M2：分配总量 ≤ 运力总量</b>（A3 退化式）+ 未分到的部分是 {@code unallocated}（不成交、不成债、不计价）。 */
  @Test
  void totalAllocationNeverExceedsTotalCapacityAndResidualIsReported() {
    HouseholdId big = hh("hh-big");
    HouseholdId small = hh("hh-small");
    MerchantCapacityPool pool = twoCarrierPool(big, small);

    long totalCapacity = pool.totalCapacityAt(H1);
    assertThat(totalCapacity).as("两位跑商 = 6,000 + 2,000").isEqualTo(8_000L);

    CarrierAllocation allocation = pool.select(H1, H2, 100_000L, 1_000L);

    long allocated = allocation.choices().stream().mapToLong(CarrierChoice::quantityMilli).sum();
    assertThat(allocated).as("★ 分配总量 ≤ 运力总量").isLessThanOrEqualTo(totalCapacity);
    assertThat(allocated).as("★ 运力被吃干净（需求远大于运力）").isEqualTo(totalCapacity);
    assertThat(allocated + allocation.unallocatedMilli())
        .as("★ 不变量：Σ choices + unallocated == requested（不静默丢）")
        .isEqualTo(allocation.requestedMilli())
        .isEqualTo(100_000L);
    assertThat(allocation.unallocatedMilli()).as("★ 未运走的部分具名（Q-27：不成交、不成债、不计价）").isEqualTo(92_000L);
    // ★ 池的剩余运力被就地扣减：第二笔请求拿不到任何东西。
    assertThat(pool.remainingCapacityAt(H1)).as("第一笔把本格运力用尽").isZero();
    assertThat(pool.select(H1, H2, 500L, 1_000L).choices()).as("★ 用尽后零分配（不许超发）").isEmpty();
  }

  /** ★★ <b>M2/Q-9：分配序 = 议价权（本格运力占比‰）降序 → 家户 id 升序</b>（缺省口径；I7 内容的纯函数）。 */
  @Test
  void allocationOrderIsMarketShareThenCanonicalHouseholdId() {
    HouseholdId big = hh("hh-big");
    HouseholdId small = hh("hh-small");
    MerchantCapacityPool pool = twoCarrierPool(big, small);

    CarrierAllocation allocation = pool.select(H1, H2, 8_000L, 1_000L);

    assertThat(allocation.choices()).as("两条分配").hasSize(2);
    assertThat(allocation.choices().get(0).household())
        .as("★ 议价权大者先被吃（6000/8000 = 750‰ > 2000/8000 = 250‰）")
        .isEqualTo(big);
    assertThat(allocation.choices().get(1).household()).isEqualTo(small);
    // 占比‰ = 该户运力 ÷ 本格运力总量（6000/8000 = 750‰、2000/8000 = 250‰）——它是分配序的第一键。
    assertThat(pool.totalCapacityAt(H1)).isEqualTo(8_000L);
    assertThat(allocation.choices().get(0).consumedWorkMilli() * 250L)
        .as("大者的分配占比 = 750‰ ⇒ 它拿到的量是小的三倍（Q-9 的口径）")
        .isEqualTo(allocation.choices().get(1).consumedWorkMilli() * 750L);
  }

  /** ★★ <b>M-A2/K-2/Q-25：有报价时"价格管买方的选择、议价权管稀缺分配"</b>——限价低者先被吃， 即使它的议价权（规模占比）更小；同价时才回到议价权序。 */
  @Test
  void lowestAskIsServedFirstWhenProvidersPostQuotes() {
    HouseholdId big = hh("hh-big");
    HouseholdId small = hh("hh-small");
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(big, row(big, H1, 6_000L));
    rows.put(small, row(small, H1, 2_000L));
    Map<HouseholdId, Map<CommodityId, Long>> goods =
        Map.of(big, Map.of(TOOL, 6_000L), small, Map.of(TOOL, 2_000L));
    MerchantCapacityPool pool =
        MerchantCapacityPool.of(
            Map.of(big, merchantStanding(big, true), small, merchantStanding(small, true)),
            Map.of(
                merchantPosition(big),
                merchantRole(big),
                merchantPosition(small),
                merchantRole(small)),
            rows,
            goods,
            CapacityQuoteBook.selfQuoted(Map.of(small, 0L, big, 500L)));

    CarrierAllocation allocation = pool.select(H1, H2, 6_000L, 1_000L);

    assertThat(pool.isPriced()).as("有报价 ⇒ 池走报价口径").isTrue();
    assertThat(allocation.choices()).hasSize(2);
    assertThat(allocation.choices().get(0).household()).as("★ 限价低者先被吃（价格管选择）").isEqualTo(small);
    assertThat(allocation.choices().get(0).quantityMilli())
        .as("★ 先把它吃满（4000）再轮到贵的")
        .isEqualTo(4_000L);
    assertThat(allocation.choices().get(1).household()).isEqualTo(big);
    assertThat(allocation.choices().get(1).quantityMilli()).isEqualTo(2_000L);
  }

  /**
   * ★★ <b>M0b/K-4：缺工具 ⇒ 该次跑商不成立</b>（门槛，不是按量计的费）：工具不足一趟的跑商家户一条分配都不产生； 分配总量的上界因此<b>小于</b>账面运力（这正是"分配
   * ≤ 运力"的另一面）。
   */
  @Test
  void aCarrierWithoutEnoughToolForOneRunProducesNoAllocation() {
    HouseholdId poor = hh("hh-poor-tool");
    Map<HouseholdId, HouseholdEconomy> rows = Map.of(poor, row(poor, H1, 5_000L));
    Map<HouseholdId, Map<CommodityId, Long>> goods =
        Map.of(poor, Map.of(TOOL, MerchantHaul.TOOL_MILLI_PER_HAUL - 1L));
    MerchantCapacityPool pool =
        MerchantCapacityPool.of(
            Map.of(poor, merchantStanding(poor, true)),
            Map.of(merchantPosition(poor), merchantRole(poor)),
            rows,
            goods);

    assertThat(pool.totalCapacityAt(H1))
        .as("账面运力仍然算得出来（= 劳动 + 工具，运力是派生量）")
        .isEqualTo(5_000L + MerchantHaul.TOOL_MILLI_PER_HAUL - 1L);
    CarrierAllocation allocation = pool.select(H1, H2, 1_000L, 1_000L);
    assertThat(allocation.choices()).as("★ 工具不够一趟 ⇒ 该次跑商不成立（绝不放行）").isEmpty();
    assertThat(allocation.unallocatedMilli()).as("全部转成「未获服务」").isEqualTo(1_000L);
    assertThat(pool.toolBlockedRuns()).as("★ 具名计数（不静默跳过）").isEqualTo(1L);

    // 补足一份工具 ⇒ 同一用例立刻可跑（证明门槛判的是"够不够一趟"，不是别的）。
    HouseholdId ok = hh("hh-poor-tool");
    MerchantCapacityPool funded =
        MerchantCapacityPool.of(
            Map.of(ok, merchantStanding(ok, true)),
            Map.of(merchantPosition(ok), merchantRole(ok)),
            rows,
            Map.of(ok, Map.of(TOOL, MerchantHaul.TOOL_MILLI_PER_HAUL)));
    assertThat(funded.select(H1, H2, 1_000L, 1_000L).choices()).hasSize(1);
  }

  /** ★★ <b>半径由规模派生 ⇒ 超出半径的格拿不到运力</b>（"运力到不了的地方不是价格问题，是没有承运"）。 */
  @Test
  void lanesBeyondTheDerivedRadiusGetNoAllocation() {
    HouseholdId porter = hh("hh-porter");
    Map<HouseholdId, HouseholdEconomy> rows = Map.of(porter, row(porter, H1, 1_000L));
    MerchantCapacityPool pool =
        MerchantCapacityPool.of(
            Map.of(porter, merchantStanding(porter, true)),
            Map.of(merchantPosition(porter), merchantRole(porter)),
            rows,
            Map.of(porter, Map.of(TOOL, 10_000L)));

    assertThat(pool.select(H1, H2, 500L, 1_000L).choices()).as("半径内（1 格）可承运").hasSize(1);
    assertThat(pool.select(H1, FAR, 500L, 1_000L).choices()).as("★ 超出派生半径 ⇒ 零分配").isEmpty();
  }

  /** 端到端：运力截断真的落成"成交毛量 ≤ 运力"（不是只改读数）——跨 hex 成交被喂给 {code select} 的那一份上限。 */
  @Test
  void endToEndFillIsCappedByTheHexCapacityPool() {
    HouseholdId seller = hh("hh-seller");
    HouseholdId buyer = hh("hh-buyer");
    HouseholdId carrier = hh("hh-carrier");
    // 承运户运力故意压到很小（劳动 2,000 + 工具 0）⇒ 跨格成交被截断，剩余记 LOGISTICS_CAPACITY。
    MarketSettlementFixtures.World world =
        MarketSettlementFixtures.builder()
            .market(H1, 10L)
            .market(H2, 10L)
            .household(seller, H1, 1L, 200_000L, 0L)
            .household(buyer, H2, 1L, 0L, 1_000_000L)
            .carrier(carrier, H1, 1_500L, 1_000L)
            .build();
    // ★ 运力要**在开市前**读：本轮跑商会把承运户的工具烧掉（M-C 一次性消耗），开市后池里的数已经不是本轮的运力上界。
    long capacity = world.carrierPool().totalCapacityAt(H1);
    MarketSettlementFixtures.Round round =
        MarketSettlementFixtures.round(world, MarketRegulation.defaultsFor(world.markets()));
    MarketSettlement.MarketOutcome outcome =
        MarketSettlementFixtures.settleWithCarriers(world, round);
    assertThat(capacity).as("派生运力 = 劳动 1500 + 工具 1000").isEqualTo(2_500L);
    assertThat(outcome.report().fills()).as("运力不足以整车 ⇒ 仍有一笔（按运力截断）").hasSize(1);
    assertThat(outcome.report().fills().get(0).quantity())
        .as("★ 需求（生活保留缺口）大于运力 ⇒ 截断真的发生了（否则本用例测不到东西）")
        .isLessThan(MarketSettlementFixtures.lifeReserveGrain(1L));
    assertThat(outcome.report().fills().get(0).quantity())
        .as("★ 成交毛量 ≤ 本格运力总量（分配 ≤ 运力）")
        .isLessThanOrEqualTo(capacity);
    assertThat(outcome.report().unfilledReasonCounts())
        .as("★ 没运走的那一份具名 LOGISTICS_CAPACITY")
        .containsKey(io.mosire.simos.economy.api.market.MarketUnfilledReason.LOGISTICS_CAPACITY);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  private static HouseholdEconomy row(HouseholdId id, HexCoord hex, long laborMilli) {
    return new HouseholdEconomy(
        id,
        new io.mosire.simos.economy.api.cohort.CohortKey(
            hex,
            io.mosire.simos.economy.api.cohort.ResidenceKind.RURAL,
            new io.mosire.simos.economy.api.id.SocialClassId("artisan")),
        0L,
        laborMilli,
        1_000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static ClassPositionId merchantPosition(HouseholdId id) {
    return ClassPositionId.parse("merchant:" + id.value());
  }

  private static ProductionRole merchantRole(HouseholdId id) {
    return new ProductionRole(
        merchantPosition(id),
        DefaultProductionModes.MERCHANT,
        "跑商",
        ProductionRole.RelationToMeans.MIXED,
        ProductionRole.LaborRole.ORGANIZER,
        ProductionRole.SurplusRole.SURPLUS_RECEIVER,
        Map.of());
  }

  /** 主业 = 副业 = 跑商位置（纯商号；M-A1 的池成员判据只看有效位置里有没有 merchant）。 */
  private static HouseholdClassMembership merchantStanding(HouseholdId id, boolean pure) {
    ClassPositionId position = merchantPosition(id);
    return new HouseholdClassMembership(
        id, position, position, java.util.Set.of(), Map.of(), 0L, 0L, pure ? "pure" : "side");
  }

  /** 两位跑商家户：big = 6,000（劳动 5,000 + 工具 1,000）、small = 2,000（劳动 1,000 + 工具 1,000）。 */
  private static MerchantCapacityPool twoCarrierPool(HouseholdId big, HouseholdId small) {
    Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    rows.put(big, row(big, H1, 5_000L));
    rows.put(small, row(small, H1, 1_000L));
    Map<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
    goods.put(big, Map.of(TOOL, 1_000L));
    goods.put(small, Map.of(TOOL, 1_000L));
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>();
    positions.put(merchantPosition(big), merchantRole(big));
    positions.put(merchantPosition(small), merchantRole(small));
    Map<HouseholdId, HouseholdClassMembership> standings = new LinkedHashMap<>();
    standings.put(big, merchantStanding(big, true));
    standings.put(small, merchantStanding(small, true));
    return MerchantCapacityPool.of(standings, positions, rows, goods);
  }
}
