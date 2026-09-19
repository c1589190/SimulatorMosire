package io.mosire.simos.app.gui;

import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.map.City;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexVertex;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.MovementState;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 视图装配（M5 T8）：把领域类型转成 GUI 直读的 {@link Map} / {@link List} 树，**只读、无副作用**。
 *
 * <p>★ **为什么单列一类**：{@link GuiServer} 只该管"路由 + 触
 * CoreSimos/QueryService"，把"领域对象长什么样"抽在这里，**不重算领域语义**—— 位置一律走 {@link
 * UnitState#effectivePosition(UnitId, SimosTimestamp)}、人口一律走 {@link
 * PopulationSeries#valueAt(SimosTimestamp)}，与 facet 同口径（GUI 不造第二份真相）。
 *
 * <p>★ **不碰存储、不碰时间线**：本类是纯函数，R1 的扫描对象之一（main 源码不得出现 store/timeline 写面）。
 */
final class ApiViews {

  private ApiViews() {}

  /** {@code {q,r}}（六角坐标的 JSON 形；身份仍是类型，这个串只在边界）。 */
  static Map<String, Object> hexCoord(HexCoord coord) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", coord.q());
    view.put("r", coord.r());
    return view;
  }

  /** {@code {tick,calendarLabel}}。 */
  static Map<String, Object> timestamp(SimosTimestamp at) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tick", at.tick());
    view.put("calendarLabel", at.calendarLabel().orElse(null));
    return view;
  }

  /** {@code {branch,revision}}。 */
  static Map<String, Object> stateRef(StateRef ref) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", ref.branch().value());
    view.put("revision", ref.revision().value());
    return view;
  }

  /** 写端点三种结局的统一视图（spec §8.2：{@code {result,ref?/current?/reason?}}）。 */
  static Map<String, Object> committed(StateRef ref) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "committed");
    view.put("ref", stateRef(ref));
    return view;
  }

  static Map<String, Object> conflict(StateRef current) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "conflict");
    view.put("current", stateRef(current));
    return view;
  }

  static Map<String, Object> rejected(String reason) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "rejected");
    view.put("reason", reason);
    return view;
  }

  /** resolver 候选逐条（id 的 namespace/localId 显式拆开，不让 Jackson 猜 record 组件名）。 */
  static List<Map<String, Object>> resolveResult(QueryResult result) {
    List<Map<String, Object>> out = new ArrayList<>(result.candidates().size());
    for (ResolvedSubject subject : result.candidates()) {
      Map<String, Object> id = new LinkedHashMap<>();
      id.put("namespace", subject.id().namespace());
      id.put("localId", subject.id().localId());
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", id);
      view.put("canonicalAddress", subject.canonicalAddress());
      view.put("typeName", subject.typeName());
      out.add(view);
    }
    return out;
  }

  /** facet 条目逐条（{@code value} 原样透出，不做二次格式化——spec §〇.3-5 的类型契约）。 */
  static List<Map<String, Object>> facets(List<FacetEntry> entries) {
    List<Map<String, Object>> out = new ArrayList<>(entries.size());
    for (FacetEntry entry : entries) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("namespace", entry.namespace());
      view.put("label", entry.label());
      view.put("typeName", entry.typeName());
      view.put("value", entry.value());
      out.add(view);
    }
    return out;
  }

  /**
   * 时间轴节点清单（spec §3.1，M7 T1）：{@code {branch,head,nodes:[…]}}。节点**不含 {@code changesetJson}**（体积大且对
   * UI 无用），{@code parent} 为 {@code {branch,revision}} 或 {@code null}。
   */
  static Map<String, Object> timeline(BranchId branch, long head, List<RevisionRow> rows) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", branch.value());
    view.put("head", head);
    List<Map<String, Object>> nodes = new ArrayList<>(rows.size());
    for (RevisionRow row : rows) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("revision", row.revision().value());
      node.put("tick", row.timestamp().tick());
      node.put("commandType", row.commandType());
      node.put("initiator", row.initiator());
      node.put("parent", row.parent().map(ApiViews::stateRef).orElse(null));
      nodes.add(node);
    }
    view.put("nodes", nodes);
    return view;
  }

  /**
   * 地图只读总览（地图形状，T9 的 Canvas 数据源；M9 T13 起**发块多边形**）。
   *
   * <p>★ **不再发逐格地形**（M9 T13，spec §七.4「载荷从 1,043,837 B 降到块数量级」）：地形由 {@code terrainBlocks} 的权威块承载，客户端
   * Pass 1 直接画带洞多边形、拾取走"点在多边形内" —— 逐格 {@code {q,r,terrain}} 数组整个移除 （{@code hexCount}
   * 仍发，供图例与"块是否覆盖全图"的一致性核对）。
   *
   * <p>★ **逐字节确定**：块按 {@link BlockId} 全序发（不靠 Map 迭代序）；顶点坐标量化到 3 位小数（消浮点噪声）；环按 {@code
   * RegionBoundary} 的规范序并**闭合**（首点补到末尾）—— 同一状态两次调用必得逐字节相同的响应。
   */
  static Map<String, Object> mapOverview(String mapId, GameMap map) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("mapId", mapId);
    view.put("hexCount", map.hexes().size());
    view.put("blocks", blockViews(map));

    List<Map<String, Object>> regions = new ArrayList<>(map.regions().size());
    for (Region region : map.regions().values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", region.id().value());
      item.put("name", region.name());
      item.put("hexCount", region.hexes().size());
      item.put("meta", regionMeta(region.meta()));
      regions.add(item);
    }
    view.put("regions", regions);

    List<Map<String, Object>> cities = new ArrayList<>(map.cities().size());
    for (City city : map.cities().values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", city.id().value());
      item.put("name", city.name());
      item.put("at", hexCoord(city.at()));
      item.put("region", city.region() == null ? null : city.region().value());
      cities.add(item);
    }
    view.put("cities", cities);

    view.put("terrainTypes", terrainTypeDefinitions(map));
    return view;
  }

  /**
   * 权威地形块的 JSON 视图（M9 T13）：{@code [{id,terrain,hexCount,boundaries:[[{x,y}…]…]}…]}。
   *
   * <p>★ 坐标是**格边长 = 1 的世界坐标**（{@code x = u·√3/2, y = w/2}，由 {@link HexVertex} 的整数标签换算），客户端乘自己的
   * 格边长即可；量化到 3 位小数保证响应逐字节稳定。★ 块按 {@link BlockId} 全序 —— 与状态里的插入顺序无关，跨进程 / 跨重放都可复现。
   */
  private static List<Map<String, Object>> blockViews(GameMap map) {
    List<BlockId> ids = new ArrayList<>(map.terrainBlocks().keySet());
    ids.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(ids.size());
    for (BlockId id : ids) {
      TerrainBlock block = map.terrainBlocks().get(id);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", id.toString());
      item.put("terrain", block.terrain());
      item.put("hexCount", block.hexes().size());
      List<List<Map<String, Object>>> rings = new ArrayList<>(1);
      rings.add(closedRingView(block.boundary().rings().get(0)));
      item.put("boundaries", rings);
      out.add(item);
    }
    return out;
  }

  /** 一条环的视图：**闭合**（首顶点补到末尾），顶点为量化后的世界坐标。 */
  private static List<Map<String, Object>> closedRingView(List<HexVertex> ring) {
    List<Map<String, Object>> out = new ArrayList<>(ring.size() + 1);
    for (HexVertex vertex : ring) {
      out.add(vertexView(vertex));
    }
    out.add(vertexView(ring.get(0)));
    return out;
  }

  /** 顶点视图：{@code {x,y}}，格边长 = 1 的世界坐标（量化为 3 位小数）。 */
  private static Map<String, Object> vertexView(HexVertex vertex) {
    Map<String, Object> point = new LinkedHashMap<>();
    point.put("x", quantize(vertex.u() * (Math.sqrt(3.0) / 2.0)));
    point.put("y", quantize(vertex.w() * 0.5));
    return point;
  }

  /** 量化到 3 位小数；{@code -0.0} 归一成 {@code 0.0}（否则两次响应的字节会差一个符号）。 */
  private static double quantize(double value) {
    double rounded = Math.round(value * 1000.0) / 1000.0;
    return rounded == 0.0 ? 0.0 : rounded;
  }

  /**
   * 地形词表的**完整定义**（M7 T2，spec §3.2）：由 {@code [key…]} 切换为 {@code [{完整定义}…]}。
   *
   * <p>★ **数据来源仍是状态里的** {@code map.terrainTypes()}（不查 {@link TerrainCatalog#defaults()}——那是第二份真相）；
   * {@code TerrainCatalog.KEYS} **只用来定序**（高度升序）。词表里出现 KEYS 之外的 key（理论不该有）时排到末尾并按字典序， 保证响应字节可复现。
   *
   * <p>★ 十字段与 {@link TerrainType} 的 record 组件一一对应——前端图例/色板因此可**完全**取自后端（T4 删硬编码色表）。
   */
  private static List<Map<String, Object>> terrainTypeDefinitions(GameMap map) {
    List<String> keys = new ArrayList<>(map.terrainTypes().keySet());
    keys.sort(
        Comparator.comparingInt(
                (String key) -> {
                  int index = TerrainCatalog.KEYS.indexOf(key);
                  return index < 0 ? Integer.MAX_VALUE : index;
                })
            .thenComparing(Comparator.naturalOrder()));
    List<Map<String, Object>> out = new ArrayList<>(keys.size());
    for (String key : keys) {
      out.add(terrainType(map.terrainTypes().get(key)));
    }
    return out;
  }

  /** 单个 {@link TerrainType} 的 JSON 形（十字段，顺序与 record 组件一致）。 */
  private static Map<String, Object> terrainType(TerrainType type) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("key", type.key());
    view.put("name", type.name());
    view.put("color", type.color());
    view.put("minHeight", type.minHeight());
    view.put("maxHeight", type.maxHeight());
    view.put("food", type.food());
    view.put("gold", type.gold());
    view.put("stone", type.stone());
    view.put("moveCost", type.moveCost());
    view.put("description", type.description());
    return view;
  }

  /**
   * 单格：格内容 + 所属区域**列表**（多从属，M8-U1）+ **完整地形定义** + facet 汇总（facet 由调用方按 **canonical** 地址查询，spec
   * §5.2）。{@code regions} 按 {@link RegionId} 字典序，**空数组 = 无从属**。
   *
   * <p>★ {@code terrainType} 由调用方从**状态里的** {@code map.terrainTypes()} 取出（spec §3.2）——本类不查 {@code
   * TerrainCatalog}，词表只有一个来源。
   */
  static Map<String, Object> mapHex(
      HexCoord coord,
      HexCell cell,
      String terrain,
      List<FacetEntry> facets,
      List<RegionId> regions,
      TerrainType terrainType) {
    Map<String, Object> view = hexCoord(coord);
    view.put("terrain", terrain);
    view.put("height", cell.height());
    List<String> regionIds = new ArrayList<>(regions.size());
    for (RegionId region : regions) {
      regionIds.add(region.value());
    }
    view.put("regions", regionIds);
    view.put("terrainType", terrainType);
    view.put("facets", facets(facets));
    return view;
  }

  /**
   * 区域详情（spec §3.3，M7 T1）：{@code {id,name,meta,hexCount,hexes:[{q,r}…]}}。
   *
   * <p>★ {@code hexes} 按 {@code (q,r)} 升序——{@link Region#hexes()} 是 {@code Set.copyOf}（迭代序不稳定），
   * 不排序的响应字节不可复现，测试也钉不住。
   */
  static Map<String, Object> regionDetail(Region region) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", region.id().value());
    view.put("name", region.name());
    view.put("meta", regionMeta(region.meta()));
    view.put("hexCount", region.hexes().size());
    List<HexCoord> sorted = new ArrayList<>(region.hexes());
    sorted.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    List<Map<String, Object>> hexes = new ArrayList<>(sorted.size());
    for (HexCoord coord : sorted) {
      hexes.add(hexCoord(coord));
    }
    view.put("hexes", hexes);
    return view;
  }

  /** 区域元数据（四字段可空；spec §3.3）。 */
  private static Map<String, Object> regionMeta(RegionMeta meta) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("color", meta.color());
    view.put("tag", meta.tag());
    view.put("description", meta.description());
    view.put("annexedBy", meta.annexedBy());
    return view;
  }

  /** 单位列表（每个单位带 head 时刻的有效位置与在途移动视图）。 */
  static List<Map<String, Object>> units(UnitState units, SimosTimestamp at, GameMap map) {
    List<Map<String, Object>> out = new ArrayList<>(units.units().size());
    for (Unit unit : units.units().values()) {
      out.add(unit(unit, units, at, map));
    }
    return out;
  }

  /** 单位详情：冻结字段 + {@code parent}（head 时刻）+ **有效位置**（head 时刻，向父取）+ **在途移动视图**。 */
  static Map<String, Object> unit(Unit unit, UnitState units, SimosTimestamp at, GameMap map) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", unit.id().value());
    view.put("name", unit.name());
    view.put("member", unit.member());
    view.put("equipment", new LinkedHashMap<>(unit.equipment()));
    view.put("speed", unit.speed());
    view.put("mobilityPerMille", unit.mobilityPerMille());
    view.put("parent", unit.parent().valueAt(at).map(UnitId::value).orElse(null));
    view.put(
        "position", units.effectivePosition(unit.id(), at).map(ApiViews::hexCoord).orElse(null));
    view.put("movement", movement(unit, at, map));
    return view;
  }

  /**
   * 在途移动视图（M7b T2）：无路线 ⇒ {@code null}；有 ⇒ 路线 + **现算**的在途状态。
   *
   * <p>★ {@code status}/{@code currentHex}/{@code nextHex}/{@code remainingMillis} **一律由 {@link
   * UnitMoves#evaluate} 现算**——app 层不重写"预算 / 每格成本 / 付清到哪一段"的算法，那是第二份真相（与位置走 {@code
   * effectivePosition} 同一条纪律）。
   */
  private static Object movement(Unit unit, SimosTimestamp at, GameMap map) {
    if (unit.movement().isEmpty()) {
      return null;
    }
    Movement movement = unit.movement().orElseThrow();
    MovementState state = UnitMoves.evaluate(unit, at, map, TerrainMovementCost.INSTANCE);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("route", route(movement.route()));
    view.put("departedAt", timestamp(movement.departedAt()));
    view.put("speedAtDeparture", movement.speedAtDeparture());
    view.put("mobilityPerMilleAtDeparture", movement.mobilityAtDeparture());
    view.put("status", state.status().name());
    view.put("currentHex", hexCoord(state.currentHex()));
    view.put("nextHex", state.nextHex().map(ApiViews::hexCoord).orElse(null));
    view.put(
        "remainingMillis",
        state.remainingEdgeCostMillis().isPresent()
            ? state.remainingEdgeCostMillis().getAsLong()
            : null);
    return view;
  }

  /** 路线视图：{@code {waypoints:[{q,r}…], path:[{q,r}…]}}（path 是逐格相邻的完整格序列）。 */
  private static Map<String, Object> route(Route route) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("waypoints", hexCoords(route.waypoints()));
    view.put("path", hexCoords(route.path()));
    return view;
  }

  /** 六角坐标列表的 JSON 形（保序）。 */
  private static List<Map<String, Object>> hexCoords(List<HexCoord> coords) {
    List<Map<String, Object>> out = new ArrayList<>(coords.size());
    for (HexCoord coord : coords) {
      out.add(hexCoord(coord));
    }
    return out;
  }

  /**
   * 寻路结果视图（M7b T3）：{@code {reachable, path:[{q,r}…]}}。
   *
   * <p>★ {@code path} 由 {@code PathFinder.findPath} 产出（**逐格相邻、含首尾**）⇒ 可原样当作 {@code unit.PlanRoute}
   * 的 {@code waypoints}（该命令把 waypoints 当 path 用，见 {@code PlanRouteHandler}）。不可达 ⇒ {@code
   * reachable=false} 且 {@code path=[]}（不省略字段，前端一次判空即可）。
   */
  static Map<String, Object> pathResult(boolean reachable, List<HexCoord> path) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("reachable", reachable);
    view.put("path", hexCoords(reachable ? path : List.of()));
    return view;
  }

  /** 人口时序点：{@code {q,r,at,population}}。 */
  static Map<String, Object> population(
      HexCoord coord, PopulationSeries series, SimosTimestamp at) {
    Map<String, Object> view = hexCoord(coord);
    view.put("at", timestamp(at));
    view.put("population", series.valueAt(at));
    return view;
  }

  /** map 切片（缺席或类型不对是装配故障，不是"没有候选"）。 */
  static GameMap gameMap(SimulationState state) {
    Snapshot snapshot =
        state
            .module("map")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  /** unit 切片。 */
  static UnitState unitState(SimulationState state) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 unit 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  /** social 切片。 */
  static SocialData socialData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 social 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }
}
