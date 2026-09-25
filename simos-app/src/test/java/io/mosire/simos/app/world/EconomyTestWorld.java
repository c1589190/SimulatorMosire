package io.mosire.simos.app.world;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
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
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
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
    // (0,0) 平原 1000 人 / (1,0) 低丘 500 人 / (2,0) 只有城市 300 人。
    farm(industries, classes, 0, 0, 1000L, PLAINS_LAND_MILLI_MU, Stock.NORMAL);
    farm(industries, classes, 1, 0, 500L, HILLS_LAND_MILLI_MU, Stock.NORMAL);
    farm(industries, classes, 2, 0, 0L, PLAINS_LAND_MILLI_MU, Stock.NORMAL);
    craft(industries, classes, 2, 0, 300L);
    // (3,0) 有粮可借 / (4,0) 无粮可借。
    farm(
        industries,
        classes,
        3,
        0,
        LENDER_HEX_POPULATION,
        PLAINS_LAND_MILLI_MU,
        Stock.LANDLORD_ZERO_RICH_SURPLUS);
    farm(industries, classes, 4, 0, 1000L, PLAINS_LAND_MILLI_MU, Stock.LANDLORD_ZERO_OTHERS_EXACT);
    EconomyMeta meta =
        new EconomyMeta(
            MAP_ID, 0L, OptionalLong.empty(), EconomySeeder.RULES_VERSION, Optional.empty());
    return new EconomyData(Optional.of(meta), industries, classes, Map.of(), Map.of());
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
      int q,
      int r,
      long population,
      long landMilliMu,
      Stock stock) {
    IndustryId id = IndustryHexKeys.id(EconomySeeder.FARM, q, r);
    long[] people = EconomySeeder.splitByShares(population, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long[] land = EconomySeeder.splitProportional(landMilliMu, people);
    industries.put(
        id, industry(id, "农业", EconomySeeder.REGIME_FEUDAL, new AllocationRule.Split(700, 300)));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      addRow(classes, id, i, people[i], Map.of(AssetKind.LAND, land[i]), stock);
    }
  }

  /** 手工业（城市格追加，§十）：不占地 ⇒ 劳动瓶颈下实际投入亩 = 0（v1 不产出，如实记在报告里）。 */
  private static void craft(
      Map<IndustryId, Industry> industries,
      Map<ClassKey, ClassRow> classes,
      int q,
      int r,
      long population) {
    IndustryId id = IndustryHexKeys.id(EconomySeeder.CRAFT, q, r);
    long[] people = EconomySeeder.splitByShares(population, EconomySeeder.CLASS_SHARE_PER_MILLE);
    industries.put(
        id,
        industry(id, "手工业", EconomySeeder.REGIME_HANDICRAFT, new AllocationRule.Split(400, 600)));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      addRow(classes, id, i, people[i], Map.of(), Stock.NORMAL);
    }
  }

  private static void addRow(
      Map<ClassKey, ClassRow> classes,
      IndustryId industry,
      int index,
      long population,
      Map<AssetKind, Long> means,
      Stock stock) {
    String slot = EconomySeeder.CLASS_IDS[index];
    ClassKey key = new ClassKey(industry, new ClassSlotId(slot));
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
                  : ("rich".equals(slot)
                      ? richSurplusOpeningStock(population)
                      : EconomyVocabulary.cumulativeRationMilli(population, 60L));
          case LANDLORD_ZERO_OTHERS_EXACT ->
              "landlord".equals(slot)
                  ? 0L
                  : EconomyVocabulary.cumulativeRationMilli(population, 1L);
        };
    classes.put(
        key,
        new ClassRow(
            key,
            population,
            EconomySeeder.laborMilli(population),
            EconomySeeder.CLASS_LABOR_PER_MILLE[index],
            means,
            goods > 0L ? Map.of(GRAIN, goods) : Map.of(),
            0L,
            List.of(),
            Map.of(GRAIN, firstDayNeed),
            Map.of()));
  }

  private static Industry industry(IndustryId id, String name, String regime, AllocationRule rule) {
    List<ClassSlot> slots = new ArrayList<>();
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      slots.add(
          new ClassSlot(
              new ClassSlotId(EconomySeeder.CLASS_IDS[i]),
              EconomySeeder.CLASS_NAMES[i],
              EconomySeeder.CLASS_LABOR_PER_MILLE[i]));
    }
    return new Industry(
        id,
        name,
        new RegimeId(regime),
        CYCLE_DAYS,
        0L,
        Map.of(),
        0L,
        Map.of(GRAIN, EconomySeeder.GRAIN_OUTPUT_PER_MU),
        Map.of(), // ★★ 必须保持空：5 格端到端夹具是"未配种子 ⇒ V2 行为不变"的证据
        slots,
        rule,
        0L,
        0L);
  }
}
