package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.read.CatalogTool;
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
import io.mosire.simos.util.identity.QueryResult;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 工具集验收（M5 T5）：注册表 12 条 / catalog 与注册面一致（R5）/ 写工具身份注入（R4）/ 读工具与 {@code QueryService} 逐值 对拍 /
 * 拒绝与冲突不留 revision。
 *
 * <p>夹具与 {@code QueryServiceTest}/{@code GuiApiTest} 同法：独立 store 种创世 {@code (main,1)} + 含
 * map/unit/social 三切片的创世 checkpoint（state 时间戳 {@code of(7)}）。写工具经真 {@link CoreSimos} 提交， 身份用**独立
 * store + {@code Timeline}** 读回（R4 的判别力来源）。
 */
class SimosToolsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 {@code agent:external-mcp} 不同，让"写死成别的值"这类变异当场现形（R4）。 */
  private static final String TEST_INITIATOR = "agent:t5-test";

  /** 现有口（T4：EXTERNAL ∪ GM）的工具面：前 9 条读，后 7 条写（3 通用写 + 4 GM 窄写）。 */
  private static final List<String> EXTERNAL_UNION_GM_TOOL_NAMES =
      List.of(
          "simos.command.catalog",
          "simos.state.resolve",
          "simos.state.facets",
          "simos.timeline.branches",
          "simos.map.overview",
          "simos.map.hex",
          "simos.unit.list",
          "simos.unit.get",
          "simos.social.population",
          "simos.command.submit",
          "simos.advance",
          "simos.fork",
          "sd.IssueDirective",
          "sd.SubmitVerdict",
          "sd.SetViewScope",
          "sd.StartDecision");

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
          "unit.SetStatus",
          "unit.AttachUnit",
          "unit.DetachUnit",
          "unit.ReparentSubtree",
          "unit.SetFormationOffset",
          "unit.SplitFormation",
          "unit.MergeFormation",
          "unit.PlanSparseRoute",
          "unit.SetRejoinTarget",
          "unit.CreateCommandChain",
          "unit.UpdateCommandChain",
          "unit.ApplyCasualties",
          "map.SetTerrain",
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "map.SetEdge",
          "map.RegisterPathwayGroup",
          "map.RandomizeRegion",
          "sd.CreateNation",
          "sd.CreateArmy",
          "sd.CreateDecisionMaker",
          "sd.PutInfo",
          "sd.CreateCombat",
          "sd.AddCombatStage",
          "sd.SetStageOutcomeTable",
          "sd.CommitCombatOutcome",
          "sd.RecordCasualties",
          "sd.RegisterEffect",
          "sd.CancelEffect",
          "sd.IssueDirective",
          "sd.SubmitVerdict",
          "sd.SetViewScope",
          "sd.StartDecision",
          "sd.SetDecisionMakerProvider");

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;

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
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── 注册面 ────────────────────────────────────────────────────────────

  @Test
  void registryContainsExactlyTheExternalUnionGmTools() {
    assertThat(shell.toolRegistry().list())
        .extracting(AgentTool::name)
        .containsExactlyInAnyOrderElementsOf(EXTERNAL_UNION_GM_TOOL_NAMES);
  }

  @Test
  void catalogListsExactlyTheRegisteredCommandTypes() throws Exception {
    ToolResult result = call("simos.command.catalog", Map.of());

    assertThat(result.success()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(textValues(body.get("types")))
        .as("catalog 与已注册 handler 同源（R5：catalog 列出的每个 type 都能经 submit 到达）")
        .hasSize(EXPECTED_COMMAND_TYPES.size())
        .containsExactlyInAnyOrderElementsOf(EXPECTED_COMMAND_TYPES);
  }

  /**
   * ★ **T9 的强判据**：catalog 的 type 集合 == **全仓 43 个 `CommandHandler` 实现**的 `type()` 集合（注册面 == 实现面），
   * 而不只是"与一份手抄的期望表相等"。扫描 simos-unit/map/sd 的 main 源码抽 `type()` 的返回串——**任一 handler 存在却没注册进 {@code
   * Shell}，或注册了一条没有实现的 type，这里都会红**。
   *
   * <p>★ 扫描范围是 surefire 工作目录（模块根 {@code simos-app/}）⇒ 相对路径 {@code ../simos-unit/src/main/java} 在主树与
   * worktree 里都成立；**非空自证**：文件数必须恰为 30（扫到 0 个是"扫描器静默"陷阱，不是通过）。
   */
  @Test
  void catalogCoversEveryCommandHandlerImplementation() throws Exception {
    Set<String> implementationTypes = handlerTypesFromSources();
    assertThat(implementationTypes)
        .as("扫描必须恰为 43 个 *Handler.java 的 type()（扫到 0/漏文件是『扫描器静默』陷阱）")
        .hasSize(43);

    ToolResult result = call("simos.command.catalog", Map.of());
    assertThat(result.success()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(textValues(body.get("types")))
        .as("catalog 的 type 集合必须等于全仓实现的 type() 集合（强判据：注册面 == 实现面）")
        .containsExactlyInAnyOrderElementsOf(implementationTypes);
  }

  /**
   * ★ D6 判据（N9/N11）+ T4（D2="加"）：工具面按角色分载——**GM 与决策 Agent 桶都没有通用写** {@code simos.command.submit}，都有
   * {@code sd.*} 窄工具；外部 MCP 桶保留现状（有通用写）；**现有运行时口 = EXTERNAL_WITH_GM 复合桶**（通用写 ∪ GM 窄写，读共享）。
   */
  @Test
  void roleBucketsNeverCarryGenericWrite() {
    List<String> gm = toolNames(shell.toolsFor(SimosToolSource.Role.GM));
    List<String> agent = toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
    List<String> external = toolNames(shell.toolsFor(SimosToolSource.Role.EXTERNAL));
    List<String> externalWithGm = toolNames(shell.toolsFor(SimosToolSource.Role.EXTERNAL_WITH_GM));

    assertThat(gm)
        .doesNotContain("simos.command.submit")
        .contains("sd.IssueDirective", "sd.SubmitVerdict", "sd.SetViewScope", "sd.StartDecision");
    assertThat(agent)
        .doesNotContain("simos.command.submit", "sd.SetViewScope", "sd.StartDecision")
        .contains("sd.IssueDirective", "sd.SubmitVerdict");
    assertThat(external)
        .contains("simos.command.submit")
        .doesNotContain(
            "sd.IssueDirective", "sd.SubmitVerdict", "sd.SetViewScope", "sd.StartDecision");
    assertThat(externalWithGm)
        .as("T4/D2：现有口 = EXTERNAL ∪ GM（9 读 + 3 通用写 + 4 GM 窄写 = 16）")
        .contains(
            "simos.command.submit",
            "simos.advance",
            "simos.fork",
            "sd.IssueDirective",
            "sd.SubmitVerdict",
            "sd.SetViewScope",
            "sd.StartDecision")
        .hasSize(16);
  }

  private static List<String> toolNames(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  /**
   * ★ T10-j：载荷提示表缺项 ⇒ **构造期拒绝**（不再 {@code getOrDefault(type, "")} 静默填空串）。
   *
   * <p>这是"声明式清单不随注册面自动延伸"这一族的**故意违规用例**：给一个没有提示的 type，{@link CatalogTool} 必须当场抛且消息点名该 type
   * ——缺项不再伪装成"有值（空串）"。
   */
  @Test
  void catalogRejectsACommandTypeWithoutAPayloadHint() {
    assertThatThrownBy(() -> new CatalogTool(Set.of("unit.NotARealCommand")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit.NotARealCommand");
  }

  /** 从 simos-unit/map/sd 的 main 源码抽 `public String type()` 的返回串（每个 *Handler.java 取首个匹配）。 */
  private static Set<String> handlerTypesFromSources() throws IOException {
    List<Path> roots =
        List.of(
            Paths.get("..", "simos-unit", "src", "main", "java"),
            Paths.get("..", "simos-map", "src", "main", "java"),
            Paths.get("..", "simos-sd", "src", "main", "java"));
    Pattern typeReturn =
        Pattern.compile("public String type\\(\\)\\s*\\{\\s*return\\s*\"([^\"]+)\"");
    Set<String> types = new LinkedHashSet<>();
    for (Path root : roots) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file :
            files.filter(path -> path.getFileName().toString().endsWith("Handler.java")).toList()) {
          Matcher matcher = typeReturn.matcher(Files.readString(file));
          if (matcher.find()) {
            types.add(matcher.group(1));
          }
        }
      }
    }
    return types;
  }

  @Test
  void readsAreAllowGatedAndDeclareTheirResources() {
    for (String name : EXTERNAL_UNION_GM_TOOL_NAMES.subList(0, 9)) {
      AgentTool tool = shell.toolRegistry().find(name).orElseThrow();
      assertThat(tool.spec()).as("%s 是常规读工具", name).isEqualTo(ToolSpec.DEFAULT);
      assertThat(tool.gate(context(tool, Map.of()))).as("%s 直放", name).isEqualTo(ToolGate.ALLOW);
    }
    assertThat(shell.toolRegistry().find("simos.state.resolve").orElseThrow().resources())
        .isEqualTo(ToolSupport.ALL_READ);
    assertThat(shell.toolRegistry().find("simos.map.hex").orElseThrow().resources())
        .isEqualTo(ResourceManifest.of("map", ResourcePolicy.READ_ONLY));
    assertThat(shell.toolRegistry().find("simos.unit.get").orElseThrow().resources())
        .isEqualTo(ResourceManifest.of("unit", ResourcePolicy.READ_ONLY));
    assertThat(shell.toolRegistry().find("simos.social.population").orElseThrow().resources())
        .isEqualTo(ResourceManifest.of("social", ResourcePolicy.READ_ONLY));
    assertThat(shell.toolRegistry().find("simos.command.catalog").orElseThrow().resources())
        .isEqualTo(ResourceManifest.NONE);
    assertThat(shell.toolRegistry().find("simos.timeline.branches").orElseThrow().resources())
        .isEqualTo(ResourceManifest.NONE);
  }

  @Test
  void writesAreSensitiveAndAskWithTheToolNameAsClassKey() {
    for (String name : EXTERNAL_UNION_GM_TOOL_NAMES.subList(9, 16)) {
      AgentTool tool = shell.toolRegistry().find(name).orElseThrow();
      assertThat(tool.spec().sensitive()).as("%s 是敏感写", name).isTrue();
      assertThat(tool.spec().noExport()).as("%s 不外发标记为假（无内部工具）", name).isFalse();
      ToolGate gate = tool.gate(context(tool, Map.of()));
      assertThat(gate).as("%s 需审批", name).isInstanceOf(ToolGate.Ask.class);
      assertThat(((ToolGate.Ask) gate).classKey()).isEqualTo(name);
    }
    assertThat(shell.toolRegistry().find("simos.command.submit").orElseThrow().resources())
        .isEqualTo(ToolSupport.ALL_WRITE);
    assertThat(shell.toolRegistry().find("simos.advance").orElseThrow().resources())
        .isEqualTo(ToolSupport.ALL_WRITE);
    assertThat(shell.toolRegistry().find("simos.fork").orElseThrow().resources())
        .as("spec §7.1：fork 无资源命名空间")
        .isEqualTo(ResourceManifest.NONE);
  }

  // ── 写：经 CoreSimos 提交 + 身份注入（R4）───────────────────────────────

  @Test
  void writeToolCommitsAndStampsTheConfiguredInitiator() throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "unit.RenameUnit");
    args.put("payloadJson", "{\"id\":\"u-1\",\"name\":\"改名后的第一连\"}");
    args.put("branch", "main");
    args.put("expectedRevision", 1);

    ToolResult result = call("simos.command.submit", args);

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("result").asText()).isEqualTo("committed");
    assertThat(body.get("ref").get("branch").asText()).isEqualTo("main");
    assertThat(body.get("ref").get("revision").asLong()).isEqualTo(2L);
    assertThat(body.get("commandId").asText()).isNotBlank();
    assertThat(body.get("correlationId").asText()).isEqualTo(body.get("commandId").asText());

    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 2)).orElseThrow();
      assertThat(row.initiator()).as("MCP 写命令的 initiator 恰是配置值（R4）").isEqualTo(TEST_INITIATOR);
      assertThat(row.commandType()).isEqualTo("unit.RenameUnit");
      assertThat(row.commandId()).isEqualTo(row.correlationId());
    }

    ToolResult readBack = call("simos.unit.get", Map.of("id", "u-1"));
    assertThat(JSON.readTree(readBack.message()).get("name").asText()).isEqualTo("改名后的第一连");
  }

  @Test
  void advanceToolCommitsThroughCore() throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", 1);
    args.put("from", 7L);
    args.put("to", 9L);

    ToolResult result = call("simos.advance", args);

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("ref").get("revision").asLong()).isGreaterThan(1L);
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      long head = shell.coreSimos().head(main()).orElseThrow().value();
      RevisionRow row =
          new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", head)).orElseThrow();
      assertThat(row.initiator()).isEqualTo(TEST_INITIATOR);
    }
  }

  // ── 拒绝 / 冲突：ToolResult.error 且不留 revision ────────────────────────

  @Test
  void rejectedWriteReturnsErrorAndCreatesNoRevision() throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "core.Nope");
    args.put("branch", "main");
    args.put("expectedRevision", 1);
    args.put("payloadJson", "{}");

    ToolResult result = call("simos.command.submit", args);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("REJECTED");
    assertThat(JSON.readTree(result.message()).get("reason").asText()).isNotBlank();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("被拒的命令不留 revision")
        .isEqualTo(1L);
  }

  @Test
  void conflictingWriteReturnsErrorWithTheRealHeadAndCreatesNoRevision() throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "unit.RenameUnit");
    args.put("payloadJson", "{\"id\":\"u-1\",\"name\":\"不生效\"}");
    args.put("branch", "main");
    args.put("expectedRevision", 999L);

    ToolResult result = call("simos.command.submit", args);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("CONFLICT");
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("current").get("branch").asText()).isEqualTo("main");
    assertThat(body.get("current").get("revision").asLong()).as("返回的是真实 head").isEqualTo(1L);
    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).isEqualTo(1L);
  }

  // ── 读：与 QueryService 逐值对拍 ────────────────────────────────────────

  @Test
  void readToolsMatchQueryServicePerValue() throws Exception {
    QueryTarget head = QueryTarget.head(main());

    ToolResult resolve = call("simos.state.resolve", Map.of("address", "map:Map1:[1,1]"));
    QueryResult expected = shell.queryService().resolve("map:Map1:[1,1]", head);
    JsonNode candidates = JSON.readTree(resolve.message()).get("candidates");
    assertThat(candidates).hasSize(expected.candidates().size());
    assertThat(candidates.get(0).get("canonicalAddress").asText())
        .isEqualTo(expected.candidates().get(0).canonicalAddress());
    assertThat(candidates.get(0).get("typeName").asText())
        .isEqualTo(expected.candidates().get(0).typeName());

    ToolResult population = call("simos.social.population", Map.of("q", 1, "r", 1));
    assertThat(JSON.readTree(population.message()).get("population").asLong())
        .isEqualTo(populationSeries().valueAt(T7));

    ToolResult list = call("simos.unit.list", Map.of());
    JsonNode units = JSON.readTree(list.message()).get("units");
    assertThat(units).hasSize(1);
    assertThat(units.get(0).get("id").asText()).isEqualTo("u-1");
    assertThat(units.get(0).get("position").get("q").asInt()).isEqualTo(1);
    assertThat(units.get(0).get("position").get("r").asInt()).isEqualTo(1);
  }

  /** M7b T2 判据：MCP 读面与 GUI 同形——有路线 ⇒ movement 对象；无路线 ⇒ null。 */
  @Test
  void unitReadToolsExposeMovementObjectAndNullWithoutRoute() throws Exception {
    ToolResult before = call("simos.unit.get", Map.of("id", "u-1"));
    assertThat(JSON.readTree(before.message()).get("movement").isNull())
        .as("无路线 ⇒ movement 为 null")
        .isTrue();

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "unit.PlanRoute");
    args.put(
        "payloadJson",
        "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}]}");
    args.put("branch", "main");
    args.put("expectedRevision", 1);
    assertThat(call("simos.command.submit", args).success()).isTrue();

    JsonNode movement =
        JSON.readTree(call("simos.unit.get", Map.of("id", "u-1")).message()).get("movement");
    assertThat(movement.isObject()).as("movement 必须是对象（不再是布尔）").isTrue();
    assertThat(movement.get("route").get("path")).hasSize(3);
    assertThat(movement.get("status").asText()).isEqualTo("IN_TRANSIT");
    assertThat(movement.get("currentHex").get("r").asInt()).isEqualTo(1);
    assertThat(movement.get("nextHex").get("r").asInt()).isEqualTo(2);
    assertThat(movement.get("remainingMillis").asLong()).isEqualTo(1500L);

    JsonNode listed =
        JSON.readTree(call("simos.unit.list", Map.of()).message())
            .get("units")
            .get(0)
            .get("movement");
    assertThat(listed.get("route").get("path")).as("list 与 get 同形").hasSize(3);
  }

  @Test
  void mapHexBuildsTheCanonicalFacetSubject() throws Exception {
    ToolResult result = call("simos.map.hex", Map.of("q", 1, "r", 1));

    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("terrain").asText()).isEqualTo("desert");
    assertThat(body.get("height").asDouble()).isEqualTo(0.5);
    assertThat(body.get("facets")).as("非空即证明按 canonical 主体查了 facet").hasSize(2);
    assertThat(body.get("facets").get(0).get("value").asText()).isEqualTo("unit:u-1");
  }

  @Test
  void unknownUnitReturnsNotFound() {
    ToolResult result = call("simos.unit.get", Map.of("id", "nope"));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("NOT_FOUND");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private ToolResult call(String toolName, Map<String, Object> args) {
    AgentTool tool = shell.toolRegistry().find(toolName).orElseThrow();
    return tool.execute(context(tool, args));
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }

  private static List<String> textValues(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false)
        .map(JsonNode::asText)
        .toList();
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
