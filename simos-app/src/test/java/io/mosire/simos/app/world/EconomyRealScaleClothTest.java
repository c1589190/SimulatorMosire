package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R3（T4/T5）：城乡各有一个非土地生产，且它们在**真档量级**上真的产出东西**。
 *
 * <p>★★ **为什么必须在真档量级上验**（与 {@code EconomyRealScaleSeedBottleneckTest} 同一条理由）：5 格夹具是 1,000 人/格的，
 * 而**织机数、作坊数、纤维量**都是按人口/亩数派生的（{@code EconomySeeder} 的场景参数）⇒ 小夹具上的数字证明不了真档的任何事。
 * 本文件用**真播种器载荷**（{@link EconomySeeder#payload} → 真 {@link EconomySeedHandler}）造一格 14,806 人的平原格 +
 * 一座城， 再跑真结算**一年**（365 天 = 3 个周期）。
 *
 * <pre>
 * 农村那一池（14,806 人）：
 *   毛劳动 = 14,806 × 580‰ = 8,587,480；该池日劳动 = Σ(行劳动 × 槽位投入率) = **7,385,372**
 *     （450/350/150/50 → 6,663/5,182/2,221/740 人 × 580 × 950/900/750/100‰ —— 与改口径前逐值相同）
 *   R3 拆成：农业 900‰ = 6,646,835；家庭纺织 100‰ = **738,537**
 *   三路瓶颈：织机 = 14,806 ÷ 20 = **740**；劳动 = 738,537 ÷ 1000 = **738**；
 *             纤维 = 本格农田一个周期的纤维副产 = 3,098 亩 × 6 × 1000 = 18,588,000 毫 ⇒ ÷ 30,000 = **619**（最紧）
 *   ⇒ 纺织规模 = min(740, 738, 619) = **619** ⇒ 布毛产 619 × 30 × 1000 = 18,570,000（净 18,012,900）
 * 城市那一池（1,777 人）：作坊 = 1,777 ÷ 50 = **35** 座；劳动可开 886 座（不是瓶颈）；原料够 35 座
 *   ⇒ 布毛产 35 × 60 × 1000 = 2,100,000（净 2,037,000）、工具毛产 35 × 5 × 1000 = 175,000（净 169,750）
 * 一年后的布库存 = 18,012,900 + 2,037,000 = **20,049,900**
 * </pre>
 *
 * <p>★★ **一年只跑得起一个周期**（**如实记，不是 bug**）：织机与作坊吃的那份原料是**创世一次性给的**（= 本格农田一个周期的
 * 纤维副产），而"把农田的纤维搬到织机上"是**跨行的实物转移** —— 那正是 spec §六 V8（统一转移）的活（brief 明说"不建议本轮做跨行实物转移"）。 ⇒ 第 2
 * 个周期起织机与作坊都没有原料、停工；而农田自己产的纤维照常累积在农业行里（读口看得见）。本文件把这条**逐值钉住**。
 *
 * <p>★ **本轮"最紧约束"在真档上真的被走到了**：纺织那一路的最紧者是**纤维**（619 &lt; 织机 740 &lt; 劳动 738 之上）—— 三路各不相同、量级接近，只有真正按
 * {@code min} 归一才给得出这个数。
 */
class EconomyRealScaleClothTest {

  /** 真档每格人口（11,830,000 ÷ 799，与 {@code EconomyRealScaleSeedBottleneckTest} 同口径）。 */
  private static final long POPULATION_PER_HEX = 11_830_000L / 799L;

  /** 该格的城市人口（城市占总人口约 12%，取真档报告里的量级）。 */
  private static final long CITY_POPULATION = 1_777L;

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String MAP_ID = "econ-cloth";

  private static final IndustryId FARM = IndustryHexKeys.id(EconomySeeder.FARM, 0, 0);
  private static final IndustryId WEAVE = IndustryHexKeys.id(EconomySeeder.WEAVE, 0, 0);
  private static final IndustryId CRAFT = IndustryHexKeys.id(EconomySeeder.CRAFT, 0, 0);

  /** 一年的天数（本仓日制、不引入闰年，与 {@code PopulationSeeder} 同口径）。 */
  private static final long YEAR_DAYS = 365L;

  /**
   * 一格平原（真档人口）+ 一座城（1,777 人），**真播种器载荷**经真 handler 落成状态。
   *
   * <p>★ 城市人口**不从农村人口里扣**：{@code SettlementPlan} 的两池是分开的（与 {@code PopulationSeeder} 的语义一致）。
   */
  private static EconomyData seeded() {
    SettlementPlan plan =
        new SettlementPlan(
            Map.of(HEX, POPULATION_PER_HEX),
            List.of(
                new PlannedCity(
                    "c-0_0",
                    "c-0_0",
                    HEX,
                    PlannedCity.TIER_TOWN,
                    CITY_POPULATION,
                    1,
                    0.0,
                    1.0,
                    1.0,
                    "test")),
            Map.of(),
            250L,
            0L);
    String payload =
        EconomySeeder.payload(MAP_ID, PopulationSeeder.groups(plan, 0L), at -> "plains");
    SimulationState emptyState =
        new SimulationState(
            stateMeta(), snapshots(EconomyData.empty()), InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(emptyState, payload);
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    return EconomyChangeSet.apply(
        (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());
  }

  // ── ① 农村：家庭纺织拿到非零配额，且真的织出布 ────────────────────────────────────────

  /**
   * ★★ **本轮最容易踩的那个坑，逐值钉在这里**（brief 点名，与 V3 的"播种器留白"同款）：若创世把农村批次 **1000‰ 全给农业**，
   * 家庭纺织就是"有配额、没活干"的惰性状态 ⇒ 报表里看不见布。
   *
   * <p>判据 = ① 农村批次有一条**非零**的纺织配额；② 推一年后该格的 {@code CLOTH} 库存 **&gt; 0**。
   */
  @Test
  void theRuralHouseholdGetsANonZeroWeavingQuotaAndActuallyWeaves() {
    EconomyData shared = seeded();

    long weaveQuota = 0L;
    for (LaborAllocation allocation : shared.allocations().values()) {
      if (allocation.actor().id().equals(WEAVE.value())) {
        weaveQuota += allocation.laborMilli();
      }
    }
    assertThat(weaveQuota).as("★ 判据 ①：农村批次给家庭纺织的配额必须**非零**（否则织机有配额、没原料也没活干）").isPositive();
    assertThat(weaveQuota)
        .as("它 = 该池日劳动 × WEAVE_SHARE_PER_MILLE ÷ 1000")
        .isEqualTo(ruralDailyLabor(shared) * EconomySeeder.WEAVE_SHARE_PER_MILLE / 1000L);

    EconomyData afterOneCycle = EconomySettlement.settle(shared, 0L, EconomySeeder.CYCLE_DAYS);
    long clothAfterOneCycle = clothOf(afterOneCycle);
    assertThat(clothAfterOneCycle).as("★ 判据 ②：一个周期就有布（规模由最紧约束决定 ⇒ 织机/劳动/纤维三路都参与）").isPositive();

    EconomyData afterOneYear = EconomySettlement.settle(shared, 0L, YEAR_DAYS);
    assertThat(clothOf(afterOneYear)).as("★ 判据 ②（原文）：推一年后该格的 CLOTH 库存 > 0").isPositive();
    assertThat(fiberOf(afterOneCycle))
        .as("★ 农田第 120 天真的产出了纤维（规模受**种子**那一路上限 3,098 亩 ⇒ 3,098 × 6 × 1000 × 0.97 净产）")
        .isEqualTo(
            3_098L
                * EconomySeeder.FIBER_OUTPUT_PER_MU
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                * 970L
                / 1000L);
    assertThat(fiberOf(afterOneYear))
        .as("★ R4（T0）：农田的纤维**每个周期都被同格的织机取走**（不再是'自己攒着、织机停工'）⇒ 年末缸里是 0")
        .isZero();
    // ★★ **R4（T0）取代了 R3 那条"第 2 周期起停工"的如实记**：本格每个周期都能从农田取到新一期的纤维 ⇒
    //   纺织**持续**，布库存逐周期增长（下一条与 {@link #weavingContinuesEveryCycleBecauseTheFieldsFeedTheLooms}
    // 一起钉死）。
    assertThat(clothOf(afterOneYear))
        .as("★ R4（T0）：布库存**不再**只靠第 1 个周期的那一份 —— 推一年（3 个周期）拿到的是三份")
        .isGreaterThan(clothAfterOneCycle);
  }

  /**
   * ★★ **非 LAND 生产成立 + 规模由最紧约束决定**（spec §五/§六 给 R3 定的判据）：纺织的三路瓶颈里**没有一寸土地**， 而真档上最紧的那一路是**纤维**。
   *
   * <pre>
   * 织机 = 农村人口 ÷ {@link EconomySeeder#RURAL_CAPITA_PER_LOOM}(20) = 740
   * 劳动 = 该池日劳动 × 100‰ ÷ 1000 = 7,385,372 × 100‰ ÷ 1000 = 738
   * 纤维 = 本格农田一个周期的纤维副产 ÷ (每台织机一周期耗纤维 30,000) = 18,588,000 ÷ 30,000 = **619**（最紧）
   * ⇒ 规模 = 619 ⇒ 布净产 = 619 × 30 × 1000 × 0.97 = 18,012,900 毫
   * </pre>
   *
   * <p>★ 判别力（逐条对着一种坏实现）：把纺织的产能写成"土地"（或让规模只看土地）⇒ 纺织行没有土地 ⇒ 规模 0 ⇒ 布恒 0 ⇒ 红； 只看织机（740）⇒ 布会多出
   * 3,510,000 毫 ⇒ 红；不扣纤维投入 ⇒ 纤维的逐商品守恒式不平（见端到端用例）。
   */
  @Test
  void weavingScaleComesFromLoomsLaborAndFiberNotFromLand() {
    EconomyData shared = seeded();
    Industry weave = shared.industries().get(WEAVE);

    assertThat(weave.capacityPerUnit())
        .as("★ 纺织的产能锚是**织机**（TOOL），不是土地")
        .containsOnlyKeys(AssetKind.TOOL);
    assertThat(
            shared.classes().entrySet().stream()
                .filter(entry -> entry.getKey().industry().equals(WEAVE))
                .mapToLong(
                    entry -> entry.getValue().meansOfProduction().getOrDefault(AssetKind.LAND, 0L))
                .sum())
        .as("★ 纺织的四行一寸土地都没有")
        .isZero();

    long looms = 0L;
    long fiber = 0L;
    for (Map.Entry<ClassKey, ClassRow> entry : shared.classes().entrySet()) {
      if (!entry.getKey().industry().equals(WEAVE)) {
        continue;
      }
      looms += entry.getValue().meansOfProduction().getOrDefault(AssetKind.TOOL, 0L);
      fiber += entry.getValue().goods().getOrDefault(EconomyTestWorld.FIBER, 0L);
    }
    assertThat(looms).as("织机数 = 农村人口 ÷ RURAL_CAPITA_PER_LOOM").isEqualTo(740L);
    assertThat(fiber)
        .as("初始纤维 = 本格**农田一个周期的纤维副产**（估计来源；搬到织机上是 V8 的活）")
        .isEqualTo(
            3_098L
                * EconomySeeder.FIBER_OUTPUT_PER_MU
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);

    long fiberCap =
        fiber / (EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH);
    long laborCap = weaveLabor(shared) / EconomySeeder.LABOR_MILLI_PER_LOOM;
    assertThat(fiberCap).as("★ 纤维最紧（619）").isEqualTo(619L);
    assertThat(laborCap).as("劳动次之（738）—— 三路各不相同才证明 min 真的在取").isEqualTo(738L);
    assertThat(fiberCap).as("严格小于织机数与劳动可开数 ⇒ 它确实是那一年最紧的那块").isLessThan(looms);

    EconomyData afterOneCycle = EconomySettlement.settle(shared, 0L, EconomySeeder.CYCLE_DAYS);
    assertThat(goodsOf(afterOneCycle, WEAVE, EconomyTestWorld.CLOTH))
        .as("★ 农村织机的布净产 = 最紧那一路（纤维 619）决定的规模 × 30 匹 × 1000 × 0.97")
        .isEqualTo(
            fiberCap
                * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                * 970L
                / 1000L)
        .isEqualTo(18_012_900L);
  }

  /** 该产业名下全部配额之和（= 结算每天累加进 {@code cycleLaborMilli} 的那个数）。 */
  private static long quotaOf(EconomyData data, IndustryId industry) {
    long sum = 0L;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (allocation.actor().id().equals(industry.value())) {
        sum += allocation.laborMilli();
      }
    }
    return sum;
  }

  /** 家庭纺织那一池的日劳动。 */
  private static long weaveLabor(EconomyData data) {
    return quotaOf(data, WEAVE);
  }

  // ── ② 城市：靠自己的产品（布与工具）生产，与土地无关 ──────────────────────────────────

  /**
   * ★★ **城市作坊：非 LAND 生产 + 城市能产出自己的产品**（T5 的两个目的）。
   *
   * <pre>
   * 作坊 = 城市人口 1,777 ÷ {@link EconomySeeder#URBAN_CAPITA_PER_WORKSHOP}(50) = **35** 座
   * 劳动 = 城市那一池的日劳动 ÷ 1000 = 886,356 ÷ 1000 = 886 座（**不是瓶颈**）
   * 原料 = 35 座 × 一个周期的用量（纤维 2,100,000 毫、铁 350,000 毫）⇒ 原料那两路 = 35（与作坊同为瓶颈）
   * 规模 = min(35, 886, 35, 35) = **35**（**作坊**是最紧那一路 —— 那条路上没有土地）
   * ⇒ 布净产 35 × 60 × 1000 × 0.97 = 2,037,000；工具净产 35 × 5 × 1000 × 0.97 = 169,750
   * ⇒ 一年后的布 = 农村 18,012,900 + 城市 2,037,000 = **20,049,900**；工具 = **169,750**
   * </pre>
   *
   * <p>★ 判别力：v1 的手工业"不占地 ⇒ 恒产 0"（spec §一.4 实测的病态）⇒ 本条红。
   */
  @Test
  void theCityWorkshopProducesItsOwnGoodsWithoutAnyLand() {
    EconomyData shared = seeded();
    assertThat(shared.industries()).as("有城的格 = 农业 + 家庭纺织 + 城市作坊").containsKeys(FARM, WEAVE, CRAFT);

    EconomyData afterOneCycle = EconomySettlement.settle(shared, 0L, EconomySeeder.CYCLE_DAYS);
    EconomyData afterOneYear = EconomySettlement.settle(shared, 0L, YEAR_DAYS);
    long workshops = CITY_POPULATION / EconomySeeder.URBAN_CAPITA_PER_WORKSHOP;
    long cityCloth =
        workshops
            * EconomySeeder.CLOTH_PER_WORKSHOP_PER_CYCLE
            * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
            * 970L
            / 1000L;
    long yardCloth = clothOf(afterOneCycle) - cityCloth;

    assertThat(cityCloth).as("城里的布净产（35 座 × 60 匹 × 1000 × 0.97）").isEqualTo(2_037_000L);
    assertThat(clothOf(afterOneYear))
        .as("★ 一年后的布 = 第 1 周期的布 + 后两个周期**持续**织出来的那两份（T0 之后不再停工）")
        .isGreaterThan(clothOf(afterOneCycle));
    assertThat(toolsOf(afterOneYear))
        .as("★ 工具仍然是**城市自己的第二种产品**（农村不产工具）")
        .isEqualTo(
            workshops
                * EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                * 970L
                / 1000L)
        .isEqualTo(169_750L);
    assertThat(toolsOf(shared)).as("非平凡：创世时一件工具都没有").isZero();
    // ★★ **如实记（R4 之后仍然成立的那一半）**：作坊的第 2 个周期起产量为 0 —— 但原因**不再**是"没有转移通道"，
    //   而是**它的铁只有创世那一份**（本轮无冶炼流程）⇒ 铁那一路瓶颈恒 0 ⇒ 规模 0。
    //   ★ 取材步因此**不往它那儿搬纤维**（"只搬用得上的量"，见 EconomySettlement.rowUsageScale）——
    //     否则纤维会被倒进一个空转的作坊（扣成 consumed 而产出为 0），织机反而拿不到料。
    assertThat(weaveClothIncome(EconomySettlement.settle(shared, 0L, YEAR_DAYS / 3L * 2L), CRAFT))
        .as("★ 第 2 周期起作坊产不出东西：它的铁用光了 ⇒ 规模那一路 = 0")
        .isZero();
  }

  /**
   * ★★ **R4（T0）：纺织**每个周期**都在织 —— 农田把新一期的纤维交给了同格的织机**（R3 遗留的收口）。
   *
   * <pre>
   * 第 1 周期：织机吃创世那份纤维（= 本格农田一个周期的副产，逐行取整后 3,098 亩 × 6 × 1000 = 18,588,000）
   *           而它**想要** 740 × 30,000 = 22,200,000 ⇒ 逐行 `min(库存, 需求)` 把 18,588,000 **扣得一分不剩**
   *           ⇒ 纤维路 ⌊18,588,000 ÷ 30,000⌋ = 619 ⇒ 布毛产 619 × 30,000 = 18,570,000
   * 第 2 周期：农田第 120 天收获的纤维**净产** = 3,098 亩 × 6 × 1000 × 0.97 = 18,030,360
   *           （3,098 亩 = 收获规模被**种子**那一路卡住的那个数 —— 与第 1 周期同一个瓶颈，不是新数）
   *           织机缸里是 0（上一周期扣光）⇒ 缺口 22,200,000 ≥ 农田那份 ⇒ 取材取走**全部** 18,030,360
   *           ⇒ 纤维路 ⌊18,030,360 ÷ 30,000⌋ = **601** ⇒ 布毛产 601 × 30,000 = **18,030,000**
   * 第 3 周期：同上（缸里又是 0 ⇒ 又取走农田那一份 18,030,360）⇒ 纤维路 **601** ⇒ 布毛产 **18,030,000**
   * 作坊：第 2 周期起不产（铁用光）⇒ 不再与织机抢纤维，也不把纤维倒进空转的作坊
   * </pre>
   *
   * <p>★★ **判别力（逐条对着一种坏实现）**：
   *
   * <ul>
   *   <li>**不做同格取材**（R3 的旧形态）⇒ 第 2/3 周期的布毛产是 **0** ⇒ 两条断言一起红（这正是 R3 遗留的那条）；
   *   <li>**取材不做"用得上"的上限** ⇒ 作坊把纤维取走倒掉 ⇒ 织机第 2 周期的布毛产小于 18,030,000 ⇒ 红；
   *   <li>**取材不记供方的 consumed**（单侧扣减）⇒ 逐商品的守恒式当场不平 ⇒ 端到端那条守恒用例红。
   * </ul>
   */
  @Test
  void weavingContinuesEveryCycleBecauseTheFieldsFeedTheLooms() {
    EconomyData shared = seeded();
    // ★ 农田的纤维净产（取值上限）：收获的规模 3,098 亩由**种子**那一路卡住（与 R3 记的同一个瓶颈，
    //   见本文件上方那条 3,098 的注释）⇒ 净产 = 3,098 × 6 × 1000 × 0.97 = 18,030,360 毫纤维。
    long farmNetFiber =
        3_098L
            * EconomySeeder.FIBER_OUTPUT_PER_MU
            * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
            * 970L
            / 1000L;
    long loomNeedPerCycle =
        740L * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH;

    EconomyData cycle1 = EconomySettlement.settle(shared, 0L, 120L);
    EconomyData cycle2 = EconomySettlement.settle(shared, 0L, 240L);
    // ★ 第 3 个周期的**关账日**是第 360 天（不是 365）—— 关账日读得到整周期的量，次日归零（§八.5）。
    EconomyData cycle3 = EconomySettlement.settle(shared, 0L, 360L);

    assertThat(weaveClothIncome(cycle1, WEAVE)).as("第 1 周期：纤维路 619").isEqualTo(18_570_000L);
    assertThat(weaveClothIncome(cycle2, WEAVE))
        .as("★ 第 2 周期：织机从同格农田取到它那一份纤维 ⇒ 纤维路 601（手工推导见本方法的 javadoc）")
        .isEqualTo(
            601L
                * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT)
        .isEqualTo(18_030_000L);
    assertThat(weaveClothIncome(cycle3, WEAVE))
        .as("★ 第 3 周期：仍取到农田那一份 ⇒ 纤维路 601（纺织**没有**在第 2 个周期停工）")
        .isEqualTo(
            601L
                * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT)
        .isEqualTo(18_030_000L);
    assertThat(farmNetFiber).as("农田一个周期的纤维净产（取材量的上限）").isEqualTo(18_030_360L);
    assertThat(loomNeedPerCycle).as("织机满负荷一个周期要多少纤维（缺口那一侧）").isEqualTo(22_200_000L);

    // ★ 布库存**逐周期增长**（这是 brief 给 R4 的真档判据 ③ 在本夹具上的形态；真档上由
    //   WorldgenInitializeToolTest 的 R4 用例逐值钉住）。
    assertThat(clothOf(cycle2)).as("第 2 周期末的布 > 第 1 周期末").isGreaterThan(clothOf(cycle1));
    assertThat(clothOf(cycle3)).as("第 3 周期末的布 > 第 2 周期末").isGreaterThan(clothOf(cycle2));
    // ★★ **布真的被消费**（R4 的 T2）：三个周期里布那一维的缺口与消费都读得出来。
    assertThat(flowConsumed(cycle3, EconomyTestWorld.CLOTH))
        .as("★ 判据（真档可见性 ④）：CLOTH 的 consumed 非零")
        .isPositive();
  }

  /** 某产业本周期**布**的毛产（流水所得里布那一维）。 */
  private static long weaveClothIncome(EconomyData data, IndustryId industry) {
    long total = 0L;
    for (Map.Entry<ClassKey, ClassRow> entry : data.classes().entrySet()) {
      if (!entry.getKey().industry().equals(industry)) {
        continue;
      }
      io.mosire.simos.economy.model.FlowRow flow = data.flows().get(entry.getKey());
      if (flow != null) {
        total += flow.income().getOrDefault(EconomyTestWorld.CLOTH, 0L);
      }
    }
    return total;
  }

  /** 全格 Σ 行本周期某商品的消费（流水口径）。 */
  private static long flowConsumed(
      EconomyData data, io.mosire.simos.economy.api.id.CommodityId commodity) {
    return data.flows().values().stream()
        .mapToLong(flow -> flow.consumed().getOrDefault(commodity, 0L))
        .sum();
  }

  // ── 读数 ────────────────────────────────────────────────────────────────────────────

  /** 该格 Σ 某商品库存。 */
  private static long goodsOf(EconomyData data, CommodityId commodity) {
    return data.classes().values().stream()
        .mapToLong(row -> row.goods().getOrDefault(commodity, 0L))
        .sum();
  }

  private static long clothOf(EconomyData data) {
    return goodsOf(data, EconomyTestWorld.CLOTH);
  }

  /** 某**产业**名下 Σ 行的某商品库存（R3：同一格的两个产业各有各的产品，读数必须分得开）。 */
  private static long goodsOf(EconomyData data, IndustryId industry, CommodityId commodity) {
    return data.classes().entrySet().stream()
        .filter(entry -> entry.getKey().industry().equals(industry))
        .mapToLong(entry -> entry.getValue().goods().getOrDefault(commodity, 0L))
        .sum();
  }

  private static long toolsOf(EconomyData data) {
    return goodsOf(data, EconomyTestWorld.TOOL);
  }

  private static long fiberOf(EconomyData data) {
    return goodsOf(data, EconomyTestWorld.FIBER);
  }

  /** 该格**农村那一池**（农业行）的当日劳动（改口径前那条算式：Σ 行 laborMilli × 槽位投入率 ÷ 1000）。 */
  private static long ruralDailyLabor(EconomyData data) {
    long sum = 0L;
    for (Map.Entry<ClassKey, ClassRow> entry : data.classes().entrySet()) {
      if (entry.getKey().industry().equals(FARM)) {
        sum += entry.getValue().laborMilli() * entry.getValue().participationPerMille() / 1000L;
      }
    }
    return sum;
  }

  private static StateMeta stateMeta() {
    return new StateMeta(
        new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0));
  }

  private static Map<String, Snapshot> snapshots(EconomyData data) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put(
        "economy",
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0), data));
    return modules;
  }
}
