package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.MarketSeeder.genesisMoney;
import static io.mosire.simos.app.world.ProductionRoleSeeder.isMerchantClassSlot;
import static io.mosire.simos.app.world.RuralLaborSeeder.fiberPerWorkshopMilli;
import static io.mosire.simos.app.world.RuralLaborSeeder.fiberStockMilli;
import static io.mosire.simos.app.world.RuralLaborSeeder.firstDayRationMilli;
import static io.mosire.simos.app.world.RuralLaborSeeder.householdBudgetMilli;
import static io.mosire.simos.app.world.RuralLaborSeeder.initialRationDays;
import static io.mosire.simos.app.world.RuralLaborSeeder.ironPerWorkshopMilli;

import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 创世家户行、开缸库存/钱包与 Social 侧人口切分。 */
class RuralHouseholdSeeder extends WorldBalanceSeeder {

  // ── 家户行：{@code (格, 居住类型, 阶层)}（H0.2）────────────────────────────────────────

  /**
   * ★★ **农村四行**（一层 = 该格农村家户的四个阶层）：人口 = 该格**农村批次**之和按 {@link #CLASS_SHARE_PER_MILLE} 切。
   *
   * <p>★ **它们同时是"农业的行"与"家庭纺织的行"**：农业与纺织是**同一批人的两份活**（900‰ + 100‰ 两条配额）， 故只有一本账。
   *
   * @param landMilliMu 本格可耕地（千分亩）—— 只用来推"本格农田一个周期的纤维副产"这份初始库存
   */
  /**
   * ★★ **农村四行 + 可选流民行**（P2-A：家户身份与人口都从 {@link PopulationSeeder.Seeding} 的 Social 侧切分读入，
   * 经济侧不再自己按阶层比例二次切人、也不再另造家户 id）。
   *
   * <p>★ **纤维的去处**：旧版按阶层份额落在那四行**纺织行**上，H0.2 起并入**农村四行**（同一批人的同一本账）。
   */
  static List<Map<String, Object>> ruralCohort(
      HexCoord hex,
      List<PopulationGroup> pool,
      List<PopulationGroup> allPool,
      long landMilliMu,
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      long genesisMoneyMilliPerCapita,
      PoolCohorts cohorts,
      CurrencyId numeraire) {
    Map<String, long[]> goods = new LinkedHashMap<>();
    goods.put(COMMODITY_FIBER, splitByShares(fiberStockMilli(landMilliMu), CLASS_SHARE_PER_MILLE));
    return cohortGroup(
        hex,
        ResidenceKind.RURAL,
        pool,
        allPool,
        goods,
        locations,
        stocks,
        money,
        genesisMoneyMilliPerCapita,
        cohorts,
        numeraire);
  }

  /**
   * ★★ **城镇四行**：人口 = 该格**城镇批次**之和按 {@link #CLASS_SHARE_PER_MILLE} 切（**逐值 = 旧版 craft 四行**）。
   *
   * <p>★ **原料库存的去处**：旧版 craft 四行里的纤维与铁按"该行作坊数 × 一座作坊一个周期的用量"持有， H0.2 起并入**城镇四行**（同一批人的同一本账）、H1
   * 起落进那四个家户的 {@code HouseholdInventory}（见 {@link #openingStock}） —— 逐值同式，只是行键不再带产业、且账本搬到了 actor 侧。
   *
   * @param workshops 本格作坊总数（= 城镇人口 ÷ {@link #URBAN_CAPITA_PER_WORKSHOP}）
   */
  /**
   * ★★ **城镇四行 + 可选流民行**：人口 = Social 侧 {@link PopulationSeeder.Seeding} 里该 (格, 城镇, 阶层) 家户的人数 （**逐值 =
   * 旧版 craft 四行**）。
   *
   * <p>★ **原料库存的去处**：旧版 craft 四行里的纤维与铁按"该行作坊数 × 一座作坊一个周期的用量"持有， H0.2 起并入**城镇四行**（同一批人的同一本账）、H1
   * 起落进那四个家户的 {@code HouseholdInventory}（见 {@link #openingStock}） —— 逐值同式，只是行键不再带产业、且账本搬到了 actor 侧。
   *
   * @param workshops 本格作坊总数（= 城镇人口 ÷ {@link #URBAN_CAPITA_PER_WORKSHOP}）
   */
  static List<Map<String, Object>> urbanCohort(
      HexCoord hex,
      List<PopulationGroup> pool,
      List<PopulationGroup> allPool,
      long workshops,
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      long genesisMoneyMilliPerCapita,
      PoolCohorts cohorts,
      CurrencyId numeraire) {
    long[] shopByClass = splitByShares(workshops, CLASS_SHARE_PER_MILLE);
    long[] fiber = new long[CLASS_IDS.length];
    long[] iron = new long[CLASS_IDS.length];
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★ 原料库存 = 该阶层分到的作坊数 × 一座作坊**一个周期**的用量（自洽：不是一个拍出来的总量）。
      fiber[i] = shopByClass[i] * fiberPerWorkshopMilli();
      iron[i] = shopByClass[i] * ironPerWorkshopMilli();
    }
    Map<String, long[]> goods = new LinkedHashMap<>();
    goods.put(COMMODITY_FIBER, fiber);
    goods.put(COMMODITY_IRON, iron);
    // ★★ M-C：跑商门槛 = 工具（H-D）⇒ 创世给"选了跑商"的城镇家户一份启动工具（理由/断点见
    //   {@link #MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}）。★ 判据不写死槽位号：走**位置目录**
    //   （{@link #productionRuntimePositionId} → {@code DefaultProductionModes}）⇒ 阶层↔位置的映射只有一处真相。
    long[] tool = new long[CLASS_IDS.length];
    for (int i = 0; i < CLASS_IDS.length; i++) {
      tool[i] =
          isMerchantClassSlot(ResidenceKind.URBAN, CLASS_IDS[i])
              ? MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI
              : 0L;
    }
    goods.put(COMMODITY_TOOL, tool);
    return cohortGroup(
        hex,
        ResidenceKind.URBAN,
        pool,
        allPool,
        goods,
        locations,
        stocks,
        money,
        genesisMoneyMilliPerCapita,
        cohorts,
        numeraire);
  }

  /**
   * ★★ <b>一个池的 Social 侧家户切分（P2-A 的 A1 接缝）</b>：Social 是家户身份与人口的真值源，经济侧只读。
   *
   * <pre>
   * raw = { PeopleLotId → {m,i,z,t,s,i}}         // 可选留痕，不进状态
   * classHouseholds[i] = householdByCohort[(hex, residence, CLASS_IDS[i])]
   * classPopulations[i] = Σ members(lot)         // 该阶层的批次人数之和（= Social 侧切分结果，不再二次切）
   * displaced           = householdByCohort[(hex, residence, landless_laborer)]（可能缺）
   * </pre>
   *
   * <p>★ 缺常规家户 ⇒ fail-closed（Social 与 Economy 的格集/居住类型必须一一对应）。
   */
  static PoolCohorts poolCohorts(
      PopulationSeeder.Seeding seeding, HexCoord hex, ResidenceKind residence) {
    List<HouseholdId> classHouseholds = new ArrayList<>(CLASS_IDS.length);
    long[] classPopulations = new long[CLASS_IDS.length];
    for (int i = 0; i < CLASS_IDS.length; i++) {
      CohortKey key = new CohortKey(hex, residence, new SocialClassId(CLASS_IDS[i]));
      HouseholdId household =
          seeding
              .householdOfCohort(key)
              .orElseThrow(
                  () -> new IllegalStateException("Social 播种缺家户（P2-A 起经济侧只读 Social 的家户集）：" + key));
      classHouseholds.add(household);
      classPopulations[i] = seeding.populationOf(household);
    }
    HouseholdId displaced =
        seeding
            .householdOfCohort(new CohortKey(hex, residence, SocialClassId.LANDLESS_LABORER))
            .orElse(null);
    long displacedPopulation = displaced == null ? 0L : seeding.populationOf(displaced);
    return new PoolCohorts(
        residence, classHouseholds, classPopulations, displaced, displacedPopulation);
  }

  /** 从池里剔除流民批次（D-023：流民不进 laborSupply/allocations/资产；但成员份额用全池）。 */
  static List<PopulationGroup> withoutDisplaced(List<PopulationGroup> pool, PoolCohorts cohorts) {
    if (cohorts.displacedHousehold() == null) {
      return pool;
    }
    List<PopulationGroup> out = new ArrayList<>(pool.size());
    for (PopulationGroup group : pool) {
      if (!cohorts.displacedHousehold().equals(cohorts.householdOfGroup(group))) {
        out.add(group);
      }
    }
    return out;
  }

  /**
   * **一组四行**（同一格、同一居住类型、四个阶层）：人口按阶层比例切（Σ 恰为该池人口），有效劳动 = 该行人口 × **该池的人均劳动**（见 {@link
   * #householdEconomy} 的旧注：阶层之间"年龄性别同分布"这条明说的假设）。
   *
   * <p>★ {@code goodsByClass} 是"逐商品的**逐阶层**存量表"（与 {@link #CLASS_IDS} 同序）：0 ⇒ 不落键（保持空商品表的纯形态）。
   *
   * <p>★★ <b>H4：货币与商品在**同一处**算定</b>（{@code money} 出参）：{@link #genesisMoney(long)} 按**同一份人口</b>
   * （{@code people[i]}）算出 ⇒ "这家户有多少人、于是有多少口粮、于是有多少钱"三件事不会各算一遍而漂开。
   */
  /**
   * ★★ <b>一组家户行</b>（P2-A：身份/人口/成员份额都从 Social 侧的 {@link PoolCohorts} 读入，经济侧不二次切分）。
   *
   * <p>★ {@code goodsByClass} 是"逐商品的**逐阶层**存量表"（与 {@link #CLASS_IDS} 同序）：0 ⇒ 不落键（保持空商品表的纯形态）。
   *
   * <p>★★ <b>memberships 的口径（P2-A 的 A3）</b>：逐 lot 直接从 Social 的成员关系投影（{@code (lot, household,
   * count)}），<b>包括流民户的批次</b>——流民行不参与劳动/资产，但它的成员份额是真实的社会构成。这样 {@code Σ Membership.count == Σ
   * HouseholdEconomy.population} 与"逐 lot Σcount == PopulationGroup.count"两条守卫同时自然成立。
   */
  private static List<Map<String, Object>> cohortGroup(
      HexCoord hex,
      ResidenceKind residence,
      List<PopulationGroup> pool,
      List<PopulationGroup> allPool,
      Map<String, long[]> goodsByClass,
      Map<HouseholdId, HexCoord> locations,
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      long genesisMoneyMilliPerCapita,
      PoolCohorts cohorts,
      CurrencyId numeraire) {
    long[] people = cohorts.classPopulations();
    long displacedPopulation = cohorts.displacedPopulation();
    List<Map<String, Object>> rows =
        new ArrayList<>(CLASS_IDS.length + (displacedPopulation > 0L ? 1 : 0));
    for (int i = 0; i < CLASS_IDS.length; i++) {
      // ★★ **H1：这个家户的开缸库存 → actor 侧的账本**（键 = {@link HouseholdActors#of} 的那个家户身份）。
      //   ★ **空账也落键**（人口 0 ⇒ 余额全 0）：读口因此读得到"这个家户在这一格有一本账"（既定口径），
      //     而"账本为空"与"这一格没有这个家户"是两件事。
      HouseholdId key = cohorts.classHouseholds().get(i);
      locations.put(key, hex);
      stocks.put(key, openingStock(people[i], CLASS_IDS[i], goodsByClass, i));
      // ★★ H4：创世货币禀赋走**同一本账**（actor 侧的同一个 {@code HouseholdInventory}）—— 见 {@link
      // #genesisMoneyMilliPerCapita}。
      money.put(key, genesisMoney(people[i], genesisMoneyMilliPerCapita, numeraire));
      rows.add(
          cohortRow(
              key,
              residence,
              CLASS_IDS[i],
              people[i],
              CLASS_LABOR_PER_MILLE[i],
              householdBudgetMilli(allPool, cohorts, key)));
    }
    if (displacedPopulation > 0L) {
      // ★★ D-023：流民没有工作、没有自动组织 —— 只落一条人口/需求行与它的家户账（库存/货币），不进劳动与资产表。
      HouseholdId displacedKey = cohorts.displacedHousehold();
      locations.put(displacedKey, hex);
      stocks.put(
          displacedKey,
          openingStock(displacedPopulation, initialRationDays(CLASS_IDS[0]), Map.of(), 0));
      money.put(
          displacedKey, genesisMoney(displacedPopulation, genesisMoneyMilliPerCapita, numeraire));
      rows.add(
          cohortRow(
              displacedKey,
              residence,
              DISPLACED_SLOT,
              displacedPopulation,
              0,
              householdBudgetMilli(allPool, cohorts, displacedKey)));
    }
    return rows;
  }

  /**
   * /** ★ {人口：{@code floor(池人口 × }{@link #DISPLACED_SEED_PER_MILLE}{@code ÷ 1000)}}——切不出 ≥1 人时返回 0。
   */
  static long displacedSeedPopulation(long poolPopulation) {
    if (poolPopulation <= 0L) {
      return 0L;
    }
    return poolPopulation * DISPLACED_SEED_PER_MILLE / 1000L;
  }

  /**
   * ★★ <b>一个家户的创世库存</b>（H1；**行里不再有 {@code goods}** ⇒ 这是它的唯一拼写点）：口粮按阶层天数 （{@code 人口 × 该阶层天数} 天，见
   * {@link #rationMilli}）+ 逐行给出的其它商品（R3：农村行的纤维、城镇行的纤维与铁）。
   *
   * <p>★ <b>只落正的量</b>（0 ⇒ 不落键，保持"空账"的纯形态）—— 与旧版 {@code row.goods} 的口径逐字相同。
   */
  static Map<CommodityId, Long> openingStock(
      long population, String slot, Map<String, long[]> goodsByClass, int index) {
    return openingStock(population, initialRationDays(slot), goodsByClass, index);
  }

  /**
   * 上一条的**显式口粮天数**形态（流民行没有阶层槽位可查：它按最贫一档的天数走，见 {@link #DISPLACED_SLOT}）—— 库存的 "只落正的量 +
   * 同一处算定"口径与上一条逐字相同，不另写第二份算式。
   */
  static Map<CommodityId, Long> openingStock(
      long population, int rationDays, Map<String, long[]> goodsByClass, int index) {
    Map<CommodityId, Long> stock = new LinkedHashMap<>();
    putStockIfPositive(
        stock, COMMODITY_GRAIN, EconomyVocabulary.cumulativeRationMilli(population, rationDays));
    for (Map.Entry<String, long[]> entry : goodsByClass.entrySet()) {
      putStockIfPositive(stock, entry.getKey(), entry.getValue()[index]);
    }
    return stock;
  }

  /**
   * ★★ **一个家户行**（H0.2 起与 §3.2 {@code HouseholdEconomy} 逐字段对应，**除 {@code meansOfProduction} 外** ——
   * 那一项已按 K3 搬到 {@code IndustryDefinitionView.capacity}）：身份 = {@code (residence, slot)}（格由它所在的
   * entry 给出）； 有效劳动 = 本行人口 × **该池的人均劳动**（= 池内 Σ(count × 年龄档 × 性别的每人系数) ÷ 池人口，见 {@link
   * #laborMilli(List)}）、 自然需求 = **第 1 天**的口粮（{@link #firstDayRationMilli}；结算每天会覆写它，§八.8）、 货币
   * 0、无债务、无有效需求（§十 没有它们的依据 ⇒ 不臆造）。
   *
   * <p>★★ **H1：本行没有 {@code goods}** —— 开缸库存（口粮按阶层天数 + R3 的纤维/铁）由 {@link #openingStock} 算好后 落进 actor
   * 切片该家户的账本（{@link Seed#householdStocks()} ⇒ {@link HouseholdSeeder}）。
   *
   * <p>★★ **为什么"人均"而不是"逐行按自己的年龄构成"**：阶层比例把池子切成四份，而**没有任何数据**说清各阶层的年龄/性别构成 ⇒
   * 取池内人均（"阶层之间年龄性别同分布"这条**明说的**假设），不假装知道更多。默认系数下它与 R1 之前 `人口 × 580‰` **逐值相同**（池的年龄构成恰是 D4 preset
   * 时人均恒为 580），故真档的既有期望值不动。
   *
   * <p>★ **人口为 0 的行**（该池在这格没人）：人口/劳动/需求全 0 —— ★ 它的**开缸库存仍照算** （若那份存量由**土地**派生 ——
   * 如农村行的纤维，则它仍在：**地不因没人种而消失**），只是记在 actor 侧的账本上（H1）。
   */
  private static Map<String, Object> cohortRow(
      HouseholdId householdId,
      ResidenceKind residence,
      String slot,
      long population,
      int participationPerMille,
      long laborBudgetMilli) {
    Map<String, Object> row = new LinkedHashMap<>();
    // ★★ **居住类型是身份的一维**（H0.1/R-N1-A）：少了它，同一格的农村贫农与城镇贫农会并成同一本账
    //   ⇒ "农村的余粮 + 城市的缺口"并到一起 ⇒ 城市不再饿死，但那不是因为修好了通道。
    //   ★ 字面量取自 {@link ResidenceKind#value()}（前缀/字面的唯一拼写点在契约里，本类不写第二份）。
    // ★★ S1：家户稳定身份随行发出（载荷把它带进 EconomyData；运行期不再从视图派生 actor id）。
    row.put("householdId", householdId.value());
    row.put("residence", residence.value());
    row.put("slot", slot);
    row.put("population", population);
    row.put("laborMilli", laborBudgetMilli);
    row.put("participationPerMille", participationPerMille);
    // ★★ **H1：这里没有 {@code goods} 键**（裁定 D3-C/K1）—— 商品库存的唯一持久真源是 actor 切片里该家户的
    //   {@code HouseholdInventory}，开缸余额由 {@link #openingStock} 一次算好、经 {@link HouseholdSeeder}
    // 落成那本账。
    //   ★ 载荷里再写一份 = 同一事实的第二处拼写点，而且它会静默漂开（economy 侧已不再读它）。
    row.put("money", 0);
    // ★★ E4a：行里的债务引用不再由 seed 写（欠债只能由 runtime 借粮路径产生；空引用 = 缺键）。
    row.put("naturalNeeds", Map.of(COMMODITY_GRAIN, firstDayRationMilli(population)));
    row.put("effectiveDemand", Map.of());
    return row;
  }

  /** 同上的**家户账本形态**（键是 {@link CommodityId}；同一口径：0 ⇒ 不落键）。★ 与上面那个同名会撞擦除 ⇒ 另起名。 */
  private static void putStockIfPositive(
      Map<CommodityId, Long> stock, String commodity, long amount) {
    if (amount > 0L) {
      stock.put(new CommodityId(commodity), amount);
    }
  }
}
