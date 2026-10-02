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
 * 决策记录只读面端到端验收：{@code GET /api/sd/directives[?decisionMakerId=<id>]}。
 *
 * <p>★ **本端点存在的理由**：{@code /api/sd/decision-makers/{id}} 只报 {@code lastDirectiveTick} / {@code
 * ticksSinceLast}——"最近一次在第几 tick"看得见，**令的内容一个字都看不见**。而 {@code Directive}（执行原文 +
 * 结构化命令清单）本来就在世界状态里、本来就可回放，缺的只是这一个读口（铁律 1：界面是视图，不是真相的第二份）。
 *
 * <p>★ 夹具经**真命令路径**（{@code sd.IssueDirective}）种入，不走"直接构造 SdState"：这样"写侧写的"与"读侧读的"
 * 走同一条链——直接造状态的话，两侧各错一半也照样全绿。
 *
 * <p>★ **本轮只留两条**（用户 2026-09-23：少做测试，写完直接编译、我直接看）：一条钉返回形状 + 降序 + 过滤，一条钉 fail-closed 的两个方向（未知 id ⇒
 * 404、{@code as=} ⇒ 400）。
 */
class SdDirectivesApiTest {

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

  /** 决策目标（canonical 形式由 {@code Address.canonical()} 产出；这里断言的就是它）。 */
  private static final String TARGET = "map:Map1:hex.1_2";

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
  void directivesExposeTheIntentTextAndTheCommandListNewestFirst() throws Exception {
    createFixture();
    submit(
        "sd.IssueDirective",
        "{\"directiveId\":\"d-1\",\"decisionMakerId\":\"dm-1\",\"tick\":4,"
            + "\"target\":\""
            + TARGET
            + "\",\"intentInfo\":\"沿河谷向北推进（补给吃紧）\","
            + "\"commands\":[{\"type\":\"unit.PlanRoute\",\"payloadJson\":\"{\\\"unitId\\\":\\\"u-1\\\"}\"}]}");
    submit(
        "sd.IssueDirective",
        "{\"directiveId\":\"d-2\",\"decisionMakerId\":\"dm-1\",\"tick\":5,"
            + "\"intentInfo\":\"就地固守，等援军\",\"commands\":[]}");

    JsonNode all = directivesJson("/api/sd/directives");
    assertThat(all).as("两条真指令逐条读出（不多不少）").hasSize(2);
    // ★ 顺序由服务端定（tick 降序）⇒ 界面上第一条就是"最近一次出令"。前端不重排。
    JsonNode newest = all.get(0);
    assertThat(newest.get("directiveId").asText()).as("新的在前（tick 5 > tick 4）").isEqualTo("d-2");
    assertThat(all.get(1).get("directiveId").asText()).isEqualTo("d-1");

    JsonNode older = all.get(1);
    assertThat(older.get("decisionMakerId").asText()).isEqualTo("dm-1");
    assertThat(older.get("tick").asLong()).isEqualTo(4L);
    assertThat(older.get("target").asText())
        .as("target 以 canonical 形式送出（Directive.target 是 Optional<Address>）")
        .isEqualTo(TARGET);
    assertThat(older.get("intentInfoKey").asText())
        .as("★ 执行原文的 key 也报出来（它与 intentInfo 不互相推导）")
        .isEqualTo("intent");
    assertThat(older.get("intentInfo").asText())
        .as("★★ 这一条才是本端点的要害：决心/理由的**原文**（从 sd INFO 覆盖层取回）")
        .isEqualTo("沿河谷向北推进（补给吃紧）");
    JsonNode commands = older.get("commands");
    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).get("type").asText()).isEqualTo("unit.PlanRoute");
    assertThat(commands.get(0).get("payloadJson").asText()).isEqualTo("{\"unitId\":\"u-1\"}");
    assertThat(older.get("effects")).as("无效果引用 ⇒ 空数组（不是缺字段）").isEmpty();
    assertThat(older.get("verdict").isNull()).as("未挂判决 ⇒ null（不拿空串顶替）").isTrue();
    assertThat(older.get("status").asText()).isEqualTo("ISSUED");
    // 无目标那条如实报 null（"没有目标"≠"目标是个空串"）。
    assertThat(newest.get("target").isNull()).isTrue();
    assertThat(newest.get("intentInfo").asText()).isEqualTo("就地固守，等援军");

    // 过滤：同一个人的那条链上写的就是这两条。
    assertThat(directivesJson("/api/sd/directives?decisionMakerId=dm-1"))
        .as("按决策人过滤 == 不筛（本夹具只有一个决策人）")
        .hasSize(2);
  }

  @Test
  void failClosedOnUnknownDecisionMakerAndOnTheActorParameter() throws Exception {
    createFixture();

    HttpResponse<String> unknown = get("/api/sd/directives?decisionMakerId=dm-nope");
    assertThat(unknown.statusCode())
        .as("未知决策人 ⇒ 404（**不是空列表**：空列表只表示「这个人还没出过令」，折成同一个响应会把没查到伪装成不存在）")
        .isEqualTo(404);
    assertThat(JSON.readTree(unknown.body()).get("error").asText()).contains("not found");

    HttpResponse<String> withActor = get("/api/sd/directives?as=dm-1");
    assertThat(withActor.statusCode())
        .as("审计/配置面接 as= 等于「用甲的身份读出乙下的令」⇒ fail-closed 400，不静默忽略")
        .isEqualTo(400);
    assertThat(JSON.readTree(withActor.body()).get("error").asText()).contains("as=");
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────

  /** 经真命令路径种入：一个国家 + 一个决策人。 */
  private void createFixture() throws Exception {
    submit(
        "sd.CreateNation",
        "{\"nationId\":\"n1\",\"name\":\"甲国\",\"homeRegionId\":\"r-nation\",\"adminBudgetPerTick\":10}");
    submit(
        "sd.CreateDecisionMaker",
        "{\"id\":\"dm-1\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
            + "\"allowedTools\":[],\"cadence\":3}");
  }

  private JsonNode directivesJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("%s: %s", path, response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body()).get("directives");
    assertThat(body).as("响应必须是 {directives:[…]} 形状").isNotNull();
    return body;
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

  /** 创世 {@code (main,1)}：甲乙两国各一片区域（甲 3 格、乙 1 格）+ 单位 u-1 在 (1,1)；sd 为空。 */
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
            List.of(new CompositionEntry("步兵", 100)),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty()));
    return new UnitState(units);
  }
}
