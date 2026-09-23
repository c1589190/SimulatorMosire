package io.mosire.simos.social.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 真档夹具：读签入的 {@code v17levant} 世界（真地图）+ 冻结输入的国家参数，构造三国的 {@link SettlementRequest}。
 *
 * <p>★ **为什么 social 测试要走相对路径去读 simos-app 的资源**：social 模块**不能依赖 simos-app**（enforcer 会拦），而本夹具要用真档的
 * 799 格验 {@code TerrainView} 与守恒 —— 合成夹具验不了"riverEdgesAt 的真值"。surefire 的工作目录默认是模块 basedir，故 {@code
 * ../simos-app/...} 命中；同时兼容"从仓根起跑"的 {@code simos-app/...}。
 *
 * <p>★ 世界解码走**真读路径**（{@code MapCodec.decodeSnapshot}，含旧形状迁移），与 {@code RichWorld} 同一条路 —— 不另写一套解析。
 */
final class RealNations {

  static final String DEUTSCHES_REICH = "德意志第二帝国";
  static final String OSTERMARK = "奥斯特马克侯国";
  static final String HOCHLAND = "霍赫兰伯国";
  static final List<String> ALL = List.of(DEUTSCHES_REICH, OSTERMARK, HOCHLAND);

  private static final String WORLD = "simos-app/src/main/resources/worlds/v17levant.json";
  private static final String NATIONS = "config/worldgen/v17levant-nations.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static GameMap map;
  private static TerrainView terrain;
  private static JsonNode nations;

  private RealNations() {}

  /** 真地图（缓存；解码 59223 格约几秒，整个测试类只付一次）。 */
  static synchronized GameMap map() {
    if (map == null) {
      String envelope = read(WORLD);
      String mapJson = tree(envelope).path("modules").path("map").asText();
      MapSnapshot snapshot = (MapSnapshot) new MapCodec().decodeSnapshot(mapJson);
      map = snapshot.map();
    }
    return map;
  }

  /**
   * {@link TerrainView#of(GameMap)} 的真实例。★ 名字**故意不叫 {@code terrain()}**：simos-map 的表示法泄漏护栏把 {@code
   * .terrain()} 当"直读 HexCell.terrain"，会（正确地）把我这行报成违规。
   */
  static synchronized TerrainView terrainView() {
    if (terrain == null) {
      terrain = TerrainView.of(map());
    }
    return terrain;
  }

  /** 按 regionId 构造请求（人口/系/种子/首都/文档地名都取自冻结输入）。 */
  static synchronized SettlementRequest request(String regionName) {
    JsonNode nation = null;
    for (JsonNode candidate : nations().path("nations")) {
      if (regionName.equals(candidate.path("regionId").asText())) {
        nation = candidate;
        break;
      }
    }
    if (nation == null) {
      throw new IllegalArgumentException("冻结输入里没有这个国家: " + regionName);
    }
    RegionId regionId = new RegionId(regionName);
    Region region = map().regions().get(regionId);
    if (region == null) {
      throw new IllegalStateException("真档里没有这个 region: " + regionName);
    }
    Set<HexCoord> hexes = region.hexes();
    JsonNode capitalJson = nation.path("capital");
    Optional<CapitalAnchor> capital;
    if (capitalJson.isMissingNode() || capitalJson.isNull()) {
      capital = Optional.empty();
    } else {
      String name = capitalJson.path("name").asText();
      JsonNode target = capitalJson.path("targetPopulation");
      capital =
          Optional.of(
              target.isMissingNode() || target.isNull()
                  ? CapitalAnchor.withoutTarget(name)
                  : CapitalAnchor.of(name, target.asLong()));
    }
    List<String> documented = new ArrayList<>();
    for (JsonNode place : nation.path("documentedPlaceNames")) {
      documented.add(place.path("name").asText());
    }
    return new SettlementRequest(
        regionId,
        nation.path("displayName").asText(),
        nation.path("population").asLong(),
        nation.path("urbanizationRate").asDouble(),
        nation.path("agrarianSurplusRate").asDouble(),
        nation.path("commercialIntegration").asDouble(),
        nation.path("politicalCentralization").asDouble(),
        nation.path("seed").asLong(),
        capital,
        hexes,
        documented);
  }

  private static synchronized JsonNode nations() {
    if (nations == null) {
      nations = tree(read(NATIONS));
    }
    return nations;
  }

  private static JsonNode tree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(String relative) {
    try {
      return Files.readString(resourcePath(relative), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 冻结输入 JSON 的实际路径（生产版加载器 {@code WorldgenConfig.load(Path)} 吃它就够）。 */
  static Path nationsFile() {
    return resourcePath(NATIONS);
  }

  private static Path resourcePath(String relative) {
    for (Path candidate : List.of(Path.of("..", relative), Path.of(relative))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "找不到真档资源 " + relative + "（测试工作目录=" + Path.of(".").toAbsolutePath() + "）");
  }
}
