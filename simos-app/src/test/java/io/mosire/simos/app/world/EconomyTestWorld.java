package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
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
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.map.hex.HexCoord;
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
import java.util.Set;

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

  /**
   * ★★ <b>T5：带 economy <b>与 actor</b> 两片的创世状态</b>（{@code (main,1)}，时刻 0）。
   *
   * <p>★★ <b>为什么 actor 片必须在场</b>：产出自本阶段起<b>不再写进阶层行</b> —— 它变成产权条目，而账本住在 actor 切片 ⇒ "产出落
   * operator"这件事只有同时看得见两片的协调器（{@link
   * io.mosire.simos.app.time.EconomyOwnershipTimeParticipant}）做得到。actor 片缺席 ⇒ 协调器当场抛（装配故障）。
   *
   * <p>★★ <b>H1：家户 actor 与它们的账本在创世就位</b>（{@link HouseholdSeeder#books}）—— 商品库存的唯一真源是 actor 侧的
   * {@code GoodsAccount}（{@code ClassRow} 里没有 {@code goods}），而日结算的消费/投入都要读那本账 （{@code
   * EconomyDayStepper} 的会话工作副本由它载入）⇒ 夹具必须与真播种器**同形**地起账。 ★ 账本的余额 = {@link #openingStock} 那一份（与旧版行里的
   * {@code goods} 逐值相同）。
   */
  public static SimulationState genesis() {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(0);
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    EconomyData data = data();
    modules.put("economy", new EconomySnapshot(ref, at, data));
    modules.put("actor", new ActorSnapshot(ref, at, books()));
    return new SimulationState(new StateMeta(ref, at), modules, InMemoryInfoSystem.empty());
  }

  /**
   * ★★ <b>创世的家户账本</b>（H1）：{@link HouseholdSeeder#books} 用**同一套身份**（{@code HouseholdActors}）建
   * "每格两组四行"的 actor + 账，余额 = 本夹具写死的开缸库存（见 {@link #openingStock}）。
   *
   * <p>★ 与真播种路径（{@code economy.Seed} + {@code actor.Seed} 同批）**逐字段同形**：id 由契约拼、账户键 = {@code (actor,
   * hex)}、空账也建（人口 0 的那一组）。
   */
  public static ActorData books() {
    ActorData books = HouseholdSeeder.books(openingStocks(), openingMoney());
    return books.withMeta(Optional.of(new ActorMeta(MAP_ID, 0L, HouseholdSeeder.RULES_VERSION)));
  }

  /**
   * 逐家户的开缸库存（**可变的新表**：会话工作副本由它拷出来，见 {@link #householdGoods()}）。
   *
   * <p>★ 它由 {@link #data(Map)} **同一条构造**顺手记下 ⇒ "行"与"账"逐格同源（不可能漂开）。
   */
  public static Map<CohortKey, Map<CommodityId, Long>> openingStocks() {
    Map<CohortKey, Map<CommodityId, Long>> stocks = new LinkedHashMap<>();
    data(stocks, new LinkedHashMap<>());
    return stocks;
  }

  /**
   * ★★ <b>H4：逐家户的创世货币禀赋</b>（毫银）—— 与 {@link #openingStocks()} 由**同一条构造**产出 （人口 → 口粮 →
   * 钱），口径照真播种器：{@link EconomySeeder#genesisMoney(long)}（唯一拼写点）。
   *
   * <p>★ 本夹具不另拍一个数：改口径时它与真档**一起**变（"手搭的世界"与"命令播出来的世界"在钱上也不许漂）。
   */
  public static Map<CohortKey, Map<CurrencyId, Long>> openingMoney() {
    Map<CohortKey, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    data(new LinkedHashMap<>(), money);
    return money;
  }

  /**
   * ★★ <b>会话工作副本的起点</b>（{@code EconomyDayStepper} 的入参）：开缸库存的一份**可变深拷贝**。
   *
   * <p>★ 每个用例各取一份：副本会被日结算**就地更新** ⇒ 共享同一份会让用例之间互相污染。
   */
  public static Map<CohortKey, Map<CommodityId, Long>> householdGoods() {
    Map<CohortKey, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, Map<CommodityId, Long>> entry : openingStocks().entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return copy;
  }

  /** 五格的经济状态（已激活；{@code lastClosedCycle} 空 = 还没关过账）。 */
  public static EconomyData data() {
    return data(new LinkedHashMap<>(), new LinkedHashMap<>());
  }

  /**
   * 同上，但把**逐家户的开缸库存**顺手记进 {@code stocks}（H1：行里不再有 {@code goods}，库存住在 actor 账本上）。
   *
   * <p>★ 只有一处构造：{@link #data()} 给一张丢弃的表、{@link #openingStocks()} 收下它 ⇒ 两条路不可能漂开。
   */
  private static EconomyData data(
      Map<CohortKey, Map<CommodityId, Long>> stocks, Map<CohortKey, Map<CurrencyId, Long>> money) {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    Map<CohortKey, ClassRow> classes = new LinkedHashMap<>();
    Map<PeopleLotId, LaborSupply> supply = new LinkedHashMap<>();
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    // (0,0) 平原 1000 人 / (1,0) 低丘 500 人 / (2,0) 只有城市 300 人。
    farm(
        industries,
        classes,
        supply,
        allocations,
        0,
        0,
        1000L,
        PLAINS_LAND_MILLI_MU,
        Stock.NORMAL,
        stocks);
    farm(
        industries,
        classes,
        supply,
        allocations,
        1,
        0,
        500L,
        HILLS_LAND_MILLI_MU,
        Stock.NORMAL,
        stocks);
    farm(
        industries,
        classes,
        supply,
        allocations,
        2,
        0,
        0L,
        PLAINS_LAND_MILLI_MU,
        Stock.NORMAL,
        stocks);
    craft(industries, classes, supply, allocations, 2, 0, 300L, stocks);
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
        Stock.LANDLORD_ZERO_RICH_SURPLUS,
        stocks);
    farm(
        industries,
        classes,
        supply,
        allocations,
        4,
        0,
        1000L,
        PLAINS_LAND_MILLI_MU,
        Stock.LANDLORD_ZERO_OTHERS_EXACT,
        stocks);
    // ★★ **R3：非土地生产**（有农村人口的格各一座家庭纺织）—— 它让"份外之地"这件事在夹具里就成立：
    //   织机不占地、纤维与织机都不来自土地那一路，规模由**最紧约束**（劳动 vs 织机 vs 纤维）决定。
    //   ★ 劳动那一条在 {@link #farm} 里与农业一起发（同一批农村人的 900‰/100‰）—— 一格的农村劳动只在一处决定。
    //   ★ (2,0) 农业人口为 0 ⇒ **没有纺织**（配额为 0 ⇒ 纺织行没有活干 ⇒ 连产业都不建）。
    weaving(industries, classes, 0, 0, 1000L, stocks);
    weaving(industries, classes, 1, 0, 500L, stocks);
    weaving(industries, classes, 3, 0, LENDER_HEX_POPULATION, stocks);
    weaving(industries, classes, 4, 0, 1000L, stocks);
    // ★★ H0.2：**每格两组四行**（与真播种器同形）—— 上面的构造只为"有城市人口的格"建了城镇四行，
    //   这里把缺的那一组补成**空账**（人口/劳动/需求全 0）。★ 用 {@code putIfAbsent} ⇒ 已建的那一组
    //   （(2,0) 的城镇四行）**一个数都不动**；★ 两条判据靠它才成立：① 行集逐格同形（H1 的家户 actor 按
    //   "格 × 居住 × 阶层"播）；② "同格两组账不并"那条断言（农村格的城镇四行必须真的在场，否则空集上恒真）。
    for (int q = 0; q <= 4; q++) {
      for (ResidenceKind residence : ResidenceKind.all()) {
        for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
          CohortKey key =
              new CohortKey(
                  new HexCoord(q, 0), residence, new SocialClassId(EconomySeeder.CLASS_IDS[i]));
          classes.putIfAbsent(key, emptyRow(key, i));
          // ★★ H1：**空账也要有账本**（与真播种器同口径：每格两组四行各一本，余额可以全 0）——
          //   少了它，日结算在 load 阶段就抛（"这个家户在这一格没有账"与"它的账是空的"是两件事）。
          stocks.putIfAbsent(key, new LinkedHashMap<>());
        }
      }
    }
    EconomyMeta meta =
        new EconomyMeta(
            MAP_ID, 0L, OptionalLong.empty(), EconomySeeder.RULES_VERSION, Optional.empty());
    // ★★ H4：**创世货币禀赋**（毫银）—— 与行由**同一次构造**产出（人口 → 钱），口径照真播种器
    //   （{@link EconomySeeder#genesisMoney(long)} 是唯一拼写点）；★ 空账（人口 0）⇒ 空钱包。
    for (Map.Entry<CohortKey, ClassRow> row : classes.entrySet()) {
      money.put(row.getKey(), EconomySeeder.genesisMoney(row.getValue().population()));
    }
    return new EconomyData(
        Optional.of(meta),
        industries,
        classes,
        Map.of(),
        Map.of(),
        supply,
        allocations,
        // ★★ T4/T5：第 8 个组件（生产关系表）**非空** —— harvest 已经真的读它了。
        //   本夹具按**每个产业自己的 regime** 推默认关系（{@link RegimeRelations#defaultRelation}），
        //   与真播种器载荷走的是**同一条推导**（{@code EconomyPayloads.relation}）⇒ 夹具与真档不漂。
        //   ★ 产出自此不再写进阶层行：行里的实物只经"cohort 入账"回来（R5 ③）。
        relations(industries),
        // ★★ H4：第 9 个组件（市场表）—— 本夹具**逐格给一个市场**（与真播种器的"每格一个"同口径），
        //   计价货币与价表取真装载器的出厂值 {@link EconomySeeder#MARKET_FACTORY}（唯一拼写点）。
        markets(classes));
  }

  /**
   * ★★ <b>H4：逐格市场表</b>（{@code EconomyData.markets} 的第 9 个组件）：本夹具的**每一格**一个市场， 值取真播种器的出厂市场（{@link
   * EconomySeeder#MARKET_FACTORY}）—— 夹具**不另拍价表**， 否则"夹具里的价"与"真档的价"会在两次改动之间静默漂开。
   */
  private static Map<HexCoord, Market> markets(Map<CohortKey, ClassRow> classes) {
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    for (CohortKey key : classes.keySet()) {
      markets.putIfAbsent(key.hex(), EconomySeeder.MARKET_FACTORY);
    }
    return markets;
  }

  /**
   * 逐产业按 regime 推默认关系（{@link RegimeRelations} 是唯一拼写点）。
   *
   * <p>★ H0.2：默认关系里的 cohort 受方**必须带居住维** ⇒ 多传一份"这个产业的家户住哪种居住类型"。★ 它的事实来源是 **劳动配额表的批次前缀**（{@link
   * ResidenceKind#ofLot}，唯一拼写点）—— 真档由 {@code EconomyPayloads} 在载荷边缘算， 本夹具按同一个判据从**合成的批次 id**
   * 取（{@link #syntheticLot}）。
   */
  private static Map<IndustryId, ProductionRelation> relations(
      Map<IndustryId, Industry> industries) {
    Map<IndustryId, ProductionRelation> relations = new LinkedHashMap<>();
    for (Industry industry : industries.values()) {
      relations.put(
          industry.id(),
          RegimeRelations.defaultRelation(
              industry.regime(),
              industry.id(),
              industry.operator(),
              Set.of(ResidenceKind.ofLot(syntheticLot(industry.id())))));
    }
    return relations;
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
      Map<CohortKey, ClassRow> classes,
      Map<PeopleLotId, LaborSupply> supply,
      Map<LaborAllocationId, LaborAllocation> allocations,
      int q,
      int r,
      long population,
      long landMilliMu,
      Stock stock,
      Map<CohortKey, Map<CommodityId, Long>> stocks) {
    IndustryId id = IndustryHexKeys.id(EconomySeeder.FARM, q, r);
    long[] people = EconomySeeder.splitByShares(population, EconomySeeder.CLASS_SHARE_PER_MILLE);
    industries.put(
        id,
        industry(
            id,
            "农业",
            EconomySeeder.REGIME_FEUDAL,
            new AllocationRule.Split(700, 300),
            // ★ R3（V7）：规模单位 = 亩；每亩 143 千分劳动；**田里同时出粮与纤维**（纤维是副产物 ⇒ 多商品产出的判据所在）。
            Map.of(AssetKind.LAND, 1_000L),
            // ★★ H0.3（K3）：**产能 = 本格可耕地**（千分亩）—— 旧版按人口切在四行里、靠 Σ 还原总量；
            //   现在总量只有一处真相（{@code Industry.capacity}），行的 {@code meansOfProduction} 整个消失。
            Map.of(AssetKind.LAND, landMilliMu),
            EconomySettlement.LABOR_MILLI_PER_MU,
            Map.of(
                GRAIN, EconomySeeder.GRAIN_OUTPUT_PER_MU, FIBER, EconomySeeder.FIBER_OUTPUT_PER_MU),
            // ★★ 必须保持空：5 格端到端夹具是"未配投入 ⇒ 投入那一路不施加约束"的对照
            Map.of()));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      addRow(classes, ruralKey(q, r, i), i, people[i], stock, Map.of(), stocks);
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

  /** 一本**空账**（该格那一组家户在本夹具里没有人）：人口/劳动全 0、库存空、需求 = 那条 0 的粮 —— ★ 与真播种器对"人口为 0 的那一组"写下的形状逐字同形（H0.2）。 */
  private static ClassRow emptyRow(CohortKey key, int index) {
    return new ClassRow(
        key,
        0L,
        0L,
        EconomySeeder.CLASS_LABOR_PER_MILLE[index],
        0L,
        List.of(),
        Map.of(GRAIN, EconomyVocabulary.dailyRationMilli(0L, 1L)),
        Map.of());
  }

  /** 农村家户行的键（{@code (格, RURAL, 阶层)}；H0.2 的键形状）。 */
  private static CohortKey ruralKey(int q, int r, int index) {
    return new CohortKey(
        new HexCoord(q, r), ResidenceKind.RURAL, new SocialClassId(EconomySeeder.CLASS_IDS[index]));
  }

  /** 城镇家户行的键（{@code (格, URBAN, 阶层)}）。 */
  private static CohortKey urbanKey(int q, int r, int index) {
    return new CohortKey(
        new HexCoord(q, r), ResidenceKind.URBAN, new SocialClassId(EconomySeeder.CLASS_IDS[index]));
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
      Map<CohortKey, ClassRow> classes,
      Map<PeopleLotId, LaborSupply> supply,
      Map<LaborAllocationId, LaborAllocation> allocations,
      int q,
      int r,
      long population,
      Map<CohortKey, Map<CommodityId, Long>> stocks) {
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
            // ★★ H0.3（K3）：产能 = 本格**作坊总座数**（旧版四行各一份、Σ 才是总数）。
            Map.of(AssetKind.WORKSHOP, workshops),
            EconomySeeder.LABOR_MILLI_PER_WORKSHOP,
            Map.of(CLOTH, clothPerWorkshop(), TOOL, EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE),
            Map.of(AssetKind.WORKSHOP, Map.of(FIBER, fiberPerShop, IRON, ironPerShop))));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      // ★★ H0.2：旧的 craft 四行 = 新的**城镇四行**（人口逐值不动；作坊搬到产业产能、纤维与铁留在家户账上）。
      addRow(
          classes,
          urbanKey(q, r, i),
          i,
          people[i],
          Stock.NORMAL,
          // ★ 原料库存 = 该家户分到的作坊数 × 一座作坊**一个周期**的用量（自洽，不是一个拍出来的总量）。
          Map.of(FIBER, shopByClass[i] * fiberPerShop, IRON, shopByClass[i] * ironPerShop),
          stocks);
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
      Map<CohortKey, ClassRow> classes,
      int q,
      int r,
      long ruralPopulation,
      Map<CohortKey, Map<CommodityId, Long>> stocks) {
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
            // ★★ H0.3（K3）：产能 = 本格**织机总数**（旧版四行各一份、Σ 才是总数）。
            Map.of(AssetKind.TOOL, looms),
            EconomySeeder.LABOR_MILLI_PER_LOOM,
            Map.of(CLOTH, clothPerLoom()),
            Map.of(AssetKind.TOOL, Map.of(FIBER, fiberPerLoom))));
    // ★★ H0.2：**纤维并入农村四行**（旧版落在"家庭纺织"那四行上）—— 织布是农村池的活，账只能记在自家户名下。
    //   ★ 逐值同式：每户分到的织机数 × 一台织机一个周期的用量（夹具的"估计来源"，与真档的"田里一个周期的副产"同性质）。
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      mergeStock(stocks, ruralKey(q, r, i), FIBER, loomByClass[i] * fiberPerLoom);
    }
  }

  /** 把一份商品并进已有家户的**账本**（H0.2 的"纤维并入农村行"用它；键不存在 ⇒ 抛，不静默新建一本账）。 */
  private static void mergeStock(
      Map<CohortKey, Map<CommodityId, Long>> stocks,
      CohortKey key,
      CommodityId commodity,
      long amount) {
    if (amount <= 0L) {
      return;
    }
    Map<CommodityId, Long> stock = stocks.get(key);
    if (stock == null) {
      throw new IllegalStateException("家户账本不存在，无法并入库存: " + key);
    }
    stock.merge(commodity, amount, Long::sum);
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
   * 一个**家户行**（H0.2：键 = {@code (格, 居住类型, 阶层)}；**没有 {@code meansOfProduction}** —— 产能已在 {@code
   * Industry.capacity} 上）。
   *
   * <p>★ 人口/劳动/需求全按真档口径折算：需求 = **第 1 天**的口粮（逐日差分，不是"每人每日的量 × 人口"）， 初始粮 = {@link
   * EconomySeeder#rationMilli}；{@code extraGoods} = 该家户的其它商品（纤维 / 铁）。
   */
  private static void addRow(
      Map<CohortKey, ClassRow> classes,
      CohortKey key,
      int index,
      long population,
      Stock stock,
      Map<CommodityId, Long> extraGoods,
      Map<CohortKey, Map<CommodityId, Long>> stocks) {
    String slot = EconomySeeder.CLASS_IDS[index];
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
    // ★★ H1：开缸库存进的是**家户账本**（{@code GoodsAccount}），不是阶层行 —— 行里已经没有 {@code goods}。
    Map<CommodityId, Long> openingStock = new LinkedHashMap<>();
    if (goods > 0L) {
      openingStock.put(GRAIN, goods);
    }
    for (Map.Entry<CommodityId, Long> entry : extraGoods.entrySet()) {
      if (entry.getValue() > 0L) {
        openingStock.put(entry.getKey(), entry.getValue());
      }
    }
    stocks.put(key, openingStock);
    classes.put(
        key,
        new ClassRow(
            key,
            population,
            EconomySeeder.laborMilli(population),
            EconomySeeder.CLASS_LABOR_PER_MILLE[index],
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
      Map<AssetKind, Long> capacity,
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
        capacity,
        Map.of(),
        0L,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        rule,
        0L,
        Map.of(),
        // ★ operator = **派生**，按**它自己的 regime 参数**（8 个调用点传的是三个已登记的 `EconomySeeder.REGIME_*`
        //   ⇒ **不改本方法的 8 参签名**、8 个调用点零改动；默认值只有一处拼写点）。
        RegimeOperators.defaultOperator(new RegimeId(regime), id));
  }
}
