package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.observe.EventTypes;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.store.EventStore;
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
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
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
import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **判据①（spec §1.1 / §10.1 / §11 R1·R4）的端到端验收（M5 T11）**：GUI（玩家）与 MCP（Agent）对**同一个世界**、经**同一条**
 * {@code Command → ChangeSet → Revision} 路径写，两条写各产生一行 revision，落在**同一张** {@code revisions} 表，按
 * {@code initiator} 可辨，且各自的事件链完整。
 *
 * <p>★ **判别力来源**：两行 revision 用**独立 store + {@code Timeline}** 读回（不是问发出写的那个对象），{@code initiator}
 * 逐字断言（{@code player:gui} vs 配置的 {@code agent:t11-e2e}），correlationId 逐条取事件链（{@code received →
 * committed} 恰两条 = CommandEnvelope 支的冻结链）。仅当"GUI 与 MCP 走了同一条 Core 写路径"这两行才会同表同形状地出现。
 *
 * <p>★ 另跑一条真 {@code simos.advance}，断言其**冻结事件序列**（1×received + 1×started + N×proposal + 1×finished +
 * 1×committed，本壳 N = 1 个 time participant ⇒ 恰 5 条）。
 *
 * <p>夹具与 {@code McpServerTest}/{@code ShellApprovalTest} 同法：独立 store 种创世 {@code (main,1)} + 含
 * map/unit/social 三切片的创世 checkpoint（state 时间戳 {@code of(7)}）；端口全 0。
 */
class ShellEndToEndTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  /** GUI 写命令的 initiator 由 {@code GuiServer} 钉死（spec §九）；此处是断言字面量。 */
  private static final String PLAYER_INITIATOR = "player:gui";

  /** 与缺省 {@code agent:external-mcp} 不同，让"写死成别的值"这类变异当场现形（R4）。 */
  private static final String TEST_INITIATOR = "agent:t11-e2e";

  private static final Duration WAIT = Duration.ofSeconds(10);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private McpSyncClient client;

  @BeforeEach
  void startShell() {
    seedGenesis();
    ShellConfig base = ShellConfig.defaults(tempDir).withPorts(0, 0, 0, 0);
    shell =
        Shell.start(
            new ShellConfig(
                base.storeDir(),
                base.checkpointInterval(),
                base.guiPort(),
                base.mcpPort(),
                base.mcpPath(),
                base.approvalPort(),
                TEST_INITIATOR,
                base.mapId(),
                base.bindAddress(),
                base.decisionAgentMcpPort()));
    client = newClient();
  }

  @AfterEach
  void stopShell() {
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (Exception ignored) {
        // 关停清理失败不影响用例判定（server 侧仍会被 shell.close() 收掉）
      }
    }
    if (shell != null) {
      shell.close();
    }
  }

  @Test
  void playerAndAgentWritesShareOneTimelineWithDistinctInitiatorsAndCompleteChains()
      throws Exception {
    client.initialize();

    // ── 玩家路径：POST /api/command（unit.RenameUnit）⇒ revision 2 ──────────────────────
    String playerName = "玩家改的名";
    HttpResponse<String> playerResponse =
        post(
            "/api/command",
            JSON.writeValueAsString(
                Map.of(
                    "type",
                    "unit.RenameUnit",
                    "payloadJson",
                    "{\"id\":\"u-1\",\"name\":\"" + playerName + "\"}",
                    "branch",
                    "main",
                    "expectedRevision",
                    1)));
    assertThat(playerResponse.statusCode()).as(playerResponse.body()).isEqualTo(200);
    JsonNode playerResult = JSON.readTree(playerResponse.body());
    assertThat(playerResult.get("result").asText()).isEqualTo("committed");
    assertThat(playerResult.get("ref").get("revision").asLong()).as("玩家写落在 (main,2)").isEqualTo(2L);

    // ── Agent 路径：经真 MCP 传输提交同一命令（unit.RenameUnit）⇒ revision 3 ──────────────
    String agentName = "Agent改的名";
    McpSchema.CallToolResult agentResult =
        submitWithApproval(
            "unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"" + agentName + "\"}", 2L);
    assertThat(agentResult.isError()).as(wireText(agentResult)).isFalse();
    JsonNode agentBody = JSON.readTree(wireText(agentResult));
    assertThat(agentBody.get("result").asText()).isEqualTo("committed");
    assertThat(agentBody.get("ref").get("revision").asLong())
        .as("Agent 写落在同一分支的下一个 revision")
        .isEqualTo(3L);
    String agentCorrelation = agentBody.get("correlationId").asText();
    assertThat(agentCorrelation).isNotBlank();

    // ── 再跑一条真 simos.advance ⇒ revision 4（断言冻结事件链）──────────────────────────
    McpSchema.CallToolResult advanceResult = advanceWithApproval(3L, 7L, 9L);
    assertThat(advanceResult.isError()).as(wireText(advanceResult)).isFalse();
    JsonNode advanceBody = JSON.readTree(wireText(advanceResult));
    assertThat(advanceBody.get("ref").get("revision").asLong()).isEqualTo(4L);
    String advanceCorrelation = advanceBody.get("correlationId").asText();

    // ── 独立 store + Timeline/EventStore 读回：同一张 revisions 表 ──────────────────────
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      Timeline timeline = new Timeline(store, CHECKPOINT_INTERVAL);
      EventStore events = new EventStore(store);

      RevisionRow playerRow = timeline.row(ref("main", 2)).orElseThrow();
      RevisionRow agentRow = timeline.row(ref("main", 3)).orElseThrow();
      RevisionRow advanceRow = timeline.row(ref("main", 4)).orElseThrow();

      // ① 两行都在**同一张** revisions 表（同一个 store + Timeline 读得出），类型正确。
      assertThat(playerRow.commandType()).isEqualTo("unit.RenameUnit");
      assertThat(agentRow.commandType()).isEqualTo("unit.RenameUnit");
      assertThat(advanceRow.commandType()).isEqualTo("core.AdvanceTime");

      // ② initiator 逐字可辨（R4）。
      assertThat(playerRow.initiator()).as("玩家写经 GUI 端点 ⇒ player:gui").isEqualTo(PLAYER_INITIATOR);
      assertThat(agentRow.initiator())
          .as("Agent 写经 MCP ⇒ 配置的 mcpInitiator（逐字）")
          .isEqualTo(TEST_INITIATOR);
      assertThat(playerRow.initiator())
          .as("两条 initiator 必须真的不同（否则 isEqualTo 可能恒真）")
          .isNotEqualTo(agentRow.initiator());
      assertThat(advanceRow.initiator()).isEqualTo(TEST_INITIATOR);

      // ③ commandId = correlationId（C22 的单命令链），且两条写互不相同。
      assertThat(playerRow.commandId()).isEqualTo(playerRow.correlationId());
      assertThat(agentRow.commandId()).isEqualTo(agentRow.correlationId());
      assertThat(playerRow.correlationId()).isNotEqualTo(agentRow.correlationId());
      assertThat(agentRow.correlationId())
          .as("工具返回的 correlationId 就是落盘那一行的 correlationId")
          .isEqualTo(agentCorrelation);

      // ④ 事件链完整：信封支恰 [received, committed]；推进支 N=1 ⇒ 冻结 5 条序列。
      assertThat(types(events, playerRow.correlationId()))
          .as("玩家写的信封链完整")
          .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_COMMITTED);
      assertThat(types(events, agentRow.correlationId()))
          .as("Agent 写的信封链完整")
          .containsExactly(EventTypes.COMMAND_RECEIVED, EventTypes.COMMAND_COMMITTED);
      assertThat(types(events, advanceRow.correlationId()))
          .as("推进支的冻结序列（本壳 2 个 time participant（sd/unit）⇒ N = 2）")
          .containsExactly(
              EventTypes.COMMAND_RECEIVED,
              EventTypes.TIME_ADVANCE_STARTED,
              EventTypes.MODULE_PROPOSAL,
              EventTypes.MODULE_PROPOSAL,
              EventTypes.TIME_ADVANCE_FINISHED,
              EventTypes.COMMAND_COMMITTED);
      assertThat(advanceRow.correlationId()).isEqualTo(advanceCorrelation);
    }

    // ── 世界逐值反映两笔写：各自 revision 的重放状态 ─────────────────────────────────────
    assertThat(unitName(shell.coreSimos().replay(ref("main", 2))))
        .as("(main,2) 只看见玩家那笔写")
        .isEqualTo(playerName);
    assertThat(unitName(shell.coreSimos().replay(ref("main", 3))))
        .as("(main,3) 看见 Agent 随后那笔写（同一世界的顺序推进）")
        .isEqualTo(agentName);

    // 其余单位字段不因改名而变（证明重放整棵树、不是只搬了名字）。
    Unit afterAgent = unitOf(shell.coreSimos().replay(ref("main", 3)));
    assertThat(afterAgent.member()).isEqualTo(100);
    assertThat(afterAgent.equipment()).isEqualTo(Map.of("步枪", 50));
    assertThat(afterAgent.speed()).isEqualTo(2);
    assertThat(afterAgent.position().valueAt(T0)).contains(H11);

    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).isEqualTo(4L);
  }

  // ────────────────────────────── MCP 助手 ──────────────────────────────

  private McpSchema.CallToolResult submitWithApproval(
      String type, String payloadJson, long expectedRevision) throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", type);
    args.put("payloadJson", payloadJson);
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return callWithApproval(CommandSubmitTool.NAME, args);
  }

  private McpSchema.CallToolResult advanceWithApproval(long expectedRevision, long from, long to)
      throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    args.put("from", from);
    args.put("to", to);
    return callWithApproval("simos.advance", args);
  }

  /** 经真 MCP 传输调用写工具；等它进审批 ⇒ {@code APPROVE_ONCE} ⇒ 取结果（R3 的行为面）。 */
  private McpSchema.CallToolResult callWithApproval(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
    FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> client.callTool(request));
    Thread.ofVirtual().name("t11-mcp-write").start(task);
    String id = awaitPendingId(task);
    assertThat(id).as("写工具必须先进审批（MCP 客户端不得绕过审批闸）").isNotNull();
    assertThat(shell.pendingApprovals().decide(id, ApprovalDecision.APPROVE_ONCE, "test:gui"))
        .isTrue();
    return task.get(WAIT.toSeconds(), TimeUnit.SECONDS);
  }

  private String awaitPendingId(Future<McpSchema.CallToolResult> call) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      List<ApprovalRequest> pending = shell.pendingApprovals().pending();
      if (!pending.isEmpty()) {
        return pending.get(0).id();
      }
      if (call.isDone()) {
        return null;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("MCP 写工具既未进审批、也未结束（" + WAIT + " 内）——审批链装配异常");
  }

  private McpSyncClient newClient() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + shell.boundMcpPort())
            .endpoint(shell.config().mcpPath())
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return McpClient.sync(transport).build();
  }

  private static List<String> types(EventStore events, String correlationId) {
    return events.byCorrelation(correlationId).stream().map(EventRow::type).toList();
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }

  // ────────────────────────────── GUI 助手 ──────────────────────────────

  private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + shell.boundGuiPort() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private static String unitName(SimulationState state) {
    return unitOf(state).name();
  }

  private static Unit unitOf(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state().units().get(U1);
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
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
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
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        Map.of(),
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

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
