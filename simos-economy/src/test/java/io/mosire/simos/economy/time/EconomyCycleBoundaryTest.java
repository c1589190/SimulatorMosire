package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **周期边界**（v2 spec §八.3 + §3.1）：{@code progressDays == cycleDays} 是**合法值**（闭区间上界），
 * 结算必须把它当"周期已满、待收获"处理，**不是**抛。
 *
 * <p><b>病灶</b>：v1 用 {@code progressed == cycleDays} 判收获、否则 {@code nextProgress = progressed} ⇒
 * 该状态下次日构造出 {@code cycleDays + 1}，在 {@code Industry} 构造期抛 IAE，异常穿出协调器的 {@code simulateWorld} ⇒
 * **整条推进 revision 失败**。
 *
 * <p>★ <b>判别力</b>：把判据改回 {@code ==} ⇒ 本类第一条抛 IAE ⇒ 红。
 *
 * <p>★ 量纲与常数：土地 3,100 亩/格、亩产 67 粮/亩（v2 spec §10.3 定案 A）；1 粮 = 1000 毫粮。
 */
class EconomyCycleBoundaryTest {

  /** 本夹具的格（H0：家户键 = 格 + 居住类型 + 阶层；这里只有一格）。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final CohortKey PEASANT_KEY = new CohortKey(HEX, ResidenceKind.RURAL, PEASANT);
  private static final long CYCLE_DAYS = 120L;
  private static final long LAND_MILLI_MU = 3_100_000L; // 3,100 亩（千分亩）
  private static final long GRAIN_PER_MU = 67L;

  /** ★ R2 的夹具：一格一批人 ⇒ 供给一条、配额一条（{@link #fullCycleFixture} 与 {@link #zeroPopulationFixture} 共用）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-0-farm@0_0");

  /** ★ 创世配额的发放周期（与 {@code EconomySeeder.FIRST_PERIOD} 同值：周期序号从 1 起）。 */
  private static final long FIRST_PERIOD = 1L;

  /** 夹具：一个"周期已满"的产业（{@code progressDays == cycleDays}），一行 100 人， 周期累计劳动已按整周期记满（供收获算平均日劳动）。 */
  private static EconomyFixtures.World fullCycleFixture() {
    long dailyLabor = 58_000L * 950L / 1000L; // 有效劳动 58,000 千分劳动 × 投入率 950‰
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            CYCLE_DAYS,
            CYCLE_DAYS, // ★ 边界值：周期已满（合法，且 v1 会在此炸）
            // ★ R3：产能锚 —— 规模单位 = 1 亩（每 1 亩要 1,000 千分亩）。
            Map.of(AssetKind.LAND, 1_000L),
            // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
            Map.of(AssetKind.LAND, LAND_MILLI_MU),
            Map.of(),
            0L,
            // ★ R3：劳动那一路 = 每亩 143 千分劳动（= ⌈1000/7⌉）—— 它就是旧口径"1 标准劳动经营 7 亩"的倒数形式，
            //   故本文件那串 V2 字面量（388 亩）一分不动（见 aFullCycleHarvestsInsteadOfThrowing 的算式）。
            EconomySettlement.LABOR_MILLI_PER_MU,
            Map.of(EconomySettlement.GRAIN, GRAIN_PER_MU),
            Map.of(), // ★ 一个都不配种子：同上
            List.of(new ClassSlot(PEASANT, "贫农", 950)),
            new AllocationRule.Split(700, 300),
            dailyLabor * CYCLE_DAYS, // 周期累计劳动
            Map.of(),
            // ★ 通用夹具的 operator = **派生**（`feudal` ⇒ `ESTATE:farm@0_0`）：默认值只有一处拼写点。
            RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
    ClassRow row =
        new ClassRow(
            PEASANT_KEY,
            100L,
            58_000L,
            950,
            // ★★ H1（K1）：10,000 粮不再写进行里 —— 它在**会话工作副本**里（见下面成对交出的 goods）。
            0L,
            List.of(),
            Map.of(),
            Map.of());
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, PEASANT_KEY, EconomySettlement.GRAIN, 10_000_000L); // 10,000 粮
    return new EconomyFixtures.World(
        new EconomyData(
            Optional.of(new EconomyMeta("m1", 0L, OptionalLong.empty(), "v2", Optional.empty())),
            Map.of(FARM, farm),
            Map.of(PEASANT_KEY, row),
            Map.of(),
            Map.of(),
            // ★★ R2：当日劳动**取自配额表**（不再从"行 laborMilli × 投入率"算）⇒ 夹具必须发一条 = 该日劳动的配额，
            //   否则 {@code cycledLabor} 为 0 ⇒ 劳动瓶颈算出 0 亩 ⇒ 本文件全部字面量（388 亩那条链）一起变。
            //   ★ 58,000 是**毛额**（供给的上限），55,100 是**这条配额承诺投入的量**（= 58,000 × 950‰）。
            Map.of(LOT, new LaborSupply(LOT, FIRST_PERIOD, 58_000L, 0L, 0L)),
            Map.of(
                ALLOCATION,
                new LaborAllocation(
                    ALLOCATION,
                    LOT,
                    new ActorRef(ActorKind.ESTATE, FARM.value()),
                    "farm",
                    dailyLabor,
                    FIRST_PERIOD)),
            // ★★ **T4：关系表非空** —— 产出自本阶段起不再写进阶层行（R5 ②），行里的实物只能经关系结算的 cohort 入账回来。
            //   本夹具**只有一行有人口**（贫农）⇒ 那条 1000‰ 的劳动分成**逐值等于净产**（own ÷ Σ劳动 = 1）
            //   ⇒ 下面"单行 ⇒ 权重 1000 ⇒ 全部归它"那条账（25,216,120）一字不改。
            EconomyFixtures.laborShareToPeasant(Map.of(FARM, farm))),
        goods);
  }

  @Test
  void aFullCycleHarvestsInsteadOfThrowing() {
    EconomyFixtures.World world = fullCycleFixture();
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    assertThat(next.industries().get(FARM).progressDays()).as("周期已满 ⇒ 收获并归零").isZero();
    assertThat(next.industries().get(FARM).cycleLaborMilli()).as("周期累计清零").isZero();
    assertThat(next.meta().orElseThrow().lastClosedCycle()).as("关账周期序号 1").hasValue(1L);

    // 算账（全部整数，量纲：毫粮）：
    //   日劳动    = 58,000 × 950‰ = 55,100 千分劳动
    //   周期累计  = 55,100 × 120 = 6,612,000；收获当天再 +55,100 ⇒ cycledLabor = 6,667,100
    //   平均日劳动 = 6,667,100 ÷ 120 = 55,559（向下取整）
    //   ★ R3：劳动可经营 = 55,559 ÷ 每亩需劳动 143 = 388 亩（**劳动是最短那块**，地有 3,100 亩）
    //     —— 与旧式 floor(55,559 × 7 ÷ 1000) = 388 逐值相同（143 = ⌈1000/7⌉ 就是那条口径的倒数形式）
    //   毛产      = 388 × 67 × 1000 = 25,996,000 毫粮
    //   生产消耗  = 25,996,000 × (饲料 0‰ + 折旧 30‰) ÷ 1000 = 779,880 ⇒ 净 25,216,120
    //   ★ T4：产出**不再**按 Split 分给行 —— 走 "+净产 → operator" + 关系结算（夹具那条规则把净产全给贫农队
    //     cohort：受方劳动 58,000×950‰ = 55,100 = Σ劳动 ⇒ 实付 = 净产）⇒ 落到**行里**的仍是同一个数
    //   库存      = 10,000,000 − 第 1 天口粮 8,333 + 25,216,120 = 35,207,787
    //   ★ 口粮 = {@link EconomyVocabulary#dailyRationMilli}(100, 1) = floor(100 × 10,000 ÷ 120) =
    // 8,333
    //     （口径 = 每人每 120 天 10 粮，累计口粮的逐日差分；不再是"每人每日 83"）
    assertThat(EconomyFixtures.grainOf(world.goods(), PEASANT_KEY))
        .as("吃一天 + 收获一次后的粮余额（毫粮）—— ★ H1：这条账现在住在**家户账工作副本**里（裁定 K1）")
        .isEqualTo(10_000_000L - EconomyVocabulary.dailyRationMilli(100L, 1L) + 25_216_120L);
  }

  @Test
  void aOneDayCycleHarvestsOnItsVeryFirstDayWithoutDividingByZero() {
    // Review Focus 第 8 条：cycleDays == 1 ⇒ avgLaborMilli = cycledLabor / 1，且当天即满足 progressed >=
    // cycleDays
    EconomyFixtures.World world = withCycleDays(1L);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);
    assertThat(next.industries().get(FARM).progressDays()).as("1 天周期：当天就收获并归零（且不许除零）").isZero();
  }

  @Test
  void aZeroPopulationHexProducesNothingAndDoesNotDivideByZero() {
    // Review Focus 第 3 条：农村人口为 0 的纯城市格（无劳动 ⇒ 投入面积 0 ⇒ 不造粮）
    EconomyFixtures.World world = zeroPopulationFixture();
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);
    // ★ H1：判据不变（"不造粮"），落点从"行里的 goods"换成"该家户的账"——副本里那一张表仍然是空的。
    assertThat(world.goods().getOrDefault(PEASANT_KEY, Map.of()))
        .as("无劳动 ⇒ 投入面积 0 ⇒ 不造粮（也不许除零）")
        .isEmpty();
  }

  /** 换 `cycleDays` 与 `progressDays`（其余照 {@link #fullCycleFixture()}；★ 家户账副本原样带过）。 */
  private static EconomyFixtures.World withCycleDays(long cycleDays) {
    EconomyFixtures.World world = fullCycleFixture();
    EconomyData base = world.data();
    Industry farm = base.industries().get(FARM);
    return new EconomyFixtures.World(
        base.withIndustries(
            Map.of(
                FARM,
                new Industry(
                    farm.id(),
                    farm.name(),
                    farm.regime(),
                    cycleDays,
                    cycleDays,
                    farm.capacityPerUnit(),
                    // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
                    farm.capacity(),
                    farm.dailyInputPerUnit(),
                    farm.dailyLaborPerUnit(),
                    farm.laborPerUnit(),
                    farm.outputPerUnit(),
                    farm.cycleInputPerUnit(),
                    farm.slots(),
                    farm.allocation(),
                    farm.cycleLaborMilli(),
                    farm.cycleInputUsedMilli(),
                    // ★★ **重建点 ⇒ 透传**（不是重新推导）：这个夹具要保留的正是"这一格原来的经营主体"。
                    farm.operator()))),
        world.goods());
  }

  /**
   * 无人口、无劳动、无库存，**且周期累计劳动也为 0** 的格（纯城市格 / 空地）。
   *
   * <p>★ 为什么必须连 {@code cycleLaborMilli}（周期累计劳动）一起清零：**它才是"本周期实际投了多少劳动" 的权威记录**（挂在产业上的累加器），行里的
   * {@code laborMilli} 只喂"当日增量"。 只清行不清累加器 ⇒ 已记为投下的劳动不会被抹掉，照样有产出 —— 那是**正确行为**，不是 bug
   * （本用例第一版就踩了这个前提：期望"无人口 ⇒ 不产粮"，实测产了 31,925,750 毫粮）。
   *
   * <p>★★ **R2 起还要清掉配额与供给**（同一条教训换了机制）：当日劳动现在取自**配额表**，故"把行清空、却留着配额"照样有产出 ——
   * 本条要显式造出的正是这个形态：得清**三处**（行、周期累加器、配额），少一处就测不出"无人口 ⇒ 不产粮"。
   */
  private static EconomyFixtures.World zeroPopulationFixture() {
    EconomyFixtures.World world = fullCycleFixture();
    EconomyData base = world.data();
    Industry farm = base.industries().get(FARM);
    ClassRow row = base.classes().get(PEASANT_KEY);
    // ★★ H1：0 人口的家户**不吃饭、不出工** ⇒ 交付一份**空账**（它可以缺席，这里显式给空表 ——
    //   与改前"行里 goods 为空"逐字对应，"无劳动 ⇒ 不造粮"那条断言才有判别力）。
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, PEASANT_KEY, EconomySettlement.GRAIN, 0L);
    return new EconomyFixtures.World(
        base.withIndustries(
                Map.of(
                    FARM,
                    new Industry(
                        farm.id(),
                        farm.name(),
                        farm.regime(),
                        farm.cycleDays(),
                        farm.progressDays(),
                        farm.capacityPerUnit(),
                        // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
                        farm.capacity(),
                        farm.dailyInputPerUnit(),
                        farm.dailyLaborPerUnit(),
                        farm.laborPerUnit(),
                        farm.outputPerUnit(),
                        farm.cycleInputPerUnit(),
                        farm.slots(),
                        farm.allocation(),
                        0L, // ★ 周期累计劳动清零
                        farm.cycleInputUsedMilli(),
                        // ★★ **重建点 ⇒ 透传**（同上）。
                        farm.operator())))
            .withClasses(
                Map.of(
                    PEASANT_KEY,
                    new ClassRow(
                        PEASANT_KEY,
                        0L, // 人口
                        0L, // 有效劳动
                        row.participationPerMille(),
                        row.money(),
                        row.debts(),
                        row.naturalNeeds(),
                        row.effectiveDemand())))
            // ★ R2：第三处 —— 配额与供给一起清空（见方法注释：三者少一个，"无人口 ⇒ 不产粮"就测不出来）。
            //   ★ **次序有讲究**：先清配额再清供给 —— 反过来会在中间态造出"有配额、没供给"的非法状态，
            //     构造期守卫当场抛（那条守卫是**对的**：没有供给的配额没有上限）。
            .withAllocations(Map.of())
            .withLaborSupply(Map.of()),
        goods);
  }
}
