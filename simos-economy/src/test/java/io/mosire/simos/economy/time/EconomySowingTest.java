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
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
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
 * 第 1 天口粮   = 累计(1) = floor(400 × 10,000 ÷ 120)   = 33,333 毫粮（第 2 天 33,333；头 2 天合计 66,666）
 * 两日口粮缸   = 满种 + 头 2 天口粮 = 40,000 + 66,666   = 106,666 毫粮（{@link #JAR_TWO_DAYS}）
 * 满产地净产   = 400 × 67 粮/亩 × 1000 × (1 − 饲料0‰ − 折旧30‰)  = 25,996,000 毫粮
 * </pre>
 *
 * <p>★★ **多日口粮一律经 {@link EconomyVocabulary} 的公开纯函数表达**（不许写"n × 某一天的量"：日耗是累计口粮的**逐日差分**，乘不出来）。
 */
class EconomySowingTest {

  /** 本夹具的格（H0：家户键 = 格 + 居住类型 + 阶层；这里只有一格）。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final IndustryId CRAFT = new IndustryId("craft@0_0");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CohortKey PEASANT_KEY = new CohortKey(HEX, ResidenceKind.RURAL, PEASANT);
  private static final CohortKey LANDLORD_KEY = new CohortKey(HEX, ResidenceKind.RURAL, LANDLORD);
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** ★ R3：另外两种商品 —— 用来证明"六档投入都允许，而只有粮那一档进本文件的粮链"。 */
  private static final CommodityId FIBER = new CommodityId("fiber");

  private static final CommodityId WOOD = new CommodityId("wood");

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

  /** ★ R2 的夹具：本文件的配额都挂在同一个批次上（一格一批人 ⇒ 供给一条、配额一条）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-0-farm@0_0");

  /** ★ 创世配额的发放周期（与 {@code EconomySeeder.FIRST_PERIOD} 同值：周期序号从 1 起）。 */
  private static final long FIRST_PERIOD = 1L;

  /** 该批次的**毛劳动**（= 供给的上限）与**承诺投入**（= 配额）：本夹具两者同值（投入率 1000‰）。 */
  private static final long LABOR_MILLI = POPULATION * LABOR_PER_PERSON; // 232,000

  private static final long FULL_SEED = (LAND_MILLI_MU / 1000L) * SEED_PER_MU; // 40,000

  /** 第 {@code day} 天的口粮（毫粮）：唯一算法在 {@link EconomyVocabulary#dailyRationMilli}（逐日差分，残差不丢）。 */
  private static long rationOn(long day) {
    return EconomyVocabulary.dailyRationMilli(POPULATION, day);
  }

  /** 头 {@code days} 天的口粮合计（毫粮）—— 多日口粮只能用累计函数表达。 */
  private static long rationOver(long days) {
    return EconomyVocabulary.cumulativeRationMilli(POPULATION, days);
  }

  /** 夹具的缸：**满种 + 一整个周期的口粮**（{@code 40,000 + 66,666 = 106,666}）—— 恰好够播种日扣种与全周期吃饭。 */
  private static final long JAR_TWO_DAYS = FULL_SEED + rationOver(CYCLE_DAYS);

  /**
   * 收获时的生产损耗合计（千分）：{@link EconomySettlement#FEED_PER_MILLE} + {@link
   * EconomySettlement#DEPRECIATION_PER_MILLE} —— 与 {@code harvest} 的 {@code loss} **同式**（两项各自具名、V7
   * 参数化后各自可调，本算式跟着走；不在这里把 30 抄死）。
   */
  private static final long PRODUCTION_LOSS_PER_MILLE =
      EconomySettlement.FEED_PER_MILLE + EconomySettlement.DEPRECIATION_PER_MILLE;

  private static final long FULL_HARVEST_NET =
      (LAND_MILLI_MU / 1000L)
          * YIELD_PER_MU
          * EconomySettlement.MILLI_PER_GRAIN
          * (1000L - PRODUCTION_LOSS_PER_MILLE)
          / 1000L; // 25,996,000

  /**
   * 一行的贫农（人口 400 / 投入率 1000‰）。
   *
   * <p>★★ <b>H1（K1）：行里没有商品库存了</b> —— 缸里的粮由夹具写进**会话工作副本**（见 {@link #data} 的 {@code goods} 参数）。
   */
  private static ClassRow peasantRow() {
    return new ClassRow(
        PEASANT_KEY,
        POPULATION,
        POPULATION * LABOR_PER_PERSON,
        1000,
        0L,
        List.of(),
        Map.of(GRAIN, rationOn(1L)),
        Map.of(),
        0L);
  }

  /** **不占地**的一行（真档里每座城的手工业行都是这一形态）：无生产资料 ⇒ 种子需求恒 0。 */
  private static ClassRow landlessRow(CohortKey key) {
    return new ClassRow(
        key,
        POPULATION,
        POPULATION * LABOR_PER_PERSON,
        1000,
        0L,
        List.of(),
        Map.of(GRAIN, rationOn(1L)),
        Map.of(),
        0L);
  }

  /** 一行的地主（0 人、0 劳动、投入率 0 —— 它的缸只是种子本钱与同格放贷的余粮）。 */
  private static ClassRow landlordRow() {
    return new ClassRow(LANDLORD_KEY, 0L, 0L, 0, 0L, List.of(), Map.of(), Map.of(), 0L);
  }

  /**
   * **每亩下种 {@code perMu} 毫粮**（R3 换型后的形状）：{@code {"LAND":{"grain":perMu}}}。
   *
   * <p>★ 它是本文件"配了种子"的**唯一拼写点** —— 十几处调用都读它，改形状时不会漏掉一处。
   */
  private static Map<AssetKind, Map<CommodityId, Long>> seeds(long perMu) {
    return Map.of(AssetKind.LAND, Map.of(GRAIN, perMu));
  }

  /**
   * 一个产业：两个槽位（上限 1000‰，够放本夹具的行）、`Split(700, 300)`、亩产 {@value #YIELD_PER_MU} 粮/亩。
   *
   * <p>★ **R3**：产能锚 = 1 亩；劳动那一路**刻意留 0**（不施加约束）—— 本文件量的是**投入那一路瓶颈** （{@code Σ实际扣到的投入 ÷
   * 每单位规模的投入}），劳动若也参与取小就会把它的字面量搅进来。★ 投入表按生产资料种类归类（农业 = LAND）。
   */
  private static Industry industry(
      IndustryId id,
      String name,
      long cycleDays,
      Map<AssetKind, Map<CommodityId, Long>> cycleInput) {
    return industry(id, name, cycleDays, cycleInput, new RegimeId("feudal"), null);
  }

  /**
   * 同上，但**制度与经营者可注入**（H3/K7 用）：{@code operator == null} ⇒ 按制度推导（旧行为）； 给了 ⇒ 就用它（本文件用它造"<b>operator
   * 就是佃农家户</b>"那一档 —— 见 {@link #eachClassRowDrawsItsOwnSeedSoTheDryRowLeavesItsLandFallow}）。
   */
  private static Industry industry(
      IndustryId id,
      String name,
      long cycleDays,
      Map<AssetKind, Map<CommodityId, Long>> cycleInput,
      RegimeId regime,
      ActorRef operator) {
    return new Industry(
        id,
        name,
        regime,
        cycleDays,
        0L,
        Map.of(AssetKind.LAND, 1_000L),
        // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
        Map.of(AssetKind.LAND, LAND_MILLI_MU),
        Map.of(),
        0L,
        0L,
        Map.of(GRAIN, YIELD_PER_MU),
        cycleInput,
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        Map.of(),
        // ★ 通用夹具的 operator = **派生**（`feudal` ⇒ `ESTATE:<本夹具的 id 参数>`）；H3 的重载可显式注入。
        operator == null ? RegimeOperators.defaultOperator(regime, id) : operator);
  }

  /**
   * 一份经济状态。★ 两张表都用 {@code LinkedHashMap}（迭代序是内容的纯函数）。
   *
   * <p>★★ **R2：当日劳动取自配额表**（不再从"行 laborMilli × 投入率"算）⇒ 本文件的夹具必须发一条配额， 否则 {@code cycledLabor = 0} ⇒
   * 劳动可经营亩数 0 ⇒ 收获恒 0，全部收获字面量一起变。 ★ 配额量 = {@code 232,000} （= {@link #POPULATION} 400 人 × {@link
   * #LABOR_PER_PERSON} 580‰ × 投入率 1000‰；地主行的投入率 0‰ 且人口 0，贡献 0）。
   */
  private static EconomyFixtures.World data(
      Map<CohortKey, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<CohortKey, Map<CommodityId, Long>> goods) {
    return data(rows, industries, goods, EconomyFixtures.laborShareToPeasant(industries));
  }

  /** 同上，但**关系表可注入**（H3：operator 就是某个家户时，"自留"只能由 {@code residualOwner} 表达 —— 见 K7 那条用例）。 */
  private static EconomyFixtures.World data(
      Map<CohortKey, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<CohortKey, Map<CommodityId, Long>> goods,
      Map<IndustryId, ProductionRelation> relations) {
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyFixtures.World(
        new EconomyData(
            Optional.of(meta),
            industries,
            rows,
            Map.of(),
            Map.of(),
            Map.of(LOT, new LaborSupply(LOT, FIRST_PERIOD, LABOR_MILLI, 0L, 0L)),
            Map.of(
                ALLOCATION,
                new LaborAllocation(
                    ALLOCATION,
                    LOT,
                    new ActorRef(ActorKind.ESTATE, FARM.value()),
                    "farm",
                    LABOR_MILLI,
                    FIRST_PERIOD)),
            // ★★ **T4：关系表非空**（不再是"空表 = 全归 residualOwner"）—— 产出离开 ClassRow 之后，行里唯一还有实物的
            //   通道就是关系结算的 cohort 入账。本文件的夹具**只有一行有人口**（贫农）⇒ 那条 1000‰ 的劳动分成
            //   **逐值等于净产**（own ÷ Σ劳动 = 1）⇒ 既有的收获字面量（{@link #FULL_HARVEST_NET} 那一族）一字不改。
            //   ★ H3：operator 就是受方那个家户时（tenant 档）**不能**用这条规则 —— 那会铸出一条自转移
            //     （{@code Transfer} 的两端不得相等）⇒ 那种世界用**空规则表**（= 全归 residualOwner，裁定 E9）。
            relations,
            Map.of(),
            Map.of()),
        goods);
  }

  /** 一格、一个农业产业、**一行贫农**（缸 {@code stock} 毫粮）、周期 {@value #CYCLE_DAYS} 天。 */
  private static EconomyFixtures.World farm(
      long stock, Map<AssetKind, Map<CommodityId, Long>> cycleInput) {
    return farm(stock, cycleInput, CYCLE_DAYS);
  }

  private static EconomyFixtures.World farm(
      long stock, Map<AssetKind, Map<CommodityId, Long>> cycleInput, long cycleDays) {
    LinkedHashMap<CohortKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow());
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", cycleDays, cycleInput));
    // ★★ H1（K1）：缸里的粮进**会话工作副本**（行里没有 goods 了）。
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, PEASANT_KEY, GRAIN, stock);
    return data(rows, industries, goods);
  }

  /** 某家户的粮余额（★ H1：从**会话工作副本**读 —— 行里没有 {@code goods} 了）。 */
  private static long grainOf(Map<CohortKey, Map<CommodityId, Long>> goods, CohortKey key) {
    return EconomyFixtures.grainOf(goods, key);
  }

  /**
   * 一份"两户两缸"的家户账工作副本（{@code 本格两行}夹具专用）。
   *
   * <p>★ 为什么另给一个助手、而不是在用例里写两行 {@code hold}：那两行在同一个用例里要出现**两次**（一次给"跑满周期"、 一次给"只跑播种日"的对照）——
   * 两处各写一遍必然有一处漂开。**每次调用给一份新的副本**（会话状态不能跨世界复用）。
   */
  private static Map<CohortKey, Map<CommodityId, Long>> goodsFor(
      CohortKey first, long firstGrain, CohortKey second, long secondGrain) {
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, first, GRAIN, firstGrain);
    EconomyFixtures.hold(goods, second, GRAIN, secondGrain);
    return goods;
  }

  private static long consumedOf(EconomyData data, CohortKey key) {
    FlowRow flow = data.flows().get(key);
    return flow == null ? 0L : flow.consumed().getOrDefault(GRAIN, 0L);
  }

  private static long unmetOf(EconomyData data, CohortKey key) {
    FlowRow flow = data.flows().get(key);
    return flow == null ? 0L : flow.unmetNeed().getOrDefault(GRAIN, 0L);
  }

  // ── ① 播种日先扣种（spec §九 V3 判据 2）────────────────────────────────────────────

  /**
   * 缸 {@link #JAR_TWO_DAYS}（= 满种 40,000 + 头 2 天口粮 66,666 = 106,666）恰够"满种 + 两天口粮"：
   *
   * <pre>
   * 第 1 天（progressDays == 0 ⇒ 播种日）：扣种 400 亩 × 100 = 40,000 ⇒ 66,666；再吃 33,333 ⇒ 33,333
   * 第 2 天：不播种，只吃 33,333 ⇒ 0；周期末收获（土地是瓶颈 400 亩）⇒ 净 25,996,000 ⇒ 25,996,000
   * </pre>
   */
  @Test
  void sowingDayDrawsTheSeedBeforeTheDayIsEaten() {
    EconomyFixtures.World world = farm(JAR_TWO_DAYS, seeds(SEED_PER_MU));

    EconomyData day1 = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("库存 = 期初 − 种子 40,000 − 当日口粮")
        .isEqualTo(JAR_TWO_DAYS - FULL_SEED - rationOn(1L));
    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("本周期实际扣到的种子 = 满种量")
        .isEqualTo(FULL_SEED);
    assertThat(consumedOf(day1, PEASANT_KEY))
        .as("★ 留种要记账（spec §二：留种的计量是\"数\"）：消费 = 种子 + 口粮")
        .isEqualTo(FULL_SEED + rationOn(1L));
    assertThat(unmetOf(day1, PEASANT_KEY)).as("缸够 ⇒ 没有缺口").isZero();

    EconomyData day2 = EconomyFixtures.advance(day1, world.goods(), 1L, 2L);

    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("第 2 天只吃口粮 ⇒ 0，然后收获满产净额")
        .isEqualTo(FULL_HARVEST_NET);
    assertThat(day2.industries().get(FARM).cycleSeedUsedMilli())
        .as("周期关账 ⇒ 累加器清零（不清零第 2 周期的 seedCap 会凭空变大）")
        .isZero();
    assertThat(day2.industries().get(FARM).progressDays()).as("关账后进度归零").isZero();
    assertThat(day2.meta().orElseThrow().lastClosedCycle()).as("关账周期序号 1").hasValue(1L);
  }

  /** 非播种日（`progressDays != 0`）**一次都不扣**：库存只减当日口粮。 */
  @Test
  void daysAfterTheSowingDayDrawNothing() {
    EconomyFixtures.World world = farm(JAR_TWO_DAYS, seeds(SEED_PER_MU));
    EconomyData day1 = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    EconomyData day2 = EconomyFixtures.advance(day1, world.goods(), 1L, 2L);

    assertThat(consumedOf(day2, PEASANT_KEY) - consumedOf(day1, PEASANT_KEY))
        .as("★ T4：第 2 天的消费**只有口粮** —— 生产损耗不再进 consumed（它只进 ProductionLedger.losses）")
        .isEqualTo(rationOn(2L));
  }

  /**
   * ★ **边界：库存恰好 == need**（Review Focus 4：「边界不许写成 `<`」）。缸 {@code 40,000} 恰好够满种 400 亩：
   *
   * <pre>
   * 播种日：drawn = min(40,000, 40,000) = 40,000 ⇒ 缸 0（不许"差一点"少扣一粒）⇒ 当天口粮一件不剩 ⇒ 缺口 = 第 1 天口粮
   * </pre>
   */
  @Test
  void aJarThatExactlyCoversTheSeedIsDrawnToZero() {
    EconomyFixtures.World world = farm(FULL_SEED, seeds(SEED_PER_MU));
    EconomyData day1 = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("恰好够 ⇒ 满种（等号归「够」这一侧）")
        .isEqualTo(FULL_SEED);
    assertThat(grainOf(world.goods(), PEASANT_KEY)).as("扣光 ⇒ 缸 0").isZero();
    assertThat(consumedOf(day1, PEASANT_KEY)).as("消费只有种子那一笔（口粮没吃到）").isEqualTo(FULL_SEED);
    assertThat(unmetOf(day1, PEASANT_KEY)).as("口粮全缺 ⇒ 走缺口路径").isEqualTo(rationOn(1L));
  }

  // ── ② 次序预设：先扣 vs 后扣（spec §3.2 的"可调预设"，不许是死分支）────────────────────

  /**
   * ★★ **次序的判别力**（这条用例是"偏离 ③"存在的理由）：缸 {@code 20,000} **不够**满种（40,000）也**不够**一天口粮（33,200）。
   *
   * <pre>
   * 先扣种（默认 true）：扣 min(20,000, 40,000) = 20,000 ⇒ 缸 0 ⇒ 当天口粮 0 ⇒ 缺口 = 第 1 天口粮 33,333
   * 先吃饭（false）    ：吃 min(20,000, 33,333) = 20,000 ⇒ 缸 0 ⇒ 再扣种 0 ⇒ **种子一颗没留住**，缺口 13,333
   * </pre>
   *
   * <p>★ 两种次序给出**不同的种子量（20,000 vs 0）与不同的缺口（33,200 vs 13,200）**——这正是 spec §3.2
   * 把"播种该不该先于吃饭扣种"做成预设的原因：代码不替 GM 选。判别力：把播种步挪到 {@code settleHexes} 之后（或把次序参数恒传 false）⇒ 本条的"先扣"一半必红。
   */
  @Test
  void drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown() {
    EconomyFixtures.World world = farm(20_000L, seeds(SEED_PER_MU));
    Map<CohortKey, Map<CommodityId, Long>> goodsFirst = EconomyFixtures.householdGoods();
    Map<CohortKey, Map<CommodityId, Long>> goodsAfter = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goodsFirst, PEASANT_KEY, GRAIN, 20_000L);
    EconomyFixtures.hold(goodsAfter, PEASANT_KEY, GRAIN, 20_000L);
    LinkedHashMap<CohortKey, FlowRow> flowsFirst = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, FlowRow> flowsAfter = new LinkedHashMap<>();

    // ★ T4：日结算现在还要交回当天的 ProductionLedger（产出离开 ClassRow 之后的落点）⇒ 累加器是必填入参；
    //   本用例只量"播种次序对行/种子的影响"（第 1 天没有任何产业关账）⇒ 两份账都应当是空的。
    ProductionLedger.Accumulator ledgerFirst = new ProductionLedger.Accumulator(1L);
    ProductionLedger.Accumulator ledgerAfter = new ProductionLedger.Accumulator(1L);
    EconomyData first =
        EconomySettlement.settleOneDay(world.data(), 1L, flowsFirst, goodsFirst, true, ledgerFirst);
    EconomyData after =
        EconomySettlement.settleOneDay(
            world.data(), 1L, flowsAfter, goodsAfter, false, ledgerAfter);
    assertThat(ledgerFirst.toLedger().hasOutput()).as("第 1 天没有产业关账 ⇒ 没有产出").isFalse();
    assertThat(ledgerAfter.toLedger().hasOutput()).as("同上（次序只改扣减次序，不改「有没有关账」）").isFalse();

    assertThat(first.industries().get(FARM).cycleSeedUsedMilli())
        .as("先扣种 ⇒ 缸里那 20,000 全变成种子")
        .isEqualTo(20_000L);
    assertThat(after.industries().get(FARM).cycleSeedUsedMilli())
        .as("先吃饭 ⇒ 颗粒无种（这就是 GM 取 false 时的后果）")
        .isZero();
    assertThat(unmetOf(first, PEASANT_KEY)).as("先扣种 ⇒ 当天一口没吃").isEqualTo(rationOn(1L));
    assertThat(unmetOf(after, PEASANT_KEY))
        .as("先吃饭 ⇒ 吃了 20,000，缺口只剩第 1 天口粮 − 20,000")
        .isEqualTo(rationOn(1L) - 20_000L);
  }

  // ── ③ 未配种子 ⇒ 与 V2 逐字一致（spec §3.3 的口径 + 旧档护栏）────────────────────────

  /** ★★ **没配种子（空 map）或每亩需种为 0 ⇒ 播种日一字不扣**。这条是"旧档与未配种子的产业行为与 V2 完全一致"的护栏——第三路瓶颈**绝不许**把它们变成一粒无收。 */
  @Test
  void anIndustryWithoutASeedRateDrawsNothingAtAll() {
    for (Map<AssetKind, Map<CommodityId, Long>> cycleInput :
        List.of(Map.<AssetKind, Map<CommodityId, Long>>of(), seeds(0L))) {
      EconomyFixtures.World world = farm(JAR_TWO_DAYS, cycleInput);
      EconomyData day1 = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

      assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
          .as("没配种子 ⇒ 累加器恒 0（%s）", cycleInput)
          .isZero();
      assertThat(grainOf(world.goods(), PEASANT_KEY))
          .as("库存只减当日口粮（%s）", cycleInput)
          .isEqualTo(JAR_TWO_DAYS - rationOn(1L));
    }
  }

  /** ★ `need == 0` 的行（**没有地** ⇒ 真档里每座城的手工业行）⇒ 不扣、不累加。 */
  @Test
  void aRowWithoutLandIsNeverDrawnFrom() {
    LinkedHashMap<CohortKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow()); // 农业行：缸够满种 + 两天口粮
    CohortKey craftPeasant = new CohortKey(HEX, ResidenceKind.URBAN, PEASANT);
    rows.put(craftPeasant, landlessRow(craftPeasant)); // 手工业行：不占地、缸很足
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", CYCLE_DAYS, seeds(SEED_PER_MU)));
    industries.put(CRAFT, industry(CRAFT, "手工业", CYCLE_DAYS, seeds(SEED_PER_MU)));
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, PEASANT_KEY, GRAIN, JAR_TWO_DAYS);
    EconomyFixtures.hold(goods, craftPeasant, GRAIN, 1_000_000L);
    EconomyFixtures.World world = data(rows, industries, goods);

    EconomyData day1 = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    assertThat(grainOf(world.goods(), craftPeasant))
        .as("手工业行没有地 ⇒ 不扣它的粮（只吃口粮）")
        .isEqualTo(1_000_000L - rationOn(1L));
    assertThat(day1.industries().get(CRAFT).cycleSeedUsedMilli()).as("无地产业的种子累加器恒 0").isZero();
    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("农业那一路照扣（不许被无地产业带偏）")
        .isEqualTo(FULL_SEED);
  }

  // ── ④ 等价性与 1 天周期（§十一 / Review Focus 9、12）──────────────────────────────

  /** ★ 一次 2 天 == 两次单日：**播种恰发生一次**（不会"每推一次就扣一遍"）。 */
  @Test
  void settlingTwoDaysAtOnceEqualsTwoSingleDayStepsWithSeeds() {
    EconomyFixtures.World world = farm(JAR_TWO_DAYS, seeds(SEED_PER_MU));
    EconomyFixtures.World twiceWorld = farm(JAR_TWO_DAYS, seeds(SEED_PER_MU));

    EconomyData once = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);
    EconomyData twice =
        EconomyFixtures.advance(
            EconomyFixtures.advance(twiceWorld.data(), twiceWorld.goods(), 0L, 1L),
            twiceWorld.goods(),
            1L,
            2L);

    assertThat(once).as("§十一：一次 2 天 == 两次单日（终态逐值）").isEqualTo(twice);
    assertThat(once.flows()).as("流水也逐值相同").isEqualTo(twice.flows());
    assertThat(once.industries().get(FARM).cycleSeedUsedMilli()).as("关账后归零").isZero();
  }

  /**
   * ★★ `cycleDays == 1`：**同一天先播后收**。收获日读的必须是**当天刚播**的种子累加器 （若收获读的是播种前的 `Industry` 快照，`seedCapMu`
   * 会恒为 0 ⇒ 本条的库存断言必红）。
   *
   * <pre>
   * 第 1 天：播 40,000 ⇒ 66,666；吃 33,333 ⇒ 33,333；progressed(1) &gt;= cycleDays(1) ⇒ 收获
   *     劳动可经营 = 232,000 × 7 / 1000 = 1,624 亩；土地 400 亩；种子可支撑 = 40,000 / 100 = 400 亩 ⇒ 取 400
   *     ⇒ 净 25,996,000 ⇒ 库存 33,333 + 25,996,000 = 26,029,333；随后关账清零
   * </pre>
   */
  @Test
  void aOneDayCycleSowsAndHarvestsOnTheSameDay() {
    EconomyFixtures.World world = farm(JAR_TWO_DAYS, seeds(SEED_PER_MU), 1L);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("当天播、当天收（种子必须先扣、收获必须读到它）")
        .isEqualTo(JAR_TWO_DAYS - FULL_SEED - rationOn(1L) + FULL_HARVEST_NET);
    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("关账后归零").isZero();
    assertThat(next.industries().get(FARM).progressDays()).isZero();
  }

  // ── ⑤ 第三路瓶颈（spec §九 V3 判据 3）──────────────────────────────────────────────

  /**
   * ★★ **缺种子 ⇒ 投入面积缩 ⇒ 减产**：缸 {@code 20,000} < 满种 40,000 ⇒ 先扣种时**扣光**（任务 3 已断言）， 收获日可支撑亩数 = {@code
   * 20,000 / 100 = 200 亩}（&lt; 土地的 400 亩、&lt; 劳动的 1,624 亩）⇒ **种子是瓶颈**。
   *
   * <pre>
   * 毛产 = 200 × 67 × 1000 = 13,400,000；扣生产损耗（饲料 0‰ + 折旧 30‰）⇒ 净 12,998,000
   * 对照（同夹具**不配种子**）：土地是瓶颈 ⇒ 400 亩 ⇒ 净 25,996,000（= {@link #FULL_HARVEST_NET}）
   * </pre>
   *
   * <p>★★ <b>H5 的口径修正（改这两条期望值的算式，断言本身一条没动）</b>：结算的次序现在是 <b>吃饭 → 收获/分配 → 集市 → 借粮</b>（借粮是最后手段，见
   * {@code EconomySettlement.lendDeficits} 的"再吃一口"） ⇒
   * **关账日那一顿是从刚收获的粮里吃的**（改前：关账日的缺口记在读数里、人空着肚子等下一个周期）。逐条算式：
   *
   * <pre>
   * 终态粮 = 净产 − 关账日那一顿（第 CYCLE_DAYS 天 = dailyRationMilli(400, 2) = 33,333）
   * 未满足 = 整周期口粮(66,666) − 关账日那一顿(33,333) = 33,333   （第 1 天全缺口、第 2 天在收获里吃上了）
   * 对照世界同理：净产 25,996,000 − 33,333
   * </pre>
   */
  @Test
  void aShortJarShrinksTheSownAreaAndCutsTheHarvest() {
    long seedCapMu = 20_000L / SEED_PER_MU; // = 200 亩
    long expectedNet =
        seedCapMu
            * YIELD_PER_MU
            * EconomySettlement.MILLI_PER_GRAIN
            * (1000L - PRODUCTION_LOSS_PER_MILLE)
            / 1000L;
    // ★ H5：关账日那一顿从收获里吃（见方法注释的算式）。
    long closingDayMeal = rationOn(CYCLE_DAYS);

    EconomyFixtures.World seededWorld = farm(20_000L, seeds(SEED_PER_MU));
    EconomyFixtures.World bareWorld = farm(20_000L, Map.of());
    EconomyData withSeeds =
        EconomyFixtures.advance(seededWorld.data(), seededWorld.goods(), 0L, 2L);
    EconomyData withoutSeeds = EconomyFixtures.advance(bareWorld.data(), bareWorld.goods(), 0L, 2L);

    assertThat(grainOf(seededWorld.goods(), PEASANT_KEY))
        .as("20,000 毫粮的种子只够种 200 亩（土地本来能种 400 亩）⇒ 终态 = 200 亩的净产 − 关账日那一顿")
        .isEqualTo(expectedNet - closingDayMeal);
    // ★ 实测修正（见提交说明与"偏离"报告）：`settle(…, 0, 2)` 跑满一个 2 天周期 ⇒ **关账时累加器已清零**
    //   （任务 3 的 `sowingDayDrawsTheSeedBeforeTheDayIsEaten` 已逐值钉住"关账清零"）⇒ "扣光 20,000" 这一笔
    //   只能在**播种日当天**读。故这里另跑一次单日结算取第 1 天读数（期望值不变），而不是把断言放宽成 0。
    EconomyFixtures.World sowingWorld = farm(20_000L, seeds(SEED_PER_MU));
    EconomyData sowingDay =
        EconomyFixtures.advance(sowingWorld.data(), sowingWorld.goods(), 0L, 1L);
    assertThat(sowingDay.industries().get(FARM).cycleSeedUsedMilli())
        .as("扣光：缸里那 20,000 全变成种子（播种日当天读数；关账后归零）")
        .isEqualTo(20_000L);
    assertThat(unmetOf(withSeeds, PEASANT_KEY))
        .as("整周期缺口 = 两天口粮 − 关账日那一顿（★ H5：第 2 天的那一顿在收获里吃上了）")
        .isEqualTo(rationOver(CYCLE_DAYS) - closingDayMeal);
    assertThat(grainOf(bareWorld.goods(), PEASANT_KEY))
        .as("对照：不配种子 ⇒ 第三路不施加约束 ⇒ 土地瓶颈满产；那 20,000 被第 1 天口粮吃光" + "（起点 0）⇒ 终态 = 净产 − 关账日那一顿（★ H5）")
        .isEqualTo(FULL_HARVEST_NET - closingDayMeal);
  }

  /** ★★ **缸全空 ⇒ 播种日扣不到 ⇒ 颗粒无收**（"冬春吃空缸 ⇒ 减产"在单周期的极端形态）。 */
  @Test
  void anEmptyJarYieldsNothingInsteadOfTheLandBoundHarvest() {
    EconomyFixtures.World world = farm(0L, seeds(SEED_PER_MU));
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);

    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("一颗都没扣到").isZero();
    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("seedCapMu = 0 ⇒ 0 亩 ⇒ 不产粮（V2 会按土地 400 亩满产 25,996,000）")
        .isZero();
    assertThat(unmetOf(next, PEASANT_KEY))
        .as("两天全缺口 = 头 2 天口粮合计")
        .isEqualTo(rationOver(CYCLE_DAYS));
  }

  /** ★★ **`seedPerMu == 0`（键在、值是 0）不许把产量压成 0 亩**（本任务最主要的风险点）： 三元式写成 `? 0 :` 或直接除零 ⇒ 本条必红。 */
  @Test
  void aZeroSeedRateLeavesTheHarvestExactlyAsBefore() {
    EconomyFixtures.World world = farm(JAR_TWO_DAYS, seeds(0L));
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);

    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("每亩需种 0 ⇒ 这一路不施加约束（min 里它不可能更小）⇒ 与 V2 同值（缸里的种子那笔也就不扣）")
        .isEqualTo(JAR_TWO_DAYS - rationOver(CYCLE_DAYS) + FULL_HARVEST_NET);
  }

  /**
   * ★★ **六种键都允许**（spec §3.3：协议不设限）：R3 起投入表是"生产资料 → 商品表"，**每一档都真的被读到**，
   * 只是**只有粮那一档**进这条链（其余五档刻意配**别的商品** ⇒ 与本文件的粮字面量无关）。
   *
   * <p>★ 判别力：把值侧退回标量 ⇒ 本夹具的 `cycleInputPerUnit` 编译不过；把"合计"改成"只读 LAND"也能过 ⇒ 由 {@code inputPerUnit}
   * 的断言与下方的逐值相同断言一起挡（六档里只有 LAND 带粮 ⇒ 合计仍等于 LAND 那一档）。
   */
  @Test
  void allSixAssetKindsAreAcceptedAndOnlyTheGrainLineFeedsThisChain() {
    LinkedHashMap<AssetKind, Map<CommodityId, Long>> sixKinds = new LinkedHashMap<>();
    // ★ 其余五档刻意配**别的商品且量为 0**：它们的键必须被接受、且**一分不改本文件的粮字面量**
    //   （配成非 0 的 FIBER/WOOD 会让"缺原料 ⇒ 停产"生效 ⇒ 测的就不是这件事了；真档的多商品现扣由
    //    {@code EconomyRealScaleClothTest} 与端到端夹具覆盖）。
    sixKinds.put(AssetKind.CATTLE, Map.of(FIBER, 0L));
    sixKinds.put(AssetKind.TOOL, Map.of(FIBER, 0L));
    sixKinds.put(AssetKind.LAND, Map.of(GRAIN, SEED_PER_MU));
    sixKinds.put(AssetKind.WORKSHOP, Map.of(FIBER, 0L));
    sixKinds.put(AssetKind.MACHINE, Map.of(FIBER, 0L));
    sixKinds.put(AssetKind.SHIP, Map.of(WOOD, 0L));

    EconomyFixtures.World world = farm(JAR_TWO_DAYS, sixKinds);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);

    // ★ 实测修正：同 `aShortJarShrinksTheSownAreaAndCutsTheHarvest` —— 2 天结算跑满周期 ⇒ 关账清零，
    //   "只按粮那一档扣了 40,000" 要在现扣日当天读。
    EconomyFixtures.World oneDayWorld = farm(JAR_TWO_DAYS, sixKinds);
    assertThat(
            EconomyFixtures.advance(oneDayWorld.data(), oneDayWorld.goods(), 0L, 1L)
                .industries()
                .get(FARM)
                .inputPerUnit())
        .as("六档的投入**合计**成一张每单位规模的表（非零的那一项只来自 LAND 那一档）")
        .containsEntry(GRAIN, SEED_PER_MU)
        .containsEntry(FIBER, 0L)
        .containsEntry(WOOD, 0L);
    EconomyFixtures.World oneDayWorld2 = farm(JAR_TWO_DAYS, sixKinds);
    assertThat(
            EconomyFixtures.advance(oneDayWorld2.data(), oneDayWorld2.goods(), 0L, 1L)
                .industries()
                .get(FARM)
                .cycleSeedUsedMilli())
        .as("粮那一档的现扣量（其余五档不耗粮 ⇒ 与本条无关），现扣日当天读数")
        .isEqualTo(FULL_SEED);
    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("与只配 LAND 的同夹具逐值相同（★ 算式含扣掉的种子 40,000：期初 − 种子 − 头两天口粮 + 满产净额）")
        .isEqualTo(JAR_TWO_DAYS - FULL_SEED - rationOver(CYCLE_DAYS) + FULL_HARVEST_NET);
  }

  /**
   * ★ **第三路不许盖过另外两路**：满种时 {@code seedCapMu} **恰等于** {@code availableMu} （因为 {@code 扣到的种子 = Σ地亩 ×
   * seedPerMu} ⇒ 相除还原出地亩）⇒ 取小仍按土地。 ★★ 由此得一条**恒成立的不等式**：{@code seedCapMu ≤ availableMu} ——
   * 第三路只会**缩**面积， 永远不会**放大**它（这条也是"`seedPerMu == 0` 时取 `availableMu` = 不施加约束"的依据）。
   */
  @Test
  void aHugeSeedAmountNeverOverridesTheOtherTwoBottlenecks() {
    EconomyFixtures.World world = farm(JAR_TWO_DAYS * 100L, seeds(1L));
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);

    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as(
            "每亩需种 1 毫粮 ⇒ 满种只需 400 毫粮；可支撑亩 = 400 ÷ 1 = 400 亩 = 土地 ⇒ 取小仍按土地"
                + "（库存 = 期初 − 满种 400 毫粮 − 头两天口粮 + 满产净额）")
        .isEqualTo(JAR_TWO_DAYS * 100L - 400L - rationOver(CYCLE_DAYS) + FULL_HARVEST_NET);
  }

  // ── ⑥ 验收链（spec §九 V3 判据 4）与阶级差异 ────────────────────────────────────────

  /**
   * ★★ **各扣各的：贫农缸空 ⇒ 它的地荒着；缸足的行 ⇒ 它的地照种**（定案：逐 {@code ClassRow} 从它自己的 {@code goods} 里扣，不从全格池子扣）。
   *
   * <p>夹具：贫农（400 人 / **缸空**）+ 地主（**0 人** / 缸 5,000,000）。周期 2 天。
   *
   * <p>★★ <b>下面这段算式是 H0（K3）之前的口径，留痕不改</b>；新口径的读数见其后的〔H0/K3 重算〕。
   *
   * <pre>
   * 〔H0/K3 之前〕
   * 播种日：贫农扣 min(0, 200 × 100 = 20,000) = 0；地主扣 800 × 100 = 80,000 ⇒ 累加器 80,000
   * 可支撑亩 = 80,000 / 100 = 800 亩（&lt; 土地 1,000 亩、&lt; 劳动 1,624 亩）⇒ **贫农那 200 亩荒着**
   * 满产地净产（按 800 亩）= 800 × 67 × 1000 × (1 − 生产损耗) = 51,992,000
   * ★★ T4：产出**不再**按 `Split` 的"土地权重 + 劳动权重"分给两行 —— 它进 "+净产 → operator"，再按关系结算落回行：
   *   那条规则（{@code OUTPUT_SHARE × LABOR_AMOUNT} 1000‰ 给 {@code (0,0)|rural|poor_peasant} cohort）的受方**只有贫农行**
   *   （地主行**人口为 0** ⇒ 按 R7 它永远不是 cohort 受方）⇒ 实付 = 净产 × 贫农劳动 ÷ Σ劳动 = 净产 × 1 = 净产
   * 贫农两天缺口由地主借出（同格借粮）：2 × 33,333 = 66,666 ⇒ ★ **一条**债务（§7.2 按 (周期, 债务人, 债权人) 聚合）
   * 贫农库存 = 0 − 0 − 0 + 51,992,000 = 51,992,000
   * 地主库存 = 5,000,000 − 80,000 − 33,333 − 33,333 = 4,853,334（它拿不到产出 ⇒ 比 T4 之前少了 560‰ 那一份）
   * ★ 地主**0 人口** ⇒ 本周期自需 0 ⇒ 保留额 0 ⇒ "只贷余粮"这一路在它这里不缩任何量（放贷额与 V1 同值）
   * </pre>
   *
   * <pre>
   * 〔H0 / K3 重算〕产能在**产业**上（{@code Industry.capacity} = 400 亩），行的"想扣多少"按**人口占比**折算：
   * 该产业只有贫农行有人口（400 人；地主行 0 人）⇒ 贫农拿到**全部**规模 400 亩、地主拿 0
   * 播种日：贫农想扣 400 × 100 = 40,000，而它缸空 ⇒ 实扣 **0** ⇒ 投入那一路的规模 = 0 / 100 = 0
   *        ⇒ 规模 = min(产能 400 亩, 投入 0) = **0** ⇒ **整块地荒着**（收获 0、无产出可分）
   * 贫农库存 = 0（借到的当日即吃掉，不进库存）· 地主库存 = 5,000,000 − 头两天借出的口粮（**无人在它的地上出工 ⇒ 一分种也不扣**）
   * ★ 判别力仍在：改从**全格池子**扣 ⇒ 地主的 5,000,000 会被拿来下种 ⇒ 两条 `isZero()` 当场红。
   * ★★ H3（C3 的"投入由谁出"进 relation）落地后，本用例要按那条新口径**再重算一次**。
   * </pre>
   */
  @Test
  void eachClassRowDrawsItsOwnSeedSoTheDryRowLeavesItsLandFallow() {
    LinkedHashMap<CohortKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow()); // 缸空
    rows.put(LANDLORD_KEY, landlordRow()); // 缸足（5,000,000）—— 它**不是**这个产业的经营者
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    // ★★ **H3/K7（2026-09-27）：这个夹具的 operator 换成"佃农家户"** —— 因为"缸空 ⇒ 地荒着"这条机构自 H3 起
    //   由**单一主体的账**表达（裁定 C3 的原话：tenant 档的 operator 就是佃农家户），而不再是"逐行各扣各的"：
    //   旧口径里"谁出料"是按该产业各行的人口占比**算**出来的（{@code rowSharesOf}），H3 起由 relation 明说。
    //   ⇒ 本条判据一个字没改（缸空 ⇒ 播 0 亩 ⇒ 颗粒无收；地主那 5,000,000 **一分不被拿去下种**），
    //   改的只是"谁是这个产业的出料人"这条前提（夹具修正，见计划 K7）。
    //   ★ 判别力仍在：若取材改成"从**全格池子**扣"（或从"该产业名下的家户账"整体扣），地主的 5,000,000 会被拿来下种
    //     ⇒ 下面那两条 `isZero()` 当场红。
    //   ★ 关系表用**空规则**（= 全归 residualOwner）：operator 就是受方那个家户时，一条"付给它的规则"会铸出自转移
    //     （{@code Transfer} 两端不得相等）—— tenant 档的"自留"本就该由 residualOwner 表达（裁定 E9）。
    ActorRef tenant = HouseholdActors.of(PEASANT_KEY);
    industries.put(
        FARM, industry(FARM, "农业", CYCLE_DAYS, seeds(SEED_PER_MU), new RegimeId("tenant"), tenant));
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(goods, PEASANT_KEY, GRAIN, 0L);
    EconomyFixtures.hold(goods, LANDLORD_KEY, GRAIN, 5_000_000L);
    EconomyFixtures.World world =
        data(
            rows,
            industries,
            goods,
            Map.of(FARM, new ProductionRelation(FARM, tenant, null, List.of(), tenant)));

    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);

    // ★★ 裁定 K10（2026-09-27，H0/K3 的口径变化）：本夹具的**前提**是"**0 人**的地主行占 800 亩并下种" ——
    //   那正是 K3 废除的东西（产能在**产业**上；且**没有人就没有份额**）。⇒ 同一份夹具在新口径下的读数是：
    //   唯一有人口的贫农行拿到**全部**产业规模（= 该产业产能 400 亩），而它缸空 ⇒ **一分种也扣不出**
    //   ⇒ 整块地荒着（收获 0）。
    //   ★ 判别力仍在：若取材改成"从**全格池子**扣"，地主的 5,000,000 会被拿来下种 ⇒ 下面那两条 `isZero()`
    //     当场红；若改成"无份额的行也照扣"，同款红。⇒ 它守的机构（**投入各扣各的**）没变，
    //     变的只是"谁有份额"（H0 的口径：按人口占比；H3 起改由 relation 的"投入由谁出"决定）。
    //   ★★ **H3（C3）落地后本用例要再重算一次** —— 记在这里，别静默。
    // ★ 修订 1（控制器 2026-09-25 追加）：`settle(base, 0, 2)` 跑满**一整个 2 天周期** ⇒ 关账时该累加器
    //   **已清零**（任务 3 的 `sowingDayDrawsTheSeedBeforeTheDayIsEaten` 自己就钉着"关账清零"）。
    //   故这里另跑一次**单日**结算读第 1 天（播种日）读数。
    EconomyFixtures.World sowingWorld =
        data(
            rows,
            industries,
            goodsFor(PEASANT_KEY, 0L, LANDLORD_KEY, 5_000_000L),
            Map.of(FARM, new ProductionRelation(FARM, tenant, null, List.of(), tenant)));
    assertThat(
            EconomyFixtures.advance(sowingWorld.data(), sowingWorld.goods(), 0L, 1L)
                .industries()
                .get(FARM)
                .cycleSeedUsedMilli())
        .as("★ 缸空的行拿满份额也扣不出种（播种日当天读数；改前 = 800 亩 × 100 = 80,000）")
        .isZero();
    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("关账后归零").isZero();
    assertThat(grainOf(world.goods(), PEASANT_KEY))
        .as("★ 地荒着 ⇒ 没有产出可分；它自己也是靠**借**才吃上的（借到的当日即吃掉，不进库存）")
        .isZero();
    assertThat(grainOf(world.goods(), LANDLORD_KEY))
        .as("地主：5,000,000 − 头两天借给贫农的口粮（★ 无人下种 ⇒ 一分种子也不扣；它人口 0 ⇒ 拿不到产出）")
        .isEqualTo(5_000_000L - rationOver(CYCLE_DAYS));
    // ★★ **V6 §7.2 债务聚合**：贫农**两天都向同一个地主借**，但同周期内同一对债权债务人**只有一条**
    //   （旧口径是"每天一条"，本条的 2 会变成 1 —— 这正是本批要改的那件事）。
    List<DebtId> peasantDebts = next.classes().get(PEASANT_KEY).debts();
    assertThat(peasantDebts).as("两天的借入聚合成一条（旧口径：每天各一条 ⇒ 2 条）").hasSize(1);
    Debt aggregated = next.debts().get(peasantDebts.get(0));
    assertThat(aggregated.principal())
        .as(
            "本金递增（两天缺口之和 = 头 2 天口粮）+ 周期末计息按**当日起始本金**（第 2 天开始时只有第 1 天的借入 %d；第 2 天新借的 %d 当天不计息）⇒ %d × %d‰ = %d",
            rationOn(1L),
            rationOn(2L),
            rationOn(1L),
            EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE,
            rationOn(1L) * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L)
        .isEqualTo(
            rationOver(CYCLE_DAYS)
                + rationOn(1L) * EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE / 1000L)
        .isEqualTo(67_332L);
    assertThat(aggregated.debtor()).isEqualTo(PEASANT_KEY);
    assertThat(aggregated.creditor()).isEqualTo(LANDLORD_KEY);
    assertThat(aggregated.id().value())
        .as("★ id 由 (周期, 债务人, 债权人, 商品) 确定性算出，且**不含 \".\"**（debt.<id> 在第一个点处被 AddressParser 切）")
        // ★ S1 阶段 1 手算重推（**不是抄实际值**）：格式 = debt-c<周期>-<债务人键>><债权人键>-<商品>；★ H0 起家户键 = <格>|<居住>|<阶层>；
        //   债务人键 = PEASANT_KEY = FARM|PEASANT = "farm@0_0" + "|" + "poor_peasant"（新词表）
        //   债权人键 = LANDLORD_KEY = "farm@0_0|landlord"（地主一词不变）；商品 = "grain"。
        .isEqualTo("debt-c1-0_0|rural|poor_peasant>0_0|rural|landlord-grain");
    assertThat(next.debts()).as("整场只此一条债（聚合后条数不随天数增长）").hasSize(1);
    EconomyFixtures.World oneDayWorld =
        data(rows, industries, goodsFor(PEASANT_KEY, 0L, LANDLORD_KEY, 5_000_000L));
    assertThat(
            EconomyFixtures.advance(oneDayWorld.data(), oneDayWorld.goods(), 0L, 1L)
                .debts()
                .values()
                .iterator()
                .next()
                .principal())
        .as("对照：第 1 天结束时本金只有一天的量 ⇒ 第 2 天确实是**累加**上去的，不是另建一条")
        .isEqualTo(rationOn(1L));
  }

  /**
   * ★★ **验收判据 4 逐值**：缸一直是空的 ⇒ 每个周期的播种日都扣不到 ⇒ **每个周期都颗粒无收** ⇒ 人越死越少 （"冬春吃空缸 ⇒ 播种日扣不到 ⇒
   * 减产"的多周期形态；判别力：删掉第三路瓶颈 ⇒ 第 1 周期就满产 25,996,000， 缸被填上、没人饿死 ⇒ 本条全红）。
   *
   * <p>★ **致死率显式注入 200‰**：本用例验的是"饿死的多周期级联"，而**默认致死率是 0‰**（V4：缺口照记、不死人）⇒
   * 必须走包内可见的重载，否则这条叙述在新默认值下不成立（默认路径由 {@code EconomySettlementTest} 钉住）。
   *
   * <pre>
   * 第 1 周期（第 1~2 天）：播 0、吃 0 ⇒ 缺口 = 头 2 天口粮 66,666 = 全额需求 ⇒ faminePerMille = 1000‰
   *     死 400 × 1000/1000 × 200/1000 = 80 ⇒ 人口 320、劳动 232,000 × 320/400 = 185,600
   *     收获 = 0（seedCapMu = 0）
   * 第 2 周期（第 3~4 天）：缸仍空 ⇒ 播 0、吃 0 ⇒ 缺口 = 累计(320,4) − 累计(320,2) = 53,333 = 全额需求 ⇒ 1000‰
   *     死 320 × 200/1000 = 64 ⇒ 人口 256、劳动 185,600 × 256/320 = 148,480；收获仍 = 0
   * </pre>
   */
  @Test
  void theEmptySpringJarMakesTheNextSowingFailAndTheHarvestCollapse() {
    EconomyFixtures.World world = farm(0L, seeds(SEED_PER_MU));

    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 4L, 200);

    ClassRow row = next.classes().get(PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);
    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("第 2 周期的播种日同样扣不到").isZero();
    assertThat(grainOf(world.goods(), PEASANT_KEY)).as("两个周期都颗粒无收").isZero();
    assertThat(flow.unmetNeed().getOrDefault(GRAIN, 0L))
        .as("第 2 周期缺口 = 累计(320,4) − 累计(320,2)（本期口径，不含第 1 周期；★ R4 起只读粮那一维）")
        .isEqualTo(
            EconomyVocabulary.cumulativeRationMilli(320L, 4L)
                - EconomyVocabulary.cumulativeRationMilli(320L, 2L));
    assertThat(flow.deaths()).as("★ 本期（第 2 周期）饿死 = 64；累计口径已废（§八.5）").isEqualTo(64L);
    assertThat(row.population()).as("400 → 320 → 256").isEqualTo(256L);
    assertThat(row.laborMilli()).as("劳动同比例缩：185,600 × 256/320").isEqualTo(148_480L);
    assertThat(next.meta().orElseThrow().lastClosedCycle()).as("两个周期都关过账").hasValue(2L);
  }

  /**
   * ★★ **账要平**（spec §6.1 / `EconomySettlementEndToEndTest.assertConserved` 的纯函数版）： 种子扣减计入 {@code
   * consumed} 之后，{@code Σ库存减少 == Σ消费 − Σ所得} 在**配了种子**的世界上仍成立。
   *
   * <p>★ 判别力：把播种步里的 {@code consumedGrain.merge(key, drawn, Long::sum)} 删掉 ⇒ 左边少了种子那一笔 （20,000）⇒
   * 本条必红。
   */
  @Test
  void theLedgerStaysBalancedEvenWithSeedDraws() {
    EconomyFixtures.World world = farm(20_000L, seeds(SEED_PER_MU));
    long stockBefore = grainOf(world.goods(), PEASANT_KEY);
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 2L);

    long stockAfter = grainOf(world.goods(), PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);
    long consumed = flow.consumed().getOrDefault(GRAIN, 0L);

    assertThat(consumed).as("消费里含种子 20,000（留种是本期消费）").isGreaterThanOrEqualTo(20_000L);
    assertThat(stockBefore - stockAfter)
        .as("Σ库存减少 == Σ消费 − Σ所得（毛产口径；★ R3 起两边都取粮那一维）")
        .isEqualTo(consumed - flow.income().getOrDefault(GRAIN, 0L));
  }
}
