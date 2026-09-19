package io.mosire.simos.app.gui;

import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.map.City;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
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

  /** 地图只读总览（地图形状，T9 的 Canvas 数据源）。 */
  static Map<String, Object> mapOverview(String mapId, GameMap map) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("mapId", mapId);
    view.put("hexCount", map.hexes().size());

    List<Map<String, Object>> hexes = new ArrayList<>(map.hexes().size());
    for (Map.Entry<HexCoord, HexCell> entry : map.hexes().entrySet()) {
      Map<String, Object> hex = hexCoord(entry.getKey());
      hex.put("terrain", entry.getValue().terrain());
      hex.put("height", entry.getValue().height());
      hexes.add(hex);
    }
    view.put("hexes", hexes);

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

    view.put("terrainTypes", new ArrayList<>(map.terrainTypes().keySet()));
    return view;
  }

  /**
   * 单格：格内容 + 所属区域 + **完整地形定义** + facet 汇总（facet 由调用方按 **canonical** 地址查询，spec §5.2）。
   *
   * <p>★ {@code terrainType} 由调用方从**状态里的** {@code map.terrainTypes()} 取出（spec §3.2）——本类不查 {@code
   * TerrainCatalog}，词表只有一个来源。
   */
  static Map<String, Object> mapHex(
      HexCoord coord,
      HexCell cell,
      List<FacetEntry> facets,
      RegionId region,
      TerrainType terrainType) {
    Map<String, Object> view = hexCoord(coord);
    view.put("terrain", cell.terrain());
    view.put("height", cell.height());
    view.put("region", region == null ? null : region.value());
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

  /** 单位列表（每个单位带 head 时刻的有效位置）。 */
  static List<Map<String, Object>> units(UnitState units, SimosTimestamp at) {
    List<Map<String, Object>> out = new ArrayList<>(units.units().size());
    for (Unit unit : units.units().values()) {
      out.add(unit(unit, units, at));
    }
    return out;
  }

  /** 单位详情：冻结字段 + {@code parent}（head 时刻）+ **有效位置**（head 时刻，向父取）。 */
  static Map<String, Object> unit(Unit unit, UnitState units, SimosTimestamp at) {
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
    view.put("movement", unit.movement().isPresent());
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
