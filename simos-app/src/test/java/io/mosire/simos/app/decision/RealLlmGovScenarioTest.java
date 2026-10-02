package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.docs.DecisionDoc;
import io.mosire.simos.app.llm.ProviderLlm;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.read.LlmProvidersTool;
import io.mosire.simos.app.tools.write.ActorAdjustAccountsTool;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.app.tools.write.GovAbsorbUnitTool;
import io.mosire.simos.app.tools.write.GovCreateOfficeTool;
import io.mosire.simos.app.tools.write.GovDispatchTeamTool;
import io.mosire.simos.app.tools.write.GovRetireStaffTool;
import io.mosire.simos.app.tools.write.GovSelectExamineesTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.RunDecisionTool;
import io.mosire.simos.app.tools.write.SdPutInfoTool;
import io.mosire.simos.app.tools.write.UnitSetJurisdictionTool;
import io.mosire.simos.app.world.CompactThreeNationsWorld;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.spi.IssueDirectiveHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.StaffRole;
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
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>真 LLM 多决策人 GOV 场景验收（阶段 13C）</b>：在 compact 三国 class-first 世界上建多级 GOV 决策人，把 A
 * 的四类人员流转作为中央决策人的目标，观察「中央出令 → 省级响应/执行 → GM 代执行 → GM 文档回复」的协作链；中央前两轮不作为则 GM
 * 把首都人口减半并写入它的决策文档，再观察是否被促动。
 *
 * <p>★★ <b>本类默认跳过、会打真网络</b>：整类门控 {@code SIMOS_REAL_LLM=1}；另有一个只验夹具与四条 GM 工具（不打 LLM）的自检方法，门控 {@code
 * SIMOS_GOV_SCENARIO_FIXTURE_ONLY=1}。真跑命令见类尾注释。
 *
 * <p>★★ <b>判据只钉可观察行为</b>：每轮真轨迹/真 revision、连续 ≥3 轮无 {@code TOOL_ERROR}/未捕获异常、中央与省级的令、
 * 范围隔离（中央读不到省、命令省资源必拒；省在辖区内可读）、A 四类工具至少真跑过一遍且逐值守恒、升级减半逐批恰为原值/2。 模型侧不达（不出令/不提目标/未触发越权探测）会以 {@code
 * [GOV-SCENARIO-MODEL-GAP]} 如实打印，并在收尾的模型判据断言里点名—— 这类红是模型侧失败，不是夹具/机制错误。
 */
@EnabledIfEnvironmentVariable(named = "SIMOS_REAL_LLM", matches = "1")
class RealLlmGovScenarioTest {

  private static final String MAP_ID = CompactThreeNationsWorld.MAP_ID;
  private static final String PROVIDER_ID = "mosire-flash";
  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final String INITIATOR = "agent:real-llm-gov-scenario";
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 每轮触发后的等待上限（含真模型思考 + 工具 + 内层审批）。 */
  private static final Duration CALL_TIMEOUT = Duration.ofMinutes(8);

  /** 默认 3 轮（判据要求 ≥3）；调试可设 {@code SIMOS_GOV_SCENARIO_ROUNDS=1}；上限 6。 */
  private static final int ROUNDS = clamp(envInt("SIMOS_GOV_SCENARIO_ROUNDS", 3), 1, 6);

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CommodityId CLOTH = new CommodityId(PilotModel.CLOTH);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  /** 四条工具名（GM 代执行/补执行的靶子）。 */
  private static final String TOOL_SELECT = GovSelectExamineesTool.NAME;

  private static final String TOOL_DISPATCH = GovDispatchTeamTool.NAME;
  private static final String TOOL_ABSORB = GovAbsorbUnitTool.NAME;
  private static final String TOOL_RETIRE = GovRetireStaffTool.NAME;

  private static final List<String> SELECT_WORDS =
      List.of("科举", "选人", "选送", "选考", "考试", "selectExaminees", "select_examinees");
  private static final List<String> DISPATCH_WORDS =
      List.of("调查", "派出", "派遣", "dispatchTeam", "dispatch_team", "查访");
  private static final List<String> ABSORB_WORDS =
      List.of("吸收", "入编", "收编", "absorbUnit", "absorb_unit");
  private static final List<String> RETIRE_WORDS =
      List.of("退休", "回籍", "致仕", "离编", "retireStaff", "retire_staff");
  private static final List<String> ALL_GOAL_WORDS =
      Stream.of(SELECT_WORDS, DISPATCH_WORDS, ABSORB_WORDS, RETIRE_WORDS)
          .flatMap(List::stream)
          .toList();

  private static final Pattern NUMBER = Pattern.compile("(\\d+)");

  @TempDir Path tempDir;

  private Path storeDir;
  private Shell shell;
  private McpSyncClient client;

  /** 三国夹具（key → 固定装配）。 */
  private final Map<String, NationFixture> nations = new LinkedHashMap<>();

  /** 本次 MCP 调用里答过的审批（工具名 = classKey）。 */
  private final List<String> approvals = new ArrayList<>();

  /** 已答过的审批 id（全局，避免对同一 id 重复 decide）。 */
  private final Set<String> answeredIds = new HashSet<>();

  /** 机制侧阻断（TOOL_ERROR / 未捕获 / MCP 调用失败）——硬判据。 */
  private final List<String> blockers = new ArrayList<>();

  /** 越权却成功（范围泄漏）——硬判据。 */
  private final List<String> scopeViolations = new ArrayList<>();

  /** 逐值守恒失败明细——硬判据。 */
  private final List<String> conservationFailures = new ArrayList<>();

  /** 已从 DM 意图实际执行成功的工具类型。 */
  private final Set<String> executedToolTypes = new LinkedHashSet<>();

  /** 由 DM 意图（中央或省）触发的工具类型。 */
  private final Set<String> scenarioToolTypes = new LinkedHashSet<>();

  /** 每次 GM 代执行的记录（打印 + 矩阵 + 报告）。 */
  private final List<Map<String, Object>> executions = new ArrayList<>();

  /** 已触发减半的国家 key（每国至多一次）。 */
  private final Set<String> punishedNations = new LinkedHashSet<>();

  /** 中央 DM 越权尝试的原始证据。 */
  private final List<Map<String, Object>> scopeAttempts = new ArrayList<>();

  /** 已裁决过的 tick（同一 tick 不能重复裁决：幂等闸会响亮拒）。 */
  private final Set<Long> adjudicatedTicks = new LinkedHashSet<>();

  private boolean scopeDeniedObserved;
  private boolean centralOutOfScopeAttempt;
  private boolean provinceDirective;
  private boolean provinceIntentAction;

  @BeforeEach
  void startShellAndBuildScenario() throws Exception {
    storeDir = tempDir.resolve("store");
    Files.createDirectories(storeDir);
    copyRepoProviderConfig();
    copyRepoSkills();
    seedGenesis();
    // ★ 不注入 llm ⇒ 生产路径：按决策人 providerId 经 AgentLib 配置解析真 provider。
    ShellConfig shellConfig =
        new ShellConfig(
            storeDir, CHECKPOINT_INTERVAL, 0, 0, "/mcp", 0, INITIATOR, MAP_ID, "127.0.0.1", false);
    boolean fake = "1".equals(envText("SIMOS_GOV_SCENARIO_FAKE"));
    if (fake) {
      fakeLlmClients = new ScriptedGovLlmClients();
      System.out.println("[GOV-SCENARIO-FAKE] 使用脚本 LLM（仅调试；真实验收不设 SIMOS_GOV_SCENARIO_FAKE）");
    }
    shell = fake ? Shell.start(shellConfig, fakeLlmClients) : Shell.start(shellConfig);
    client = newClient();
    verifyProviderConfig();
    buildWorld();
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

  // ────────────────────────────── 主场景 ──────────────────────────────

  @Test
  @Timeout(value = 60, unit = TimeUnit.MINUTES)
  void multiLevelGovScenario() throws Exception {
    System.out.println(
        "[GOV-SCENARIO-START] rounds="
            + ROUNDS
            + " nations="
            + nations.size()
            + " provider="
            + PROVIDER_ID
            + " fixtureOnlyGate="
            + envText("SIMOS_GOV_SCENARIO_FIXTURE_ONLY"));
    assertFixtureWired();
    assertScopeIsolationDeterministic();

    List<Map<String, Object>> roundViews = new ArrayList<>();
    boolean centralAnyDirective = false;
    boolean centralAnyQualified = false;

    for (int round = 1; round <= ROUNDS; round++) {
      long roundTick = currentTick();
      Map<String, Object> roundView = new LinkedHashMap<>();
      roundView.put("round", round);
      roundView.put("tick", roundTick);
      List<Map<String, Object>> runViews = new ArrayList<>();
      roundView.put("runs", runViews);
      List<DirectiveInfo> roundDirectives = new ArrayList<>();

      // ① 中央 DM 先跑；中央令处理后，GM 才把结果写文档/转达给省 —— 于是省在本轮就能看到中央令。
      for (NationFixture nf : nations.values()) {
        DmRun run = runDecision(round, nf, true);
        runViews.add(run.view());
        if (run.error != null) {
          nf.centralRunsError++;
          blockers.add("round " + round + " central " + nf.key + " " + run.error);
          continue;
        }
        nf.centralRunsOk++;
        for (DirectiveInfo info : run.directives) {
          roundDirectives.add(info);
          centralAnyDirective = true;
          nf.centralDirectiveCount++;
          if (mentionsGoal(info.intent())) {
            centralAnyQualified = true;
            if (round <= 2) {
              nf.qualifiedFirstTwo = true;
            }
            if (nf.punished) {
              nf.actedAfterPunishment = true;
            }
          }
        }
        List<ExecutionRecord> acted = processIntentActions(round, nf, run.directives, true);
        if (!run.directives.isEmpty()) {
          writeCentralOutcomeDoc(round, nf, run.directives, acted);
          writeProvinceRelayDoc(round, nf, run.directives, acted);
        }
      }

      // ② 省级 DM 跑（能看到上一拍的中央令转达文档）。
      for (NationFixture nf : nations.values()) {
        DmRun run = runDecision(round, nf, false);
        runViews.add(run.view());
        if (run.error != null) {
          blockers.add("round " + round + " province " + nf.key + " " + run.error);
          continue;
        }
        if (!run.directives.isEmpty()) {
          provinceDirective = true;
        }
        for (DirectiveInfo info : run.directives) {
          roundDirectives.add(info);
          if (mentionsGoal(info.intent())) {
            List<ExecutionRecord> acted = processIntentActions(round, nf, List.of(info), false);
            if (!acted.isEmpty()) {
              provinceIntentAction = true;
            }
          }
        }
      }

      // ③ 同一轮所有 DM 的令一起裁决（每 tick 只一次：幂等闸会拒重复裁决）。
      Map<String, Object> adjView = adjudicateRound(round, roundDirectives);
      roundView.put("adjudication", adjView);

      // ④ 升级规则：前两轮结束时，仍未出「提及四目标」的令 ⇒ 首都人口减半一次 + 文档告知。
      if (round == 2) {
        List<Map<String, Object>> punishViews = new ArrayList<>();
        for (NationFixture nf : nations.values()) {
          if (!nf.qualifiedFirstTwo && !nf.punished && nf.centralRunsOk > 0) {
            punishViews.add(halveCapitalPopulation(nf, round));
          } else if (!nf.qualifiedFirstTwo && !nf.punished && nf.centralRunsOk == 0) {
            System.out.println(
                "[GOV-SCENARIO-PUNISH-SKIP] nation="
                    + nf.key
                    + " round=2 原因=中央 RunDecision 无任一成功轮（疑似 provider/基础设施失败），不把基础设施失败当成模型不作为。");
          }
        }
        roundView.put("punishment", punishViews);
      }

      // ⑤ 轮间 GM 拖 tick（模型每轮必须从工具读当前 tick；同一 tick 不能重复裁决）。
      if (round < ROUNDS) {
        roundView.put("advance", advanceOneTick(round));
      }
      roundViews.add(roundView);
      System.out.println("[GOV-SCENARIO-ROUND] " + json(roundView));
    }

    // ⑥ 确定性补执行：确保四条工具都真跑过一次（场景意图已跑过的类型不重复）。
    ensureAllFourToolsExecutedOnce();

    // ⑦ 汇总矩阵先打印（即使后面红，报告也能拿到逐轮原始账）。
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("rounds", ROUNDS);
    summary.put("nations", nations.values().stream().map(NationFixture::summaryView).toList());
    summary.put("executions", executions);
    summary.put("scopeAttempts", scopeAttempts);
    summary.put("scopeDeniedObserved", scopeDeniedObserved);
    summary.put("scopeViolations", scopeViolations);
    summary.put("blockers", blockers);
    summary.put("conservationFailures", conservationFailures);
    summary.put("executedToolTypes", new ArrayList<>(executedToolTypes));
    summary.put("scenarioToolTypes", new ArrayList<>(scenarioToolTypes));
    summary.put("punishedNations", new ArrayList<>(punishedNations));
    Map<String, Object> criteria = new LinkedHashMap<>();
    criteria.put("centralAnyDirective", centralAnyDirective);
    criteria.put("centralAnyQualified", centralAnyQualified);
    criteria.put("centralAnyOutOfScopeAttempt", centralOutOfScopeAttempt);
    criteria.put("provinceDirective", provinceDirective);
    criteria.put("provinceIntentAction", provinceIntentAction);
    criteria.put("scopeDeniedObserved", scopeDeniedObserved);
    criteria.put("provinceDirectReadOk", provinceDirectReadOk);
    criteria.put("scenarioToolTypesCount", scenarioToolTypes.size());
    criteria.put("allFourToolsRun", executedToolTypes.size() == 4);
    criteria.put("punishedNations", punishedNations.size());
    criteria.put(
        "actedAfterPunishment",
        nations.values().stream()
            .filter(n -> n.punished)
            .map(n -> n.key + "=" + n.actedAfterPunishment)
            .toList());
    summary.put("criteria", criteria);
    summary.put("roundsDetail", roundViews);
    System.out.println("[GOV-SCENARIO-MATRIX] " + json(summary));

    if (centralAnyDirective
        && !centralAnyQualified
        && !punishedNations.isEmpty()
        && nations.values().stream().anyMatch(n -> n.punished && !n.actedAfterPunishment)) {
      System.out.println("[GOV-SCENARIO-MODEL-GAP] 中央出过令但未提及四目标；减半后 1–2 轮内仍未观测到促动。");
    }
    if (!centralOutOfScopeAttempt) {
      System.out.println("[GOV-SCENARIO-MODEL-GAP] 中央未发起可识别的越权读/令探测（范围隔离仍有确定性断言与 GovScope 真值）。");
    }

    // ── 机制侧硬判据 ──────────────────────────────────────────────────────────
    assertThat(blockers).as("不得有 TOOL_ERROR / 未捕获异常 / MCP 调用失败；发现即命中，不重试掩盖").isEmpty();
    assertThat(scopeViolations).as("越权探测若发生必须被拒；不得有越权成功").isEmpty();
    assertThat(conservationFailures).as("GM 代执行的逐值守恒不得失败；明细见上").isEmpty();
    assertThat(executedToolTypes)
        .as("四条 A 工具（科举/调查/吸收/退休）必须都真跑过一次（场景意图或 GM 确定性补执行）")
        .containsExactlyInAnyOrder(TOOL_SELECT, TOOL_DISPATCH, TOOL_ABSORB, TOOL_RETIRE);
    if (ROUNDS < 3) {
      System.out.println(
          "[GOV-SCENARIO-WARN] 本轮是调试冒烟：SIMOS_GOV_SCENARIO_ROUNDS=" + ROUNDS + " < 3。");
    }

    // ── 模型侧验收判据（如实点名：红 = 模型侧不达，不是夹具/机制错）──────────────────
    assertThat(centralAnyDirective).as("模型侧判据：至少一条中央 DM 的真令 revision（不作为 = 模型侧失败）").isTrue();
    assertThat(provinceDirective || provinceIntentAction)
        .as("模型侧判据：至少一个省级 DM 的指令或由其意图触发的行动（模型侧失败）")
        .isTrue();
    assertThat(scenarioToolTypes)
        .as("模型侧判据：场景中由 DM 意图实际执行的 A 工具至少 2 类（其余可 GM 补执行）")
        .hasSizeGreaterThanOrEqualTo(2);
  }

  /**
   * ★ <b>非 LLM 夹具/工具自检</b>（门控 {@code SIMOS_GOV_SCENARIO_FIXTURE_ONLY=1}）：建同款世界与 GOV 决策人，但不跑
   * LLM；直接把四条 GM 组合工具各真跑一次并做逐值守恒。真跑命令：
   *
   * <pre>
   *   SIMOS_REAL_LLM=1 SIMOS_GOV_SCENARIO_FIXTURE_ONLY=1 tools/mvn-lock.sh -q \
   *       -pl simos-app -am -Dtest='RealLlmGovScenarioTest#fixtureOnlySmokeRunsFourGovToolsWithoutLlm' \
   *       -Dsurefire.failIfNoSpecifiedTests=false test
   * </pre>
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "SIMOS_GOV_SCENARIO_FIXTURE_ONLY", matches = "1")
  void fixtureOnlySmokeRunsFourGovToolsWithoutLlm() throws Exception {
    assertFixtureWired();
    assertScopeIsolationDeterministic();
    NationFixture nf = nations.values().iterator().next();
    executeSelect(nf, false, "夹具自检：科举选人", 10);
    executeDispatch(nf, false, "夹具自检：派调查组", 5, false);
    executeAbsorb(nf, false, "夹具自检：吸收人口单位");
    executeRetire(nf, false, "夹具自检：退休回籍", 5, StaffRole.SCRIBE);
    assertThat(conservationFailures).as("夹具自检的逐值守恒").isEmpty();
    assertThat(executions).as("四条工具各跑一次").hasSize(4);
    advanceOneTick(1);
    System.out.println("[GOV-SCENARIO-FIXTURE] ok executions=" + executions.size());
  }

  // ────────────────────────────── 夹具装配 ──────────────────────────────

  private void verifyProviderConfig() throws Exception {
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
    // ★ 允许用 SIMOS_GOV_SCENARIO_MODEL 在 store 副本里换同站模型（上游故障应急）；期望值同步。
    String expectedModel =
        java.util.Optional.ofNullable(System.getenv("SIMOS_GOV_SCENARIO_MODEL"))
            .filter(v -> !v.isBlank())
            .orElse("deepseek-flash");
    assertThat(flash.get("model").asText()).isEqualTo(expectedModel);
    assertThat(flash.get("keyConfigured").asBoolean()).as("密钥必须已配置（不打印值）").isTrue();
    System.out.println(
        "[GOV-SCENARIO-CONFIG] provider="
            + flash.get("id").asText()
            + " valid="
            + flash.get("valid").asBoolean()
            + " model="
            + flash.get("model").asText()
            + " keyConfigured="
            + flash.get("keyConfigured").asBoolean()
            + " echoReasoningContent="
            + flash.path("capabilities").path("echoReasoningContent").asBoolean());
  }

  private void buildWorld() throws Exception {
    List<JsonNode> summaries =
        CompactThreeNationsWorld.initializeNations(
            shell.coreSimos(), EconomySeeder.FoundationProfile.CLASS_FIRST);
    assertThat(summaries).as("三国 worldgen 摘要").hasSize(3);
    SimulationState world = stateAt(head());
    for (int i = 0; i < CompactThreeNationsWorld.NATION_REGIONS.size(); i++) {
      RegionId regionId = CompactThreeNationsWorld.NATION_REGIONS.get(i);
      JsonNode summary = summaries.get(i);
      assertThat(summary.path("nation").asText()).as("worldgen 摘要国家").isEqualTo(regionId.value());
      HexCoord capital = hex(summary.path("capital").path("at"));
      assertThat(capital).as("%s 必须产出首都格", regionId.value()).isNotNull();
      Region region = CompactThreeNationsWorld.mapOf(world).regions().get(regionId);
      assertThat(region).as("地图里有 region %s", regionId.value()).isNotNull();
      HexCoord seat = pickProvinceSeat(world, region, capital);
      String key = "n" + i;
      NationFixture nf =
          new NationFixture(
              key,
              i,
              regionId,
              region,
              capital,
              seat,
              new UnitId("gov-central-" + i),
              new UnitId("gov-province-" + i),
              new DecisionMakerId("dm-gov-central-" + i),
              new DecisionMakerId("dm-gov-province-" + i));
      nations.put(key, nf);
      System.out.println(
          "[GOV-SCENARIO-WORLD] nation="
              + regionId.value()
              + " capital="
              + hexText(capital)
              + " provinceSeat="
              + hexText(seat)
              + " centralGov="
              + nf.centralGov.value()
              + " provinceGov="
              + nf.provinceGov.value());
    }
    for (NationFixture nf : nations.values()) {
      createOffices(nf);
      setProvinceJurisdiction(nf);
      prechargeTreasury(nf);
      putBriefs(nf);
    }
  }

  private void createOffices(NationFixture nf) throws Exception {
    Map<String, Object> central = new LinkedHashMap<>();
    central.put("unitId", nf.centralGov.value());
    central.put("name", "中央 GOV " + nf.regionId.value());
    central.put("q", nf.capital.q());
    central.put("r", nf.capital.r());
    central.put("level", "CENTRAL");
    central.put("staff", Map.of("SCRIBE", 50, "YAMEN", 50, "POST", 50));
    central.put("policy", noUpkeepPolicy(20L));
    central.put("decisionMakerId", nf.centralDm.value());
    central.put("providerId", PROVIDER_ID);
    central.put("reason", "阶段 13C：多级 GOV 真 LLM 场景夹具");
    central.put("preview", false);
    central.put("expectedRevision", head());
    JsonNode centralResult = callToolJson(GovCreateOfficeTool.NAME, central);
    assertThat(centralResult.path("submitted").asBoolean()).isTrue();
    assertThat(centralResult.path("unitId").asText()).isEqualTo(nf.centralGov.value());
    assertThat(centralResult.path("decisionMakerId").asText()).isEqualTo(nf.centralDm.value());

    Map<String, Object> province = new LinkedHashMap<>();
    province.put("unitId", nf.provinceGov.value());
    province.put("name", "省 GOV " + nf.regionId.value());
    province.put("q", nf.provinceSeat.q());
    province.put("r", nf.provinceSeat.r());
    province.put("level", "PROVINCE");
    province.put("superiorGov", nf.centralGov.value());
    province.put("staff", Map.of("SCRIBE", 200, "YAMEN", 200, "POST", 200));
    province.put("policy", noUpkeepPolicy(50L));
    province.put("decisionMakerId", nf.provinceDm.value());
    province.put("providerId", PROVIDER_ID);
    province.put("reason", "阶段 13C：省 GOV（superiorGov=中央）");
    province.put("preview", false);
    province.put("expectedRevision", head());
    JsonNode provinceResult = callToolJson(GovCreateOfficeTool.NAME, province);
    assertThat(provinceResult.path("submitted").asBoolean()).isTrue();
    assertThat(provinceResult.path("superiorGov").asText()).isEqualTo(nf.centralGov.value());
    assertThat(provinceResult.path("decisionMakerId").asText()).isEqualTo(nf.provinceDm.value());
  }

  private void setProvinceJurisdiction(NationFixture nf) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", nf.provinceGov.value());
    payload.put("regions", List.of(nf.regionId.value()));
    payload.put("levyGrainCapPerCommand", 1000L);
    payload.put("levyMoneyCapPerCommand", 1000L);
    payload.put("levyManpowerCapPerCommand", 1000L);
    payload.put("administrationPerMille", 1000L);
    McpToolOutcome outcome =
        callToolOutcome(
            UnitSetJurisdictionTool.NAME,
            Map.of(
                "payloadJson", json(payload), "branch", MAIN.value(), "expectedRevision", head()));
    assertThat(outcome.isError()).as(outcome.raw()).isFalse();
    System.out.println(
        "[GOV-SCENARIO-JURISDICTION] " + nf.provinceGov.value() + " -> " + nf.regionId.value());
  }

  private void prechargeTreasury(NationFixture nf) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    List<Map<String, Object>> entries = new ArrayList<>();
    entries.add(accountEntry(nf.centralGov, nf.capital, 500_000L, 500_000L, 20_000_000L));
    entries.add(accountEntry(nf.provinceGov, nf.provinceSeat, 500_000L, 500_000L, 20_000_000L));
    payload.put("entries", entries);
    McpToolOutcome outcome =
        callToolOutcome(
            ActorAdjustAccountsTool.NAME,
            Map.of(
                "payloadJson", json(payload), "branch", MAIN.value(), "expectedRevision", head()));
    assertThat(outcome.isError()).as(outcome.raw()).isFalse();
  }

  private static Map<String, Object> accountEntry(
      UnitId unitId, HexCoord at, long grain, long cloth, long silver) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("owner", Map.of("kind", ActorKind.UNIT.name(), "id", unitId.value()));
    entry.put("q", at.q());
    entry.put("r", at.r());
    entry.put("goods", Map.of("grain", grain, "cloth", cloth));
    entry.put("money", Map.of("silver", silver));
    return entry;
  }

  private static Map<String, Object> noUpkeepPolicy(long retirementPerStaff) {
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("grainPerStaffPerTick", 0L);
    policy.put("clothPerStaffPerCycle", 0L);
    policy.put("moneyPerStaffPerTick", 0L);
    policy.put("retirementPerStaff", retirementPerStaff);
    return policy;
  }

  private void putBriefs(NationFixture nf) throws Exception {
    writeDoc("central-brief-" + nf.key, nf.centralDm.value(), "第 0 号中央任务简报", centralBrief(nf));
    writeDoc("province-brief-" + nf.key, nf.provinceDm.value(), "第 0 号省级任务简报", provinceBrief(nf));
  }

  private static String centralBrief(NationFixture nf) {
    return "你是「"
        + nf.regionId.value()
        + "」的中央 GOV 决策人（govUnit="
        + nf.centralGov.value()
        + "）。\n"
        + "【本轮测试目的】指挥地方落实四类人员流转：①科举选人送中央（selectExaminees）②派（武装）调查组（dispatchTeam）"
        + "③吸收人口单位入编（absorbUnit）④按政策让老吏退休回籍（retireStaff）。执行由 GM 代跑工具；你只管出令、指定目标与数量。"
        + "若你什么都不做会有后果：前两轮不作为，首都人口将被减半，并写入你的决策文档。\n"
        + "【权限边界】你的直接可达面只有你的首都格与你的 GOV 自身；省级 GOV id="
        + nf.provinceGov.value()
        + " 在 "
        + hexText(nf.provinceSeat)
        + "，不在你的可达面。你可以先调用 simos.unit.get 读它一次：若被系统拒绝，那是『权限≠信息』——省确实存在，"
        + "只是你读不到；不要绕过，继续出令即可。\n"
        + "【你要做什么】先读 simos.command.catalog、sd.DecisionDocs、你可见的 unit/social 读数；然后用 sd.IssueDirective 出令，"
        + "在 intentInfo 里写清：要哪个下属（省 GOV）执行①②③④中的哪些、各多少。commands 可留空或只放你直辖范围内的命令；"
        + "指向省资源的命令会在裁决时因越界被拒（同样不要绕过）。tick 一律填工具读到的当前世界 tick。\n";
  }

  private static String provinceBrief(NationFixture nf) {
    return "你是「"
        + nf.regionId.value()
        + "」的省 GOV 决策人（govUnit="
        + nf.provinceGov.value()
        + "），辖区 = 该 region（你的直辖范围）。\n"
        + "【任务】服从中央调度。GM 会把中央令转达到你的 sd.DecisionDocs；先读 sd.DecisionDocs / simos.command.catalog / 你辖区数据，"
        + "然后用 sd.IssueDirective 出令，在 intentInfo 里写清你要执行的做法与数量（commands 只针对你自己的 GOV 与辖区；"
        + "越界会被拒）。若无法执行，明确写出原因。tick 一律填工具读到的当前世界 tick。\n";
  }

  private void writeCentralOutcomeDoc(
      int round, NationFixture nf, List<DirectiveInfo> directives, List<ExecutionRecord> acted) {
    StringBuilder body = new StringBuilder();
    body.append("第 ").append(round).append(" 轮：GM 已代执行你的令的意图。\n");
    for (DirectiveInfo info : directives) {
      body.append("令 ")
          .append(info.directive().id().value())
          .append(" intent=")
          .append(info.intent())
          .append('\n');
    }
    for (ExecutionRecord record : acted) {
      body.append("GM 代执行 ")
          .append(record.tool())
          .append(" => ")
          .append(record.violations().isEmpty() ? "守恒通过" : record.violations())
          .append('\n');
    }
    writeDoc(
        "central-outcome-" + nf.key + "-r" + round,
        nf.centralDm.value(),
        "GM 代执行回执（第 " + round + " 轮）",
        body.toString());
  }

  private void writeProvinceRelayDoc(
      int round, NationFixture nf, List<DirectiveInfo> directives, List<ExecutionRecord> acted) {
    StringBuilder body = new StringBuilder();
    body.append("GM 转达：中央 GOV ")
        .append(nf.centralGov.value())
        .append(" 第 ")
        .append(round)
        .append(" 轮的令/意图：\n");
    for (DirectiveInfo info : directives) {
      body.append("- ").append(info.intent()).append('\n');
    }
    if (!acted.isEmpty()) {
      body.append("GM 已代执行：\n");
      for (ExecutionRecord record : acted) {
        body.append("- ")
            .append(record.tool())
            .append(" count=")
            .append(record.count())
            .append('\n');
      }
    } else {
      body.append("（GM 本轮未代执行具体工具；你若认为该执行，请在 intentInfo 里写清目标与数量。）\n");
    }
    writeDoc(
        "province-relay-" + nf.key + "-r" + round,
        nf.provinceDm.value(),
        "GM 转达：中央令（第 " + round + " 轮）",
        body.toString());
  }

  private void writeDoc(String docId, String dmId, String title, String body) {
    try {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("title", title);
      value.put("body", body);
      value.put("author", "GM");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("address", DecisionDoc.addressOf(docId));
      payload.put("key", DecisionDoc.KEY);
      payload.put("value", json(value));
      payload.put("note", title);
      payload.put("tags", List.of(dmId));
      McpToolOutcome outcome =
          callToolOutcome(
              SdPutInfoTool.NAME,
              Map.of(
                  "payloadJson",
                  json(payload),
                  "branch",
                  MAIN.value(),
                  "expectedRevision",
                  head()));
      if (outcome.isError()) {
        blockers.add("写决策文档失败 doc=" + docId + " raw=" + truncate(outcome.raw(), 800));
        return;
      }
      System.out.println("[GOV-SCENARIO-DOC] doc=" + docId + " dm=" + dmId + " title=" + title);
    } catch (Exception e) {
      blockers.add("写决策文档异常 doc=" + docId + ": " + e.getMessage());
    }
  }

  // ────────────────────────────── DM 运行与轨迹 ──────────────────────────────

  private DmRun runDecision(int round, NationFixture nf, boolean central) {
    String dmId = central ? nf.centralDm.value() : nf.provinceDm.value();
    long headBefore = head();
    int fromIndex = conversationSize(dmId);
    long start = System.nanoTime();
    McpSchema.CallToolResult result = null;
    String raw = null;
    String error = null;
    try {
      result = callWithApproval(RunDecisionTool.NAME, runDecisionArgs(dmId, headBefore));
      raw = wireText(result);
    } catch (Exception e) {
      error = "sd.RunDecision 调用失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    long headAfter = head();
    List<ToolTrace> traces =
        conversationExists(dmId) ? collectTraces(round, dmId, fromIndex) : List.of();
    JsonNode body = null;
    boolean triggerError = false;
    if (raw != null) {
      try {
        body = result.isError() ? errorBody(result) : JSON.readTree(raw);
      } catch (Exception e) {
        error = error == null ? "触发返回无法解析: " + e.getMessage() : error;
      }
      triggerError = result.isError();
      if (triggerError && error == null) {
        error = "sd.RunDecision 返回 error: " + truncate(raw, 1200);
      }
    }
    List<String> runApprovals = List.copyOf(approvals);
    List<DirectiveInfo> directives = freshDirectiveInfos(headBefore, headAfter, dmId, traces);
    System.out.println(
        "[GOV-SCENARIO-RUN] round="
            + round
            + " dm="
            + dmId
            + " role="
            + (central ? "CENTRAL" : "PROVINCE")
            + " expectedRevision="
            + headBefore
            + " headAfter="
            + headAfter
            + " isError="
            + (result != null && result.isError())
            + " ms="
            + ((System.nanoTime() - start) / 1_000_000L)
            + " raw="
            + truncate(raw, 12_000));
    System.out.println(
        "[GOV-SCENARIO-APPROVAL] round=" + round + " dm=" + dmId + " approvals=" + runApprovals);
    for (ToolTrace trace : traces) {
      if (!trace.ok()
          && trace.result() != null
          && (trace.result().contains("TOOL_ERROR")
              || trace.result().contains("未捕获")
              || trace.result().contains("Exception"))) {
        blockers.add(
            "round "
                + round
                + " dm="
                + dmId
                + " tool="
                + realToolName(trace.tool())
                + " TOOL_ERROR/异常: "
                + truncate(trace.result(), 800));
      }
      System.out.println(
          "[GOV-SCENARIO-TOOL] round="
              + round
              + " dm="
              + dmId
              + " tool="
              + trace.tool()
              + " ok="
              + trace.ok()
              + " args="
              + json(trace.args())
              + " result="
              + truncate(trace.result(), 4000));
    }
    for (DirectiveInfo info : directives) {
      System.out.println(
          "[GOV-SCENARIO-DIRECTIVE] round="
              + round
              + " dm="
              + dmId
              + " id="
              + info.directive().id().value()
              + " tick="
              + info.directive().tick()
              + " status="
              + info.directive().status()
              + " commands="
              + commandTypes(info.directive())
              + " intent="
              + truncate(info.intent(), 3000));
    }

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("dm", dmId);
    view.put("role", central ? "CENTRAL" : "PROVINCE");
    view.put("nation", nf.key);
    view.put("headBefore", headBefore);
    view.put("headAfter", headAfter);
    view.put("isError", result != null && result.isError());
    view.put("triggerError", triggerError);
    view.put("error", error);
    view.put("llmCalls", body == null ? null : body.path("llmCalls").asInt());
    view.put("finalText", body == null ? null : body.path("finalText").asText(null));
    view.put("abortedByBudget", body != null && body.path("abortedByBudget").asBoolean());
    view.put("approvals", runApprovals);
    view.put("directives", directives.stream().map(DirectiveInfo::view).toList());
    view.put("traces", traces.stream().map(ToolTrace::view).toList());

    if (triggerError) {
      blockers.add("round " + round + " dm=" + dmId + " trigger error: " + truncate(raw, 800));
    }
    if (body != null && body.path("abortedByBudget").asBoolean()) {
      blockers.add("round " + round + " dm=" + dmId + " abortedByBudget");
    }
    inspectScopeTraces(round, dmId, central, nf, traces);
    return new DmRun(
        nf,
        central,
        dmId,
        round,
        currentTick(),
        headBefore,
        headAfter,
        body,
        raw,
        triggerError,
        error,
        traces,
        runApprovals,
        directives,
        view);
  }

  private Map<String, Object> runDecisionArgs(String dmId, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", json(Map.of("decisionMakerId", dmId)));
    args.put("branch", MAIN.value());
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  /** 从会话库里取本轮新增消息，逐条打印并合成轨迹（姓名/参数/成败/原始返回）。 */
  private List<ToolTrace> collectTraces(int round, String dmId, int fromIndex) {
    List<LlmMessage> all = conversationMessages(dmId);
    List<LlmMessage> fresh = all.subList(Math.min(fromIndex, all.size()), all.size());
    Map<String, ContentPart.ToolCall> callsById = new LinkedHashMap<>();
    List<ToolTrace> traces = new ArrayList<>();
    for (LlmMessage message : fresh) {
      if (LlmMessage.ROLE_ASSISTANT.equals(message.role())) {
        for (ContentPart part : message.content()) {
          if (part instanceof ContentPart.ToolCall call) {
            callsById.put(call.id(), call);
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
            traces.add(new ToolTrace(round, dmId, tool, args, ok, payload == null ? "" : payload));
          }
        }
      }
    }
    return traces;
  }

  private void inspectScopeTraces(
      int round, String dmId, boolean central, NationFixture nf, List<ToolTrace> traces) {
    ResourceScopeMap scope = scopeOf(central ? nf.centralDm : nf.provinceDm);
    for (ToolTrace trace : traces) {
      String real = realToolName(trace.tool());
      String namespace = null;
      String resource = null;
      if ("simos.unit.get".equals(real)) {
        String id = textArg(trace.args(), "id");
        if (id != null) {
          namespace = "unit";
          resource = ToolSupport.resourceUnit(id).path();
        }
      } else if ("simos.map.hex".equals(real) || "simos.social.population".equals(real)) {
        Integer q = intArg(trace.args(), "q");
        Integer r = intArg(trace.args(), "r");
        if (q != null && r != null) {
          namespace = "simos.map.hex".equals(real) ? "map" : "social";
          resource =
              "simos.map.hex".equals(real)
                  ? ToolSupport.resourceHex(MAP_ID, q, r).path()
                  : ToolSupport.resourceSocial(q, r).path();
        }
      } else if ("simos.map.region".equals(real)) {
        String region = textArg(trace.args(), "regionId");
        if (region != null) {
          namespace = "map";
          resource = ToolSupport.resourceRegion(MAP_ID, region).path();
        }
      }
      if (resource == null || namespace == null) {
        continue;
      }
      boolean allowed =
          scope.declaredScope(namespace) != null && scope.declaredScope(namespace).allows(resource);
      boolean intended = isOutOfScopeProbe(real, trace.args(), nf);
      if (central && intended) {
        centralOutOfScopeAttempt = true;
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("round", round);
        attempt.put("dm", dmId);
        attempt.put("tool", real);
        attempt.put("args", trace.args());
        attempt.put("ok", trace.ok());
        attempt.put("result", truncate(trace.result(), 1200));
        attempt.put("note", "被探测的省资源在夹具中确实存在（assertFixtureWired 已断言）；此处的原始拒因是作用域过滤后的结果（权限≠信息）");
        scopeAttempts.add(attempt);
        if (trace.ok()) {
          scopeViolations.add("中央越权读成功: " + real + " " + json(trace.args()));
        } else {
          scopeDeniedObserved = true;
          System.out.println("[GOV-SCENARIO-SCOPE-DENIED] " + json(attempt));
        }
      } else if (!central && intended && !allowed && !trace.ok()) {
        System.out.println(
            "[GOV-SCENARIO-PROVINCE-RESOURCE-DENIED] " + real + " " + json(trace.args()));
      }
      if (!central && trace.ok() && isReadTool(real)) {
        provinceDirectReadOk = true;
      }
    }
    if (central && !scopeAttempts.isEmpty()) {
      // 已经记录过一次中央越权尝试（矩阵里给全量）。
    }
  }

  private boolean provinceDirectReadOk;

  /** 仅调试用的脚本 LLM（{@code SIMOS_GOV_SCENARIO_FAKE=1}）：验证场景循环本身，真实验收不设它。 */
  private DecisionAgentService.LlmClients fakeLlmClients;

  private boolean isOutOfScopeProbe(String real, Map<String, Object> args, NationFixture nf) {
    return switch (real) {
      case "simos.unit.get" -> !nf.centralGov.value().equals(textArg(args, "id"));
      case "simos.map.hex", "simos.social.population" -> {
        Integer q = intArg(args, "q");
        Integer r = intArg(args, "r");
        yield q != null && r != null && !(q == nf.capital.q() && r == nf.capital.r());
      }
      case "simos.map.region" -> {
        String region = textArg(args, "regionId");
        yield region != null;
      }
      default -> false;
    };
  }

  private static boolean isReadTool(String real) {
    return List.of(
            "simos.unit.get",
            "simos.unit.list",
            "simos.map.hex",
            "simos.map.region",
            "simos.social.population",
            "simos.command.catalog",
            "sd.DecisionDocs",
            "sd.DecisionResults")
        .contains(real);
  }

  // ────────────────────────────── 意图与 GM 代执行 ──────────────────────────────

  private List<ExecutionRecord> processIntentActions(
      int round, NationFixture nf, List<DirectiveInfo> directives, boolean fromCentral) {
    List<ExecutionRecord> acted = new ArrayList<>();
    boolean select = false;
    boolean dispatch = false;
    boolean absorb = false;
    boolean retire = false;
    String merged = "";
    for (DirectiveInfo info : directives) {
      merged = merged + "\n" + info.intent();
      select |= containsAny(info.intent(), SELECT_WORDS);
      dispatch |= containsAny(info.intent(), DISPATCH_WORDS);
      absorb |= containsAny(info.intent(), ABSORB_WORDS);
      retire |= containsAny(info.intent(), RETIRE_WORDS);
    }
    if (-1 == merged.indexOf('\n')) {
      return acted;
    }
    if (select && !nf.selectExecuted) {
      ExecutionRecord record =
          executeSelect(
              nf,
              true,
              "DM 意图代执行：科举选人 round=" + round + " fromCentral=" + fromCentral,
              parseCount(merged, 10));
      if (record != null) {
        acted.add(record);
      }
    }
    if (dispatch && !nf.dispatchExecuted) {
      ExecutionRecord record =
          executeDispatch(
              nf,
              true,
              "DM 意图代执行：调查组 round=" + round + " fromCentral=" + fromCentral,
              parseCount(merged, 5),
              containsAny(merged, List.of("武装", "armed", "army")));
      if (record != null) {
        acted.add(record);
      }
    }
    if (absorb && !nf.absorbExecuted) {
      ExecutionRecord record =
          executeAbsorb(nf, true, "DM 意图代执行：吸收人口单位 round=" + round + " fromCentral=" + fromCentral);
      if (record != null) {
        acted.add(record);
      }
    }
    if (retire && !nf.retireExecuted) {
      ExecutionRecord record =
          executeRetire(
              nf,
              true,
              "DM 意图代执行：退休回籍 round=" + round + " fromCentral=" + fromCentral,
              parseCount(merged, 5),
              parseRole(merged));
      if (record != null) {
        acted.add(record);
      }
    }
    return acted;
  }

  private ExecutionRecord executeSelect(
      NationFixture nf, boolean scenarioTriggered, String reason, long requestedCount) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", nf.provinceGov.value());
    args.put("count", requestedCount);
    args.put("targetGovUnitId", nf.centralGov.value());
    args.put("reason", reason);
    args.put("preview", true);
    JsonNode preview = previewArgs(TOOL_SELECT, args);
    if (preview == null || preview.path("available").asLong() < requestedCount) {
      String gap =
          "selectExaminees preview 不足：requested="
              + requestedCount
              + " available="
              + (preview == null ? "N/A" : preview.path("available").asLong());
      recordFailure(TOOL_SELECT, nf.key, scenarioTriggered, gap);
      return null;
    }
    long count = preview.path("count").asLong(requestedCount);
    long headBefore = head();
    SimulationState before = stateAt(headBefore);
    long regionBefore = socialTotalInRegion(before, nf.region);
    JsonNode apply = applyArgs(TOOL_SELECT, args, headBefore);
    if (apply == null) {
      return null;
    }
    long headAfter = head();
    SimulationState after = stateAt(headAfter);
    long regionAfter = socialTotalInRegion(after, nf.region);
    String newUnitId = apply.path("newUnitId").asText(preview.path("newUnitId").asText(""));
    long member = unitMember(after, newUnitId);
    List<String> violations = new ArrayList<>();
    if (regionBefore - regionAfter != count) {
      violations.add("来源扣人=" + (regionBefore - regionAfter) + " != count=" + count);
    }
    if (member != count) {
      violations.add("新单位 member=" + member + " != count=" + count);
    }
    ExecutionRecord record =
        new ExecutionRecord(
            TOOL_SELECT,
            nf.key,
            scenarioTriggered,
            count,
            headBefore,
            headAfter,
            preview,
            apply,
            violations);
    finishExecution(record);
    nf.selectExecuted = true;
    nf.scenarioSelect |= scenarioTriggered;
    nf.examUnitId = newUnitId;
    return record;
  }

  private ExecutionRecord executeDispatch(
      NationFixture nf,
      boolean scenarioTriggered,
      String reason,
      long requestedCount,
      boolean armed) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", nf.provinceGov.value());
    args.put("count", requestedCount);
    args.put("role", "SCRIBE");
    args.put("armed", armed);
    args.put("reason", reason);
    args.put("preview", true);
    JsonNode preview = previewArgs(TOOL_DISPATCH, args);
    if (preview == null || preview.path("staffBefore").asLong() < requestedCount) {
      recordFailure(
          TOOL_DISPATCH,
          nf.key,
          scenarioTriggered,
          "dispatchTeam preview 缺员：requested="
              + requestedCount
              + " staffBefore="
              + (preview == null ? "N/A" : preview.path("staffBefore").asLong()));
      return null;
    }
    long count = preview.path("count").asLong(requestedCount);
    long headBefore = head();
    SimulationState before = stateAt(headBefore);
    long staffBefore = govStaff(before, nf.provinceGov, StaffRole.SCRIBE);
    JsonNode apply = applyArgs(TOOL_DISPATCH, args, headBefore);
    if (apply == null) {
      return null;
    }
    long headAfter = head();
    SimulationState after = stateAt(headAfter);
    long staffAfter = govStaff(after, nf.provinceGov, StaffRole.SCRIBE);
    String newUnitId = apply.path("newUnitId").asText(preview.path("newUnitId").asText(""));
    long member = unitMember(after, newUnitId);
    List<String> violations = new ArrayList<>();
    if (staffBefore - staffAfter != count) {
      violations.add("roster 减少=" + (staffBefore - staffAfter) + " != count=" + count);
    }
    if (member != count) {
      violations.add("新单位 member=" + member + " != count=" + count);
    }
    ExecutionRecord record =
        new ExecutionRecord(
            TOOL_DISPATCH,
            nf.key,
            scenarioTriggered,
            count,
            headBefore,
            headAfter,
            preview,
            apply,
            violations);
    finishExecution(record);
    nf.dispatchExecuted = true;
    nf.scenarioDispatch |= scenarioTriggered;
    nf.teamUnitId = newUnitId;
    return record;
  }

  private ExecutionRecord executeAbsorb(
      NationFixture nf, boolean scenarioTriggered, String reason) {
    String source = pickAbsorbSource(nf);
    if (source == null) {
      recordFailure(TOOL_ABSORB, nf.key, scenarioTriggered, "没有可吸收的纯人员单位（需先 select/dispatch）");
      return null;
    }
    long sourceMember = unitMember(stateAt(head()), source);
    if (sourceMember <= 0L) {
      recordFailure(TOOL_ABSORB, nf.key, scenarioTriggered, "源单位 " + source + " 已空");
      return null;
    }
    long count = Math.min(5L, sourceMember);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", nf.centralGov.value());
    args.put("role", "SCRIBE");
    args.put("sourceUnitId", source);
    args.put("count", count);
    args.put("disbandSource", true);
    args.put("reason", reason);
    args.put("preview", true);
    JsonNode preview = previewArgs(TOOL_ABSORB, args);
    if (preview == null) {
      return null;
    }
    long headBefore = head();
    SimulationState before = stateAt(headBefore);
    long sourceBefore = unitMember(before, source);
    long staffBefore = govStaff(before, nf.centralGov, StaffRole.SCRIBE);
    JsonNode apply = applyArgs(TOOL_ABSORB, args, headBefore);
    if (apply == null) {
      return null;
    }
    long headAfter = head();
    SimulationState after = stateAt(headAfter);
    long sourceAfter = unitMember(after, source);
    long staffAfter = govStaff(after, nf.centralGov, StaffRole.SCRIBE);
    List<String> violations = new ArrayList<>();
    if (sourceBefore - sourceAfter != count) {
      violations.add("源 member 减少=" + (sourceBefore - sourceAfter) + " != count=" + count);
    }
    if (staffAfter - staffBefore != count) {
      violations.add("吸收方 roster 增加=" + (staffAfter - staffBefore) + " != count=" + count);
    }
    ExecutionRecord record =
        new ExecutionRecord(
            TOOL_ABSORB,
            nf.key,
            scenarioTriggered,
            count,
            headBefore,
            headAfter,
            preview,
            apply,
            violations);
    finishExecution(record);
    nf.absorbExecuted = true;
    nf.scenarioAbsorb |= scenarioTriggered;
    if (sourceMember - count <= 0L) {
      if (source.equals(nf.examUnitId)) {
        nf.examUnitId = null;
      }
      if (source.equals(nf.teamUnitId)) {
        nf.teamUnitId = null;
      }
    }
    return record;
  }

  private String pickAbsorbSource(NationFixture nf) {
    SimulationState state = stateAt(head());
    for (String candidate : List.of(nf.examUnitId, nf.teamUnitId)) {
      if (candidate != null && unitMember(state, candidate) > 0L) {
        return candidate;
      }
    }
    return null;
  }

  private ExecutionRecord executeRetire(
      NationFixture nf,
      boolean scenarioTriggered,
      String reason,
      long requestedCount,
      StaffRole role) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", nf.provinceGov.value());
    args.put("role", role.name());
    args.put("count", requestedCount);
    args.put("reinsertQ", nf.provinceSeat.q());
    args.put("reinsertR", nf.provinceSeat.r());
    args.put("reason", reason);
    args.put("preview", true);
    JsonNode preview = previewArgs(TOOL_RETIRE, args);
    if (preview == null || preview.path("staffBefore").asLong() < requestedCount) {
      recordFailure(
          TOOL_RETIRE,
          nf.key,
          scenarioTriggered,
          "retireStaff preview 缺员：requested="
              + requestedCount
              + " staffBefore="
              + (preview == null ? "N/A" : preview.path("staffBefore").asLong()));
      return null;
    }
    long count = preview.path("count").asLong(requestedCount);
    long payment = preview.path("payment").asLong();
    long headBefore = head();
    SimulationState before = stateAt(headBefore);
    long staffBefore = govStaff(before, nf.provinceGov, role);
    long silverBefore = treasurySilver(before, nf.provinceGov);
    long hexBefore = socialTotalAt(before, nf.provinceSeat);
    JsonNode apply = applyArgs(TOOL_RETIRE, args, headBefore);
    if (apply == null) {
      return null;
    }
    long headAfter = head();
    SimulationState after = stateAt(headAfter);
    long staffAfter = govStaff(after, nf.provinceGov, role);
    long silverAfter = treasurySilver(after, nf.provinceGov);
    long hexAfter = socialTotalAt(after, nf.provinceSeat);
    long policy = preview.path("retirementPerStaff").asLong();
    List<String> violations = new ArrayList<>();
    if (staffBefore - staffAfter != count) {
      violations.add("roster 减少=" + (staffBefore - staffAfter) + " != count=" + count);
    }
    if (policy * count != payment) {
      violations.add("policy×count=" + (policy * count) + " != payment=" + payment);
    }
    if (silverBefore - silverAfter != payment) {
      violations.add("国库银减少=" + (silverBefore - silverAfter) + " != payment=" + payment);
    }
    if (hexAfter - hexBefore != count) {
      violations.add("回写格人口增加=" + (hexAfter - hexBefore) + " != count=" + count);
    }
    ExecutionRecord record =
        new ExecutionRecord(
            TOOL_RETIRE,
            nf.key,
            scenarioTriggered,
            count,
            headBefore,
            headAfter,
            preview,
            apply,
            violations);
    finishExecution(record);
    nf.retireExecuted = true;
    nf.scenarioRetire |= scenarioTriggered;
    return record;
  }

  private JsonNode previewArgs(String tool, Map<String, Object> args) {
    JsonNode preview = applyOrPreview(tool, args, true, null);
    if (preview == null) {
      return null;
    }
    System.out.println(
        "[GOV-SCENARIO-GM-EXEC] phase=preview tool="
            + tool
            + " args="
            + json(args)
            + " result="
            + json(preview));
    return preview;
  }

  private JsonNode applyArgs(String tool, Map<String, Object> args, long expectedRevision) {
    return applyOrPreview(tool, args, false, expectedRevision);
  }

  private JsonNode applyOrPreview(
      String tool, Map<String, Object> args, boolean preview, Long expectedRevision) {
    Map<String, Object> callArgs = new LinkedHashMap<>(args);
    callArgs.put("preview", preview);
    if (!preview) {
      callArgs.put("expectedRevision", expectedRevision == null ? head() : expectedRevision);
    }
    McpToolOutcome outcome;
    try {
      outcome = callToolOutcome(tool, callArgs);
    } catch (Exception e) {
      blockers.add("GM 工具 " + tool + " 调用异常: " + e.getMessage());
      return null;
    }
    if (outcome.isError()) {
      System.out.println(
          "[GOV-SCENARIO-GM-EXEC] phase="
              + (preview ? "preview" : "apply")
              + " tool="
              + tool
              + " REJECTED raw="
              + truncate(outcome.raw(), 3000));
      recordFailure(
          tool,
          String.valueOf(args.getOrDefault("unitId", "?")),
          false,
          "工具返回错误: " + truncate(outcome.raw(), 1500));
      return null;
    }
    JsonNode body = outcome.body();
    if (!preview) {
      System.out.println(
          "[GOV-SCENARIO-GM-EXEC] phase=apply tool="
              + tool
              + " args="
              + json(args)
              + " result="
              + json(body));
    }
    return body;
  }

  private void finishExecution(ExecutionRecord record) {
    executions.add(record.view());
    if (record.violations().isEmpty()) {
      executedToolTypes.add(record.tool());
      if (record.scenarioTriggered()) {
        scenarioToolTypes.add(record.tool());
      }
    } else {
      conservationFailures.add(
          record.tool() + " nation=" + record.nationKey() + " " + record.violations());
    }
  }

  private void recordFailure(
      String tool, String nationKey, boolean scenarioTriggered, String detail) {
    System.out.println(
        "[GOV-SCENARIO-GM-EXEC] tool=" + tool + " nation=" + nationKey + " failure=" + detail);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tool", tool);
    view.put("nation", nationKey);
    view.put("scenarioTriggered", scenarioTriggered);
    view.put("failure", detail);
    executions.add(view);
  }

  private boolean hasExecuted(String tool) {
    return executedToolTypes.contains(tool);
  }

  private void ensureAllFourToolsExecutedOnce() {
    NationFixture nf = nations.values().iterator().next();
    System.out.println(
        "[GOV-SCENARIO-ENSURE] executed=" + executedToolTypes + " scenario=" + scenarioToolTypes);
    if (!hasExecuted(TOOL_SELECT)) {
      executeSelect(nf, false, "GM 确定性补执行：确保科举工具真跑一次", 10);
    }
    if (!hasExecuted(TOOL_DISPATCH)) {
      executeDispatch(nf, false, "GM 确定性补执行：确保调查组工具真跑一次", 5, false);
    }
    if (!hasExecuted(TOOL_ABSORB)) {
      if (pickAbsorbSource(nf) == null) {
        if (!nf.selectExecuted) {
          executeSelect(nf, false, "GM 补执行吸收前先造源：科举", 10);
        }
        if (pickAbsorbSource(nf) == null) {
          executeDispatch(nf, false, "GM 补执行吸收前先造源：调查组", 5, false);
        }
      }
      executeAbsorb(nf, false, "GM 确定性补执行：确保吸收工具真跑一次");
    }
    if (!hasExecuted(TOOL_RETIRE)) {
      executeRetire(nf, false, "GM 确定性补执行：确保退休工具真跑一次", 5, StaffRole.SCRIBE);
    }
  }

  // ────────────────────────────── 裁决 / 升级 / 推进 ──────────────────────────────

  private Map<String, Object> adjudicateRound(int round, List<DirectiveInfo> directives) {
    Set<Long> ticks = new LinkedHashSet<>();
    for (DirectiveInfo info : directives) {
      ticks.add(info.directive().tick());
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("ticks", new ArrayList<>(ticks));
    List<Map<String, Object>> outcomes = new ArrayList<>();
    for (long tick : ticks) {
      if (!adjudicatedTicks.add(tick)) {
        outcomes.add(Map.of("tick", tick, "skipped", "该 tick 已裁决过（幂等闸）"));
        continue;
      }
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("branch", MAIN.value());
      args.put("expectedRevision", head());
      args.put("tick", tick);
      McpToolOutcome outcome;
      try {
        outcome = callToolOutcome(AdjudicateTickTool.NAME, args);
      } catch (Exception e) {
        blockers.add("round " + round + " AdjudicateTick tick=" + tick + " 异常: " + e.getMessage());
        continue;
      }
      System.out.println(
          "[GOV-SCENARIO-ADJUDICATE] round="
              + round
              + " tick="
              + tick
              + " isError="
              + outcome.isError()
              + " raw="
              + truncate(outcome.raw(), 8000));
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("tick", tick);
      entry.put("isError", outcome.isError());
      entry.put("raw", truncate(outcome.raw(), 8000));
      if (!outcome.isError() && outcome.body() != null) {
        JsonNode commands = outcome.body().get("commands");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (commands != null && commands.isArray()) {
          for (JsonNode row : commands) {
            Map<String, Object> rowView = new LinkedHashMap<>();
            String directiveId = row.path("directiveId").asText(null);
            String type = row.path("type").asText(null);
            String result = row.path("result").asText(null);
            String reason = row.path("reason").asText(null);
            rowView.put("directiveId", directiveId);
            rowView.put("type", type);
            rowView.put("result", result);
            rowView.put("reason", reason);
            rows.add(rowView);
            DirectiveInfo source = findDirective(directives, directiveId);
            if ("rejected".equals(result) && reason != null && reason.contains("超出出令决策人")) {
              scopeDeniedObserved = true;
              Map<String, Object> attempt = new LinkedHashMap<>();
              attempt.put("round", round);
              attempt.put("tick", tick);
              attempt.put("directiveId", directiveId);
              attempt.put("type", type);
              attempt.put("reason", reason);
              scopeAttempts.add(attempt);
              System.out.println("[GOV-SCENARIO-SCOPE-DENIED] " + json(attempt));
            }
            if ("applied".equals(result)
                && source != null
                && centralCommandOutsideScope(source, type)) {
              scopeViolations.add(
                  "中央令越权却被 applied: type="
                      + type
                      + " directive="
                      + directiveId
                      + " dm="
                      + source.directive().decisionMakerId().value());
            }
          }
        }
        entry.put("commands", rows);
      }
      outcomes.add(entry);
    }
    view.put("outcomes", outcomes);
    return view;
  }

  private DirectiveInfo findDirective(List<DirectiveInfo> directives, String id) {
    if (id == null) {
      return null;
    }
    for (DirectiveInfo info : directives) {
      if (info.directive().id().value().equals(id)) {
        return info;
      }
    }
    return null;
  }

  private NationFixture nationOfDm(String dmId) {
    for (NationFixture nation : nations.values()) {
      if (nation.centralDm.value().equals(dmId) || nation.provinceDm.value().equals(dmId)) {
        return nation;
      }
    }
    return null;
  }

  private boolean centralCommandOutsideScope(DirectiveInfo info, String commandType) {
    NationFixture nf = nationOfDm(info.directive().decisionMakerId().value());
    if (nf == null || !nf.centralDm.equals(info.directive().decisionMakerId())) {
      return false;
    }
    for (DirectiveCommand command : info.directive().commands()) {
      if (!command.type().equals(commandType)) {
        continue;
      }
      try {
        JsonNode payload = JSON.readTree(command.payloadJson());
        if (command.type().startsWith("map.")) {
          return true; // 中央无 region 管辖 ⇒ 任何 map 区域命令都应在裁决时被拒。
        }
        if (command.type().startsWith("unit.")) {
          String unitId = payload.path("unitId").asText(null);
          if (unitId != null && !unitId.equals(nf.centralGov.value())) {
            return true;
          }
          String source = payload.path("sourceUnitId").asText(null);
          if (source != null && !source.equals(nf.centralGov.value())) {
            return true;
          }
        }
        if (command.type().equals("social.SeedGroups")) {
          JsonNode entries = payload.get("entries");
          if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
              if (entry.path("q").asInt(Integer.MIN_VALUE) != nf.capital.q()
                  || entry.path("r").asInt(Integer.MIN_VALUE) != nf.capital.r()) {
                return true;
              }
            }
          }
        }
      } catch (JsonProcessingException ignored) {
        // 非法载荷在裁决时另有拒因；这里不重复判。
      }
    }
    return false;
  }

  private Map<String, Object> halveCapitalPopulation(NationFixture nf, int round) throws Exception {
    long headBefore = head();
    SimulationState before = stateAt(headBefore);
    List<PopulationGroup> groups =
        CompactThreeNationsWorld.socialOf(before).groups().values().stream()
            .filter(group -> group.residence().equals(nf.capital))
            .toList();
    assertThat(groups).as("%s 首都格必须有批次", nf.key).isNotEmpty();
    Map<String, Object> payload = new LinkedHashMap<>();
    List<Map<String, Object>> entries = new ArrayList<>();
    for (PopulationGroup group : groups) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", group.id().value());
      entry.put("q", group.residence().q());
      entry.put("r", group.residence().r());
      entry.put("sex", group.sex().name());
      entry.put("count", group.count() / 2L);
      entry.put("ageDays", group.ageAtAnchorDays());
      entry.put("anchorTick", group.anchorTick());
      entry.put("stress", group.physiologicalStress());
      entries.add(entry);
    }
    payload.put("entries", entries);
    McpToolOutcome outcome =
        callToolOutcome(
            "simos.command.submit",
            Map.of(
                "type",
                "social.SeedGroups",
                "payloadJson",
                json(payload),
                "branch",
                MAIN.value(),
                "expectedRevision",
                headBefore));
    assertThat(outcome.isError()).as("首都减半 social.SeedGroups 必须成功: " + outcome.raw()).isFalse();
    long headAfter = head();
    SimulationState after = stateAt(headAfter);
    for (PopulationGroup group : groups) {
      PopulationGroup now = CompactThreeNationsWorld.socialOf(after).groups().get(group.id());
      assertThat(now).as("减半后批次 %s 必须还在（0 也保留）", group.id().value()).isNotNull();
      assertThat(now.count())
          .as("%s 减半逐批：原值/2 向下取整", group.id().value())
          .isEqualTo(group.count() / 2L);
    }
    nf.punished = true;
    nf.punishTick = currentTick();
    punishedNations.add(nf.key);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("nation", nf.key);
    view.put("region", nf.regionId.value());
    view.put("capital", hexText(nf.capital));
    view.put("round", round);
    view.put("headBefore", headBefore);
    view.put("headAfter", headAfter);
    view.put("groups", groups.size());
    view.put("countsBefore", groups.stream().map(PopulationGroup::count).toList());
    view.put(
        "countsAfter",
        groups.stream()
            .map(g -> CompactThreeNationsWorld.socialOf(after).groups().get(g.id()).count())
            .toList());
    writeDoc(
        "punish-" + nf.key + "-r" + round,
        nf.centralDm.value(),
        "首都人口已减半——不作为的代价",
        "第 "
            + round
            + " 轮结束时，中央 GOV "
            + nf.centralGov.value()
            + " 仍未出令提及四目标。GM 已把首都 "
            + hexText(nf.capital)
            + " 的全部人口批次减半（向下取整，0 保留）。请立即出令指挥地方落实四类人员流转。");
    System.out.println("[GOV-SCENARIO-PUNISH] " + json(view));
    return view;
  }

  private Map<String, Object> advanceOneTick(int round) {
    long from = currentTick();
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", MAIN.value());
    args.put("expectedRevision", head());
    args.put("from", from);
    args.put("to", from + 1);
    McpToolOutcome outcome = null;
    try {
      outcome = callToolOutcome("simos.advance", args);
    } catch (Exception e) {
      blockers.add("round " + round + " advance 异常: " + e.getMessage());
      return Map.of("error", e.getMessage());
    }
    if (outcome.isError()) {
      blockers.add("round " + round + " advance 失败: " + truncate(outcome.raw(), 1200));
    } else {
      System.out.println(
          "[GOV-SCENARIO-ADVANCE] round=" + round + " from=" + from + " to=" + (from + 1));
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("from", from);
    view.put("to", from + 1);
    view.put("isError", outcome.isError());
    view.put("raw", truncate(outcome.raw(), 1500));
    return view;
  }

  // ────────────────────────────── 预检与确定性范围断言 ──────────────────────────────

  private void assertFixtureWired() {
    SimulationState state = stateAt(head());
    for (NationFixture nf : nations.values()) {
      UnitState units = CompactThreeNationsWorld.unitOf(state);
      Unit central = units.units().get(nf.centralGov);
      Unit province = units.units().get(nf.provinceGov);
      assertThat(central).as("中央 GOV %s 存在", nf.centralGov.value()).isNotNull();
      assertThat(province).as("省 GOV %s 存在", nf.provinceGov.value()).isNotNull();
      assertThat(central.module().orElse(null))
          .as("中央挂 GovFormation")
          .isInstanceOf(GovFormation.class);
      assertThat(province.module().orElse(null))
          .as("省挂 GovFormation")
          .isInstanceOf(GovFormation.class);
      GovFormation pf = (GovFormation) province.module().orElseThrow();
      assertThat(pf.superiorGov()).contains(nf.centralGov);
      assertThat(province.jurisdiction()).isPresent();
      assertThat(province.jurisdiction().orElseThrow().taxRatePerMilleByRegion())
          .containsKey(nf.regionId);
      DecisionMaker centralDm = sdAt(head()).decisionMakers().get(nf.centralDm);
      DecisionMaker provinceDm = sdAt(head()).decisionMakers().get(nf.provinceDm);
      assertThat(centralDm).isNotNull();
      assertThat(provinceDm).isNotNull();
      assertThat(centralDm.affiliation())
          .isInstanceOf(io.mosire.simos.sd.model.Affiliation.Gov.class);
      assertThat(provinceDm.affiliation())
          .isInstanceOf(io.mosire.simos.sd.model.Affiliation.Gov.class);
      assertThat(centralDm.providerId()).contains(PROVIDER_ID);
      assertThat(provinceDm.providerId()).contains(PROVIDER_ID);
      assertThat(treasurySilver(state, nf.centralGov)).as("中央国库预充银").isPositive();
      assertThat(treasurySilver(state, nf.provinceGov)).as("省国库预充银").isPositive();
      assertThat(socialTotalAt(state, nf.capital)).as("首都有人口批次").isPositive();
      assertThat(socialTotalAt(state, nf.provinceSeat)).as("省会格有可回写批次").isPositive();
    }
    System.out.println("[GOV-SCENARIO-FIXTURE-SELF-CHECK] nations=" + nations.size());
  }

  private void assertScopeIsolationDeterministic() {
    SimulationState state = stateAt(head());
    DecisionScopeFunctions scopeFunctions = DecisionScopeFunctions.defaults();
    for (NationFixture nf : nations.values()) {
      DecisionMaker centralDm = sdAt(head()).decisionMakers().get(nf.centralDm);
      DecisionMaker provinceDm = sdAt(head()).decisionMakers().get(nf.provinceDm);
      ResourceScopeMap centralScope =
          DecisionCallerFactory.resourceScopesFor(scopeFunctions, centralDm, state, MAP_ID);
      ResourceScopeMap provinceScope =
          DecisionCallerFactory.resourceScopesFor(scopeFunctions, provinceDm, state, MAP_ID);
      assertThat(
              centralScope
                  .declaredScope("unit")
                  .allows(ToolSupport.resourceUnit(nf.provinceGov.value()).path()))
          .as("中央不能读/命令省 GOV %s（权限≠信息）", nf.provinceGov.value())
          .isFalse();
      assertThat(
              centralScope
                  .declaredScope("social")
                  .allows(
                      ToolSupport.resourceSocial(nf.provinceSeat.q(), nf.provinceSeat.r()).path()))
          .as("中央不能读省会格 %s", hexText(nf.provinceSeat))
          .isFalse();
      assertThat(
              provinceScope
                  .declaredScope("unit")
                  .allows(ToolSupport.resourceUnit(nf.provinceGov.value()).path()))
          .as("省能读/命令自己的 GOV")
          .isTrue();
      assertThat(
              provinceScope
                  .declaredScope("social")
                  .allows(
                      ToolSupport.resourceSocial(nf.provinceSeat.q(), nf.provinceSeat.r()).path()))
          .as("省能读自己的辖区格")
          .isTrue();
      assertThat(
              provinceScope
                  .declaredScope("map")
                  .allows(ToolSupport.resourceRegion(MAP_ID, nf.regionId.value()).path()))
          .as("省能读自己的 region")
          .isTrue();
    }
    System.out.println("[GOV-SCENARIO-SCOPE] deterministic isolation verified");
  }

  // ────────────────────────────── 状态与数值读回 ──────────────────────────────

  private long head() {
    return shell.coreSimos().head(MAIN).orElseThrow().value();
  }

  private SimulationState stateAt(long revision) {
    return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(revision)));
  }

  private SdState sdAt(long revision) {
    return ((SdSnapshot) stateAt(revision).module("sd").orElseThrow()).state();
  }

  private ResourceScopeMap scopeOf(DecisionMakerId dmId) {
    DecisionMaker dm = sdAt(head()).decisionMakers().get(dmId);
    assertThat(dm).as("决策人 %s 存在", dmId.value()).isNotNull();
    return DecisionCallerFactory.resourceScopesFor(
        DecisionScopeFunctions.defaults(), dm, stateAt(head()), MAP_ID);
  }

  private long currentTick() {
    return stateAt(head()).meta().timestamp().tick();
  }

  private String conversationId(String dmId) {
    DecisionMaker dm = sdAt(head()).decisionMakers().get(new DecisionMakerId(dmId));
    long generation = dm == null ? 0L : dm.conversationGeneration();
    return DecisionAgentRunner.conversationIdOf(new DecisionMakerId(dmId), generation);
  }

  private List<LlmMessage> conversationMessages(String dmId) {
    try (SqliteConversationStore conversations =
        SqliteConversationStore.open(
            storeDir.resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME))) {
      return conversations.load(conversationId(dmId));
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private int conversationSize(String dmId) {
    return conversationMessages(dmId).size();
  }

  private boolean conversationExists(String dmId) {
    return true;
  }

  private List<DirectiveInfo> freshDirectiveInfos(
      long headBefore, long headAfter, String dmId, List<ToolTrace> traces) {
    SdState before = sdAt(headBefore);
    SdState after = sdAt(headAfter);
    Map<String, String> intents = new LinkedHashMap<>();
    for (ToolTrace trace : traces) {
      if (!IssueDirectiveTool.NAME.equals(realToolName(trace.tool()))) {
        continue;
      }
      String payload = textArg(trace.args(), "payloadJson");
      if (payload == null) {
        continue;
      }
      try {
        JsonNode node = JSON.readTree(payload);
        intents.put(node.path("directiveId").asText(), node.path("intentInfo").asText(""));
      } catch (JsonProcessingException ignored) {
        // 非法载荷不会落令；这里只做诊断。
      }
    }
    List<DirectiveInfo> out = new ArrayList<>();
    for (Directive directive : after.directives().values()) {
      if (!directive.decisionMakerId().value().equals(dmId)
          || before.directives().containsKey(directive.id())) {
        continue;
      }
      String intent = stateIntent(after, directive);
      if (intent == null || intent.isBlank()) {
        intent = intents.getOrDefault(directive.id().value(), "");
      }
      out.add(new DirectiveInfo(directive, intent));
    }
    return out;
  }

  private static String stateIntent(SdState sd, Directive directive) {
    List<SdInfoEntry> entries = sd.info().get("sd:directive." + directive.id().value());
    if (entries == null) {
      return "";
    }
    for (SdInfoEntry entry : entries) {
      if (IssueDirectiveHandler.INTENT_INFO_KEY.equals(entry.key())
          && entry.value() instanceof String text) {
        return text;
      }
    }
    return "";
  }

  private static List<String> commandTypes(Directive directive) {
    List<String> out = new ArrayList<>();
    for (DirectiveCommand command : directive.commands()) {
      out.add(command.type());
    }
    return out;
  }

  private static boolean mentionsGoal(String intent) {
    return containsAny(intent, ALL_GOAL_WORDS);
  }

  private static boolean containsAny(String text, List<String> words) {
    if (text == null) {
      return false;
    }
    String lower = text.toLowerCase();
    for (String word : words) {
      if (lower.contains(word.toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  private static long parseCount(String text, long fallback) {
    if (text == null) {
      return fallback;
    }
    Matcher matcher = NUMBER.matcher(text);
    if (matcher.find()) {
      try {
        long value = Long.parseLong(matcher.group(1));
        return value >= 1 ? value : fallback;
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static StaffRole parseRole(String text) {
    if (containsAny(text, List.of("驿传", "POST", "post"))) {
      return StaffRole.POST;
    }
    if (containsAny(text, List.of("衙门", "YAMEN", "yamen", "治安"))) {
      return StaffRole.YAMEN;
    }
    return StaffRole.SCRIBE;
  }

  private static long socialTotalInRegion(SimulationState state, Region region) {
    long total = 0L;
    for (PopulationGroup group : CompactThreeNationsWorld.socialOf(state).groups().values()) {
      if (region.hexes().contains(group.residence())) {
        total += group.count();
      }
    }
    return total;
  }

  private static long socialTotalAt(SimulationState state, HexCoord hex) {
    long total = 0L;
    for (PopulationGroup group : CompactThreeNationsWorld.socialOf(state).groups().values()) {
      if (group.residence().equals(hex)) {
        total += group.count();
      }
    }
    return total;
  }

  private static long unitMember(SimulationState state, String unitId) {
    if (unitId == null || unitId.isBlank()) {
      return 0L;
    }
    Unit unit = CompactThreeNationsWorld.unitOf(state).units().get(new UnitId(unitId));
    return unit == null ? 0L : unit.manpower().stream().mapToLong(CompositionEntry::amount).sum();
  }

  private static long govStaff(SimulationState state, UnitId govUnit, StaffRole role) {
    Unit unit = CompactThreeNationsWorld.unitOf(state).units().get(govUnit);
    if (unit == null || !(unit.module().orElse(null) instanceof GovFormation formation)) {
      return -1L;
    }
    return formation.staff().getOrDefault(role, 0L);
  }

  private static long treasurySilver(SimulationState state, UnitId govUnit) {
    Unit unit = CompactThreeNationsWorld.unitOf(state).units().get(govUnit);
    if (unit == null) {
      return 0L;
    }
    Optional<HexCoord> at =
        CompactThreeNationsWorld.unitOf(state).effectivePosition(govUnit, state.meta().timestamp());
    if (at.isEmpty()) {
      return 0L;
    }
    GoodsAccountKey key =
        new GoodsAccountKey(new ActorRef(ActorKind.UNIT, govUnit.value()), at.get());
    GoodsAccount account = CompactThreeNationsWorld.actorOf(state).accounts().get(key);
    return account == null ? 0L : account.money().getOrDefault(SILVER, 0L);
  }

  private static HexCoord hex(JsonNode node) {
    if (node == null || !node.isObject() || node.get("q") == null || node.get("r") == null) {
      return null;
    }
    return new HexCoord(node.get("q").asInt(), node.get("r").asInt());
  }

  private static HexCoord pickProvinceSeat(SimulationState state, Region region, HexCoord capital) {
    SocialData social = CompactThreeNationsWorld.socialOf(state);
    return region.hexes().stream()
        .filter(hex -> !hex.equals(capital))
        .filter(hex -> social.populations().containsKey(hex))
        .filter(
            hex ->
                social.groups().values().stream()
                    .anyMatch(group -> group.residence().equals(hex) && group.count() > 0L))
        .sorted(
            Comparator.comparingInt((HexCoord hex) -> hex.distanceTo(capital))
                .thenComparingInt(HexCoord::q)
                .thenComparingInt(HexCoord::r))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("找不到可作省会的格: " + region.id().value()));
  }

  private static String hexText(HexCoord hex) {
    return "(" + hex.q() + "," + hex.r() + ")";
  }

  // ────────────────────────────── MCP / 审批 / 通用小件 ──────────────────────────────

  private McpSyncClient newClient() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + shell.boundMcpPort())
            .endpoint(shell.config().mcpPath())
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    McpSyncClient syncClient =
        McpClient.sync(transport)
            .requestTimeout(CALL_TIMEOUT.plusMinutes(2))
            .initializationTimeout(Duration.ofSeconds(20))
            .build();
    syncClient.initialize();
    return syncClient;
  }

  private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args) {
    return client.callTool(new McpSchema.CallToolRequest(toolName, args));
  }

  private McpToolOutcome callToolOutcome(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolResult result = callWithApproval(toolName, args);
    String raw = wireText(result);
    JsonNode body = null;
    if (!result.isError()) {
      try {
        body = JSON.readTree(raw);
      } catch (JsonProcessingException e) {
        body = null;
      }
    }
    return new McpToolOutcome(result.isError(), raw, body);
  }

  private JsonNode callToolJson(String toolName, Map<String, Object> args) throws Exception {
    McpToolOutcome outcome = callToolOutcome(toolName, args);
    if (outcome.isError()) {
      throw new IllegalStateException(
          "GM 工具 " + toolName + " 失败: " + truncate(outcome.raw(), 1500));
    }
    return outcome.body();
  }

  private McpSchema.CallToolResult callWithApproval(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
    FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> client.callTool(request));
    Thread.ofVirtual().name("gov-scenario-mcp").start(task);
    approvals.clear();
    long deadline = System.nanoTime() + CALL_TIMEOUT.toNanos();
    while (!task.isDone() && System.nanoTime() < deadline) {
      for (ApprovalRequest pending : shell.pendingApprovals().pending()) {
        if (answeredIds.add(pending.id())
            && shell
                .pendingApprovals()
                .decide(pending.id(), ApprovalDecision.APPROVE_ONCE, "test:gov-scenario")) {
          approvals.add(pending.classKey());
        }
      }
      Thread.sleep(25);
    }
    if (!task.isDone()) {
      task.cancel(true);
      throw new IllegalStateException(
          "MCP 调用 " + toolName + " 在 " + CALL_TIMEOUT + " 内未返回；本轮已答审批=" + approvals);
    }
    return task.get(5, TimeUnit.SECONDS);
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }

  private static JsonNode errorBody(McpSchema.CallToolResult result) throws Exception {
    String text = wireText(result);
    int close = text.indexOf(']');
    String json = text.startsWith("[mosire:code=") && close > 0 ? text.substring(close + 1) : text;
    return JSON.readTree(json.strip());
  }

  private static String realToolName(String wireName) {
    return wireName == null ? "" : wireName.replace('_', '.');
  }

  private static String textArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static Integer intArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    return value instanceof Number number ? number.intValue() : null;
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

  private static int envInt(String name, int fallback) {
    String text = System.getenv(name);
    if (text == null || text.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String envText(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private void copyRepoProviderConfig() throws IOException {
    Path repoConfig = repoRoot().resolve("config").resolve("llm-providers.json");
    assertThat(Files.isRegularFile(repoConfig)).as("仓库存在 %s", repoConfig).isTrue();
    Path target = storeDir.resolve("llm-providers.json");
    Files.copy(repoConfig, target, StandardCopyOption.REPLACE_EXISTING);
    // ★ 仅改 store 里的副本（不动仓库配置）：中转站某模型上游故障时，可用
    //   SIMOS_GOV_SCENARIO_MODEL=<同站另一模型> 跑同一真场景（例如 deepseek-flash 上游 500 时换 glm-5.3-flash）。
    String modelOverride = System.getenv("SIMOS_GOV_SCENARIO_MODEL");
    if (modelOverride != null && !modelOverride.isBlank()) {
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(target.toFile());
      for (var provider : root.withArray("providers")) {
        if (provider.path("id").asText().equals(PROVIDER_ID)) {
          ((com.fasterxml.jackson.databind.node.ObjectNode) provider).put("model", modelOverride);
        }
      }
      mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), root);
    }
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
                  MAIN,
                  new RevisionId(1),
                  Optional.empty(),
                  T0,
                  "cmd-genesis",
                  "corr-genesis",
                  INITIATOR,
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    } catch (Exception e) {
      throw new IllegalStateException("创世 revision 落盘失败", e);
    }
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref, T0),
            Map.of(
                "map", new io.mosire.simos.map.MapSnapshot(ref, T0, CompactThreeNationsWorld.map()),
                "social", new SocialSnapshot(ref, T0, new SocialData(Map.of(), Map.of(), Map.of())),
                "unit", new UnitSnapshot(ref, T0, UnitState.empty()),
                "sd", new SdSnapshot(ref, T0, SdState.empty()),
                "economy", new EconomySnapshot(ref, T0, EconomyData.empty()),
                "actor", new ActorSnapshot(ref, T0, ActorData.empty()),
                "gov", new GovSnapshot(ref, T0, GovState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(storeDir)
        .write(
            ref,
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new SdCodec(),
                    new EconomyCodec(),
                    new ActorCodec(),
                    new GovCodec())));
  }

  // ────────────────────────────── 小类型 ──────────────────────────────

  private static final class NationFixture {
    final String key;
    final int index;
    final RegionId regionId;
    final Region region;
    final HexCoord capital;
    final HexCoord provinceSeat;
    final UnitId centralGov;
    final UnitId provinceGov;
    final DecisionMakerId centralDm;
    final DecisionMakerId provinceDm;

    boolean selectExecuted;
    boolean dispatchExecuted;
    boolean absorbExecuted;
    boolean retireExecuted;
    boolean scenarioSelect;
    boolean scenarioDispatch;
    boolean scenarioAbsorb;
    boolean scenarioRetire;
    String examUnitId;
    String teamUnitId;
    int centralDirectiveCount;
    int centralRunsOk;
    int centralRunsError;
    boolean qualifiedFirstTwo;
    boolean punished;
    boolean actedAfterPunishment;
    long punishTick;

    NationFixture(
        String key,
        int index,
        RegionId regionId,
        Region region,
        HexCoord capital,
        HexCoord provinceSeat,
        UnitId centralGov,
        UnitId provinceGov,
        DecisionMakerId centralDm,
        DecisionMakerId provinceDm) {
      this.key = key;
      this.index = index;
      this.regionId = regionId;
      this.region = region;
      this.capital = capital;
      this.provinceSeat = provinceSeat;
      this.centralGov = centralGov;
      this.provinceGov = provinceGov;
      this.centralDm = centralDm;
      this.provinceDm = provinceDm;
    }

    Map<String, Object> summaryView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("key", key);
      view.put("region", regionId.value());
      view.put("centralGov", centralGov.value());
      view.put("provinceGov", provinceGov.value());
      view.put("centralDm", centralDm.value());
      view.put("provinceDm", provinceDm.value());
      view.put("capital", hexText(capital));
      view.put("provinceSeat", hexText(provinceSeat));
      view.put("centralDirectiveCount", centralDirectiveCount);
      view.put("centralRunsOk", centralRunsOk);
      view.put("centralRunsError", centralRunsError);
      view.put("qualifiedFirstTwo", qualifiedFirstTwo);
      view.put("punished", punished);
      view.put("punishTick", punishTick);
      view.put("actedAfterPunishment", actedAfterPunishment);
      view.put(
          "tools",
          Map.of(
              "select", selectExecuted,
              "dispatch", dispatchExecuted,
              "absorb", absorbExecuted,
              "retire", retireExecuted));
      view.put(
          "scenarioTools",
          Map.of(
              "select", scenarioSelect,
              "dispatch", scenarioDispatch,
              "absorb", scenarioAbsorb,
              "retire", scenarioRetire));
      return view;
    }
  }

  private record DirectiveInfo(Directive directive, String intent) {
    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", directive.id().value());
      view.put("dm", directive.decisionMakerId().value());
      view.put("tick", directive.tick());
      view.put("status", directive.status().name());
      view.put("commands", commandTypes(directive));
      view.put("intent", truncate(intent, 4000));
      return view;
    }
  }

  private record ToolTrace(
      int round, String dmId, String tool, Map<String, Object> args, boolean ok, String result) {
    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("round", round);
      view.put("dm", dmId);
      view.put("tool", realToolName(tool));
      view.put("args", args);
      view.put("ok", ok);
      view.put("result", truncate(result, 3000));
      return view;
    }
  }

  private record DmRun(
      NationFixture nation,
      boolean central,
      String dmId,
      int round,
      long tick,
      long headBefore,
      long headAfter,
      JsonNode body,
      String raw,
      boolean triggerError,
      String error,
      List<ToolTrace> traces,
      List<String> approvals,
      List<DirectiveInfo> directives,
      Map<String, Object> view) {}

  private record ExecutionRecord(
      String tool,
      String nationKey,
      boolean scenarioTriggered,
      long count,
      long headBefore,
      long headAfter,
      JsonNode preview,
      JsonNode apply,
      List<String> violations) {
    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("tool", tool);
      view.put("nation", nationKey);
      view.put("scenarioTriggered", scenarioTriggered);
      view.put("count", count);
      view.put("headBefore", headBefore);
      view.put("headAfter", headAfter);
      view.put("conservationOk", violations.isEmpty());
      view.put("violations", violations);
      view.put("preview", preview);
      view.put("apply", apply);
      return view;
    }
  }

  private record McpToolOutcome(boolean isError, String raw, JsonNode body) {}

  /** 仅调试：脚本回放（每个决策人每轮“可选一次越权探测 + 一条 sd.IssueDirective + 收口”），不联网。 */
  private final class ScriptedGovLlmClients implements DecisionAgentService.LlmClients {

    @Override
    public ProviderLlm providerFor(String providerId) {
      assertThat(providerId).as("脚本路径仍由世界事实解析出 providerId").isEqualTo(PROVIDER_ID);
      return new ProviderLlm(new ScriptedGovLlmClient(), false);
    }
  }

  /** 每个 DM 一份阶段机：0 = 本轮第一个动作，1 = 已出令，之后收口并复位。 */
  private final class ScriptedGovLlmClient implements LlmClient {

    private final Map<String, Integer> phaseByDm = new LinkedHashMap<>();
    private final Map<String, Integer> callByDm = new LinkedHashMap<>();

    @Override
    public LlmResponse chat(LlmRequest request) {
      String dmId = dmIdOf(request);
      boolean central = dmId.contains("central");
      String mode = envText("SIMOS_GOV_SCENARIO_FAKE_MODE");
      int call = callByDm.merge(dmId, 1, Integer::sum);
      int phase = phaseByDm.getOrDefault(dmId, 0);

      if (central && "scope".equals(mode) && phase == 0) {
        phaseByDm.put(dmId, 1);
        return LlmResponse.toolCall(
            "fake-probe-" + dmId + "-" + call, "simos_unit_get", Map.of("id", provinceGovId(dmId)));
      }
      if (central && "scope".equals(mode) && phase == 1) {
        phaseByDm.put(dmId, 2);
        return directiveToolCall(dmId, call, true, true);
      }
      if (phase == 0) {
        phaseByDm.put(dmId, 1);
        boolean punishMode = "punish".equals(mode);
        boolean centralGoal = !central || !punishMode || call >= 5;
        return directiveToolCall(dmId, call, central, centralGoal);
      }
      phaseByDm.put(dmId, 0);
      return LlmResponse.text("（脚本决策人收口）");
    }

    private LlmResponse directiveToolCall(
        String dmId, int call, boolean central, boolean centralGoal) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("directiveId", "fake-" + dmId + "-" + call);
      payload.put("decisionMakerId", dmId);
      payload.put("tick", currentTick());
      payload.put(
          "intentInfo",
          central
              ? (centralGoal
                  ? "指挥地方落实：①科举选人送中央（selectExaminees）10 人 ②派武装调查组（dispatchTeam）5 人"
                      + "③吸收人口单位入编（absorbUnit）5 人 ④老吏退休回籍（retireStaff）5 人。"
                  : "本轮先观察，不下达任何具体任务；等上级或下级先动。")
              : "服从中央调度：省级执行科举选人 10 人、派调查组 5 人、吸收 5 人入编、退休回籍 5 人。");
      if (central && "scope".equals(envText("SIMOS_GOV_SCENARIO_FAKE_MODE"))) {
        // 中央试图命令省资源：裁决时必须因超出可达面被拒（权限≠信息）。
        payload.put(
            "commands",
            List.of(
                Map.of(
                    "type",
                    "unit.RecruitStaff",
                    "payloadJson",
                    json(Map.of("unitId", provinceGovId(dmId), "role", "SCRIBE", "count", 1L)))));
      } else {
        payload.put("commands", List.of());
      }
      return LlmResponse.toolCall(
          "fake-call-" + dmId + "-" + call,
          "sd_IssueDirective",
          Map.of("payloadJson", json(payload), "branch", MAIN.value(), "expectedRevision", head()));
    }

    private String provinceGovId(String dmId) {
      int index = Integer.parseInt(dmId.substring(dmId.lastIndexOf('-') + 1));
      return nations.get("n" + index).provinceGov.value();
    }
  }

  /** 从落盘的 system 开场消息里取回本决策人的 id（脚本客户端只用于调试）。 */
  private static String dmIdOf(LlmRequest request) {
    for (LlmMessage message : request.messages()) {
      if (!LlmMessage.ROLE_SYSTEM.equals(message.role())) {
        continue;
      }
      String text = textOf(message);
      int start = text.indexOf("你是决策人「");
      if (start >= 0) {
        int from = start + "你是决策人「".length();
        int to = text.indexOf('」', from);
        if (to > from) {
          return text.substring(from, to);
        }
      }
    }
    throw new IllegalStateException("脚本客户端看不到决策人身份 system 消息");
  }

  private static String textOf(LlmMessage message) {
    StringBuilder out = new StringBuilder();
    for (ContentPart part : message.content()) {
      if (part instanceof ContentPart.Text text) {
        out.append(text.text());
      }
    }
    return out.toString();
  }
}
