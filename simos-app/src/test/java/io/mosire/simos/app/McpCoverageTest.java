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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * ★★ **判据②（spec §1.1 / §10.2 / §11 R5）的端到端验收（M5 T11）**：{@code simos.command.catalog} 列出的**每一个**
 * 已注册命令类型，都能经 MCP 工具面（{@code simos.command.submit}）提交并真的生效；{@code simos.advance} 与 {@code
 * simos.fork} 同样可达且有效；反向：坏载荷 ⇒ {@code Rejected} 且**不留 revision**。
 *
 * <p>★ **判别力来源**：catalog 的 type 集合与"逐类提交后分支 head 恰好前进该类型数"两侧同时钉住——任何一条命令没到（工具面返回 unsupported /
 * 类型未注册 / 载荷约定不符）都会让 head 对不上或该条直接报错。每条提交的结局逐类记在 {@code [T11-COVERAGE]} 行里。
 *
 * <p>★ **执行序不是 catalog 序**（catalog 按字典序：CancelRoute 在 DisbandUnit 前）：{@code DisbandUnit} 会移除 {@code
 * u-1}， 之后的命令就查无此人 ⇒ 本用例按**语义合法序**跑（先建第二个单位，改名/编制/改编/定位/路线/取消都在 {@code u-1} 上，最后解散它）。
 *
 * <p>夹具与 {@code McpServerTest}/{@code ShellEndToEndTest} 同法：独立 store 种创世 {@code (main,1)} + 含
 * map/unit/social 三切片的创世 checkpoint（state 时间戳 {@code of(7)}）；端口全 0。
 */
class McpCoverageTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final String TEST_INITIATOR = "agent:t11-coverage";

  /** catalog 预期的 9 个已注册命令类型（与 {@code Shell} 注册的 handler 同源）。 */
  private static final List<String> EXPECTED_COMMAND_TYPES =
      List.of(
          "unit.RenameUnit",
          "unit.CreateUnit",
          "unit.ReparentUnit",
          "unit.SetStrength",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.DisbandUnit",
          "map.SetTerrain");

  /** 每类的**最小合法载荷**（对夹具世界；顺序即语义合法序）。 */
  private static final Map<String, String> MINIMAL_PAYLOADS = new LinkedHashMap<>();

  static {
    MINIMAL_PAYLOADS.put(
        "unit.CreateUnit",
        "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":2},\"member\":50,"
            + "\"equipment\":{\"步枪\":10},\"speed\":2,\"mobilityPerMille\":500}");
    MINIMAL_PAYLOADS.put("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"一改\"}");
    MINIMAL_PAYLOADS.put(
        "unit.SetStrength", "{\"id\":\"u-1\",\"member\":120,\"equipment\":{\"步枪\":60}}");
    MINIMAL_PAYLOADS.put("unit.ReparentUnit", "{\"id\":\"u-1\",\"parent\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.PlaceAt", "{\"id\":\"u-1\",\"hex\":{\"q\":1,\"r\":2}}");
    MINIMAL_PAYLOADS.put(
        "unit.PlanRoute", "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}]}");
    MINIMAL_PAYLOADS.put("unit.CancelRoute", "{\"id\":\"u-1\"}");
    MINIMAL_PAYLOADS.put("unit.DisbandUnit", "{\"id\":\"u-1\"}");
    MINIMAL_PAYLOADS.put(
        "map.SetTerrain", "{\"hexes\":[{\"q\":1,\"r\":3}],\"terrain\":\"plains\"}");
  }

  private static final Duration WAIT = Duration.ofSeconds(10);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private McpSyncClient client;

  @BeforeEach
  void startShell() {
    seedGenesis();
    ShellConfig base = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);
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
                base.mapId()));
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
  void everyCatalogTypeIsReachableThroughMcpAndTakesEffect() throws Exception {
    client.initialize();

    // 1. catalog 经 MCP 读回，与注册面一致（R5 的载体）。
    List<String> catalogTypes = catalogTypes();
    assertThat(catalogTypes)
        .as("catalog 列出的 type 与 Shell 注册的 9 个 handler 同源")
        .containsExactlyInAnyOrderElementsOf(EXPECTED_COMMAND_TYPES);
    assertThat(MINIMAL_PAYLOADS.keySet())
        .as("用例为每个 catalog type 都备了载荷（漏一个就会在这里红）")
        .containsExactlyInAnyOrderElementsOf(catalogTypes);

    // 2. 逐类经 MCP 提交（每条都过审批 APPROVE_ONCE），断言全部 commit 且 head 逐条前进。
    List<String> coverage = new ArrayList<>();
    long expectedRevision = 1L;
    for (Map.Entry<String, String> entry : MINIMAL_PAYLOADS.entrySet()) {
      McpSchema.CallToolResult result =
          submitWithApproval(entry.getKey(), entry.getValue(), expectedRevision);
      assertThat(result.isError())
          .as("type=%s 必须经 MCP 可提交并生效: %s", entry.getKey(), wireText(result))
          .isFalse();
      JsonNode body = JSON.readTree(wireText(result));
      assertThat(body.get("result").asText()).as("type=%s", entry.getKey()).isEqualTo("committed");
      expectedRevision++;
      assertThat(body.get("ref").get("revision").asLong())
          .as("type=%s 的提交落在 (main,%d)", entry.getKey(), expectedRevision)
          .isEqualTo(expectedRevision);
      coverage.add(
          "[T11-COVERAGE] type="
              + entry.getKey()
              + " result=committed revision="
              + expectedRevision);
    }
    for (String line : coverage) {
      System.out.println(line);
    }
    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).as("9 条命令各推一格").isEqualTo(10L);

    // 3. 世界真的变了（不是"没报错"）：u-1 被解散、只剩 CreateUnit 建的 u-2。
    SimulationState afterUnitCommands = shell.coreSimos().replay(ref("main", 10));
    UnitState units = unitSlice(afterUnitCommands);
    assertThat(units.units().keySet())
        .as("u-1 已被 DisbandUnit 解散，只剩 u-2")
        .containsExactly(new UnitId("u-2"));
    assertThat(units.units().get(new UnitId("u-2")).name()).isEqualTo("第二连");
    assertThat(units.units().get(new UnitId("u-2")).member()).isEqualTo(50);

    // 4. simos.advance 经 MCP 可达且有效。
    McpSchema.CallToolResult advance = advanceWithApproval(10L, 7L, 9L);
    assertThat(advance.isError()).as(wireText(advance)).isFalse();
    JsonNode advanceBody = JSON.readTree(wireText(advance));
    assertThat(advanceBody.get("result").asText()).isEqualTo("committed");
    assertThat(advanceBody.get("ref").get("revision").asLong()).isEqualTo(11L);
    System.out.println("[T11-COVERAGE] tool=simos.advance result=committed revision=11");
    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).isEqualTo(11L);

    // 5. simos.fork 经 MCP 可达且有效（新分支 head = 1）。
    McpSchema.CallToolResult fork = forkWithApproval("main", 11L, "mcp-branch");
    assertThat(fork.isError()).as(wireText(fork)).isFalse();
    JsonNode forkBody = JSON.readTree(wireText(fork));
    assertThat(forkBody.get("result").asText()).isEqualTo("committed");
    assertThat(forkBody.get("ref").get("branch").asText()).isEqualTo("mcp-branch");
    assertThat(forkBody.get("ref").get("revision").asLong()).isEqualTo(1L);
    System.out.println(
        "[T11-COVERAGE] tool=simos.fork result=committed branch=mcp-branch revision=1");
    assertThat(shell.coreSimos().branches().stream().map(BranchId::value).toList())
        .as("fork 真的建出了新分支")
        .contains("main", "mcp-branch");
    assertThat(shell.coreSimos().head(new BranchId("mcp-branch")).orElseThrow().value())
        .isEqualTo(1L);

    // 6. 反向：坏载荷 ⇒ REJECTED 且不留 revision（按行数计）。
    long revisionsBefore = revisionRowCount();
    long headBefore = shell.coreSimos().head(main()).orElseThrow().value();
    McpSchema.CallToolResult bad =
        submitWithApproval("unit.RenameUnit", "{\"id\":\"u-2\"}", headBefore);
    assertThat(bad.isError()).as("缺 name 的载荷必须被拒（不得静默提交）: %s", wireText(bad)).isTrue();
    assertThat(wireText(bad)).startsWith("[mosire:code=REJECTED]");
    JsonNode badBody = JSON.readTree(wireText(bad).substring("[mosire:code=REJECTED]".length()));
    assertThat(badBody.get("result").asText()).isEqualTo("rejected");
    assertThat(badBody.get("reason").asText()).isNotBlank();
    System.out.println("[T11-COVERAGE] reverse=bad-payload result=rejected");
    assertThat(revisionRowCount()).as("被拒的命令不得多留一行 revision").isEqualTo(revisionsBefore);
    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).isEqualTo(headBefore);
  }

  // ────────────────────────────── MCP 助手 ──────────────────────────────

  private List<String> catalogTypes() throws Exception {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("simos.command.catalog", Map.of()));
    assertThat(result.isError()).as(wireText(result)).isFalse();
    JsonNode types = JSON.readTree(wireText(result)).get("types");
    List<String> out = new ArrayList<>();
    types.forEach(node -> out.add(node.asText()));
    return out;
  }

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

  private McpSchema.CallToolResult forkWithApproval(
      String source, long expectedRevision, String target) throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("source", source);
    args.put("expectedRevision", expectedRevision);
    args.put("newBranch", target);
    return callWithApproval("simos.fork", args);
  }

  /** 经真 MCP 传输调用写工具；等它进审批 ⇒ {@code APPROVE_ONCE} ⇒ 取结果。 */
  private McpSchema.CallToolResult callWithApproval(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
    FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> client.callTool(request));
    Thread.ofVirtual().name("t11-mcp-call").start(task);
    String id = awaitPendingId(task);
    assertThat(id).as("%s 必须先进审批", toolName).isNotNull();
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

  /** 独立 store 读 {@code revisions} 行数（"拒绝不留 revision"的按行断言）。 */
  private long revisionRowCount() {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      return store.inTransaction(
          connection -> {
            try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM revisions")) {
              return rows.next() ? rows.getLong(1) : 0L;
            }
          });
    }
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

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private static UnitState unitSlice(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state();
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
