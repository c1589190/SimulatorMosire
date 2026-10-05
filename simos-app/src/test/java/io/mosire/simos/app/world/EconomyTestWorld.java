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
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
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
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
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
    return genesis(true);
  }

  /**
   * ★★ <b>无市场变体</b>（M2 收尾新增）：与 {@link #genesis()} 逐字段同形，只把 {@code markets} 置空。
   *
   * <p>★★ <b>为什么需要它</b>：{@link EconomySettlementEndToEndTest} 的闭式期望（第 119 天缸里的余量、缺口、收获后库存、
   * 守恒式）全是按"窗口内没有市场成交"手推的；M2 起每 5 天开市、家户只留 35 天生活库存、经营者也入市 ⇒ 同一批粮会被市场 重新分配，那些闭式期望的前提**整类失效**（实测差
   * 183,333 / 604,381）。该用例组测的是**日结算 + 周期收获 + 借粮**， 市场不是它的被测物 ——
   * 与其把几十条闭式期望改成"抄实际值"，不如给它一个无市场创世（**不放宽任何断言**）。 市场侧的守恒由 {@link EconomyConservationNetTest}
   * 与真档用例承担（它们用 {@link #genesis()} 的带市场世界）。
   */
  public static SimulationState genesisWithoutMarkets() {
    return genesis(false);
  }

  private static SimulationState genesis(boolean withMarkets) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(0);
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    EconomyData data = data(new LinkedHashMap<>(), new LinkedHashMap<>(), withMarkets);
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
    // ★★ H5（⑤）：**经营主体的开缸账也要播**（真创世那条路：`EconomySeeder.plan` 的 `Seed.operators()` →
    //   `HouseholdSeeder.books/​payload`）—— 少了它，作坊的投入（工具）没有来处、净产也落不进它的账：
    //   实测（本夹具）：作坊第 1 个周期就开不起来（工具可用 0 ⇒ usableScale 0）、工具产量 0。
    Map<HouseholdId, HexCoord> locations = new LinkedHashMap<>();
    for (ClassRow row : data().classes().values()) {
      locations.put(row.id(), row.view().hex());
    }
    ActorData books =
        HouseholdSeeder.books(locations, openingStocks(), openingMoney(), operators());
    return books.withMeta(Optional.of(new ActorMeta(MAP_ID, 0L, HouseholdSeeder.RULES_VERSION)));
  }

  /**
   * ★★ <b>本夹具的经营主体开缸账</b>（H5 ⑤）：**从它自己的经济状态推**，与真播种器**同一条规则** （{@link EconomySeeder#operatorSeed}
   * —— 主体由 regime 推、钱包 = 工资周转金、账户键 = (主体, 产业那一格)）。
   *
   * <p>★ <b>开缸商品</b>：{@code handicraft} 的作坊拿到<b>一个周期的工具用量 / 座</b>（它自己的周转料 ——
   * 工具是它自己的产品，故这份料必须记在**它自己**的账上，理由见 {@code EconomySeeder.TOOL_MILLI_PER_WORKSHOP_CYCLE}）；
   * 其余产业开缸为空（庄园的种子在出料主体的账上、织机的纤维在农村家户的账上，见 {@code EconomySeeder.plan} 的同款注释）。
   */
  public static List<EconomySeeder.OperatorSeed> operators() {
    // ★ P2-A §13.3：庄园/作坊/商号**不再持账** ⇒ 生产播种不再产出经营者账户（P2-C 起农场/作坊的
    //   收支走组织者/经营者家户账户；本夹具与生产口径一致，返回空表）。
    return List.of();
  }

  /**
   * 逐家户的开缸库存（**可变的新表**：会话工作副本由它拷出来，见 {@link #householdGoods()}）。
   *
   * <p>★ 它由 {@link #data(Map)} **同一条构造**顺手记下 ⇒ "行"与"账"逐格同源（不可能漂开）。
   */
  public static Map<HouseholdId, Map<CommodityId, Long>> openingStocks() {
    Map<HouseholdId, Map<CommodityId, Long>> stocks = new LinkedHashMap<>();
    data(stocks, new LinkedHashMap<>());
    return stocks;
  }

  /**
   * ★★ <b>H4：逐家户的创世货币禀赋</b>（毫银）—— 与 {@link #openingStocks()} 由**同一条构造**产出 （人口 → 口粮 →
   * 钱），口径照真播种器：{@link EconomySeeder#genesisMoney(long)}（唯一拼写点）。
   *
   * <p>★ 本夹具不另拍一个数：改口径时它与真档**一起**变（"手搭的世界"与"命令播出来的世界"在钱上也不许漂）。
   */
  public static Map<HouseholdId, Map<CurrencyId, Long>> openingMoney() {
    Map<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    data(new LinkedHashMap<>(), money);
    return money;
  }

  /**
   * ★★ <b>会话工作副本的起点</b>（{@code EconomyDayStepper} 的入参）：开缸库存的一份**可变深拷贝**。
   *
   * <p>★ 每个用例各取一份：副本会被日结算**就地更新** ⇒ 共享同一份会让用例之间互相污染。
   */
  public static Map<HouseholdId, Map<CommodityId, Long>> householdGoods() {
    Map<HouseholdId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : openingStocks().entrySet()) {
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
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money) {
    return data(stocks, money, true);
  }

  private static EconomyData data(
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      boolean withMarkets) {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    Map<HouseholdId, ClassRow> classes = new LinkedHashMap<>();
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    // (0,0) 平原 1000 人 / (1,0) 低丘 500 人 / (2,0) 只有城市 300 人。
    farm(
        industries,
        classes,
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
        allocations,
        2,
        0,
        0L,
        PLAINS_LAND_MILLI_MU,
        Stock.NORMAL,
        stocks);
    craft(industries, classes, allocations, 2, 0, 300L, stocks);
    // (3,0) 有粮可借 / (4,0) 无粮可借。
    farm(
        industries,
        classes,
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
          classes.putIfAbsent(HouseholdIds.ofLegacy(key), emptyRow(key, i));
          // ★★ H1：**空账也要有账本**（与真播种器同口径：每格两组四行各一本，余额可以全 0）——
          //   少了它，日结算在 load 阶段就抛（"这个家户在这一格没有账"与"它的账是空的"是两件事）。
          stocks.putIfAbsent(HouseholdIds.ofLegacy(key), new LinkedHashMap<>());
        }
      }
    }
    EconomyMeta meta =
        new EconomyMeta(
            MAP_ID, 0L, OptionalLong.empty(), EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2, Optional.empty());
    // ★★ H4：**创世货币禀赋**（毫银）—— 与行由**同一次构造**产出（人口 → 钱），口径照真播种器
    //   （{@link EconomySeeder#genesisMoney(long)} 是唯一拼写点）；★ 空账（人口 0）⇒ 空钱包。
    for (Map.Entry<HouseholdId, ClassRow> row : classes.entrySet()) {
      money.put(row.getKey(), EconomySeeder.genesisMoney(row.getValue().population()));
    }
    // ★ E1–E6 + S1：用 withX 搭当前 29 组件形状；临时挂 pre-modern-v1 抑制
    //   "classes 非空但 memberships 尚未挂上"的中间态自动迁移；allocations 的 pending household
    //   由 LegacyHouseholdMigration 按人口权重拆成真实家户（这正是旧聚合夹具的迁移路径）。
    EconomyMeta legacyMeta =
        new EconomyMeta(
            MAP_ID,
            0L,
            OptionalLong.empty(),
            EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
            Optional.empty());
    EconomyData current =
        EconomyData.empty()
            .withMeta(Optional.of(legacyMeta))
            .withIndustries(industries)
            .withClasses(classes)
            // ★★ T4/T5：第 8 个组件（生产关系表）**非空** —— harvest 已经真的读它了。
            //   本夹具按**每个产业自己的 regime** 推默认关系（{@link RegimeRelations#defaultRelation}），
            //   与真播种器载荷走的是**同一条推导**（{@code EconomyPayloads.relation}）⇒ 夹具与真档不漂。
            .withRelations(relations(industries));
    // pending 配额在这一步触发迁移：拆成真实家户 + activity/actor 对齐到 unit + 补 memberships/assetShares。
    current = current.withAllocations(allocations);
    current = current.withMarkets(withMarkets ? markets(classes) : Map.of());
    return current.withMeta(Optional.of(meta));
  }

  /**
   * ★★ <b>H4：逐格市场表</b>（{@code EconomyData.markets} 的第 9 个组件）：本夹具的**每一格**一个市场， 值取真播种器的出厂市场（{@link
   * EconomySeeder#MARKET_FACTORY}）—— 夹具**不另拍价表**， 否则"夹具里的价"与"真档的价"会在两次改动之间静默漂开。
   */
  private static Map<HexCoord, Market> markets(Map<HouseholdId, ClassRow> classes) {
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    for (ClassRow row : classes.values()) {
      markets.putIfAbsent(row.view().hex(), EconomySeeder.MARKET_FACTORY);
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
  private static Map<ProductionUnitId, ProductionRelation> relations(
      Map<IndustryId, Industry> industries) {
    Map<ProductionUnitId, ProductionRelation> relations = new LinkedHashMap<>();
    for (Industry industry : industries.values()) {
      ProductionUnitId unit = ProductionUnitId.idOf(industry.id(), industry.operator());
      relations.put(
          unit,
          RegimeRelations.defaultRelation(
              industry.regime(),
              unit,
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
      Map<HouseholdId, ClassRow> classes,
      Map<LaborAllocationId, LaborAllocation> allocations,
      int q,
      int r,
      long population,
      long landMilliMu,
      Stock stock,
      Map<HouseholdId, Map<CommodityId, Long>> stocks) {
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
    long ruralDaily =
        EconomySeeder.industryDailyLabor(people, EconomySeeder.laborMilli(population), population);
    long weaveQuota = ruralDaily * EconomySeeder.WEAVE_SHARE_PER_MILLE / 1000L;
    addAllocation(
        allocations,
        id,
        ActorKind.ORGANIZATION,
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
        HouseholdIds.ofLegacy(key),
        key,
        0L,
        0L,
        EconomySeeder.CLASS_LABOR_PER_MILLE[index],
        0L,
        List.of(),
        Map.of(GRAIN, EconomyVocabulary.dailyRationMilli(0L, 1L)),
        Map.of(),
        0L);
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
      Map<HouseholdId, ClassRow> classes,
      Map<LaborAllocationId, LaborAllocation> allocations,
      int q,
      int r,
      long population,
      Map<HouseholdId, Map<CommodityId, Long>> stocks) {
    IndustryId id = IndustryHexKeys.id(EconomySeeder.CRAFT, q, r);
    long[] people = EconomySeeder.splitByShares(population, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long workshops = population / EconomySeeder.URBAN_CAPITA_PER_WORKSHOP;
    long[] shopByClass =
        EconomySeeder.splitByShares(workshops, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long fiberPerShop = clothPerWorkshop() * EconomySeeder.FIBER_MILLI_PER_CLOTH;
    long ironPerShop =
        EconomySeeder.TOOL_PER_WORKSHOP_PER_CYCLE * EconomySeeder.IRON_MILLI_PER_TOOL;
    // ★★ H5 ④：作坊的投入是 **TOOL**（与真播种器同一条口径 —— 工具自产自用 ⇒ 净产为正 ⇒ 存量可再生）。
    long toolPerShop = EconomySeeder.toolPerWorkshopMilli();
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
            Map.of(AssetKind.WORKSHOP, Map.of(FIBER, fiberPerShop, TOOL, toolPerShop))));
    for (int i = 0; i < EconomySeeder.CLASS_IDS.length; i++) {
      // ★★ H0.2：旧的 craft 四行 = 新的**城镇四行**（人口逐值不动；作坊搬到产业产能、纤维与铁留在家户账上）。
      addRow(
          classes,
          urbanKey(q, r, i),
          i,
          people[i],
          Stock.NORMAL,
          // ★ 原料库存 = 该家户分到的作坊数 × 一座作坊**一个周期**的用量（自洽，不是一个拍出来的总量）。
          // ★★ H5 ④：**工具不再记在家户账上** —— 它是作坊自己的产品，故那份周转料由**经营者**的账持有
          //   （见 {@link #operators()}）；家户这边照旧只有纤维与铁（铁是词表里的留位商品，本仓没有配方读它）。
          Map.of(FIBER, shopByClass[i] * fiberPerShop, IRON, shopByClass[i] * ironPerShop),
          stocks);
    }
    addAllocation(
        allocations,
        id,
        ActorKind.ORGANIZATION,
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
      Map<HouseholdId, ClassRow> classes,
      int q,
      int r,
      long ruralPopulation,
      Map<HouseholdId, Map<CommodityId, Long>> stocks) {
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
      Map<HouseholdId, Map<CommodityId, Long>> stocks,
      CohortKey key,
      CommodityId commodity,
      long amount) {
    if (amount <= 0L) {
      return;
    }
    Map<CommodityId, Long> stock = stocks.get(HouseholdIds.ofLegacy(key));
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
    // ★ S1 旧档迁移路径：household 先用 pending 占位（构造器允许），构造期迁移按人口权重替换成真实家户；
    //   actor/activity 保持旧产业口径，迁移器会一起对齐到 unit.operator / unit id。
    HouseholdId pendingHousehold =
        HouseholdId.parse(HouseholdIds.PENDING_LEGACY_PREFIX + industry.value());
    return new LaborAllocation(
        allocationId(industry),
        lot,
        pendingHousehold,
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
      Map<HouseholdId, ClassRow> classes,
      CohortKey key,
      int index,
      long population,
      Stock stock,
      Map<CommodityId, Long> extraGoods,
      Map<HouseholdId, Map<CommodityId, Long>> stocks) {
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
    stocks.put(HouseholdIds.ofLegacy(key), openingStock);
    classes.put(
        HouseholdIds.ofLegacy(key),
        new ClassRow(
            HouseholdIds.ofLegacy(key),
            key,
            population,
            EconomySeeder.laborMilli(population),
            EconomySeeder.CLASS_LABOR_PER_MILLE[index],
            0L,
            List.of(),
            Map.of(GRAIN, firstDayNeed),
            Map.of(),
            0L));
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
    // ★ 旧 17 参形状：operator/capacity 是兼容位，构造期归一化据此合成 unit + AssetShare
    //   （夹具因此与真档同一套换算；新的 12 参模板会把这两个事实留在外面）。
    return new Industry(
        id,
        name,
        new RegimeId(regime),
        CYCLE_DAYS,
        capacityPerUnit,
        Map.of(),
        0L,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        rule,
        RegimeOperators.defaultOperator(new RegimeId(regime), id),
        0L,
        capacity,
        0L,
        Map.of());
  }
}
