package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
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
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 决策人**可见范围**只读面端到端验收：{@code GET /api/sd/decision-makers/{id}/scope}。
 *
 * <p>★ **本端点存在的理由**：某决策人"能看见什么"由**范围函数现算 ∩ GM 的 {@code accessLimit}** 决定，而这份结果此前 **只活在运行时**（MCP
 * 工具链与 GUI 的 {@code as=} 路径里）。GM 因此看不见自己配的权收到了多少。本端点把**算出来的那一个对象** 直接摊开给 GM。
 *
 * <p>★★ **同源是本端点的全部要害**：它必须复用 {@link DecisionCallerFactory#resourceScopesFor}（决策人每次工具调用、 GUI 的
 * {@code as=} 读都走这个方法）——各算一份的话两边**都不会报错**，只会慢慢漂移。故这里有一条**派生式**判据：把端点返回的 每个命名空间前缀与**当场重算**的 {@code
 * resourceScopesFor} 逐值对比。
 *
 * <p>★ 夹具刻意造出"限制把范围收窄了"的形态（{@code dm-narrow}：与国家决策人**同归属**、但限制指向别国的区域）——只报 "范围函数输出"的实现（漏掉 {@code
 * narrowTo}）在这个夹具下**会红**。
 *
 * <p>★ **本轮只留两条**（用户 2026-09-23：少做测试，写完直接编译、我直接看）：一条钉返回形状 + 同源 + 交集，一条钉 404。
 */
class SdDecisionScopeApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final HexCoord H14 = new HexCoord(1, 4);

  private static final UnitId U1 = new UnitId("u-1");
  private static final RegionId R_NATION = new RegionId("r-nation");
  private static final RegionId R_OTHER = new RegionId("r-other");

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

  @Test
  void scopeReportsTheComputedRangeAndItsPrefixesMatchTheOneComputation() throws Exception {
    createFixture();

    JsonNode body = scopeJson("dm-nation");
    assertThat(body.get("decisionMakerId").asText()).isEqualTo("dm-nation");
    assertThat(body.get("affiliation").get("kind").asText())
        .as("国家级 / 军队级的分辨靠 affiliation.kind")
        .isEqualTo("nation");
    assertThat(body.get("revision").asLong())
        .as("范围是现算的 ⇒ 必须能看出它算在哪一个 revision 上")
        .isEqualTo(shell.coreSimos().head(main()).orElseThrow().value());

    JsonNode visible = body.get("visible");
    // 甲国本土 = H11/H12/H13 三格；乙国的 r-other（H14）不在范围里。
    assertThat(texts(visible.get("regionIds"))).containsExactly("r-nation");
    assertThat(hexPairs(visible.get("hexes")))
        .as("区域级授权展开成逐格（高亮要的就是格）")
        .containsExactly("1_1", "1_2", "1_3");
    assertThat(visible.get("hexCount").asLong()).isEqualTo(3);
    assertThat(visible.get("offMapHexCount").asLong()).as("范围全落在图上").isZero();
    assertThat(texts(visible.get("unitIds"))).as("位置落在本国区域内的单位").containsExactly("u-1");
    assertThat(visible.get("boundingBox").get("minR").asInt()).isEqualTo(1);
    assertThat(visible.get("boundingBox").get("maxR").asInt()).isEqualTo(3);

    // ★★ 同源护栏：命名空间前缀必须逐值等于当场重算的 resourceScopesFor。
    SimulationState state = shell.queryService().stateAt(QueryTarget.head(main()));
    DecisionMaker maker = sdState(state).decisionMakers().get(new DecisionMakerId("dm-nation"));
    var expected =
        DecisionCallerFactory.resourceScopesFor(
                DecisionScopeFunctions.defaults(), maker, state, "Map1")
            .byNamespace();
    JsonNode namespaces = body.get("namespaces");
    assertThat(namespaces.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrderElementsOf(expected.keySet());
    for (Map.Entry<String, ResourceScope> entry : expected.entrySet()) {
      JsonNode node = namespaces.get(entry.getKey());
      // ★ 期望侧要**排序**：`ResourceScope.prefixes()` 是 Set（其迭代序不是内容的纯函数），而端点必须有序
      //   ——否则"同一状态两次响应逐字节相同"这条前提会破（跨 JVM 的哈希盐是第三个来源）。
      List<String> want = new ArrayList<>(entry.getValue().prefixes());
      want.sort(null);
      assertThat(texts(node.get("prefixes")))
          .as("命名空间 %s 的前缀必须与现算逐值相等（且有序）", entry.getKey())
          .containsExactlyElementsOf(want);
    }
    // sd 维是机制不是配权：决策人只能以自己名义下决策/提交决策包 ⇒ 前缀逐条报出来。
    assertThat(texts(namespaces.get("sd").get("prefixes")))
        .containsExactly("decision-maker/dm-nation", "decision-packet/dm-nation");
    assertThat(body.get("unparsedPrefixes")).as("内置范围函数产出的前缀形状必须全可解码（解不出的要显式列出，不静默丢）").isEmpty();

    // ★ GM 的 accessLimit 是**交集**：dm-narrow 与国家决策人同归属，但限制只指向乙国的区域 ⇒ 交集为空。
    //   只报"范围函数输出"（漏 narrowTo）的实现在这里会给出 3 格 ⇒ 本断言有判别力。
    JsonNode narrow = scopeJson("dm-narrow").get("visible");
    assertThat(narrow.get("hexes")).as("交集为空 ⇒ 一个格都没有").isEmpty();
    assertThat(narrow.get("hexCount").asLong()).isZero();
    assertThat(narrow.has("boundingBox")).as("键必须在（不省字段）").isTrue();
    assertThat(narrow.get("boundingBox").isNull())
        .as("空范围没有边界（null，不是 0/0/0/0——那会被读成「范围就是原点那一格」）")
        .isTrue();
    // 而 unit 维**没被收窄**（限制只写了 map 键）——逐命名空间表态，不是"一处收窄处处收窄"。
    assertThat(texts(narrow.get("unitIds"))).containsExactly("u-1");
  }

  @Test
  void unknownDecisionMakerIsNotFound() throws Exception {
    createFixture();

    HttpResponse<String> response = get("/api/sd/decision-makers/dm-nope/scope");

    assertThat(response.statusCode()).as("未知决策人 ⇒ 404（与详情端点同口径），绝不回一份空范围").isEqualTo(404);
    assertThat(JSON.readTree(response.body()).get("error").asText()).contains("not found");
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────

  /** 经真命令路径种入：甲乙两国（各一片区域）+ a1 军（根单位 u-1）+ 三个决策人 + 两处配权。 */
  private void createFixture() throws Exception {
    submit(
        "sd.CreateNation",
        "{\"nationId\":\"n1\",\"name\":\"甲国\",\"homeRegionId\":\"r-nation\",\"adminBudgetPerTick\":10}");
    submit(
        "sd.CreateNation",
        "{\"nationId\":\"n2\",\"name\":\"乙国\",\"homeRegionId\":\"r-other\",\"adminBudgetPerTick\":10}");
    submit("sd.CreateArmy", "{\"armyId\":\"a1\",\"rootUnitId\":\"u-1\",\"name\":\"第一军\"}");
    submit(
        "sd.CreateDecisionMaker",
        "{\"id\":\"dm-nation\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
            + "\"allowedTools\":[],\"cadence\":3}");
    submit(
        "sd.CreateDecisionMaker",
        "{\"id\":\"dm-army\",\"affiliation\":{\"kind\":\"army\",\"id\":\"a1\"},"
            + "\"allowedTools\":[],\"cadence\":5}");
    submit(
        "sd.CreateDecisionMaker",
        "{\"id\":\"dm-narrow\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
            + "\"allowedTools\":[],\"cadence\":3}");
    // 交集演示：一个含本国区域（+ 一个不存在的），一个只含**别国**的区域。
    submit(
        "sd.SetDecisionMakerAccess",
        "{\"decisionMakerId\":\"dm-nation\","
            + "\"accessLimit\":{\"map\":[\"Map1/region/r-nation\",\"Map1/region/r-extra\"]},"
            + "\"adjudicationDisclosure\":\"PERCEPTION_ONLY\",\"redactedFields\":[]}");
    submit(
        "sd.SetDecisionMakerAccess",
        "{\"decisionMakerId\":\"dm-narrow\","
            + "\"accessLimit\":{\"map\":[\"Map1/region/r-other\"]},"
            + "\"adjudicationDisclosure\":\"PERCEPTION_ONLY\",\"redactedFields\":[]}");
  }

  private JsonNode scopeJson(String id) throws Exception {
    HttpResponse<String> response = get("/api/sd/decision-makers/" + id + "/scope");
    assertThat(response.statusCode()).as("范围 %s: %s", id, response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode node : array) {
      out.add(node.asText());
    }
    return out;
  }

  /** hex 数组 {@code [[q,r],…]} ⇒ {@code "q_r"} 列表（与路径语法同形，便于逐值断言）。 */
  private static List<String> hexPairs(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode node : array) {
      out.add(node.get(0).asInt() + "_" + node.get(1).asInt());
    }
    return out;
  }

  private void submit(String type, String payloadJson) throws Exception {
    long expected = shell.coreSimos().head(main()).orElseThrow().value();
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("type", type);
    request.put("payloadJson", payloadJson);
    request.put("branch", "main");
    request.put("expectedRevision", expected);
    HttpResponse<String> response = post("/api/command", JSON.writeValueAsString(request));
    assertThat(response.statusCode()).as("提交 %s: %s", type, response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");
  }

  private static SdState sdState(SimulationState state) {
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  /**
   * 创世 {@code (main,1)}：甲乙两国各一片区域（甲 3 格、乙 1 格）+ 单位 u-1 在 (1,1)；sd 为空。
   *
   * <p>★ 两片区域**必须同图**：只种一片的话"范围把别国也划进来"这类错**看不出来**（没有别国）。
   */
  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(tempDir.resolve(CoreSimos.DB_FILE_NAME))) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  new BranchId("main"),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoNationMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, unitState()),
                "social",
                    new SocialSnapshot(
                        ref("main", 1),
                        T7,
                        new SocialData(new LinkedHashMap<>(), Map.of(), Map.of())),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  private static GameMap twoNationMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : List.of(H11, H12, H13, H14)) {
      hexes.put(coord, new HexCell(0.5));
    }
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        R_NATION,
        Region.of(
            R_NATION,
            "甲国本土",
            Set.of(H11, H12, H13),
            new RegionMeta(null, "nation:n1", null, null)));
    regions.put(
        R_OTHER,
        Region.of(R_OTHER, "乙国本土", Set.of(H14), new RegionMeta(null, "nation:n2", null, null)));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        PathwayGroup.defaults(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 单位 u-1：位置 (1,1)、视野半径走缺省（1 圈 ⇒ 七格球）。 */
  private static UnitState unitState() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(
        U1,
        new Unit(
            U1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty()));
    return new UnitState(units);
  }
}
