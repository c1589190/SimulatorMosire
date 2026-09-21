package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.GuiServer;
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
 * 审批装配验收（M5 T6，spec §7.3/S5/R3）：写工具**未审批不得执行**，审批放行才提交；5711 的 {@code /api/approvals} 是 AgentLib
 * 端点的**透传代理**（语义不复制）。
 *
 * <p>★ **{@code APPROVE_SESSION} 的收窄（本用例的实测口径）**：审批请求的 {@code callerKey} 取自 {@code
 * ToolContext.caller().name()}，即 {@link AccessToken} 的**桶名**（{@code GUEST/DEFAULT/SYSTEM}），**不是**
 * spec §九 的 initiator 串（{@code player:gui} / {@code agent:…}）。本用例的调用者桶是 {@code DEFAULT} ⇒
 * 人对会话级放行的答复（{@code scope=session}）被 AgentLib 的 {@code effectiveDecision} **收窄为一次**，除非 {@code
 * sessionGrantable}（缺省只认 {@code "SYSTEM"}）。代理回执里 {@code scope=="once"} 正来自这里——收窄只在 AgentLib
 * 发生，代理逐字透传、不复制规则。
 *
 * <p>夹具与 {@code SimosToolsTest}/{@code GuiApiTest} 同法：独立 store 种创世 {@code (main,1)} + 含
 * map/unit/social 三切片的 checkpoint（state 时间戳 {@code of(7)}）。写工具经真 {@link Shell#toolAuthorizer()} 执行，
 * 身份用**独立 store + {@code Timeline}** 读回（R4 的判别力来源）。
 */
class ShellApprovalTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 {@code agent:external-mcp} 不同，让"写死成别的值"这类变异当场现形（R4/R3）。 */
  private static final String TEST_INITIATOR = "agent:t6-test";

  /** 端口全 0：GUI 与审批端点都由 OS 分配随机端口（spec §3.1）。 */
  private static final Duration WAIT = Duration.ofSeconds(10);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;

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
                base.mapId(),
                base.bindAddress()));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── R3：未审批的写不得执行；放行后提交 ────────────────────────────────

  @Test
  void unapprovedWriteIsDeniedAndApprovedWriteCommits() throws Exception {
    // 第一轮：DENY ⇒ APPROVAL_DENIED 且 head 不动（未审批的写不得产生 revision）
    Future<ToolResult> denied = startRename("未批准的改名");
    String deniedId = awaitPendingId(denied);
    assertThat(deniedId).as("R3：写工具必须先进审批（不得未经审批就执行）——未出现待审批项即为闸门失效").isNotNull();
    assertThat(shell.pendingApprovals().decide(deniedId, ApprovalDecision.DENY, "test:gui"))
        .isTrue();
    ToolResult deniedResult = denied.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(deniedResult.success()).isFalse();
    assertThat(deniedResult.code()).isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(head()).as("R3：未审批 / 被拒的写调用不得留下 revision").isEqualTo(1L);

    // 第二轮：APPROVE_ONCE ⇒ 写提交、revision 前进
    Future<ToolResult> approved = startRename("批准后的第一连");
    String approvedId = awaitPendingId(approved);
    assertThat(approvedId).as("放行轮：写工具也应进审批").isNotNull();
    assertThat(
            shell.pendingApprovals().decide(approvedId, ApprovalDecision.APPROVE_ONCE, "test:gui"))
        .isTrue();
    ToolResult approvedResult = approved.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(approvedResult.success()).as(approvedResult.message()).isTrue();
    assertThat(head()).as("放行后 revision 前进到 2").isEqualTo(2L);

    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 2)).orElseThrow();
      assertThat(row.initiator()).as("MCP 写命令的 initiator 恰是配置值").isEqualTo(TEST_INITIATOR);
      assertThat(row.commandType()).isEqualTo("unit.RenameUnit");
    }
    assertThat(unitName(shell.coreSimos().replay(ref("main", 2))))
        .as("放行后的改名真的生效（不是「没报错」）")
        .isEqualTo("批准后的第一连");
  }

  // ── 代理：透传 AgentLib 端点的 200/409/404/405 ────────────────────────

  @Test
  void approvalProxyForwardsListDecisionConflictAndUnknown() throws Exception {
    assertThat(shell.boundApprovalPort()).as("审批端点真的绑定在监听（spec §3.2 第 4 步）").isPositive();

    Future<ToolResult> call = startRename("代理批准的第一连");
    String id = awaitPendingId(call);
    assertThat(id).isNotNull();

    JsonNode pending = getJson(shell.boundGuiPort(), "/api/approvals").get("pending");
    assertThat(pending).as("GET /api/approvals 列出待裁决项（登记表快照）").hasSize(1);
    JsonNode row = pending.get(0);
    assertThat(row.get("id").asText()).isEqualTo(id);
    assertThat(row.get("tool").asText()).isEqualTo(CommandSubmitTool.NAME);
    assertThat(row.get("classKey").asText()).isEqualTo(CommandSubmitTool.NAME);

    // 会话级答复对 DEFAULT 桶收窄为一次：回执 scope 必须是 AgentLib 算出的 "once"，代理不改语义。
    HttpResponse<String> decision =
        exchange(
            shell.boundGuiPort(),
            "POST",
            "/api/approvals/" + id,
            "{\"decision\":\"approve\",\"scope\":\"session\",\"by\":\"gui\"}");
    assertThat(decision.statusCode()).isEqualTo(200);
    JsonNode receipt = JSON.readTree(decision.body());
    assertThat(receipt.get("decision").asText()).isEqualTo("approve");
    assertThat(receipt.get("scope").asText())
        .as("DEFAULT 桶非 sessionGrantable ⇒ APPROVE_SESSION 收窄为 once（AgentLib effectiveDecision）")
        .isEqualTo("once");

    HttpResponse<String> repeat =
        exchange(shell.boundGuiPort(), "POST", "/api/approvals/" + id, "{\"decision\":\"deny\"}");
    assertThat(repeat.statusCode()).as("重复决议 ⇒ 409（幂等保护，端点判定）").isEqualTo(409);
    assertThat(getJson(shell.boundGuiPort(), "/api/approvals").get("pending"))
        .as("已决议项不再是待裁决")
        .isEmpty();

    assertThat(
            exchange(
                    shell.boundGuiPort(),
                    "POST",
                    "/api/approvals/ap-nope",
                    "{\"decision\":\"deny\"}")
                .statusCode())
        .as("未知 id ⇒ 404")
        .isEqualTo(404);

    HttpResponse<String> wrongMethod =
        exchange(shell.boundGuiPort(), "GET", "/api/approvals/ap-nope", null);
    assertThat(wrongMethod.statusCode()).as("错误方法 ⇒ 405（端点判定）").isEqualTo(405);
    assertThat(wrongMethod.headers().firstValue("Allow").orElse(""))
        .as("Allow 头原样透传")
        .isEqualTo("POST");

    ToolResult result = call.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(head()).isEqualTo(2L);
  }

  @Test
  void approvalsProxyReturns502WhenEndpointIsUnreachable() throws Exception {
    try (GuiServer unreachable =
        new GuiServer(
            shell.queryService(),
            shell.coreSimos(),
            shell.config().mapId(),
            "http://127.0.0.1:1")) {
      unreachable.start("127.0.0.1", 0);

      HttpResponse<String> response =
          exchange(unreachable.boundPort(), "GET", "/api/approvals", null);
      assertThat(response.statusCode()).as("端点不可达 ⇒ 502").isEqualTo(502);
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 在虚拟线程上经**真 {@code toolAuthorizer}** 执行 {@code simos.command.submit}（改名）。
   *
   * <p>调用者桶 = {@link AccessToken#DEFAULT}（写工具 spec 要求该级别），身份 = 外部 MCP 面（{@link
   * AgentIdentity#external()}）；权限集全放行（含 sensitive）以穿过硬拒闸，把判定交给审批闸。
   */
  private Future<ToolResult> startRename(String newName) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "unit.RenameUnit");
    args.put("payloadJson", "{\"id\":\"u-1\",\"name\":\"" + newName + "\"}");
    args.put("branch", "main");
    args.put("expectedRevision", 1L);
    ToolContext context =
        new ToolContext(
            AccessToken.DEFAULT,
            AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
            Map.of(),
            args,
            AgentIdentity.external());
    FutureTask<ToolResult> task =
        new FutureTask<>(
            () ->
                shell
                    .toolAuthorizer()
                    .execute(shell.toolRegistry(), CommandSubmitTool.NAME, context));
    Thread.ofVirtual().name("t6-write-call").start(task);
    return task;
  }

  /** 等写工具进审批并返回登记 id；写工具在登记之前就跑完（= 未审批即执行）则返回 {@code null}——R3 的禁止形态。 */
  private String awaitPendingId(Future<ToolResult> call) throws InterruptedException {
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
    throw new AssertionError("写工具既未进审批、也未结束（" + WAIT + " 内）——审批链装配异常");
  }

  private JsonNode getJson(int port, String path) throws Exception {
    HttpResponse<String> response = exchange(port, "GET", path, null);
    assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> exchange(int port, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return HttpClient.newHttpClient()
        .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
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

  private static String unitName(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state().units().get(U1).name();
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
