package io.mosire.simos.app.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.culture.codec.CultureCodec;
import io.mosire.simos.culture.spi.SetHouseholdCultureHandler;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexAreas;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.spi.SeedArableOwnershipHandler;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
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

/**
 * ★★ <b>D-032 P6a1：32 hex / 单市场区 / 单地方政府测试世界</b>（test scope，仅用于 P6a2 的 360 tick 模拟）。
 *
 * <p>父设计锚点：{@code docs/superpowers/specs/2026-10-19-d032-rural-runtime-target-design.md}
 * §1.1–§1.3、§6.3、§7。 用户 2026-10-19 要求：小世界规模大一些（32 hex）、<b>1 个市场区</b>、<b>1 个地方政府</b>，跑 360 tick
 * 后把数据表交用户核对。
 *
 * <p>★★ <b>本类只造创世世界，不改 M0–M9 顺序、公式或状态所有权</b>。装配序列逐条复用生产路径：
 *
 * <pre>
 * map() → Region → SettlementPlan → PopulationSeeder → EconomySeeder.plan
 * → 空 SimulationState（十片在场）
 * → social.SetPopulation → social.CreateCity → social.SeedGroups
 * → production.Seed → economy.Seed → actor.Seed
 * </pre>
 *
 * <p>★★ <b>刻意不调用 {@link io.mosire.simos.app.tools.write.GovWorldBootstrap#apply}</b>：那条路径会建中央/省两级
 * GOV 单位。本世界只需要一个地方政府记录 + 一个国库家户（{@code hh-gov-local}）；GOV 切片保持合法的空状态。
 *
 * <p>★★ <b>地方政府命名</b>：现有 {@code PopulationSeeder}/{@code EconomySeeder} 只能经 {@code
 * GENESIS_GOVERNMENT_ID} 播政府记录，故本夹具在 {@code economy.Seed} 载荷出口处把该唯一政府记录的 {@code id}/{@code
 * nationRef} 改名为 {@code local}（家户引用仍由 {@code PopulationSeeder} 以 {@code "local"} 生成 {@code
 * hh-gov-local}）。不改生产代码的常量与语义，只换本世界那份载荷的身份标签。
 *
 * <p>★ <b>确定性</b>：无随机、无 UUID、无墙钟。格按 {@code (q,r)} 升序；城市/家户/名册均由既有 seeder 的确定序产生。 同一 {@code mapId}
 * 两次调用逐字段相等。
 */
public final class Rural32HexWorld {

  /** 本世界的 mapId / worldId 建议值。 */
  public static final String MAP_ID = "d032-32hex";

  /** 唯一 Region 的稳定 id（32 格全含；不建 central/province 两级）。 */
  static final String REGION_ID = "d032-32hex";

  /** 唯一 Region 的显示名。 */
  static final String REGION_NAME = "D-032 32hex 单市场区";

  /** 格数：32。 */
  static final int HEX_COUNT = 32;

  /** 每个农村 hex 的人口。 */
  static final long RURAL_POPULATION_PER_HEX = 200L;

  /** 首都/城镇格 (0,0) 的城镇人口。 */
  static final long CAPITAL_URBAN_POPULATION = 700L;

  /** 总人口 = 32 × 200 + 700 = 7,100。 */
  static final long TOTAL_POPULATION =
      RURAL_POPULATION_PER_HEX * HEX_COUNT + CAPITAL_URBAN_POPULATION;

  /** 首都格 = 政府家户落点（人口最多：200 农村 + 700 城镇）。 */
  static final HexCoord CAPITAL_AT = new HexCoord(0, 0);

  /** 首都稳定 id。 */
  static final String CAPITAL_ID = "c-d032-capital";

  /** 地方政府引用：PopulationSeeder 据此生成 {@code hh-gov-local}。 */
  static final String LOCAL_GOVERNMENT_REF = "local";

  /** 唯一政府记录的稳定 id（载荷出口改名后的值）。 */
  static final String LOCAL_GOVERNMENT_ID = "local";

  /** 唯一政府记录的 nationRef（载荷出口改名后的值）。 */
  static final String LOCAL_GOVERNMENT_NATION_REF = "local";

  /** 创世时刻：tick 0。 */
  static final SimosTimestamp AT = SimosTimestamp.of(0L);

  /** 山峰高度等无关几何量（照 SmallWorld / CorridorWorld 的先例取确定值）。 */
  private static final double HEX_HEIGHT = 0.5;

  /** 唯一地形：平原（土地全部可耕，32 hex 同一市场/同一地价）。 */
  private static final String PLAINS = "plains";

  /**
   * 32 格 = 4×8 平行四边形：{@code q ∈ {-1,0,1,2}}、{@code r ∈ {0..7}}，按 {@code q/r} 升序。
   *
   * <p>六邻连通：相邻列之间按轴向邻居 {@code (q±1,r)/(q±1,r∓1)} 与同列 {@code (q,r±1)} 相连，整表连通、无重复。 顺序即 {@link
   * GameMap#hexes()} 的插入序与人口/经济载荷的落盘序。
   */
  private static final List<HexCoord> HEXES = buildHexes();

  private static final BranchId MAIN = new BranchId("main");

  private Rural32HexWorld() {}

  /**
   * 组装 32hex 单市场区世界的创世状态。**确定性**：同一 {@code mapId} 每次调用逐字段相同。
   *
   * @param mapId 本世界的 map 称谓（非空白；进 {@code EconomyMeta}/{@code ActorMeta}）
   * @throws NullPointerException {@code mapId} 为 null
   * @throws IllegalArgumentException {@code mapId} 为空白
   * @throws IllegalStateException 创世命令被某个 handler 拒（装配故障，当场上抛不静默）
   */
  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    if (mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白: " + mapId);
    }

    GameMap map = map();
    if (map.regions().get(new RegionId(REGION_ID)) == null) {
      throw new IllegalStateException("32hex 世界装配故障：地图里缺 Region " + REGION_ID);
    }
    SettlementPlan plan = settlementPlan();
    PopulationSeeder.Seeding seeding = PopulationSeeder.seed(plan, AT.tick(), LOCAL_GOVERNMENT_REF);
    EconomySeeder.Seed economy = EconomySeeder.plan(mapId, seeding, map);

    SimulationState state = emptyGenesisState(map);
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
              createCityPayload(new RegionId(REGION_ID), city));
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
            "production.Seed",
            new io.mosire.simos.production.spi.ProductionSeedHandler(),
            new io.mosire.simos.production.codec.ProductionCodec(),
            economy.productionPayload());
    state =
        applyCommand(
            state,
            "economy.Seed",
            new EconomySeedHandler(),
            new EconomyCodec(),
            localGovernmentEconomyPayload(economy));
    // ★★★ D-032 L4：Map component.owner 是 LAND 唯一权威 —— 用与 economy.Seed 同一份 Seed 的
    //   landHoldings 播种 arable owner 属性（同一创世 revision）。
    state =
        applyCommand(
            state,
            SeedArableOwnershipHandler.TYPE,
            new SeedArableOwnershipHandler(),
            new MapCodec(),
            economy.landOwnershipSeedPayload());
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
    return applyDefaultCultureLabels(state);
  }

  /**
   * ★ T1（D-032 v4 R4b/§9）：给本世界全部家户落统一默认文化标签。R1 的 merge key 含 {@code cultureKey}； 若测试世界没有 culture
   * 标签，economy 只能走 {@code HouseholdCultureKey.missing()}，R1 的“同文化合并”就没有真实靶子。
   *
   * <p>用生产命令 {@code culture.SetHouseholdCulture} 写入（唯一写口），不把文化塞进人口/经济第二本账。
   */
  private static SimulationState applyDefaultCultureLabels(SimulationState state) {
    SocialSnapshot social = (SocialSnapshot) state.module("social").orElseThrow();
    List<HouseholdId> households = new ArrayList<>(social.data().households().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      String payload =
          "{\"householdId\":\""
              + household.value()
              + "\",\"cultureId\":\"d032-default\",\"religionId\":\"d032-default\","
              + "\"customs\":[],\"label\":\"本地农户\"}";
      state =
          applyCommand(
              state,
              SetHouseholdCultureHandler.TYPE,
              new SetHouseholdCultureHandler(),
              new CultureCodec(),
              payload);
    }
    return state;
  }

  /** 32 格清单（不可变、保序；供探针/后续 P6a2 复用）。 */
  static List<HexCoord> hexes() {
    return HEXES;
  }

  /** 32 格单 Region 地图（全部平原；同一市场区/同一地价的地形基础）。 */
  static GameMap map() {
    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      terrainByHex.put(hex, PLAINS);
    }
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      hexes.put(hex, HexAreas.initialCell(hex, HEX_HEIGHT, terrainByHex.get(hex)));
    }
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    TerrainType plains = TerrainCatalog.of(PLAINS);
    terrainTypes.put(plains.key(), plains);

    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        new RegionId(REGION_ID),
        Region.of(
            new RegionId(REGION_ID),
            REGION_NAME,
            new LinkedHashSet<>(HEXES),
            new RegionMeta("#7ba05b", null, "D-032 P6a1：32 hex 单市场区/单地方政府测试世界", null)));
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

  /** 32 格农村人口各 200 + 首都 (0,0) 城镇 700。 */
  private static SettlementPlan settlementPlan() {
    Map<HexCoord, Long> rural = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      rural.put(hex, RURAL_POPULATION_PER_HEX);
    }
    List<PlannedCity> cities =
        List.of(
            new PlannedCity(
                CAPITAL_ID,
                "D-032 地方政府首府",
                CAPITAL_AT,
                PlannedCity.TIER_MAJOR_CITY,
                CAPITAL_URBAN_POPULATION,
                HEX_COUNT,
                (double) RURAL_POPULATION_PER_HEX * HEX_COUNT,
                1.0,
                1.5,
                "D-032 P6a1 32hex 世界唯一首府/地方 GOV 家户落点"));
    return new SettlementPlan(rural, cities, Map.of(), CAPITAL_URBAN_POPULATION, 0L);
  }

  /** 32 格显式表（4×8 平行四边形，按 q/r 升序；确定性）。 */
  private static List<HexCoord> buildHexes() {
    List<HexCoord> hexes = new ArrayList<>(HEX_COUNT);
    for (int q = -1; q <= 2; q++) {
      for (int r = 0; r <= 7; r++) {
        hexes.add(new HexCoord(q, r));
      }
    }
    if (hexes.size() != HEX_COUNT) {
      throw new IllegalStateException("32hex 表长度不是 32: " + hexes.size());
    }
    return List.copyOf(hexes);
  }

  /**
   * 空创世状态：坐标 {@code (main, 1)}、时刻 {@link #AT}，十个命名空间切片全在场、数据为空（map 除外）。
   *
   * <p>★ 十片在场是硬要求：命令总线在切片缺席时响亮失败；本世界不建 GOV 单位，故 gov 是合法的空片。
   */
  private static SimulationState emptyGenesisState(GameMap map) {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("map", new MapSnapshot(ref, AT, map));
    modules.put(
        "social", new SocialSnapshot(ref, AT, new SocialData(Map.of(), Map.of(), Map.of())));
    modules.put("unit", new UnitSnapshot(ref, AT, UnitState.empty()));
    modules.put("economy", new EconomySnapshot(ref, AT, EconomyData.empty()));
    modules.put("actor", new ActorSnapshot(ref, AT, ActorData.empty()));
    modules.put("gov", new GovSnapshot(ref, AT, GovState.empty()));
    modules.put("army", new ArmySnapshot(ref, AT, ArmyData.empty()));
    modules.put("ga", new GaSnapshot(ref, AT, GaState.empty()));
    GenesisModuleSlices.ensureEmptyProductionAndCulture(modules, ref, AT);
    return new SimulationState(new StateMeta(ref, AT), modules, InMemoryInfoSystem.empty());
  }

  /** 把一条命令交给它自己的 handler 求 ChangeSet，再用它自己的 codec 施加到候选状态上。 */
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
          throw new IllegalStateException("32hex 世界创世命令被拒 " + type + "：" + rejected.reason());
      case HandlerOutcome.Applied applied -> {
        ChangeSet changeSet = applied.changeSet();
        Snapshot base =
            state
                .module(codec.namespace())
                .orElseThrow(
                    () -> new IllegalStateException("32hex 世界装配故障：状态里没有切片 " + codec.namespace()));
        Snapshot next = codec.apply(changeSet, base, state.meta());
        Map<String, Snapshot> modules = new LinkedHashMap<>(state.modules());
        modules.put(codec.namespace(), next);
        yield new SimulationState(state.meta(), modules, state.info());
      }
    };
  }

  /**
   * {@code economy.Seed} 载荷出口：把唯一政府记录（现有 seeder 只能经 {@code GENESIS_GOVERNMENT_ID} 播的那一条）的 {@code
   * id}/{@code nationRef} 改名为地方口径，并同步 {@code moneyIssuances[].governmentId}。
   *
   * <p>★ 只改本世界这份载荷的身份标签；不新增/删除政府、不改国库家户、不改发行总量、不改任何生产/土地/市场/名册数据。
   */
  private static String localGovernmentEconomyPayload(EconomySeeder.Seed economy) {
    ObjectMapper mapper = SimosObjectMapper.create();
    ObjectNode root;
    try {
      JsonNode parsed = mapper.readTree(economy.economyPayload());
      if (!(parsed instanceof ObjectNode objectNode)) {
        throw new IllegalStateException("economy.Seed 载荷顶层不是对象");
      }
      root = objectNode;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("economy.Seed 载荷不是合法 JSON", e);
    }

    JsonNode rawGovernments = root.get("governments");
    if (!(rawGovernments instanceof ArrayNode governments) || governments.size() != 1) {
      throw new IllegalStateException(
          "32hex 世界需要恰好一个政府记录，实得 "
              + (rawGovernments instanceof ArrayNode array ? array.size() : "非数组"));
    }
    JsonNode rawGovernment = governments.get(0);
    if (!(rawGovernment instanceof ObjectNode government)
        || !EconomySeeder.GENESIS_GOVERNMENT_ID.value().equals(government.path("id").asText())) {
      throw new IllegalStateException("32hex 世界的政府记录不是 GENESIS_GOVERNMENT_ID: " + rawGovernment);
    }
    government.put("id", LOCAL_GOVERNMENT_ID);
    government.put("nationRef", LOCAL_GOVERNMENT_NATION_REF);

    JsonNode rawIssuances = root.get("moneyIssuances");
    if (!(rawIssuances instanceof ArrayNode issuances)) {
      throw new IllegalStateException("economy.Seed 载荷缺 moneyIssuances 数组");
    }
    for (JsonNode issuanceNode : issuances) {
      if (issuanceNode instanceof ObjectNode issuance
          && EconomySeeder.GENESIS_GOVERNMENT_ID
              .value()
              .equals(issuance.path("governmentId").asText())) {
        issuance.put("governmentId", LOCAL_GOVERNMENT_ID);
      }
    }
    return root.toString();
  }

  /**
   * {@code social.SetPopulation} 载荷：{@code {entries:[{q,r,population}…]}}，按 {@link HexCoord} 排序。
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

  /** {@code social.CreateCity} 最小载荷（{@code id/name/at/region}）；人口由 SeedGroups 批次派生。 */
  private static String createCityPayload(RegionId region, PlannedCity city) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", city.id());
    payload.put("name", city.name());
    payload.put("at", ToolSupport.hexCoord(city.at()));
    payload.put("region", region.value());
    return ToolSupport.json(payload);
  }
}
