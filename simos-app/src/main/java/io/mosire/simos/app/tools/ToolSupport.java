package io.mosire.simos.app.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.City;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
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
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具集的共享助手（M5 T5）：参数解析、资源断言、领域视图与 {@link ToolResult} 折叠。
 *
 * <p>★ **不重算领域语义**：位置一律走 {@link UnitState#effectivePosition(UnitId, SimosTimestamp)}、人口一律走 {@link
 * PopulationSeries#valueAt(SimosTimestamp)}——与 {@code ApiViews}/facet 同口径，工具面不造第二份真相。
 *
 * <p>★ **R1 的扫描对象之一**：本类不 import 任何 store/timeline 类型，写面只有调用方传进来的 {@link
 * io.mosire.simos.core.CoreSimos#submit}。
 */
public final class ToolSupport {

  private ToolSupport() {}

  /** 工具面唯一的 JSON 出口（与 GUI/领域同源，ADR 的口径：不新搓第二台）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** 查询/写入的缺省分支（spec §8.2 的 GUI 同款；工具入参可覆盖）。 */
  public static final String DEFAULT_BRANCH = "main";

  /** 读工具的资源声明（spec §7.1：map+soc+unit READ_ONLY）。 */
  public static final ResourceManifest ALL_READ =
      ResourceManifest.of(
          Map.of(
              "map", ResourcePolicy.READ_ONLY,
              "social", ResourcePolicy.READ_ONLY,
              "unit", ResourcePolicy.READ_ONLY));

  /** 单命名空间读声明（map.overview / map.hex）。 */
  public static final ResourceManifest MAP_READ =
      ResourceManifest.of("map", ResourcePolicy.READ_ONLY);

  /** 单命名空间读声明（unit.list / unit.get）。 */
  public static final ResourceManifest UNIT_READ =
      ResourceManifest.of("unit", ResourcePolicy.READ_ONLY);

  /** 单命名空间读声明（social.population）。 */
  public static final ResourceManifest SOCIAL_READ =
      ResourceManifest.of("social", ResourcePolicy.READ_ONLY);

  /** 写工具的资源声明（spec §7.1：三命名空间 UNRESTRICTED）。 */
  public static final ResourceManifest ALL_WRITE =
      ResourceManifest.of(
          Map.of(
              "map", ResourcePolicy.UNRESTRICTED,
              "social", ResourcePolicy.UNRESTRICTED,
              "unit", ResourcePolicy.UNRESTRICTED));

  // ── 资源断言（AgentTool 契约：真正读写前调 require，工具只调不判）──────────────────────

  public static void requireMapRead(ToolContext context, String mapId) {
    context.resources().require(Operation.READ, ResourceId.of("map", mapId));
  }

  public static void requireUnitRead(ToolContext context) {
    context.resources().require(Operation.READ, ResourceId.of("unit", "*"));
  }

  public static void requireSocialRead(ToolContext context) {
    context.resources().require(Operation.READ, ResourceId.of("social", "*"));
  }

  public static void requireAllRead(ToolContext context, String mapId) {
    requireMapRead(context, mapId);
    requireUnitRead(context);
    requireSocialRead(context);
  }

  public static void requireAllWrite(ToolContext context, String mapId) {
    context.resources().require(Operation.WRITE, ResourceId.of("map", mapId));
    context.resources().require(Operation.WRITE, ResourceId.of("unit", "*"));
    context.resources().require(Operation.WRITE, ResourceId.of("social", "*"));
  }

  // ── 参数解析（模型给的 JSON 参数；坏输入折 ToolResult.error，不抛给管线）────────────────

  public static String requiredText(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为非空文本");
    }
    return text;
  }

  public static String optionalText(Map<String, Object> args, String name, String fallback) {
    Object value = args.get(name);
    if (value == null) {
      return fallback;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是非空文本");
    }
    return text;
  }

  public static long requiredLong(Map<String, Object> args, String name) {
    Long value = optionalLong(args, name);
    if (value == null) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为整数");
    }
    return value;
  }

  public static Long optionalLong(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + text);
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是整数");
  }

  public static boolean has(Map<String, Object> args, String name) {
    return args.get(name) != null;
  }

  // ── JSON Schema 小件（工具定义给模型看）──────────────────────────────────────────────

  public static Map<String, Object> prop(String type, String description) {
    Map<String, Object> property = new LinkedHashMap<>();
    property.put("type", type);
    property.put("description", description);
    return property;
  }

  /** 查/写共用的 {branch, revision} 两个可选属性。 */
  public static Map<String, Object> targetProps() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", prop("string", "分支名（缺省 " + DEFAULT_BRANCH + "）"));
    props.put("revision", prop("integer", "版本号（缺省 = 该分支 head）"));
    return props;
  }

  public static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    return schema;
  }

  /** 查询坐标：{@code branch} 缺省 {@value #DEFAULT_BRANCH}；{@code revision} 缺省 = head。 */
  public static QueryTarget target(Map<String, Object> args, String defaultBranch) {
    BranchId branch = new BranchId(optionalText(args, "branch", defaultBranch));
    Long revision = optionalLong(args, "revision");
    return revision == null
        ? QueryTarget.head(branch)
        : QueryTarget.at(branch, new RevisionId(revision));
  }

  /**
   * canonical {@code map:<mapId>:hex.<q>_<r>}——**只能用 Address AST 造**（T3 的硬接缝，spec §5.2 + 裁定 58）：
   * facet 只服务 canonical 主体，且 {@code QueryService.facets} 不改写转交的地址。
   */
  public static String canonicalHex(String mapId, int q, int r) {
    Address address =
        new Address(List.of(new Namespace("map"), Entity.of(mapId), Entity.of("hex", q + "_" + r)));
    return address.canonical();
  }

  // ── 结果折叠 ────────────────────────────────────────────────────────────────────────

  public static String json(Object view) {
    try {
      return MAPPER.writeValueAsString(view);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON 序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  public static ToolResult ok(Object view) {
    return ToolResult.ok(json(view));
  }

  /**
   * {@link CommandResult} 三结局的统一折叠（spec §7.1）：{@code Committed → ok}（带新坐标）， {@code
   * Conflict/Rejected → error}（带真实 head / 理由）。
   */
  public static ToolResult fold(CommandResult result, String commandId, String correlationId) {
    return switch (result) {
      case CommandResult.Committed committed ->
          ToolResult.ok(json(committedView(committed.ref(), commandId, correlationId)));
      case CommandResult.Conflict conflict ->
          ToolResult.error(
              "CONFLICT", json(conflictView(conflict.current(), commandId, correlationId)));
      case CommandResult.Rejected rejected ->
          ToolResult.error(
              "REJECTED", json(rejectedView(rejected.reason(), commandId, correlationId)));
    };
  }

  private static Map<String, Object> committedView(
      StateRef ref, String commandId, String correlationId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "committed");
    view.put("ref", stateRef(ref));
    view.put("commandId", commandId);
    view.put("correlationId", correlationId);
    return view;
  }

  private static Map<String, Object> conflictView(
      StateRef current, String commandId, String correlationId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "conflict");
    view.put("current", stateRef(current));
    view.put("commandId", commandId);
    view.put("correlationId", correlationId);
    return view;
  }

  private static Map<String, Object> rejectedView(
      String reason, String commandId, String correlationId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "rejected");
    view.put("reason", reason);
    view.put("commandId", commandId);
    view.put("correlationId", correlationId);
    return view;
  }

  // ── 视图装配（只读纯函数）──────────────────────────────────────────────────────────

  public static Map<String, Object> hexCoord(HexCoord coord) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", coord.q());
    view.put("r", coord.r());
    return view;
  }

  public static Map<String, Object> stateRef(StateRef ref) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", ref.branch().value());
    view.put("revision", ref.revision().value());
    return view;
  }

  public static Map<String, Object> timestamp(SimosTimestamp at) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tick", at.tick());
    view.put("calendarLabel", at.calendarLabel().orElse(null));
    return view;
  }

  public static Map<String, Object> mapOverview(String mapId, GameMap map) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("mapId", mapId);
    view.put("hexCount", map.hexes().size());

    List<Map<String, Object>> hexes = new ArrayList<>(map.hexes().size());
    Map<HexCoord, String> terrainIndex = map.terrainIndex();
    for (Map.Entry<HexCoord, HexCell> entry : map.hexes().entrySet()) {
      Map<String, Object> hex = hexCoord(entry.getKey());
      hex.put("terrain", terrainIndex.get(entry.getKey()));
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

  public static List<Map<String, Object>> units(UnitState units, SimosTimestamp at, GameMap map) {
    List<Map<String, Object>> out = new ArrayList<>(units.units().size());
    for (Unit unit : units.units().values()) {
      out.add(unit(unit, units, at, map));
    }
    return out;
  }

  public static Map<String, Object> unit(
      Unit unit, UnitState units, SimosTimestamp at, GameMap map) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", unit.id().value());
    view.put("name", unit.name());
    view.put("member", unit.member());
    view.put("equipment", new LinkedHashMap<>(unit.equipment()));
    view.put("speed", unit.speed());
    view.put("mobilityPerMille", unit.mobilityPerMille());
    view.put("parent", unit.parent().valueAt(at).map(UnitId::value).orElse(null));
    view.put(
        "position", units.effectivePosition(unit.id(), at).map(ToolSupport::hexCoord).orElse(null));
    view.put("movement", movement(unit, at, map));
    return view;
  }

  /**
   * 在途移动视图（M7b T2），与 GUI 面 **同形**：无路线 ⇒ {@code null}；有 ⇒ 路线 + 由 {@link UnitMoves#evaluate}
   * 现算的在途状态。工具面不重写预算/成本算法（不造第二份真相）。
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
    view.put("nextHex", state.nextHex().map(ToolSupport::hexCoord).orElse(null));
    view.put(
        "remainingMillis",
        state.remainingEdgeCostMillis().isPresent()
            ? state.remainingEdgeCostMillis().getAsLong()
            : null);
    return view;
  }

  /** 路线视图，与 GUI 面同形：{@code {waypoints:[{q,r}…], path:[{q,r}…]}}。 */
  private static Map<String, Object> route(Route route) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("waypoints", hexCoords(route.waypoints()));
    view.put("path", hexCoords(route.path()));
    return view;
  }

  private static List<Map<String, Object>> hexCoords(List<HexCoord> coords) {
    List<Map<String, Object>> out = new ArrayList<>(coords.size());
    for (HexCoord coord : coords) {
      out.add(hexCoord(coord));
    }
    return out;
  }

  public static List<Map<String, Object>> facets(List<FacetEntry> entries) {
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

  public static Map<String, Object> population(
      HexCoord coord, PopulationSeries series, SimosTimestamp at) {
    Map<String, Object> view = hexCoord(coord);
    view.put("at", timestamp(at));
    view.put("population", series.valueAt(at));
    return view;
  }

  public static GameMap gameMap(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  public static UnitState unitState(SimulationState state) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 unit 模块切片——装配故障"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  public static SocialData socialData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 social 模块切片——装配故障"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }
}
