package io.mosire.simos.app.gui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.command.ForkBranch;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.PathFinder;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GUI 服务器（M5 T8，spec §8.1/§8.2；★ WebUI 出形 1/2）：JDK {@link HttpServer} + **虚拟线程 executor**，同源提供静态页与
 * {@code /api/*} JSON。
 *
 * <p>★ **写面唯一**：三个写端点（{@code /api/command}、{@code /api/advance}、{@code /api/fork}）一律经 {@link
 * CoreSimos#submit}，身份 {@code initiator="player:gui"}、{@code commandId=correlationId=新 UUID}（spec
 * §九；C22 的"单命令链缺省"由调用方显式满足）。本类**不打开任何存储/时间线写面**——R1 的扫描对象。
 *
 * <p>★ **读面唯一**：所有读端点经 {@link QueryService}（每次重放，spec §5.1）。
 *
 * <p>★ **canonical 地址由 Address AST 构造**（T3 的硬接缝，spec §5.2 + 台账裁定 58）：facet 只服务 canonical {@code
 * map:<mapId>:hex.<q>_<r>}，且 {@code QueryService.facets} **不改写**转交的地址 ⇒ 本类的两个 hex 端点用 {@link
 * #canonicalHex(int, int)} 先造 canonical 主体再查 facet。
 *
 * <p>★ **审批代理（T6）**：{@code GET /api/approvals} 与 {@code POST /api/approvals/{id}} **原样透传**给
 * AgentLib 的 {@code ApprovalHttpEndpoint}（方法/路径/体照转，状态码/体/头照回；不可达 ⇒ 502）。**本类不实现审批语义**——id
 * 幂等、{@code APPROVE_SESSION} 的收窄、404/405/409 的判定全在 AgentLib 端点里；未配置 base URL ⇒ 503（仅直接构造时会遇到）。
 *
 * <p>★ **零 CORS 头**（spec §〇.4）：同源，无预检面。静态资源带 {@code Cache-Control: no-store}。
 */
public final class GuiServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(GuiServer.class);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** GUI 写命令的发起者（spec §九；R4 的行为面断言对象）。 */
  static final String GUI_INITIATOR = "player:gui";

  private static final String DEFAULT_BRANCH = "main";
  private static final String UNIT_DETAIL_PREFIX = "/api/unit/";

  /** 区域详情前缀（M7 T1，spec §3.3）：{@code /api/map/region/{id}}。 */
  private static final String REGION_DETAIL_PREFIX = "/api/map/region/";

  /**
   * 决策人只读查询面（T5，spec §六.1）：{@code /api/sd/decision-makers} 与 {@code /api/sd/decision-makers/{id}}。
   */
  private static final String DECISION_MAKERS_PATH = "/api/sd/decision-makers";

  private static final String DECISION_MAKER_DETAIL_PREFIX = DECISION_MAKERS_PATH + "/";

  /** 审批代理路径：{@code /api/approvals} 或 {@code /api/approvals/{id}}（原样转给 AgentLib 端点）。 */
  private static final String APPROVAL_PATH = "/api/approvals";

  private static final Set<String> GET_ROUTES =
      Set.of(
          "/api/state",
          "/api/resolve",
          "/api/facets",
          "/api/map/overview",
          "/api/map/hex",
          "/api/map/path",
          "/api/units",
          "/api/social/population",
          "/api/timeline",
          "/api/sd/decision-makers",
          "/api/sd/verdicts");

  private static final Set<String> POST_ROUTES =
      Set.of("/api/command", "/api/advance", "/api/fork");

  /** 判决只读面（T6，spec `C28`）：{@code GET /api/sd/verdicts[?as=<dmId>]}。 */
  private static final String VERDICTS_PATH = "/api/sd/verdicts";

  private final QueryService queryService;
  private final RedactingQueryService redactingQueryService;
  private final SdQueryService sdQueryService;
  private final CoreSimos core;
  private final String mapId;

  /** 审批面 base URL（T6 由 Shell 注入为 AgentLib 端点地址）；null / 空白 = 未配置（端点回 503）。 */
  private final String approvalBaseUrl;

  /** 审批透传用（T6）：只转发，不解释语义。 */
  private final HttpClient approvalClient;

  private final ExecutorService executor;
  private final StaticHandler staticHandler;

  private HttpServer server;
  private volatile boolean closed;

  /**
   * @param queryService 只读门面
   * @param core 唯一写入口
   * @param mapId 本世界的 map 称谓（构造 canonical hex 地址用）
   * @param approvalBaseUrl 审批面 base URL；{@code null} = T6 未接入
   */
  public GuiServer(
      QueryService queryService, CoreSimos core, String mapId, String approvalBaseUrl) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.core = Objects.requireNonNull(core, "core");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.redactingQueryService = new RedactingQueryService(queryService);
    this.sdQueryService = new SdQueryService(queryService);
    this.approvalBaseUrl = approvalBaseUrl;
    this.approvalClient = HttpClient.newHttpClient();
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.staticHandler = new StaticHandler();
  }

  /**
   * 启动并绑定。{@code port=0} ⇒ 由系统分配随机端口，实际端口经 {@link #boundPort()} 读回（spec §3.1 的测试口径）。
   *
   * @throws IllegalStateException 已启动、或绑定失败
   */
  public void start(String host, int port) {
    Objects.requireNonNull(host, "host");
    if (port < 0) {
      throw new IllegalArgumentException("port 不得为负（0 = 随机端口）: " + port);
    }
    if (server != null) {
      throw new IllegalStateException("GUI 服务器已启动，不能重复 start");
    }
    HttpServer created;
    try {
      created = HttpServer.create(new InetSocketAddress(host, port), 0);
    } catch (IOException e) {
      executor.shutdownNow();
      throw new IllegalStateException("GUI 服务器绑定失败: " + host + ":" + port, e);
    }
    created.setExecutor(executor);
    created.createContext("/", this::handle);
    created.start();
    this.server = created;
    LOG.info("GUI 服务器已启动: http://{}:{}/（静态 /webui，同源，无 CORS 头）", host, boundPort());
  }

  /** 实际绑定端口（{@code port=0} 时由 OS 分配）。 */
  public int boundPort() {
    return requireServer().getAddress().getPort();
  }

  /** 实际绑定主机（回环）。 */
  public String boundHost() {
    return requireServer().getAddress().getHostString();
  }

  private HttpServer requireServer() {
    HttpServer current = server;
    if (current == null) {
      throw new IllegalStateException("GUI 服务器尚未启动，没有可读回的端口");
    }
    return current;
  }

  /** 关闭：停监听（释放端口）+ 停执行器。幂等（spec §3.3 的关闭次序里 GUI 排第一）。 */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    HttpServer current = server;
    if (current != null) {
      current.stop(0);
      server = null;
    }
    approvalClient.close();
    executor.shutdownNow();
  }

  // ── 路由 ────────────────────────────────────────────────────────────────────────────

  private void handle(HttpExchange exchange) throws IOException {
    long startedNanos = System.nanoTime();
    try {
      String path = exchange.getRequestURI().getPath();
      if (isApprovalPath(path)) {
        proxyApproval(exchange, path);
      } else if (path.equals("/api") || path.startsWith("/api/")) {
        writeReply(exchange, handleApi(exchange, exchange.getRequestMethod(), path));
      } else if (!staticHandler.tryServe(exchange, path)) {
        writeReply(exchange, Reply.of(404, notFound(path)));
      }
    } catch (IllegalArgumentException e) {
      safeError(exchange, 400, e.getMessage());
    } catch (Exception e) {
      LOG.warn("GUI 请求处理失败: {} {}", exchange.getRequestMethod(), exchange.getRequestURI(), e);
      safeError(exchange, 500, "internal error");
    } finally {
      logAccess(exchange, startedNanos);
      exchange.close();
    }
  }

  /**
   * 一行一请求的访问日志（M10）：方法 / 路径 / 状态码 / 耗时 / 远端。**运维盲区**（此前本类零访问日志）的落点。
   *
   * <p>★ **密钥纪律**：只取 {@link URI#getPath()}，**不带查询串**（查询参数理论上可能含敏感值）；请求体一概不读、不进日志。 状态码取 {@link
   * HttpExchange#getResponseCode()}（响应头发出后才有值；未发出时 JDK 返回 {@code -1}）。
   */
  private void logAccess(HttpExchange exchange, long startedNanos) {
    try {
      long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
      InetSocketAddress remote = exchange.getRemoteAddress();
      String remoteHost =
          (remote == null || remote.getAddress() == null)
              ? "-"
              : remote.getAddress().getHostAddress();
      LOG.info(
          "access {} {} -> {} {}ms remote={}",
          exchange.getRequestMethod(),
          exchange.getRequestURI().getPath(),
          exchange.getResponseCode(),
          millis,
          remoteHost);
    } catch (RuntimeException e) {
      // 访问日志失败不得改变响应结果（它在 finally 里；抛出会遮蔽原异常）
      LOG.debug("访问日志写出失败（不影响响应）", e);
    }
  }

  private Reply handleApi(HttpExchange exchange, String method, String path) throws IOException {
    return switch (method) {
      case "GET" -> {
        Reply reply = handleGet(exchange, path);
        yield reply != null ? reply : routeError(path);
      }
      case "POST" -> {
        Reply reply = handlePost(exchange, path);
        yield reply != null ? reply : routeError(path);
      }
      default -> routeError(path);
    };
  }

  private Reply handleGet(HttpExchange exchange, String path) throws IOException {
    Map<String, String> params = queryParams(exchange);
    String actorParam = params.get("as");
    boolean asPresent = actorParam != null && !actorParam.isBlank();
    DecisionMakerId actor = asPresent ? new DecisionMakerId(actorParam) : null;

    if (path.equals("/api/state")) {
      return redactedIfRequested(asPresent, actor, params, stateReply());
    }
    if (path.equals("/api/resolve")) {
      QueryResult result = queryService.resolve(requiredParam(params, "address"), target(params));
      return redactedIfRequested(
          asPresent,
          actor,
          params,
          Reply.of(200, Map.of("candidates", ApiViews.resolveResult(result))));
    }
    if (path.equals("/api/facets")) {
      List<FacetEntry> entries =
          queryService.facets(requiredParam(params, "address"), target(params));
      return redactedIfRequested(
          asPresent, actor, params, Reply.of(200, Map.of("entries", ApiViews.facets(entries))));
    }
    if (path.equals("/api/map/overview")) {
      if (asPresent) {
        return Reply.of(200, redactingQueryService.mapOverview(actor, target(params), mapId));
      }
      SimulationState state = queryService.stateAt(target(params));
      return Reply.of(200, ApiViews.mapOverview(mapId, ApiViews.gameMap(state)));
    }
    if (path.equals("/api/map/hex")) {
      return mapHexReply(params, actor, asPresent);
    }
    if (path.equals("/api/map/path")) {
      rejectAs(path, asPresent);
      return mapPathReply(params);
    }
    if (isRegionDetail(path)) {
      return regionReply(
          params, actor, asPresent, urlDecode(path.substring(REGION_DETAIL_PREFIX.length())));
    }
    if (path.equals("/api/timeline")) {
      return redactedIfRequested(asPresent, actor, params, timelineReply(params));
    }
    if (path.equals("/api/units")) {
      if (asPresent) {
        return Reply.of(200, Map.of("units", redactingQueryService.units(actor, target(params))));
      }
      SimulationState state = queryService.stateAt(target(params));
      UnitState units = ApiViews.unitState(state);
      return Reply.of(
          200,
          Map.of(
              "units", ApiViews.units(units, state.meta().timestamp(), ApiViews.gameMap(state))));
    }
    if (isUnitDetail(path)) {
      return unitReply(
          params, actor, asPresent, urlDecode(path.substring(UNIT_DETAIL_PREFIX.length())));
    }
    if (path.equals("/api/social/population")) {
      return populationReply(params, actor, asPresent);
    }
    if (path.equals(VERDICTS_PATH)) {
      List<Map<String, Object>> views =
          asPresent
              ? redactingQueryService.verdicts(actor, target(params))
              : redactingQueryService.verdicts(target(params));
      return Reply.of(200, Map.of("verdicts", views));
    }
    if (path.equals(DECISION_MAKERS_PATH)) {
      rejectAs(path, asPresent);
      return decisionMakersReply(params);
    }
    if (isDecisionMakerDetail(path)) {
      rejectAs(path, asPresent);
      return decisionMakerReply(
          params, urlDecode(path.substring(DECISION_MAKER_DETAIL_PREFIX.length())));
    }
    return null;
  }

  /**
   * 未接 redaction 的读端点：带 {@code as=} ⇒ **显式拒绝**（fail-closed）。★ 不静默忽略 {@code as=} 而返回全量——那正是 research
   * §C.7 的洞 ②（"没接"被伪装成"全可见"）。
   */
  private static void rejectAs(String path, boolean asPresent) {
    if (asPresent) {
      throw new IllegalArgumentException(
          "读端点 " + path + " 未接入 data redaction，不支持 as= 视角参数（fail-closed）");
    }
  }

  /**
   * 已接 redaction 的端点：按 actor 的 scope **递归剔除** {@code redactedFields}（{@code adjudicationDisclosure}
   * 由判决面承载）。
   */
  private Reply redactedIfRequested(
      boolean asPresent, DecisionMakerId actor, Map<String, String> params, Reply reply) {
    if (!asPresent) {
      return reply;
    }
    ViewScope scope = redactingQueryService.scopeOf(actor, target(params));
    return new Reply(
        reply.status(),
        redactingQueryService.applyRedactedFields(reply.body(), scope),
        reply.allow());
  }

  private Reply handlePost(HttpExchange exchange, String path) throws IOException {
    if (path.equals("/api/command")) {
      return submitReply(exchange);
    }
    if (path.equals("/api/advance")) {
      return advanceReply(exchange);
    }
    if (path.equals("/api/fork")) {
      return forkReply(exchange);
    }
    return null;
  }

  /** known-but-wrong-method ⇒ 405（带 Allow）；unknown ⇒ 404。 */
  private static Reply routeError(String path) {
    String allowed = allowedMethod(path);
    if (allowed != null) {
      return new Reply(405, Map.of("error", "method not allowed", "allow", allowed), allowed);
    }
    return Reply.of(404, notFound(path));
  }

  private static String allowedMethod(String path) {
    if (GET_ROUTES.contains(path)
        || isUnitDetail(path)
        || isRegionDetail(path)
        || isDecisionMakerDetail(path)) {
      return "GET";
    }
    if (POST_ROUTES.contains(path)) {
      return "POST";
    }
    return null;
  }

  private static boolean isUnitDetail(String path) {
    return path.startsWith(UNIT_DETAIL_PREFIX) && path.length() > UNIT_DETAIL_PREFIX.length();
  }

  /** 区域详情：{@code /api/map/region/{id}}（M7 T1，spec §3.3）。 */
  private static boolean isRegionDetail(String path) {
    return path.startsWith(REGION_DETAIL_PREFIX) && path.length() > REGION_DETAIL_PREFIX.length();
  }

  /** 决策人详情：{@code /api/sd/decision-makers/{id}}（T5）。 */
  private static boolean isDecisionMakerDetail(String path) {
    return path.startsWith(DECISION_MAKER_DETAIL_PREFIX)
        && path.length() > DECISION_MAKER_DETAIL_PREFIX.length();
  }

  /** 审批代理路径：{@code /api/approvals}（列表/GET）与 {@code /api/approvals/{id}}（决议/POST）。 */
  private static boolean isApprovalPath(String path) {
    return path.equals(APPROVAL_PATH) || path.startsWith(APPROVAL_PATH + "/");
  }

  // ── 读端点 ──────────────────────────────────────────────────────────────────────────

  private Reply stateReply() {
    Set<BranchId> branches = core.branches();
    List<String> names = new ArrayList<>(branches.size());
    Map<String, Long> heads = new LinkedHashMap<>();
    for (BranchId branch : branches) {
      names.add(branch.value());
      core.head(branch).ifPresent(head -> heads.put(branch.value(), head.value()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("branches", names);
    body.put("heads", heads);
    body.put("meta", metaFor(names));
    return Reply.of(200, body);
  }

  private Object metaFor(List<String> branchNames) {
    if (branchNames.isEmpty()) {
      return null;
    }
    String branchName = branchNames.contains(DEFAULT_BRANCH) ? DEFAULT_BRANCH : branchNames.get(0);
    BranchId branch = new BranchId(branchName);
    RevisionId revision = core.head(branch).orElseThrow();
    SimulationState state = queryService.stateAt(QueryTarget.head(branch));
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("branch", branchName);
    meta.put("revision", revision.value());
    meta.put("timestamp", ApiViews.timestamp(state.meta().timestamp()));
    return meta;
  }

  private Reply mapHexReply(Map<String, String> params, DecisionMakerId actor, boolean asPresent) {
    int q = intParam(params, "q");
    int r = intParam(params, "r");
    HexCoord coord = new HexCoord(q, r);
    QueryTarget target = target(params);
    if (asPresent && !redactingQueryService.seesHex(actor, target, coord)) {
      // fail-closed：不可见的格与"不存在"同形（都不给存在性侧信道）。
      Map<String, Object> body = ApiViews.hexCoord(coord);
      body.put("error", "hex not found");
      return Reply.of(404, body);
    }
    SimulationState state = queryService.stateAt(target);
    GameMap map = ApiViews.gameMap(state);
    HexCell cell = map.hexes().get(coord);
    if (cell == null) {
      Map<String, Object> body = ApiViews.hexCoord(coord);
      body.put("error", "hex not found");
      return Reply.of(404, body);
    }
    List<RegionId> regions = MapResolver.regionOfHex(map, coord);
    String terrain = map.terrainAt(coord);
    TerrainType terrainType = map.terrainTypes().get(terrain);
    List<FacetEntry> facets = queryService.facets(canonicalHex(q, r), target);
    return redactedIfRequested(
        asPresent,
        actor,
        params,
        Reply.of(
            200, ApiViews.mapHex(coord, cell, terrain, facets, regions, terrainType, map.edges())));
  }

  /**
   * 寻路只读端点（M7b T3，M7b-S3）：{@code GET /api/map/path?unit=&q=&r=&branch=&revision=}。
   *
   * <p>★ **起点由服务端取**（该单位在目标时刻的 {@code effectivePosition}，权威）——前端不传起点，避免第二份真相；寻路调 {@link
   * PathFinder#findPath}（app 层不另写寻路）。单位不存在 ⇒ 404；终点不存在/不可达/单位无位置 ⇒ {@code
   * reachable:false,path:[]}。**纯只读**：不触发任何写。
   */
  private Reply mapPathReply(Map<String, String> params) {
    String unitText = requiredParam(params, "unit");
    int q = intParam(params, "q");
    int r = intParam(params, "r");
    QueryTarget target = target(params);
    SimulationState state = queryService.stateAt(target);
    UnitState units = ApiViews.unitState(state);
    UnitId unitId = new UnitId(unitText);
    Unit unit = units.units().get(unitId);
    if (unit == null) {
      return Reply.of(404, Map.of("error", "unit not found", "id", unitText));
    }
    Optional<HexCoord> start = units.effectivePosition(unitId, state.meta().timestamp());
    Optional<List<HexCoord>> path =
        start.isEmpty()
            ? Optional.empty()
            : PathFinder.findPath(
                ApiViews.gameMap(state),
                start.get(),
                new HexCoord(q, r),
                unit,
                TerrainMovementCost.INSTANCE);
    return Reply.of(200, ApiViews.pathResult(path.isPresent(), path.orElse(List.of())));
  }

  /** 区域详情（M7 T1，spec §3.3）：区域不存在、或带 {@code as=} 时不可见 ⇒ 404（同口径，不给存在性侧信道）。 */
  private Reply regionReply(
      Map<String, String> params, DecisionMakerId actor, boolean asPresent, String id) {
    RegionId regionId = RegionId.parse(id);
    QueryTarget target = target(params);
    if (asPresent && !redactingQueryService.seesRegion(actor, target, regionId)) {
      return Reply.of(404, Map.of("error", "region not found", "id", id));
    }
    SimulationState state = queryService.stateAt(target);
    Region region = ApiViews.gameMap(state).regions().get(regionId);
    if (region == null) {
      return Reply.of(404, Map.of("error", "region not found", "id", id));
    }
    return redactedIfRequested(
        asPresent, actor, params, Reply.of(200, ApiViews.regionDetail(region)));
  }

  /**
   * 时间轴节点清单（M7 T1，spec §3.1）：{@code branch} 缺省 {@code main}，分支不存在 ⇒ 404。
   *
   * <p>★ 只读：{@code core.head} / {@code core.revisions} 都不触发封存与写盘（判据②的只读面）。
   */
  private Reply timelineReply(Map<String, String> params) {
    BranchId branch = new BranchId(params.getOrDefault("branch", DEFAULT_BRANCH));
    Optional<RevisionId> head = core.head(branch);
    if (head.isEmpty()) {
      return Reply.of(404, Map.of("error", "branch not found", "branch", branch.value()));
    }
    List<RevisionRow> rows = core.revisions(branch);
    return Reply.of(200, ApiViews.timeline(branch, head.get().value(), rows));
  }

  private Reply unitReply(
      Map<String, String> params, DecisionMakerId actor, boolean asPresent, String id) {
    UnitId unitId = new UnitId(id);
    QueryTarget target = target(params);
    if (asPresent && !redactingQueryService.seesUnit(actor, target, unitId)) {
      return Reply.of(404, Map.of("error", "unit not found", "id", id));
    }
    SimulationState state = queryService.stateAt(target);
    UnitState units = ApiViews.unitState(state);
    Unit unit = units.units().get(unitId);
    if (unit == null) {
      return Reply.of(404, Map.of("error", "unit not found", "id", id));
    }
    return redactedIfRequested(
        asPresent,
        actor,
        params,
        Reply.of(
            200, ApiViews.unit(unit, units, state.meta().timestamp(), ApiViews.gameMap(state))));
  }

  private Reply populationReply(
      Map<String, String> params, DecisionMakerId actor, boolean asPresent) {
    HexCoord coord = new HexCoord(intParam(params, "q"), intParam(params, "r"));
    QueryTarget target = target(params);
    if (asPresent && !redactingQueryService.seesHex(actor, target, coord)) {
      Map<String, Object> body = ApiViews.hexCoord(coord);
      body.put("error", "population series not found");
      return Reply.of(404, body);
    }
    SimulationState state = queryService.stateAt(target);
    PopulationSeries series = ApiViews.socialData(state).populations().get(coord);
    if (series == null) {
      Map<String, Object> body = ApiViews.hexCoord(coord);
      body.put("error", "population series not found");
      return Reply.of(404, body);
    }
    return redactedIfRequested(
        asPresent,
        actor,
        params,
        Reply.of(200, ApiViews.population(coord, series, state.meta().timestamp())));
  }

  /**
   * 决策人列表（T5，spec §六.1）：{@code GET /api/sd/decision-makers[?affiliation=nation:<id>|army:<id>]}。
   *
   * <p>★ **只读**（不经 {@code CoreSimos}、不写盘）。空库/无匹配 ⇒ {@code 200 {"decisionMakers":[]}}（不是 404/500）；
   * 过滤串坏掉 ⇒ {@link IllegalArgumentException} ⇒ 400（**fail-closed，不静默当空集合**）。
   */
  private Reply decisionMakersReply(Map<String, String> params) {
    SdQueryService.AffiliationFilter filter = SdQueryService.parseFilter(params.get("affiliation"));
    List<SdQueryService.DecisionMakerInfo> makers =
        sdQueryService.listDecisionMakers(filter, target(params));
    return Reply.of(200, Map.of("decisionMakers", ApiViews.decisionMakers(makers)));
  }

  /** 决策人详情（T5）：{@code GET /api/sd/decision-makers/{id}}；不存在 ⇒ 404（与 unit/region 详情同口径）。 */
  private Reply decisionMakerReply(Map<String, String> params, String id) {
    DecisionMakerId makerId = DecisionMakerId.parse(id);
    return sdQueryService
        .decisionMaker(makerId, target(params))
        .map(info -> Reply.of(200, ApiViews.decisionMaker(info)))
        .orElseGet(() -> Reply.of(404, Map.of("error", "decision maker not found", "id", id)));
  }

  // ── 写端点（全部经 CoreSimos.submit；身份 = player:gui）─────────────────────────────────

  private Reply submitReply(HttpExchange exchange) throws IOException {
    JsonNode root = readBody(exchange);
    String id = UUID.randomUUID().toString();
    CommandEnvelope command =
        new CommandEnvelope(
            id,
            id,
            GUI_INITIATOR,
            new BranchId(textField(root, "branch")),
            new RevisionId(longField(root, "expectedRevision")),
            textField(root, "type"),
            payloadField(root));
    return resultReply(core.submit(command));
  }

  private Reply advanceReply(HttpExchange exchange) throws IOException {
    JsonNode root = readBody(exchange);
    String id = UUID.randomUUID().toString();
    long from = longField(root, "from");
    TimeRange range =
        root.hasNonNull("to")
            ? new TimeRange(
                SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(longField(root, "to"))))
            : TimeRange.since(SimosTimestamp.of(from));
    AdvanceTime command =
        new AdvanceTime(
            id,
            id,
            GUI_INITIATOR,
            new BranchId(textField(root, "branch")),
            new RevisionId(longField(root, "expectedRevision")),
            range);
    return resultReply(core.submit(command));
  }

  private Reply forkReply(HttpExchange exchange) throws IOException {
    JsonNode root = readBody(exchange);
    String id = UUID.randomUUID().toString();
    ForkBranch command =
        new ForkBranch(
            id,
            id,
            GUI_INITIATOR,
            new BranchId(textField(root, "source")),
            new RevisionId(longField(root, "expectedRevision")),
            new BranchId(textField(root, "newBranch")));
    return resultReply(core.submit(command));
  }

  private static Reply resultReply(CommandResult result) {
    return switch (result) {
      case CommandResult.Committed committed -> Reply.of(200, ApiViews.committed(committed.ref()));
      case CommandResult.Conflict conflict -> Reply.of(409, ApiViews.conflict(conflict.current()));
      case CommandResult.Rejected rejected -> Reply.of(422, ApiViews.rejected(rejected.reason()));
    };
  }

  /**
   * 审批透传（T6，spec §8.2）：把方法与路径原样转给 AgentLib 的 {@code ApprovalHttpEndpoint}，状态码/体/头原样回。
   *
   * <p>★ **不解释语义**：404/405/409/400 全部由 AgentLib 端点判定；本方法只做"转发 + 照回"。不可达 ⇒ 502。未配置 base URL ⇒ 503。
   */
  private void proxyApproval(HttpExchange exchange, String path) throws IOException {
    if (approvalBaseUrl == null || approvalBaseUrl.isBlank()) {
      writeReply(exchange, Reply.of(503, Map.of("error", "approval endpoint 未配置")));
      return;
    }
    String query = exchange.getRequestURI().getRawQuery();
    String target = approvalBaseUrl + path + (query == null || query.isEmpty() ? "" : "?" + query);
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target));
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    builder.method(
        exchange.getRequestMethod(),
        requestBody.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(requestBody));
    HttpResponse<byte[]> response;
    try {
      response = approvalClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeReply(exchange, Reply.of(502, Map.of("error", "审批端点不可达")));
      return;
    } catch (IOException e) {
      writeReply(exchange, Reply.of(502, Map.of("error", "审批端点不可达")));
      return;
    }
    Headers headers = exchange.getResponseHeaders();
    String responseType = response.headers().firstValue("Content-Type").orElse(null);
    headers.set(
        "Content-Type", responseType == null ? "application/json; charset=utf-8" : responseType);
    response.headers().firstValue("Allow").ifPresent(allow -> headers.set("Allow", allow));
    byte[] body = response.body();
    if (body.length == 0) {
      exchange.sendResponseHeaders(response.statusCode(), -1);
    } else {
      exchange.sendResponseHeaders(response.statusCode(), body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }

  // ── 请求工具 ────────────────────────────────────────────────────────────────────────

  private JsonNode readBody(HttpExchange exchange) throws IOException {
    byte[] bytes;
    try (InputStream in = exchange.getRequestBody()) {
      bytes = in.readAllBytes();
    }
    if (bytes.length == 0) {
      throw new IllegalArgumentException("请求体为空");
    }
    try {
      return MAPPER.readTree(bytes);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("请求体不是合法 JSON: " + e.getOriginalMessage());
    }
  }

  private static String textField(JsonNode root, String name) {
    JsonNode node = root.get(name);
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + name + " 必填且为非空文本");
    }
    return node.asText();
  }

  private static long longField(JsonNode root, String name) {
    JsonNode node = root.get(name);
    if (node == null || !node.isIntegralNumber()) {
      throw new IllegalArgumentException("字段 " + name + " 必填且为整数");
    }
    return node.asLong();
  }

  private static String payloadField(JsonNode root) {
    JsonNode node = root.get("payloadJson");
    if (node == null || node.isNull()) {
      return "{}";
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("字段 payloadJson 必须是文本");
    }
    return node.asText();
  }

  private static Map<String, String> queryParams(HttpExchange exchange) {
    String raw = exchange.getRequestURI().getRawQuery();
    Map<String, String> params = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return params;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        params.put(urlDecode(pair), "");
      } else {
        params.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
      }
    }
    return params;
  }

  private static String requiredParam(Map<String, String> params, String name) {
    String value = params.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("查询参数 " + name + " 必填");
    }
    return value;
  }

  private static int intParam(Map<String, String> params, String name) {
    String value = requiredParam(params, name);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("查询参数 " + name + " 必须是整数: " + value);
    }
  }

  private static QueryTarget target(Map<String, String> params) {
    BranchId branch = new BranchId(params.getOrDefault("branch", DEFAULT_BRANCH));
    String revision = params.get("revision");
    if (revision == null || revision.isBlank()) {
      return QueryTarget.head(branch);
    }
    try {
      return QueryTarget.at(branch, new RevisionId(Long.parseLong(revision)));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("查询参数 revision 必须是整数: " + revision);
    }
  }

  /** canonical {@code map:<mapId>:hex.<q>_<r>}——**只能用 Address AST 造**（T3 的硬接缝）。 */
  private String canonicalHex(int q, int r) {
    Address address =
        new Address(List.of(new Namespace("map"), Entity.of(mapId), Entity.of("hex", q + "_" + r)));
    return address.canonical();
  }

  private static String urlDecode(String text) {
    return URLDecoder.decode(text, StandardCharsets.UTF_8);
  }

  // ── 响应工具 ────────────────────────────────────────────────────────────────────────

  private void writeReply(HttpExchange exchange, Reply reply) throws IOException {
    byte[] body = reply.body() == null ? new byte[0] : MAPPER.writeValueAsBytes(reply.body());
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    if (reply.allow() != null) {
      headers.set("Allow", reply.allow());
    }
    if (body.length == 0) {
      exchange.sendResponseHeaders(reply.status(), -1);
    } else {
      exchange.sendResponseHeaders(reply.status(), body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }

  private void safeError(HttpExchange exchange, int status, String message) {
    try {
      writeReply(exchange, Reply.of(status, Map.of("error", message == null ? "error" : message)));
    } catch (IOException | RuntimeException e) {
      LOG.debug("无法写出错误响应（客户端可能已断开）", e);
    }
  }

  private static Map<String, Object> notFound(String path) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "not found");
    body.put("path", path);
    return body;
  }

  /** 一个已算好的响应：状态码 + JSON 体（null = 空体）+ 可选 {@code Allow} 头。 */
  private record Reply(int status, Object body, String allow) {

    static Reply of(int status, Object body) {
      return new Reply(status, body, null);
    }
  }
}
