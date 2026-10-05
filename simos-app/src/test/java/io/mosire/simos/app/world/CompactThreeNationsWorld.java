package io.mosire.simos.app.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.app.tools.write.WorldgenInitializeTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.gen.WorldgenConfig;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * ★★ **紧凑三国测试世界**（test scope，2026-09-28）：3 个互不重叠的 48 格 {@link Region} + 14 格海洋 = 158 格地图，
 * 由本类**程序化、确定性地**构造（不手写 JSON、不复制 59223 格的 {@code v17levant} 真档）。
 *
 * <p>★★ <b>为什么要它</b>：真档是 799 格 / 1183 万人的复刻数据集，跑世界/经济端到端测试太慢；而小夹具（{@code CorridorWorld} / {@code
 * EconomyTestWorld}）又只有一两格、"人口与需求一格一组"，看不出市场、城市、阶层、产业之间的相互作用。 本夹具取中间形态：地图够小（秒级推进 120 天）、数据够厚（3 国 /
 * 13 城 / 144 个市场 / 5 种商品 / 600+ 阶层行）。
 *
 * <p>★★ <b>真实路径</b>：本类只造<b>地图与空切片</b>；人口 / 城市 / 经济 / 家户 actor 一律由真工具 {@link
 * WorldgenInitializeTool}（{@code simos.worldgen.initialize}）读 {@code
 * simos-app/src/test/resources/worldgen/test-three-nations.json} 播种 —— 不另写第二套播种逻辑。{@link
 * #initializeNations(CoreSimos)} 就是把这条路径包一层的辅助方法。
 *
 * <p>★ <b>确定性</b>：固定 region 中心、固定地形带序、固定 seed、{@code randomization.enabled=false} ⇒ 同一份调用逐字段产出同一状态。
 *
 * <p>★ <b>地形</b>：平原 / 低丘 / 山地 / 沙漠 / 海洋 5 种；agricultural region 的 r=0 一行铺一条 9 格干流（河边 ≥2 的格吃 {@code
 * navigable=1.5} 加成）；commercial region 西侧贴 14 格海洋（coastal=true）。故 {@code TerrainView} 的 river /
 * coastal 两条查询都真的有数据。
 */
public final class CompactThreeNationsWorld {

  /** 本夹具的世界 id（与其它夹具不复用；进 economy 的 {@code EconomyMeta.mapId}）。 */
  public static final String MAP_ID = "compact-three-nations";

  /** 农业国 regionId（= nationId，worldgen 的既定口径）。 */
  public static final RegionId GRANARY = new RegionId("粮仓平原国");

  /** 商业/手工业国 regionId。 */
  public static final RegionId WEAVING_PORT = new RegionId("织港商邦");

  /** 山地/混合国 regionId。 */
  public static final RegionId IRON_RIDGE = new RegionId("铁岭山地国");

  /** 三国（JSON {@code nations} 的顺序，也是初始化顺序）。 */
  public static final List<RegionId> NATION_REGIONS = List.of(GRANARY, WEAVING_PORT, IRON_RIDGE);

  /** 每国格数。 */
  public static final int HEXES_PER_REGION = 48;

  /** 陆地格数 = 3 × 48。 */
  public static final int LAND_HEXES = HEXES_PER_REGION * 3;

  /** 海洋格数（商业国的海岸线；地图总格数 = 陆地 + 海洋）。 */
  public static final int OCEAN_HEXES = 14;

  /** 地图总格数。 */
  public static final int MAP_HEXES = LAND_HEXES + OCEAN_HEXES;

  /** 三国人口合计（冻结输入硬值 150000 + 120000 + 110000）。 */
  public static final long TOTAL_POPULATION = 380_000L;

  /** 农业国人口。 */
  public static final long GRANARY_POPULATION = 150_000L;

  /** 商业国人口。 */
  public static final long WEAVING_PORT_POPULATION = 120_000L;

  /** 山地国人口。 */
  public static final long IRON_RIDGE_POPULATION = 110_000L;

  /** 农业国城市化人口 = round(150000 × 0.15)。 */
  public static final long GRANARY_URBAN_POPULATION = 22_500L;

  /** 商业国城市化人口 = round(120000 × 0.40)。 */
  public static final long WEAVING_PORT_URBAN_POPULATION = 48_000L;

  /** 山地国城市化人口 = round(110000 × 0.25)。 */
  public static final long IRON_RIDGE_URBAN_POPULATION = 27_500L;

  /** 三个 region 的几何中心（两两距离 ≥ 10，48 格 blob 互不重叠）。 */
  private static final HexCoord GRANARY_CENTER = new HexCoord(0, 0);

  private static final HexCoord WEAVING_PORT_CENTER = new HexCoord(16, 0);
  private static final HexCoord IRON_RIDGE_CENTER = new HexCoord(8, 10);

  /** 半径 4 的六边形盘有 61 格，取前 48 格（按 距离 → q → r 排序）⇒ 紧凑、无缝、可复现。 */
  private static final int BLOB_RADIUS = 4;

  /** 地图生成 seed（只进 {@code GenerationSpec}；本夹具不跑地图随机化）。 */
  private static final long MAP_SEED = 7100L;

  /** 初始化工具落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）。 */
  private static final String INITIATOR = "agent:compact-three-nations";

  private static final BranchId MAIN = new BranchId("main");

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  /**
   * ★★ 测试条件的目标国（粮仓平原国）—— 旧 P3 的债务/拆分/质押条件只作用于该国。★ R3b：那两个条件夹具 （{@code typicalConditions}/{@code
   * stressConditions}）已随旧结算运行时一并删除（无调用方，且 class-first 入口会具名拒绝带旧资产规则的 payload）；本常量保留，因为 {@link
   * #initializeNations(CoreSimos, TestConditions, RegionId)} 的"条件只作用于哪一国"语义仍需要它。
   */
  public static final RegionId TYPICAL_CONDITIONS_NATION = GRANARY;

  private CompactThreeNationsWorld() {}

  // ── 创世状态 ────────────────────────────────────────────────────────────────────────

  /**
   * 组装本夹具的创世状态：坐标固定 {@code (main, 1)}、时刻 tick 0，含 map 与其余五片<b>空</b>切片 （social / unit / sd / economy
   * / actor）—— 后五片由 worldgen 的真命令批填满。
   *
   * <p>★ 六片都在场是<b>硬要求</b>：命令总线 / 时间参与者在切片缺席时响亮失败（照 {@code CorridorWorld} 的先例）。
   *
   * @param mapId 本世界的 map 称谓（非空白）；与 {@link #MAP_ID} 同值即可
   */
  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    if (mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白: " + mapId);
    }
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    SimosTimestamp t0 = SimosTimestamp.of(0L);
    return new SimulationState(
        new StateMeta(ref, t0),
        Map.of(
            "map", new MapSnapshot(ref, t0, map()),
            "social", new SocialSnapshot(ref, t0, new SocialData(Map.of(), Map.of(), Map.of())),
            "unit", new UnitSnapshot(ref, t0, UnitState.empty()),
            "sd", new SdSnapshot(ref, t0, SdState.empty()),
            "economy", new EconomySnapshot(ref, t0, EconomyData.empty()),
            "actor", new ActorSnapshot(ref, t0, ActorData.empty())),
        InMemoryInfoSystem.empty());
  }

  // ── 地图构造 ────────────────────────────────────────────────────────────────────────

  /** 3 个 48 格 region + 14 格海洋的确定性地图。 */
  public static GameMap map() {
    Set<HexCoord> granary = blob(GRANARY_CENTER);
    Set<HexCoord> weavingPort = blob(WEAVING_PORT_CENTER);
    Set<HexCoord> ironRidge = blob(IRON_RIDGE_CENTER);
    assertDisjoint(granary, weavingPort, ironRidge);

    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>();
    assignBandedTerrain(
        terrainByHex,
        orderByDistance(granary, GRANARY_CENTER),
        new Band("plains", 32),
        new Band("low_hills", 12),
        new Band("desert", 4));
    assignBandedTerrain(
        terrainByHex,
        orderByDistance(weavingPort, WEAVING_PORT_CENTER),
        new Band("plains", 10),
        new Band("low_hills", 16),
        new Band("mountains", 12),
        new Band("desert", 10));
    assignBandedTerrain(
        terrainByHex,
        orderByDistance(ironRidge, IRON_RIDGE_CENTER),
        new Band("plains", 1),
        new Band("low_hills", 4),
        new Band("mountains", 20),
        new Band("desert", 23));

    // ★ 海洋：商业国 blob 的边界外邻格（按 (q,r) 升序取前 14）⇒ 商业国真的 coastal，且不与任何 region 重叠。
    TreeSet<HexCoord> coastCandidates = new TreeSet<>();
    for (HexCoord hex : weavingPort) {
      for (HexCoord neighbor : hex.neighbors()) {
        if (!granary.contains(neighbor)
            && !weavingPort.contains(neighbor)
            && !ironRidge.contains(neighbor)) {
          coastCandidates.add(neighbor);
        }
      }
    }
    if (coastCandidates.size() < OCEAN_HEXES) {
      throw new IllegalStateException(
          "海洋候选格不足：需要 " + OCEAN_HEXES + "，只有 " + coastCandidates.size());
    }
    List<HexCoord> ocean = new ArrayList<>(coastCandidates).subList(0, OCEAN_HEXES);
    for (HexCoord hex : ocean) {
      terrainByHex.put(hex, "ocean");
    }

    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, String> entry : terrainByHex.entrySet()) {
      hexes.put(entry.getKey(), new HexCell(heightOf(entry.getValue())));
    }

    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        GRANARY,
        Region.of(
            GRANARY,
            "粮仓平原国",
            granary,
            new RegionMeta("#8fbf6a", "Nation", "紧凑三国测试世界：沿河农业国（48 格）", null)));
    regions.put(
        WEAVING_PORT,
        Region.of(
            WEAVING_PORT,
            "织港商邦",
            weavingPort,
            new RegionMeta("#5fa8c9", "Nation", "紧凑三国测试世界：沿海商业/手工业国（48 格）", null)));
    regions.put(
        IRON_RIDGE,
        Region.of(
            IRON_RIDGE,
            "铁岭山地国",
            ironRidge,
            new RegionMeta("#9a8b7a", "Nation", "紧凑三国测试世界：山地/混合国（48 格）", null)));

    return new GameMap(
        hexes,
        TerrainBlocks.split(terrainByHex),
        regions,
        Map.of(),
        TerrainCatalog.defaults(),
        Map.of(),
        PathwayGroup.defaults(),
        granaryRiverEdges(granary),
        GenerationSpec.defaults(MAP_SEED));
  }

  /**
   * 半径 {@link #BLOB_RADIUS} 的六边形盘里的前 {@link #HEXES_PER_REGION} 格（按 距离 → q → r 排序）⇒ 圆形紧凑 blob，边界
   * 平滑、无洞、无随机。
   */
  private static Set<HexCoord> blob(HexCoord center) {
    List<HexCoord> disk = new ArrayList<>();
    int radius = BLOB_RADIUS;
    for (int q = center.q() - radius; q <= center.q() + radius; q++) {
      for (int r = center.r() - radius; r <= center.r() + radius; r++) {
        HexCoord hex = new HexCoord(q, r);
        if (hex.distanceTo(center) <= radius) {
          disk.add(hex);
        }
      }
    }
    List<HexCoord> ordered = orderByDistance(disk, center);
    if (ordered.size() < HEXES_PER_REGION) {
      throw new IllegalStateException("半径 " + radius + " 的盘只有 " + ordered.size() + " 格");
    }
    return Set.copyOf(ordered.subList(0, HEXES_PER_REGION));
  }

  /** 按 距离(center) → q → r 排序（内容的纯函数，不依赖集合迭代序）。 */
  private static List<HexCoord> orderByDistance(Set<HexCoord> hexes, HexCoord center) {
    return orderByDistance(new ArrayList<>(hexes), center);
  }

  private static List<HexCoord> orderByDistance(List<HexCoord> hexes, HexCoord center) {
    List<HexCoord> ordered = new ArrayList<>(hexes);
    ordered.sort(
        Comparator.<HexCoord>comparingInt(hex -> hex.distanceTo(center))
            .thenComparingInt(HexCoord::q)
            .thenComparingInt(HexCoord::r));
    return ordered;
  }

  /** 地形带：按已排序的格序铺连续带（平原在中心、沙漠/山地在边缘）。 */
  private record Band(String terrainKey, int count) {}

  private static void assignBandedTerrain(
      Map<HexCoord, String> out, List<HexCoord> ordered, Band... bands) {
    int index = 0;
    for (Band band : bands) {
      for (int i = 0; i < band.count(); i++) {
        out.put(ordered.get(index++), band.terrainKey());
      }
    }
    if (index != ordered.size()) {
      throw new IllegalStateException(
          "地形带合计 " + index + " ≠ region 格数 " + ordered.size() + "（夹具自检）");
    }
  }

  /**
   * 农业国 r=0 一行上的干流边：取该行<b>最靠西的 5 格</b>连成 4 条边（中间 3 格各触到 2 条 river 边 ⇒ {@code
   * TerrainView.riverEdgesAt} 走 navigable 档 1.5；两端各 1 条）。
   *
   * <p>★ 这里只写 {@code map.edges}（河边标注的<b>唯一主存储</b>）；{@code GameMapTerrainView} 判 river 只读这张表，不需要
   * {@code Pathway} 实例。
   */
  private static Map<EdgeRef, EdgeTags> granaryRiverEdges(Set<HexCoord> granary) {
    List<HexCoord> row =
        granary.stream()
            .filter(hex -> hex.r() == 0)
            .sorted(Comparator.naturalOrder())
            .limit(5)
            .toList();
    EdgeTags riverTags = new EdgeTags(Map.of("river", Map.<String, Object>of("kind", "river")));
    Map<EdgeRef, EdgeTags> edges = new LinkedHashMap<>();
    for (int i = 0; i + 1 < row.size(); i++) {
      edges.put(new EdgeRef(row.get(i), row.get(i + 1)), riverTags);
    }
    return Collections.unmodifiableMap(edges);
  }

  /** 地形 → 一个落进 [0,1] 的合理海拔（{@code TerrainView} 不读海拔，只保证 HexCell 合法）。 */
  private static double heightOf(String terrain) {
    return switch (terrain) {
      case "ocean" -> 0.15;
      case "plains" -> 0.35;
      case "desert" -> 0.50;
      case "low_hills" -> 0.62;
      case "mountains" -> 0.72;
      default -> 0.45;
    };
  }

  private static void assertDisjoint(
      Set<HexCoord> granary, Set<HexCoord> weavingPort, Set<HexCoord> ironRidge) {
    for (HexCoord hex : granary) {
      if (weavingPort.contains(hex) || ironRidge.contains(hex)) {
        throw new IllegalStateException("region 重叠于 " + hex + "（夹具自检）");
      }
    }
    for (HexCoord hex : weavingPort) {
      if (ironRidge.contains(hex)) {
        throw new IllegalStateException("region 重叠于 " + hex + "（夹具自检）");
      }
    }
  }

  // ── 配置加载与三国初始化（真 WorldgenInitializeTool 路径）────────────────────────────

  /** 冻结输入 JSON 的路径（test resources；先找文件系统、再回退 classpath）。 */
  public static Path configFile() {
    for (Path candidate :
        List.of(
            Path.of("src", "test", "resources", "worldgen", "test-three-nations.json"),
            Path.of(
                "simos-app", "src", "test", "resources", "worldgen", "test-three-nations.json"))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    URL resource = CompactThreeNationsWorld.class.getResource("/worldgen/test-three-nations.json");
    if (resource != null && "file".equals(resource.getProtocol())) {
      try {
        return Path.of(resource.toURI());
      } catch (URISyntaxException e) {
        throw new IllegalStateException("测试 resource 路径无法转成 Path: " + resource, e);
      }
    }
    throw new IllegalStateException(
        "找不到 test-three-nations.json（工作目录=" + Path.of(".").toAbsolutePath() + "）");
  }

  /** 解析本世界的冻结输入（与 worldgen 工具走同一个 {@link WorldgenConfig#load(Path)}）。 */
  public static WorldgenConfig config() {
    return WorldgenConfig.load(configFile());
  }

  /**
   * ★★ 用真工具 {@code simos.worldgen.initialize} 依次初始化三国（{@code dryRun=false, army=true,
   * economyProfile=class-first}），返回三国摘要 JSON。
   *
   * <p>★ 顺序 = {@link #NATION_REGIONS} 的顺序；每次调用落一条 revision。任一失败即抛（不把失败折叠成"跳过"）。 ★ R3a 起 economy 只有
   * class-first 一条生产路径；旧 P1/P3 的 COMPLETE 条件（债务/质押/资产拆分）随旧结算运行时一并退役， 带条件的 worldgen 调用会被 {@code
   * applyTestConditions} 具名拒绝。
   */
  public static List<JsonNode> initializeNations(CoreSimos core) throws IOException {
    return initializeNations(core, TestConditions.EMPTY, TYPICAL_CONDITIONS_NATION);
  }

  /**
   * ★★ <b>P3：带测试条件初始化三国</b>（条件只交给 {@code conditionsNation} 那一国的 worldgen 调用；其余两国走 P1 的无条件路径）。条件经
   * {@link TestConditions#toJson()} 变成真工具参数 {@code economyTestConditions} ⇒ 与"外部 GM 传
   * JSON"走**同一条**解析/校验/应用路径，夹具不做旁路。
   *
   * <p>★ 条件里的家户/份额必须落在 {@code conditionsNation} 的格集内（跨国引用没有对侧，播种器 fail-closed）。
   */
  public static List<JsonNode> initializeNations(
      CoreSimos core, TestConditions conditions, RegionId conditionsNation) throws IOException {
    return initializeNations(
        core,
        conditions,
        conditionsNation,
        NATION_REGIONS,
        EconomySeeder.FoundationProfile.PRODUCTION_RUNTIME); // P0.1：class-first 已删，唯一路线是 production-runtime
  }

  /**
   * ★★ <b>R2a：用 profile 初始化单个 region</b>（唯一值 = {@code PRODUCTION_RUNTIME}）。
   */
  public static JsonNode initializeNation(
      CoreSimos core, RegionId region, EconomySeeder.FoundationProfile profile) throws IOException {
    return initializeNations(
            core, TestConditions.EMPTY, TYPICAL_CONDITIONS_NATION, List.of(region), profile)
        .get(0);
  }

  /**
   * ★★ <b>R2c：用同一 profile 依次初始化三国</b>（三国 class-first 验收入口）——每次调用落一条 revision； 三国的 region
   * 格集互不相同，economy.Seed 全部成功。
   */
  public static List<JsonNode> initializeNations(
      CoreSimos core, EconomySeeder.FoundationProfile profile) throws IOException {
    return initializeNations(
        core, TestConditions.EMPTY, TYPICAL_CONDITIONS_NATION, NATION_REGIONS, profile);
  }

  /** 内部主循环：regions + profile 都可注入；旧入口在上面逐字保持（COMPLETE + 三国）。 */
  private static List<JsonNode> initializeNations(
      CoreSimos core,
      TestConditions conditions,
      RegionId conditionsNation,
      List<RegionId> regions,
      EconomySeeder.FoundationProfile profile)
      throws IOException {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(profile, "profile");
    TestConditions effective = conditions == null ? TestConditions.EMPTY : conditions;
    lastConditionReport = TestConditions.Report.EMPTY;
    AgentTool tool = new WorldgenInitializeTool(core, INITIATOR, MAP_ID, configFile());
    List<JsonNode> summaries = new ArrayList<>(regions.size());
    for (RegionId region : regions) {
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("nation", region.value());
      args.put("dryRun", false);
      args.put("economyProfile", profile.wireName());
      if (!effective.isEmpty() && region.equals(conditionsNation)) {
        args.put("economyTestConditions", effective.toJson());
      }
      ToolResult result = tool.execute(context(tool, args));
      if (!result.success()) {
        throw new IllegalStateException(
            "worldgen 初始化 " + region.value() + " 失败: " + result.code() + " " + result.message());
      }
      summaries.add(JSON.readTree(result.message()));
    }
    // ★ 全部批次都成功后才把条件报告挂上（诊断读数用；apply 失败会抛在上面 ⇒ 不会留下"报成功其实没应用"的读数）。
    lastConditionReport = effective.report();
    return List.copyOf(summaries);
  }

  /** ★ P3：条件 JSON 文本入口（解析失败抛具名 {@code IllegalArgumentException}）。 */
  public static List<JsonNode> initializeNations(CoreSimos core, String conditionsJson)
      throws IOException {
    return initializeNations(
        core,
        conditionsJson == null ? TestConditions.EMPTY : TestConditions.parseJson(conditionsJson));
  }

  /** ★ P3：条件对象入口（默认作用于 {@link #TYPICAL_CONDITIONS_NATION}）。 */
  public static List<JsonNode> initializeNations(CoreSimos core, TestConditions conditions)
      throws IOException {
    return initializeNations(core, conditions, TYPICAL_CONDITIONS_NATION);
  }

  /** 最近一次 {@link #initializeNations(CoreSimos, TestConditions, RegionId)} 的条件报告（诊断用）。 */
  public static TestConditions.Report lastConditionReport() {
    return lastConditionReport;
  }

  private static volatile TestConditions.Report lastConditionReport = TestConditions.Report.EMPTY;

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }

  // ── 可读读数 ────────────────────────────────────────────────────────────────────────

  /**
   * 把当前状态里测试关心的数一次算出来（LinkedHashMap ⇒ 打印顺序稳定）：国家 / 地图 / 城市 / 人口 / 阶层行 / 产业 / 市场 / 商品 / 债务 /
   * 单位，以及<b>本周期</b>口径的流水合计（消费 / 所得 / 缺口 / 借入 / 偿还 / 出生 / 死亡）。
   *
   * <p>★★ 流水字段是"本期发生额"（新周期第一天归零，见 {@link FlowRow}）⇒ 这个辅助方法只做求和，<b>不</b>替调用方解释窗口；
   * 关账日读到的才是整周期量（见测试里的注释）。
   */
  public static Map<String, Object> readings(SimulationState state) {
    GameMap map = mapOf(state);
    SocialData social = socialOf(state);
    EconomyData economy = economyOf(state);
    ActorData books = actorOf(state);
    long rural = 0L;
    long urban = 0L;
    for (PopulationGroup group : social.groups().values()) {
      if (PopulationLots.isUrban(group)) {
        urban += group.count();
      } else {
        rural += group.count();
      }
    }
    Set<String> commodities = new TreeSet<>();
    for (Market market : economy.markets().values()) {
      for (CommodityId commodity : market.prices().keySet()) {
        commodities.add(commodity.value());
      }
    }
    long consumed = 0L;
    long income = 0L;
    long unmet = 0L;
    long newBorrowing = 0L;
    long repaid = 0L;
    long births = 0L;
    long deaths = 0L;
    long capitalizedArrears = 0L;
    long capitalizedArrearsRows = 0L;
    for (FlowRow flow : economy.flows().values()) {
      consumed += sum(flow.consumed());
      income += sum(flow.income());
      unmet += sum(flow.unmetNeed());
      newBorrowing += flow.newBorrowing();
      repaid += flow.repaid();
      births += flow.births();
      deaths += flow.deaths();
      long capitalized =
          flow.capitalizedArrears().values().stream().mapToLong(Long::longValue).sum();
      capitalizedArrears += capitalized;
      if (capitalized > 0L) {
        capitalizedArrearsRows++;
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mapHexes", (long) map.hexes().size());
    out.put("landHexes", map.hexes().size() - oceanHexCount(map));
    out.put("regions", (long) map.regions().size());
    out.put("nations", (long) sdOf(state).nations().size());
    out.put("cities", (long) social.cities().size());
    out.put("populationGroups", (long) social.groups().size());
    out.put("ruralPopulation", rural);
    out.put("urbanPopulation", urban);
    out.put("totalPopulation", rural + urban);
    out.put("classRows", (long) economy.classes().size());
    out.put("flowRows", (long) economy.flows().size());
    out.put("industries", (long) economy.industries().size());
    out.put("productionUnits", (long) economy.units().size());
    // ★ P1 地基/自动组织读数（不参与旧断言；只把"完整世界真的种下了什么"打印出来）。
    out.put("modes", (long) economy.modes().size());
    out.put("classStructures", (long) economy.classStructures().size());
    out.put("classPositions", (long) economy.classPositions().size());
    out.put("classStandings", (long) economy.classStandings().size());
    out.put("assetRules", (long) economy.assetRules().size());
    out.put("liquidationPolicies", (long) economy.liquidationPolicies().size());
    out.put("productionOrganizations", (long) economy.productionOrganizations().size());
    out.put(
        "orgActive",
        economy.productionOrganizations().values().stream()
            .filter(org -> "ACTIVE".equals(org.status().name()))
            .count());
    out.put(
        "orgShortage",
        economy.productionOrganizations().values().stream()
            .filter(org -> "SHORTAGE".equals(org.status().name()))
            .count());
    out.put(
        "orgWithUnit",
        economy.productionOrganizations().values().stream()
            .filter(org -> org.unitId().isPresent())
            .count());
    out.put("markets", (long) economy.markets().size());
    out.put("commodities", (long) commodities.size());
    out.put("commodityIds", List.copyOf(commodities));
    out.put("debts", (long) economy.debtContracts().size());
    // ★★ P3：初始条件相关读数（不改旧键语义）——
    //   · pledges/assetShares/initialDebts 直接来自 economy 状态（真实表）；
    //   · condition* 来自本夹具**最近一次** initializeNations 传入的条件报告（诊断口径，见 lastConditionReport）。
    out.put("pledges", (long) economy.pledges().size());
    out.put("assetShares", (long) economy.assetShares().size());
    long initialDebts = 0L;
    for (DebtContract debt : economy.debtContracts().values()) {
      if (debt.openedDay() == 0L) {
        initialDebts++;
      }
    }
    out.put("initialDebts", initialDebts);
    TestConditions.Report report = lastConditionReport;
    out.put("conditionDebtContracts", (long) report.debtContractCount());
    out.put("conditionPledges", (long) report.pledgeCount());
    out.put("conditionSplitShares", (long) report.splitShareCount());
    out.put("conditionInjectedGoods", sumValues(report.injectedGoods()));
    out.put("conditionInjectedMoney", sumValues(report.injectedMoney()));
    out.put("shipments", (long) economy.shipments().size());
    out.put("units", (long) unitOf(state).units().size());
    out.put("commandChains", (long) unitOf(state).commandChains().size());
    out.put("armies", (long) sdOf(state).armies().size());
    out.put("lastClosedCycle", lastClosedCycleOf(economy));
    out.put("cycleConsumed", consumed);
    out.put("cycleIncome", income);
    out.put("cycleUnmetNeed", unmet);
    out.put("cycleNewBorrowing", newBorrowing);
    out.put("cycleRepaid", repaid);
    out.put("cycleCapitalizedArrears", capitalizedArrears);
    out.put("cycleCapitalizedArrearsRows", capitalizedArrearsRows);
    out.put("cycleBirths", births);
    out.put("cycleDeaths", deaths);
    out.put("grainAccountMilli", grainAccountMilli(books));
    out.put("grainInTransitMilli", grainInTransitMilli(economy));
    out.put("moneyTotalMilli", moneyTotalMilli(books));
    return Collections.unmodifiableMap(out);
  }

  /** 未关账过 ⇒ -1（不是 0：0 是"第 0 周期已关"这种不存在的状态）。 */
  public static long lastClosedCycleOf(EconomyData economy) {
    return economy
        .meta()
        .map(meta -> meta.lastClosedCycle().isPresent() ? meta.lastClosedCycle().getAsLong() : -1L)
        .orElse(-1L);
  }

  private static long sum(Map<CommodityId, Long> values) {
    return sumValues(values);
  }

  /** 任意键的 long 值表求和（条件报告的两个注入表共用一处）。 */
  private static long sumValues(Map<?, Long> values) {
    long total = 0L;
    for (long value : values.values()) {
      total += value;
    }
    return total;
  }

  private static long oceanHexCount(GameMap map) {
    long ocean = 0L;
    for (Map.Entry<HexCoord, String> entry : map.terrainIndex().entrySet()) {
      if ("ocean".equals(entry.getValue())) {
        ocean++;
      }
    }
    return ocean;
  }

  /** 全部 actor 商品账上粮的余额合计（毫粮）。 */
  public static long grainAccountMilli(ActorData books) {
    CommodityId grain = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
    long total = 0L;
    for (GoodsAccount account : books.accounts().values()) {
      total += account.balances().getOrDefault(grain, 0L);
    }
    return total;
  }

  /** 在途粮（{@code EconomyData.shipments} 里未到货的批次数量，毫粮）。 */
  public static long grainInTransitMilli(EconomyData economy) {
    CommodityId grain = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
    long total = 0L;
    for (var batch : economy.shipments().values()) {
      if (batch.commodity().equals(grain)) {
        total += batch.quantity();
      }
    }
    return total;
  }

  /** 全部 actor 货币账的余额合计（最小币值）。 */
  public static long moneyTotalMilli(ActorData books) {
    long total = 0L;
    for (GoodsAccount account : books.accounts().values()) {
      for (long value : account.money().values()) {
        total += value;
      }
    }
    return total;
  }

  // ── 切片读取（缺片 = 装配故障，响亮抛）────────────────────────────────────────────────

  public static GameMap mapOf(SimulationState state) {
    return ((MapSnapshot) module(state, "map")).map();
  }

  public static SocialData socialOf(SimulationState state) {
    return ((SocialSnapshot) module(state, "social")).data();
  }

  public static EconomyData economyOf(SimulationState state) {
    return ((EconomySnapshot) module(state, "economy")).data();
  }

  public static ActorData actorOf(SimulationState state) {
    return ((ActorSnapshot) module(state, "actor")).data();
  }

  public static SdState sdOf(SimulationState state) {
    return ((SdSnapshot) module(state, "sd")).state();
  }

  public static UnitState unitOf(SimulationState state) {
    return ((UnitSnapshot) module(state, "unit")).state();
  }

  private static Snapshot module(SimulationState state, String namespace) {
    return state
        .module(namespace)
        .orElseThrow(() -> new IllegalStateException("状态里没有 " + namespace + " 切片（装配故障）"));
  }

  /**
   * 各国读数（按 {@link #NATION_REGIONS} 顺序；键 = regionId）：人口 / 阶层行 / 城市 / 市场 / 产业 / <b>本期</b>消费、所得、 未满足需求
   * / 债务。归属 = 该行所在格落在哪个 region（格集是 region 的内容，不是名字）。
   *
   * <p>★ 与 {@link #readings(SimulationState)} 同一窗口纪律：流水是"本期发生额"，关账日读到的是整周期量。
   */
  public static Map<String, Map<String, Object>> nationReadings(SimulationState state) {
    EconomyData economy = economyOf(state);
    SocialData social = socialOf(state);
    Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    for (RegionId region : NATION_REGIONS) {
      Region area = mapOf(state).regions().get(region);
      if (area == null) {
        throw new IllegalStateException("地图里没有 region " + region.value());
      }
      long population = 0L;
      long classRows = 0L;
      for (ClassRow row : economy.classes().values()) {
        if (area.contains(row.view().hex())) {
          population += row.population();
          classRows++;
        }
      }
      long cities =
          social.cities().values().stream()
              .filter(city -> city.region().filter(region::equals).isPresent())
              .count();
      long markets = economy.markets().keySet().stream().filter(area::contains).count();
      long industries = 0L;
      for (IndustryId id : economy.industries().keySet()) {
        Optional<String> hexKey = IndustryHexKeys.hexKeyOf(id);
        if (hexKey.isPresent() && area.contains(HexCoord.parse(hexKey.get()))) {
          industries++;
        }
      }
      long consumed = 0L;
      long income = 0L;
      long unmet = 0L;
      for (FlowRow flow : economy.flows().values()) {
        ClassRow row = economy.classes().get(flow.id());
        if (row == null || !area.contains(row.view().hex())) {
          continue;
        }
        consumed += sum(flow.consumed());
        income += sum(flow.income());
        unmet += sum(flow.unmetNeed());
      }
      long debts = 0L;
      for (DebtContract debt : economy.debtContracts().values()) {
        ClassRow row = economy.classes().get(debt.debtor());
        if (row != null && area.contains(row.view().hex())) {
          debts++;
        }
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("population", population);
      row.put("classRows", classRows);
      row.put("cities", cities);
      row.put("markets", markets);
      row.put("industries", industries);
      row.put("cycleConsumed", consumed);
      row.put("cycleIncome", income);
      row.put("cycleUnmetNeed", unmet);
      row.put("debts", debts);
      out.put(region.value(), Collections.unmodifiableMap(row));
    }
    return Collections.unmodifiableMap(out);
  }
}
