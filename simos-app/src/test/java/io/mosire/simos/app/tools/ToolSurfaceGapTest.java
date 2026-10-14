package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
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
import io.mosire.simos.ga.tools.write.SdCreateDecisionMakerTool;
import io.mosire.simos.ga.tools.write.SdCreateNationTool;
import io.mosire.simos.ga.tools.write.SubmitVerdictTool;
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
 * 工具面补齐（2026-09-25）：五条新读工具 + {@code simos.sd.decision-maker} 并入 scope 的**逐值**验收。
 *
 * <p>夹具走**真命令路径**：创世只种地图（三格走廊 + 一个 {@code nation:n1} 区域）+ 单位 {@code u-1}（在 {@code (1,1)}），sd 为空；随后经
 * GM 窄写工具真写国家 / 决策人 / 交战 / 判决——读的就是真命令写出来的状态。
 *
 * <p>★ 每条断言**逐值**（不是"没报错"）：{@code sd.combats} 的交战格 / 阶段 / 参与单位；{@code map.block} 的块成员格； {@code
 * sd.verdicts} 的 FULL（含 payload/meta）与带 actor（WITHHELD ⇒ 不含 payload）之差；{@code sd.decision-maker}
 * 的现算可见范围 regionIds；{@code gm.tool-usage} 的真调用留痕；{@code llm.providers} 的 provider 键。
 */
class ToolSurfaceGapTest {

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

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── simos.map.block：整块成员格（GM-only）────────────────────────────

  @Test
  void mapBlockToolReportsTheWholeConnectedTerrainBlock() throws Exception {
    // 走廊三格同地形且连通 ⇒ 整块 = 三格。
    JsonNode body = read("simos.map.block", Map.of("q", 1L, "r", 1L));

    assertThat(body.get("q").asInt()).isEqualTo(1);
    assertThat(body.get("r").asInt()).isEqualTo(1);
    assertThat(body.get("terrain").asText()).isEqualTo("desert");
    assertThat(body.get("hexCount").asInt()).isEqualTo(3);
    assertThat(body.get("hexes")).hasSize(3);
    // ★ 逐值：块成员格恰为本格所属的整块（此处 = 走廊三格）。
    assertThat(hexPairs(body.get("hexes")))
        .containsExactlyInAnyOrder(List.of(1, 1), List.of(1, 2), List.of(1, 3));

    // 图外格 ⇒ NOT_FOUND（与 GUI 404 同口径，不给存在性侧信道）。
    ToolResult missing = call("simos.map.block", Map.of("q", 9L, "r", 9L));
    assertThat(missing.success()).isFalse();
    assertThat(missing.code()).isEqualTo("NOT_FOUND");
  }

  // ── simos.sd.verdicts：FULL（省略 actor）vs 带 actor（WITHHELD）────────

  @Test
  void verdictsToolIsFullWithoutActorAndWithheldWithActor() throws Exception {
    seedNationAndDecisionMaker();
    submitVerdict();

    // 省略 actor ⇒ FULL：模型原始输出与 meta 都在。
    JsonNode full = read("simos.sd.verdicts", Map.of()).get("verdicts");
    assertThat(full).hasSize(1);
    JsonNode verdict = full.get(0);
    assertThat(verdict.get("id").asText()).isEqualTo("v1");
    assertThat(verdict.get("breakpoint").asText()).isEqualTo("D1");
    assertThat(verdict.get("subject").asText())
        .as("★★ 判决面重挂：战斗 subject 的地址族是 `army:combat.*`（旧 `sd:combat.*` 已随批次 3d-2 退役）")
        .isEqualTo("army:combat.c1");
    assertThat(verdict.get("payload").asText()).contains("stageId").contains("s1");
    assertThat(verdict.get("meta").get("model").asText()).isEqualTo("test-model");
    assertThat(verdict.get("meta").get("promptVersion").asText()).isEqualTo("v9");

    // 带 actor（默认 AccessLimit ⇒ WITHHELD）⇒ 整条不出现（不是"给个空 payload"）。
    JsonNode withheld = read("simos.sd.verdicts", Map.of("actor", "dm-1")).get("verdicts");
    assertThat(withheld).as("WITHHELD ⇒ 判决对持有者完全不披露（整条不出现）").isEmpty();
  }

  // ── simos.sd.decision-maker：并入的现算 scope ─────────────────────────

  @Test
  void decisionMakerToolMergesTheComputedVisibleScope() throws Exception {
    seedNationAndDecisionMaker();

    JsonNode body = read("simos.sd.decision-maker", Map.of("decisionMakerId", "dm-1"));

    assertThat(body.get("id").asText()).isEqualTo("dm-1");
    JsonNode scope = body.get("scope");
    assertThat(scope).as("★ scope 已并入（不再需要单独一条工具）").isNotNull();
    assertThat(scope.get("decisionMakerId").asText()).isEqualTo("dm-1");
    assertThat(scope.get("branch").asText()).isEqualTo("main");
    // ★ 逐值：国家决策人的可见区域 = 带 `nation:n1` tag 的区域（范围函数现算 ∩ GM 限制）。
    assertThat(textValues(scope.get("visible").get("regionIds")))
        .containsExactlyInAnyOrder("r-nation");
    assertThat(scope.get("visible").get("hexCount").asInt())
        .as("可见格 = 区域成员格（H11+H12）")
        .isEqualTo(2);

    // 未并入 run-status：它是进程内运行观测，不是决策人的世界属性。
    assertThat(body.has("runStatus")).isFalse();
  }

  // ── simos.gm.tool-usage：真调用留痕（GM-only 观测面）──────────────────

  @Test
  void gmToolUsageToolReturnsTheRecordedInvocation() throws Exception {
    // 先经 MCP 面调一次读工具（记录器包的是 GM 组的源）。
    // ★★ 2026-10-13 收尾测试第 3 层：`simos.sd.combats` 是**幽灵名**（旧战斗族退役后真名 = `simos.army.combats`）
    //   ——`DecisionFaceGolden.ALL` 里那条同名条目也已删除（同一批）。
    read("simos.army.combats", Map.of());

    JsonNode entries = read("simos.gm.tool-usage", Map.of()).get("entries");
    assertThat(entries).isNotEmpty();
    JsonNode latest = entries.get(0);
    assertThat(latest.get("tool").asText()).isEqualTo("simos.army.combats");
    assertThat(latest.get("ok").asBoolean()).isTrue();
    assertThat(latest.get("code").isNull()).as("成功 ⇒ 无失败码").isTrue();
    assertThat(latest.get("atEpochMs").asLong()).isPositive();
  }

  // ── simos.llm.providers：掩码配置（GM-only）──────────────────────────

  @Test
  void llmProvidersToolReturnsTheMaskedConfigAndNeverTheKeyValue() throws Exception {
    // 经 Shell 的配置门面真写一条路由 + 一个密钥值（与 GUI 配置页同一路径）。
    shell
        .llmConfig()
        .upsertRoute("probe", "https://example.invalid/v1", "probe-model", "keys.probe", 1000);
    shell.llmConfig().putKey("probe", "super-secret-value-xyz");

    ToolResult result = call("simos.llm.providers", Map.of());
    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode providers = JSON.readTree(result.message()).get("providers");
    assertThat(providers).isNotNull();
    assertThat(providers.isArray()).isTrue();

    JsonNode probe = null;
    for (JsonNode node : providers) {
      if ("probe".equals(node.get("id").asText())) {
        probe = node;
      }
    }
    assertThat(probe).as("刚写的 probe 路由必须在列表里").isNotNull();
    assertThat(probe.get("valid").asBoolean()).isTrue();
    assertThat(probe.get("baseUrl").asText()).isEqualTo("https://example.invalid/v1");
    assertThat(probe.get("model").asText()).isEqualTo("probe-model");
    assertThat(probe.get("credentialsRef").asText()).isEqualTo("keys.probe");
    assertThat(probe.get("keyConfigured").asBoolean()).as("只报布尔，不回报值").isTrue();

    // ★ 掩码已核：整份响应**不含密钥值**（M4 §二-5 的静态结论在行为面复验）。
    assertThat(result.message()).doesNotContain("super-secret-value-xyz");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private JsonNode read(String tool, Map<String, Object> args) throws Exception {
    ToolResult result = call(tool, args);
    assertThat(result.success()).as("%s: %s", tool, result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  private ToolResult call(String toolName, Map<String, Object> args) {
    AgentTool tool = shell.toolRegistry().find(toolName).orElseThrow();
    AgentPermissionSet gm = gmPermissionSet();
    return tool.execute(
        new ToolContext(AccessToken.DEFAULT, gm, Map.of(), args)
            .withResources(ResourceAuthorizer.of(gm, tool.resources())));
  }

  /**
   * 与 {@code Shell.gmPermissionSet()} 同形（四个命名空间各自 {@code unlimited}）——窄写工具（如 {@code
   * sd.SubmitVerdict}）声明的 {@code sd:decision-maker} 需要 sd 命名空间被显式表态为可写， 否则资源断言会拦在 {@code SYSTEM}
   * 的缺省只读策略上（那正是 GM 与 SYSTEM 的区别，不是测试的取巧）。
   */
  private static AgentPermissionSet gmPermissionSet() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allowAll()
        .sensitiveAllowed(true)
        .destructiveAllowed(true)
        .resourceScopes(
            ResourceScopeMap.of(
                Map.of(
                    ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.SOCIAL_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.UNIT_NAMESPACE, ResourceScope.unlimited(),
                    ToolSupport.GA_NAMESPACE, ResourceScope.unlimited())))
        .build();
  }

  private void seedNationAndDecisionMaker() {
    long head = head();
    assertThat(
            callNarrowWrite(
                    SdCreateNationTool.NAME,
                    "{\"nationId\":\"n1\",\"name\":\"甲国\",\"homeRegionId\":\"r-nation\","
                        + "\"adminBudgetPerTick\":10}",
                    head)
                .success())
        .isTrue();
    assertThat(
            callNarrowWrite(
                    SdCreateDecisionMakerTool.NAME,
                    "{\"id\":\"dm-1\",\"allowedTools\":[\"ga.IssueDirective\"],\"cadence\":5}",
                    head + 1)
                .success())
        .isTrue();
    // ★★ 2026-10-13 收尾测试第 3 层：归属归模块 ⇒ `ga.CreateDecisionMaker` 不再收 `affiliation`（载荷带它 = 具名拒），
    //   归属由 gov 的赋权工单写 `GovState.nationAffiliations()`；该命令**没有**窄工具，走通用信封提交面。
    assertThat(
            submitCommand(
                    "gov.AssignDecisionMakerNation",
                    "{\"decisionMakerId\":\"dm-1\",\"nationId\":\"n1\"}",
                    head + 2)
                .success())
        .as("赋权工单必须真的落进 gov 的归属表（否则范围函数给 deny-all，下面的可见性断言会恒真）")
        .isTrue();
  }

  private void submitVerdict() {
    long head = head();
    String payload =
        "{\\\"stageId\\\":\\\"s1\\\",\\\"selectedOutcomeId\\\":\\\"o1\\\","
            + "\\\"casualtyDeltas\\\":[],\\\"rationaleText\\\":\\\"测试判决\\\"}";
    String body =
        "{\"verdictId\":\"v1\",\"breakpoint\":\"D1\",\"subject\":\"army:combat.c1\","
            + "\"payload\":\""
            + payload
            + "\",\"meta\":{\"model\":\"test-model\",\"promptVersion\":\"v9\","
            + "\"inputBriefDigest\":\"digest-1\"}}";
    assertThat(callNarrowWrite(SubmitVerdictTool.NAME, body, head).success()).isTrue();
  }

  /** 通用信封提交（没有窄工具的命令走这条：GM 的 {@code simos.command.submit}）。 */
  private ToolResult submitCommand(String type, String payloadJson, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", type);
    args.put("payloadJson", payloadJson);
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return call(CommandSubmitTool.NAME, args);
  }

  private ToolResult callNarrowWrite(String toolName, String payloadJson, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", payloadJson);
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return call(toolName, args);
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private static List<List<Integer>> hexPairs(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false)
        .map(node -> List.of(node.get("q").asInt(), node.get("r").asInt()))
        .toList();
  }

  private static List<String> textValues(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false)
        .map(JsonNode::asText)
        .toList();
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  /** 创世 {@code (main,1)}：一格 {@code nation:n1} 区域（H11+H12）+ 三格走廊 + 单位 u-1（在 {@code (1,1)}）；sd 为空。 */
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
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, unitState()),
                "social",
                    new SocialSnapshot(
                        ref("main", 1),
                        T7,
                        new SocialData(new LinkedHashMap<>(), Map.of(), Map.of())),
                "ga", new GaSnapshot(ref("main", 1), T7, GaState.empty()),
                // ★★ 2026-10-13 收尾测试第 3 层：国家住 gov、军队住 army（批次 3c/3d-1 起不再住 sd/ga）
                //   ⇒ 缺这两片时 `gov.CreateNation` 直接 500、赋权工单也写不进表。
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

  private static GameMap corridorMap() {
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
        Region.of(
            R_NATION, "种子国区域", Set.of(H11, H12), new RegionMeta(null, "nation:n1", null, null)));
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

  private static UnitState unitState() {
    Unit unit =
        new Unit(
            U1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty());
    return new UnitState(new LinkedHashMap<>(Map.of(U1, unit)));
  }
}
