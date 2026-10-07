package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
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
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>小世界（P1.4，2026-10-23 扩到 19 格）</b>：一个<b>程序化、确定性、可直接 bootstrap 的 19 格世界</b>——1 个 {@link
 * Region}、1 座首都 + 1 座镇、4,800 人（3,800 农村 + 1,000 城镇），经济地基走<b>唯一路线 production-runtime</b>（政府内置）。
 *
 * <p>★ <b>扩格依据</b>：{@code
 * docs/superpowers/specs/2026-10-23-smallworld-19hex-and-economy-360tick-design.md} §4（19 格六邻连通 / 1
 * Region / 人口公式不变 / 地形只用 plains·low_hills / 世界 id 与地形两城口径不变）。
 *
 * <p>★★ <b>它解决哪件事</b>：{@code v17levant}（{@link RichWorld}）是 59,223 格的复刻真档，起一次要读大资源、跑一次要等很久； {@code
 * corridor}（{@link CorridorWorld}）只有 3 格且没有真实经济。<b>两者之间缺一个"浏览器能打开、经济有政府家户/国库、又足够小"的真实世界</b>——
 * 本类就是它：{@code --world=small-world} 即可在空库首启时种出它（登记见 {@link WorldRegistry#SMALL_WORLD}）。
 *
 * <p>★★ <b>世界形状（一切数字都是本类的具名常量，见下）</b>：
 *
 * <ul>
 *   <li><b>19 hex</b>（{@value #HEX_COUNT}）：中心 + 完整第一环（6 格）+ 完整第二环（12 格）= 半径 2 的完整六边形，六邻域连通；
 *   <li><b>1 个 Region</b>（{@link #REGION_ID}，名字 {@value #REGION_NAME}）：全部 19 格都归属它；
 *   <li><b>1 座首都</b>（{@value #CAPITAL_ID}，{@value #CAPITAL_URBAN_POPULATION} 城镇人口）+ <b>1
 *       座镇</b>（{@value #TOWN_ID}，{@value #TOWN_URBAN_POPULATION} 城镇人口）；
 *   <li><b>人口 4,800</b>：19 格 × {@value #RURAL_POPULATION_PER_HEX} 农村人口 = 3,800，加城镇
 *       1,000；人口最多的是首都格（200 + 700 = 900）⇒ 世界级政府家户 {@code hh-gov-world-silver} 落在首都；
 *   <li><b>地形</b>：平原 15 格（原 11 + 新增 4）+ 低丘 4 格（城市两格取平原，故土地的"满可耕/低丘"两档都真的被 economic 播种读到）。
 * </ul>
 *
 * <p>★★ <b>经济播种走真路径 production-runtime（政府内置）</b>：本类不另写一套播种逻辑，而是按 {@code WorldgenInitializeTool}
 * 的同一条命令序，把同一批<b>真命令载荷</b>交给<b>真 handler</b>：
 *
 * <pre>
 * social.SetPopulation（19 格农村人口序列）
 * → social.CreateCity ×2（首都 + 镇）
 * → social.SeedGroups（同一份批次/家户，PopulationSeeder）
 * → economy.Seed（EconomySeeder.plan 的 production-runtime 载荷：产业/阶层/劳动/资产/市场 + world-silver 政府家户/国库/周期铸币政策）
 * → actor.Seed（HouseholdSeeder 载荷：与 economy.Seed 同一次 plan 的家户/经营者 actor + 账本 + 创世货币）
 * → GovWorldBootstrap（Z5：中央/省 GOV + office unit + 官吏户/岗位/GOV_SERVICE 承诺 + 编制计划/预算 + 国库注资）
 * </pre>
 *
 * <p>★★ <b>为什么可以先造空切片、再逐条 apply handler</b>：创世本来就发生在"命令面存在之前"——{@code CoreSimos#bootstrapGenesis}
 * 是全仓唯一绕过 {@code submit} 的写路径（见其类注）。本类只是把"该批命令作用在空世界上的结果"算出来， 用的仍是每个命令自己的 handler 与模块 codec 的
 * {@code apply}（Command → ChangeSet 这条语义不走样）；落盘时由 {@code bootstrapGenesis} 写成一条 {@code (main, 1)}
 * 创世 revision，不伪造领域命令历史。
 *
 * <p>★ <b>确定性、无随机、无时钟</b>：无 {@code UUID}、无 {@code Random}、无墙钟读取；同一 {@code mapId} 每次调用产出 {@link
 * SimulationState#equals(Object) 相等}的状态（逐字段），本类的构造序也确定：格按 {@link #HEXES} 声明序（即 {@link
 * GameMap#hexes()} 插入序；{@code PopulationSeeder} 另按 {@code (q,r)} 排序）、城市按人口降序/id 升序、 家户 actor 由
 * {@code HouseholdSeeder} 的确定序。★ 不承诺内部 {@code Set}/{@code Map} 的 {@code toString()} 迭代序——那是 util
 * 既有实现（{@code Set.copyOf}/{@code Map.copyOf} 的迭代序不是内容的纯函数），各世界一致。
 *
 * <p>★ <b>它当前不带 army / sd</b>：八个命名空间的切片都在场（命令总线要求），{@code unit}/{@code gov} 已由 {@link
 * GovWorldBootstrap} 在创世批里种出（2 个 GOV 单位 + 官署 office unit + 官吏户/岗位/承诺/2 份编制计划与预算 + 2 份国库注资），{@code
 * army}/{@code sd} 仍为空片。{@code unit}/{@code sd} 命令要 "有切片才不响亮失败"，空片正是它们的合法起点。
 *
 * <p>★ {@code mapId} 与非空白校验口径同 {@link CorridorWorld}：{@code GameMap} 本身没有 id，状态里无处存它；它进 {@code
 * EconomyMeta} / {@code ActorMeta} 的载荷（由 seeders 透传）。
 */
public final class SmallWorld {

  /**
   * 本世界的 worldId / 缺省 mapId（登记在 {@link WorldRegistry#SMALL_WORLD}；{@code config/shell.json} 可切它）。
   */
  public static final String MAP_ID = "small-world";

  /** 唯一 Region 的稳定 id。 */
  public static final String REGION_ID = "small-world";

  /** 唯一 Region 的显示名。 */
  public static final String REGION_NAME = "小世界";

  /** 格数：19（半径 2 的完整六边形 = 中心 1 + 第一环 6 + 第二环 12；世界形状见类注）。 */
  public static final int HEX_COUNT = 19;

  /** 每格农村人口（19 × 200 = 3,800）。 */
  public static final long RURAL_POPULATION_PER_HEX = 200L;

  /** 首都城镇人口。 */
  public static final long CAPITAL_URBAN_POPULATION = 700L;

  /** 镇城镇人口。 */
  public static final long TOWN_URBAN_POPULATION = 300L;

  /** 总人口 = 3,800 农村 + 1,000 城镇 = 4,800（19 hex 的公式推导值）。 */
  public static final long TOTAL_POPULATION =
      RURAL_POPULATION_PER_HEX * HEX_COUNT + CAPITAL_URBAN_POPULATION + TOWN_URBAN_POPULATION;

  /** 首都格（也是政府家户落点：该格人口最多）。 */
  public static final HexCoord CAPITAL_AT = new HexCoord(0, 0);

  /** 镇格（与首都相距 2 环）。 */
  public static final HexCoord TOWN_AT = new HexCoord(0, 2);

  /** 首都的稳定 id。 */
  public static final String CAPITAL_ID = "c-small-capital";

  /** 镇的稳定 id。 */
  public static final String TOWN_ID = "c-small-town";

  /** 创世时刻：tick 0（人口批次锚点、结算起点都用它）。 */
  public static final SimosTimestamp AT = SimosTimestamp.of(0L);

  /** 山峰高度等无关几何量取一个确定值（照 {@link CorridorWorld} 的先例）。 */
  private static final double HEX_HEIGHT = 0.5;

  /** 平原/低丘地形 key（与 {@code TerrainCatalog} 同一词表）。 */
  private static final String PLAINS = "plains";

  private static final String LOW_HILLS = "low_hills";

  /**
   * 19 格 = 中心 + 完整第一环（6）+ 完整第二环（12）：半径 2 的完整六边形，六邻域连通、无重复。
   *
   * <p>前 15 条是 P1.4 的原有清单（相对顺序未动，最小 diff）；末尾 4 条 {@code (2,-2)/(1,-2)/(-2,1)/(-1,2)} 是第二环仅剩的四个缺口，
   * 补齐后第二环完整。每个第二环格都与第一环相邻（新 4 格分别邻第一环的 {@code (1,-1)}、{@code (0,-1)}、{@code (-1,0)}、 {@code
   * (-1,1)}），故整图连通。
   *
   * <p>顺序即 {@link GameMap#hexes()} 的插入序与人口序列的落盘序（确定性）。
   */
  private static final List<HexCoord> HEXES =
      List.of(
          new HexCoord(0, 0),
          new HexCoord(1, 0),
          new HexCoord(1, -1),
          new HexCoord(0, -1),
          new HexCoord(-1, 0),
          new HexCoord(-1, 1),
          new HexCoord(0, 1),
          new HexCoord(2, 0),
          new HexCoord(2, -1),
          new HexCoord(0, -2),
          new HexCoord(-2, 0),
          new HexCoord(-2, 2),
          new HexCoord(0, 2),
          new HexCoord(1, 1),
          new HexCoord(-1, -1),
          new HexCoord(2, -2),
          new HexCoord(1, -2),
          new HexCoord(-2, 1),
          new HexCoord(-1, 2));

  /** 低丘 4 格（保持 P1.4 原有清单不动；新增的 4 格取平原，城市两格也刻意取平原：首都/镇的经济播种不受地形系数干扰）。 */
  private static final Set<HexCoord> LOW_HILLS_HEXES =
      Set.of(new HexCoord(2, 0), new HexCoord(2, -1), new HexCoord(0, -2), new HexCoord(-2, 0));

  private static final BranchId MAIN = new BranchId("main");

  private SmallWorld() {}

  /**
   * 组装小世界的创世状态。**确定性**：同一 {@code mapId} 每次调用逐字段相同。
   *
   * @param mapId 本世界的 map 称谓（非空白；进 {@code EconomyMeta}/{@code ActorMeta}）
   * @throws NullPointerException {@code mapId} 为 null
   * @throws IllegalArgumentException {@code mapId} 为空白
   * @throws IllegalStateException 创世命令被某个 handler 拒（装配故障，当场上抛不静默；消息含命令类型与拒因）
   */
  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    if (mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白: " + mapId);
    }

    GameMap map = map();
    Region region = map.regions().get(new RegionId(REGION_ID));
    if (region == null) {
      throw new IllegalStateException("小世界装配故障：地图里没有 Region " + REGION_ID);
    }
    SettlementPlan plan = settlementPlan();
    // ★★ P2-C §13.7：小世界 demo 的政府是**世界级** world-silver（不是 GOV 单位）⇒ 显式把它的稳定 id 作为
    //   政府引用交给播种器；家户身份 = hh-gov-world-silver（确定性），不再靠"第一个 seed 撞上的 official 户"。
    PopulationSeeder.Seeding seeding =
        PopulationSeeder.seed(plan, AT.tick(), EconomySeeder.GENESIS_GOVERNMENT_ID.value());
    // ★ 经济与 actor 读**同一次** plan（H1/H4/H5 的同源接缝）：商品库存/货币/经营者账本与
    //   economy.Seed 的逐格状态出自同一次计算，两处各算一遍必然漂开（本仓明令禁止）。
    EconomySeeder.Seed economy = EconomySeeder.plan(mapId, seeding, map);

    SimulationState state = emptyGenesisState(map);
    // ★ 命令序与 WorldgenInitializeTool.buildBatch 逐条一致（人口 → 城市 → 批次 → 经济 → 家户 actor）。
    state =
        applyCommand(
            state,
            "social.SetPopulation",
            new SetPopulationHandler(),
            new SocialCodec(),
            setPopulationPayload(plan));
    for (PlannedCity city : plan.cities()) {
      state =
          applyCommand(
              state,
              "social.CreateCity",
              new CreateCityHandler(),
              new SocialCodec(),
              createCityPayload(region.id(), city));
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
    // ★★ 2026-10-23 Z5：19 hex 世界的政府行政链（中央/省 GOV + office unit + 官吏户/岗位/承诺 +
    //   编制计划/预算 + 国库注资）同一批创世落成——沿用同一条 handler → codec.apply 语义，
    //   整份状态仍由 ShellMain.seedGenesisIfEmpty → bootstrapGenesis 写成一条 (main, 1) revision。
    return GovWorldBootstrap.apply(
        state, SmallWorld::applyCommand, map, REGION_ID, officialManpowerSources(seeding));
  }

  /**
   * 官吏来源选人（唯一判据，确定性）：在 {@link PopulationSeeder} 的批次序列里按声明序取**前两个**「成年男性、人数 ≥ {@link
   * GovWorldBootstrap#OFFICIAL_MEMBERS_PER_GOV}」的批次，分别供中央/省官吏户转移。
   *
   * <p>★ 成年档判定走 {@link EconomySeeder#ageBracketOf(long)}（与创世劳动折算同一个 social 权威，不另写 365 天边界）；
   * 不取政府家户（它 0 人口、无批次天然不在表里）。
   */
  private static List<GovWorldBootstrap.ManpowerSource> officialManpowerSources(
      PopulationSeeder.Seeding seeding) {
    List<GovWorldBootstrap.ManpowerSource> picks = new ArrayList<>(2);
    for (PopulationGroup group : seeding.groups()) {
      if (group.sex() != Sex.MALE
          || EconomySeeder.ageBracketOf(group.ageAtAnchorDays()) != AgeBracket.ADULT.ordinal()
          || group.count() < GovWorldBootstrap.OFFICIAL_MEMBERS_PER_GOV) {
        continue;
      }
      picks.add(
          new GovWorldBootstrap.ManpowerSource(
              group.id().value(), seeding.householdOf(group.id()).value()));
      if (picks.size() == 2) {
        break;
      }
    }
    if (picks.size() < 2) {
      throw new IllegalStateException(
          "小世界创世装配故障：找不到两个≥"
              + GovWorldBootstrap.OFFICIAL_MEMBERS_PER_GOV
              + " 人的成年男性批次供官吏户转移（实际 "
              + picks.size()
              + " 个）");
    }
    return List.copyOf(picks);
  }

  /**
   * 空创世状态：坐标 {@code (main, 1)}、时刻 {@link #AT}，八个命名空间切片全在场、数据为空（map 除外）。
   *
   * <p>★ 八片在场是硬要求：命令总线在切片缺席时响亮失败（照 {@link CorridorWorld} / {@link RichWorld} 的先例）。
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
   * 命令总线可走（见类注）。被拒**当场抛**（创世不能带一条被拒的命令继续）。
   */
  private static SimulationState applyCommand(
      SimulationState state,
      String type,
      CommandHandler handler,
      ModuleCodec codec,
      String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    return switch (handler.handle(state, payloadJson)) {
      case HandlerOutcome.Rejected rejected ->
          throw new IllegalStateException("小世界创世命令被拒 " + type + "：" + rejected.reason());
      case HandlerOutcome.Applied applied -> {
        ChangeSet changeSet = applied.changeSet();
        Snapshot base =
            state
                .module(codec.namespace())
                .orElseThrow(
                    () -> new IllegalStateException("小世界装配故障：状态里没有切片 " + codec.namespace()));
        Snapshot next = codec.apply(changeSet, base, state.meta());
        Map<String, Snapshot> modules = new LinkedHashMap<>(state.modules());
        modules.put(codec.namespace(), next);
        yield new SimulationState(state.meta(), modules, state.info());
      }
    };
  }

  /** 19 格地图：唯一 Region（含全部格）+ 平原/低丘两档地形（块由 {@link TerrainBlocks#split} 切，分割不变式随构造校验）。 */
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
    regions.put(
        new RegionId(REGION_ID),
        Region.of(
            new RegionId(REGION_ID),
            REGION_NAME,
            new LinkedHashSet<>(HEXES),
            new RegionMeta("#7ba05b", null, "P1.4 小世界：" + HEX_COUNT + " hex / 1 区域", null)));
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

  /**
   * 一次生成的内存计划：逐格农村人口（每格 {@value #RURAL_POPULATION_PER_HEX}）+ 首都/镇两座城。
   *
   * <p>★ 本世界**不经** {@code SettlementGenerator}（那需要冻结的 worldgen 配置与随机化区间）；这里只是把"已经定死的人口/城市表" 翻成
   * {@link PopulationSeeder} 能吃的形状。故 {@code audit} 为空表——它是生成器的审计中间量，本路径不产生它，也不该伪造 （{@code
   * EconomySeeder} 不读它）。
   */
  private static SettlementPlan settlementPlan() {
    Map<HexCoord, Long> rural = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      rural.put(hex, RURAL_POPULATION_PER_HEX);
    }
    List<PlannedCity> cities =
        List.of(
            new PlannedCity(
                CAPITAL_ID,
                "小世界首府",
                CAPITAL_AT,
                PlannedCity.TIER_MAJOR_CITY,
                CAPITAL_URBAN_POPULATION,
                HEX_COUNT,
                (double) RURAL_POPULATION_PER_HEX * HEX_COUNT,
                1.0,
                1.5,
                "P1.4 小世界首都：人口最多的格（production-runtime 政府家户落点）"),
            new PlannedCity(
                TOWN_ID,
                "小世界镇",
                TOWN_AT,
                PlannedCity.TIER_TOWN,
                TOWN_URBAN_POPULATION,
                6,
                200.0,
                1.0,
                1.0,
                "P1.4 小世界第二座城（镇级）"));
    return new SettlementPlan(
        rural, cities, Map.of(), CAPITAL_URBAN_POPULATION + TOWN_URBAN_POPULATION, 0L);
  }

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
   * {@code social.CreateCity} 的最小载荷（{@code id/name/at/region}）：城镇人口**不发**——它由 {@code
   * social.SeedGroups} 的批次派生（{@code CreateCityHandler} 明令拒收人口字段）。
   */
  private static String createCityPayload(RegionId region, PlannedCity city) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", city.id());
    payload.put("name", city.name());
    payload.put("at", ToolSupport.hexCoord(city.at()));
    payload.put("region", region.value());
    return ToolSupport.json(payload);
  }
}
