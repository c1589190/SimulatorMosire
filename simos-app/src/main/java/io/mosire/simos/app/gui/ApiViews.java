package io.mosire.simos.app.gui;

import io.mosire.simos.app.access.DecisionScopeView;
import io.mosire.simos.app.decision.DecisionAgentRunner;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.map.City;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexVertex;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
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
import io.mosire.simos.util.address.Address;
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
import java.util.Set;

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
   * <p>★ **逐字节确定**：块按 {@link BlockId} 全序发（不靠 Map 迭代序）；顶点以**整数标签**发（见下）；环按 {@code RegionBoundary}
   * 的规范序并**闭合**（首点补到末尾）—— 同一状态两次调用必得逐字节相同的响应。
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
      item.put("label", regionLabelHex(region));
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
    view.put("pathwayGroups", pathwayGroupDefinitions(map));
    view.put("edges", allEdgeViews(map.edges()));
    return view;
  }

  /**
   * 连通性组的**完整定义**（T3，spec §三.6）：{@code [{id,name,color,description,visible,properties}…]}，按组 id 字典序
   * （与状态插入序无关 ⇒ 响应字节可复现）。UI 的 kind 候选**只能**来自这里（不硬编码 river/road）。
   */
  private static List<Map<String, Object>> pathwayGroupDefinitions(GameMap map) {
    List<String> ids = new ArrayList<>(map.pathwayGroups().keySet());
    ids.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(ids.size());
    for (String id : ids) {
      PathwayGroup group = map.pathwayGroups().get(id);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", group.id());
      view.put("name", group.name());
      view.put("color", group.color());
      view.put("description", group.description());
      view.put("visible", group.visible());
      List<Map<String, Object>> properties = new ArrayList<>(group.properties().size());
      for (Map.Entry<String, PathwayGroup.PropertyDef> entry : group.properties().entrySet()) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", entry.getValue().type());
        def.put("defaultValue", entry.getValue().defaultValue());
        def.put("description", entry.getValue().description());
        properties.add(def);
      }
      view.put("properties", properties);
      out.add(view);
    }
    return out;
  }

  /** 全图边表（T3）：{@code [{edge,pathways}…]}，边按 {@link EdgeRef} 自然序、pathways 按字典序。 */
  private static List<Map<String, Object>> allEdgeViews(Map<EdgeRef, EdgeTags> edges) {
    List<EdgeRef> ordered = new ArrayList<>(edges.keySet());
    ordered.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(ordered.size());
    for (EdgeRef edge : ordered) {
      out.add(edgeEntry(edge, edges.get(edge)));
    }
    return out;
  }

  /**
   * 权威地形块的 JSON 视图（M9 T13）：{@code [{id,terrain,hexCount,boundaries:[[u,w,u,w,…]…]}…]}。
   *
   * <p>★ **顶点是整数标签**（{@link HexVertex#u()}/{@link HexVertex#w()}，M9 T11 起）：格角顶点的 {@code (u,w)}
   * **本就是整数**，直接发标签既**逐字节精确**（不再有浮点量化）又**显著省流**（比 {@code {"x":…,"y":…}} 对象少约 2/3 字节）。客户端按 {@code x
   * = u·√3/2、y = w/2} 还原（唯一的格式转换点在 {@code webui/blocks.js}）。★ 块按 {@link BlockId} 全序 ——
   * 与状态里的插入顺序无关，跨进程 / 跨重放都可复现。
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
      List<List<Integer>> rings = new ArrayList<>(block.boundary().rings().size());
      for (List<HexVertex> ring : block.boundary().rings()) {
        rings.add(closedLabelRing(ring));
      }
      item.put("boundaries", rings);
      out.add(item);
    }
    return out;
  }

  /**
   * 一条环的**整数顶点标签**：{@code [u0,w0,u1,w1,…]}，首顶点补到末尾（闭合）。
   *
   * <p>每条环的顶点数 &gt; 2，且首尾同点（闭合）。客户端据此逐对还原世界坐标。
   */
  private static List<Integer> closedLabelRing(List<HexVertex> ring) {
    List<Integer> out = new ArrayList<>(ring.size() * 2 + 2);
    for (HexVertex vertex : ring) {
      out.add(vertex.u());
      out.add(vertex.w());
    }
    out.add(ring.get(0).u());
    out.add(ring.get(0).w());
    return out;
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
   * §5.2）+ **入射连通性**（M8 T11）。{@code regions} 按**定义序**（{@code GameMap.regions} 插入序，★ **V3 取代 M8-Q6**
   * 的字典序； **末位 = 最顶层区域**），**空数组 = 无从属**。
   *
   * <p>★ {@code terrainType} 由调用方从**状态里的** {@code map.terrainTypes()} 取出（spec §3.2）——本类不查 {@code
   * TerrainCatalog}，词表只有一个来源。
   *
   * <p>★ {@code edges}（T11 新增）是**只读**增量：连通性此前在 app 层**没有任何读路径**（overview 只有块， mapHex
   * 只有地形），于是"`merge` 后既有 tag 仍在 / `replace` 后只剩新的"这条判据在**浏览器里根本观测不到** （断不了言 = 装饰）。字段形如 {@code
   * [{edge:"1_1|1_2", pathways:["river","road"]}]}，语义照 {@link EdgeTags#byPathway()} 的键。
   */
  static Map<String, Object> mapHex(
      HexCoord coord,
      HexCell cell,
      String terrain,
      List<FacetEntry> facets,
      List<RegionId> regions,
      TerrainType terrainType,
      Map<EdgeRef, EdgeTags> edges) {
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
    view.put("edges", incidentEdges(coord, edges));
    return view;
  }

  /**
   * 入射到 {@code coord} 的边（M8 T11）：{@code [{edge:"1_1|1_2", pathways:["river","road"]}]}。
   *
   * <p>★ **两种排序都是为了可复现**：边按 {@link EdgeRef} 自然序（`(q,r)`，**不是字符串序**——`1_10` 与 `1_2`
   * 的字符串序会把规范序倒过来），`pathways` 按字典序。`/api/map/hex` 有一条"同一 revision 两次响应**逐字节 相同**"的既有断言（{@code
   * GuiApiTest}）：任何一处跟着 `Map` 的迭代序走，那条断言就会随 JVM 的散列盐抖动。
   *
   * <p>★ 只发 pathway **键**（当前载荷里 props 恒为空表），与"哪种连通性在这条边上"这一个判据同名。
   */
  private static List<Map<String, Object>> incidentEdges(
      HexCoord coord, Map<EdgeRef, EdgeTags> edges) {
    List<EdgeRef> incident = new ArrayList<>();
    for (EdgeRef edge : edges.keySet()) {
      if (edge.a().equals(coord) || edge.b().equals(coord)) {
        incident.add(edge);
      }
    }
    incident.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(incident.size());
    for (EdgeRef edge : incident) {
      out.add(edgeEntry(edge, edges.get(edge)));
    }
    return out;
  }

  /** 一条边的视图：{@code {edge:"a|b", pathways:["river","road"]}}，pathways 按字典序。 */
  private static Map<String, Object> edgeEntry(EdgeRef edge, EdgeTags tags) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("edge", edge.toString());
    List<String> pathways = new ArrayList<>(tags.byPathway().keySet());
    pathways.sort(Comparator.naturalOrder());
    entry.put("pathways", pathways);
    return entry;
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

  /**
   * 区域名的**锚点**（U2 设计 §二）：取区域 hex 集合的质心（{@code round(Σq/n), round(Σr/n)}）。
   *
   * <p>★ 为什么在服务端算：overview 已是块级载荷，逐区域 hex 会让客户端再拉 N+1 次 + MB 级 payload（M9 教训）。 质心只是两个整数。{@code
   * hexes} 为空 ⇒ {@code null}（不编造锚点）。{@code long} 累加避免溢出。 质心**可能落在区域外**（带洞 / 细长区域）——它只是标签锚点，与
   * GSimulator 同口径。
   */
  private static Map<String, Object> regionLabelHex(Region region) {
    Set<HexCoord> hexes = region.hexes();
    if (hexes.isEmpty()) {
      return null;
    }
    long sumQ = 0L;
    long sumR = 0L;
    for (HexCoord hex : hexes) {
      sumQ += hex.q();
      sumR += hex.r();
    }
    int n = hexes.size();
    return hexCoord(
        new HexCoord((int) Math.round((double) sumQ / n), (int) Math.round((double) sumR / n)));
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

  /**
   * 决策人列表（T5，spec §六.1）：{@code [{id,affiliation,allowedTools,cadence,accessLimit,due}…]}。
   *
   * <p>★ 列表顺序**由服务端决定**（{@link SdQueryService#listDecisionMakers} 按 id 字典序）——本类只做装配，不重排，
   * 否则"同状态两次响应逐字节相同"这条前提会破。
   */
  static List<Map<String, Object>> decisionMakers(List<SdQueryService.DecisionMakerInfo> infos) {
    List<Map<String, Object>> out = new ArrayList<>(infos.size());
    for (SdQueryService.DecisionMakerInfo info : infos) {
      out.add(decisionMaker(info));
    }
    return out;
  }

  /**
   * 单个决策人视图（T5，D13：**复用现有字段，不新增 sd 数据**）。
   *
   * <p>字段：{@code id} / {@code affiliation}（kind + id + 解析出的显示名 + 国家 id + 军队的根单位）/ {@code
   * allowedTools} / {@code cadence} / {@code accessLimit} 摘要 / {@code due} / {@code
   * lastDirectiveTick} / {@code ticksSinceLast}。
   *
   * <p>★ **T9 起 {@code due} 是真值**（D7 公式，由 {@link SdQueryService#listDecisionMakers} / {@link
   * SdQueryService#decisionMaker} 算出），不再有占位：首次（无任何 Directive）⇒ {@code true}， {@code
   * lastDirectiveTick}/{@code ticksSinceLast} 为 {@code null}。
   */
  static Map<String, Object> decisionMaker(SdQueryService.DecisionMakerInfo info) {
    DecisionMaker maker = info.maker();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", maker.id().value());
    Map<String, Object> affiliation = new LinkedHashMap<>();
    affiliation.put("kind", info.affiliationKind());
    affiliation.put("id", info.affiliationId());
    affiliation.put("displayName", info.displayName());
    affiliation.put("nationId", info.nationId());
    affiliation.put("rootUnit", info.rootUnit());
    view.put("affiliation", affiliation);
    view.put("allowedTools", sorted(maker.allowedTools()));
    view.put("cadence", maker.decisionCadenceTicks());
    // M11：绑定的 LLM provider 引用（null = 未绑定，不拿空串顶替）。providerId 是不透明 id，sd 不解释其内容。
    view.put("providerId", maker.providerId().orElse(null));
    view.put("accessLimit", accessLimit(maker.accessLimit()));
    // ★ 会话世代与派生出的会话 id（世界事实 → 纯函数派生，见 DecisionAgentRunner.conversationIdOf）：
    //   GM 据此知道"这个人换过几次会话"。派生**走那个唯一拼写点**，不在这里另拼一份（拼错了不报错、只失忆）。
    view.put("conversationGeneration", maker.conversationGeneration());
    view.put("conversationId", DecisionAgentRunner.conversationIdOf(maker));
    SdQueryService.PendingSignal pending = info.pending();
    view.put("due", pending.due());
    view.put("lastDirectiveTick", pending.lastDirectiveTick());
    view.put("ticksSinceLast", pending.ticksSinceLast());
    return view;
  }

  /**
   * 决策记录列表（{@code GET /api/sd/directives}）：每条 = 一条真 {@link Directive} + 取回的执行原文。
   *
   * <p>★ **顺序由服务端决定**（{@link SdQueryService#listDirectives} 按 tick 降序、同 tick 按 id 字典序）——本类只做
   * 装配，不重排，否则"同状态两次响应逐字节相同"这条前提会破（与 {@link #decisionMakers} 同口径）。
   */
  static List<Map<String, Object>> directives(List<SdQueryService.DirectiveInfo> infos) {
    List<Map<String, Object>> out = new ArrayList<>(infos.size());
    for (SdQueryService.DirectiveInfo info : infos) {
      out.add(directive(info));
    }
    return out;
  }

  /**
   * 单条决策记录视图（字段名**以 sd 域的既有类型为准**，不另起名）： {@code directiveId} / {@code decisionMakerId} / {@code
   * tick} / {@code target} / {@code intentInfoKey} / {@code intentInfo} / {@code commands} / {@code
   * effects} / {@code verdict} / {@code status}。
   *
   * <p>★ {@code target} 是 {@link Address#canonical()}（{@code Directive.target} 是 {@code
   * Optional<Address>}； 无目标 ⇒ {@code null}，**不拿空串顶替**）。
   *
   * <p>★ **{@code intentInfoKey} 与 {@code intentInfo} 两样都报**：前者是 record 里真正存着的东西（INFO 覆盖层的
   * key），后者是照它取回来的原文。取不到原文而只报 key，界面就只能显示一个 key 名（"决策内容看不见"的老问题换个形状回来）。
   */
  static Map<String, Object> directive(SdQueryService.DirectiveInfo info) {
    Directive directive = info.directive();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("directiveId", directive.id().value());
    view.put("decisionMakerId", directive.decisionMakerId().value());
    view.put("tick", directive.tick());
    view.put("target", directive.target().map(Address::canonical).orElse(null));
    view.put("intentInfoKey", directive.intentInfoKey());
    view.put("intentInfo", info.intentInfo());
    List<Map<String, Object>> commands = new ArrayList<>(directive.commands().size());
    for (DirectiveCommand command : directive.commands()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", command.type());
      row.put("payloadJson", command.payloadJson());
      commands.add(row);
    }
    view.put("commands", commands);
    List<String> effects = new ArrayList<>(directive.effects().size());
    for (EffectId effect : directive.effects()) {
      effects.add(effect.value());
    }
    effects.sort(null); // 响应字节可复现（Set 的迭代序不是内容的纯函数）
    view.put("effects", effects);
    view.put("verdict", directive.verdict().map(VerdictId::value).orElse(null));
    view.put("status", directive.status().name());
    return view;
  }

  /**
   * {@code accessLimit} 摘要（T9，原 {@code viewScope} 摘要）：GM 配的**额外限制**本身。
   *
   * <p>★★ **不再"把限制冒充成可见集合"**：旧摘要是 {@code visibleRegions}/{@code visibleHexes} 的**计数**——那时 GM
   * 存的就是绝对可见集合。新语义下真正可见的是 **app 层范围函数现算 ∩ 本限制**（spec §3.1/§4.2），而这个端点**算不出**它 （要读地图与单位状态）。⇒
   * 这里只如实报"限制说了什么"：每个命名空间的前缀**条数**（真档 59223 hex 下前缀可几十条，故给计数而非全量， 与左栏的用途相称）+ 字段级剔除 +
   * 判决披露档。要看**算出来的**范围请走 {@code /api/map/overview?as=<dm>}（那是同一套判定器的输出）。
   */
  private static Map<String, Object> accessLimit(AccessLimit limit) {
    Map<String, Object> view = new LinkedHashMap<>();
    Map<String, Object> counts = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : limit.prefixesByNamespace().entrySet()) {
      counts.put(entry.getKey(), entry.getValue().size());
    }
    view.put("prefixesByNamespace", counts);
    view.put("redactedFields", sorted(limit.redactedFields()));
    view.put("adjudicationDisclosure", limit.adjudicationDisclosure().name());
    return view;
  }

  /**
   * 决策人的**现算可见范围**视图：{@code GET /api/sd/decision-makers/{id}/scope} 的体。
   *
   * <p>★★ **与 {@link #accessLimit} 摘要的分工**：那个报"GM 配了什么"（限制原文的计数），这个报"**算出来收到了多少**"
   * ——限制是**交集**的一半，另一半是范围函数（随世界状态变：国家圈地、军队移动）。GM 要判断"我这条限制管不管用"， 只有这一个端点能回答。
   *
   * <p>★ {@code affiliation} 直接复用 {@link #decisionMaker} 的那一块：两级范围（国家/军队）在前端就靠它分辨， 各拼一份会分叉。
   *
   * <p>★ {@code unparsedPrefixes} **照原样送出**（不吞）：认不出形状的 {@code map} 前缀仍是生效的限制，只是投影不出区域/格 ——吞掉它会让 GM
   * 把"我没看懂"读成"没生效"。
   */
  static Map<String, Object> decisionScope(
      SdQueryService.DecisionMakerInfo info, StateRef ref, DecisionScopeView view) {
    Map<String, Object> maker = decisionMaker(info);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decisionMakerId", maker.get("id"));
    body.put("affiliation", maker.get("affiliation"));
    body.put("branch", ref.branch().value());
    body.put("revision", ref.revision().value());
    body.put("visible", visibleScope(view));
    body.put("namespaces", namespaceSummaries(view));
    body.put("unparsedPrefixes", view.unparsedPrefixes());
    return body;
  }

  /** 范围里"看得见什么"（区域 / 格 / 边界 / 单位）——全是**解码好的**事实，前端不再自己算。 */
  private static Map<String, Object> visibleScope(DecisionScopeView view) {
    Map<String, Object> visible = new LinkedHashMap<>();
    List<String> regionIds = new ArrayList<>();
    for (RegionId id : view.regions()) {
      regionIds.add(id.value());
    }
    visible.put("regionIds", regionIds);
    List<List<Integer>> hexes = new ArrayList<>();
    for (HexCoord coord : view.hexes()) {
      hexes.add(List.of(coord.q(), coord.r()));
    }
    visible.put("hexes", hexes);
    visible.put("hexCount", view.hexCount());
    visible.put("offMapHexCount", view.offMapHexCount());
    List<String> unitIds = new ArrayList<>();
    for (UnitId id : view.units()) {
      unitIds.add(id.value());
    }
    visible.put("unitIds", unitIds);
    visible.put(
        "boundingBox",
        view.boundingBox()
            .map(
                box -> {
                  Map<String, Object> out = new LinkedHashMap<>();
                  out.put("minQ", box.minQ());
                  out.put("maxQ", box.maxQ());
                  out.put("minR", box.minR());
                  out.put("maxR", box.maxR());
                  return (Object) out;
                })
            .orElse(null));
    return visible;
  }

  /** 各命名空间的前缀摘要（含"不限"标记）——GM 据此看"限制原文长什么样"。 */
  private static Map<String, Object> namespaceSummaries(DecisionScopeView view) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, DecisionScopeView.NamespaceSummary> entry :
        view.namespaces().entrySet()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("unrestricted", entry.getValue().unrestricted());
      item.put("prefixCount", entry.getValue().prefixes().size());
      item.put("prefixes", entry.getValue().prefixes());
      out.put(entry.getKey(), item);
    }
    return out;
  }

  /**
   * 集合型字段的**字典序**副本：{@code allowedTools}/{@code redactedFields} 在状态里是 {@link java.util.Set}
   * （语义无序，其迭代序不是内容的纯函数——M2 Task 5 实测）⇒ 进响应前排序，让"同一状态两次响应逐字节相同"跨 JVM 也成立。
   */
  private static List<String> sorted(Set<String> values) {
    List<String> out = new ArrayList<>(values);
    out.sort(Comparator.naturalOrder());
    return out;
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
