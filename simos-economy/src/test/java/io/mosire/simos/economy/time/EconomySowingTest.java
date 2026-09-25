package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **V3：播种时序 + 种子瓶颈**（v2 spec §3.2/§3.3，验收判据逐条落在下面）。
 *
 * <p>夹具（除非某条用例另有说明）：一格、一个农业产业、**一行贫农**；周期 {@value #CYCLE_DAYS} 天； 人口 {@value #POPULATION}（有效劳动
 * 232,000 千分劳动、投入率 1000‰）；地 {@code 400 亩}； 每亩需种 {@value #SEED_PER_MU} 毫粮；亩产 {@value #YIELD_PER_MU}
 * 粮/亩。
 *
 * <pre>
 * 劳动可经营亩 = 400 × 580 × {@link EconomySettlement#LAND_MU_PER_LABOR}(7) / 1000 = 1,624 亩  &gt; 400 亩
 * ⇒ **土地**是 V2 的基线瓶颈（故种子一缩面积就看得见，不会被劳动瓶颈掩盖）
 * 满种的种子量 = 400 亩 × 100 毫粮/亩                     = 40,000 毫粮
 * 一日口粮     = 400 × {@link EconomySettlement#DAILY_GRAIN_MILLI_PER_PERSON}(83) = 33,200 毫粮
 * 满产地净产   = 400 × 67 粮/亩 × 1000 × (1 − 15%)        = 22,780,000 毫粮
 * </pre>
 */
class EconomySowingTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final IndustryId CRAFT = new IndustryId("craft@0_0");
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassSlotId LANDLORD = new ClassSlotId("landlord");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey LANDLORD_KEY = new ClassKey(FARM, LANDLORD);
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** 周期（天）：一天播种、一天收获 —— 算账最短，且第 2 天就能看见累加器清零。 */
  private static final long CYCLE_DAYS = 2L;

  /** 每亩需种（毫粮/亩）= 0.1 粮/亩。 */
  private static final long SEED_PER_MU = 100L;

  /** 亩产（粮/亩）：与 v2 spec §10.3 定案 A 同值。 */
  private static final long YIELD_PER_MU = 67L;

  /** 一个人的有效劳动（千分劳动）：与 §十"D4 默认"同（400 人 ⇒ 232,000）。 */
  private static final long LABOR_PER_PERSON = 580L;

  private static final long POPULATION = 400L;

  /** 一行的地（千分亩）：400,000 千分亩 = 400 亩。 */
  private static final long LAND_MILLI_MU = 400_000L;

  private static final long DAILY_NEED =
      POPULATION * EconomySettlement.DAILY_GRAIN_MILLI_PER_PERSON; // 33,200
  private static final long FULL_SEED = (LAND_MILLI_MU / 1000L) * SEED_PER_MU; // 40,000
  private static final long FULL_HARVEST_NET =
      (LAND_MILLI_MU / 1000L)
          * YIELD_PER_MU
          * EconomySettlement.MILLI_PER_GRAIN
          * (1000L - EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE)
          / 1000L; // 22,780,000

  /** 一行的贫农（人口 400 / 投入率 1000‰ / 地 {@link #LAND_MILLI_MU} 千分亩 / 缸 {@code stock} 毫粮）。 */
  private static ClassRow peasantRow(long stock) {
    return peasantRow(stock, LAND_MILLI_MU);
  }

  /** 同上，但地由调用方给（两行夹具里贫农只占 200 亩）。 */
  private static ClassRow peasantRow(long stock, long landMilliMu) {
    return new ClassRow(
        PEASANT_KEY,
        POPULATION,
        POPULATION * LABOR_PER_PERSON,
        1000,
        Map.of(AssetKind.LAND, landMilliMu),
        stock > 0L ? Map.of(GRAIN, stock) : Map.of(),
        0L,
        List.of(),
        Map.of(GRAIN, DAILY_NEED),
        Map.of());
  }

  /** **不占地**的一行（真档里每座城的手工业行都是这一形态）：无生产资料 ⇒ 种子需求恒 0。 */
  private static ClassRow landlessRow(ClassKey key, long stock) {
    return new ClassRow(
        key,
        POPULATION,
        POPULATION * LABOR_PER_PERSON,
        1000,
        Map.of(),
        stock > 0L ? Map.of(GRAIN, stock) : Map.of(),
        0L,
        List.of(),
        Map.of(GRAIN, DAILY_NEED),
        Map.of());
  }

  /** 一行的地主（0 人、0 劳动、投入率 0 —— 它的缸只是种子本钱与同格放贷的余粮）。 */
  private static ClassRow landlordRow(long stock, long landMilliMu) {
    return new ClassRow(
        LANDLORD_KEY,
        0L,
        0L,
        0,
        Map.of(AssetKind.LAND, landMilliMu),
        stock > 0L ? Map.of(GRAIN, stock) : Map.of(),
        0L,
        List.of(),
        Map.of(),
        Map.of());
  }

  /** 一个产业：两个槽位（上限 1000‰，够放本夹具的行）、`Split(700, 300)`、亩产 {@value #YIELD_PER_MU} 粮/亩。 */
  private static Industry industry(
      IndustryId id, String name, long cycleDays, Map<AssetKind, Long> cycleInput) {
    return new Industry(
        id,
        name,
        new RegimeId("feudal"),
        cycleDays,
        0L,
        Map.of(),
        0L,
        Map.of(GRAIN, YIELD_PER_MU),
        cycleInput,
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        0L);
  }

  /** 一份经济状态。★ 两张表都用 {@code LinkedHashMap}（迭代序是内容的纯函数）。 */
  private static EconomyData data(
      Map<ClassKey, ClassRow> rows, Map<IndustryId, Industry> industries) {
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyData(Optional.of(meta), industries, rows, Map.of(), Map.of());
  }

  /** 一格、一个农业产业、**一行贫农**（地 {@link #LAND_MILLI_MU} 千分亩）、周期 {@value #CYCLE_DAYS} 天。 */
  private static EconomyData farm(long stock, Map<AssetKind, Long> cycleInput) {
    return farm(stock, cycleInput, CYCLE_DAYS);
  }

  private static EconomyData farm(long stock, Map<AssetKind, Long> cycleInput, long cycleDays) {
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow(stock));
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", cycleDays, cycleInput));
    return data(rows, industries);
  }

  private static long grainOf(EconomyData data, ClassKey key) {
    return data.classes().get(key).goods().getOrDefault(GRAIN, 0L);
  }

  private static long consumedOf(EconomyData data, ClassKey key) {
    FlowRow flow = data.flows().get(key);
    return flow == null ? 0L : flow.consumed().getOrDefault(GRAIN, 0L);
  }

  private static long unmetOf(EconomyData data, ClassKey key) {
    FlowRow flow = data.flows().get(key);
    return flow == null ? 0L : flow.unmetNeed();
  }

  // ── ① 播种日先扣种（spec §九 V3 判据 2）────────────────────────────────────────────

  /**
   * 缸 {@code 40,000 + 2 × 33,200 = 106,400} 恰够"满种 + 两天口粮"：
   *
   * <pre>
   * 第 1 天（progressDays == 0 ⇒ 播种日）：扣种 400 亩 × 100 = 40,000 ⇒ 66,400；再吃 33,200 ⇒ 33,200
   * 第 2 天：不播种，只吃 33,200 ⇒ 0；周期末收获（土地是瓶颈 400 亩）⇒ 净 22,780,000 ⇒ 22,780,000
   * </pre>
   */
  @Test
  void sowingDayDrawsTheSeedBeforeTheDayIsEaten() {
    EconomyData base = farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU));

    EconomyData day1 = EconomySettlement.settle(base, 0L, 1L);

    assertThat(grainOf(day1, PEASANT_KEY))
        .as("库存 = 期初 − 种子 40,000 − 当日口粮 33,200")
        .isEqualTo(106_400L - FULL_SEED - DAILY_NEED);
    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("本周期实际扣到的种子 = 满种量")
        .isEqualTo(FULL_SEED);
    assertThat(consumedOf(day1, PEASANT_KEY))
        .as("★ 留种要记账（spec §二：留种的计量是\"数\"）：消费 = 种子 + 口粮")
        .isEqualTo(FULL_SEED + DAILY_NEED);
    assertThat(unmetOf(day1, PEASANT_KEY)).as("缸够 ⇒ 没有缺口").isZero();

    EconomyData day2 = EconomySettlement.settle(day1, 1L, 2L);

    assertThat(grainOf(day2, PEASANT_KEY)).as("第 2 天只吃口粮 ⇒ 0，然后收获满产净额").isEqualTo(FULL_HARVEST_NET);
    assertThat(day2.industries().get(FARM).cycleSeedUsedMilli())
        .as("周期关账 ⇒ 累加器清零（不清零第 2 周期的 seedCap 会凭空变大）")
        .isZero();
    assertThat(day2.industries().get(FARM).progressDays()).as("关账后进度归零").isZero();
    assertThat(day2.meta().orElseThrow().lastClosedCycle()).as("关账周期序号 1").hasValue(1L);
  }

  /** 非播种日（`progressDays != 0`）**一次都不扣**：库存只减当日口粮。 */
  @Test
  void daysAfterTheSowingDayDrawNothing() {
    EconomyData day1 =
        EconomySettlement.settle(farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 1L);

    EconomyData day2 = EconomySettlement.settle(day1, 1L, 2L);

    assertThat(consumedOf(day2, PEASANT_KEY) - consumedOf(day1, PEASANT_KEY))
        .as("第 2 天的消费只有口粮（外加 15% 生产损耗的份额）")
        .isEqualTo(
            DAILY_NEED
                + (FULL_HARVEST_NET
                    * EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE
                    / (1000L - EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE)));
  }

  /**
   * ★ **边界：库存恰好 == need**（Review Focus 4：「边界不许写成 `<`」）。缸 {@code 40,000} 恰好够满种 400 亩：
   *
   * <pre>
   * 播种日：drawn = min(40,000, 40,000) = 40,000 ⇒ 缸 0（不许"差一点"少扣一粒）⇒ 当天口粮一件不剩 ⇒ 缺口 33,200
   * </pre>
   */
  @Test
  void aJarThatExactlyCoversTheSeedIsDrawnToZero() {
    EconomyData day1 =
        EconomySettlement.settle(farm(FULL_SEED, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 1L);

    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("恰好够 ⇒ 满种（等号归「够」这一侧）")
        .isEqualTo(FULL_SEED);
    assertThat(grainOf(day1, PEASANT_KEY)).as("扣光 ⇒ 缸 0").isZero();
    assertThat(consumedOf(day1, PEASANT_KEY)).as("消费只有种子那一笔（口粮没吃到）").isEqualTo(FULL_SEED);
    assertThat(unmetOf(day1, PEASANT_KEY)).as("口粮全缺 ⇒ 走缺口路径").isEqualTo(DAILY_NEED);
  }

  // ── ② 次序预设：先扣 vs 后扣（spec §3.2 的"可调预设"，不许是死分支）────────────────────

  /**
   * ★★ **次序的判别力**（这条用例是"偏离 ③"存在的理由）：缸 {@code 20,000} **不够**满种（40,000）也**不够**一天口粮（33,200）。
   *
   * <pre>
   * 先扣种（默认 true）：扣 min(20,000, 40,000) = 20,000 ⇒ 缸 0 ⇒ 当天口粮 0 ⇒ 缺口 33,200
   * 先吃饭（false）    ：吃 min(20,000, 33,200) = 20,000 ⇒ 缸 0 ⇒ 再扣种 0 ⇒ **种子一颗没留住**，缺口 13,200
   * </pre>
   *
   * <p>★ 两种次序给出**不同的种子量（20,000 vs 0）与不同的缺口（33,200 vs 13,200）**——这正是 spec §3.2
   * 把"播种该不该先于吃饭扣种"做成预设的原因：代码不替 GM 选。判别力：把播种步挪到 {@code settleHexes} 之后（或把次序参数恒传 false）⇒ 本条的"先扣"一半必红。
   */
  @Test
  void drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown() {
    EconomyData base = farm(20_000L, Map.of(AssetKind.LAND, SEED_PER_MU));
    LinkedHashMap<ClassKey, FlowRow> flowsFirst = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, FlowRow> flowsAfter = new LinkedHashMap<>();

    EconomyData first = EconomySettlement.settleOneDay(base, 1L, flowsFirst, true);
    EconomyData after = EconomySettlement.settleOneDay(base, 1L, flowsAfter, false);

    assertThat(first.industries().get(FARM).cycleSeedUsedMilli())
        .as("先扣种 ⇒ 缸里那 20,000 全变成种子")
        .isEqualTo(20_000L);
    assertThat(after.industries().get(FARM).cycleSeedUsedMilli())
        .as("先吃饭 ⇒ 颗粒无种（这就是 GM 取 false 时的后果）")
        .isZero();
    assertThat(unmetOf(first, PEASANT_KEY)).as("先扣种 ⇒ 当天一口没吃").isEqualTo(DAILY_NEED);
    assertThat(unmetOf(after, PEASANT_KEY))
        .as("先吃饭 ⇒ 吃了 20,000，缺口只剩 33,200 − 20,000")
        .isEqualTo(DAILY_NEED - 20_000L);
  }

  // ── ③ 未配种子 ⇒ 与 V2 逐字一致（spec §3.3 的口径 + 旧档护栏）────────────────────────

  /** ★★ **没配种子（空 map）或每亩需种为 0 ⇒ 播种日一字不扣**。这条是"旧档与未配种子的产业行为与 V2 完全一致"的护栏——第三路瓶颈**绝不许**把它们变成一粒无收。 */
  @Test
  void anIndustryWithoutASeedRateDrawsNothingAtAll() {
    for (Map<AssetKind, Long> cycleInput :
        List.of(Map.<AssetKind, Long>of(), Map.of(AssetKind.LAND, 0L))) {
      EconomyData day1 = EconomySettlement.settle(farm(106_400L, cycleInput), 0L, 1L);

      assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
          .as("没配种子 ⇒ 累加器恒 0（%s）", cycleInput)
          .isZero();
      assertThat(grainOf(day1, PEASANT_KEY))
          .as("库存只减当日口粮（%s）", cycleInput)
          .isEqualTo(106_400L - DAILY_NEED);
    }
  }

  /** ★ `need == 0` 的行（**没有地** ⇒ 真档里每座城的手工业行）⇒ 不扣、不累加。 */
  @Test
  void aRowWithoutLandIsNeverDrawnFrom() {
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow(106_400L)); // 农业行：400 亩、缸够满种 + 两天口粮
    ClassKey craftPeasant = new ClassKey(CRAFT, PEASANT);
    rows.put(craftPeasant, landlessRow(craftPeasant, 1_000_000L)); // 手工业行：不占地、缸很足
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", CYCLE_DAYS, Map.of(AssetKind.LAND, SEED_PER_MU)));
    industries.put(CRAFT, industry(CRAFT, "手工业", CYCLE_DAYS, Map.of(AssetKind.LAND, SEED_PER_MU)));
    EconomyData base = data(rows, industries);

    EconomyData day1 = EconomySettlement.settle(base, 0L, 1L);

    assertThat(grainOf(day1, craftPeasant))
        .as("手工业行没有地 ⇒ 不扣它的粮（只吃口粮）")
        .isEqualTo(1_000_000L - DAILY_NEED);
    assertThat(day1.industries().get(CRAFT).cycleSeedUsedMilli()).as("无地产业的种子累加器恒 0").isZero();
    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("农业那一路照扣（不许被无地产业带偏）")
        .isEqualTo(FULL_SEED);
  }

  // ── ④ 等价性与 1 天周期（§十一 / Review Focus 9、12）──────────────────────────────

  /** ★ 一次 2 天 == 两次单日：**播种恰发生一次**（不会"每推一次就扣一遍"）。 */
  @Test
  void settlingTwoDaysAtOnceEqualsTwoSingleDayStepsWithSeeds() {
    EconomyData base = farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU));

    EconomyData once = EconomySettlement.settle(base, 0L, 2L);
    EconomyData twice = EconomySettlement.settle(EconomySettlement.settle(base, 0L, 1L), 1L, 2L);

    assertThat(once).as("§十一：一次 2 天 == 两次单日（终态逐值）").isEqualTo(twice);
    assertThat(once.flows()).as("流水也逐值相同").isEqualTo(twice.flows());
    assertThat(once.industries().get(FARM).cycleSeedUsedMilli()).as("关账后归零").isZero();
  }

  /**
   * ★★ `cycleDays == 1`：**同一天先播后收**。收获日读的必须是**当天刚播**的种子累加器 （若收获读的是播种前的 `Industry` 快照，`seedCapMu`
   * 会恒为 0 ⇒ 本条的库存断言必红）。
   *
   * <pre>
   * 第 1 天：播 40,000 ⇒ 66,400；吃 33,200 ⇒ 33,200；progressed(1) &gt;= cycleDays(1) ⇒ 收获
   *     劳动可经营 = 232,000 × 7 / 1000 = 1,624 亩；土地 400 亩；种子可支撑 = 40,000 / 100 = 400 亩 ⇒ 取 400
   *     ⇒ 净 22,780,000 ⇒ 库存 33,200 + 22,780,000 = 22,813,200；随后关账清零
   * </pre>
   */
  @Test
  void aOneDayCycleSowsAndHarvestsOnTheSameDay() {
    EconomyData next =
        EconomySettlement.settle(farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU), 1L), 0L, 1L);

    assertThat(grainOf(next, PEASANT_KEY))
        .as("当天播、当天收（种子必须先扣、收获必须读到它）")
        .isEqualTo(33_200L + FULL_HARVEST_NET);
    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("关账后归零").isZero();
    assertThat(next.industries().get(FARM).progressDays()).isZero();
  }

  // ── ⑤ 第三路瓶颈（spec §九 V3 判据 3）──────────────────────────────────────────────

  /**
   * ★★ **缺种子 ⇒ 投入面积缩 ⇒ 减产**：缸 {@code 20,000} < 满种 40,000 ⇒ 先扣种时**扣光**（任务 3 已断言）， 收获日可支撑亩数 = {@code
   * 20,000 / 100 = 200 亩}（&lt; 土地的 400 亩、&lt; 劳动的 1,624 亩）⇒ **种子是瓶颈**。
   *
   * <pre>
   * 毛产 = 200 × 67 × 1000 = 13,400,000；扣 15% ⇒ 净 11,390,000
   * 对照（同夹具**不配种子**）：土地是瓶颈 ⇒ 400 亩 ⇒ 净 22,780,000（= {@link #FULL_HARVEST_NET}）
   * </pre>
   */
  @Test
  void aShortJarShrinksTheSownAreaAndCutsTheHarvest() {
    long seedCapMu = 20_000L / SEED_PER_MU; // = 200 亩
    long expectedNet =
        seedCapMu
            * YIELD_PER_MU
            * EconomySettlement.MILLI_PER_GRAIN
            * (1000L - EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE)
            / 1000L;

    EconomyData withSeeds =
        EconomySettlement.settle(farm(20_000L, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 2L);
    EconomyData withoutSeeds = EconomySettlement.settle(farm(20_000L, Map.of()), 0L, 2L);

    assertThat(grainOf(withSeeds, PEASANT_KEY))
        .as("20,000 毫粮的种子只够种 200 亩（土地本来能种 400 亩）⇒ 终态 = 200 亩的净产")
        .isEqualTo(expectedNet);
    // ★ 实测修正（见提交说明与"偏离"报告）：`settle(…, 0, 2)` 跑满一个 2 天周期 ⇒ **关账时累加器已清零**
    //   （任务 3 的 `sowingDayDrawsTheSeedBeforeTheDayIsEaten` 已逐值钉住"关账清零"）⇒ "扣光 20,000" 这一笔
    //   只能在**播种日当天**读。故这里另跑一次单日结算取第 1 天读数（期望值不变），而不是把断言放宽成 0。
    EconomyData sowingDay =
        EconomySettlement.settle(farm(20_000L, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 1L);
    assertThat(sowingDay.industries().get(FARM).cycleSeedUsedMilli())
        .as("扣光：缸里那 20,000 全变成种子（播种日当天读数；关账后归零）")
        .isEqualTo(20_000L);
    assertThat(unmetOf(withSeeds, PEASANT_KEY)).as("两天全缺口").isEqualTo(2L * DAILY_NEED);
    assertThat(grainOf(withoutSeeds, PEASANT_KEY))
        .as("对照：不配种子 ⇒ 第三路不施加约束 ⇒ 土地瓶颈满产；那 20,000 被第 1 天口粮吃光" + "（起点 0）⇒ 终态恰为净产")
        .isEqualTo(FULL_HARVEST_NET);
  }

  /** ★★ **缸全空 ⇒ 播种日扣不到 ⇒ 颗粒无收**（"冬春吃空缸 ⇒ 减产"在单周期的极端形态）。 */
  @Test
  void anEmptyJarYieldsNothingInsteadOfTheLandBoundHarvest() {
    EconomyData next =
        EconomySettlement.settle(farm(0L, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 2L);

    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("一颗都没扣到").isZero();
    assertThat(grainOf(next, PEASANT_KEY))
        .as("seedCapMu = 0 ⇒ 0 亩 ⇒ 不产粮（V2 会按土地 400 亩满产 22,780,000）")
        .isZero();
    assertThat(unmetOf(next, PEASANT_KEY)).as("两天全缺口 = 2 × 33,200").isEqualTo(2L * DAILY_NEED);
  }

  /** ★★ **`seedPerMu == 0`（键在、值是 0）不许把产量压成 0 亩**（本任务最主要的风险点）： 三元式写成 `? 0 :` 或直接除零 ⇒ 本条必红。 */
  @Test
  void aZeroSeedRateLeavesTheHarvestExactlyAsBefore() {
    EconomyData next = EconomySettlement.settle(farm(106_400L, Map.of(AssetKind.LAND, 0L)), 0L, 2L);

    assertThat(grainOf(next, PEASANT_KEY))
        .as("每亩需种 0 ⇒ 这一路不施加约束（min 里它不可能更小）⇒ 与 V2 同值")
        .isEqualTo(106_400L - 2L * DAILY_NEED + FULL_HARVEST_NET);
  }

  /** ★ 六种键都允许，但 **v1 只读 `LAND`**：配上其余五种不改任何数（spec §3.1"声明但不启用"）。 */
  @Test
  void allSixAssetKindsAreAcceptedButOnlyLandIsRead() {
    LinkedHashMap<AssetKind, Long> sixKinds = new LinkedHashMap<>();
    sixKinds.put(AssetKind.CATTLE, 1L);
    sixKinds.put(AssetKind.TOOL, 2L);
    sixKinds.put(AssetKind.LAND, SEED_PER_MU);
    sixKinds.put(AssetKind.WORKSHOP, 3L);
    sixKinds.put(AssetKind.MACHINE, 4L);
    sixKinds.put(AssetKind.SHIP, 5L);

    EconomyData next = EconomySettlement.settle(farm(106_400L, sixKinds), 0L, 2L);

    // ★ 实测修正：同 `aShortJarShrinksTheSownAreaAndCutsTheHarvest` —— 2 天结算跑满周期 ⇒ 关账清零，
    //   "只按 LAND 扣了 40,000" 要在播种日当天读。
    assertThat(
            EconomySettlement.settle(farm(106_400L, sixKinds), 0L, 1L)
                .industries()
                .get(FARM)
                .cycleSeedUsedMilli())
        .as("只按 LAND 那一档扣（其余五档不进公式），播种日当天读数")
        .isEqualTo(FULL_SEED);
    assertThat(grainOf(next, PEASANT_KEY))
        .as("与只配 LAND 的同夹具逐值相同（★ 算式含扣掉的种子 40,000：期初 − 种子 − 两天口粮 + 满产净额）")
        .isEqualTo(106_400L - FULL_SEED - 2L * DAILY_NEED + FULL_HARVEST_NET);
  }

  /**
   * ★ **第三路不许盖过另外两路**：满种时 {@code seedCapMu} **恰等于** {@code availableMu} （因为 {@code 扣到的种子 = Σ地亩 ×
   * seedPerMu} ⇒ 相除还原出地亩）⇒ 取小仍按土地。 ★★ 由此得一条**恒成立的不等式**：{@code seedCapMu ≤ availableMu} ——
   * 第三路只会**缩**面积， 永远不会**放大**它（这条也是"`seedPerMu == 0` 时取 `availableMu` = 不施加约束"的依据）。
   */
  @Test
  void aHugeSeedAmountNeverOverridesTheOtherTwoBottlenecks() {
    EconomyData next =
        EconomySettlement.settle(farm(106_400L * 100L, Map.of(AssetKind.LAND, 1L)), 0L, 2L);

    assertThat(grainOf(next, PEASANT_KEY))
        .as(
            "每亩需种 1 毫粮 ⇒ 满种只需 400 毫粮；可支撑亩 = 400 ÷ 1 = 400 亩 = 土地 ⇒ 取小仍按土地"
                + "（库存 = 期初 − 满种 400 毫粮 − 两天口粮 + 满产净额）")
        .isEqualTo(106_400L * 100L - 400L - 2L * DAILY_NEED + FULL_HARVEST_NET);
  }
}
