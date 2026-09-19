package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GUI {@code /api} 端点的端到端验收（M5 T8 Step 5）：起真 {@link Shell}（{@code guiPort=0}），用 JDK {@link
 * HttpClient} 打真 HTTP。
 *
 * <p>夹具与 {@code QueryServiceTest} 同法：独立 store 种创世 {@code (main,1)} + 含 **map/unit/social** 三切片的 创世
 * checkpoint（state 时间戳 {@code of(7)}）。
 *
 * <p>覆盖：只读端点**逐值**（与 {@link io.mosire.simos.app.query.QueryService} 对拍）、写端点经 {@link CoreSimos} 提交且
 * {@code initiator=="player:gui"}（**独立 store + Timeline 读回**，R4）、404/405、静态首页、{@code close()} 释放端口。
 */
class GuiApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── 读端点（逐值对拍 QueryService）────────────────────────────────────

  @Test
  void stateReportsBranchesAndHeads() throws Exception {
    JsonNode body = getJson("/api/state");

    assertThat(body.get("branches")).as("分支清单与 core.branches() 同源").hasSize(1);
    assertThat(body.get("branches").get(0).asText()).isEqualTo("main");
    assertThat(body.get("heads").get("main").asLong()).isEqualTo(1L);
    assertThat(body.get("meta").get("branch").asText()).isEqualTo("main");
    assertThat(body.get("meta").get("revision").asLong()).isEqualTo(1L);
    assertThat(body.get("meta").get("timestamp").get("tick").asLong()).isEqualTo(T7.tick());
  }

  @Test
  void resolveReturnsCanonicalCandidates() throws Exception {
    String address = "map:Map1:[1,1]";
    JsonNode body = getJson("/api/resolve?address=" + enc(address));
    QueryResult expected = shell.queryService().resolve(address, QueryTarget.head(main()));

    assertThat(body.get("candidates")).hasSize(expected.candidates().size());
    JsonNode first = body.get("candidates").get(0);
    assertThat(first.get("canonicalAddress").asText()).isEqualTo("map:Map1:hex.1_1");
    assertThat(first.get("typeName").asText()).isEqualTo("Hex");
    assertThat(first.get("id").get("namespace").asText()).isEqualTo("map.hex");
  }

  @Test
  void facetsMatchQueryServicePerValue() throws Exception {
    String address = "map:Map1:hex.1_1";
    JsonNode body = getJson("/api/facets?address=" + enc(address));
    List<FacetEntry> expected = shell.queryService().facets(address, QueryTarget.head(main()));

    assertThat(body.get("entries")).hasSize(expected.size());
    JsonNode unit = body.get("entries").get(0);
    assertThat(unit.get("namespace").asText()).isEqualTo("unit");
    assertThat(unit.get("label").asText()).isEqualTo("第一连");
    assertThat(unit.get("typeName").asText()).isEqualTo("Unit");
    assertThat(unit.get("value").asText()).isEqualTo("unit:u-1");
    assertThat(body.get("entries").get(1).get("namespace").asText()).isEqualTo("social");
    assertThat(body.get("entries").get(1).get("value").asLong())
        .isEqualTo(populationSeries().valueAt(T7));
  }

  @Test
  void mapHexBuildsTheCanonicalFacetSubject() throws Exception {
    JsonNode body = getJson("/api/map/hex?q=1&r=1");

    assertThat(body.get("q").asInt()).isEqualTo(1);
    assertThat(body.get("r").asInt()).isEqualTo(1);
    assertThat(body.get("terrain").asText()).isEqualTo("desert");
    assertThat(body.get("height").asDouble()).isEqualTo(0.5);
    // ★ 非空即证明 hex 端点确实按 canonical 主体查了 facet（非 canonical ⇒ facet 为空列表）
    assertThat(body.get("facets")).hasSize(2);
    assertThat(body.get("facets").get(0).get("value").asText()).isEqualTo("unit:u-1");
    assertThat(body.get("facets").get(1).get("namespace").asText()).isEqualTo("social");
  }

  @Test
  void mapOverviewMatchesTheMapSlice() throws Exception {
    JsonNode body = getJson("/api/map/overview");

    assertThat(body.get("mapId").asText()).isEqualTo("Map1");
    assertThat(body.get("hexCount").asInt()).isEqualTo(3);
    assertThat(body.get("hexes")).hasSize(3);
  }

  // ── M7 T1 只读扩展（spec §3.1/§3.2/§3.3）──────────────────────────────

  /** 判据②后端面 / R3：{@code /api/timeline} 的节点与库一致、升序、parent 形状正确、未知分支 404。 */
  @Test
  void timelineReportsNodesAndHeadAnd404sForUnknownBranch() throws Exception {
    appendRevision(ref("main", 2), ref("main", 1), SimosTimestamp.of(8L), "unit.RenameUnit");

    JsonNode body = getJson("/api/timeline?branch=main");
    assertThat(body.get("branch").asText()).isEqualTo("main");
    assertThat(body.get("head").asLong()).as("节点数与 head 一致").isEqualTo(2L);
    JsonNode nodes = body.get("nodes");
    assertThat(nodes).hasSize(2);
    JsonNode genesis = nodes.get(0);
    assertThat(genesis.get("revision").asLong()).isEqualTo(1L);
    assertThat(genesis.get("tick").asLong()).isEqualTo(T7.tick());
    assertThat(genesis.get("commandType").asText()).isEqualTo("core.AdvanceTime");
    assertThat(genesis.get("initiator").asText()).isEqualTo("player:local");
    assertThat(genesis.get("parent").isNull()).as("创世节点 parent 为 null").isTrue();
    assertThat(genesis.has("changesetJson")).as("changesetJson 不进节点（体积大且对 UI 无用）").isFalse();
    JsonNode second = nodes.get(1);
    assertThat(second.get("revision").asLong()).isEqualTo(2L);
    assertThat(second.get("commandType").asText()).isEqualTo("unit.RenameUnit");
    assertThat(second.get("parent").get("branch").asText()).isEqualTo("main");
    assertThat(second.get("parent").get("revision").asLong()).isEqualTo(1L);

    assertThat(getJson("/api/timeline").get("branch").asText())
        .as("branch 缺省 main")
        .isEqualTo("main");
    assertThat(get("/api/timeline?branch=ghost").statusCode()).as("分支不存在 ⇒ 404").isEqualTo(404);
  }

  /** 判据③ / R5：hex 详情带 {@code region} 与完整 {@code terrainType} 定义。 */
  @Test
  void mapHexCarriesRegionAndFullTerrainDefinition() throws Exception {
    JsonNode body = getJson("/api/map/hex?q=1&r=1");

    assertThat(body.get("region").asText())
        .as("H11 属 r-1（MapResolver.regionOfHex）")
        .isEqualTo("r-1");
    TerrainType expected = TerrainCatalog.of("desert");
    JsonNode terrain = body.get("terrainType");
    assertThat(terrain).as("完整地形定义在场").isNotNull();
    assertThat(terrain.get("key").asText()).isEqualTo(expected.key());
    assertThat(terrain.get("name").asText()).isEqualTo(expected.name());
    assertThat(terrain.get("color").asText()).isEqualTo(expected.color());
    assertThat(terrain.get("minHeight").asDouble()).isEqualTo(expected.minHeight());
    assertThat(terrain.get("maxHeight").asDouble()).isEqualTo(expected.maxHeight());
    assertThat(terrain.get("food").asInt()).isEqualTo(expected.food());
    assertThat(terrain.get("gold").asInt()).isEqualTo(expected.gold());
    assertThat(terrain.get("stone").asInt()).isEqualTo(expected.stone());
    assertThat(terrain.get("moveCost").asInt()).isEqualTo(expected.moveCost());
    assertThat(terrain.get("description").asText()).isEqualTo(expected.description());
  }

  /**
   * 判据④后端面：overview 的 region 项带 {@code meta}（可空），且 {@code terrainTypes} 已是**完整定义**（M7 T2 的 原子形状切换）。
   */
  @Test
  void mapOverviewRegionItemsCarryMeta() throws Exception {
    JsonNode body = getJson("/api/map/overview");
    JsonNode regions = body.get("regions");
    assertThat(regions).hasSize(2);

    JsonNode first = regionById(regions, "r-1");
    assertThat(first.get("meta").get("color").asText()).isEqualTo("#112233");
    assertThat(first.get("meta").get("tag").asText()).isEqualTo("核心");
    assertThat(first.get("meta").get("description").asText()).isEqualTo("测试区域");
    assertThat(first.get("meta").get("annexedBy").asText()).isEqualTo("u-1");

    JsonNode second = regionById(regions, "r-2");
    assertThat(second.get("meta").get("color").isNull()).isTrue();
    assertThat(second.get("meta").get("tag").isNull()).isTrue();
    assertThat(second.get("meta").get("description").isNull()).isTrue();
    assertThat(second.get("meta").get("annexedBy").isNull()).isTrue();

    JsonNode terrainTypes = body.get("terrainTypes");
    assertThat(terrainTypes).as("T2 起 terrainTypes 是完整定义，不再是 [key…]").hasSize(1);
    JsonNode desert = terrainTypes.get(0);
    assertThat(desert.isObject()).as("每项必须是对象（不是字符串 key）").isTrue();
    TerrainType expected = TerrainCatalog.of("desert");
    assertThat(desert.get("key").asText()).isEqualTo(expected.key());
    assertThat(desert.get("name").asText()).isEqualTo(expected.name());
    assertThat(desert.get("color").asText()).isEqualTo(expected.color());
    assertThat(desert.get("minHeight").asDouble()).isEqualTo(expected.minHeight());
    assertThat(desert.get("maxHeight").asDouble()).isEqualTo(expected.maxHeight());
    assertThat(desert.get("food").asInt()).isEqualTo(expected.food());
    assertThat(desert.get("gold").asInt()).isEqualTo(expected.gold());
    assertThat(desert.get("stone").asInt()).isEqualTo(expected.stone());
    assertThat(desert.get("moveCost").asInt()).isEqualTo(expected.moveCost());
    assertThat(desert.get("description").asText()).isEqualTo(expected.description());
  }

  /** 判据③后端面：区域详情回排序后的 hex 集合，未知区域 404。 */
  @Test
  void regionDetailReturnsSortedHexesAnd404sForUnknownId() throws Exception {
    JsonNode body = getJson("/api/map/region/r-1");

    assertThat(body.get("id").asText()).isEqualTo("r-1");
    assertThat(body.get("name").asText()).isEqualTo("第一区");
    assertThat(body.get("hexCount").asInt()).isEqualTo(2);
    assertThat(body.get("meta").get("tag").asText()).isEqualTo("核心");
    JsonNode hexes = body.get("hexes");
    assertThat(hexes).hasSize(2);
    assertThat(hexes.get(0).get("q").asInt()).isEqualTo(1);
    assertThat(hexes.get(0).get("r").asInt()).isEqualTo(1);
    assertThat(hexes.get(1).get("q").asInt()).isEqualTo(1);
    assertThat(hexes.get(1).get("r").asInt()).isEqualTo(3);

    assertThat(get("/api/map/region/r-ghost").statusCode()).as("区域不存在 ⇒ 404").isEqualTo(404);
  }

  @Test
  void unitsListAndDetailCarryEffectivePosition() throws Exception {
    JsonNode list = getJson("/api/units");
    assertThat(list.get("units")).hasSize(1);
    JsonNode unit = list.get("units").get(0);
    assertThat(unit.get("id").asText()).isEqualTo("u-1");
    assertThat(unit.get("name").asText()).isEqualTo("第一连");
    assertThat(unit.get("position").get("q").asInt()).isEqualTo(1);
    assertThat(unit.get("position").get("r").asInt()).isEqualTo(1);

    JsonNode detail = getJson("/api/unit/u-1");
    assertThat(detail.get("name").asText()).isEqualTo("第一连");
    assertThat(detail.get("member").asInt()).isEqualTo(100);
    assertThat(detail.get("position").get("q").asInt()).isEqualTo(1);
  }

  /**
   * M7b T2 判据：有路线 ⇒ {@code movement} 是对象，且 {@code status}/{@code currentHex}/{@code nextHex}/{@code
   * remainingMillis} 由 {@code UnitMoves.evaluate} 现算（同刻预算 0 ⇒ 全段未付）。
   */
  @Test
  void unitDetailExposesMovementObjectWithRouteAndInTransitState() throws Exception {
    String payload =
        "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}]}";
    String requestBody =
        "{\"type\":\"unit.PlanRoute\",\"payloadJson\":"
            + JSON.writeValueAsString(payload)
            + ",\"branch\":\"main\",\"expectedRevision\":1}";

    HttpResponse<String> response = post("/api/command", requestBody);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");

    JsonNode movement = getJson("/api/unit/u-1").get("movement");
    assertThat(movement.isObject()).as("movement 必须是对象（不再是布尔）").isTrue();
    JsonNode path = movement.get("route").get("path");
    assertThat(path).hasSize(3);
    assertThat(path.get(0).get("q").asInt()).isEqualTo(1);
    assertThat(path.get(0).get("r").asInt()).isEqualTo(1);
    assertThat(path.get(2).get("r").asInt()).isEqualTo(3);
    assertThat(movement.get("route").get("waypoints")).hasSize(3);
    assertThat(movement.get("departedAt").get("tick").asLong())
        .as("领域命令继承父行时刻 ⇒ 出发 tick == 创世 tick")
        .isEqualTo(T7.tick());
    assertThat(movement.get("speedAtDeparture").asInt()).isEqualTo(2);
    assertThat(movement.get("mobilityPerMilleAtDeparture").asInt()).isEqualTo(500);
    assertThat(movement.get("status").asText()).isEqualTo("IN_TRANSIT");
    assertThat(movement.get("currentHex").get("q").asInt()).isEqualTo(1);
    assertThat(movement.get("currentHex").get("r").asInt()).isEqualTo(1);
    assertThat(movement.get("nextHex").get("r").asInt()).isEqualTo(2);
    assertThat(movement.get("remainingMillis").asLong())
        .as("每格成本 = desert.moveCost(3) × 500‰ = 1500 毫 MP；同刻预算 0")
        .isEqualTo(1500L);

    JsonNode listed = getJson("/api/units").get("units").get(0).get("movement");
    assertThat(listed.get("route").get("path")).as("/api/units 与 /api/unit 同形").hasSize(3);
  }

  /** M7b T2 判据：无路线的单位 ⇒ {@code movement} 为 {@code null}（不是 false，也不是空对象）。 */
  @Test
  void unitWithoutRouteHasNullMovement() throws Exception {
    assertThat(getJson("/api/unit/u-1").get("movement").isNull()).isTrue();
    assertThat(getJson("/api/units").get("units").get(0).get("movement").isNull()).isTrue();
  }

  @Test
  void populationMatchesTheSeriesValueAtHead() throws Exception {
    JsonNode body = getJson("/api/social/population?q=1&r=1");

    assertThat(body.get("population").asLong()).isEqualTo(populationSeries().valueAt(T7));
    assertThat(body.get("at").get("tick").asLong()).isEqualTo(T7.tick());
  }

  // ── 写端点（R4：initiator 由独立 store + Timeline 读回）────────────────

  @Test
  void writeCommitsThroughCoreAndStampsPlayerGuiInitiator() throws Exception {
    String payload = "{\"id\":\"u-1\",\"name\":\"改名后的第一连\"}";
    String requestBody =
        "{\"type\":\"unit.RenameUnit\",\"payloadJson\":"
            + JSON.writeValueAsString(payload)
            + ",\"branch\":\"main\",\"expectedRevision\":1}";

    HttpResponse<String> response = post("/api/command", requestBody);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode result = JSON.readTree(response.body());
    assertThat(result.get("result").asText()).isEqualTo("committed");
    assertThat(result.get("ref").get("branch").asText()).isEqualTo("main");
    assertThat(result.get("ref").get("revision").asLong()).isEqualTo(2L);

    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 2)).orElseThrow();
      assertThat(row.initiator()).as("GUI 写命令的发起者恰是 player:gui（R4）").isEqualTo("player:gui");
      assertThat(row.commandType()).isEqualTo("unit.RenameUnit");
      assertThat(row.commandId())
          .as("C22：单命令链 commandId = correlationId")
          .isEqualTo(row.correlationId());
      assertThat(row.correlationId()).isNotBlank();
    }
    assertThat(getJson("/api/unit/u-1").get("name").asText()).isEqualTo("改名后的第一连");
  }

  // ── 404 / 405 / 审批接缝 / 静态 / 生命周期 ─────────────────────────────

  @Test
  void unknownApiPathIs404AndWrongMethodIs405() throws Exception {
    assertThat(get("/api/nope").statusCode()).isEqualTo(404);

    HttpResponse<String> wrongMethod = get("/api/command");
    assertThat(wrongMethod.statusCode()).isEqualTo(405);
    assertThat(wrongMethod.headers().firstValue("Allow").orElse("")).isEqualTo("POST");

    assertThat(post("/api/state", "{}").statusCode()).isEqualTo(405);
  }

  @Test
  void approvalsProxyForwardsToTheLiveAgentLibEndpoint() throws Exception {
    // Shell 现在恒装配审批端点（T6）⇒ 5711 的 /api/approvals 是真代理，状态码/体透传。
    JsonNode list = getJson("/api/approvals");
    assertThat(list.get("pending")).as("无写调用 ⇒ 待裁决表为空").isEmpty();

    assertThat(post("/api/approvals/ap-nope", "{\"decision\":\"deny\"}").statusCode())
        .as("未知 id ⇒ AgentLib 端点的 404 原样透传")
        .isEqualTo(404);

    HttpResponse<String> wrongMethod = get("/api/approvals/ap-nope");
    assertThat(wrongMethod.statusCode()).as("错误方法 ⇒ 405（端点判定）").isEqualTo(405);
    assertThat(wrongMethod.headers().firstValue("Allow").orElse("")).isEqualTo("POST");
  }

  @Test
  void indexHtmlIsServedFromClasspath() throws Exception {
    HttpResponse<String> root = get("/");
    assertThat(root.statusCode()).isEqualTo(200);
    assertThat(root.headers().firstValue("Content-Type").orElse("")).contains("text/html");
    assertThat(root.body()).contains("Simos Shell");

    assertThat(get("/nope").statusCode()).as("未知静态路径 ⇒ 404").isEqualTo(404);
  }

  @Test
  void closeReleasesTheGuiPort() throws Exception {
    int bound = shell.boundGuiPort();
    shell.close();

    try (ServerSocket probe = new ServerSocket()) {
      probe.setReuseAddress(true);
      probe.bind(new InetSocketAddress("127.0.0.1", bound));
      assertThat(probe.isBound()).as("close() 后端口已释放，可重新绑定").isTrue();
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private JsonNode getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, unit())));
    SocialData social = new SocialData(new LinkedHashMap<>(Map.of(H11, populationSeries())));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis, List.of(new MapCodec(), new SocialCodec(), new UnitCodec())));
  }

  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  /** 与 {@code QueryServiceTest.populationSeries()} 同款：anchor 10000、growth 2%→1%→−3%、t=45 减 800。 */
  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(SimosTimestamp.of(0), 0.02),
                new Segment<>(SimosTimestamp.of(20), 0.01),
                new Segment<>(SimosTimestamp.of(70), -0.03)),
            List.of(),
            null),
        List.of(new Event<>(SimosTimestamp.of(45), -800L, EventMode.ADD)));
  }

  private static GameMap corridorMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(desert.key(), 0.5));
    hexes.put(H12, new HexCell(desert.key(), 0.5));
    hexes.put(H13, new HexCell(desert.key(), 0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    // M7 T1：两个区域——r-1 带全量 meta（覆盖 H11/H13，非相邻 ⇒ 两条环），r-2 空 meta（覆盖 H12）
    Region r1 =
        Region.of(
            new RegionId("r-1"),
            "第一区",
            Set.of(H11, H13),
            new RegionMeta("#112233", "核心", "测试区域", "u-1"));
    Region r2 = Region.of(new RegionId("r-2"), "第二区", Set.of(H12), RegionMeta.empty());
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(r1.id(), r1);
    regions.put(r2.id(), r2);
    return new GameMap(
        hexes,
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  /** 从第二个连接追加一行 revision（M7 T1 的时间轴夹具；空变更集 ⇒ 不需要新 checkpoint）。 */
  private void appendRevision(
      StateRef target, StateRef parent, SimosTimestamp timestamp, String commandType) {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      new Timeline(store, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  target.branch(),
                  target.revision(),
                  Optional.of(parent),
                  timestamp,
                  "cmd-" + target.revision().value(),
                  "corr-" + target.revision().value(),
                  "player:local",
                  commandType,
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
  }

  private static JsonNode regionById(JsonNode regions, String id) {
    for (JsonNode region : regions) {
      if (id.equals(region.get("id").asText())) {
        return region;
      }
    }
    throw new AssertionError("overview 里没有区域 " + id);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
