package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.simos.app.query.QueryService.QueryTarget;
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
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.facet.FacetEntry;
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
 * MCP 服务验收（M5 T7，spec §7.2/§10.3）：注册表经 {@code AgentToMcpServer.startHttp} 暴露，**官方 MCP SDK 客户端**经真
 * socket 完成 {@code initialize → tools/list → tools/call}；写调用触发审批（经真 MCP 传输），放行后提交且 {@code
 * initiator} 恰是 {@code ShellConfig.mcpInitiator}（R4 端到端）。
 *
 * <p>夹具与 {@code ShellApprovalTest} 同法：独立 store 种创世 {@code (main,1)} + 含 map/unit/social 三切片的
 * checkpoint（state 时间戳 {@code of(7)}）；端口全 0，MCP 端口经 {@link Shell#boundMcpPort()} 读回（绝不硬编码 5715）。
 */
class McpServerTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 {@code agent:external-mcp} 不同，让"写死成别的值"这类变异当场现形（R4）。 */
  private static final String TEST_INITIATOR = "agent:t7-test";

  private static final String SERVER_NAME = "simos-shell";

  /**
   * 现有口（T4：EXTERNAL ∪ GM）的工具面 = 23 读 + 67 写 = 90 条（spec §七.2 的 C6；含阶段 9–12 新增的 4 条 unit 编制/政策窄写与 3
   * 条 GOV 组合写，以及第 3 波第 2 步的 sd.AdjudicateTick、2026-09-23 的 sd.RejectDirective、 2026-09-25 补的五条读口与
   * H0.6 的 simos.economy.ownership）。
   */
  /**
   * 现有口（T4：EXTERNAL ∪ GM）的工具面 = 37 读 + 123 写 = 160 条（2026-10-09 按实测补入 A1/A2a/B2 的 4 条货币 + 3
   * 条市场区工具）（spec §七.2 的 C6；含 D1–D5 的 unit/sd/army 新读写口与 P1b1/P1b2/P3/R3a/P7a/P7b/P7c 的各项补齐，以及用户
   * 2026-10-02 的 {@code simos.map.overlaps}；S3a 再 +6 条 家户/人口 GM 窄写；Z6 起含 Z1a/Z1c/Z2/Z3c/Z3d 的 9 条
   * gov/economy 新口）。
   */
  private static final List<String> EXTERNAL_UNION_GM_TOOL_NAMES =
      List.of(
          "actor.AdjustAccounts",
          "map.CreateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup",
          "map.RenameRegion",
          "map.SetEdge",
          "map.SetTerrain",
          "map.UpdateRegion",
          "sd.AddCombatStage",
          "sd.AdjudicateTick",
          "sd.CancelEffect",
          "sd.CommitCombatOutcome",
          "sd.CreateArmy",
          "sd.CreateCombat",
          "sd.CreateDecisionMaker",
          "sd.CreateNation",
          "sd.IssueDirective",
          "sd.PutInfo",
          "sd.RecordCasualties",
          "sd.RegisterEffect",
          "sd.RejectDirective",
          "sd.ResetDecisionMakerConversation",
          "sd.RunDecision",
          "sd.SetArmyMasterGov",
          "sd.SetDecisionMakerAccess",
          "sd.SetDecisionMakerProvider",
          "sd.SetStageOutcomeTable",
          "sd.StartDecision",
          "sd.SubmitVerdict",
          "sd.VoidAdjudication",
          "simos.advance",
          "simos.army.assignGov",
          "simos.army.combat",
          "simos.army.combats",
          "simos.army.formatUnit",
          "simos.army.recordCombat",
          "simos.army.resolveCombat",
          "simos.army.startCombat",
          "simos.calendar.configure",
          "simos.calendar.info",
          "simos.command.catalog",
          "simos.command.submit",
          "simos.economy.adjust",
          "simos.economy.upsertGovUnit",
          "simos.economy.upsertIndustry",
          "simos.economy.defineMarketZone",
          "simos.economy.mergeMarketZones",
          "simos.economy.reassignZoneHexes",
          "simos.economy.hex",
          "simos.economy.ownership",
          "simos.fork",
          "simos.gm.adjustPopulation",
          "simos.gm.approvals",
          "simos.gm.approve",
          "simos.gm.armyPayPolicy",
          "simos.gm.mergedPlan.apply",
          "simos.gm.mergedPlan.upsert",
          "simos.gm.packet",
          "simos.gm.packet.decide",
          "simos.gm.packet.execute",
          "simos.gm.packets",
          "simos.gm.periodicAdjustment",
          "simos.gm.tool-usage",
          "simos.gm.vitalRates",
          "simos.gov.absorbUnit",
          "simos.gov.applyStaffing",
          "simos.gov.assignPosts",
          "simos.gov.createOffice",
          "simos.gov.defineCurrency",
          "simos.gov.dismiss",
          "simos.gov.dispatchTeam",
          "simos.gov.expandHousehold",
          "simos.gov.issueMoney",
          "simos.gov.openPostsToMarket",
          "simos.gov.recruit",
          "simos.gov.remit",
          "simos.gov.renameCurrency",
          "simos.gov.retireStaff",
          "simos.gov.selectExaminees",
          "simos.gov.setBudgetPolicy",
          "simos.gov.setEstablishment",
          "simos.gov.setFxRate",
          "simos.gov.transferTreasury",
          "simos.gov.info",
          "simos.llm.providers",
          "simos.map.block",
          "simos.map.hex",
          "simos.map.overlaps",
          "simos.map.overview",
          "simos.map.path",
          "simos.map.region",
          "simos.map.render",
          "simos.province.apply",
          "simos.province.assignCities",
          "simos.province.divide",
          "simos.region.clearData",
          "simos.region.clearStructures",
          "simos.region.seed",
          "simos.sd.combats",
          "simos.sd.decision-maker",
          "simos.sd.decision-makers",
          "simos.sd.diplomacy",
          "simos.sd.diplomatic-events",
          "simos.sd.directives",
          "simos.sd.record-diplomatic-event",
          "simos.sd.reports",
          "simos.sd.run-decision-makers",
          "simos.sd.set-diplomatic-relation",
          "simos.sd.verdicts",
          "simos.skill",
          "simos.social.demand",
          "simos.social.household.create",
          "simos.social.household.members",
          "simos.social.household.move",
          "simos.social.household.rates",
          "simos.social.households",
          "simos.social.labor",
          "simos.social.population",
          "simos.state.facets",
          "simos.state.resolve",
          "simos.timeline.branches",
          "simos.timeline.revisions",
          "simos.unit.adjust-composition",
          "simos.unit.assignHousehold",
          "simos.unit.detachHousehold",
          "simos.unit.get",
          "simos.unit.levyRegion",
          "simos.unit.list",
          "simos.unit.raiseUnit",
          "simos.unit.set-composition",
          "simos.unit.set-state-description",
          "simos.unit.spawnArmy",
          "simos.worldgen.initialize",
          "social.UpdateCity",
          "unit.ApplyCasualties",
          "unit.AttachUnit",
          "unit.CancelRoute",
          "unit.CreateCommandChain",
          "unit.CreateUnit",
          "unit.DetachUnit",
          "unit.DisbandUnit",
          "unit.MergeFormation",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.PlanSparseRoute",
          "unit.RenameUnit",
          "unit.ReparentSubtree",
          "unit.ReparentUnit",
          "unit.SetArmyFormation",
          "unit.SetFormationOffset",
          "unit.SetGovFormation",
          "unit.SetGovPolicy",
          "unit.SetGovSuperior",
          "unit.SetJurisdiction",
          "unit.SetRejoinTarget",
          "unit.SetStatus",
          "unit.SetTaxRate",
          "unit.SplitFormation",
          "unit.UpdateCommandChain");

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
                base.mapId(),
                base.bindAddress()));
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

  // ── initialize + tools/list（R2 的暴露面断言）────────────────────────────

  @Test
  void initializeAndToolsListExposeExactlyTheExternalUnionGmTools() {
    McpSchema.InitializeResult init = client.initialize();
    assertThat(init.serverInfo().name()).as("MCP server 自报名称（spec §7.2）").isEqualTo(SERVER_NAME);
    assertThat(shell.boundMcpPort()).as("MCP owned 形态暴露实际绑定端口").isPositive();

    McpSchema.ListToolsResult tools = client.listTools();
    assertThat(tools.tools())
        .extracting(McpSchema.Tool::name)
        .as(
            "现有口 tools/list 必须恰好是 EXTERNAL ∪ GM 的 160 条（37 读 + 123 写；D1–D5 + C5b + S3a + Z6 后逐条同源 + 2026-10-09 实测补 A1/A2a/B2）")
        .containsExactlyInAnyOrderElementsOf(EXTERNAL_UNION_GM_TOOL_NAMES);
  }

  /**
   * ★ P3（2026-09-24）：{@code simos.map.render} 的图片资产经**真 MCP 传输**出站为 {@link
   * McpSchema.ImageContent}—— 外面的大模型据此"看图"（"图像生成 → 喂给 LLM"在 MCP 面的出口）。
   */
  @Test
  void mapRenderShipsTheImageAsImageContentOverTheWire() throws Exception {
    client.initialize();

    McpSchema.CallToolResult result =
        client.callTool(
            new McpSchema.CallToolRequest(
                "simos.map.render", Map.of("q", 1, "r", 1, "radius", 1, "format", "image")));
    assertThat(result.isError()).as(wireText(result)).isFalse();
    String assetId = JSON.readTree(wireText(result)).get("assetId").asText();
    assertThat(assetId).hasSize(64);

    List<McpSchema.Content> images =
        result.content().stream().filter(McpSchema.ImageContent.class::isInstance).toList();
    assertThat(images).as("图片资产必须出成一个 image content 块").hasSize(1);
    McpSchema.ImageContent image = (McpSchema.ImageContent) images.get(0);
    assertThat(image.mimeType()).isEqualTo("image/png");
    byte[] bytes = shell.artifactStore().resolve(assetId).orElseThrow().bytes();
    assertThat(image.data())
        .as("线上的 base64 必须与工件库里的字节逐字节一致（同一份字节只渲染一次）")
        .isEqualTo(java.util.Base64.getEncoder().encodeToString(bytes));
  }

  // ── 读：与 QueryService 直接调用逐值对拍 ────────────────────────────────

  @Test
  void unitListMatchesQueryServicePerValue() throws Exception {
    client.initialize();

    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("simos.unit.list", Map.of()));
    assertThat(result.isError()).as(wireText(result)).isFalse();
    JsonNode units = JSON.readTree(wireText(result)).get("units");
    assertThat(units).hasSize(1);
    JsonNode unit = units.get(0);

    SimulationState state = shell.queryService().stateAt(QueryTarget.head(main()));
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    Unit expected = slice.state().units().get(U1);
    HexCoord expectedPosition =
        slice.state().effectivePosition(U1, state.meta().timestamp()).orElseThrow();

    assertThat(unit.get("id").asText()).isEqualTo(expected.id().value());
    assertThat(unit.get("name").asText()).isEqualTo(expected.name());
    assertThat(unit.get("equipment")).hasSize(expected.equipment().size());
    assertThat(unit.get("equipment").get(0).get("type").asText())
        .isEqualTo(expected.equipment().get(0).type());
    assertThat(unit.get("equipment").get(0).get("amount").asLong())
        .isEqualTo(expected.equipment().get(0).amount());
    assertThat(unit.get("position").get("q").asInt()).isEqualTo(expectedPosition.q());
    assertThat(unit.get("position").get("r").asInt()).isEqualTo(expectedPosition.r());
  }

  @Test
  void mapHexReturnsTheSameFacetsAsQueryService() throws Exception {
    client.initialize();

    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("simos.map.hex", Map.of("q", 1, "r", 1)));
    assertThat(result.isError()).as(wireText(result)).isFalse();
    JsonNode body = JSON.readTree(wireText(result));
    assertThat(body.get("terrain").asText()).isEqualTo("desert");
    assertThat(body.get("height").asDouble()).isEqualTo(0.5);

    String canonical = canonicalHex(1, 1);
    List<FacetEntry> expected = shell.queryService().facets(canonical, QueryTarget.head(main()));
    JsonNode facets = body.get("facets");
    assertThat(facets).as("MCP 返回的 facet 数须与 QueryService 直接调用一致").hasSize(expected.size());
    for (int i = 0; i < expected.size(); i++) {
      JsonNode actual = facets.get(i);
      FacetEntry entry = expected.get(i);
      assertThat(actual.get("namespace").asText()).isEqualTo(entry.namespace());
      assertThat(actual.get("label").asText()).isEqualTo(entry.label());
      assertThat(actual.get("typeName").asText()).isEqualTo(entry.typeName());
      if (entry.value() instanceof Number number) {
        assertThat(actual.get("value").asDouble()).isEqualTo(number.doubleValue());
      } else {
        assertThat(actual.get("value").asText()).isEqualTo(String.valueOf(entry.value()));
      }
    }
  }

  // ── 写：经真 MCP 传输**无脑过**（2026-09-24 用户裁定），提交后 initiator 逐字（R4 端到端）──

  /**
   * ★★ 原用例（{@code writeToolBlocksOnApprovalThenCommitsWithConfiguredInitiator}）随用户裁定作废： 「为啥这种 GM
   * 级命令要额外审批？改成 MCP/GM Agent **无脑过**」。
   *
   * <p>现在钉的是新口径：真 MCP 客户端调写工具 ⇒ **不进待批、不等任何人**，一次调用直接落 revision；
   * 且连发两次都如此（不是"批一次管一会儿"）。「要批的那条链」是**决策人链**，判据在 {@code ShellApprovalTest} / {@code
   * RunDecisionEndToEndTest}。
   */
  @Test
  void writeToolGoesThroughWithoutApprovalAndCommitsWithConfiguredInitiator() throws Exception {
    client.initialize();

    // 第一次：直接提交、revision 前进、待批表里一条都没有
    Future<McpSchema.CallToolResult> first = startRename("经 MCP 直接改名");
    McpSchema.CallToolResult firstResult = first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(firstResult.isError()).as(wireText(firstResult)).isFalse();
    assertThat(shell.pendingApprovals().pending())
        .as("★ GM 面（真 MCP 客户端）不得登记待批项 —— 要批的链是决策人链")
        .isEmpty();

    // 第二次：同样直接过（换新的 head）
    Future<McpSchema.CallToolResult> approved = startRename("经 MCP 第二次改名");
    McpSchema.CallToolResult approvedResult = approved.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(approvedResult.isError()).as(wireText(approvedResult)).isFalse();
    JsonNode body = JSON.readTree(wireText(approvedResult));
    assertThat(body.get("result").asText()).isEqualTo("committed");
    assertThat(body.get("ref").get("branch").asText()).isEqualTo("main");
    assertThat(body.get("ref").get("revision").asLong()).as("第二次 ⇒ (main,3)").isEqualTo(3L);

    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 3)).orElseThrow();
      assertThat(row.initiator())
          .as("MCP 写命令的 initiator 恰是 ShellConfig.mcpInitiator（R4 端到端）")
          .isEqualTo(TEST_INITIATOR);
      assertThat(row.commandType()).isEqualTo("unit.RenameUnit");
    }

    McpSchema.CallToolResult readBack =
        client.callTool(new McpSchema.CallToolRequest("simos.unit.list", Map.of()));
    JsonNode units = JSON.readTree(wireText(readBack)).get("units");
    assertThat(units.get(0).get("name").asText())
        .as("直过后的改名经 MCP 读回真的生效（不是「没报错」）")
        .isEqualTo("经 MCP 第二次改名");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private McpSyncClient newClient() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + shell.boundMcpPort())
            .endpoint(shell.config().mcpPath())
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return McpClient.sync(transport).build();
  }

  /** 在虚拟线程上经**真 MCP 传输**提交 {@code simos.command.submit}（改名）；GM 面 ⇒ 无脑过、不阻塞。 */
  private Future<McpSchema.CallToolResult> startRename(String newName) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "unit.RenameUnit");
    args.put("payloadJson", "{\"id\":\"u-1\",\"name\":\"" + newName + "\"}");
    args.put("branch", "main");
    // ★ 每次取**当前** head（本用例连发两次写：第一次 1→2、第二次 2→3）。
    args.put("expectedRevision", head());
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(CommandSubmitTool.NAME, args);
    FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> client.callTool(request));
    Thread.ofVirtual().name("t7-mcp-write").start(task);
    return task;
  }

  private static String canonicalHex(int q, int r) {
    return new Address(
            List.of(new Namespace("map"), Entity.of("Map1"), Entity.of("hex", q + "_" + r)))
        .canonical();
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
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
    SocialData social =
        new SocialData(new LinkedHashMap<>(Map.of(H11, populationSeries())), Map.of(), Map.of());
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
        List.of(new CompositionEntry("步枪", 50)),
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
