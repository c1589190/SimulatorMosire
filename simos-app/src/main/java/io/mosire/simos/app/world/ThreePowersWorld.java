package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.time.MarketZoneReadout;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>{@code three-powers}：三个市场区 / 三个政府 / 三种货币的测试世界（B1，2026-10-08 阶段 2-B）</b>。
 *
 * <p>★★ <b>它解决哪件事</b>（用户 2026-10-08 第 19 轮原话：「测试世界扩大，B 阶段做三个市场区、三个政府，其他按你说的做， 先 A 后 B」）：阶段 2 的验收判据
 * G1 要求"世界起来即有 3 个市场区 / 3 个 GOV / 3 种货币，每区法定币不同，行政区两两不相交"—— 这条判据在既有世界里<b>造不出来</b>（{@code
 * small-world} 是 1 个市场区 + 2 个 GOV + 2 种货币，且两币不同区这件事需要 每区法定币）。本类就是那个世界：{@code --world=three-powers}
 * 即可在空库首启时种出它（登记见 {@link WorldRegistry#THREE_POWERS}）。
 *
 * <p>★★ <b>世界形状（一切数字都是本类的具名常量）</b>：
 *
 * <ul>
 *   <li><b>37 hex</b>（半径 3 的完整六边形：1 + 6 + 12 + 18），六邻域连通；
 *   <li><b>3 个行政区（{@link Region}，两两不相交、并集 = 全部 37 格）</b>：一个区 = 一个市场区 = 一个 GOV 的辖区 =
 *       一种法定币（"一省一币一城一政府"的世界形态）；
 *   <li><b>3 座城（每区一座，恰在区的锚格上）</b>：{@value #SILVER_CITY_ID} / {@value #COPPER_CITY_ID} / {@value
 *       #GOLD_CITY_ID}，tier 都是 {@code City}（半径 8 ⇒ 覆盖本区全部格，"几个区"因此恰为 3）；
 *   <li><b>3 种货币</b>：{@code silver}（既有词表）/ {@code copper}（{@link MoneyVocabulary#COPPER}）/ {@code
 *       gold} （本类定义）；后两者由各自的 GOV 在创世期 {@code economy.DefineCurrency} 定义并成为发行人 —— 银的发行人仍是创世审计政府
 *       {@code world-silver}（"发行政府 ↔ GOV 连线"是 B2 的面，见账本的偏离记录）；
 *   <li><b>3 个 GOV</b>：{@value #SILVER_GOV_ID}（中央，辖区 = 银区）+ 两个省 GOV（各自辖区 = 各自的区，上级 = 中央）， 每个 GOV
 *       都有官署产业 / 政府家户 + 官吏户（2 名成年男性）/ 岗位 / 承诺 / 编制计划 / 预算政策 / 国库禀赋；
 *   <li><b>人口</b>：37 格 × {@value #RURAL_POPULATION_PER_HEX} 农村 = 5,550，加三城城镇 300 + 250 + 250 = 800
 *       ⇒ 6,350。
 * </ul>
 *
 * <p>★★ <b>"3 个市场区"是怎么落成的</b>（本批的核心技术风险，逐条见 {@code MarketTopologyBook#from} 的类注）： 每格市场的计价币 =
 * 该格所属区的法定币（{@code EconomySeeder.plan(..., numeraireOf)} 的逐格函数）， 三个区的币两两不同 ⇒ {@code sameNumeraire}
 * 为假 ⇒ 市场拓扑走"城市节点 + tier 半径"路径；每座城的锚格都有市场、半径 8 覆盖本区全部格 ⇒ <b>恰 3 个节点、0 个兜底单格区 = 恰 3 个市场区</b>。★
 * 三个区的划分函数（{@link #zoneOf}） 与市场拓扑的"半径内最近节点、同距按 nodeId 字典序"是<b>同一条规则</b>（城市 id 序 = 区序）⇒
 * 行政区与市场区**逐格重合**， 这让"每区法定币"这句话在任何一侧读都自洽。
 *
 * <p>★★ <b>辖区互斥（I23）的三重保证</b>（用户第 8 轮裁定「重叠辖区是不被允许的」）：① 行政区由 {@link #zoneOf} 的**划分** 构造（并集 =
 * 全域、两两不相交，构造性成立）；② 创世链入口 {@code ThreePowersGovBootstrap.apply} 逐 hex 自检并具名抛； ③ 运行期由组合根的 {@code
 * GovJurisdictionGuard}（{@code MutationGuard}）拦截"两个 GOV 授同一 hex"的写命令。
 *
 * <p>★ <b>确定性、无随机、无时钟</b>（{@link WorldRegistry.GenesisProvider} 的生成器契约）：无 {@code UUID}、无 {@code
 * Random}、无墙钟读取；格序 = {@code (q,r)} 升序、区按声明的三区序、城市按 id 升序、官吏来源按批次序 ⇒ 同一 {@code mapId}
 * 每次调用产出逐字段相等的状态。
 *
 * <p>★ <b>日志</b>（AGENTS §一.9）：创世末尾按四件事各发 INFO —— 几个市场区 / 几个 GOV / 几种货币 / 每区法定币（逐区一条）， 另加一条 I23 逐
 * hex 断言结果。事件名见 {@link #EVENT_ZONES} / {@link #EVENT_GOVS} / {@link #EVENT_CURRENCIES} / {@link
 * #EVENT_ZONE_NUMERAIRE} / {@link #EVENT_EXCLUSIVITY}。
 *
 * <p>★ <b>不做</b>（留给 B2 及之后，见账本）：市场区**持久状态组件**（{@code EconomyData} 新组件 + Codec + 往返不变式）、
 * "退让/覆盖/合并"命令面、发行政府 ↔ GOV 连线的完整查询面、GOV 总疆域 → hex 的公开函数、跨区汇率套利、口岸/关税、
 * 商品采购窗口、铸币。本世界的市场区仍是**派生件**（I22 的持久化是 B2）。
 */
public final class ThreePowersWorld {

  /**
   * 本世界的 worldId / 缺省 mapId（登记在 {@link WorldRegistry#THREE_POWERS}；{@code --world=three-powers}）。
   */
  public static final String MAP_ID = "three-powers";

  /** 世界半径（hex）：半径 3 的完整六边形 = 37 格。 */
  public static final int RADIUS_HEX = 3;

  /** 格数 = 1 + 6 + 12 + 18 = 37（半径 3 完整六边形）。 */
  public static final int HEX_COUNT = 37;

  // ── 三个区 / 三座城 / 三个 GOV / 三种货币 ────────────────────────────────────────────────

  /** 银区（中央直辖区）的行政区 id。 */
  public static final String SILVER_REGION_ID = "province-silver";

  /** 铜区的行政区 id。 */
  public static final String COPPER_REGION_ID = "province-copper";

  /** 金区的行政区 id。 */
  public static final String GOLD_REGION_ID = "province-gold";

  /** 银区行政区显示名。 */
  public static final String SILVER_REGION_NAME = "银省";

  /** 铜区行政区显示名。 */
  public static final String COPPER_REGION_NAME = "铜省";

  /** 金区行政区显示名。 */
  public static final String GOLD_REGION_NAME = "金省";

  /** 银区城 id（也是市场拓扑的节点 id：区 id 与城 id 一一对应）。 */
  public static final String SILVER_CITY_ID = "c-tp-silver";

  /** 铜区城 id。 */
  public static final String COPPER_CITY_ID = "c-tp-copper";

  /** 金区城 id。 */
  public static final String GOLD_CITY_ID = "c-tp-gold";

  /** 银区城（中央座位）的落点。 */
  public static final HexCoord SILVER_AT = new HexCoord(3, 0);

  /** 铜区城的落点。 */
  public static final HexCoord COPPER_AT = new HexCoord(0, -3);

  /** 金区城的落点。 */
  public static final HexCoord GOLD_AT = new HexCoord(-3, 3);

  /** 银区 GOV（中央）的稳定 id。 */
  public static final String SILVER_GOV_ID = "gov-tp-silver";

  /** 铜区 GOV（省）的稳定 id。 */
  public static final String COPPER_GOV_ID = "gov-tp-copper";

  /** 金区 GOV（省）的稳定 id。 */
  public static final String GOLD_GOV_ID = "gov-tp-gold";

  /**
   * ★ 金的币种 id（全仓 {@code src/main} 的唯一一处 {@code "gold"} 字面量）。
   *
   * <p>★ <b>为什么它不写在 {@code MoneyVocabulary}</b>：货币词表住 {@code simos-economy-api}（A1 的契约层）， 而 B1
   * 的文件所有权不含那个模块；且"哪个世界有哪种钱"本来由**那个世界的创世**决定（词表只给 {@code silver}/{@code copper} 两个拼写点）。⇒
   * 金是**本世界**的第三种币，拼写点就在本类。
   */
  public static final String GOLD_CURRENCY_ID = "gold";

  /** 金的最小单位精度（3 ⇒ 毫金；与银/铜同制，本批不做任何折算）。 */
  public static final int GOLD_SCALE = 3;

  /** 金的显示名。 */
  public static final String GOLD_DISPLAY_NAME = "金";

  /** 金的币种定义（本世界的第三种币）。 */
  public static final CurrencyDef GOLD =
      new CurrencyDef(GOLD_CURRENCY_ID, GOLD_SCALE, GOLD_DISPLAY_NAME);

  /** 铜的币种定义（取词表的拼写点：{@code copper} 不是本世界发明的币种）。 */
  public static final CurrencyDef COPPER = MoneyVocabulary.COPPER;

  /** 三区的**声明序**（也是 CITY_ID / 行政区 / GOV spec 的序：银 → 铜 → 金）。 */
  public static final List<String> ZONE_IDS = List.of(SILVER_CITY_ID, COPPER_CITY_ID, GOLD_CITY_ID);

  /** 三个行政区的 id（声明序）。 */
  public static final List<RegionId> ZONE_REGIONS =
      List.of(
          new RegionId(SILVER_REGION_ID),
          new RegionId(COPPER_REGION_ID),
          new RegionId(GOLD_REGION_ID));

  // ── 人口与地形 ─────────────────────────────────────────────────────────────────────────

  /** 每格农村人口（37 × 150 = 5,550）。 */
  public static final long RURAL_POPULATION_PER_HEX = 150L;

  /** 银区城（中央座位所在城）城镇人口。 */
  public static final long SILVER_URBAN_POPULATION = 300L;

  /** 铜区城城镇人口。 */
  public static final long COPPER_URBAN_POPULATION = 250L;

  /** 金区城城镇人口。 */
  public static final long GOLD_URBAN_POPULATION = 250L;

  /** 城镇人口合计 = 300 + 250 + 250 = 800。 */
  public static final long URBAN_POPULATION =
      SILVER_URBAN_POPULATION + COPPER_URBAN_POPULATION + GOLD_URBAN_POPULATION;

  /** 总人口 = 5,550 农村 + 800 城镇 = 6,350。 */
  public static final long TOTAL_POPULATION =
      RURAL_POPULATION_PER_HEX * HEX_COUNT + URBAN_POPULATION;

  /** 国库的本区法定币禀赋（毫 / GOV；与 {@code GovWorldBootstrap} 的第二币种量同阶）。 */
  public static final long TREASURY_ZONE_CURRENCY_MILLI = 100_000L;

  /** 国库的银工作余额（毫银 / GOV；现行行政结算是银本位的，见 {@code ThreePowersGovBootstrap} 的注）。 */
  public static final long TREASURY_SILVER_MILLI = 100_000L;

  /** 创世时刻：tick 0（人口批次锚点、结算起点都用它）。 */
  public static final SimosTimestamp AT = SimosTimestamp.of(0L);

  // ── 日志事件名（§一.9；探针按名字取证据）────────────────────────────────────────────────

  /** 创世 INFO：世界总览（一个区一个 GOV 一种币的一句话摘要）。 */
  public static final String EVENT_GENESIS = "THREE_POWERS_GENESIS";

  /** 创世 INFO：**几个市场区**（G1 的第一条读数）。 */
  public static final String EVENT_ZONES = "THREE_POWERS_GENESIS_ZONES";

  /** 创世 INFO：**几个 GOV**。 */
  public static final String EVENT_GOVS = "THREE_POWERS_GENESIS_GOVS";

  /** 创世 INFO：**几种货币**。 */
  public static final String EVENT_CURRENCIES = "THREE_POWERS_GENESIS_CURRENCIES";

  /** 创世 INFO：**每区法定币**（逐区一条）。 */
  public static final String EVENT_ZONE_NUMERAIRE = "THREE_POWERS_GENESIS_ZONE_NUMERAIRE";

  /** 创世 INFO：行政区互斥（I23）逐 hex 断言结果。 */
  public static final String EVENT_EXCLUSIVITY = "THREE_POWERS_GENESIS_EXCLUSIVITY_OK";

  /** 山峰高度等无关几何量取一个确定值（照 {@link CorridorWorld} / {@link SmallWorld} 的先例）。 */
  private static final double HEX_HEIGHT = 0.5;

  /** 平原地形 key（与 {@code TerrainCatalog} 同一词表）。 */
  private static final String PLAINS = "plains";

  /** 低丘地形 key。 */
  private static final String LOW_HILLS = "low_hills";

  /** 城市 tier：三座城都是 {@code City}（半径 8 ⇒ 覆盖本区全部格）。 */
  private static final String CITY_TIER = PlannedCity.TIER_CITY;

  /** 低丘格（刻意避开三个锚格：城市格取平原 ⇒ 城市的经济播种不受地形系数干扰；取值与 {@code SmallWorld} 同理）。 */
  private static final Set<HexCoord> LOW_HILLS_HEXES =
      Set.of(
          new HexCoord(0, 0),
          new HexCoord(2, -2),
          new HexCoord(-2, 1),
          new HexCoord(1, 1),
          new HexCoord(-1, -2));

  private static final BranchId MAIN = new BranchId("main");

  /** 37 格：半径 3 的完整六边形，按 {@code (q,r)} 升序（顺序即 {@link GameMap#hexes()} 的插入序与人口序列的落盘序 ⇒ 确定性）。 */
  private static final List<HexCoord> HEXES = hexagon(RADIUS_HEX);

  /** 三座城（声明序 = 银 → 铜 → 金；id 序与声明序恰好一致 ⇒ "同距按 id 字典序"的划分与声明序一致）。 */
  private static final List<City> CITIES =
      List.of(
          new City(SILVER_CITY_ID, "银城", SILVER_AT, SILVER_URBAN_POPULATION, "中央座位城（银区）"),
          new City(COPPER_CITY_ID, "铜城", COPPER_AT, COPPER_URBAN_POPULATION, "铜区城（省治）"),
          new City(GOLD_CITY_ID, "金城", GOLD_AT, GOLD_URBAN_POPULATION, "金区城（省治）"));

  private ThreePowersWorld() {}

  /**
   * 组装三权世界的创世状态。**确定性**：同一 {@code mapId} 每次调用逐字段相同。
   *
   * @param mapId 本世界的 map 称谓（非空白；进 {@code EconomyMeta}/{@code ActorMeta}）
   * @throws NullPointerException {@code mapId} 为 null
   * @throws IllegalArgumentException {@code mapId} 为空白
   * @throws IllegalStateException 创世命令被某个 handler 拒、或任一自检不过（装配故障，当场上抛不静默）
   */
  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    if (mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白: " + mapId);
    }

    GameMap map = map();
    SettlementPlan plan = settlementPlan();
    // ★ 不建"世界级政府家户"（{@code governmentRef = null}）：本世界的政府是**三个 GOV 单位**，
    //   由 ThreePowersGovBootstrap 按 GOV 稳定 id 建户与国库（多国/多省播种的既定路径）。
    PopulationSeeder.Seeding seeding = PopulationSeeder.seed(plan, AT.tick());
    EconomySeeder.Seed economy =
        EconomySeeder.plan(mapId, seeding, map, ThreePowersWorld::numeraireAt);

    SimulationState state = emptyGenesisState(map);
    state =
        applyCommand(
            state,
            "social.SetPopulation",
            new SetPopulationHandler(),
            new SocialCodec(),
            setPopulationPayload(plan));
    for (City city : CITIES) {
      state =
          applyCommand(
              state,
              "social.CreateCity",
              new CreateCityHandler(),
              new SocialCodec(),
              createCityPayload(regionOfZone(zoneOf(city.at())), city));
    }
    state =
        applyCommand(
            state,
            "social.SeedGroups",
            new SeedGroupsHandler(),
            new SocialCodec(),
            PopulationSeeder.payload(seeding));
    state =
        applyCommand(
            state,
            "economy.Seed",
            new EconomySeedHandler(),
            new EconomyCodec(),
            economy.economyPayload());
    state =
        applyCommand(
            state,
            "actor.Seed",
            new ActorSeedHandler(),
            new ActorCodec(),
            HouseholdSeeder.payload(
                mapId,
                economy.householdLocations(),
                economy.householdStocks(),
                economy.householdMoney(),
                economy.operators()));
    // ★ 三个 GOV 的行政链（官署/编制/辖区/官吏户/承诺/计划/预算/国库禀赋 + 两种新币的定义与审计）。
    state =
        ThreePowersGovBootstrap.apply(
            state, ThreePowersWorld::applyCommand, map, govSpecs(seeding));
    logGenesis(state);
    return state;
  }

  // ── 三个 GOV 的创世事实 ─────────────────────────────────────────────────────────────────

  /**
   * 三个 GOV 的 spec（顺序 = 创建序：中央 → 两个省）。
   *
   * <p>★ <b>国库币种</b>：每个 GOV 都拿 {@link #TREASURY_SILVER_MILLI} 毫银（现行行政结算与官吏工资规则的记账币）+ {@link
   * #TREASURY_ZONE_CURRENCY_MILLI} 毫**本区法定币**；中央的法定币就是银 ⇒ 它的两笔都落在银上（200,000 毫银）。
   *
   * <p>★ <b>官吏来源</b>：每区各取**本区内**第一条"成年男性、人数 ≥ 2"的批次（确定性；同区取不到 ⇒ 具名抛）。
   */
  private static List<ThreePowersGovBootstrap.GovSpec> govSpecs(PopulationSeeder.Seeding seeding) {
    Map<String, ThreePowersGovBootstrap.ManpowerSource> sources = officialManpowerSources(seeding);
    return List.of(
        new ThreePowersGovBootstrap.GovSpec(
            SILVER_GOV_ID,
            "银省中央政府",
            SILVER_AT,
            GovernmentLevel.CENTRAL,
            Optional.empty(),
            SILVER_REGION_ID,
            treasuryMoney(MoneyVocabulary.SILVER_CURRENCY),
            // ★ 中央 GOV 是**银**的发行政府：银在词表里早已定义 ⇒ economy.DefineCurrency 拒它（currency-already-defined），
            //   故"谁是银的发行主体"只能在 RegisterGovernment 的 issuable 里声明（不声明 ⇒ 国库那笔银发不出发行审计）。
            Set.of(MoneyVocabulary.SILVER_CURRENCY),
            Optional.empty(),
            sources.get(SILVER_CITY_ID)),
        new ThreePowersGovBootstrap.GovSpec(
            COPPER_GOV_ID,
            "铜省政府",
            COPPER_AT,
            GovernmentLevel.PROVINCE,
            Optional.of(SILVER_GOV_ID),
            COPPER_REGION_ID,
            treasuryMoney(COPPER.currencyId()),
            Set.of(),
            Optional.of(
                new ThreePowersGovBootstrap.NewCurrency(
                    COPPER.currencyId().value(), COPPER.scale(), COPPER.displayName())),
            sources.get(COPPER_CITY_ID)),
        new ThreePowersGovBootstrap.GovSpec(
            GOLD_GOV_ID,
            "金省政府",
            GOLD_AT,
            GovernmentLevel.PROVINCE,
            Optional.of(SILVER_GOV_ID),
            GOLD_REGION_ID,
            treasuryMoney(GOLD.currencyId()),
            Set.of(),
            Optional.of(
                new ThreePowersGovBootstrap.NewCurrency(
                    GOLD.currencyId().value(), GOLD.scale(), GOLD.displayName())),
            sources.get(GOLD_CITY_ID)));
  }

  /** 国库货币表（银工作余额 + 本区法定币；两者同币时**合并**成一笔，不写两条同键）。 */
  private static Map<CurrencyId, Long> treasuryMoney(CurrencyId zoneCurrency) {
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.merge(zoneCurrency, TREASURY_ZONE_CURRENCY_MILLI, Math::addExact);
    money.merge(MoneyVocabulary.SILVER_CURRENCY, TREASURY_SILVER_MILLI, Math::addExact);
    return money;
  }

  /**
   * 每区一名官吏来源：该区内**第一条**「成年男性、人数 ≥ {@code OFFICIAL_MEMBERS_PER_GOV}」的人口批次 （按 {@link
   * PopulationSeeder.Seeding#groups()} 的声明序 = 确定性）。
   *
   * <p>★ 成年档判定走 {@code EconomySeeder.ageBracketOf(long)}（与创世劳动折算同一个 social 权威，不另写 365 天边界）。
   */
  private static Map<String, ThreePowersGovBootstrap.ManpowerSource> officialManpowerSources(
      PopulationSeeder.Seeding seeding) {
    Map<String, ThreePowersGovBootstrap.ManpowerSource> picks = new LinkedHashMap<>();
    for (PopulationGroup group : seeding.groups()) {
      if (group.sex() != Sex.MALE
          || EconomySeeder.ageBracketOf(group.ageAtAnchorDays()) != AgeBracket.ADULT.ordinal()
          || group.count() < ThreePowersGovBootstrap.OFFICIAL_MEMBERS_PER_GOV) {
        continue;
      }
      String zone = zoneOf(seeding.locationOf(group.id()));
      picks.putIfAbsent(
          zone,
          new ThreePowersGovBootstrap.ManpowerSource(
              group.id().value(), seeding.householdOf(group.id()).value()));
    }
    for (String zone : ZONE_IDS) {
      if (!picks.containsKey(zone)) {
        throw new IllegalStateException(
            "three-powers 创世装配故障："
                + zone
                + " 区内找不到≥"
                + ThreePowersGovBootstrap.OFFICIAL_MEMBERS_PER_GOV
                + " 人的成年男性批次供官吏户转移");
      }
    }
    return picks;
  }

  // ── 区划分（行政区 = 市场区 = GOV 辖区）─────────────────────────────────────────────────

  /**
   * ★★ <b>一个格属哪个区</b>：到三座城锚格的<b>最近者</b>；同距取**城市 id 字典序小**的那个。
   *
   * <p>★★ <b>为什么这条规则必须与市场拓扑逐字同源</b>：市场区由 {@code MarketTopology.of} 现算（"半径内最近节点、同距按 nodeId
   * 字典序"），行政区由本方法划分。用同一条规则（且城市 id 序 = 声明序）⇒ 两侧**逐格重合**，"每区法定币" 在任何一侧读都自洽；若各写一套，"行政区 ≠
   * 市场区"就会变成一个没人能解释的差异（本仓反复踩过的形态）。
   *
   * @throws IllegalArgumentException 该格不在本世界（说不出它属哪个区 ⇒ 不猜）
   */
  public static String zoneOf(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    if (!HEXES.contains(hex)) {
      throw new IllegalArgumentException(
          "格 " + hex + " 不在 three-powers 世界（半径 " + RADIUS_HEX + " 的六边形）里");
    }
    String bestZone = null;
    int bestDistance = Integer.MAX_VALUE;
    for (City city : CITIES) {
      int distance = hex.distanceTo(city.at());
      if (bestZone == null
          || distance < bestDistance
          || (distance == bestDistance && city.id().compareTo(bestZone) < 0)) {
        bestZone = city.id();
        bestDistance = distance;
      }
    }
    return bestZone;
  }

  /** 该格所属区的行政区 id。 */
  public static RegionId regionOfZone(String zoneId) {
    return new RegionId(
        switch (requireZone(zoneId)) {
          case SILVER_CITY_ID -> SILVER_REGION_ID;
          case COPPER_CITY_ID -> COPPER_REGION_ID;
          default -> GOLD_REGION_ID;
        });
  }

  /** 该区的法定币（区内每格市场的计价币、也是该区 GOV 的发行币/国库币）。 */
  public static CurrencyId currencyOfZone(String zoneId) {
    return switch (requireZone(zoneId)) {
      case SILVER_CITY_ID -> MoneyVocabulary.SILVER_CURRENCY;
      case COPPER_CITY_ID -> COPPER.currencyId();
      default -> GOLD.currencyId();
    };
  }

  /** 该格市场的计价币（= 它所属区的法定币）—— 这就是交给 {@code EconomySeeder} 的逐格计价币函数。 */
  public static CurrencyId numeraireAt(HexCoord hex) {
    return currencyOfZone(zoneOf(hex));
  }

  /** 全部 37 格（保序不可变；(q,r) 升序）。 */
  public static List<HexCoord> hexes() {
    return HEXES;
  }

  /** 某个区的全部格（保序：{@link #HEXES} 序）。 */
  public static List<HexCoord> hexesOfZone(String zoneId) {
    String zone = requireZone(zoneId);
    List<HexCoord> hexes = new ArrayList<>();
    for (HexCoord hex : HEXES) {
      if (zoneOf(hex).equals(zone)) {
        hexes.add(hex);
      }
    }
    return List.copyOf(hexes);
  }

  private static String requireZone(String zoneId) {
    Objects.requireNonNull(zoneId, "zoneId");
    if (!ZONE_IDS.contains(zoneId)) {
      throw new IllegalArgumentException("未知区 id: " + zoneId + "（已登记: " + ZONE_IDS + "）");
    }
    return zoneId;
  }

  // ── 地图 / 人口计划 / 创世状态 ──────────────────────────────────────────────────────────

  /**
   * 37 格地图：<b>三个两两不相交的行政区</b>（并集 = 全部 37 格；由 {@link #hexesOfZone} 的划分构造）+ 平原/低丘两档地形 （块由 {@link
   * TerrainBlocks#split} 切，分割不变式随构造校验）。
   */
  private static GameMap map() {
    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      terrainByHex.put(hex, LOW_HILLS_HEXES.contains(hex) ? LOW_HILLS : PLAINS);
    }
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      hexes.put(hex, new HexCell(HEX_HEIGHT));
    }
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    TerrainType plains = TerrainCatalog.of(PLAINS);
    TerrainType lowHills = TerrainCatalog.of(LOW_HILLS);
    terrainTypes.put(plains.key(), plains);
    terrainTypes.put(lowHills.key(), lowHills);

    Map<RegionId, Region> regions = new LinkedHashMap<>();
    for (int i = 0; i < ZONE_IDS.size(); i++) {
      String zone = ZONE_IDS.get(i);
      RegionId regionId = ZONE_REGIONS.get(i);
      regions.put(
          regionId,
          Region.of(
              regionId,
              regionNameOf(regionId),
              new LinkedHashSet<>(hexesOfZone(zone)),
              new RegionMeta(
                  regionColorOf(regionId),
                  null,
                  "three-powers：一个区 = 一个市场区 = 一个 GOV 辖区 = 一种法定币（"
                      + currencyOfZone(zone).value()
                      + "）",
                  null)));
    }
    return new GameMap(
        hexes,
        TerrainBlocks.split(terrainByHex),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        PathwayGroup.defaults(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static String regionNameOf(RegionId regionId) {
    if (regionId.value().equals(SILVER_REGION_ID)) {
      return SILVER_REGION_NAME;
    }
    if (regionId.value().equals(COPPER_REGION_ID)) {
      return COPPER_REGION_NAME;
    }
    return GOLD_REGION_NAME;
  }

  private static String regionColorOf(RegionId regionId) {
    if (regionId.value().equals(SILVER_REGION_ID)) {
      return "#8c8c8c";
    }
    if (regionId.value().equals(COPPER_REGION_ID)) {
      return "#b87333";
    }
    return "#c9a227";
  }

  /**
   * 一次生成的内存计划：逐格农村人口（每格 {@value #RURAL_POPULATION_PER_HEX}）+ 三座城。
   *
   * <p>★ 本世界**不经** {@code SettlementGenerator}（那需要冻结的 worldgen 配置与随机化区间）；这里只把"已经定死的人口/城市表" 翻成
   * {@link PopulationSeeder} 能吃的形状。故 {@code audit} 为空表（{@code EconomySeeder} 不读它）。
   */
  private static SettlementPlan settlementPlan() {
    Map<HexCoord, Long> rural = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      rural.put(hex, RURAL_POPULATION_PER_HEX);
    }
    List<PlannedCity> cities = new ArrayList<>(CITIES.size());
    for (City city : CITIES) {
      cities.add(
          new PlannedCity(
              city.id(),
              city.name(),
              city.at(),
              CITY_TIER,
              city.urbanPopulation(),
              HEX_COUNT,
              (double) RURAL_POPULATION_PER_HEX * HEX_COUNT,
              1.0,
              1.0,
              city.justification()));
    }
    return new SettlementPlan(rural, cities, Map.of(), URBAN_POPULATION, 0L);
  }

  /**
   * 空创世状态：坐标 {@code (main, 1)}、时刻 {@link #AT}，八个命名空间切片全在场、数据为空（map 除外）。
   *
   * <p>★ 八片在场是硬要求：命令总线在切片缺席时响亮失败（照 {@link CorridorWorld} / {@link SmallWorld} 的先例）。
   */
  private static SimulationState emptyGenesisState(GameMap map) {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("map", new MapSnapshot(ref, AT, map));
    modules.put(
        "social", new SocialSnapshot(ref, AT, new SocialData(Map.of(), Map.of(), Map.of())));
    modules.put("unit", new UnitSnapshot(ref, AT, UnitState.empty()));
    modules.put("sd", new SdSnapshot(ref, AT, SdState.empty()));
    modules.put("economy", new EconomySnapshot(ref, AT, EconomyData.empty()));
    modules.put("actor", new ActorSnapshot(ref, AT, ActorData.empty()));
    modules.put("gov", new GovSnapshot(ref, AT, GovState.empty()));
    modules.put("army", new ArmySnapshot(ref, AT, ArmyData.empty()));
    return new SimulationState(new StateMeta(ref, AT), modules, InMemoryInfoSystem.empty());
  }

  /**
   * 把一条命令交给**它自己的 handler** 求 ChangeSet，再用**它自己的 codec** 施加到候选状态上。
   *
   * <p>★ 这条路径与 {@code CommandBus} 的"逐命令 handler → {@code codec.apply} → 候选态"同语义；差别只在：本类是创世生成器，没有
   * 命令总线可走（见 {@code SmallWorld} 的类注）。被拒**当场抛**（创世不能带一条被拒的命令继续）。
   */
  static SimulationState applyCommand(
      SimulationState state,
      String type,
      CommandHandler handler,
      ModuleCodec codec,
      String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    return switch (handler.handle(state, payloadJson)) {
      case HandlerOutcome.Rejected rejected ->
          throw new IllegalStateException("three-powers 创世命令被拒 " + type + "：" + rejected.reason());
      case HandlerOutcome.Applied applied -> {
        ChangeSet changeSet = applied.changeSet();
        Snapshot base =
            state
                .module(codec.namespace())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "three-powers 装配故障：状态里没有切片 " + codec.namespace()));
        Snapshot next = codec.apply(changeSet, base, state.meta());
        Map<String, Snapshot> modules = new LinkedHashMap<>(state.modules());
        modules.put(codec.namespace(), next);
        yield new SimulationState(state.meta(), modules, state.info());
      }
    };
  }

  // ── 载荷（social.Seed 侧；形状以各 handler 的注为准）──────────────────────────────────────

  /**
   * {@code social.SetPopulation} 载荷：{@code {entries:[{q,r,population}…]}}，按 {@link HexCoord} 排序。
   *
   * <p>★ 字段形状与 {@code SetPopulationHandler} 的类注同源（该 handler 是形状的权威）；本世界只发它，不编造别的字段。
   */
  private static String setPopulationPayload(SettlementPlan plan) {
    List<Map.Entry<HexCoord, Long>> entries = new ArrayList<>(plan.ruralPopulation().entrySet());
    entries.sort(Map.Entry.comparingByKey());
    List<Map<String, Object>> rows = new ArrayList<>(entries.size());
    for (Map.Entry<HexCoord, Long> entry : entries) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("q", entry.getKey().q());
      row.put("r", entry.getKey().r());
      row.put("population", entry.getValue());
      rows.add(row);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", rows);
    return ToolSupport.json(payload);
  }

  /**
   * {@code social.CreateCity} 载荷（{@code id/name/at/region/props}）：城镇人口**不发**——它由 {@code
   * social.SeedGroups} 的批次派生（{@code CreateCityHandler} 明令拒收人口字段）。
   *
   * <p>★ {@code props.tier} 是**显式**发的（照 worldgen 的口径）：市场半径由 tier 决定，而"半径必须覆盖本区全部格" 正是"恰 3
   * 个市场区"的第二条前提 —— 不写明就等于把 3 个区挂在一个默认值上。
   */
  private static String createCityPayload(RegionId region, City city) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", city.id());
    payload.put("name", city.name());
    payload.put("at", ToolSupport.hexCoord(city.at()));
    payload.put("region", region.value());
    payload.put("props", Map.of("tier", CITY_TIER));
    return ToolSupport.json(payload);
  }

  // ── 创世日志（§一.9）────────────────────────────────────────────────────────────────────

  /**
   * 创世落盘的读数（四件事各一条 INFO + I23 断言一条）：<b>几个区 / 几个 GOV / 几种货币 / 每区法定币</b>。
   *
   * <p>★ 只记稳定 id 与数量，不记载荷/密钥；日志失败不影响创世（SLF4J 的调用不抛）。
   */
  private static void logGenesis(SimulationState state) {
    List<MarketZoneReadout.Zone> zones = MarketZoneReadout.zones(state);
    List<CurrencyDef> currencies = List.copyOf(requireEconomy(state).currencies().values());
    int govs = ThreePowersGovBootstrap.requireGovCount(state);
    EventLog.channel(AppLog.shell())
        .info(
            LogEvent.of(
                EVENT_GENESIS,
                AppLogSource.SHELL_LIFECYCLE,
                "world",
                MAP_ID,
                "hexes",
                HEX_COUNT,
                "regions",
                ZONE_REGIONS.size(),
                "cities",
                CITIES.size(),
                "population",
                TOTAL_POPULATION,
                "markets",
                requireEconomy(state).markets().size()));
    EventLog.channel(AppLog.shell())
        .info(
            LogEvent.of(
                EVENT_ZONES,
                AppLogSource.SHELL_LIFECYCLE,
                "world",
                MAP_ID,
                "zones",
                zones.size(),
                "zoneIds",
                zoneIds(zones)));
    EventLog.channel(AppLog.shell())
        .info(LogEvent.of(EVENT_GOVS, AppLogSource.SHELL_LIFECYCLE, "world", MAP_ID, "govs", govs));
    EventLog.channel(AppLog.shell())
        .info(
            LogEvent.of(
                EVENT_CURRENCIES,
                AppLogSource.SHELL_LIFECYCLE,
                "world",
                MAP_ID,
                "currencies",
                currencies.size(),
                "currencyIds",
                currencyIds(currencies)));
    for (MarketZoneReadout.Zone zone : zones) {
      EventLog.channel(AppLog.shell())
          .info(
              LogEvent.of(
                  EVENT_ZONE_NUMERAIRE,
                  AppLogSource.SHELL_LIFECYCLE,
                  "world",
                  MAP_ID,
                  "zone",
                  zone.zoneId(),
                  "anchor",
                  zone.anchor().q() + "_" + zone.anchor().r(),
                  "hexes",
                  zone.hexCount(),
                  "numeraire",
                  zone.numeraire().value()));
    }
    EventLog.channel(AppLog.shell())
        .info(
            LogEvent.of(
                EVENT_EXCLUSIVITY,
                AppLogSource.SHELL_LIFECYCLE,
                "world",
                MAP_ID,
                "regions",
                ZONE_REGIONS.size(),
                "hexesChecked",
                exclusiveHexCount(state),
                "overlaps",
                0));
  }

  /** 行政区互斥（I23）的逐 hex 读数：全域被**恰好一个**行政区覆盖的格数（构造性应为 {@link #HEX_COUNT}）。 */
  private static int exclusiveHexCount(SimulationState state) {
    GameMap map = ((MapSnapshot) state.module("map").orElseThrow()).map();
    int exclusive = 0;
    for (HexCoord hex : HEXES) {
      int covering = 0;
      for (RegionId regionId : ZONE_REGIONS) {
        Region region = map.regions().get(regionId);
        if (region != null && region.hexes().contains(hex)) {
          covering++;
        }
      }
      if (covering == 1) {
        exclusive++;
      }
    }
    return exclusive;
  }

  private static List<String> zoneIds(List<MarketZoneReadout.Zone> zones) {
    List<String> ids = new ArrayList<>(zones.size());
    for (MarketZoneReadout.Zone zone : zones) {
      ids.add(zone.zoneId());
    }
    return ids;
  }

  private static List<String> currencyIds(List<CurrencyDef> currencies) {
    List<String> ids = new ArrayList<>(currencies.size());
    for (CurrencyDef def : currencies) {
      ids.add(def.currencyId().value());
    }
    return ids;
  }

  private static EconomyData requireEconomy(SimulationState state) {
    return state
        .module("economy")
        .filter(EconomySnapshot.class::isInstance)
        .map(EconomySnapshot.class::cast)
        .map(EconomySnapshot::data)
        .orElseThrow(() -> new IllegalStateException("three-powers 创世日志需要 economy 切片（装配故障）"));
  }

  /** 半径 {@code n} 的完整六边形，按 {@code (q,r)} 升序（确定性；与 {@code SmallWorld} 的"声明显式清单"同序）。 */
  private static List<HexCoord> hexagon(int n) {
    List<HexCoord> hexes = new ArrayList<>();
    for (int q = -n; q <= n; q++) {
      int from = Math.max(-n, -q - n);
      int to = Math.min(n, -q + n);
      for (int r = from; r <= to; r++) {
        hexes.add(new HexCoord(q, r));
      }
    }
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    return List.copyOf(hexes);
  }

  /** 一座城（本世界的具名常量；{@code id} 同时是市场拓扑的节点 id）。 */
  private record City(
      String id, String name, HexCoord at, long urbanPopulation, String justification) {}
}
