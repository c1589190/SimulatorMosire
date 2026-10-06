package io.mosire.simos.app.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
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
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ P11.6 测试夹具：真实 12 格小地图。
 *
 * <p>地图由本类程序化构造：1 个 12 格 Region（11 格陆地 + 1 格海洋；一条 4 格干流），不复制 {@link CompactThreeNationsWorld} 的 48
 * 格整块，也不手搭 EconomyData。
 *
 * <p>人口 / 城市 / 经济 / 家户 actor 一律走真工具 {@code simos.worldgen.initialize}（production-runtime）， 其配置由本类从
 * {@code test-three-nations.json} 改出单国版本后写进临时文件，不另写第二套播种逻辑。
 */
public final class RealTwelveHexWorld {

  /** 本夹具的世界 id。 */
  public static final String MAP_ID = "real-twelve";

  /** 12 格 Region 的 id：worldgen 配置里的 regionId 必须与它逐字一致。 */
  public static final RegionId REGION = new RegionId("real12");

  /** 全国人口（1,000–5,000 量级）。 */
  public static final long TOTAL_POPULATION = 3_500L;

  /** 城市化率。 */
  public static final double URBANIZATION_RATE = 0.25;

  /** 地图总格数：用户要求 7/十几格，取 12。 */
  public static final int MAP_HEXES = 12;

  /** 海洋格数（允许保留 1–3 格海洋/不可通行）。 */
  public static final int OCEAN_HEXES = 1;

  /** 陆地格数。 */
  public static final int LAND_HEXES = MAP_HEXES - OCEAN_HEXES;

  private static final BranchId MAIN = new BranchId("main");

  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  /** 12 格地图的规范格序（(q,r) 字典序）。 */
  private static final List<HexCoord> HEXES =
      List.of(
          new HexCoord(0, 0),
          new HexCoord(1, 0),
          new HexCoord(2, 0),
          new HexCoord(3, 0),
          new HexCoord(0, 1),
          new HexCoord(1, 1),
          new HexCoord(2, 1),
          new HexCoord(3, 1),
          new HexCoord(0, 2),
          new HexCoord(1, 2),
          new HexCoord(2, 2),
          new HexCoord(3, 2));

  /** 海洋格：地图右下角，只与 (2,2)/(3,1) 相邻 ⇒ 这两格真的 coastal。 */
  private static final HexCoord OCEAN = new HexCoord(3, 2);

  private static final long MAP_SEED = 1212L;

  private RealTwelveHexWorld() {}

  // ── 地图 ────────────────────────────────────────────────────────────────────────────

  /** 12 格真实 GameMap：8 平原 + 3 低丘 + 1 海洋；一条 4 格干流沿 r=0。 */
  public static GameMap map() {
    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>();
    for (HexCoord hex : HEXES) {
      terrainByHex.put(hex, terrainOf(hex));
    }
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, String> entry : terrainByHex.entrySet()) {
      hexes.put(entry.getKey(), new HexCell(heightOf(entry.getValue())));
    }

    Region region =
        Region.of(
            REGION,
            "真实十二格",
            Set.copyOf(HEXES),
            new RegionMeta("#b58b5a", "Nation", "P11.6 测试：12 格真实地图（11 陆 + 1 海）", null));

    Map<EdgeRef, EdgeTags> edges = new LinkedHashMap<>();
    EdgeTags riverTags = new EdgeTags(Map.of("river", Map.<String, Object>of("kind", "river")));
    for (int q = 0; q < 3; q++) {
      edges.put(new EdgeRef(new HexCoord(q, 0), new HexCoord(q + 1, 0)), riverTags);
    }

    return new GameMap(
        hexes,
        TerrainBlocks.split(terrainByHex),
        Map.of(REGION, region),
        Map.of(),
        TerrainCatalog.defaults(),
        Map.of(),
        PathwayGroup.defaults(),
        Collections.unmodifiableMap(edges),
        GenerationSpec.defaults(MAP_SEED));
  }

  /** 具体地形：保证 11 格有承载力，且至少 2 格沿海、2 格触到 ≥2 条 river 边。 */
  private static String terrainOf(HexCoord hex) {
    if (hex.equals(OCEAN)) {
      return "ocean";
    }
    if (hex.equals(new HexCoord(1, 1))
        || hex.equals(new HexCoord(2, 1))
        || hex.equals(new HexCoord(1, 2))) {
      return "low_hills";
    }
    return "plains";
  }

  private static double heightOf(String terrain) {
    return switch (terrain) {
      case "ocean" -> 0.15;
      case "low_hills" -> 0.62;
      default -> 0.35;
    };
  }

  // ── 创世状态 ─────────────────────────────────────────────────────────────────────────

  /** 六片齐全的创世空状态（map 有内容，social/unit/sd/economy/actor 为空）。 */
  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    if (mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白");
    }
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T0),
        Map.of(
            "map", new MapSnapshot(ref, T0, map()),
            "social", new SocialSnapshot(ref, T0, new SocialData(Map.of(), Map.of(), Map.of())),
            "unit", new UnitSnapshot(ref, T0, UnitState.empty()),
            "sd", new SdSnapshot(ref, T0, SdState.empty()),
            "economy", new EconomySnapshot(ref, T0, EconomyData.empty()),
            "actor", new ActorSnapshot(ref, T0, ActorData.empty())),
        InMemoryInfoSystem.empty());
  }

  // ── worldgen 配置（单国、production-runtime 可解析）────────────────────────────────────

  /** 从测试 resource 的 {@code test-three-nations.json} 改出单国 12 格配置：只改 nation 字段，不手写整份 schema。 */
  public static Path writeConfig(Path dir) throws IOException {
    Objects.requireNonNull(dir, "dir");
    Path source = configFile();
    JsonNode root = JSON.readTree(Files.readString(source, StandardCharsets.UTF_8));
    if (!(root instanceof ObjectNode object) || !(object.get("nations") instanceof ArrayNode)) {
      throw new IllegalStateException("测试 worldgen 配置形状不符: " + source);
    }
    ArrayNode nations = (ArrayNode) object.get("nations");
    if (nations.isEmpty() || !(nations.get(0) instanceof ObjectNode first)) {
      throw new IllegalStateException("测试 worldgen 配置 nations[0] 形状不符: " + source);
    }
    ObjectNode nation = first.deepCopy();
    nation.put("regionId", REGION.value());
    nation.put("displayName", "真实十二格");
    nation.put("population", TOTAL_POPULATION);
    nation.put("urbanizationRate", URBANIZATION_RATE);
    nation.put("agrarianSurplusRate", 1.2);
    nation.put("commercialIntegration", 1.2);
    nation.put("politicalCentralization", 1.1);
    nation.put("seed", MAP_SEED);
    if (nation.get("capital") instanceof ObjectNode capital) {
      capital.remove("targetPopulation"); // 12 格硬目标会与城市人口上限冲突；无硬目标更自然。
    }
    object.set("nations", JSON.createArrayNode().add(nation));
    Path out = dir.resolve("real-twelve-worldgen.json");
    Files.writeString(out, JSON.writeValueAsString(object), StandardCharsets.UTF_8);
    return out;
  }

  /** 测试 resource 路径（先文件系统、再 classpath）。 */
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
    URL resource = RealTwelveHexWorld.class.getResource("/worldgen/test-three-nations.json");
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
}
