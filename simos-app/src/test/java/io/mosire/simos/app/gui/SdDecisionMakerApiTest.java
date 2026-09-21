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
 * 决策人只读查询面端到端验收（T5，spec §六.1）：起真 {@link Shell}（四端口全 0），用 JDK {@link HttpClient} 打真 HTTP。
 *
 * <p>★ **夹具走真命令路径**：创世 checkpoint 只种地图（一个带 {@code nation:n1} tag 的区域 + 一格）+ 单位，sd 切片为空；然后经 {@code
 * POST /api/command} 依次发 {@code sd.CreateNation} / {@code sd.CreateArmy} / {@code
 * sd.CreateDecisionMaker}（国家、军队各一） / {@code sd.SetViewScope}——列表/过滤/详情读的就是**真命令写出来的状态**，不是手搭的 sd
 * 实体。
 *
 * <p>★ **覆盖三条主判据**（T5）：① 空库 ⇒ {@code 200 {"decisionMakers":[]}}（非 404/500）；② {@code
 * ?affiliation=nation:<id>} / {@code army:<id>} 过滤逐值；③ 详情字段与重放出的 {@link SdState} 逐值一致。
 *
 * <p>★ **fail-closed 的判别力**：坏过滤串（未知 kind / 缺冒号 / 空 id）⇒ **400**，与"合法但不存在的 id ⇒ 200 空列表"是**不同**结果
 * ——把前者折成空列表会让"查询坏掉"与"确实没有决策人"不可区分。
 */
class SdDecisionMakerApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final RegionId R_NATION = new RegionId("r-nation");

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0, 0));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── 判据：空库 ⇒ 空列表（不是 500）────────────────────────────────────

  @Test
  void emptyLibraryGivesEmptyListNotAnError() throws Exception {
    HttpResponse<String> response = get("/api/sd/decision-makers");

    assertThat(response.statusCode()).as("空库不是错误").isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("decisionMakers")).as("字段在、是数组、为空").isNotNull().isEmpty();
  }

  // ── 判据：列出全部 + affiliation 显示名逐值 ────────────────────────────

  @Test
  void listReturnsAllDecisionMakersSortedByIdWithResolvedAffiliation() throws Exception {
    createFixture();

    JsonNode makers = getJson("/api/sd/decision-makers").get("decisionMakers");

    assertThat(makers).hasSize(2);
    // ★ 字典序：dm-army < dm-nation（插入序相反也无所谓——排序是实现的一部分）。
    assertThat(makers.get(0).get("id").asText()).isEqualTo("dm-army");
    assertThat(makers.get(1).get("id").asText()).isEqualTo("dm-nation");

    JsonNode army = makers.get(0);
    assertThat(army.get("affiliation").get("kind").asText()).isEqualTo("army");
    assertThat(army.get("affiliation").get("id").asText()).isEqualTo("a1");
    assertThat(army.get("affiliation").get("displayName").asText())
        .as("军队显示名来自 Army.name")
        .isEqualTo("第一军");
    assertThat(army.get("affiliation").get("nationId").asText()).isEqualTo("n1");
    assertThat(army.get("affiliation").get("rootUnit").asText()).isEqualTo("u-1");
    assertThat(army.get("cadence").asLong()).isEqualTo(5L);
    assertThat(army.get("due").isNull()).as("T5 的 due 是占位（T9 才计算）").isTrue();

    JsonNode nation = makers.get(1);
    assertThat(nation.get("affiliation").get("kind").asText()).isEqualTo("nation");
    assertThat(nation.get("affiliation").get("id").asText()).isEqualTo("n1");
    assertThat(nation.get("affiliation").get("displayName").asText())
        .as("国家显示名来自 Nation.name")
        .isEqualTo("甲国");
    assertThat(nation.get("affiliation").get("nationId").asText()).isEqualTo("n1");
    assertThat(nation.get("affiliation").get("rootUnit").isNull()).as("国家归属无根单位").isTrue();
    assertThat(nation.get("cadence").asLong()).isEqualTo(3L);
    assertThat(nation.get("allowedTools").get(0).asText()).isEqualTo("sd.SubmitVerdict");
  }

  @Test
  void viewScopeIsSummarizedFromStoredScope() throws Exception {
    createFixture();

    JsonNode nation = detailJson("dm-nation");
    JsonNode scope = nation.get("viewScope");

    assertThat(scope.get("visibleRegions").asInt()).isEqualTo(1);
    assertThat(scope.get("visibleHexes").asInt()).isEqualTo(1);
    assertThat(scope.get("visibleUnits").asInt()).isEqualTo(1);
    assertThat(scope.get("seeOwnUnits").asBoolean()).isTrue();
    assertThat(scope.get("adjudicationDisclosure").asText()).isEqualTo("PERCEPTION_ONLY");
    assertThat(scope.get("redactedFields").get(0).asText()).isEqualTo("position");
  }

  // ── 判据：按 affiliation 过滤 ─────────────────────────────────────────

  @Test
  void filterByNationReturnsOnlyThatNationsDecisionMakers() throws Exception {
    createFixture();

    JsonNode makers =
        getJson("/api/sd/decision-makers?affiliation=nation:n1").get("decisionMakers");

    assertThat(makers).as("恰一条，且是 dm-nation（不过滤的实现会回 2 条）").hasSize(1);
    assertThat(makers.get(0).get("id").asText()).isEqualTo("dm-nation");
    assertThat(makers.get(0).get("affiliation").get("kind").asText()).isEqualTo("nation");
  }

  @Test
  void filterByArmyReturnsOnlyThatArmysDecisionMakers() throws Exception {
    createFixture();

    JsonNode makers = getJson("/api/sd/decision-makers?affiliation=army:a1").get("decisionMakers");

    assertThat(makers).as("恰一条，且是 dm-army").hasSize(1);
    assertThat(makers.get(0).get("id").asText()).isEqualTo("dm-army");
    assertThat(makers.get(0).get("affiliation").get("rootUnit").asText()).isEqualTo("u-1");
  }

  /** ★ 合法 kind + 不存在的 id ⇒ **200 空列表**（这是"确实没有决策人"，不是错误）。 */
  @Test
  void filterForValidButAbsentNationGivesEmptyList() throws Exception {
    createFixture();

    HttpResponse<String> response =
        get("/api/sd/decision-makers?affiliation=nation:no-such-nation");

    assertThat(response.statusCode()).as("合法过滤、无匹配 ⇒ 200").isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("decisionMakers")).isEmpty();
  }

  // ── 判据：fail-closed（坏过滤串 ≠ 空集合）─────────────────────────────

  @Test
  void filterWithUnknownKindIsRejectedNotSilentlyEmpty() throws Exception {
    createFixture();

    HttpResponse<String> response = get("/api/sd/decision-makers?affiliation=kingdom:n1");

    assertThat(response.statusCode()).as("未知 kind 必须显式失败，不得当空集合").isEqualTo(400);
    assertThat(JSON.readTree(response.body()).get("error")).isNotNull();
  }

  @Test
  void filterWithoutColonIsRejected() throws Exception {
    createFixture();

    HttpResponse<String> response = get("/api/sd/decision-makers?affiliation=bogus");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void filterWithEmptyIdIsRejected() throws Exception {
    createFixture();

    HttpResponse<String> response = get("/api/sd/decision-makers?affiliation=nation:");

    assertThat(response.statusCode()).as("空 id 不得当空集合").isEqualTo(400);
  }

  // ── 判据：详情与重放状态逐值一致 ─────────────────────────────────────

  @Test
  void detailMatchesReplayedSdStateFieldByField() throws Exception {
    createFixture();
    DecisionMaker expected =
        replayedSdState().decisionMakers().get(new DecisionMakerId("dm-nation"));

    JsonNode body = detailJson("dm-nation");

    assertThat(body.get("id").asText()).isEqualTo(expected.id().value());
    assertThat(body.get("cadence").asLong()).isEqualTo(expected.decisionCadenceTicks());
    assertThat(body.get("allowedTools")).hasSize(expected.allowedTools().size());
    assertThat(body.get("allowedTools").get(0).asText()).isEqualTo("sd.SubmitVerdict");
    assertThat(body.get("affiliation").get("kind").asText()).isEqualTo("nation");
    assertThat(body.get("affiliation").get("id").asText()).isEqualTo("n1");
  }

  @Test
  void detailForUnknownIdGives404() throws Exception {
    createFixture();

    HttpResponse<String> response = get("/api/sd/decision-makers/dm-nope");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).get("error").asText())
        .isEqualTo("decision maker not found");
  }

  @Test
  void listEndpointOnlyAllowsGet() throws Exception {
    createFixture();

    HttpResponse<String> response = post("/api/sd/decision-makers", "{}");

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow")).hasValue("GET");
  }

  @Test
  void twoListCallsAreByteIdentical() throws Exception {
    createFixture();

    HttpResponse<String> first = get("/api/sd/decision-makers");
    HttpResponse<String> second = get("/api/sd/decision-makers");

    JsonNode makers = JSON.readTree(first.body()).get("decisionMakers");
    assertThat(makers).as("先断言聚合非空，别把空==空当成功").hasSize(2);
    assertThat(second.body()).as("排序是逐字节可复现的前提").isEqualTo(first.body());
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 经真命令路径种入：n1 国 + a1 军（根单位 u-1）+ 两个决策人 + 给国家决策人配权。 */
  private void createFixture() throws Exception {
    submit(
        "sd.CreateNation",
        "{\"nationId\":\"n1\",\"name\":\"甲国\",\"homeRegionId\":\"r-nation\",\"adminBudgetPerTick\":10}");
    submit(
        "sd.CreateArmy",
        "{\"armyId\":\"a1\",\"nationId\":\"n1\",\"rootUnitId\":\"u-1\",\"name\":\"第一军\"}");
    submit(
        "sd.CreateDecisionMaker",
        "{\"id\":\"dm-nation\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
            + "\"allowedTools\":[\"sd.SubmitVerdict\"],\"cadence\":3}");
    submit(
        "sd.CreateDecisionMaker",
        "{\"id\":\"dm-army\",\"affiliation\":{\"kind\":\"army\",\"id\":\"a1\"},"
            + "\"allowedTools\":[\"sd.IssueDirective\",\"sd.SubmitVerdict\"],\"cadence\":5}");
    submit(
        "sd.SetViewScope",
        "{\"decisionMakerId\":\"dm-nation\",\"viewScope\":{\"visibleRegions\":[\"r-nation\"],"
            + "\"visibleHexes\":[{\"q\":1,\"r\":1}],\"visibleUnits\":[\"u-1\"],\"seeOwnUnits\":true,"
            + "\"adjudicationDisclosure\":\"PERCEPTION_ONLY\",\"redactedFields\":[\"position\"]}}");
  }

  private String submit(String type, String payloadJson) throws Exception {
    long expected = shell.coreSimos().head(main()).orElseThrow().value();
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("type", type);
    request.put("payloadJson", payloadJson);
    request.put("branch", "main");
    request.put("expectedRevision", expected);
    HttpResponse<String> response = post("/api/command", JSON.writeValueAsString(request));
    assertThat(response.statusCode()).as("提交 %s: %s", type, response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");
    return response.body();
  }

  private SdState replayedSdState() {
    SimulationState state = shell.queryService().stateAt(QueryTarget.head(main()));
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private JsonNode detailJson(String id) throws Exception {
    HttpResponse<String> response = get("/api/sd/decision-makers/" + id);
    assertThat(response.statusCode()).as("详情 %s", id).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private JsonNode getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
    return JSON.readTree(response.body());
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

  /** 创世 {@code (main,1)}：一格国区域 + 三格走廊 + 单位 u-1；sd 为空（决策人由真命令种）。 */
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
                "map", new MapSnapshot(ref("main", 1), T7, nationMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, nationUnitState()),
                "social",
                    new SocialSnapshot(ref("main", 1), T7, new SocialData(new LinkedHashMap<>())),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  private static GameMap nationMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        R_NATION,
        Region.of(R_NATION, "种子国区域", Set.of(H11), new RegionMeta(null, "nation:n1", null, null)));
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

  private static UnitState nationUnitState() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(
        U1,
        new Unit(
            U1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            100,
            Map.of("步枪", 50),
            2,
            500,
            Optional.empty()));
    return new UnitState(units);
  }
}
