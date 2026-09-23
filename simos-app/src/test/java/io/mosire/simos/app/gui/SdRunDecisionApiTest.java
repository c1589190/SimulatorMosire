package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
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
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.spi.NationTag;
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
 * 「让它跑一轮」窄写端到端验收：{@code POST /api/sd/run-decision}。
 *
 * <p>★ **它补的是哪一处空白**：让某个决策人真跑一轮（真 LLM 自行读世界、出令）此前**只有 GM 的 MCP 窄工具做得到** ⇒ 界面上点不出来。本端点把同一条路接进工作台。
 *
 * <p>★ **两步走，且顺序有意义**（与 GM 侧 {@code RunDecisionTool} 同一套语义）：① 先落**触发事实**（{@code sd.RunDecision}，经
 * {@link CoreSimos#submit}，铁律 2 无例外）；② 再跑那一轮（世界版本取**刚落盘的新 head**）。 落盘失败就不跑。
 *
 * <p>★ **唯一替身是 LLM 客户端**（{@code FakeLlmClient}，本仓纪律：测试不打真网络）：真壳、真 store、真命令处理器、
 * 真工具面、真权限链，替身只替"怎么造客户端"。
 *
 * <p>★ **本轮只留两条**（用户 2026-09-23：少做测试，写完直接编译、我直接看）：一条钉"真经 Core + 轨迹读得回"， 一条钉"未知决策人被拒 ⇒ 触发事实也落不下"。
 */
class SdRunDecisionApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String PROVIDER_ID = "stub";
  private static final String DM_ID = "dm-fra";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");
  private static final RegionId R_FRA = new RegionId("701");

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 夹具决策人：国家 FRA 的决策人，**绑了** provider（没绑的话那一轮 fail-closed，跑不起来）。 */
  private static final DecisionMaker DM_FRA =
      new DecisionMaker(
          new DecisionMakerId(DM_ID),
          new Affiliation.Nation(new NationId("FRA")),
          Set.of(),
          AccessLimit.empty(),
          1,
          Optional.of(PROVIDER_ID),
          0L);

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;
  private FakeLlmClient llm;

  @BeforeEach
  void startShell() {
    seedGenesis();
    llm = new FakeLlmClient();
    // ★ 注入的只是"怎么造客户端"：未绑定 provider 的 fail-closed、世界、权限、落盘全是真的。
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0), providerId -> llm);
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
  void aRoundIsTriggeredThroughCoreAndTheTraceIsReadBackOnTheSameResponse() throws Exception {
    // 模型先读一格（真工具、真世界）⇒ 再收口。★ 刻意**不出令**：出令是敏感写、要过阻塞式审批（见端点注释）。
    llm.enqueue(LlmResponse.toolCall("call-1", "simos_map_hex", Map.of("q", 1, "r", 1)));
    llm.enqueue(LlmResponse.text("已读图，本 tick 无动作"));

    HttpResponse<String> response = post("/api/sd/run-decision", runDecisionBody(DM_ID, 1L));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("result").asText()).isEqualTo("committed");
    assertThat(body.get("ref").get("revision").asLong())
        .as("触发事实落在 (main,2)——**先落事实、再跑那一轮**")
        .isEqualTo(2L);
    assertThat(body.get("decisionMakerId").asText()).isEqualTo(DM_ID);
    assertThat(body.get("conversationId").asText())
        .as("会话 id 按世界事实（id + 会话世代）派生")
        .isEqualTo("decision-maker:" + DM_ID);
    assertThat(body.get("llmCalls").asInt()).as("一次工具调用 + 一次收口 = 两次模型调用（多轮，不是单轮）").isEqualTo(2);
    assertThat(body.get("abortedByBudget").asBoolean())
        .as("跑完了 ⇒ 这一位是**判别位**不是结论（不省字段，调用方一次判空即可）")
        .isFalse();
    assertThat(body.get("finalText").asText()).isEqualTo("已读图，本 tick 无动作");

    // ★★ 轨迹：决策人调了什么、看见了什么——这就是"本轮报告"的载体。
    JsonNode calls = body.get("toolCalls");
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).get("tool").asText())
        .as("真名（平台身份），不是模型的线名 simos_map_hex")
        .isEqualTo("simos.map.hex");
    assertThat(calls.get(0).get("ok").asBoolean()).isTrue();
    assertThat(calls.get(0).get("summary").asText())
        .as("★ 摘要是**回灌给模型的那段文本**的同源副本——真跑过才会带上夹具世界的地形名")
        .contains("desert");

    // ★ 真经 Core：revision 行上写着这条命令（铁律 2：连"谁让谁跑了一轮"也是世界事实）。
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      assertThat(
              new Timeline(store, CHECKPOINT_INTERVAL)
                  .row(ref("main", 2))
                  .orElseThrow()
                  .commandType())
          .isEqualTo("sd.RunDecision");
    }
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("这一轮没有出令 ⇒ 只有触发事实那一条 revision")
        .isEqualTo(2L);
  }

  @Test
  void anUnknownDecisionMakerIsRejectedAndLandsNoTriggerFact() throws Exception {
    HttpResponse<String> response = post("/api/sd/run-decision", runDecisionBody("dm-nope", 1L));

    assertThat(response.statusCode()).as("领域拒绝 ⇒ 422（与 /api/command 同表）").isEqualTo(422);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("result").asText()).isEqualTo("rejected");
    assertThat(body.get("reason").asText()).contains("决策人不存在");
    assertThat(llm.calls()).as("★ 触发事实没落盘 ⇒ 一次 LLM 都不烧").isZero();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).isEqualTo(1L);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────

  private static String runDecisionBody(String decisionMakerId, long expectedRevision) {
    return "{\"branch\":\"main\",\"expectedRevision\":"
        + expectedRevision
        + ",\"decisionMakerId\":\""
        + decisionMakerId
        + "\"}";
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

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  /** 创世 {@code (main,1)}：两格世界 + 国家 FRA（区域 701）+ 单位 u-1 + 一个绑了 provider 的决策人。 */
  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
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
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, genesisUnit())));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social",
                    new SocialSnapshot(ref("main", 1), T7, new SocialData(new LinkedHashMap<>())),
                "sd", new SdSnapshot(ref("main", 1), T7, sdState())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 一个**国家**决策人。 */
  private static SdState sdState() {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", R_FRA, 0));
    ArmyId a1 = new ArmyId("a1");
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(a1, new Army(a1, fra, U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_FRA.id(), DM_FRA);
    return SdState.empty().withNations(nations).withArmies(armies).withDecisionMakers(makers);
  }

  /** 两格世界（{@code (1,1)/(1,2)}，desert），区域 701（{@code nation:FRA}）覆盖两格。 */
  private static GameMap twoHexMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : List.of(H11, H12)) {
      hexes.put(coord, new HexCell(0.5));
    }
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    Region fra =
        Region.of(
            R_FRA,
            "区域 701",
            Set.of(H11, H12),
            new RegionMeta(null, NationTag.tagFor(new NationId("FRA")), null, null));
    regions.put(fra.id(), fra);
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 最小单位：自身带位置、无父、无路线、视野缺省（1）。 */
  private static Unit genesisUnit() {
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
}
