package io.mosire.simos.app.render;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 取值 + 裁剪 + 投影：把"世界状态"变成一张 {@link RenderModel} 绘制清单。
 *
 * <p>三种视图共用这一份口径（图 / 字符图 / GUI 热力图），因此**各图层怎么取数只在这里写一次**：
 *
 * <ul>
 *   <li>地形：{@code GameMap.terrainAt} → {@code terrainTypes[key].color}（世界数据里的色；缺失回兜底色，不静默给白）；
 *   <li>区域：边界 = 相邻两格区域不同处的那条边（颜色取 {@code region.meta.color}）；
 *   <li>城市：social 侧城市节点（落点）；
 *   <li>单位：unit 侧<b>有效位置</b>（{@code UnitState.effectivePosition}——自己没位置时向父取，与 GUI/MCP 同口径）；
 *   <li>人口：{@code SocialData.populations} 的<b>农村</b>人口，按对数分档着色（城市人口在城节点上，不混进来）。
 * </ul>
 *
 * <p>★ 本类<b>不认识存储</b>（不碰 SqliteStore/Timeline/CheckpointStore）——只吃装配好的状态切片，符合 app 层写路径守卫。
 */
public final class RenderModelBuilder {

  /** 画布底色（与前端画布同色系，避免"两处看到的世界不一样"的错觉）。 */
  static final String BACKGROUND = "#0D1015";

  /** 地形类型缺色时的兜底色（灰蓝；不静默给白——白与"海洋"太像）。 */
  static final String FALLBACK_TERRAIN_COLOR = "#6E7B8B";

  /** 区域无色时的兜底边色。 */
  static final String REGION_EDGE_FALLBACK = "#E8E8E8";

  static final String CITY_COLOR = "#F0D27A";
  static final String UNIT_COLOR = "#FF5C5C";

  /** 人口色阶（浅 → 深，5 档）。 */
  static final List<String> POPULATION_SCALE =
      List.of("#F7FBFF", "#C6DBEF", "#6BAED6", "#2171B5", "#08306B");

  /** "该格没有人口数据"的中性色——**必须与色阶最浅档拉开**：把"无数据"画成"人口极低"是这张图最容易撒的谎 （第一版实测就长这样：三大国之外的格白得发亮，看着像"人口 0"）。 */
  static final String NO_DATA_COLOR = "#39404A";

  /** 画布留白比例（四周各留 4%，给图例与边格留气口）。 */
  private static final double MARGIN_RATIO = 0.92;

  private RenderModelBuilder() {}

  /** 构建一张图的地图视图清单（图层与范围见 {@link RenderRequest}）。 */
  public static RenderModel build(
      GameMap map, SocialData social, UnitState units, SimosTimestamp at, RenderRequest request) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(request, "request");

    List<HexCoord> visible =
        MapProjection.hexesWithin(request.center(), request.radius()).stream()
            .filter(coord -> map.hexes().containsKey(coord))
            .toList();
    Set<HexCoord> visibleSet = new LinkedHashSet<>(visible);

    Viewport viewport = Viewport.fitting(visible, request.width(), request.height());

    Map<HexCoord, Long> populations = readPopulations(social, at, visible);
    long populationMax = populations.values().stream().mapToLong(Long::longValue).max().orElse(0L);

    List<RenderModel.HexShape> shapes = new ArrayList<>(visible.size());
    for (HexCoord coord : visible) {
      String color;
      if (request.layers().contains(RenderLayer.POPULATION)) {
        color =
            populations.get(coord) == null
                ? NO_DATA_COLOR
                : POPULATION_SCALE.get(populationBucket(populations.get(coord), populationMax));
      } else {
        color = terrainColor(map, coord);
      }
      shapes.add(
          new RenderModel.HexShape(
              coord, viewport.pixelX(coord), viewport.pixelY(coord), viewport.hexSize(), color));
    }

    List<RenderModel.Segment> segments =
        request.layers().contains(RenderLayer.REGIONS)
            ? regionEdges(map, visible, viewport)
            : List.of();

    List<RenderModel.Marker> markers = new ArrayList<>();
    if (request.layers().contains(RenderLayer.CITIES)) {
      for (SocialCity city : social.cities().values()) {
        if (!visibleSet.contains(city.at())) {
          continue;
        }
        markers.add(
            new RenderModel.Marker(
                viewport.pixelX(city.at()),
                viewport.pixelY(city.at()),
                RenderModel.MarkerKind.CITY,
                city.name(),
                CITY_COLOR));
      }
    }
    if (request.layers().contains(RenderLayer.UNITS)) {
      for (Unit unit : units.units().values()) {
        HexCoord here = units.effectivePosition(unit.id(), at).orElse(null);
        if (here == null || !visibleSet.contains(here)) {
          continue;
        }
        markers.add(
            new RenderModel.Marker(
                viewport.pixelX(here),
                viewport.pixelY(here),
                RenderModel.MarkerKind.UNIT,
                unit.name() == null || unit.name().isBlank() ? unit.id().value() : unit.name(),
                UNIT_COLOR));
      }
    }

    List<RenderModel.LegendEntry> legend =
        buildLegend(map, visible, request.layers(), populationMax, populations);
    String title =
        "中心 (" + request.center().q() + "," + request.center().r() + ") · 半径 " + request.radius();
    String subtitle =
        "图层 "
            + layerNames(request.layers())
            + " · "
            + visible.size()
            + " 格"
            + (request.layers().contains(RenderLayer.POPULATION) ? "（农村人口热度，城市人口另算）" : "");

    return new RenderModel(
        request.width(), request.height(), title, subtitle, shapes, segments, markers, legend);
  }

  // ---------- 取数 ----------

  private static Map<HexCoord, Long> readPopulations(
      SocialData social, SimosTimestamp at, List<HexCoord> visible) {
    Map<HexCoord, Long> out = new LinkedHashMap<>();
    for (HexCoord coord : visible) {
      PopulationSeries series = social.populations().get(coord);
      if (series == null) {
        continue;
      }
      Long value = series.valueAt(at);
      if (value != null) {
        out.put(coord, value);
      }
    }
    return out;
  }

  private static String terrainColor(GameMap map, HexCoord coord) {
    String key = map.terrainAt(coord);
    if (key == null) {
      return FALLBACK_TERRAIN_COLOR;
    }
    TerrainType type = map.terrainTypes().get(key);
    if (type == null || type.color() == null || type.color().isBlank()) {
      return FALLBACK_TERRAIN_COLOR;
    }
    return type.color();
  }

  private static List<RenderModel.Segment> regionEdges(
      GameMap map, List<HexCoord> visible, Viewport viewport) {
    Map<HexCoord, RegionId> regionIndex = regionIndex(map, visible);
    List<RenderModel.Segment> out = new ArrayList<>();
    double[][] corners = MapProjection.corners(viewport.hexSize());
    for (HexCoord coord : visible) {
      RegionId mine = regionIndex.get(coord);
      if (mine == null) {
        continue;
      }
      for (int dir = 0; dir < MapProjection.DIR_VECTORS.length; dir++) {
        int[] step = MapProjection.DIR_VECTORS[dir];
        HexCoord neighbor = new HexCoord(coord.q() + step[0], coord.r() + step[1]);
        if (!map.hexes().containsKey(neighbor)) {
          continue; // 地图边缘不画：那是"视野边界"，不是区域边界（画了像一圈假墙）
        }
        if (mine.equals(regionIndex.get(neighbor))) {
          continue;
        }
        int[] edge = MapProjection.edgeCornerIndexes(dir);
        double cx = viewport.pixelX(coord);
        double cy = viewport.pixelY(coord);
        out.add(
            new RenderModel.Segment(
                cx + corners[edge[0]][0],
                cy + corners[edge[0]][1],
                cx + corners[edge[1]][0],
                cy + corners[edge[1]][1],
                regionColor(map, mine),
                2.0));
      }
    }
    return out;
  }

  /** 视野内每格的区域归属（一遍扫全区域，避免"每格 × 每区域"的二次方探测）。 */
  private static Map<HexCoord, RegionId> regionIndex(GameMap map, List<HexCoord> visible) {
    Set<HexCoord> visibleSet = new LinkedHashSet<>(visible);
    Map<HexCoord, RegionId> out = new LinkedHashMap<>();
    for (Region region : map.regions().values()) {
      for (HexCoord coord : region.hexes()) {
        if (visibleSet.contains(coord)) {
          out.put(coord, region.id());
        }
      }
    }
    return out;
  }

  private static String regionColor(GameMap map, RegionId id) {
    Region region = map.regions().get(id);
    if (region == null
        || region.meta() == null
        || region.meta().color() == null
        || region.meta().color().isBlank()) {
      return REGION_EDGE_FALLBACK;
    }
    return region.meta().color();
  }

  // ---------- 人口分档与图例 ----------

  /** 对数分档：返回 {@link #POPULATION_SCALE} 的下标（max ≤ 0 时恒为 0）。 */
  static int populationBucket(Long value, long max) {
    if (value == null || value <= 0 || max <= 0) {
      return 0;
    }
    double fraction = Math.log10(1.0 + value) / Math.log10(1.0 + max);
    int index = (int) Math.floor(fraction * POPULATION_SCALE.size());
    return Math.max(0, Math.min(POPULATION_SCALE.size() - 1, index));
  }

  private static List<RenderModel.LegendEntry> buildLegend(
      GameMap map,
      List<HexCoord> visible,
      Set<RenderLayer> layers,
      long populationMax,
      Map<HexCoord, Long> populations) {
    List<RenderModel.LegendEntry> out = new ArrayList<>();
    if (layers.contains(RenderLayer.POPULATION)) {
      long[] thresholds = populationThresholds(populationMax);
      out.add(new RenderModel.LegendEntry("0 – " + thresholds[0] + " 人", POPULATION_SCALE.get(0)));
      for (int i = 1; i < thresholds.length; i++) {
        out.add(
            new RenderModel.LegendEntry(
                thresholds[i - 1] + " – " + thresholds[i] + " 人", POPULATION_SCALE.get(i)));
      }
      out.add(
          new RenderModel.LegendEntry(
              "> " + thresholds[thresholds.length - 1] + " 人",
              POPULATION_SCALE.get(POPULATION_SCALE.size() - 1)));
      if (visible.stream().anyMatch(coord -> !populations.containsKey(coord))) {
        out.add(new RenderModel.LegendEntry("无数据", NO_DATA_COLOR));
      }
    } else {
      Map<String, RenderModel.LegendEntry> byKey = new TreeMap<>();
      for (HexCoord coord : visible) {
        String key = map.terrainAt(coord);
        if (key == null || byKey.containsKey(key)) {
          continue;
        }
        TerrainType type = map.terrainTypes().get(key);
        String label = type == null || type.name() == null ? key : type.name();
        byKey.put(key, new RenderModel.LegendEntry(label, terrainColor(map, coord)));
      }
      out.addAll(byKey.values());
    }
    if (layers.contains(RenderLayer.CITIES)) {
      out.add(new RenderModel.LegendEntry("城市", CITY_COLOR));
    }
    if (layers.contains(RenderLayer.UNITS)) {
      out.add(new RenderModel.LegendEntry("单位", UNIT_COLOR));
    }
    return out;
  }

  /** 5 档的分界值（对数等分；max ≤ 0 时给全 0，图例不撒谎）。 */
  static long[] populationThresholds(long max) {
    long[] out = new long[POPULATION_SCALE.size() - 1];
    if (max <= 0) {
      return out;
    }
    double logMax = Math.log10(1.0 + max);
    for (int i = 0; i < out.length; i++) {
      double fraction = (i + 1) / (double) POPULATION_SCALE.size();
      out[i] = Math.max(1, (long) Math.pow(10, fraction * logMax) - 1);
    }
    return out;
  }

  private static String layerNames(Set<RenderLayer> layers) {
    List<String> names = new ArrayList<>();
    for (RenderLayer layer : RenderLayer.values()) {
      if (layers.contains(layer)) {
        names.add(layer.name().toLowerCase(java.util.Locale.ROOT));
      }
    }
    return String.join("+", names);
  }

  /** 一块视野的投影：世界坐标（size=1）→ 像素。 */
  record Viewport(double hexSize, double worldCenterX, double worldCenterY, int width, int height) {

    static Viewport fitting(List<HexCoord> visible, int width, int height) {
      if (visible.isEmpty()) {
        return new Viewport(1.0, 0.0, 0.0, width, height);
      }
      double minX = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double minY = Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;
      for (HexCoord coord : visible) {
        double x = MapProjection.centerX(coord, 1.0);
        double y = MapProjection.centerY(coord, 1.0);
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
      }
      // 六角的外接尺寸（size=1）：宽 √3、高 2
      double spanX = (maxX - minX) + Math.sqrt(3.0);
      double spanY = (maxY - minY) + 2.0;
      double size = Math.min(width * MARGIN_RATIO / spanX, height * MARGIN_RATIO / spanY);
      return new Viewport(size, (minX + maxX) / 2.0, (minY + maxY) / 2.0, width, height);
    }

    double pixelX(HexCoord coord) {
      return (MapProjection.centerX(coord, 1.0) - worldCenterX) * hexSize + width / 2.0;
    }

    double pixelY(HexCoord coord) {
      return (MapProjection.centerY(coord, 1.0) - worldCenterY) * hexSize + height / 2.0;
    }
  }
}
