package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>模块内 0 → 120 tick 纯经济验收探针</b>（用户任务书判据 2）。
 *
 * <p>这是一条<b>最小探针</b>，不是生产 API：它用公开的 {@link EconomyDayStepper} 逐日推进一个一格世界， 跨过一个完整 120
 * 天生产周期，逐日核账、周期末核收获/关系分配/家户账，并检查库存不出现负数。
 *
 * <p>★ <b>市场面</b>：本探针的世界没有市场表 ⇒ 按任务书允许的退化路径使用 {@link
 * MarketTopology#singleHex(java.util.Map)}；故市场“不造粮/钱”由无市场表的空操作承担，不作成交断言。
 */
class EconomyCycle120AcceptanceTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final CohortKey PEASANT_KEY = new CohortKey(HEX, ResidenceKind.RURAL, PEASANT);
  private static final HouseholdId PEASANT_HOUSE = EconomyFixtures.hh(PEASANT_KEY);
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-0-farm@0_0");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ESTATE, FARM.value());

  private static final long CYCLE_DAYS = 120L;
  private static final long POPULATION = 100L;
  private static final long LABOR_MILLI = POPULATION * 580L; // 58,000 千分劳动/日
  private static final long LAND_MILLI_MU = 3_100_000L; // 3,100 亩
  private static final long YIELD_PER_MU = 67L;
  private static final long OPENING_GRAIN_MILLI = 3_000_000L; // 3,000 粮

  @Test
  void oneHexRunsFromTickZeroToTickOneHundredTwentyWithBalancedHouseholdAccount() {
    EconomyFixtures.World world = fixture();
    EconomyData base = world.data();
    AccountSession accounts = EconomyFixtures.accountSession(base, world.goods());
    EconomyDayStepper stepper =
        new EconomyDayStepper(base, accounts, MarketTopology.singleHex(base.markets()));
    long minGrain = Long.MAX_VALUE;
    ProductionLedger closing = null;
    try {
      // 逐日推进：跨过一个完整生产周期（第 120 天关账）。
      for (long day = 1L; day <= CYCLE_DAYS; day++) {
        closing = stepper.step(day);
        Map<CommodityId, Long> balance = accounts.householdAccount(PEASANT_HOUSE).goods();
        assertThat(balance.getOrDefault(GRAIN, 0L))
            .as("第 %d 天粮账不得为负", day)
            .isGreaterThanOrEqualTo(0L);
        minGrain = Math.min(minGrain, balance.getOrDefault(GRAIN, 0L));
      }
    } finally {
      // 异常路径也要收口线程池；正常 finish 也幂等。
      if (closing == null) {
        stepper.close();
      }
    }
    EconomyData after = stepper.finish();

    // ── 周期末：关账序号前进、unit 进度与周期态归零 ────────────────────────────────
    assertThat(closing).as("第 120 天必须关账").isNotNull();
    assertThat(after.meta().orElseThrow().lastClosedCycle())
        .as("周期末 lastClosedCycle 前进到 1")
        .hasValue(1L);
    assertThat(EconomyFixtures.progressDaysOf(after, FARM)).as("产业进度归零").isZero();
    assertThat(EconomyFixtures.cycleLaborOf(after, FARM)).as("周期累计劳动清零").isZero();
    assertThat(EconomyFixtures.unitOf(after, FARM).cycleInputUsedMilli()).as("本周期投入清零").isEmpty();

    // ── 第 120 天收获的闭式账（算式写在断言里，不抄实际值）─────────────────────────
    // 劳动可经营 = ⌊58,000 ÷ 143⌋ = 405 亩 < 土地 3,100 亩 ⇒ 劳动是最紧约束。
    long arableByLabor = LABOR_MILLI / EconomySettlement.LABOR_MILLI_PER_MU;
    long expectedGross = arableByLabor * YIELD_PER_MU * EconomySettlement.MILLI_PER_GRAIN;
    long expectedLoss =
        expectedGross
            * (EconomySettlement.FEED_PER_MILLE + EconomySettlement.DEPRECIATION_PER_MILLE)
            / 1000L;
    long expectedNet = expectedGross - expectedLoss;

    assertThat(closing.grossOf(FARM, GRAIN))
        .as("毛产 = ⌊58,000 ÷ 143⌋ × 67 × 1000")
        .isEqualTo(expectedGross);
    assertThat(closing.lossOf(FARM, GRAIN)).as("生产损耗 = 毛产 × (0‰ + 30‰)").isEqualTo(expectedLoss);
    assertThat(closing.inputOf(FARM, GRAIN)).as("本例不配周期投入").isZero();

    // 产出计提 → operator，关系实付 → 家户；两条腿都在当天账本里对得上。
    long accrualToOperator =
        closing.outputAccruals().stream()
            .filter(entry -> entry.actor().equals(OPERATOR))
            .mapToLong(ProductionSettlement.ActorEntry::delta)
            .sum();
    long paidToHousehold =
        closing.transfers().stream()
            .filter(transfer -> transfer.to().equals(HouseholdActors.of(PEASANT_HOUSE)))
            .flatMap(transfer -> transfer.goods().values().stream())
            .mapToLong(Long::longValue)
            .sum();
    assertThat(accrualToOperator).as("operator 计提 = 净产").isEqualTo(expectedNet);
    assertThat(paidToHousehold).as("关系实付 = 净产全额（只有一户有人口）").isEqualTo(expectedNet);

    // ── 家户粮账：期初 − 一个周期的口粮 + 收获关系实付 ────────────────────────────
    long cycleRation =
        EconomyVocabulary.cumulativeRationMilli(POPULATION, CYCLE_DAYS); // 1,000,000 毫粮
    long finalGrain = accounts.householdAccount(PEASANT_HOUSE).goods().getOrDefault(GRAIN, 0L);
    assertThat(finalGrain)
        .as(
            "家户粮账恒等式：期初 %d − 口粮 %d + 收获 %d = %d",
            OPENING_GRAIN_MILLI,
            cycleRation,
            expectedNet,
            OPENING_GRAIN_MILLI - cycleRation + expectedNet)
        .isEqualTo(OPENING_GRAIN_MILLI - cycleRation + expectedNet);
    assertThat(minGrain).as("120 天里粮账最小余额不为负").isGreaterThanOrEqualTo(0L);

    assertThat(EconomyFixtures.flowOf(after, PEASANT_KEY).income().getOrDefault(GRAIN, 0L))
        .as("流水：本期所得 = 关系实付")
        .isEqualTo(expectedNet);
    assertThat(EconomyFixtures.flowOf(after, PEASANT_KEY).consumed().getOrDefault(GRAIN, 0L))
        .as("流水：本期消费粮 = 一个周期的口粮")
        .isEqualTo(cycleRation);
    assertThat(EconomyFixtures.classOf(after, PEASANT_KEY).population())
        .as("人口不变")
        .isEqualTo(POPULATION);
    assertThat(after.allocations()).as("配额表不被结算改写").isEqualTo(base.allocations());
    assertThat(after.laborSupply()).as("劳动供给表不被结算改写").isEqualTo(base.laborSupply());

    // ── 账户/资产没有负数或凭空蒸发 ───────────────────────────────────────────────
    assertNoNegativeAccountBalances(accounts);
    long finalAssetQuantity =
        after.assetShares().values().stream().mapToLong(share -> share.quantity()).sum();
    assertThat(finalAssetQuantity).as("土地份额总量不变").isEqualTo(LAND_MILLI_MU);

    // 本探针没有市场表 ⇒ 退化路径：没有市场轮次，也就没有成交/运费/价格变化。
    assertThat(stepper.lastMarketReport()).as("无市场表 ⇒ 没有市场报告").isEmpty();
  }

  private static void assertNoNegativeAccountBalances(AccountSession accounts) {
    for (AccountSession.ActorAccount account : accounts.accounts().values()) {
      for (long amount : account.goods().values()) {
        assertThat(amount).as("商品余额不得为负: %s", account.key().canonical()).isGreaterThanOrEqualTo(0L);
      }
      for (long amount : account.money().values()) {
        assertThat(amount).as("货币余额不得为负: %s", account.key().canonical()).isGreaterThanOrEqualTo(0L);
      }
    }
  }

  private static EconomyFixtures.World fixture() {
    Industry farm =
        EconomyFixtures.industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            CYCLE_DAYS,
            0L, // 创世从周期第 0 天开始
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(AssetKind.LAND, LAND_MILLI_MU),
            Map.of(),
            0L,
            EconomySettlement.LABOR_MILLI_PER_MU,
            Map.of(GRAIN, YIELD_PER_MU),
            Map.of(), // 不配种子：本探针量的是劳动瓶颈下的收获
            List.of(new ClassSlot(PEASANT, "贫农", 1000)),
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(),
            RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));

    ClassRow row =
        EconomyFixtures.classRow(
            PEASANT_KEY,
            POPULATION,
            LABOR_MILLI,
            1000,
            0L,
            List.of(),
            Map.of(GRAIN, EconomyVocabulary.dailyRationMilli(POPULATION, 1L)),
            Map.of(),
            0L);
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, PEASANT_KEY, GRAIN, OPENING_GRAIN_MILLI);
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm);
    return new EconomyFixtures.World(
        EconomyFixtures.data(
            Optional.of(new EconomyMeta("m1", 0L, OptionalLong.empty(), "v2", Optional.empty())),
            industries,
            Map.of(PEASANT_KEY, row),
            Map.of(),
            Map.of(),
            Map.of(LOT, new LaborSupply(LOT, 1L, LABOR_MILLI, 0L, 0L)),
            Map.of(
                ALLOCATION,
                new LaborAllocation(
                    ALLOCATION, LOT, PEASANT_HOUSE, OPERATOR, "farm", LABOR_MILLI, 1L)),
            EconomyFixtures.laborShareToPeasant(industries),
            Map.of(),
            Map.of()),
        goods);
  }
}
