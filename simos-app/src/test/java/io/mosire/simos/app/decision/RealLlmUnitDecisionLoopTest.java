package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.docs.DecisionDoc;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.read.LlmProvidersTool;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.RunDecisionTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
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
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **真 LLM → MCP → GM 工具实跑循环的白板 Unit 决策人验收**（测试/验证子代理，2026-09-30）。
 *
 * <p>★★ **本类会打真网络，默认不跑**：整类门控在 {@code @EnabledIfEnvironmentVariable(name="SIMOS_REAL_LLM",
 * matches="1")}。未设该环境变量时整类被 JUnit 跳过（不是失败）——CI 与本仓默认门禁都不会打网络。
 *
 * <p>★★ **手动怎么跑**（在仓库根，一次只跑一个 Maven）：
 *
 * <pre>
 *   tools/mvn-lock.sh -q -pl simos-app spotless:apply \
 *       -DspotlessFiles='src/test/java/io/mosire/simos/app/decision/RealLlmUnitDecisionLoopTest.java'
 *   SIMOS_REAL_LLM=1 tools/mvn-lock.sh -pl simos-app -am \
 *       -Dtest='RealLlmUnitDecisionLoopTest' -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 *
 * <p>★★ **真 provider 是走生产路径解析出来的，不是注入替身**：Shell 用 {@code Shell.start(ShellConfig)}（不传
 * decisionLlmClients）⇒ 决策编排按 {@link DecisionMaker#providerId()} 在 {@code
 * <store>/agentlib/config.json} 的路由表里解析真客户端；夹具把仓库的 {@code config/llm-providers.json} **逐字节复制**到
 * {@code <store>/llm-providers.json} 作为 AgentLib 的一次性迁移源（本类不读、不打印密钥值）。
 *
 * <p>★★ **纯白板 Unit 决策人**（无任何生产范围代码改动）：
 *
 * <ul>
 *   <li>map：4 格 {@code (1,1)/(1,2)/(2,1)/(2,2)}；区域 {@code r-core} = 前 3 格（= 管辖 hex 集），区域 {@code
 *       r-out} = {@code (2,2)}（**区外对照**）；
 *   <li>unit：{@code u-1} 在 {@code (1,1)}、visionRadius=2（视野圈恰好覆盖 4 格）、**真挂 {@code
 *       jurisdiction{r-core:0‰, caps 1000/1000/1000, admin 1000}}**；区外单位 {@code u-out} 在 {@code
 *       (2,2)}；
 *   <li>social：4 格各一条人口序列 + 各一条人口批次（人数不同，便于模型读出差异）；
 *   <li>sd：Nation FRA + Army a1（rootUnit=u-1）+ DecisionMaker {@code dm-u1}（{@code
 *       Affiliation.Army(a1)}、 {@code allowedTools=Set.of()}、{@code
 *       providerId=mosire-flash}）；另有**一条发给 dm-u1 的任务简报（Docs）**， 让"这一轮该做什么"有明确出处（{@code
 *       sd.RunDecision} 的载荷只有 decisionMakerId，没有任务字段）。
 * </ul>
 *
 * <p>★★ **范围怎么定（本任务的核心约束）**：生产范围函数仍是 {@code ArmyScope}（根单位位置 + visionRadius 圈），GM 限制 {@code
 * accessLimit} 显式收紧到 {@code Region.hexes()} 派生的前缀（map：r-core 各 hex；social： r-core 各格；unit：{@code
 * u-1}）。两者语义是**交集**（{@code ResourceScopeMap#narrowTo}）⇒ 区外格 {@code (2,2)} 与区外单位 {@code u-out}
 * 在视野圈内也**不可达**。★ **本任务不改生产范围代码**：{@code jurisdiction → scope} 的自动接线尚未做， 本测试只是把真值挂上 {@code
 * Unit.jurisdiction} 并显式写 accessLimit（为下一阶段接线留真值）。
 *
 * <p>★★ **2026-09-30 实测结论（本轮）**：**默认单轮全链绿** —— 白板 {@code dm-u1} 经真 provider 读到真值（辖区三格人口
 * 12000/23000/34000、{@code u-1} 的 member/position）、{@code sd.IssueDirective} 进审批并落真 revision、GM 经
 * MCP {@code sd.AdjudicateTick} 执行 {@code unit.SetTaxRate}（税率 0‰→100‰）。**多轮（≥2 轮）原有 thinking
 * 回传缺陷已修**： 2026-09-30 首次实测时，模型某次收口 assistant 消息没有 {@code reasoning_content}，AgentLib 回放省略该键 ⇒
 * 下一次请求 HTTP 400 {@code The reasoning_content in the thinking mode must be passed back to the
 * API}；AgentLib 修复后由路由能力位 {@code capabilities.echoReasoningContent=true} 对每条 assistant
 * 历史消息恒发该键（无内容发空串）。**默认轮数仍 = 1**（常规冒烟 省时）；多轮回归用 {@code SIMOS_REAL_LLM_ROUNDS=3}，应连续三轮、{@code
 * blockers=0}、无 400。
 *
 * <p>★ **另一个具名缺口**：{@code ArmyScope} 的 map 通道只发逐 hex 前缀、不发 {@code map:<mapId>/region/<rid>} ⇒
 * {@code simos.map.region} 对**本辖区**也返回 {@code NOT_FOUND}（accessLimit 是交集，不能"加"范围）；{@code
 * simos.unit.get} 也还没有 {@code jurisdiction}/税率只读视图。下一阶段接线 {@code jurisdiction → scope} 时应一并补。
 */
@EnabledIfEnvironmentVariable(named = "SIMOS_REAL_LLM", matches = "1")
class RealLlmUnitDecisionLoopTest {

  private static final String MAP_ID = "Map1";
  private static final String DM_ID = "dm-u1";
  private static final String REGION_CORE = "r-core";
  private static final String REGION_OUT = "r-out";
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String INITIATOR = "agent:real-llm-test";
  private static final String PROVIDER_ID = "mosire-flash";
  private static final String CONVERSATION_ID = "decision-maker:" + DM_ID;

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H21 = new HexCoord(2, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);

  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U_OUT = new UnitId("u-out");

  private static final DecisionMakerId DM = new DecisionMakerId(DM_ID);
  private static final ArmyId A1 = new ArmyId("a1");
  private static final NationId FRA = new NationId("FRA");
  private static final RegionId R_CORE = new RegionId(REGION_CORE);
  private static final RegionId R_OUT = new RegionId(REGION_OUT);

  /** 人口批次真值（每格不同 ⇒ 轨迹里读到哪个数字就能认出是哪一格）。 */
  private static final Map<HexCoord, Long> POPULATION =
      Map.of(H11, 12_000L, H12, 23_000L, H21, 34_000L, H22, 90_000L);

  /** 每轮触发后的等待上限（含真模型思考 + 工具 + 内层审批）。 */
  private static final Duration CALL_TIMEOUT = Duration.ofMinutes(6);

  /**
   * 轮数：**默认 1**（单轮全链可绿、省时；见类注的 2026-09-30 实测结论）。多轮回归用 {@code SIMOS_REAL_LLM_ROUNDS=3} 显式开 ——
   * thinking 回传缺陷已由 AgentLib 的 {@code echoReasoningContent} 能力位修复，三轮应连续成功且无 400。
   */
  private static final int ROUNDS = envInt("SIMOS_REAL_LLM_ROUNDS", 1);

  private static final long WORLD_TICK = T7.tick();
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 读工具（真实名）：判"至少一次成功读且带真值"与"越界读被拒"用（轨迹里存的是模型给的线格式名，比对前先归一）。 */
  private static final Set<String> READ_TOOL_REAL_NAMES =
      Set.of(
          "simos.map.hex",
          "simos.map.region",
          "simos.map.overview",
          "simos.unit.get",
          "simos.unit.list",
          "simos.social.population",
          "simos.command.catalog",
          "simos.skill",
          "simos.state.resolve",
          "simos.state.facets",
          "simos.timeline.revisions",
          "simos.timeline.branches",
          "sd.DecisionDocs",
          "sd.DecisionResults",
          "simos.economy.hex");

  /** 夹具真值标记（出现在**成功**读结果里 ⇒ 读到的是这个白板世界，不是编的）。 */
  private static final List<String> FIXTURE_TRUTHS =
      List.of("u-1", "u-out", "1_1", "1_2", "2_1", "r-core", "desert", "12000", "23000", "34000");

  @TempDir Path tempDir;

  private Path storeDir;
  private Shell shell;
  private McpSyncClient client;

  /** 本次 MCP 调用里答过的审批（工具名 = classKey）。 */
  private final List<String> approvals = new ArrayList<>();

  /** 已答过的审批 id（全局，避免对同一 id 重复 decide）。 */
  private final Set<String> answeredIds = new HashSet<>();

  @BeforeEach
  void startShell() throws Exception {
    storeDir = tempDir.resolve("store");
    Files.createDirectories(storeDir);
    copyRepoProviderConfig();
    copyRepoSkills();
    seedGenesis();
    // ★ 不注入 llm ⇒ 生产路径：按决策人 providerId 经 AgentLib 配置解析真 provider。
    shell = Shell.start(ShellConfig.defaults(storeDir).withPorts(0, 0, 0));
    client = newClient();
  }

  @AfterEach
  void stopShell() {
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (Exception ignored) {
        // 关停失败不影响判定：server 侧仍会被 shell.close() 收掉
      }
      client = null;
    }
    if (shell != null) {
      shell.close();
      shell = null;
    }
  }

  // ── 用例 1：前置自检（不打 LLM）────────────────────────────────────────────────────

  /**
   * ★ **配置迁移真的成功**：经真 MCP 读 {@code simos.llm.providers}（GM 只读），{@code mosire-flash} 必须在场且 {@code
   * valid=true}、密钥已配置。它证明后续真调用不是"碰巧用了另一个 provider"。
   */
  @Test
  void providerConfigIsMigratedIntoAgentLibAndValidThroughMcp() throws Exception {
    McpSchema.CallToolResult result = callTool(LlmProvidersTool.NAME, Map.of());
    assertThat(result.isError()).as(wireText(result)).isFalse();
    JsonNode body = JSON.readTree(wireText(result));
    JsonNode flash = null;
    for (JsonNode provider : body.get("providers")) {
      if (PROVIDER_ID.equals(provider.get("id").asText())) {
        flash = provider;
      }
    }
    assertThat(flash).as("provider %s 必须已迁移进 AgentLib 配置根", PROVIDER_ID).isNotNull();
    assertThat(flash.get("valid").asBoolean()).as("路由必须 valid").isTrue();
    assertThat(flash.get("model").asText()).isEqualTo("deepseek-flash");
    assertThat(flash.get("keyConfigured").asBoolean()).as("密钥必须已配置（**不打印值**）").isTrue();
    // ★ 只打掩码面（id/valid/model/keyConfigured），绝不回显密钥值。
    System.out.println(
        "[REAL-LLM-CONFIG] provider="
            + flash.get("id").asText()
            + " valid="
            + flash.get("valid").asBoolean()
            + " model="
            + flash.get("model").asText()
            + " keyConfigured="
            + flash.get("keyConfigured").asBoolean());
  }

  /**
   * ★ **夹具自检（不打 LLM）**：范围函数 ∩ accessLimit 的结果真的"只放 r-core/u-1"；区外对照（{@code (2,2)} / {@code u-out} /
   * {@code r-out}）真的存在但不可达；{@code Unit.jurisdiction} 真的挂上了。
   */
  @Test
  void fixtureScopeIsWiredAndOutOfScopeControlIsReal() {
    SimulationState state = shell.coreSimos().replay(ref("main", 1));
    DecisionMaker dm = sdAt(1).decisionMakers().get(DM);
    assertThat(dm).as("夹具必须有 dm-u1").isNotNull();
    assertThat(dm.providerId()).contains(PROVIDER_ID);

    // 区外对照真的存在（否则"不可见"是空的）
    UnitState units = unitStateAt(1);
    assertThat(units.units()).containsKeys(U1, U_OUT);
    assertThat(units.units().get(U_OUT).position().valueAt(T7)).contains(H22);
    GameMap map = ToolSupport.gameMap(state);
    assertThat(map.hexes()).containsKeys(H11, H12, H21, H22);
    assertThat(socialAt(1).groups()).isNotEmpty();

    // 真值：u-1 挂着 jurisdiction{r-core: 0‰, caps 1000, admin 1000}
    Unit u1 = units.units().get(U1);
    assertThat(u1.visionRadius()).isEqualTo(2);
    assertThat(u1.jurisdiction()).isPresent();
    assertThat(u1.jurisdiction().orElseThrow().taxRatePerMilleByRegion()).containsEntry(R_CORE, 0L);
    assertThat(u1.jurisdiction().orElseThrow().levyGrainCapPerCommand()).isEqualTo(1000L);

    // 范围 = ArmyScope（位置+视野圈）∩ accessLimit（r-core hex ∪ r-core region；u-1）——只读纯函数，无 LLM。
    ResourceScopeMap scopes =
        DecisionCallerFactory.resourceScopesFor(
            DecisionScopeFunctions.defaults(), dm, state, MAP_ID);
    assertThat(scopes.declaredScope("map").allows("Map1/hex/1_1")).isTrue();
    assertThat(scopes.declaredScope("map").allows("Map1/hex/1_2")).isTrue();
    assertThat(scopes.declaredScope("map").allows("Map1/hex/2_1")).isTrue();
    assertThat(scopes.declaredScope("map").allows("Map1/hex/2_2"))
        .as("区外格 (2,2) 虽在视野圈内，必须被 accessLimit 收掉")
        .isFalse();
    assertThat(scopes.declaredScope("map").allows("Map1/region/r-out")).isFalse();
    assertThat(scopes.declaredScope("social").allows("1_1")).isTrue();
    assertThat(scopes.declaredScope("social").allows("2_2")).isFalse();
    assertThat(scopes.declaredScope("unit").allows("u-1")).isTrue();
    assertThat(scopes.declaredScope("unit").allows("u-out")).isFalse();
  }

  // ── 用例 2/3：真 LLM 连续三轮（信息 → 出令 → 审批 → 裁决 → 稳定性矩阵）──────────────

  /**
   * ★★ **主判据**：连续 3 轮 {@code sd.RunDecision}（真 provider、真 MCP 口、真审批链、真工具面）——
   *
   * <ol>
   *   <li>每轮：模型必须至少成功读到一个带**夹具真值**的读工具结果（信息获取）；
   *   <li>模型若出令（{@code sd.IssueDirective}）：它必须进审批、必须落一条真 revision（{@code commandType =
   *       sd.IssueDirective}）；
   *   <li>令里若有合法且在范围内的命令：GM 经 MCP 调 {@code sd.AdjudicateTick} 执行，断言目标状态真的变（例：{@code
   *       unit.SetTaxRate} ⇒ u-1 的 r-core 税率逐值改变）；
   *   <li>越界探测（读区外 hex/单位）若发生 ⇒ 结果必须是拒/不可见；
   *   <li>三轮无 TOOL_ERROR / 无未捕获异常 / 无预算中止（稳定性硬判据）。
   * </ol>
   *
   * <p>★ **模型不出令或出非法令不伪造**：这类"模型侧不达"只打印 {@code [REAL-LLM-GAP]}/记进稳定性矩阵，不让机制判据变红； 机制侧（provider
   * 可达、读工具可用、审批链、裁决链）不达才红。
   */
  @Test
  @Timeout(value = 45, unit = TimeUnit.MINUTES)
  void threeRoundsOfRealLlmUnitDecisionAndAdjudication() throws Exception {
    List<Map<String, Object>> roundViews = new ArrayList<>();
    List<ToolTrace> allTraces = new ArrayList<>();
    List<String> allApprovals = new ArrayList<>();
    List<String> blockers = new ArrayList<>();
    List<String> scopeViolations = new ArrayList<>();
    System.out.println(
        "[REAL-LLM-START] rounds="
            + ROUNDS
            + " decisionMaker="
            + DM_ID
            + " provider="
            + PROVIDER_ID);
    int committedRounds = 0;
    boolean sawReadWithFixtureTruth = false;
    boolean anyDirectiveCall = false;
    boolean anyDirectiveRevision = false;
    boolean anyDirectiveLanded = false;
    boolean anyAdjudicated = false;
    long expectedRevision = 1L;

    for (int round = 1; round <= ROUNDS; round++) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("round", round);
      view.put("expectedRevision", expectedRevision);
      long headBefore = head();
      List<LlmMessage> historyBefore = conversationMessages();
      int conversationBefore = historyBefore.size();
      System.out.println(
          "[REAL-LLM-HISTORY] round="
              + round
              + " messages="
              + conversationBefore
              + " assistantWithoutReasoning="
              + assistantWithoutReasoning(historyBefore)
              + " assistantWithoutReasoningAny="
              + assistantWithoutReasoningAny(historyBefore)
              + " assistantWithToolCalls="
              + assistantWithToolCalls(historyBefore));
      long roundStart = System.nanoTime();

      McpSchema.CallToolResult trigger =
          callWithApproval(RunDecisionTool.NAME, runDecisionArgs(DM_ID, expectedRevision));
      long triggerMillis = (System.nanoTime() - roundStart) / 1_000_000L;
      String triggerRaw = wireText(trigger);
      System.out.println(
          "[REAL-LLM-ROUND] round="
              + round
              + " expectedRevision="
              + expectedRevision
              + " isError="
              + trigger.isError()
              + " raw="
              + triggerRaw);

      JsonNode triggerBody;
      try {
        triggerBody = trigger.isError() ? errorBody(trigger) : JSON.readTree(triggerRaw);
      } catch (RuntimeException e) {
        blockers.add("round " + round + " 触发返回无法解析: " + e.getMessage());
        view.put("blocker", "unparsable-trigger-result");
        roundViews.add(view);
        break;
      }

      List<ToolTrace> roundTraces = collectTraces(round, conversationBefore);
      allTraces.addAll(roundTraces);
      allApprovals.addAll(approvals);
      view.put("triggerMs", triggerMillis);
      view.put("llmCalls", triggerBody.path("llmCalls").asInt());
      view.put("toolCalls", triggerBody.path("toolCalls").size());
      view.put("finalText", triggerBody.path("finalText").asText(null));
      view.put("approvals", List.copyOf(approvals));
      view.put("headBefore", headBefore);
      view.put("headAfterTrigger", head());
      view.put("traceCalls", tracesView(roundTraces));

      if (trigger.isError()) {
        blockers.add(
            "round "
                + round
                + " sd.RunDecision 返回 error: "
                + triggerRaw.substring(0, Math.min(triggerRaw.length(), 800)));
        view.put("blocker", "trigger-error");
        if (triggerRaw.contains("reasoning_content")) {
          view.put("blockerKind", "provider-reasoning-content-replay");
          System.out.println(
              "[REAL-LLM-BLOCKER] round="
                  + round
                  + " kind=provider-reasoning-content-replay raw="
                  + triggerRaw);
        }
        view.put("roundMs", (System.nanoTime() - roundStart) / 1_000_000L);
        roundViews.add(view);
        System.out.println("[REAL-LLM-SUMMARY] " + json(view));
        break;
      }
      if (!"committed".equals(triggerBody.path("result").asText())) {
        blockers.add("round " + round + " 触发事实未提交: result=" + triggerBody.path("result").asText());
        view.put("blocker", "trigger-not-committed");
        roundViews.add(view);
        break;
      }
      if (triggerBody.path("abortedByBudget").asBoolean()) {
        blockers.add("round " + round + " 因 LLM 回合预算中止");
        view.put("blocker", "turn-budget");
        roundViews.add(view);
        break;
      }
      committedRounds++;

      // 读工具的统计（成功/拒/越界探测）
      int readsOk = 0;
      int readsRefused = 0;
      int outOfScope = 0;
      for (ToolTrace trace : roundTraces) {
        String realName = realToolName(trace.tool());
        boolean readTool = READ_TOOL_REAL_NAMES.contains(realName);
        if (readTool && trace.ok()) {
          readsOk++;
          if (containsFixtureTruth(trace.result())) {
            sawReadWithFixtureTruth = true;
          }
        }
        if (readTool && !trace.ok()) {
          readsRefused++;
        }
        if (isOutOfScopeAttempt(trace)) {
          outOfScope++;
          if (trace.ok()) {
            scopeViolations.add(
                "round " + round + " " + trace.tool() + " 越界却成功: " + argsJson(trace.args()));
          }
        }
      }
      view.put("readsOk", readsOk);
      view.put("readsRefused", readsRefused);
      view.put("outOfScopeAttempts", outOfScope);

      // 出令：轨迹里出现 sd.IssueDirective（无论成否）都必须进审批；成功则必须落真 revision。
      boolean directiveCall =
          roundTraces.stream()
              .anyMatch(t -> IssueDirectiveTool.NAME.equals(realToolName(t.tool())));
      boolean directiveOk =
          roundTraces.stream()
              .anyMatch(t -> IssueDirectiveTool.NAME.equals(realToolName(t.tool())) && t.ok());
      if (directiveCall) {
        anyDirectiveCall = true;
        if (!approvals.contains(IssueDirectiveTool.NAME)) {
          scopeViolations.add("round " + round + " sd.IssueDirective 出现但审批记录里没有它");
        }
      }
      long headAfterTrigger = head();
      if (directiveOk && headAfterTrigger > headBefore + 1) {
        anyDirectiveRevision = true;
      }
      view.put("directiveCall", directiveCall);
      view.put("directiveOk", directiveOk);

      // 找出这一轮新落的、仍活跃的令（模型可能同 tick 重写 ⇒ 取最终活跃那条）
      Set<DirectiveId> known = new LinkedHashSet<>(sdAt(headBefore).directives().keySet());
      List<Directive> fresh = new ArrayList<>();
      for (Directive directive : sdAt(headAfterTrigger).directives().values()) {
        if (!known.contains(directive.id())) {
          fresh.add(directive);
        }
      }
      Optional<Directive> active =
          fresh.stream()
              .filter(
                  d ->
                      d.status() == DirectiveStatus.ISSUED || d.status() == DirectiveStatus.PLANNED)
              .findFirst();
      if (active.isPresent()) {
        anyDirectiveLanded = true;
        Directive directive = active.get();
        view.put("directiveId", directive.id().value());
        view.put("directiveTick", directive.tick());
        view.put("directiveCommands", commandTypes(directive));
        if (!directiveRevisionsExist(headBefore, headAfterTrigger)) {
          scopeViolations.add("round " + round + " 令已落世界但没有 sd.IssueDirective revision 行");
        }
        System.out.println(
            "[REAL-LLM-DIRECTIVE] round="
                + round
                + " id="
                + directive.id().value()
                + " tick="
                + directive.tick()
                + " commands="
                + commandTypes(directive));
        for (DirectiveCommand command : directive.commands()) {
          System.out.println(
              "[REAL-LLM-DIRECTIVE-COMMAND] round="
                  + round
                  + " type="
                  + command.type()
                  + " payloadJson="
                  + command.payloadJson());
        }

        // 目标状态基线（裁决前）
        long taxBefore = taxRateAt(headAfterTrigger, R_CORE);
        view.put("taxBefore", taxBefore);
        Optional<Long> requestedRate = requestedCoreTaxRate(directive);

        // GM 经 MCP 裁决这一 tick（GM 面自动过审批）
        Map<String, Object> adjudicateArgs = new LinkedHashMap<>();
        adjudicateArgs.put("branch", "main");
        adjudicateArgs.put("expectedRevision", head());
        adjudicateArgs.put("tick", directive.tick());
        McpSchema.CallToolResult adjudication =
            callWithApproval(AdjudicateTickTool.NAME, adjudicateArgs);
        String adjudicationRaw = wireText(adjudication);
        System.out.println(
            "[REAL-LLM-ADJUDICATE] round="
                + round
                + " tick="
                + directive.tick()
                + " isError="
                + adjudication.isError()
                + " raw="
                + adjudicationRaw);
        view.put("adjudicateRaw", adjudicationRaw);
        long headAfter = head();
        view.put("headAfterAdjudication", headAfter);
        String directiveStatus =
            sdAt(headAfter).directives().get(directive.id()) == null
                ? "MISSING"
                : sdAt(headAfter).directives().get(directive.id()).status().name();
        view.put("directiveStatusAfterAdjudication", directiveStatus);
        if (!adjudication.isError()) {
          anyAdjudicated = true;
          JsonNode adjBody = JSON.readTree(adjudicationRaw);
          boolean appliedCoreTax =
              adjudicationAppliedCommand(adjBody, directive.id().value(), "unit.SetTaxRate");
          boolean allApplied = adjudicationAllApplied(adjBody, directive.id().value());
          boolean anyRejected = adjudicationAnyRejected(adjBody, directive.id().value());
          long taxAfter = taxRateAt(headAfter, R_CORE);
          view.put("taxAfter", taxAfter);
          view.put("coreTaxApplied", appliedCoreTax);
          view.put("directiveAllCommandsApplied", allApplied);
          view.put("directiveAnyCommandRejected", anyRejected);
          if (appliedCoreTax && requestedRate.isPresent()) {
            assertThat(taxAfter)
                .as("round %s：unit.SetTaxRate 报了 applied，u-1 的 r-core 税率必须真的变到请求值", round)
                .isEqualTo(requestedRate.get());
          }
          if (!allApplied) {
            String gap = "令里有命令被 GM 裁决拒（见 adjudicateRaw.commands）——模型侧不达，如实记录，不伪造";
            view.put("adjudicationGap", gap);
            System.out.println("[REAL-LLM-GAP] round=" + round + " " + gap);
          }
          // 无论落没落，都把本 tick 的逐条结局打出来（不粉饰）
          System.out.println(
              "[REAL-LLM-ADJUDICATE-COMMANDS] round="
                  + round
                  + " commands="
                  + adjudicationCommandsView(adjBody));
        } else {
          view.put(
              "adjudicationError",
              adjudicationRaw.substring(0, Math.min(adjudicationRaw.length(), 500)));
        }
      } else {
        view.put("directiveId", null);
        String gapNote = directActionsNote(roundTraces);
        view.put("gap", gapNote);
        System.out.println("[REAL-LLM-GAP] round=" + round + " " + gapNote);
      }

      expectedRevision = head();
      view.put("roundMs", (System.nanoTime() - roundStart) / 1_000_000L);
      view.put("headAfter", expectedRevision);
      roundViews.add(view);
      System.out.println("[REAL-LLM-SUMMARY] " + json(view));
    }

    // ★ 先落汇总矩阵（**在断言之前**）：即使某条硬判据红，报告也能拿到逐轮原始账（不粉饰）。
    System.out.println(
        "[REAL-LLM-MATRIX] committedRounds="
            + committedRounds
            + " directiveCall="
            + anyDirectiveCall
            + " directiveRevision="
            + anyDirectiveRevision
            + " directiveLanded="
            + anyDirectiveLanded
            + " adjudicated="
            + anyAdjudicated
            + " readWithFixtureTruth="
            + sawReadWithFixtureTruth
            + " scopeViolations="
            + scopeViolations.size()
            + " blockers="
            + blockers.size());
    System.out.println("[REAL-LLM-ROUNDS-JSON] " + json(roundViews));

    // ── 机制侧硬判据（模型侧行为只记录，不粉饰）──────────────────────────────────────
    assertThat(committedRounds)
        .as("至少一轮 sd.RunDecision 真跑通（provider 可达、触发事实已落）；traces=%s", allTraces)
        .isGreaterThan(0);
    assertThat(blockers).as("不得有 TOOL_ERROR / 未捕获异常 / 回合预算中止（发现即命中，不重试掩盖）").isEmpty();
    assertThat(scopeViolations).as("越界探测若发生必须被拒；决策人出令必须进审批且落真 revision").isEmpty();
    assertThat(sawReadWithFixtureTruth)
        .as("至少一次成功的读工具结果里带夹具真值（u-1 / 真实人口 / 真实格 / r-core）；traces=%s", allTraces)
        .isTrue();
    if (anyDirectiveCall) {
      assertThat(allApprovals).as("出令进了审批链").contains(IssueDirectiveTool.NAME);
    }
    if (anyDirectiveLanded) {
      assertThat(anyAdjudicated).as("已落的令经 GM 经 MCP 裁决").isTrue();
    }
  }

  // ────────────────────────────── MCP / 审批 / 会话助手 ──────────────────────────────

  private Map<String, Object> runDecisionArgs(String decisionMakerId, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", "{\"decisionMakerId\":\"" + decisionMakerId + "\"}");
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args) {
    return client.callTool(new McpSchema.CallToolRequest(toolName, args));
  }

  /**
   * 经真 MCP 传输调工具，并**把这一轮里出现的每条审批都答成"批一次"**（内层 {@code sd.IssueDirective} 走决策人链， 仍要人批；GM 面的触发/裁决由
   * {@code GmAutoApproveGate} 无脑过，不出现在这里）。
   */
  private McpSchema.CallToolResult callWithApproval(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
    FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> client.callTool(request));
    Thread.ofVirtual().name("real-llm-mcp").start(task);
    approvals.clear();
    long deadline = System.nanoTime() + CALL_TIMEOUT.toNanos();
    while (!task.isDone() && System.nanoTime() < deadline) {
      for (ApprovalRequest pending : shell.pendingApprovals().pending()) {
        if (answeredIds.add(pending.id())
            && shell
                .pendingApprovals()
                .decide(pending.id(), ApprovalDecision.APPROVE_ONCE, "test:real-llm")) {
          approvals.add(pending.classKey());
        }
      }
      Thread.sleep(25);
    }
    if (!task.isDone()) {
      task.cancel(true);
      throw new AssertionError(
          "MCP 调用 " + toolName + " 在 " + CALL_TIMEOUT + " 内未返回（可能卡在审批或真 LLM）；本轮已答审批=" + approvals);
    }
    return task.get(5, TimeUnit.SECONDS);
  }

  private McpSyncClient newClient() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + shell.boundMcpPort())
            .endpoint(shell.config().mcpPath())
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    McpSyncClient syncClient =
        McpClient.sync(transport)
            // ★★ 真 LLM 的一轮可能好几分钟，SDK 的 MCP 请求超时缺省是 20s ⇒ 必须显式放宽，
            //   否则 callTool 在客户端先超时（实测：20s 就报 reactor TimeoutException，而服务端那一轮还在跑）。
            .requestTimeout(CALL_TIMEOUT.plusMinutes(2))
            .initializationTimeout(Duration.ofSeconds(20))
            .build();
    syncClient.initialize();
    return syncClient;
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }

  /** 错误结果的正文：剥掉 AgentLib 的 {@code [mosire:code=xxx]} 前缀再解析。 */
  private static JsonNode errorBody(McpSchema.CallToolResult result) throws Exception {
    String text = wireText(result);
    int close = text.indexOf(']');
    String json = text.startsWith("[mosire:code=") && close > 0 ? text.substring(close + 1) : text;
    return JSON.readTree(json.strip());
  }

  // ────────────────────────────── 会话轨迹（工具名 + 参数 + 结果）──────────────────────

  /**
   * 从会话库里取本轮新增的消息，逐条打印并合成 {@link ToolTrace}（工具名 + 原始参数 + 成败 + 原始返回）。
   *
   * <p>★ 为什么不能只看 {@code sd.RunDecision} 的 {@code toolCalls[]}：那份轨迹**没有参数**（只有工具名/成败/摘要），
   * 而本任务要判"越界探测"与"嵌套 payload"，参数是承重证据。
   */
  private List<ToolTrace> collectTraces(int round, int fromIndex) {
    List<LlmMessage> all = conversationMessages();
    List<LlmMessage> fresh = all.subList(Math.min(fromIndex, all.size()), all.size());
    Map<String, ContentPart.ToolCall> callsById = new LinkedHashMap<>();
    List<ToolTrace> traces = new ArrayList<>();
    for (LlmMessage message : fresh) {
      if (LlmMessage.ROLE_ASSISTANT.equals(message.role())) {
        System.out.println(
            "[REAL-LLM-ASSISTANT] round="
                + round
                + " reasoningChars="
                + message.reasoning().length()
                + " toolCalls="
                + message.content().stream().filter(ContentPart.ToolCall.class::isInstance).count()
                + " textChars="
                + message.content().stream()
                    .filter(ContentPart.Text.class::isInstance)
                    .mapToInt(part -> ((ContentPart.Text) part).text().length())
                    .sum());
        for (ContentPart part : message.content()) {
          if (part instanceof ContentPart.ToolCall call) {
            callsById.put(call.id(), call);
            System.out.println(
                "[REAL-LLM-CALL] round="
                    + round
                    + " id="
                    + call.id()
                    + " tool="
                    + call.name()
                    + " args="
                    + json(call.arguments()));
          } else if (part instanceof ContentPart.Text text && !text.text().isBlank()) {
            System.out.println(
                "[REAL-LLM-TEXT] round=" + round + " " + truncate(text.text(), 4000));
          }
        }
      } else if (LlmMessage.ROLE_TOOL.equals(message.role())) {
        for (ContentPart part : message.content()) {
          if (part instanceof ContentPart.ToolResult result) {
            ContentPart.ToolCall call = callsById.get(result.toolCallId());
            String tool = call == null ? result.name() : call.name();
            Map<String, Object> args = call == null ? Map.of() : call.arguments();
            boolean ok = !result.isError();
            String payload = ok ? result.content() : result.error();
            traces.add(new ToolTrace(round, tool, args, ok, payload == null ? "" : payload));
            System.out.println(
                "[REAL-LLM-TOOL-RESULT] round="
                    + round
                    + " id="
                    + result.toolCallId()
                    + " tool="
                    + tool
                    + " ok="
                    + ok
                    + " payload="
                    + truncate(payload == null ? "" : payload, 4000));
          }
        }
      }
    }
    return traces;
  }

  private List<LlmMessage> conversationMessages() {
    try (SqliteConversationStore conversations =
        SqliteConversationStore.open(
            storeDir.resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME))) {
      return conversations.load(CONVERSATION_ID);
    }
  }

  /** 历史里"有 tool_calls 却没有 reasoning"的 assistant 条数（诊断 provider 的 thinking-mode 回传要求）。 */
  private static int assistantWithoutReasoning(List<LlmMessage> messages) {
    int count = 0;
    for (LlmMessage message : messages) {
      if (!LlmMessage.ROLE_ASSISTANT.equals(message.role()) || message.hasReasoning()) {
        continue;
      }
      boolean toolCall =
          message.content().stream().anyMatch(ContentPart.ToolCall.class::isInstance);
      if (toolCall) {
        count++;
      }
    }
    return count;
  }

  private static int assistantWithToolCalls(List<LlmMessage> messages) {
    int count = 0;
    for (LlmMessage message : messages) {
      if (LlmMessage.ROLE_ASSISTANT.equals(message.role())
          && message.content().stream().anyMatch(ContentPart.ToolCall.class::isInstance)) {
        count++;
      }
    }
    return count;
  }

  /** 历史里所有没有 reasoning 的 assistant 条数（含只收口的文本消息）——provider 400 的第一诊断量。 */
  private static int assistantWithoutReasoningAny(List<LlmMessage> messages) {
    int count = 0;
    for (LlmMessage message : messages) {
      if (LlmMessage.ROLE_ASSISTANT.equals(message.role()) && !message.hasReasoning()) {
        count++;
      }
    }
    return count;
  }

  // ────────────────────────────── 判定小工具 ──────────────────────────────

  /** 一份轨迹是不是"读区外资源"的探测：工具 + 参数共同判（拒绝与否由调用方判）。 */
  private static boolean isOutOfScopeAttempt(ToolTrace trace) {
    return switch (realToolName(trace.tool())) {
      case "simos.map.hex", "simos.social.population", "simos.economy.hex" ->
          intArg(trace.args(), "q", H22.q()) && intArg(trace.args(), "r", H22.r());
      case "simos.unit.get" -> "u-out".equals(textArg(trace.args(), "id"));
      case "simos.map.region" -> REGION_OUT.equals(textArg(trace.args(), "regionId"));
      default -> false;
    };
  }

  /**
   * 线格式工具名 ⇒ 真实名（模型看到并调用的名字带下划线，权限链/注册表只认真实名）。
   *
   * <p>★ AgentLib 的 {@code LlmToolNames} 把非字母数字一律折成下划线；本仓工具名不含原生下划线 ⇒ 反向映射是纯函数。
   */
  private static String realToolName(String wireName) {
    return wireName == null ? "" : wireName.replace('_', '.');
  }

  private static boolean intArg(Map<String, Object> args, String key, long expected) {
    Object value = args.get(key);
    return value instanceof Number number && number.longValue() == expected;
  }

  private static String textArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static boolean containsFixtureTruth(String text) {
    for (String truth : FIXTURE_TRUTHS) {
      if (text.contains(truth)) {
        return true;
      }
    }
    return false;
  }

  private static String directActionsNote(List<ToolTrace> traces) {
    long calls = traces.size();
    long failed = traces.stream().filter(t -> !t.ok()).count();
    return "模型未出令（traces=" + calls + "，失败=" + failed + "）——如实记录，不伪造";
  }

  private static List<String> commandTypes(Directive directive) {
    return directive.commands().stream().map(DirectiveCommand::type).toList();
  }

  /** 令里请求的 r-core 长期税率（没有这条命令/字段不是数字 ⇒ 空）。 */
  private static Optional<Long> requestedCoreTaxRate(Directive directive) {
    for (DirectiveCommand command : directive.commands()) {
      if (!"unit.SetTaxRate".equals(command.type())) {
        continue;
      }
      JsonNode payload;
      try {
        payload = JSON.readTree(command.payloadJson());
      } catch (JsonProcessingException e) {
        continue;
      }
      if ("u-1".equals(payload.path("unitId").asText())
          && REGION_CORE.equals(payload.path("regionId").asText())
          && payload.get("ratePerMille") != null
          && payload.get("ratePerMille").isNumber()) {
        return Optional.of(payload.get("ratePerMille").asLong());
      }
    }
    return Optional.empty();
  }

  /** 裁决结果里某条令的命令是不是"applied"（按令 id + 命令类型命中；提交视图的 commands 行不带载荷）。 */
  private static boolean adjudicationAppliedCommand(
      JsonNode adjBody, String directiveId, String type) {
    JsonNode commands = adjBody.get("commands");
    if (commands == null || !commands.isArray()) {
      return false;
    }
    for (JsonNode row : commands) {
      if (type.equals(row.path("type").asText())
          && directiveId.equals(row.path("directiveId").asText(""))
          && "applied".equals(row.path("result").asText())) {
        return true;
      }
    }
    return false;
  }

  /** 该令的全部命令是否都 applied（至少一条；空命令集也算"没有东西被拒"，返回 true）。 */
  private static boolean adjudicationAllApplied(JsonNode adjBody, String directiveId) {
    JsonNode commands = adjBody.get("commands");
    if (commands == null || !commands.isArray()) {
      return true;
    }
    for (JsonNode row : commands) {
      if (directiveId.equals(row.path("directiveId").asText(""))
          && !"applied".equals(row.path("result").asText())) {
        return false;
      }
    }
    return true;
  }

  /** 该令是否有命令被裁决拒。 */
  private static boolean adjudicationAnyRejected(JsonNode adjBody, String directiveId) {
    JsonNode commands = adjBody.get("commands");
    if (commands == null || !commands.isArray()) {
      return false;
    }
    for (JsonNode row : commands) {
      if (directiveId.equals(row.path("directiveId").asText(""))
          && "rejected".equals(row.path("result").asText())) {
        return true;
      }
    }
    return false;
  }

  private static List<Map<String, Object>> adjudicationCommandsView(JsonNode adjBody) {
    List<Map<String, Object>> out = new ArrayList<>();
    JsonNode commands = adjBody.get("commands");
    if (commands == null || !commands.isArray()) {
      return out;
    }
    for (JsonNode row : commands) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("directiveId", row.path("directiveId").asText(null));
      item.put("type", row.path("type").asText(null));
      item.put("result", row.path("result").asText(null));
      item.put("reason", row.path("reason").asText(null));
      out.add(item);
    }
    return out;
  }

  private static Map<String, Object> tracesView(List<ToolTrace> traces) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ToolTrace trace : traces) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("tool", trace.tool());
      item.put("args", trace.args());
      item.put("ok", trace.ok());
      item.put("payload", truncate(trace.result(), 600));
      out.add(item);
    }
    return Map.of("calls", out);
  }

  private static String argsJson(Map<String, Object> args) {
    return json(args);
  }

  private static String truncate(String text, int max) {
    if (text == null || text.length() <= max) {
      return text;
    }
    return text.substring(0, max) + "…(截断，原长 " + text.length() + ")";
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("测试自身 JSON 序列化失败", e);
    }
  }

  /** 可选轮数覆盖（默认 3；调试时用 {@code SIMOS_REAL_LLM_ROUNDS=1} 先跑一轮冒烟）。 */
  private static int envInt(String name, int fallback) {
    String text = System.getenv(name);
    if (text == null || text.isBlank()) {
      return fallback;
    }
    try {
      int value = Integer.parseInt(text.strip());
      return value >= 1 ? value : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  // ────────────────────────────── 状态读回 ──────────────────────────────

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  private SimulationState stateAt(long revision) {
    return shell.coreSimos().replay(ref("main", revision));
  }

  private SdState sdAt(long revision) {
    return ((SdSnapshot) stateAt(revision).module("sd").orElseThrow()).state();
  }

  private static UnitState unitStateAt(SimulationState state) {
    return ((UnitSnapshot) state.module("unit").orElseThrow()).state();
  }

  private UnitState unitStateAt(long revision) {
    return unitStateAt(stateAt(revision));
  }

  private static SocialData socialAt(SimulationState state) {
    return ((SocialSnapshot) state.module("social").orElseThrow()).data();
  }

  private SocialData socialAt(long revision) {
    return socialAt(stateAt(revision));
  }

  private long taxRateAt(long revision, RegionId region) {
    Unit unit = unitStateAt(revision).units().get(U1);
    if (unit == null) {
      return Long.MIN_VALUE;
    }
    return unit.jurisdiction()
        .map(j -> j.taxRatePerMilleByRegion().getOrDefault(region, Long.MIN_VALUE))
        .orElse(Long.MIN_VALUE);
  }

  /** 触发前后是否真的有 {@code sd.IssueDirective} 的 revision 行（铁律 2 的落盘证据）。 */
  private boolean directiveRevisionsExist(long headBefore, long headAfter) {
    try (SqliteStore store = SqliteStore.open(storeDir.resolve(CoreSimos.DB_FILE_NAME))) {
      Timeline timeline = new Timeline(store, CHECKPOINT_INTERVAL);
      for (long revision = headBefore + 1; revision <= headAfter; revision++) {
        Optional<RevisionRow> row = timeline.row(ref("main", revision));
        if (row.isPresent() && IssueDirectiveTool.NAME.equals(row.get().commandType())) {
          return true;
        }
      }
      return false;
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private void copyRepoProviderConfig() throws IOException {
    Path repoConfig = repoRoot().resolve("config").resolve("llm-providers.json");
    assertThat(Files.isRegularFile(repoConfig)).as("仓库存在 %s", repoConfig).isTrue();
    // ★ 逐字节复制既有事实，不读内容、不打印任何值（密钥只落在 store 的迁移源里）。
    Files.copy(
        repoConfig, storeDir.resolve("llm-providers.json"), StandardCopyOption.REPLACE_EXISTING);
  }

  private void copyRepoSkills() throws IOException {
    Path skills = repoRoot().resolve("config").resolve("skills");
    assertThat(Files.isDirectory(skills)).as("仓库存在技能种子目录 %s", skills).isTrue();
    Path target = storeDir.resolve("skills");
    Files.createDirectories(target);
    int copied = 0;
    try (Stream<Path> files = Files.list(skills)) {
      for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
        Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        copied++;
      }
    }
    assertThat(copied).as("至少复制一篇技能（SkillLibrary 的 store 覆盖目录）").isGreaterThan(0);
  }

  /** 从 CWD 往上找仓库根（兼容 surefire 的模块目录 CWD 与 IDE 的仓根 CWD）。 */
  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 4 && dir != null; depth++) {
      if (Files.isRegularFile(dir.resolve("config").resolve("llm-providers.json"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("找不到仓库根（含 config/llm-providers.json）");
  }

  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(storeDir.resolve(CoreSimos.DB_FILE_NAME))) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  INITIATOR,
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    } catch (Exception e) {
      throw new IllegalStateException("创世 revision 落盘失败", e);
    }
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, coreUnit(), U_OUT, outUnit())));
    SocialData social = socialData();
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoRegionMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sdState()),
                "economy", new EconomySnapshot(ref("main", 1), T7, EconomyData.empty()),
                "actor", new ActorSnapshot(ref("main", 1), T7, ActorData.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(storeDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new SdCodec(),
                    new EconomyCodec(),
                    new ActorCodec())));
  }

  /** u-1：(1,1)、visionRadius=2、真挂 jurisdiction{r-core: 0‰, caps 1000/1000/1000, admin 1000}。 */
  private static Unit coreUnit() {
    Jurisdiction jurisdiction = new Jurisdiction(Map.of(R_CORE, 0L), 1000L, 1000L, 1000L, 1000);
    return new Unit(
        U1,
        "第一连",
        emptyParent(),
        fixedPosition(H11),
        List.of(new CompositionEntry("步枪", 50)),
        4,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        attachedSeries(),
        emptyOffset(),
        Optional.empty(),
        2,
        Optional.of(jurisdiction));
  }

  /** 区外对照单位：在 (2,2)、无管辖、visionRadius=1。 */
  private static Unit outUnit() {
    return new Unit(
        U_OUT,
        "外围支队",
        emptyParent(),
        fixedPosition(H22),
        List.of(new CompositionEntry("步枪", 20)),
        4,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        attachedSeries(),
        emptyOffset(),
        Optional.empty(),
        1,
        Optional.empty());
  }

  private static SegmentedSeries<Optional<UnitId>> emptyParent() {
    return new SegmentedSeries<>(
        List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null);
  }

  private static SegmentedSeries<Optional<HexCoord>> fixedPosition(HexCoord hex) {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(hex))), List.of(), null);
  }

  private static SegmentedSeries<Boolean> attachedSeries() {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null);
  }

  private static SegmentedSeries<Optional<RelativeOffset>> emptyOffset() {
    return new SegmentedSeries<>(
        List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null);
  }

  /** 4 格地图：r-core = 前 3 格（管辖 hex 集）、r-out = (2,2)（区外对照）。 */
  private static GameMap twoRegionMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : List.of(H11, H12, H21, H22)) {
      hexes.put(coord, new HexCell(0.5));
    }
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        R_CORE,
        Region.of(
            R_CORE,
            "核心区",
            Set.of(H11, H12, H21),
            new RegionMeta(null, NationTag.tagFor(FRA), null, null)));
    regions.put(
        R_OUT,
        Region.of(
            R_OUT, "区外对照区", Set.of(H22), new RegionMeta(null, NationTag.tagFor(FRA), null, null)));
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

  private static SocialData socialData() {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    Map<io.mosire.simos.social.api.id.PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    Map<io.mosire.simos.social.api.id.PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Long> entry : POPULATION.entrySet()) {
      populations.put(entry.getKey(), populationSeries(entry.getValue()));
      io.mosire.simos.social.api.id.PeopleLotId lot =
          PopulationLots.rural(entry.getKey(), Sex.MALE, "0");
      groups.put(lot, new PopulationGroup(lot, Sex.MALE, entry.getValue(), 30L, 0L));
      locations.put(lot, entry.getKey());
    }
    return io.mosire.simos.app.testing.SocialHouseholdFixture.withHouseholdsAt(
        populations, Map.of(), groups, locations);
  }

  private static PopulationSeries populationSeries(long count) {
    return new PopulationSeries(
        new Segment<>(T0, count),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }

  /** 国家 FRA + 军队 a1（rootUnit=u-1）+ 决策人 dm-u1 + 一条发给它的任务简报。 */
  private static SdState sdState() {
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(FRA, new Nation(FRA, "国家 FRA", R_CORE, 0));
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(A1, new Army(A1, FRA, U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM, decisionMaker());

    String docAddress = DecisionDoc.addressOf("u1-brief-0");
    SdInfoEntry brief =
        new SdInfoEntry(
            SdInfoIds.synthesize(docAddress, 0),
            0L,
            Set.of(DM),
            Set.of(),
            DecisionDoc.KEY,
            briefText(),
            Optional.empty(),
            new RevisionId(1),
            Optional.empty(),
            Optional.empty());
    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>();
    info.put(docAddress, List.of(brief));
    return SdState.empty()
        .withNations(nations)
        .withArmies(armies)
        .withDecisionMakers(makers)
        .withInfo(info);
  }

  /**
   * ★ **发给 dm-u1 的任务简报**：{@code sd.RunDecision} 的载荷只有 decisionMakerId（没有任务字段），生产里"这一局该做什么" 的正规出处就是
   * Docs。简报把任务收敛到"核实 → 用合规命令把 r-core 税率调到 100‰ → 顺带核对区外 (2,2)/u-out 不可见"，
   * 使"能不能出正确决策"这条判据不落在模型的自由发挥上。
   */
  private static String briefText() {
    ObjectNode brief = JSON.createObjectNode();
    brief.put("title", "u-1 第 0 号任务简报");
    brief.put(
        "body",
        "你是军队 a1（根单位 u-1）的决策人，辖地是区域 r-core（(1,1)/(1,2)/(2,1) 三格）。本轮任务："
            + "① 先用读工具（command.catalog / map.hex / unit.get / social.population / skill / decision docs）核实辖区人口与单位状态；"
            + "② 然后出令 sd.IssueDirective，用 unit.SetTaxRate 把 r-core 的长期税率从 0‰ 调到 100‰（unitId=u-1，regionId=r-core）；"
            + "③ tick 填你从时间轴读到的当前世界 tick，不要猜；"
            + "④ commands 只放领域命令，不得含 sd.*；"
            + "⑤ 顺带核对：区域 r-out、格 (2,2) 与单位 u-out 不在你的管辖内——若系统拒绝就是不可见，把它们当作不存在，不要写进决心，也不要试图绕过。"
            + "★ GM 事实（你的军队读工具看不到区域层，这是当前生产的已知限制，不要据此推断区域不存在）："
            + "区域 r-core 已在世界中，u-1 也已挂着 jurisdiction{r-core:0‰}——不要下 map.CreateRegion / map.UpdateRegion，"
            + "直接下 unit.SetTaxRate 即可（unit.SetJurisdiction 已是现状，无需重复）。"
            + "★ 写法提示：外层 target 字段**可省略**（推荐省略）；若给，它必须是两段地址（如 unit:u-1），不能写裸名 r-core。"
            + "内层 commands[].payloadJson 里的 regionId 用**裸区域 id** r-core（不要加 map: 前缀）——区域 id 与地址是两种写法。");
    brief.put("author", "GM");
    return json(brief);
  }

  private static DecisionMaker decisionMaker() {
    return new DecisionMaker(
        DM, new Affiliation.Army(A1), Set.of(), accessLimit(), 1, Optional.of(PROVIDER_ID), 0L);
  }

  /**
   * ★ **显式收紧**：管辖 hex 集从 {@code r-core} 的 {@code Region.hexes()} 派生（与下一阶段的自动接线同源）。
   *
   * <p>map：r-core 各 hex；social：r-core 各格；unit：{@code u-1}。★ 现状（未改生产）：ArmyScope 的 map 通道只给**逐 hex**
   * 前缀，故 {@code simos.map.region} 的 region 资源对军队决策人不可达——本测试的成功读走 {@code map.hex / unit.get /
   * social.population / catalog / skill}。
   */
  private static AccessLimit accessLimit() {
    Set<HexCoord> coreHexes = Set.of(H11, H12, H21);
    Map<String, Set<String>> prefixes = new LinkedHashMap<>();
    Set<String> mapPrefixes = new LinkedHashSet<>();
    Set<String> socialPrefixes = new LinkedHashSet<>();
    for (HexCoord coord : coreHexes) {
      mapPrefixes.add(ToolSupport.resourceHex(MAP_ID, coord.q(), coord.r()).path());
      socialPrefixes.add(ToolSupport.resourceSocial(coord.q(), coord.r()).path());
    }
    prefixes.put("map", mapPrefixes);
    prefixes.put("social", socialPrefixes);
    prefixes.put("unit", Set.of(ToolSupport.resourceUnit(U1.value()).path()));
    return AccessLimit.ofPrefixes(prefixes);
  }

  // ────────────────────────────── 轨迹小类型 ──────────────────────────────

  /** 一次工具调用的原始账（线格式工具名 + 模型给的参数 + 成败 + 原始返回）。 */
  private record ToolTrace(
      int round, String tool, Map<String, Object> args, boolean ok, String result) {}
}
