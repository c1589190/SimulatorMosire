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
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
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
import io.mosire.simos.util.identity.ResolvedSubject;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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

  /** 地图/区域/单格资源所属的命名空间（{@code ToolSupport} 的路径助手与范围函数共用这一处定义）。 */
  public static final String MAP_NAMESPACE = "map";

  /** 人口资源所属的命名空间（与 {@link #SOCIAL_READ} 的声明同源）。 */
  public static final String SOCIAL_NAMESPACE = "social";

  /** 单位资源所属的命名空间（与 {@link #UNIT_READ} 的声明同源）。 */
  public static final String UNIT_NAMESPACE = "unit";

  /**
   * sd 域资源所属的命名空间（{@code sd.<kind>/<id>}）。
   *
   * <p>★ 今天**没有工具在 {@code ResourceManifest} 里声明它**（sd 窄写走的是 {@link #ALL_WRITE} 的 map/unit/social）——
   * 它存在的理由是**权限表态**：GM 组的可达面按四个命名空间逐条表态（{@link io.mosire.simos.app.Shell#gmCaller()}），
   * 第五个命名空间出现时那条表态会与这里对不上。
   */
  public static final String SD_NAMESPACE = "sd";

  /** 读工具的资源声明（spec §7.1：map+soc+unit READ_ONLY）。 */
  public static final ResourceManifest ALL_READ =
      ResourceManifest.of(
          Map.of(
              MAP_NAMESPACE, ResourcePolicy.READ_ONLY,
              SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY,
              UNIT_NAMESPACE, ResourcePolicy.READ_ONLY));

  /** 单命名空间读声明（map.overview / map.hex）。 */
  public static final ResourceManifest MAP_READ =
      ResourceManifest.of(MAP_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 单命名空间读声明（unit.list / unit.get）。 */
  public static final ResourceManifest UNIT_READ =
      ResourceManifest.of(UNIT_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 单命名空间读声明（social.population）。 */
  public static final ResourceManifest SOCIAL_READ =
      ResourceManifest.of(SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 写工具的资源声明（spec §7.1：三命名空间 UNRESTRICTED）。 */
  public static final ResourceManifest ALL_WRITE =
      ResourceManifest.of(
          Map.of(
              MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  // ── 资源路径语法（spec §3.3；AgentLib 对领域实体一无所知，故此处是唯一定义点）──────────────
  //
  // ★ **为什么路径规划成"区域级"而不是"逐 hex"**：`ResourceScope` 的前缀是**段边界**匹配，
  // 且真档有 59223 个 hex ⇒ 逐格前缀不可行；国家决策人的范围因此按**区域**组织（几条到几十条）。
  // ★ 本仓的调用点一律**引用这些助手**，不各自拼串（拼错一段不会抛、只会静默放宽/收紧权限）。

  /** 区域资源：{@code map:<mapId>/region/<regionId>}。 */
  public static ResourceId resourceRegion(String mapId, String regionId) {
    return ResourceId.of(MAP_NAMESPACE, mapId + "/region/" + regionId);
  }

  /** 单格资源：{@code map:<mapId>/hex/<q>_<r>}（坐标分隔符是**下划线**，与 {@link #canonicalHex} 同源）。 */
  public static ResourceId resourceHex(String mapId, int q, int r) {
    return ResourceId.of(MAP_NAMESPACE, mapId + "/hex/" + q + "_" + r);
  }

  /** 单位资源：{@code unit:<unitId>}（无子路径——单位本身就是资源）。 */
  public static ResourceId resourceUnit(String unitId) {
    return ResourceId.of(UNIT_NAMESPACE, unitId);
  }

  /**
   * 人口资源：{@code social:<q>_<r>}（spec §3.3 的 social 路径语法，**不带 mapId**——人口按格取，地图只有一张）。
   *
   * <p>★ 与 {@link #resourceHex} 是**同一个格**的两种货币：地图维的格用于"这块地形/这些 facet 你看不看得见"，
   * 人口维的格用于"这格的人口读数你看不看得见"。二者的可见集由范围函数从**同一组格**派生（spec §3.2）， 故"同格口径"在两侧同时成立。
   */
  public static ResourceId resourceSocial(int q, int r) {
    return ResourceId.of(SOCIAL_NAMESPACE, q + "_" + r);
  }

  /** sd 资源：{@code sd:<kind>/<id>}（kind ∈ decision-maker / nation / army / combat，spec §3.3）。 */
  public static ResourceId resourceSd(String kind, String id) {
    return ResourceId.of(SD_NAMESPACE, kind + "/" + id);
  }

  // ── 资源断言（AgentTool 契约：真正读写前调 require，工具只调不判）──────────────────────
  //
  // ★ **T10 起不再有"粗断言"**：`map:<mapId>` / `unit:"*"` / `social:"*"` 这三种写法在**受限调用者**身上
  // 一律判否（`ResourceScope` 是段边界前缀匹配 ⇒ `Map1/region/701` 不含 `Map1`），于是"粗断言 + 细围栏 =
  // **整调被拒**"（spec §5.2 第 2 条）——决策人曾因此**连一条读工具都过不去**（T5-T8 实测）。
  // ⇒ 读侧一律改成：**逐项 `allows` 筛**（部分可见）；要"整调拒"的地方用 `require` + **细粒度**资源。

  /** 通用写（三个命名空间的粗断言）：{@code simos.command.submit} / {@code simos.advance} 与窄写基类的缺省声明。 */
  public static List<ResourceId> allWriteResources(String mapId) {
    return List.of(
        ResourceId.of(MAP_NAMESPACE, mapId),
        ResourceId.of(UNIT_NAMESPACE, "*"),
        ResourceId.of(SOCIAL_NAMESPACE, "*"));
  }

  public static void requireAllWrite(ToolContext context, String mapId) {
    requireAll(context, Operation.WRITE, allWriteResources(mapId));
  }

  /** 逐条断言（细粒度资源的统一入口；`allows`/`require` 只在这一处成对出现）。 */
  public static void requireAll(
      ToolContext context, Operation operation, List<ResourceId> resources) {
    for (ResourceId id : resources) {
      context.resources().require(operation, id);
    }
  }

  // ── 可见性判定（T10，"部分可见"的筛在工具里：AgentLib 只给断言原语，C2）─────────────────
  //
  // ★ 为什么用 `allows` 而不全用 `require`：`require` 是**整调拒**（抛异常 ⇒ 整个工具调用失败），而读侧要的是
  // **部分可见**（读工具一次覆盖一片：越界的那几项不进结果，剩下的照给）。spec §5.2 第 1 条要的"资源维不是装饰"，
  // 靠的正是这两条路各自有判别力用例。

  /** 某区域是否可见：{@code map:<mapId>/region/<rid>}。 */
  public static boolean regionVisible(ToolContext context, String mapId, RegionId regionId) {
    return context.resources().allows(Operation.READ, resourceRegion(mapId, regionId.value()));
  }

  /** 某单位是否可见：{@code unit:<unitId>}。 */
  public static boolean unitVisible(ToolContext context, UnitId unitId) {
    return context.resources().allows(Operation.READ, resourceUnit(unitId.value()));
  }

  /** 某格的人口是否可见：{@code social:<q>_<r>}（见 {@link #resourceSocial}）。 */
  public static boolean populationVisible(ToolContext context, HexCoord coord) {
    return context.resources().allows(Operation.READ, resourceSocial(coord.q(), coord.r()));
  }

  /**
   * 某格是否可见 —— ★★ **两条通道取并集，这是本任务最容易配错的一处**：
   *
   * <ol>
   *   <li>该格**自身**的资源 {@code map:<mapId>/hex/<q>_<r>}（军队决策人的范围就是这种逐格前缀）；
   *   <li>该格所属的**某个区域**的资源 {@code map:<mapId>/region/<rid>}（国家决策人的范围是**区域级**前缀 —— spec §3.3 硬要求
   *       1：真档 59223 hex ⇒ 国家范围只能按区域组织，**不逐格枚举**）。
   * </ol>
   *
   * <p>★ **只看 ①** 会让国家决策人**连本国的格都读不到**（区域级前缀不含 hex 路径）；**只看 ②** 会让军队决策人
   * 读不到自己的视野格。两条通道各自落在自己的判据上（`DecisionMakerScopeEndToEndTest` 两类决策人各一条）。
   *
   * <p>★ 并集**不放大权限**：两条通道各自都是调用者可达面内的资源（`allows` 逐条判过），不是"绕过判定的后门"。
   */
  public static boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord) {
    if (context.resources().allows(Operation.READ, resourceHex(mapId, coord.q(), coord.r()))) {
      return true;
    }
    for (RegionId owner : map.regionIndex().regionOf(coord)) {
      if (regionVisible(context, mapId, owner)) {
        return true;
      }
    }
    return false;
  }

  /**
   * "解析出的主体"是否可见（{@code simos.state.resolve} / {@code simos.state.facets} 的筛依据）。
   *
   * <p>★ **按主体的命名空间映射到资源**（spec §3.3 的路径语法）。映射不出来的形态一律**不可见**（fail-closed）—— "判不了就放行"正是本轮要消灭的形态。
   *
   * <p>★ 各命名空间的 localId 形态来自各自的 resolver（实测）：{@code map} → mapId（根主体）、{@code map.hex} → {@code
   * q_r}、{@code map.region} → 区域 id、{@code map.city} → 城市 id、{@code social}/{@code social.hex} →
   * mapId / {@code q_r}、{@code unit} → 单位 id、{@code sd} → {@code <kind>.<name>}。
   */
  public static boolean subjectVisible(
      ToolContext context, String mapId, GameMap map, ResolvedSubject subject) {
    String namespace = subject.id().namespace();
    String localId = subject.id().localId();
    return switch (namespace) {
      case "map" ->
          context.resources().allows(Operation.READ, ResourceId.of(MAP_NAMESPACE, localId));
      case "map.hex" -> hexVisible(context, mapId, map, HexCoord.parse(localId));
      case "map.region" -> regionVisible(context, mapId, RegionId.parse(localId));
      case "map.city" ->
          cityHex(map, localId).map(c -> hexVisible(context, mapId, map, c)).orElse(false);
      case "social", "social.hex" ->
          context.resources().allows(Operation.READ, ResourceId.of(SOCIAL_NAMESPACE, localId));
      case "unit" -> unitVisible(context, new UnitId(localId));
      case "sd" -> sdVisible(context, localId);
      default -> false;
    };
  }

  /** 城市的格（{@code map.city} 主体没有自己的资源路径 ⇒ 按它所在的那一格判，与 `map.overview` 的 cities 同口径）。 */
  private static Optional<HexCoord> cityHex(GameMap map, String cityId) {
    City city = map.cities().get(CityId.parse(cityId));
    return city == null ? Optional.empty() : Optional.of(city.at());
  }

  /** {@code sd:<kind>/<id>}（localId 形如 {@code nation.FRA}、含子路径时取首段为 kind）。 */
  private static boolean sdVisible(ToolContext context, String localId) {
    int dot = localId.indexOf('.');
    if (dot <= 0 || dot == localId.length() - 1) {
      return false;
    }
    return context
        .resources()
        .allows(Operation.READ, resourceSd(localId.substring(0, dot), localId.substring(dot + 1)));
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

  /** 不过滤的总览（既有调用点：{@code RedactingQueryService} 自己的脱敏路径）。 */
  public static Map<String, Object> mapOverview(String mapId, GameMap map) {
    return mapOverview(mapId, map, any -> true, any -> true);
  }

  /**
   * **按可见性过滤**的总览（T10）：逐项判可见性，**越界的不进结果**（不是整调拒）。
   *
   * <p>★ 口径：格与区域各按**自己的**资源判；城市没有自己的资源路径（spec §3.3 未定义 city 前缀）⇒ 按它**所在的那一格**判。 {@code hexCount}
   * 也取**可见格数**——回全量会连"地图多大"一起泄露。{@code terrainTypes} 是词表（不是逐格事实），原样给。
   */
  public static Map<String, Object> mapOverview(
      String mapId,
      GameMap map,
      Predicate<HexCoord> hexVisible,
      Predicate<RegionId> regionVisible) {
    return mapOverview(mapId, map, hexVisible, regionVisible, Optional.empty());
  }

  /**
   * **带派生信息**的总览（T11，spec §3.4 的字段级可见性）：
   *
   * <ul>
   *   <li>{@code neighbors}（邻国标识）——**只有"本国"存在时才给**（{@code Optional} 空 ⇒ 整个字段不出现）。 调用方（{@code
   *       MapOverviewTool}）从**调用者身份**解出本国；解不出来（GM / 军队决策人 / 认不出身份的调用者）就**不给**这一项 ——
   *       fail-closed：判不了"你是谁"就不给派生信息，而不是"反正没害处"地给一份。
   * </ul>
   *
   * <p>★ **只加这一项、不逐格加 {@code nation}**：总览在 GM 眼里是**整张真图**（19441 格），逐格加派生字段会把 M9 辛苦压下来的 载荷重新吹大（M9 实测
   * overview 227,377 → 78,648 B）。逐格的归属国家归 {@code map.hex}（一次一格，见 {@code MapHexTool}）。
   */
  public static Map<String, Object> mapOverview(
      String mapId,
      GameMap map,
      Predicate<HexCoord> hexVisible,
      Predicate<RegionId> regionVisible,
      Optional<Set<String>> neighbors) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("mapId", mapId);

    List<Map<String, Object>> hexes = new ArrayList<>();
    Map<HexCoord, String> terrainIndex = map.terrainIndex();
    for (Map.Entry<HexCoord, HexCell> entry : map.hexes().entrySet()) {
      if (!hexVisible.test(entry.getKey())) {
        continue;
      }
      Map<String, Object> hex = hexCoord(entry.getKey());
      hex.put("terrain", terrainIndex.get(entry.getKey()));
      hex.put("height", entry.getValue().height());
      hexes.add(hex);
    }
    view.put("hexCount", hexes.size());
    view.put("hexes", hexes);

    List<Map<String, Object>> regions = new ArrayList<>();
    for (Region region : map.regions().values()) {
      if (!regionVisible.test(region.id())) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", region.id().value());
      item.put("name", region.name());
      item.put("hexCount", region.hexes().size());
      regions.add(item);
    }
    view.put("regions", regions);

    List<Map<String, Object>> cities = new ArrayList<>();
    for (City city : map.cities().values()) {
      if (!hexVisible.test(city.at())) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", city.id().value());
      item.put("name", city.name());
      item.put("at", hexCoord(city.at()));
      item.put("region", city.region() == null ? null : city.region().value());
      cities.add(item);
    }
    view.put("cities", cities);
    view.put("terrainTypes", new ArrayList<>(map.terrainTypes().keySet()));
    // ★ 邻国：**字段缺席**（不是空列表）——"你不是国家决策人"与"你没有邻国"是两件事，
    //   用空列表会把后者当成唯一解释（spec §3.4：这一项是**国家决策人**才有的字段）。
    neighbors.ifPresent(value -> view.put("neighbors", new ArrayList<>(value)));
    return view;
  }

  public static List<Map<String, Object>> units(UnitState units, SimosTimestamp at, GameMap map) {
    return units(units, at, map, any -> true);
  }

  /** **按可见性过滤**的单位清单（T10）：越界的单位不进结果（与 {@code unit.get} 的"不可见 ⇒ NOT_FOUND"同一口径）。 */
  public static List<Map<String, Object>> units(
      UnitState units, SimosTimestamp at, GameMap map, Predicate<UnitId> unitVisible) {
    List<Map<String, Object>> out = new ArrayList<>(units.units().size());
    for (Unit unit : units.units().values()) {
      if (!unitVisible.test(unit.id())) {
        continue;
      }
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

  /**
   * sd 切片（T11）：视图层要按**调用者的决策人身份**决定给不给派生信息（邻国），而决策人住在 sd 切片里。
   *
   * <p>★ **缺切片/类型不对 = 装配故障，当场炸**（与上面三个同口径，T6 裁定："装配故障当场炸不静默兜底"）： sd 切片对可推进世界是必需的（{@code DemoWorld}
   * 与各 app 夹具都补了它），静默返回空会把装配错误伪装成"这个决策人没有归属"。
   */
  public static SdState sdState(SimulationState state) {
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalArgumentException("状态里没有 sd 模块切片——装配故障"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalArgumentException("sd 模块切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }
}
