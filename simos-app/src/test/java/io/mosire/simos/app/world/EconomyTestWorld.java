package io.mosire.simos.app.world;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.actor.ActorKind;
import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ **R3a/R4a 的小测试世界**（用户点名要"看多格运作"）：**5 格**、人口与地形各异、含一格城市格（手工业）、并刻意造出
 * **一格有粮可借**与**一格无粮可借**两种缺口。**只含 economy 切片**（本夹具服务经济结算的端到端与手工观察；地图/单位不参与 结算公式）。生成走 {@link
 * EconomySeeder} 的公开换算（人口比例 / 有效劳动 / 口粮），**不另写一套**。
 *
 * <p>★ 五格的账面（一切数字都是本夹具写死的字面量，供 e2e 逐值断言）：
 *
 * <table>
 *   <caption>格与人口</caption>
 *   <tr><th>格</th><th>地形</th><th>农村人口</th><th>城市人口</th><th>产业</th><th>土地（亩）</th><th>初始粮</th></tr>
 *   <tr><td>(0,0)</td><td>平原</td><td>1000</td><td>—</td><td>农业</td><td>3,100</td><td>按阶层天数（贫 30/中 60/富 120/地 250）</td></tr>
 *   <tr><td>(1,0)</td><td>低丘</td><td>500</td><td>—</td><td>农业</td><td>2,064</td><td>按阶层天数（贫 30/中 60/富 120/地 250）</td></tr>
 *   <tr><td>(2,0)</td><td>平原</td><td>0</td><td>300</td><td>农业 + 手工业</td><td>3,100（农业无地系数的城市格不产出）</td><td>按阶层天数</td></tr>
 *   <tr><td>(3,0)</td><td>平原</td><td>1000</td><td>—</td><td>农业</td><td>3,100</td><td>地主 0、富农 120 天 + 20 万</td></tr>
 *   <tr><td>(4,0)</td><td>平原</td><td>1000</td><td>—</td><td>农业</td><td>3,100</td><td>地主 0、其余恰 1 天</td></tr>
 * </table>
 *
 * <p>★ 缺口的两种形态（e2e 用例 b）：
 *
 * <ul>
 *   <li>**(3,0)**：地主当天颗粒无收、富农有**真余粮**（≥ 本周期自需 + 20 万）⇒ 触发**同格借粮**，产生一条 {@code Debt}（本金 = 地主缺口）。
 *   <li>**(4,0)**：地主同样缺、其余各行**恰好吃干**（消费后无余粮）⇒ **没人可借 ⇒ 不产生债务**（只留未满足的自然需求）。
 * </ul>
 *
 * <p>★ **V6 §7.1①（放贷方留口粮）对 (3,0) 的连带**：富农的缸从"60 天 + 20 万"抬到"**120 天 + 20 万**" —— 放贷方的可贷额 = {@code
 * 库存 − 本周期自需 × 1000‰}，而"60 天"小于本周期自需的 120 天 ⇒ 那是**零余粮**， "有粮可借"这条叙述会当场不成立。
 */
public final class EconomyTestWorld {

  /** 本测试世界的 mapId（写进 {@code EconomyMeta}）。 */
  public static final String MAP_ID = "econ-test";

  /** 粮食商品 id（与 {@code EconomySeeder} / 结算侧同字面量）。 */
  public static final CommodityId GRAIN = new CommodityId(EconomySeeder.COMMODITY_GRAIN);

  /** ★ R3：另外三种商品 —— **田里同时出粮与纤维**、作坊出布与工具，都是本夹具的判据所在。 */
  public static final CommodityId FIBER = new CommodityId(EconomySeeder.COMMODITY_FIBER);

  public static final CommodityId CLOTH = new CommodityId(EconomySeeder.COMMODITY_CLOTH);
  public static final CommodityId TOOL = new CommodityId(EconomySeeder.COMMODITY_TOOL);
  public static final CommodityId IRON = new CommodityId(EconomySeeder.COMMODITY_IRON);

  /** 农业周期（天）：与 {@code EconomySeeder.CYCLE_DAYS} 同源（120）。 */
  public static final long CYCLE_DAYS = EconomySeeder.CYCLE_DAYS;

  /** 平原（(0,0)/(3,0)/(4,0) 的农业格）：土地 = {@link EconomySeeder#MU_PER_HEX} 亩（现标定 3,100 亩）。 */
  public static final long PLAINS_LAND_MILLI_MU = EconomySeeder.MU_PER_HEX * 1000L;

  /** 低丘（(1,0)）：map 的 food 2 ⇒ 可耕地 2/3 ⇒ 土地 2,064 亩（现标定）。 */
  public static final long HILLS_LAND_MILLI_MU =
      PLAINS_LAND_MILLI_MU
          * EconomySeeder.arablePerMilleOf(EconomySeeder.foodOf("low_hills"))
          / 1000L;

  /** 有粮可借的缺口格（3,0）：地主 0 库存 / 富农 = **一整个周期自需 + 20 万**毫粮。 */
  public static final long LENDER_HEX_POPULATION = 1000L;

  /** 借粮利率（‰）：§四 / 用户口径 20‰。 */
  public static final int BORROW_RATE_PER_MILLE = 20;

  /** 有粮可借格的地主缺口（毫粮）= 50 人**第 1 天**的口粮（{@link EconomyVocabulary#dailyRationMilli}，逐日差分）。 */
  public static final long LENDER_HEX_LANDLORD_DEFICIT =
      EconomyVocabulary.dailyRationMilli(50L, 1L);

  /**
   * 富农那一格的额外存粮（毫粮）：**真正的余粮** = 它自己 120 天的口粮**之外**多出来的部分。
   *
   * <p>★★ **V6 §7.1① 起，光是"多 20 万"不构成余粮**：放贷方必须留 {@code 本周期自需 × 1000‰}（{@code
   * EconomySettlement.LENDER_SUBSISTENCE_RESERVE_PER_MILLE}）⇒ 富农的缸改成 {@code 一整个周期自需 + 这 20 万} （见
   * {@link Stock#LANDLORD_ZERO_RICH_SURPLUS}），"有粮可借"这条叙述才继续成立。
   */
  public static final long RICH_EXTRA_GRAIN_MILLI = 200_000L;

  /**
   * 有粮可借格的富农**开缸库存**（毫粮）= {@code 一整个周期自需 + RICH_EXTRA_GRAIN_MILLI} —— 与 {@link
   * Stock#LANDLORD_ZERO_RICH_SURPLUS} 里那一支**同式**（故端到端用例的逐值期望由常量推出，不抄字面量）。
   */
  public static long richSurplusOpeningStock(long population) {
    return EconomyVocabulary.cumulativeRationMilli(population, CYCLE_DAYS) + RICH_EXTRA_GRAIN_MILLI;
  }

  private EconomyTestWorld() {}

  /** 只带 economy 切片的创世状态（{@code (main,1)}，时刻 0）。 */
  public static SimulationState genesis() {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(0);
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("economy", new EconomySnapshot(ref, at, data()));
    return new SimulationState(new StateMeta(ref, at), modules, InMemoryInfoSystem.empty());
  }

  /** 五格的经济状态（已激活；{@code lastClosedCycle} 空 = 还没关过账）。 */
  public static EconomyData data() {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    Map<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    Map<PeopleLotId, LaborSupply> supply = new LinkedHashMap<>();
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    // (0,0) 平原 1000 人 / (1,0) 低丘 500 人 / (2,0) 只有城市 300 人。
    farm(industries, classes, supply, allocations, 0, 0, 1000L, PLAINS_LAND_MILLI_MU, Stock.NORMAL);
    farm(industries, classes, supply, allocations, 1, 0, 500L, HILLS_LAND_MILLI_MU, Stock.NORMAL);
    farm(industries, classes, supply, allocations, 2, 0, 0L, PLAINS_LAND_MILLI_MU, Stock.NORMAL);
    craft(industries, classes, supply, allocations, 2, 0, 300L);
    // (3,0) 有粮可借 / (4,0) 无粮可借。
    farm(
        industries,
        classes,
        supply,
        allocations,
        3,
        0,
        LENDER_HEX_POPULATION,
        PLAINS_LAND_MILLI_MU,
        Stock.LANDLORD_ZERO_RICH_SURPLUS);
    farm(
        industries,
        classes,
        supply,
        allocations,
        4,
        0,
        1000L,
        PLAINS_LAND_MILLI_MU,
        Stock.LANDLORD_ZERO_OTHERS_EXACT);
    // ★★ **R3：非土地生产**（有农村人口的格各一座家庭纺织）—— 它让"份外之地"这件事在夹具里就成立：
    //   织机不占地、纤维与织机都不来自土地那一路，规模由**最紧约束**（劳动 vs 织机 vs 纤维）决定。
    //   ★ 劳动那一条在 {@link #farm} 里与农业一起发（同一批农村人的 900‰/100‰）—— 一格的农村劳动只在一处决定。
    //   ★ (2,0) 农业人口为 0 ⇒ **没有纺织**（配额为 0 ⇒ 纺织行没有活干 ⇒ 连产业都不建）。
    weaving(industries, classes, 0, 0, 1000L);
    weaving(industries, classes, 1, 0, 500L);
    weaving(industries, classes, 3, 0, LENDER_HEX_POPULATION);
    weaving(industries, classes, 4, 0, 1000L);
    EconomyMeta meta =
        new EconomyMeta(
            MAP_ID, 0L, OptionalLong.empty(), EconomySeeder.RULES_VERSION, Optional.empty());
    return new EconomyData(
        Optional.of(meta), industries, classes, Map.of(), Map.of(), supply, allocations);
  }

  /** 初始库存口径（毫粮）：普通行 = 按阶层天数（贫 30/中 60/富 120/地 250）；两种缺口形态见枚举。 */
  private enum Stock {
    NORMAL,
    /** 地主 0、富农 **120 天 + 20 万**（同格有人**有真余粮**可借）。 */
    LANDLORD_ZERO_RICH_SURPLUS,
    /** 地主 0、其余恰好吃一天（消费后无余粮 ⇒ 无人可借）。 */
    LANDLORD_ZERO_OTHERS_EXACT
  }

  private static void farm(
      Map<IndustryId, Industry> industries,
      Map<ClassKey, ClassRow> classes,
      Map<PeopleLotId, LaborSupply> supply,
      Map<LaborAllocationId, LaborAllocation> allocations,
      int q,
      int r,
      long population,
      long landMilliMu,
      Stock stock) {
    IndustryId id = IndustryHexKeys.id(EconomySeeder.FARM, q, r);
    long[] people = EconomySeeder.splitByShares(population, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long[] land = EconomySeeder.splitProportional(landMilliMu, people);
    industries.put(
        id,
        industry(
            id,
            "农业",
            EconomySeeder.REGIME_FEUDAL,
            new AllocationRule.Split(700, 300),
            // ★ R3（V7）：规模单位 = 亩；每亩 143 千分劳动；**田里同时出粮与纤维**（纤维是副产物 ⇒ 多商品产出的判据所在）。
            Map.of(AssetKind.LAND, 1_000L),
            EconomySettlement.LABOR_MILLI_PER_MU,
            Map.of(
                GRAIN, EconomySeeder.GRAIN_OUTPUT_PER_MU, FIBER, EconomySeeder.FIBER_OUTPUT_PER_MU),
            // ★★ 必须保持空：5 格端到端夹具是"未配投入 ⇒ 投入那一路不施加约束"的对照
            Map.of()));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      addRow(classes, id, i, people[i], Map.of(AssetKind.LAND, land[i]), stock, Map.of());
    }
    // ★★ **同一批农村人的劳动分成两条配额**（R3；spec §四 的压力测试）：农业 900‰ + 家庭纺织 100‰。
    //   ★ 两条之和 = 该池的当日劳动（{@code ruralDaily}）⇒ 与 R2 的"一池一产业"逐值同源；
    //     而"同一批次供给两个产业"在这里是**结构上**成立的（EconomyData 的构造期守卫判 Σ ≤ 可用）。
    addSupply(supply, id, people, population);
    long ruralDaily =
        EconomySeeder.industryDailyLabor(people, EconomySeeder.laborMilli(population), population);
    long weaveQuota = ruralDaily * EconomySeeder.WEAVE_SHARE_PER_MILLE / 1000L;
    addAllocation(
        allocations,
        id,
        ActorKind.ESTATE,
        EconomySeeder.FARM,
        syntheticLot(id),
        ruralDaily - weaveQuota);
    if (weaveQuota > 0L) {
      IndustryId weaveId = IndustryHexKeys.id(EconomySeeder.WEAVE, q, r);
      addAllocation(
          allocations,
          weaveId,
          ActorKind.HOUSEHOLD,
          EconomySeeder.WEAVE,
          syntheticLot(weaveId),
          weaveQuota);
    }
  }

  /**
   * ★★ **R3：城市作坊**（城市格追加，§十）：配方 {@code FIBER + IRON + LABOR + WORKSHOP → CLOTH + TOOL}。
   *
   * <p>★★ **它证明两件事**（spec §六 给 T5 定的目的）：**非 LAND 生产成立**（规模由"几座作坊"与劳动决定，与土地无关）、
   * **城市能产出自己的产品**（布与工具，不是粮）。★ 城乡交换（布换粮）不在本轮。
   *
   * <p>★ 工坊数与原料都按 {@link EconomySeeder} 的场景参数给（{@link EconomySeeder#URBAN_CAPITA_PER_WORKSHOP} 人一座；
   * 原料 = 该行作坊数**一个周期**的用量）⇒ 夹具与真档同一套口径，不是另一组拍出来的数。
   */
  private static void craft(
      Map<IndustryId, Industry> industries,
      Map<ClassKey, ClassRow> classes,
      Map<PeopleLotId, LaborSupply> supply,
      Map<LaborAllocationId, LaborAllocation> allocations,
      int q,
      int r,
      long population) {
    IndustryId id = IndustryHexKeys.id(EconomySeeder.CRAFT, q, r);
    long[] people = EconomySeeder.splitByShares(population, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long workshops = population / EconomySeeder.URBAN_CAPITA_PER_WORKSHOP;
    long[] shopByClass =
        EconomySeeder.splitByShares(workshops, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long fiberPerShop = clothPerWorkshop() * EconomySeeder.FIBER_MILLI_PER_CLOTH;
    long ironPerShop =
        EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE * EconomySeeder.IRON_MILLI_PER_TOOL;
    industries.put(
        id,
        industry(
            id,
            "手工业",
            EconomySeeder.REGIME_HANDICRAFT,
            new AllocationRule.Split(400, 600),
            Map.of(AssetKind.WORKSHOP, 1L),
            EconomySeeder.LABOR_MILLI_PER_WORKSHOP,
            Map.of(CLOTH, clothPerWorkshop(), TOOL, EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE),
            Map.of(AssetKind.WORKSHOP, Map.of(FIBER, fiberPerShop, IRON, ironPerShop))));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      addRow(
          classes,
          id,
          i,
          people[i],
          shopByClass[i] == 0L ? Map.of() : Map.of(AssetKind.WORKSHOP, shopByClass[i]),
          Stock.NORMAL,
          // ★ 原料库存 = 该行作坊数 × 一座作坊**一个周期**的用量（自洽，不是一个拍出来的总量）。
          Map.of(FIBER, shopByClass[i] * fiberPerShop, IRON, shopByClass[i] * ironPerShop));
    }
    addSupply(supply, id, people, population);
    addAllocation(
        allocations,
        id,
        ActorKind.WORKSHOP,
        EconomySeeder.CRAFT,
        syntheticLot(id),
        EconomySeeder.industryDailyLabor(people, EconomySeeder.laborMilli(population), population));
  }

  /**
   * ★★ **R3：农村家庭纺织**（有农村人口的格各一座）：配方 {@code FIBER + LABOR + TOOL → CLOTH}（spec §四 的压力测试）。
   *
   * <p>★★ **它的四行不携带人口**（{@code population = 0}、{@code laborMilli = 0}）：本格的农村人口住在**农业行**里 （R1 的"Σ
   * 阶层行人口 == 格人口"是构造性守恒，再给一条人口就是重复计数）。织造是**同一批人的第二份活** —— 支撐它的是**劳动配额**（农村批次 900‰ → 农业 + 100‰ →
   * 纺织），而不是第二份人口。★ 于是整份夹具的人口账一分不动。
   *
   * <p>★★ **织机与纤维从哪来**：与真档同款（{@link EconomySeeder#RURAL_CAPITA_PER_LOOM} 人一台织机；纤维 = 那些织机**一个周期**的
   * 用量）。★ 把农田的纤维**实物搬**到织机行是 V8 的活（brief 明说不建议本轮做跨行实物转移）⇒ 本夹具与真档都吃这份明标来源的库存。
   */
  private static void weaving(
      Map<IndustryId, Industry> industries,
      Map<ClassKey, ClassRow> classes,
      int q,
      int r,
      long ruralPopulation) {
    IndustryId id = IndustryHexKeys.id(EconomySeeder.WEAVE, q, r);
    long looms = ruralPopulation / EconomySeeder.RURAL_CAPITA_PER_LOOM;
    long[] loomByClass = EconomySeeder.splitByShares(looms, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long fiberPerLoom = clothPerLoom() * EconomySeeder.FIBER_MILLI_PER_CLOTH;
    industries.put(
        id,
        industry(
            id,
            "家庭纺织",
            EconomySeeder.REGIME_HOUSEHOLD,
            new AllocationRule.Split(300, 700),
            Map.of(AssetKind.TOOL, 1L),
            EconomySeeder.LABOR_MILLI_PER_LOOM,
            Map.of(CLOTH, clothPerLoom()),
            Map.of(AssetKind.TOOL, Map.of(FIBER, fiberPerLoom))));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      addStockRow(
          classes,
          id,
          i,
          loomByClass[i] == 0L ? Map.of() : Map.of(AssetKind.TOOL, loomByClass[i]),
          loomByClass[i] * fiberPerLoom == 0L
              ? Map.of()
              : Map.of(FIBER, loomByClass[i] * fiberPerLoom));
    }
  }

  /** 每座作坊每周期产布（匹）：真档口径（{@link EconomySeeder#CLOTH_PER_WORKSHOP_PER_CYCLE}）。 */
  private static long clothPerWorkshop() {
    return EconomySeeder.CLOTH_PER_WORKSHOP_PER_CYCLE;
  }

  /** 每台织机每周期产布（匹）：真档口径（{@link EconomySeeder#CLOTH_PER_LOOM_PER_CYCLE}）。 */
  private static long clothPerLoom() {
    return EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE;
  }

  /**
   * ★★ **供给行**（每池一条；R3 起与配额分家，见 {@code EconomySeeder.appendSupply} 的注释）：本夹具没有真的批次 ⇒ 批次 id 按真档的
   * 命名约定**合成**（{@code PopulationLots} 的拼法）。
   *
   * <p>★ 毛额取该池的毛劳动（= {@code 人口 × 580‰}，与行的口径同源）⇒ 配额之和 ≤ 毛额恒成立。
   */
  private static void addSupply(
      Map<PeopleLotId, LaborSupply> supply, IndustryId industry, long[] people, long population) {
    long grossLabor = EconomySeeder.laborMilli(population);
    if (grossLabor <= 0L) {
      return; // 零人口的格（(2,0) 的农业）：没有可支配劳动 ⇒ 不发供给
    }
    PeopleLotId lot = syntheticLot(industry);
    supply.put(lot, new LaborSupply(lot, EconomySeeder.FIRST_PERIOD, grossLabor, 0L, 0L));
  }

  /**
   * **一条配额**：{@code (产业, 批次)} 一条，量取自 {@link EconomySeeder#industryDailyLabor(long[], long, long)}
   * （**同一个算式**，本类不另写一套 —— 那正是"改口径前的当日劳动"，R2 的真档数字因此一个不变）。
   */
  private static void addAllocation(
      Map<LaborAllocationId, LaborAllocation> allocations,
      IndustryId industry,
      ActorKind kind,
      String activity,
      PeopleLotId lot,
      long laborMilli) {
    if (laborMilli <= 0L) {
      return;
    }
    allocations.put(allocationId(industry), allocation(industry, kind, activity, lot, laborMilli));
  }

  private static LaborAllocation allocation(
      IndustryId industry, ActorKind kind, String activity, PeopleLotId lot, long laborMilli) {
    return new LaborAllocation(
        allocationId(industry),
        lot,
        new ActorRef(kind, industry.value()),
        activity,
        laborMilli,
        EconomySeeder.FIRST_PERIOD);
  }

  /** 合成批次 id：与真档的命名约定同形（{@code rural:<q>_<r>:MALE:1} / {@code urban:c-<q>_<r>:FEMALE:1}）。 */
  private static PeopleLotId syntheticLot(IndustryId industry) {
    String hex = IndustryHexKeys.hexKeyOf(industry).orElseThrow();
    return new PeopleLotId(
        industry.value().startsWith(EconomySeeder.FARM)
                || industry.value().startsWith(EconomySeeder.WEAVE)
            ? "rural:" + hex + ":MALE:1"
            : "urban:c-" + hex + ":FEMALE:1");
  }

  private static LaborAllocationId allocationId(IndustryId industry) {
    return new LaborAllocationId("alloc-" + industry.value() + "-testworld");
  }

  /**
   * 一个**不携带人口**的阶层行（R3 的家庭纺织：本格的人住在农业行里，见 {@code weaving}）：只有生产资料与库存。
   *
   * <p>★ 人口/劳动都是 0 ⇒ 需求也是 0（{@code naturalNeeds} 空表）⇒ 它**不进任何口粮账**，只承载"织机与原料"。
   */
  private static void addStockRow(
      Map<ClassKey, ClassRow> classes,
      IndustryId industry,
      int index,
      Map<AssetKind, Long> means,
      Map<CommodityId, Long> goods) {
    String slot = EconomySeeder.CLASS_IDS[index];
    ClassKey key = new ClassKey(industry, new SocialClassId(slot));
    classes.put(
        key,
        new ClassRow(
            key,
            0L,
            0L,
            EconomySeeder.CLASS_LABOR_PER_MILLE[index],
            means,
            goods,
            0L,
            List.of(),
            Map.of(),
            Map.of()));
  }

  private static void addRow(
      Map<ClassKey, ClassRow> classes,
      IndustryId industry,
      int index,
      long population,
      Map<AssetKind, Long> means,
      Stock stock,
      Map<CommodityId, Long> extraGoods) {
    String slot = EconomySeeder.CLASS_IDS[index];
    ClassKey key = new ClassKey(industry, new SocialClassId(slot));
    // ★★ **第 1 天**的需求（逐日差分；不是"每人每日的量 × 人口"）—— 这一格从第 1 天起就是"恰好"形态。
    long firstDayNeed = EconomyVocabulary.dailyRationMilli(population, 1L);
    // ★ 多日储备一律用**累计**函数表达（60 天 = cumulativeRationMilli(人口, 60)），不许写成"人口 × 一天的量 × 60"。
    long goods =
        switch (stock) {
          case NORMAL -> EconomySeeder.rationMilli(population, slot);
          // ★★ **V6 §7.1① 修夹具**：富农的缸 = **一整个周期自需**（120 天）+ 20 万。
          //   旧夹具给的是"60 天 + 20 万"—— 在新口径下那**不是余粮**（60 天 < 本周期自需 120 天 ⇒
          //   可贷额 = max(0, 缸 − 120 天口粮) = 0）⇒ "有粮可借"这条叙述当场不成立。
          //   ★ **实测过**（把这一支退回旧式）：端到端用例 (a) `oneDayConsumes…` 与 (b) `deficitWithALender…`
          //     同时红（"地主背上一条债务" / "全格仍恰好吃满各自的口粮"）⇒ 是**夹具违反新口径**，不是护栏太严。
          case LANDLORD_ZERO_RICH_SURPLUS ->
              "landlord".equals(slot)
                  ? 0L
                  : ("rich_peasant".equals(slot)
                      ? richSurplusOpeningStock(population)
                      : EconomyVocabulary.cumulativeRationMilli(population, 60L));
          case LANDLORD_ZERO_OTHERS_EXACT ->
              "landlord".equals(slot)
                  ? 0L
                  : EconomyVocabulary.cumulativeRationMilli(population, 1L);
        };
    Map<CommodityId, Long> openingStock = new LinkedHashMap<>();
    if (goods > 0L) {
      openingStock.put(GRAIN, goods);
    }
    for (Map.Entry<CommodityId, Long> entry : extraGoods.entrySet()) {
      if (entry.getValue() > 0L) {
        openingStock.put(entry.getKey(), entry.getValue());
      }
    }
    classes.put(
        key,
        new ClassRow(
            key,
            population,
            EconomySeeder.laborMilli(population),
            EconomySeeder.CLASS_LABOR_PER_MILLE[index],
            means,
            openingStock,
            0L,
            List.of(),
            Map.of(GRAIN, firstDayNeed),
            Map.of()));
  }

  /**
   * 一个产业（**R3 起带 V7 的四个配方分量**）：每个产业自己锚定"单位规模"（农业 = 亩、织机/作坊 = 台/座）， 产出是**逐商品**的表 ⇒ "每单位什么"在夹具里也是数据。
   */
  private static Industry industry(
      IndustryId id,
      String name,
      String regime,
      AllocationRule rule,
      Map<AssetKind, Long> capacityPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit) {
    List<ClassSlot> slots = new ArrayList<>();
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      slots.add(
          new ClassSlot(
              new SocialClassId(EconomySeeder.CLASS_IDS[i]),
              EconomySeeder.CLASS_NAMES[i],
              EconomySeeder.CLASS_LABOR_PER_MILLE[i]));
    }
    return new Industry(
        id,
        name,
        new RegimeId(regime),
        CYCLE_DAYS,
        0L,
        capacityPerUnit,
        Map.of(),
        0L,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        rule,
        0L,
        Map.of());
  }
}
