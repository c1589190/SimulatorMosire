package io.mosire.simos.app.sd;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.llm.AgentLibLlmConfig;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.write.StartDecisionTool;
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
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.SdInfoEntry;
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
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T10 的三条判据端到端（spec `C18` ①②③，D5/D6）：「开始决策」= 新命令 {@code sd.StartDecision}。
 *
 * <ul>
 *   <li>① 用户经 GUI（{@code POST /api/sd/start-decision}，{@code initiator="player:gui"}）⇒ **直接落
 *       revision**，不经审批；
 *   <li>② GM Agent 经 GM MCP 口（真 {@link ToolCallAuthorizer}）⇒ **直接生效、不进审批**（★ 2026-09-24 用户裁定
 *       「MCP/GM Agent 无脑过」；原口径"未批不落 revision、批准后 +1"已作废，见 {@code GmAutoApproveGate}）；
 *   <li>③ 决策人 Agent 口**没有**该工具（它不参与"开始决策"）。
 * </ul>
 *
 * <p>★ 另钉一条**本任务的关键设计决定**：{@code sd.StartDecision} **不写 {@code Directive}** ⇒ 不占 R4 名额 （「同一 {@code
 * (decisionMakerId, tick)} 至多一条 {@code Directive}」）⇒ 同一 tick 其后的 {@code sd.IssueDirective}
 * **照常被接受**。若本命令改成写 {@code Directive}（哪怕 {@code PLANNED}），本用例的第 5 条会当场红。
 */
class StartDecisionEndToEndTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final DecisionMakerId DM = new DecisionMakerId("dm-t10");
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 {@code agent:external-mcp} 不同，让"写死成别的值"这类变异当场现形。 */
  private static final String TEST_INITIATOR = "agent:t10-test";

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

  // ── ① 用户路径：直接生效、不进审批 ─────────────────────────────────────

  @Test
  void guiUserPathCommitsDirectlyWithoutApproval() throws Exception {
    HttpResponse<String> response =
        post(
            shell.boundGuiPort(),
            "/api/sd/start-decision",
            "{\"branch\":\"main\",\"expectedRevision\":1,\"decisionMakerId\":\"dm-t10\","
                + "\"note\":\"用户发起\"}");

    assertThat(response.statusCode()).as("用户路径必须 200：" + response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("result").asText()).isEqualTo("committed");
    assertThat(body.get("ref").get("revision").asLong()).isEqualTo(2L);
    assertThat(head()).as("C18①：直接落 revision、头部前进").isEqualTo(2L);
    assertThat(shell.pendingApprovals().pending()).as("D6：用户经 GUI 点 ⇒ 直接生效，**不得**进审批").isEmpty();

    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 2)).orElseThrow();
      assertThat(row.initiator()).as("C18①：GUI 身份恰是 player:gui").isEqualTo("player:gui");
      assertThat(row.commandType()).isEqualTo("sd.StartDecision");
    }
    assertThat(sdState(2).info().get("sd:decision.dm-t10"))
        .as("发起记录落进 sd INFO 覆盖层（随 revision 重放）")
        .containsExactly(
            new SdInfoEntry(
                new SdInfoId("sd:decision.dm-t10#0"),
                7L,
                Set.of(DM),
                Set.of(),
                "start",
                "7",
                Optional.of("用户发起"),
                new RevisionId(1),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void guiUserPathRejectsUnknownDecisionMakerWithoutRevision() throws Exception {
    HttpResponse<String> response =
        post(
            shell.boundGuiPort(),
            "/api/sd/start-decision",
            "{\"branch\":\"main\",\"expectedRevision\":1,\"decisionMakerId\":\"dm-nope\"}");

    assertThat(response.statusCode()).as("未知决策人 ⇒ 422").isEqualTo(422);
    assertThat(JSON.readTree(response.body()).get("reason").asText()).contains("dm-nope");
    assertThat(head()).as("拒绝是原子的：head 不动").isEqualTo(1L);
  }

  // ── ② GM Agent 路径：**无脑过**（2026-09-24 用户裁定，取代"过审批门链"）──────────

  /**
   * ★★ 原用例（{@code gmAgentPathIsDeniedWithoutApprovalAndCommitsAfterApproval}）随用户裁定作废： 「为啥这种 GM
   * 级命令要额外审批？改成 MCP/GM Agent **无脑过**」。
   *
   * <p>现在钉的是**新口径**：GM 口的写工具**不进待批、不需人点**，一次调用直接落 revision；且连发两次也一样
   * （不靠"有人批过"这种一次性状态）。决策人那条链**仍要批**，判据在 {@code ShellApprovalTest}（本类不重复）。
   */
  @Test
  void gmAgentPathCommitsDirectlyWithoutApproval() throws Exception {
    Future<ToolResult> first = startDecisionViaGmPort(1L);
    ToolResult firstResult = first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(firstResult.success()).as(firstResult.message()).isTrue();
    assertThat(shell.pendingApprovals().pending()).as("★ GM 口不得留下待批项（无脑过的判据）").isEmpty();
    assertThat(head()).as("GM 口一次调用直接前进到 2").isEqualTo(2L);
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 2)).orElseThrow();
      assertThat(row.initiator()).as("GM MCP 写命令的 initiator 恰是配置值").isEqualTo(TEST_INITIATOR);
      assertThat(row.commandType()).isEqualTo("sd.StartDecision");
    }
    assertThat(sdState(2).info().get("sd:decision.dm-t10")).hasSize(1);

    // 再发一次：同样直接过（不是"批一次管一会儿"），只是这次要拿新的 head。
    Future<ToolResult> second = startDecisionViaGmPort(2L);
    assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS).success()).isTrue();
    assertThat(shell.pendingApprovals().pending()).as("第二次也不得登记待批").isEmpty();
    assertThat(head()).isEqualTo(3L);
  }

  // ── ③ 决策人人口：没有该工具 ──────────────────────────────────────────

  @Test
  void decisionAgentPortHasNoStartDecisionTool() {
    assertThat(names(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("GM 组（= MCP 口）有「开始决策」窄工具")
        .contains(StartDecisionTool.NAME);
    assertThat(names(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("C18③：决策人组**没有** sd.StartDecision（它不参与「开始决策」）")
        .doesNotContain(StartDecisionTool.NAME);
  }

  // ── 本任务的设计决定：不占 R4 名额 ─────────────────────────────────────

  @Test
  void startDecisionDoesNotConsumeTheR4DirectiveSlot() throws Exception {
    // 先发「开始决策」（用户路径），再在同一 tick 发 sd.IssueDirective —— 必须被接受。
    assertThat(
            post(
                    shell.boundGuiPort(),
                    "/api/sd/start-decision",
                    "{\"branch\":\"main\",\"expectedRevision\":1,\"decisionMakerId\":\"dm-t10\"}")
                .statusCode())
        .isEqualTo(200);

    HttpResponse<String> directive =
        post(
            shell.boundGuiPort(),
            "/api/command",
            "{\"type\":\"sd.IssueDirective\",\"branch\":\"main\",\"expectedRevision\":2,"
                + "\"payloadJson\":\"{\\\"directiveId\\\":\\\"d-after\\\","
                + "\\\"decisionMakerId\\\":\\\"dm-t10\\\",\\\"tick\\\":7,"
                + "\\\"intentInfo\\\":\\\"推进\\\",\\\"commands\\\":[]}\"}");

    assertThat(directive.statusCode())
        .as("StartDecision 不写 Directive ⇒ 同一 tick 的出令照常被接受（R4 未被占）：" + directive.body())
        .isEqualTo(200);
    assertThat(JSON.readTree(directive.body()).get("result").asText()).isEqualTo("committed");
    assertThat(head()).isEqualTo(3L);
    assertThat(sdState(3).directives()).hasSize(1);
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 在虚拟线程上经**GM 面**的 {@code gmToolAuthorizer} 执行 GM MCP 口的 {@code sd.StartDecision}（无脑过）。
   *
   * <p>调用者桶 = {@link AccessToken#DEFAULT}（写工具 spec 要求该级别）、身份 = 外部 MCP 面；权限集全放行以穿过硬拒闸，
   * 判定落在**审批链**上——而 GM 面那条链是 {@code GmAutoApproveGate}（直接批准）。
   */
  private Future<ToolResult> startDecisionViaGmPort(long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", "{\"decisionMakerId\":\"dm-t10\",\"note\":\"GM 发起\"}");
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
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
                    .gmToolAuthorizer()
                    .execute(shell.toolRegistry(), StartDecisionTool.NAME, context));
    Thread.ofVirtual().name("t10-write-call").start(task);
    return task;
  }

  private static List<String> names(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  /** 等写工具进审批并返回登记 id；写工具在登记之前就跑完（= 未审批即执行）则返回 {@code null}。 */
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

  private HttpResponse<String> post(int port, String path, String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private SdState sdState(long revision) {
    SimulationState state =
        shell.coreSimos().replay(new StateRef(main(), new RevisionId(revision)));
    SdSnapshot slice =
        (SdSnapshot) state.module("sd").orElseThrow(() -> new AssertionError("状态里没有 sd 切片"));
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
    SocialData social =
        new SocialData(new LinkedHashMap<>(Map.of(H11, populationSeries())), Map.of(), Map.of());
    SdState sd =
        new SdState(
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(DM, decisionMaker()),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sd)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
    // ★ 2026-09-22（裁决：无战斗也要走判决）：DM 绑定一个**不可达** provider ⇒ 判决走 N13 可降级失败
    //   （Judgement.Failed，不落 verdict、不落 revision）。本用例只验 C18 的命令/权限面；成功判决路径
    //   见 AdjudicationEndToEndTest（本地 stub）与真 e2e。
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.upsertRoute("stub", "http://127.0.0.1:1/v1", "deepseek-flash", "keys.stub", 2_000L);
    config.putKey("stub", "sk-test-key");
  }

  private static DecisionMaker decisionMaker() {
    return new DecisionMaker(
        DM,
        new Affiliation.Nation(new NationId("n-t10")),
        Set.of("sd.SubmitVerdict"),
        AccessLimit.empty(),
        3,
        Optional.of("stub"),
        0L);
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
