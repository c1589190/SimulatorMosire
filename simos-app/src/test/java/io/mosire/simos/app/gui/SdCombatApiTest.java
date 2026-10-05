package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
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
 * 交战只读查询面端到端验收（2026-09-24，用户点名的改法）：起真 {@link Shell}（四端口全 0），用 JDK {@link HttpClient} 打真 HTTP。
 *
 * <p>★ **夹具走真命令路径**：创世 checkpoint 只种地图（三格走廊）+ 单位 {@code u-1}（在 {@code (1,1)}），sd 切片为空； 然后经 {@code
 * POST /api/command} 发 {@code sd.CreateCombat} + {@code sd.AddCombatStage}（首阶段给 {@code
 * combatStateId}/{@code hex}）——读的就是**真命令写出来的交战记录**，不是手搭的 sd 实体。
 *
 * <p>★ **覆盖三条主判据**：① 空库 ⇒ {@code 200 {"combats":[]}}（非 404/500）；② {@code /api/sd/combats} 的
 * hex/阶段/participants 与记录逐值一致（★ 记录在案的格是真值，不再靠 GUI 推断）；③ 参与单位**不在交战格**时，单位详情 {@code
 * combat.atHex=false} 且仍能读出 combatId/currentStage/hex（引擎允许交战双方不同格）。
 */
class SdCombatApiTest {

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

  // ── 判据：空库 ⇒ 空列表（不是 500）────────────────────────────────────

  @Test
  void emptyLibraryGivesEmptyListNotAnError() throws Exception {
    HttpResponse<String> response = get("/api/sd/combats");

    assertThat(response.statusCode()).as("空库不是错误").isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("combats")).as("字段在、是数组、为空").isNotNull().isEmpty();
  }

  // ── 判据：交战格 / 阶段 / 参与单位逐值（记录在案 = 真值）────────────────

  /**
   * 交战种在 {@code (1,2)}（单位 {@code u-1} 在 {@code (1,1)}）：GET 该路由，逐值断言记录内容。
   *
   * <p>★ 判别力：把 {@code /api/sd/combats} 写成"从单位同格推"（旧 GUI 口径）会得到空集 ⇒ hex/名字/阶段 断言全红。
   */
  @Test
  void combatsReportsTheRecordedHexStageAndParticipants() throws Exception {
    createCombatAt(new HexCoord(1, 2));

    JsonNode combats = getJson("/api/sd/combats").get("combats");

    assertThat(combats).as("恰一场交战").hasSize(1);
    JsonNode combat = combats.get(0);
    assertThat(combat.get("combatId").asText()).isEqualTo("c1");
    assertThat(combat.get("combatStateId").asText()).isEqualTo("cs1");
    assertThat(combat.get("name").asText()).isEqualTo("甲战役");
    assertThat(combat.get("hex").get("q").asInt()).as("★ 交战格以记录为准").isEqualTo(1);
    assertThat(combat.get("hex").get("r").asInt()).isEqualTo(2);
    assertThat(combat.get("currentStage").asText()).isEqualTo("s1");
    assertThat(combat.get("currentStageName").asText()).isEqualTo("遭遇战");
    assertThat(combat.get("selectedOutcome").isNull()).as("尚未选结局 ⇒ null").isTrue();
    assertThat(combat.get("participants")).hasSize(1);
    assertThat(combat.get("participants").get(0).asText()).isEqualTo("u-1");
    assertThat(combat.get("participantCount").asInt()).isEqualTo(1);
    assertThat(combat.get("participantsAtHex")).as("u-1 在 (1,1)，不在交战格 (1,2)").isEmpty();
    assertThat(combat.get("participantsAtHexCount").asInt()).isEqualTo(0);
  }

  // ── 判据：跨格 —— 单位详情的 combat 子对象 ─────────────────────────────

  /** 单位不在交战格：{@code combat} 里能读出 combatId/currentStage/hex，且 {@code atHex=false}。 */
  @Test
  void unitDetailExposesItsCombatAndFlagsWhenTheUnitIsNotOnTheCombatHex() throws Exception {
    createCombatAt(new HexCoord(1, 2));

    JsonNode unit = getJson("/api/unit/u-1");

    JsonNode combat = unit.get("combat");
    assertThat(combat).as("★ 记录在案 ⇒ 单位详情带着所属交战").isNotNull();
    assertThat(combat.get("combatId").asText()).isEqualTo("c1");
    assertThat(combat.get("name").asText()).isEqualTo("甲战役");
    assertThat(combat.get("currentStage").asText()).isEqualTo("s1");
    assertThat(combat.get("hex").get("q").asInt()).isEqualTo(1);
    assertThat(combat.get("hex").get("r").asInt()).isEqualTo(2);
    assertThat(combat.get("atHex").asBoolean()).as("★ 本单位不在交战格（引擎不要求交战双方同格）").isFalse();
  }

  /** 单位**就在**交战格：同一份 {@code combat} 视图里 {@code atHex=true}。 */
  @Test
  void unitOnTheCombatHexReportsAtHexTrue() throws Exception {
    createCombatAt(H11); // 与 u-1 的创世位置 (1,1) 同格

    JsonNode combat = getJson("/api/unit/u-1").get("combat");

    assertThat(combat).isNotNull();
    assertThat(combat.get("atHex").asBoolean()).isTrue();
    assertThat(combat.get("hex").get("q").asInt()).isEqualTo(1);
    assertThat(combat.get("hex").get("r").asInt()).isEqualTo(1);
  }

  /** 无交战的单位 ⇒ {@code combat} 是 JSON {@code null}（**不拿空对象顶替**）。 */
  @Test
  void unitWithoutAnyCombatReportsNullCombat() throws Exception {
    JsonNode unit = getJson("/api/unit/u-1");

    assertThat(unit.get("combat").isNull()).as("无交战 ⇒ null（不是 {}）").isTrue();
  }

  // ── 判据：只读面口径（GET-only + 拒 as=）──────────────────────────────

  @Test
  void combatsEndpointOnlyAllowsGet() throws Exception {
    HttpResponse<String> response = post("/api/sd/combats", "{}");

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow")).hasValue("GET");
  }

  /**
   * {@code as=} 视角参数**不被接受**（fail-closed）：本端点是配置/审计面，把它接成"以某决策人视角读"会静默泄露
   * 真实交战记录（那一格原本可能不在任何人的可见范围里）。
   */
  @Test
  void combatsRejectsAsPerspective() throws Exception {
    HttpResponse<String> response = get("/api/sd/combats?as=dm-nope");

    assertThat(response.statusCode()).as("未接 redaction ⇒ 带 as= 显式拒绝").isEqualTo(400);
    assertThat(JSON.readTree(response.body()).get("error")).isNotNull();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 经真命令路径种一场交战：{@code c1/甲战役}（participants=[u-1]），首阶段 {@code s1} 落在 {@code hex}。 */
  private void createCombatAt(HexCoord hex) throws Exception {
    submit("sd.CreateCombat", "{\"combatId\":\"c1\",\"name\":\"甲战役\",\"participants\":[\"u-1\"]}");
    submit(
        "sd.AddCombatStage",
        "{\"combatId\":\"c1\",\"combatStateId\":\"cs1\",\"hex\":{\"q\":"
            + hex.q()
            + ",\"r\":"
            + hex.r()
            + "},\"stage\":{\"stageId\":\"s1\",\"name\":\"遭遇战\",\"participants\":[\"u-1\"],"
            + "\"entry\":[{\"@class\":\"at_or_after_tick\",\"tick\":0}],"
            + "\"exit\":[{\"@class\":\"at_or_after_tick\",\"tick\":5}],"
            + "\"minDurationTicks\":0,\"maxDurationTicks\":10,"
            + "\"outcomes\":{\"options\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":1}]}}}");
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

  /** 创世 {@code (main,1)}：一格国区域 + 三格走廊 + 单位 u-1（在 {@code (1,1)}）；sd 为空。 */
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
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty()));
    return new UnitState(units);
  }
}
