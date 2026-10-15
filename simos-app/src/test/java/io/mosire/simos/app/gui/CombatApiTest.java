package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
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
class CombatApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final RegionId R_NATION = new RegionId("r-nation");

  /**
   * ★★ 2026-10-13 收尾测试第 3 层：交战记录在 GUI 侧的**唯一**读口。旧的 {@code /api/sd/combats} 已随批次 3d-2 旧战斗族退役删除（打它只会
   * 404，那是"端点不存在"，不是"空库"）。
   */
  private static final String COMBATS = "/api/army/combats";

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
    HttpResponse<String> response = get(COMBATS);

    assertThat(response.statusCode()).as("空库不是错误").isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("combats")).as("字段在、是数组、为空").isNotNull().isEmpty();
  }

  // ── 判据：交战格 / 阶段 / 参与单位逐值（记录在案 = 真值）────────────────

  /**
   * 交战种在 {@code (1,2)}（单位 {@code u-1} 在 {@code (1,1)}）：GET 该路由，逐值断言记录内容。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 3 层：形状迁到新战斗契约</b>（批次 3d-2 旧战斗族整族退役）——旧形状的 {@code combatId}/{@code
   * combatStateId}/{@code name}/{@code currentStage}/{@code selectedOutcome} 都是**旧记录**的字段， 新读面
   * {@code ApiViews.armyCombat} 发的是 {@code {id,kind,tick,hex,participants,text,stages,losses}}
   * （阶段的概率表与判定在 {@code stages[].outcomes[]} 里）。断言跟着<b>新契约</b>走，不是删掉。
   *
   * <p>★ 判别力：把 {@code /api/army/combats} 写成"从单位同格推"会得到空集 ⇒ hex/阶段/participants 断言全红。
   */
  @Test
  void combatsReportsTheRecordedHexStageAndParticipants() throws Exception {
    createCombatAt(new HexCoord(1, 2));

    JsonNode combats = getJson(COMBATS).get("combats");

    assertThat(combats).as("恰一场交战").hasSize(1);
    JsonNode combat = combats.get(0);
    assertThat(combat.get("id").asText()).isEqualTo("c1");
    // ★ kind = **自定义交战状态**（D-009："轰城也是特殊交战状态"）——旧形状里的 name/combatStateId 两个槽位由它一个承担。
    assertThat(combat.get("kind").asText()).isEqualTo("野战");
    assertThat(combat.get("tick").asLong()).as("缺省 tick = 世界当前 tick（创世 7）").isEqualTo(7L);
    assertThat(combat.get("hex").get("q").asInt()).as("★ 交战格以记录为准").isEqualTo(1);
    assertThat(combat.get("hex").get("r").asInt()).isEqualTo(2);
    assertThat(combat.get("participants")).hasSize(1);
    assertThat(combat.get("participants").get(0).asText()).isEqualTo("u-1");
    // ★ stages 有序：记录级初始阶段（handler 合成 start/初始阶段）+ 追加的 s1（遭遇战）。
    JsonNode stages = combat.get("stages");
    assertThat(stages).as("初始阶段 + 追加阶段").hasSize(2);
    assertThat(stages.get(0).get("id").asText()).isEqualTo("start");
    assertThat(stages.get(1).get("id").asText()).isEqualTo("s1");
    assertThat(stages.get(1).get("name").asText()).isEqualTo("遭遇战");
    assertThat(stages.get(1).get("participants").get(0).asText()).isEqualTo("u-1");
    assertThat(stages.get(1).get("outcomes")).as("追加阶段带着概率表").hasSize(1);
    assertThat(stages.get(1).get("outcomes").get(0).get("label").asText()).isEqualTo("胜");
    assertThat(combat.get("losses")).as("尚未判定结局 ⇒ 无逐单位损失").isEmpty();
  }

  // ── 判据：记录与单位位置解耦；单位视图不再携带 combat ──────────────────────

  /**
   * ★★ <b>2026-10-13 收尾测试第 3 层：旧"单位详情 combat 子对象"整条判据的替代</b>。
   *
   * <p>旧形状里 {@code /api/unit/<id>} 带一个 {@code combat} 子对象（{@code atHex} 标"本单位在不在交战格"）。批次 3d-2 把
   * {@code combat} 一栏**从单位视图删除**（{@code ApiViews} 自述：不是恒 {@code null} ——留一条恒 null
   * 的键会让读侧以为"这个单位不参加任何交战"）。
   *
   * <p>⇒ 新契约的两条判据一起钉在这里：① 交战记录的读面在 army 自己的面，记录里的 hex 是**记录在案的真值**，与参与单位此刻在哪一格无关； ② 单位视图**没有**
   * {@code combat} 键（连记录存在时也没有）。
   */
  @Test
  void theRecordKeepsItsOwnHexEvenWhenTheUnitIsElsewhereAndTheUnitViewHasNoCombatKey()
      throws Exception {
    createCombatAt(new HexCoord(1, 2)); // 单位 u-1 的创世位置是 (1,1)

    JsonNode record = getJson(COMBATS).get("combats").get(0);
    assertThat(record.get("hex").get("q").asInt()).as("★ 记录格 = 记录时写下的那一格").isEqualTo(1);
    assertThat(record.get("hex").get("r").asInt()).isEqualTo(2);
    assertThat(record.get("participants").get(0).asText())
        .as("参与单位不必在交战格（引擎允许交战双方不同格）")
        .isEqualTo("u-1");

    JsonNode unit = getJson("/api/unit/u-1");
    assertThat(unit.has("combat")).as("★ 旧 combat 栏已删除：连记录存在时也不出现（不是恒 null 的空壳）").isFalse();
  }

  /** 交战格过滤（{@code ?q=&r=}）：记录格命中 ⇒ 一条；别格 ⇒ 空集（格的判据来自记录，不来自单位位置）。 */
  @Test
  void combatsCanBeFilteredByTheRecordedHex() throws Exception {
    createCombatAt(H11); // 与 u-1 的创世位置 (1,1) 同格

    assertThat(getJson(COMBATS + "?q=1&r=1").get("combats")).as("记录格命中").hasSize(1);
    assertThat(getJson(COMBATS + "?q=1&r=2").get("combats"))
        .as("★ 记录只落在 (1,1) ⇒ 问 (1,2) 必须是空集（不按单位位置糊弄）")
        .isEmpty();
  }

  /** 无交战的世界：{@code 200 {"combats":[]}}，且单位视图**同样**没有 {@code combat} 键（两种世界逐字同形）。 */
  @Test
  void aWorldWithoutRecordsReportsAnEmptyListAndStillNoCombatKey() throws Exception {
    assertThat(getJson(COMBATS).get("combats")).isEmpty();
    assertThat(getJson("/api/unit/u-1").has("combat")).isFalse();
  }

  // ── 判据：只读面口径（GET-only + 拒 as=）──────────────────────────────

  @Test
  void combatsEndpointOnlyAllowsGet() throws Exception {
    HttpResponse<String> response = post(COMBATS, "{}");

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow")).hasValue("GET");
  }

  /**
   * {@code as=} 视角参数**不被接受**（fail-closed）：本端点是配置/审计面，把它接成"以某决策人视角读"会静默泄露
   * 真实交战记录（那一格原本可能不在任何人的可见范围里）。
   */
  @Test
  void combatsRejectsAsPerspective() throws Exception {
    HttpResponse<String> response = get(COMBATS + "?as=dm-nope");

    assertThat(response.statusCode()).as("未接 redaction ⇒ 带 as= 显式拒绝").isEqualTo(400);
    assertThat(JSON.readTree(response.body()).get("error")).isNotNull();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 经真命令路径种一场交战：{@code c1}（participants=[u-1]，交战格 = {@code hex}），随后追加阶段 {@code s1}。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 3 层：载荷迁到新战斗契约</b>——
   *
   * <ul>
   *   <li>旧 {@code sd.CreateCombat} / {@code sd.AddCombatStage} 已随批次 3d-2 整族退役 ⇒ 写口 = {@code
   *       army.RecordCombat} / {@code army.AppendCombatStage}；
   *   <li><b>交战格在记录期给定</b>（{@code army.RecordCombat} 的 {@code hex}）：{@code AppendCombatStage} 的载荷
   *       <b>没有</b> {@code hex}/{@code combatStateId} 两个字段（旧的 {@code sd.AddCombatStage}
   *       有）——阶段只是记录内的一段演进， 换不了这场交战落在哪一格；
   *   <li>阶段的形状是 {@code {id,name,participants?,text?,outcomes?}}（旧形状的 {@code stageId}/{@code
   *       entry}/{@code exit}/{@code minDurationTicks}/{@code outcomes.options} 整组退役）。
   * </ul>
   */
  private void createCombatAt(HexCoord hex) throws Exception {
    submit(
        "army.RecordCombat",
        "{\"id\":\"c1\",\"kind\":\"野战\",\"hex\":{\"q\":"
            + hex.q()
            + ",\"r\":"
            + hex.r()
            + "},\"participants\":[\"u-1\"],\"text\":\"甲战役\"}");
    submit(
        "army.AppendCombatStage",
        "{\"combatId\":\"c1\",\"stage\":{\"id\":\"s1\",\"name\":\"遭遇战\",\"participants\":[\"u-1\"],"
            + "\"text\":\"遭遇战\",\"outcomes\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":1}]}}");
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
                "ga", new GaSnapshot(ref("main", 1), T7, GaState.empty()),
                // ★★ 2026-10-13 收尾测试第 3 层：交战记录住 army 切片（批次 3d-2 起不再住 sd/ga）
                //   ⇒ 缺这片时 `army.RecordCombat` 直接 500（装配故障），读侧也无从谈起。
                "gov", new GovSnapshot(ref("main", 1), T7, GovState.empty()),
                "army", new ArmySnapshot(ref("main", 1), T7, ArmyData.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new GovCodec(),
                    new ArmyCodec(),
                    new GaCodec())));
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
