package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionFaceGolden;
import io.mosire.simos.app.household.GovernmentHouseholdWiring;
import io.mosire.simos.app.household.GovernmentPostTierConsistency;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.app.household.GovernmentServiceUnitConsistency;
import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.time.GovSalaryRuleBridge;
import io.mosire.simos.app.tools.SimosToolSource.Role;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovBudgetCategory;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.govpolicy.GovOfficialSalaryRule;
import io.mosire.simos.economy.govpolicy.GovPostTier;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.spi.EconomySetGovernmentPolicyHandler;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.decision.SimosDecisionClock;
import io.mosire.simos.ga.model.AccessLimit;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.tools.read.GovInfoTool;
import io.mosire.simos.gov.tools.write.GovAssignPostsTool;
import io.mosire.simos.gov.tools.write.GovExpandHouseholdTool;
import io.mosire.simos.gov.tools.write.GovOpenPostsToMarketTool;
import io.mosire.simos.gov.tools.write.GovSetBudgetPolicyTool;
import io.mosire.simos.gov.tools.write.GovSetEstablishmentTool;
import io.mosire.simos.gov.tools.write.GovTransferTreasuryTool;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.AssignExternalGovPostHandler;
import io.mosire.simos.unit.tools.write.UnitSetJurisdictionTool;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.DmUsableCommand;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>Z6：GOV 服务模式工具面正/负判据（Z3c-2 + Z3d + Z4 §18 跨切片一致性）</b>。
 *
 * <p>判据来源逐条 = 台账（不按实现反推）：
 *
 * <ul>
 *   <li>{@code z3c2-impl-ledger.md} §8 全部 28 条（权限/审批 1~6、配置 7~10、assign/expand 11~18、
 *       transferTreasury 19~21、gov.info 22~27、openPostsToMarket 28 由 Z3d 回填）与 §9.2；
 *   <li>{@code z3d-impl-ledger.md} §7.2 第 8~11 条（外部岗位工具权限/预检/端到端/裸提交边界）；
 *   <li>{@code z4-impl-ledger.md} §7 第 11 条（§18 跨切片一致性负向：service unit 一致性）。
 * </ul>
 *
 * <p>★★ <b>装配</b>：每个用例自建真 {@link Shell}（空库 → {@code SmallWorld.state} → 注入含 {@code
 * dm-central}/{@code dm-province}/{@code dm-nation}/{@code dm-army} 的 sd 片 → 真 {@code
 * coreSimos().bootstrapGenesis}），GM 工具走 system 权限直调、决策人工具走 {@code
 * DecisionCallerFactory}（身份/资源/审批链与生产同源）。期望值用台账/规格里的字面量（tick0 小世界事实见 {@code
 * GovGenesisZ6Test}），不拿被测实现算期望。
 */
class GovToolsZ6Test {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final BranchId MAIN = new BranchId("main");
  private static final UnitId CENTRAL = UnitId.parse(GovWorldBootstrap.CENTRAL_GOV_ID);

  /** 夹具国家 id（`dm-nation` 的归属行指向它；小世界创世不造国家 ⇒ 由夹具补）。 */
  private static final String NATION = "FRA";

  /** 夹具军队 id（`dm-army` 的归属行指向它；同理由夹具补）。 */
  private static final String ARMY = "a1";

  private static final UnitId PROVINCE = UnitId.parse(GovWorldBootstrap.PROVINCE_GOV_ID);

  private static final HouseholdId CENTRAL_OFFICIAL = HouseholdId.parse("hh-unit:gov-central");
  private static final HouseholdId CENTRAL_GOV_HOUSEHOLD = GovernmentHouseholds.of(CENTRAL.value());

  /** 外部家户（真实 Social/economy 行；hex (-2,0) 农村贫农，tick0 人口 80 = 200×45% − 5% 流民）。 */
  private static final HouseholdId EXTERNAL_PEASANT =
      HouseholdId.parse("hh--2_0-rural-poor_peasant");

  private static final String TIERS_3 =
      "[{\"tierId\":\"tier-1\",\"securityWeightPerMille\":1000,\"paperworkWeightPerMille\":0},"
          + "{\"tierId\":\"tier-2\",\"securityWeightPerMille\":0,\"paperworkWeightPerMille\":1000},"
          + "{\"tierId\":\"tier-3\",\"securityWeightPerMille\":500,\"paperworkWeightPerMille\":500}]";

  private static final String TIERS_2 =
      "[{\"tierId\":\"tier-1\",\"securityWeightPerMille\":1000,\"paperworkWeightPerMille\":0},"
          + "{\"tierId\":\"tier-2\",\"securityWeightPerMille\":0,\"paperworkWeightPerMille\":1000}]";

  private static final String TIERS_DUPLICATE =
      "[{\"tierId\":\"tier-1\",\"securityWeightPerMille\":1000,\"paperworkWeightPerMille\":0},"
          + "{\"tierId\":\"tier-1\",\"securityWeightPerMille\":0,\"paperworkWeightPerMille\":1000},"
          + "{\"tierId\":\"tier-3\",\"securityWeightPerMille\":500,\"paperworkWeightPerMille\":500}]";

  /** tick0 中央计划（Z5/规格 §12.9 字面量）：两维各 16000、默认 3 档、四个修正 1000‰、k=1。 */
  private static final String GENESIS_CENTRAL_PLAN =
      establishmentPayload("gov-central", 16_000L, 1_000L, 1L, TIERS_3);

  @TempDir Path tempDir;

  // ── P0-1：决策人越权 GOV / 省略 unitId 身份派生（z3c2 §8-1、§8-10）────────────────────

  @Test
  void dmEstablishmentRejectsCrossGovAndDerivesOwnGovWhenUnitIdOmitted() throws Exception {
    try (World world = start(tempDir.resolve("dm-establishment"), "dm-establishment")) {
      Shell shell = world.shell();
      DecisionCallerFactory factory = dmFactory(shell);
      AgentTool tool = dmTool(shell, GovSetEstablishmentTool.NAME);

      ToolResult cross =
          tool.execute(
              dmContext(
                  factory,
                  world.dm("dm-central"),
                  world.state(),
                  world.mapId(),
                  tool,
                  Map.of(
                      "payloadJson",
                      establishmentPayload("gov-province", 12_345L, 1_000L, 1L, TIERS_3),
                      "preview",
                      true)));
      assertThat(cross.success()).as("载荷指定别的 GOV 必须被拒：%s", cross.message()).isFalse();
      assertThat(cross.code()).as("越权 GOV 属身份/归属拒，不是 BAD_REQUEST").isEqualTo("REJECTED");
      assertThat(cross.message())
          .contains("越权 GOV")
          .contains("gov-province")
          .contains("gov-central");
      assertThat(world.head()).as("越权拒 ⇒ 零 revision").isEqualTo(1L);

      ToolResult own =
          tool.execute(
              dmContext(
                  factory,
                  world.dm("dm-central"),
                  world.state(),
                  world.mapId(),
                  tool,
                  Map.of(
                      "payloadJson",
                      establishmentPayload(null, 12_345L, 1_000L, 1L, TIERS_3),
                      "preview",
                      true)));
      assertThat(own.success()).as(own.message()).isTrue();
      JsonNode view = JSON.readTree(own.message());
      assertThat(view.get("govUnitId").asText())
          .as("省略 unitId ⇒ 生效目标 = 自己所属 GOV")
          .isEqualTo("gov-central");
      assertThat(view.get("govDerivedFromIdentity").asBoolean()).as("身份派生标记").isTrue();
      JsonNode command = view.get("commandsPreview").get(0);
      assertThat(command.get("type").asText())
          .as("窄工具内部走通用写口（§21.9 D-3：旧 gov.SetAdministrationPlan 已删除，不留别名）")
          .isEqualTo(EconomySetGovernmentPolicyHandler.TYPE);
      assertThat(command.get("payloadJson").asText())
          .as("生效载荷里的来源是**身份覆盖后**的 GovernmentId（不是模型自报的那个 GOV）")
          .contains("\"governmentId\":\"gov-unit-gov-central\"")
          .doesNotContain("gov-province");
      assertThat(view.get("flatPayloadJson").asText())
          .as("调用方原样给的那份（平铺 unitId 载荷）同时可见，两个视图都不猜")
          .contains("\"unitId\":\"gov-central\"");
      assertThat(world.head()).as("决策人 preview 零写入").isEqualTo(1L);
    }
  }

  // ── P0-2：Nation/Army 决策人调任一 GOV 写工具 ⇒ REJECTED 带归属（z3c2 §8-4）──────────

  @Test
  void nonGovDecisionMakerCallingAnyGovWriteToolIsRejectedByName() {
    try (World world = start(tempDir.resolve("non-gov"), "non-gov")) {
      Shell shell = world.shell();
      DecisionCallerFactory factory = dmFactory(shell);

      Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
      calls.put(GovSetEstablishmentTool.NAME, Map.of("payloadJson", "{}"));
      calls.put(GovSetBudgetPolicyTool.NAME, Map.of("payloadJson", "{}"));
      calls.put(GovAssignPostsTool.NAME, Map.of());
      calls.put(GovExpandHouseholdTool.NAME, Map.of());
      calls.put(GovOpenPostsToMarketTool.NAME, Map.of());

      for (Map.Entry<String, Map<String, Object>> entry : calls.entrySet()) {
        AgentTool tool = dmTool(shell, entry.getKey());
        ToolResult nation =
            tool.execute(
                dmContext(
                    factory,
                    world.dm("dm-nation"),
                    world.state(),
                    world.mapId(),
                    tool,
                    entry.getValue()));
        assertThat(nation.success())
            .as("%s 对 Nation 决策人必须拒：%s", entry.getKey(), nation.message())
            .isFalse();
        assertThat(nation.code()).as(entry.getKey()).isEqualTo("REJECTED");
        assertThat(nation.message())
            .as("%s 的拒因必须写清归属", entry.getKey())
            .contains("只有 GOV 归属的决策人")
            .contains("归属")
            .contains("Nation");
      }

      AgentTool establishment = dmTool(shell, GovSetEstablishmentTool.NAME);
      ToolResult army =
          establishment.execute(
              dmContext(
                  factory,
                  world.dm("dm-army"),
                  world.state(),
                  world.mapId(),
                  establishment,
                  Map.of("payloadJson", "{}")));
      assertThat(army.code()).isEqualTo("REJECTED");
      assertThat(army.message()).contains("归属").contains("Army");
      assertThat(world.head()).as("全部身份拒 ⇒ 零 revision").isEqualTo(1L);
    }
  }

  // ── P0-3：敏感写必经审批链，GM 点头后才恰 +1 revision（z3c2 §8-3、§9.2 变异自证）
  //          + 决策人桶/白名单不含 transferTreasury（§8-2、§8-28 的 Z3d 回填）────────────

  @Test
  void decisionMakerSensitiveWriteIsQueuedWithoutExecutingOrUsingRetiredApproval() {
    try (World world = start(tempDir.resolve("dm-queue"), "dm-queue")) {
      Shell shell = world.shell();
      DecisionCallerFactory factory = dmFactory(shell);
      AgentTool tool = dmTool(shell, GovSetEstablishmentTool.NAME);
      ToolRegistry dmRegistry = new ToolRegistry();
      dmRegistry.register(tool);

      Map<String, Object> args = new LinkedHashMap<>();
      args.put("payloadJson", establishmentPayload("gov-central", 12_345L, 1_000L, 1L, TIERS_3));
      args.put("preview", false);
      args.put("expectedRevision", 1L);

      PendingDecisionQueue queue =
          new PendingDecisionQueue(
              "dm-central", AccessToken.DEFAULT, SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
      DecisionTurnScope.bind(queue);
      ToolResult result;
      try {
        result =
            factory.execute(
                dmRegistry,
                GovSetEstablishmentTool.NAME,
                world.dm("dm-central"),
                world.state(),
                world.mapId(),
                args);
      } finally {
        DecisionTurnScope.clear(queue);
      }

      assertThat(result.success()).as(result.message()).isTrue();
      assertThat(result.message()).contains("已加入决策包");
      assertThat(queue.size()).as("D1：敏感写 Ask 进入本轮队列").isEqualTo(1);
      assertThat(queue.snapshot().get(0).toolName()).isEqualTo(GovSetEstablishmentTool.NAME);
      assertThat(shell.pendingApprovals().pending()).as("H1/H3：不再投 HTTP 时代的单条待批表").isEmpty();
      assertThat(world.head()).as("D1：未裁决 ⇒ 世界零 revision").isEqualTo(1L);
    }
  }

  @Test
  void transferTreasuryIsGmOnlyAndAbsentFromDecisionBucketAndWhitelist() {
    try (World world = start(tempDir.resolve("gm-only-buckets"), "gm-only-buckets")) {
      Shell shell = world.shell();
      List<String> gmNames = shell.toolsFor(Role.GM).stream().map(AgentTool::name).toList();
      List<String> dmNames =
          shell.toolsFor(Role.DECISION_AGENT).stream().map(AgentTool::name).toList();

      // ★ B4b：这一条断的是**测试侧金名单**（独立规格），与下面那条"装配派生的 dmNames"是两条独立腿；
      //   两者合起来等价于旧的"静态白名单 ∌ transferTreasury"（且不再需要一份生产清单）。
      assertThat(DecisionFaceGolden.ALL)
          .as("金名单里放的是工具名：transferTreasury 不得入列")
          .doesNotContain(GovTransferTreasuryTool.NAME);
      assertThat(dmNames)
          .as("决策人桶不得有 transferTreasury")
          .doesNotContain(GovTransferTreasuryTool.NAME);
      assertThat(gmNames).as("GM 桶必须有 transferTreasury").contains(GovTransferTreasuryTool.NAME);
      ToolRegistry dmRegistry = new ToolRegistry();
      shell.toolsFor(Role.DECISION_AGENT).forEach(dmRegistry::register);
      assertThat(dmRegistry.find(GovTransferTreasuryTool.NAME)).as("决策人注册表 find 为空").isEmpty();
      assertThat(shell.toolRegistry().find(GovTransferTreasuryTool.NAME))
          .as("GM 注册表 find 有")
          .isPresent();

      for (String name :
          List.of(
              GovSetEstablishmentTool.NAME,
              GovSetBudgetPolicyTool.NAME,
              GovAssignPostsTool.NAME,
              GovExpandHouseholdTool.NAME,
              GovOpenPostsToMarketTool.NAME,
              GovInfoTool.NAME)) {
        assertThat(gmNames).as("GM 桶缺 " + name).contains(name);
        assertThat(dmNames).as("GM/DM 双桶缺 " + name).contains(name);
        assertThat(DecisionFaceGolden.ALL).as("金名单缺 " + name).contains(name);
      }
    }
  }

  // ── P0-4：通用政府政策写口仍**不打 DmUsableCommand**（= 仅 GM），且进不了决策人 catalog（z3c2 §8-6）──────

  @Test
  void notDmUsableGovPolicyWriteIsInvisibleInDecisionCatalog() throws Exception {
    assertThat(new EconomySetGovernmentPolicyHandler())
        .as(
            "economy.SetGovernmentPolicy 必须仍不打 DmUsableCommand（反默认 ⇒ 仅 GM：令/RegisterEffect/catalog"
                + " 三条路径不放大）—— ★ 2026-10-12 批次 1 迁移：旧断言 isInstanceOf(GmOnlyCommand)（标记已退役）")
        .isNotInstanceOf(DmUsableCommand.class);

    try (World world = start(tempDir.resolve("gm-only-catalog"), "gm-only-catalog")) {
      Shell shell = world.shell();
      AgentTool catalog = gmTool(shell, CatalogTool.NAME);

      JsonNode gmView = JSON.readTree(gmExecute(catalog, Map.of()).message());
      List<String> gmTypes = new ArrayList<>();
      gmView.get("types").forEach(node -> gmTypes.add(node.asText()));
      assertThat(gmTypes)
          .as("GM catalog 仍看得见通用政府政策写口（正向对照）")
          .contains(EconomySetGovernmentPolicyHandler.TYPE)
          .contains("unit.AssignGovPost");
      assertThat(gmView.get("payloadHints").has(EconomySetGovernmentPolicyHandler.TYPE)).isTrue();

      DecisionCallerFactory factory = dmFactory(shell);
      ToolResult dmResult =
          catalog.execute(
              dmContext(
                  factory,
                  world.dm("dm-central"),
                  world.state(),
                  world.mapId(),
                  catalog,
                  Map.of()));
      assertThat(dmResult.success()).as(dmResult.message()).isTrue();
      JsonNode dmView = JSON.readTree(dmResult.message());
      List<String> dmTypes = new ArrayList<>();
      dmView.get("types").forEach(node -> dmTypes.add(node.asText()));
      assertThat(dmTypes)
          .as("不打 DmUsableCommand ⇒ 不得进决策人 catalog（即令里不可嵌）")
          .doesNotContain(EconomySetGovernmentPolicyHandler.TYPE);
      assertThat(dmTypes)
          .as("打了 DmUsableCommand 的岗位命令仍在决策人 catalog（正向对照，证明过滤不是整体为空）")
          .contains("unit.AssignGovPost");

      // ★ 决策人面（金名单）放的是**工具名**不是命令类型；命令类型的不可嵌判据 = 不打 DmUsableCommand + 上面的 DM catalog。
      assertThat(DecisionFaceGolden.ALL)
          .as("金名单不得混入命令类型（口径说明）")
          .doesNotContain(EconomySetGovernmentPolicyHandler.TYPE);
      assertThat(DecisionFaceGolden.ALL)
          .as("两条 GovOnly 命令对应的决策人窄工具仍必须有")
          .contains(GovSetEstablishmentTool.NAME, GovSetBudgetPolicyTool.NAME);
    }
  }

  // ── P0-5：openPostsToMarket 外部户（Z3d §7.2-10；z3c2 §8-28 的 Z3d 回填）──────────────

  @Test
  void openPostsToMarketPreviewIsZeroWriteApplyIsOneRevisionAndKeepsExternalAffiliation()
      throws Exception {
    try (World world = start(tempDir.resolve("open-posts"), "open-posts")) {
      Shell shell = world.shell();
      SimulationState before = world.state();
      GovernmentFormation beforeFormation = formation(before, CENTRAL);
      long beforePopulation =
          GovZ6WorldFixture.economySlice(before).classes().get(EXTERNAL_PEASANT).population();
      HouseholdLocation beforeLocation =
          GovZ6WorldFixture.socialSlice(before).households().get(EXTERNAL_PEASANT).location();
      Map<UnitId, List<HouseholdId>> beforeHouseholds = unitHouseholds(before);
      Map<StaffRole, Long> beforeStaff = beforeFormation.staff();

      AgentTool tool = gmTool(shell, GovOpenPostsToMarketTool.NAME);
      // ★ 该户在创世已有生产承诺（贫农参与率 950‰ ⇒ 余量 ≥ 5%），显式 100 毫小时保证 Σ 预算内。
      Map<String, Object> args = openPostsArgs(EXTERNAL_PEASANT.value(), "tier-2", 100L, true);
      ToolResult preview = gmExecute(tool, args);
      assertThat(preview.success()).as(preview.message()).isTrue();
      JsonNode previewView = JSON.readTree(preview.message());
      assertThat(previewView.get("preview").asBoolean()).isTrue();
      assertThat(previewView.get("submitted").asBoolean()).isFalse();
      assertThat(previewView.get("external").asBoolean()).isTrue();
      assertThat(previewView.get("govUnitId").asText()).isEqualTo("gov-central");
      assertThat(previewView.get("householdId").asText()).isEqualTo(EXTERNAL_PEASANT.value());
      assertThat(previewView.get("householdAffiliationUnchanged").asBoolean()).isTrue();
      assertThat(previewView.get("householdLocationUnchanged").asBoolean()).isTrue();
      assertThat(previewView.get("committedLaborMilli").asLong()).as("显式承诺量逐值透传").isEqualTo(100L);
      assertThat(previewView.get("socialLaborMilli").asLong())
          .as("当前 Social 权威劳动必须大于显式承诺（否则本该拒）")
          .isGreaterThan(100L);
      List<String> previewCommands = new ArrayList<>();
      previewView
          .get("commandsPreview")
          .forEach(row -> previewCommands.add(row.get("type").asText()));
      assertThat(previewCommands)
          .as("批 = AssignExternalGovPost + SetGovServiceCommitment；不改 Unit.households")
          .contains("unit.AssignExternalGovPost", "economy.SetGovServiceCommitment")
          .doesNotContain("unit.SetUnitHouseholds");
      assertThat(world.head()).as("preview 零写入").isEqualTo(1L);
      assertThat(GovZ6WorldFixture.unitSlice(world.state()).units().get(CENTRAL).households())
          .as("preview 不得改 Unit.households")
          .isEqualTo(beforeHouseholds.get(CENTRAL));

      Map<String, Object> applyArgs = new LinkedHashMap<>(args);
      applyArgs.put("preview", false);
      applyArgs.put("expectedRevision", 1L);
      ToolResult applied = gmExecute(tool, applyArgs);
      assertThat(applied.success()).as(applied.message()).isTrue();
      JsonNode appliedView = JSON.readTree(applied.message());
      assertThat(appliedView.get("submitted").asBoolean()).isTrue();
      assertThat(appliedView.get("submission").get("result").asText()).isEqualTo("committed");
      assertThat(world.head()).as("apply 恰一条 revision").isEqualTo(2L);

      SimulationState after = world.stateAt(2L);
      GovernmentFormation afterFormation = formation(after, CENTRAL);
      GovernmentPostOfHousehold externalPost = afterFormation.externalPosts().get(EXTERNAL_PEASANT);
      assertThat(externalPost).as("externalPosts 必须命中该外部户").isNotNull();
      assertThat(externalPost.role()).isEqualTo(StaffRole.SCRIBE);
      assertThat(externalPost.tierId()).isEqualTo("tier-2");
      assertThat(afterFormation.governmentPostsOfHousehold().keySet())
          .as("内部 householdPosts 不得掺入外部户（两表互斥）")
          .doesNotContain(EXTERNAL_PEASANT);

      EconomyData economyAfter = GovZ6WorldFixture.economySlice(after);
      HouseholdLaborCommitment commitment = govServiceCommitment(economyAfter, EXTERNAL_PEASANT);
      assertThat(commitment.laborMilli()).as("承诺 = 工具显式给的 100 毫小时（不静默按 Social 全额）").isEqualTo(100L);
      assertThat(commitment.activity())
          .as("activity 缺省解析到 GOV 唯一 office unit")
          .isEqualTo(serviceUnitOf(economyAfter, CENTRAL).value());

      assertThat(unitHouseholds(after))
          .as("外部户保留原单位归属：Unit.households 全表逐值不变")
          .isEqualTo(beforeHouseholds);
      assertThat(GovZ6WorldFixture.socialSlice(after).households().get(EXTERNAL_PEASANT).location())
          .as("Social 位置逐值不变")
          .isEqualTo(beforeLocation);
      HouseholdEconomy rowAfter = economyAfter.classes().get(EXTERNAL_PEASANT);
      assertThat(rowAfter.population()).as("economy 视图人口逐值不变").isEqualTo(beforePopulation);
      assertThat(afterFormation.staff()).as("stored staff 逐值不变（投影不写 staff）").isEqualTo(beforeStaff);

      GovernmentServiceLaborBridge.Supply supply =
          GovernmentServiceLaborBridge.supply(economyAfter, CENTRAL, afterFormation, 0L);
      assertThat(supply.securityLaborMilli())
          .as("tier-2 公文档：既有官方 tier-3 治安腿 = 32000×500/1000 = 16000")
          .isEqualTo(16_000L);
      assertThat(supply.paperworkLaborMilli())
          .as("Z3d：外部岗位计入供给（公文 = 16000 + 外部承诺 100）")
          .isEqualTo(16_100L);
    }
  }

  @Test
  void openPostsToMarketRejectsMissingSocialOrEconomyHouseholdAndUnknownTier() {
    HouseholdId inSocialNotEconomy = HouseholdId.parse("hh-ghost-social-only");
    try (World world =
        start(
            tempDir.resolve("open-posts-neg"),
            "open-posts-neg",
            state -> withGhostSocialHousehold(state, inSocialNotEconomy))) {
      Shell shell = world.shell();
      AgentTool tool = gmTool(shell, GovOpenPostsToMarketTool.NAME);

      ToolResult ghost = gmExecute(tool, openPostsArgs("hh-ghost-open", null, null, true));
      assertThat(ghost.success()).isFalse();
      assertThat(ghost.code()).isEqualTo("BAD_REQUEST");
      assertThat(ghost.message()).contains("外部家户不存在").contains("hh-ghost-open");

      ToolResult missingEconomy =
          gmExecute(tool, openPostsArgs(inSocialNotEconomy.value(), null, null, true));
      assertThat(missingEconomy.code()).isEqualTo("BAD_REQUEST");
      assertThat(missingEconomy.message())
          .as("Social 有行但 economy 缺行 ⇒ 具名 BAD_REQUEST")
          .contains("economy 缺家户行")
          .contains(inSocialNotEconomy.value());

      ToolResult unknownTier =
          gmExecute(tool, openPostsArgs("hh--2_0-rural-middle_peasant", "tier-9", null, true));
      assertThat(unknownTier.code()).isEqualTo("BAD_REQUEST");
      assertThat(unknownTier.message()).contains("tierId 不在 GOV").contains("tier-9");

      ToolResult internal =
          gmExecute(tool, openPostsArgs(CENTRAL_OFFICIAL.value(), null, null, true));
      assertThat(internal.code()).isEqualTo("BAD_REQUEST");
      assertThat(internal.message())
          .as("内部官吏户不得走外部岗位（指路 assignPosts）")
          .contains("已在 GOV 单位")
          .contains("simos.gov.assignPosts");

      assertThat(world.head()).as("四条负向 ⇒ 零 revision").isEqualTo(1L);
    }
  }

  // ── P1-6：GM setEstablishment 载荷守卫（z3c2 §8-7）────────────────────────────────────

  @Test
  void gmSetEstablishmentRejectsMalformedPayloadsWithZeroRevision() {
    record Case(String label, String payload, String fragment) {}

    List<Case> cases =
        List.of(
            new Case("坏 JSON", "{not-json", "JSON"),
            new Case(
                "缺 unitId",
                establishmentPayload(null, 16_000L, 1_000L, 1L, TIERS_3),
                "govUnitId 必填"),
            new Case(
                "计划量负数",
                establishmentPayload("gov-central", -1L, 1_000L, 1L, TIERS_3),
                "securityPlannedLaborMilli 必须 ≥ 0"),
            new Case(
                "修正负数",
                establishmentPayload("gov-central", 16_000L, -1L, 1L, TIERS_3),
                "securitySupplyStaticModifierPerMille 必须 ≥ 0"),
            new Case(
                "k 负数",
                establishmentPayload("gov-central", 16_000L, 1_000L, -1L, TIERS_3),
                "supernumerarySqrtCoefficient 必须 ≥ 0"),
            new Case(
                "档位数 != 3",
                establishmentPayload("gov-central", 16_000L, 1_000L, 1L, TIERS_2),
                "postTiers 必须恰 3 档"),
            new Case(
                "tierId 重复",
                establishmentPayload("gov-central", 16_000L, 1_000L, 1L, TIERS_DUPLICATE),
                "不得重复"));

    try (World world = start(tempDir.resolve("establishment-guards"), "establishment-guards")) {
      AgentTool tool = gmTool(world.shell(), GovSetEstablishmentTool.NAME);
      for (Case item : cases) {
        ToolResult result = gmExecute(tool, Map.of("payloadJson", item.payload(), "preview", true));
        assertThat(result.success()).as("%s 必须被拒：%s", item.label(), result.message()).isFalse();
        assertThat(result.code()).as(item.label()).isEqualTo("BAD_REQUEST");
        assertThat(result.message()).as(item.label()).contains(item.fragment());
      }
      assertThat(world.head()).as("全部载荷拒 ⇒ 零 revision").isEqualTo(1L);
    }
  }

  // ── P1-7：逐值相同重放 = noop、不落空 revision（z3c2 §8-8）──────────────────────────

  @Test
  void setEstablishmentIdenticalReplayIsNoopWithoutRevision() throws Exception {
    try (World world = start(tempDir.resolve("establishment-noop"), "establishment-noop")) {
      AgentTool tool = gmTool(world.shell(), GovSetEstablishmentTool.NAME);

      ToolResult preview =
          gmExecute(tool, Map.of("payloadJson", GENESIS_CENTRAL_PLAN, "preview", true));
      assertThat(preview.success()).as(preview.message()).isTrue();
      JsonNode previewView = JSON.readTree(preview.message());
      assertThat(previewView.get("keyExisted").asBoolean()).isTrue();
      assertThat(previewView.get("noop").asBoolean()).as("逐值相同 ⇒ preview noop=true").isTrue();
      assertThat(world.head()).isEqualTo(1L);

      ToolResult replay =
          gmExecute(
              tool,
              Map.of(
                  "payloadJson", GENESIS_CENTRAL_PLAN, "preview", false, "expectedRevision", 1L));
      assertThat(replay.success()).as(replay.message()).isTrue();
      JsonNode replayView = JSON.readTree(replay.message());
      assertThat(replayView.get("submitted").asBoolean()).as("noop ⇒ submitted=false").isFalse();
      assertThat(replayView.get("noop").asBoolean()).isTrue();
      assertThat(replayView.get("preview").asBoolean()).isFalse();
      assertThat(world.head()).as("noop 不落空 revision").isEqualTo(1L);
    }
  }

  // ── P1-8：preview=false 必填 expectedRevision；过期 ⇒ CONFLICT 带真实 head（z3c2 §8-9）─

  @Test
  void setEstablishmentRequiresExpectedRevisionAndReportsRealHeadOnConflict() throws Exception {
    try (World world = start(tempDir.resolve("establishment-revision"), "establishment-revision")) {
      AgentTool tool = gmTool(world.shell(), GovSetEstablishmentTool.NAME);

      ToolResult missing =
          gmExecute(
              tool,
              Map.of(
                  "payloadJson",
                  establishmentPayload("gov-central", 12_345L, 1_000L, 1L, TIERS_3),
                  "preview",
                  false));
      assertThat(missing.success()).isFalse();
      assertThat(missing.code()).isEqualTo("BAD_REQUEST");
      assertThat(missing.message()).contains("expectedRevision");
      assertThat(world.head()).as("缺 expectedRevision ⇒ 零 revision").isEqualTo(1L);

      ToolResult first =
          gmExecute(
              tool,
              Map.of(
                  "payloadJson",
                  establishmentPayload("gov-central", 12_345L, 1_000L, 1L, TIERS_3),
                  "preview",
                  false,
                  "expectedRevision",
                  1L));
      assertThat(first.success()).as(first.message()).isTrue();
      assertThat(world.head()).as("首次提交 +1").isEqualTo(2L);

      ToolResult stale =
          gmExecute(
              tool,
              Map.of(
                  "payloadJson",
                  establishmentPayload("gov-central", 22_222L, 1_000L, 1L, TIERS_3),
                  "preview",
                  false,
                  "expectedRevision",
                  1L));
      assertThat(stale.success()).isFalse();
      assertThat(stale.code()).isEqualTo("CONFLICT");
      JsonNode conflict = JSON.readTree(stale.message());
      assertThat(conflict.get("submission").get("result").asText()).isEqualTo("conflict");
      assertThat(conflict.get("submission").get("current").get("branch").asText())
          .isEqualTo("main");
      assertThat(conflict.get("submission").get("current").get("revision").asLong())
          .as("CONFLICT 必须带真实 head")
          .isEqualTo(2L);
      assertThat(
              GovZ6WorldFixture.economySlice(world.state())
                  .governmentOrDefault(CENTRAL.value())
                  .administrationPlan()
                  .securityPlannedLaborMilli())
          .as("冲突不覆盖已提交计划")
          .isEqualTo(12_345L);
      assertThat(world.head()).as("冲突零 revision").isEqualTo(2L);
    }
  }

  // ── P1-9：GM setBudgetPolicy 守卫（z3c2 §8-7 预算段）──────────────────────────────────

  @Test
  void gmSetBudgetPolicyRejectsDuplicateBoundsAndUnknownCategory() {
    record Case(String label, String categories, String fragment) {}

    List<Case> cases =
        List.of(
            new Case(
                "类别重复",
                "[{\"category\":\"ADMIN_SALARY\"},{\"category\":\"ADMIN_SALARY\"}]",
                "类别不得重复"),
            new Case(
                "min>cap",
                "[{\"category\":\"OTHER\",\"minPerCycle\":10,\"capPerCycle\":5}]",
                "minPerCycle 不得超过 capPerCycle"),
            new Case("未知类别", "[{\"category\":\"BRIBERY\"}]", "未知预算类别"));

    try (World world = start(tempDir.resolve("budget-guards"), "budget-guards")) {
      AgentTool tool = gmTool(world.shell(), GovSetBudgetPolicyTool.NAME);
      for (Case item : cases) {
        ToolResult result =
            gmExecute(
                tool,
                Map.of(
                    "payloadJson",
                    budgetPayload("gov-central", item.categories(), 10L, 1L),
                    "preview",
                    true));
        assertThat(result.success()).as("%s 必须被拒：%s", item.label(), result.message()).isFalse();
        assertThat(result.code()).as(item.label()).isEqualTo("BAD_REQUEST");
        assertThat(result.message()).as(item.label()).contains(item.fragment());
      }
      assertThat(world.head()).as("全部预算负向 ⇒ 零 revision").isEqualTo(1L);

      // 正向对照：合法政策提交成功且逐值生效。
      String valid =
          budgetPayload(
              "gov-central",
              "[{\"category\":\"ADMIN_SALARY\",\"capPerCycle\":1000},{\"category\":\"OTHER\","
                  + "\"minPerCycle\":100,\"capPerCycle\":5000}]",
              10L,
              1L);
      ToolResult applied =
          gmExecute(tool, Map.of("payloadJson", valid, "preview", false, "expectedRevision", 1L));
      assertThat(applied.success()).as(applied.message()).isTrue();
      assertThat(world.head()).as("合法预算 = 一条 revision").isEqualTo(2L);
      var policy =
          GovZ6WorldFixture.economySlice(world.stateAt(2L))
              .governmentOrDefault(CENTRAL.value())
              .budgetPolicy();
      assertThat(policy).isNotNull();
      assertThat(policy.orderedCategories())
          .extracting(line -> line.category())
          .containsExactly(GovBudgetCategory.ADMIN_SALARY, GovBudgetCategory.OTHER);
      assertThat(policy.orderedCategories().get(0).minPerCycle()).isZero();
      assertThat(policy.orderedCategories().get(0).capPerCycle()).isEqualTo(1_000L);
      assertThat(policy.orderedCategories().get(1).minPerCycle()).isEqualTo(100L);
      assertThat(policy.orderedCategories().get(1).capPerCycle()).isEqualTo(5_000L);
      assertThat(policy.officialSalaryRule().grainMilliPerCommittedHour()).isEqualTo(10L);
      assertThat(policy.officialSalaryRule().silverMilliPerCommittedHour()).isEqualTo(1L);
    }
  }

  /**
   * ★★ Z7c：{@code remittancePerMilleToSuperior} 必须同时出现在 tool description / jsonSchema 与 catalog
   * hint 三面（写口/读口/目录同源）；缺一面 = 目录与实际政策漂移。字段仍是同一 {@code gov.SetBudgetPolicy} 载荷，不改形状。
   */
  @Test
  void setBudgetPolicyToolAndCatalogHintExposeRemittanceRate() throws Exception {
    try (World world =
        start(tempDir.resolve("budget-remittance-surface"), "budget-remittance-surface")) {
      AgentTool tool = gmTool(world.shell(), GovSetBudgetPolicyTool.NAME);
      assertThat(tool.description()).contains("remittancePerMilleToSuperior");
      assertThat(tool.description())
          .as("★ Z7e-3：写口必须暴露 PATCH/REPLACE 双模")
          .contains("PATCH")
          .contains("REPLACE");

      @SuppressWarnings("unchecked")
      Map<String, Object> schema = tool.jsonSchema();
      @SuppressWarnings("unchecked")
      Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
      String payloadDescription =
          String.valueOf(((Map<String, Object>) properties.get("payloadJson")).get("description"));
      assertThat(payloadDescription)
          .as("jsonSchema 的 payloadJson 说明必须点名字段")
          .contains("remittancePerMilleToSuperior")
          .contains("0..1000")
          .contains("PATCH")
          .contains("REPLACE");
      assertThat(properties).as("工具顶层 mode 参数（覆盖 payloadJson）").containsKey("mode");

      AgentTool catalog = gmTool(world.shell(), CatalogTool.NAME);
      JsonNode hints =
          JSON.readTree(gmExecute(catalog, Map.of()).message())
              .get("payloadHints")
              .get(EconomySetGovernmentPolicyHandler.TYPE);
      assertThat(hints).as("catalog 必须给 economy.SetGovernmentPolicy 提示").isNotNull();
      assertThat(hints.asText())
          .as("catalog 提示必须含新字段（读写目录同源）")
          .contains("remittancePerMilleToSuperior")
          .contains("PATCH")
          .contains("REPLACE");
    }
  }

  /**
   * ★★ Z7e-3（控制方裁定 A+B："AB同时应用吧"）：{@code mode} 缺省 = PATCH —— 只传 {@code
   * remittancePerMilleToSuperior} 必须保留创世的 5 类预算与 10/1 工资规则（run7 首跑污染同形）；工具顶层 {@code mode} 参数覆盖
   * payloadJson，{@code REPLACE} 仍可达旧整表替换语义。
   */
  @Test
  void setBudgetPolicyPatchKeepsOmittedFieldsAndReplaceModeStillWipes() throws Exception {
    try (World world = start(tempDir.resolve("budget-patch"), "budget-patch")) {
      AgentTool tool = gmTool(world.shell(), GovSetBudgetPolicyTool.NAME);
      GovBudgetPolicy before =
          GovZ6WorldFixture.economySlice(world.state())
              .governmentOrDefault(CENTRAL.value())
              .budgetPolicy();
      assertThat(before).as("创世必须已有中央预算政策").isNotNull();
      assertThat(before.orderedCategories()).hasSize(5);

      String onlyRemittance = "{\"unitId\":\"gov-central\",\"remittancePerMilleToSuperior\":500}";

      // ① preview 先看：PATCH 只覆盖上缴率，5 类/10/1 全保留
      ToolResult preview = gmExecute(tool, Map.of("payloadJson", onlyRemittance, "preview", true));
      assertThat(preview.success()).as(preview.message()).isTrue();
      JsonNode view = JSON.readTree(preview.message());
      assertThat(view.get("editMode").asText()).as("缺省模式 = PATCH").isEqualTo("PATCH");
      assertThat(view.get("budgetAfter").get("orderedCategories")).hasSize(5);
      assertThat(
              view.get("budgetAfter")
                  .get("officialSalaryRule")
                  .get("grainMilliPerCommittedHour")
                  .asLong())
          .isEqualTo(10L);
      assertThat(view.get("budgetAfter").get("remittancePerMilleToSuperior").asLong())
          .isEqualTo(500L);

      // ② apply 后源状态同判
      ToolResult applied =
          gmExecute(
              tool,
              Map.of(
                  "payloadJson",
                  onlyRemittance,
                  "preview",
                  false,
                  "expectedRevision",
                  world.head()));
      assertThat(applied.success()).as(applied.message()).isTrue();
      GovBudgetPolicy after =
          GovZ6WorldFixture.economySlice(world.state())
              .governmentOrDefault(CENTRAL.value())
              .budgetPolicy();
      assertThat(after.orderedCategories()).as("PATCH 不清预算（run7 回归）").hasSize(5);
      assertThat(after.officialSalaryRule().grainMilliPerCommittedHour()).isEqualTo(10L);
      assertThat(after.officialSalaryRule().silverMilliPerCommittedHour()).isEqualTo(1L);
      assertThat(after.remittancePerMilleToSuperior()).isEqualTo(500L);

      // ③ 工具顶层 mode 覆盖载荷：同一"只传上缴率"载荷在 REPLACE 下按旧语义清空
      ToolResult wiped =
          gmExecute(
              tool,
              Map.of(
                  "payloadJson",
                  "{\"unitId\":\"gov-central\",\"remittancePerMilleToSuperior\":0}",
                  "mode",
                  "REPLACE",
                  "preview",
                  false,
                  "expectedRevision",
                  world.head()));
      assertThat(wiped.success()).as(wiped.message()).isTrue();
      GovBudgetPolicy wipedPolicy =
          GovZ6WorldFixture.economySlice(world.state())
              .governmentOrDefault(CENTRAL.value())
              .budgetPolicy();
      assertThat(wipedPolicy.orderedCategories()).as("REPLACE：缺省 = 空表（旧语义）").isEmpty();
      assertThat(wipedPolicy.officialSalaryRule()).isEqualTo(GovOfficialSalaryRule.zero());
      assertThat(wipedPolicy.remittancePerMilleToSuperior()).isZero();
    }
  }

  // ── P2-10：transferTreasury 源二选一/金额/同名账户/余额（z3c2 §8-19~21）──────────────

  @Test
  void transferTreasuryValidatesSourceSelectionAmountsAndBalance() throws Exception {
    try (World world = start(tempDir.resolve("transfer-guards"), "transfer-guards")) {
      AgentTool tool = gmTool(world.shell(), GovTransferTreasuryTool.NAME);

      ToolResult noSource =
          gmExecute(tool, transferArgs(null, null, "gov-central", 1L, 0L, 0L, true, null));
      assertThat(noSource.code()).isEqualTo("BAD_REQUEST");
      assertThat(noSource.message()).contains("恰给一个");

      ToolResult bothSources =
          gmExecute(
              tool,
              transferArgs(
                  "gov-central", EXTERNAL_PEASANT.value(), "gov-province", 1L, 0L, 0L, true, null));
      assertThat(bothSources.code()).isEqualTo("BAD_REQUEST");
      assertThat(bothSources.message()).contains("恰给一个");

      ToolResult negative =
          gmExecute(
              tool, transferArgs("gov-central", null, "gov-province", -1L, 0L, 0L, true, null));
      assertThat(negative.code()).isEqualTo("BAD_REQUEST");
      assertThat(negative.message()).contains("不得为负");

      ToolResult allZero =
          gmExecute(
              tool, transferArgs("gov-central", null, "gov-province", 0L, 0L, 0L, true, null));
      assertThat(allZero.code()).isEqualTo("BAD_REQUEST");
      assertThat(allZero.message()).contains("至少一个必须 > 0");

      ToolResult sameAccount =
          gmExecute(tool, transferArgs("gov-central", null, "gov-central", 1L, 0L, 0L, true, null));
      assertThat(sameAccount.code()).isEqualTo("BAD_REQUEST");
      assertThat(sameAccount.message()).contains("不得相同").contains("hh-gov-gov-central");

      ToolResult preview =
          gmExecute(
              tool, transferArgs("gov-central", null, "gov-province", 1L, 0L, 0L, true, null));
      assertThat(preview.success()).as(preview.message()).isTrue();
      JsonNode previewView = JSON.readTree(preview.message());
      assertThat(previewView.get("from").get("unitId").asText()).isEqualTo("gov-central");
      assertThat(previewView.get("to").get("unitId").asText()).isEqualTo("gov-province");
      assertThat(previewView.get("from").get("available").get("grain").asLong())
          .as("tick0 中央国库粮可支配量 = 台账字面量 1,000,000")
          .isEqualTo(1_000_000L);
      assertThat(previewView.get("commandsPreview").get(0).get("type").asText())
          .isEqualTo("actor.RemitGovTreasury");
      assertThat(world.head()).as("preview 零写入").isEqualTo(1L);

      ToolResult insufficient =
          gmExecute(
              tool,
              transferArgs("gov-central", null, "gov-province", 1_000_001L, 0L, 0L, false, 1L));
      assertThat(insufficient.success()).as(insufficient.message()).isFalse();
      assertThat(insufficient.code()).isEqualTo("REJECTED");
      assertThat(insufficient.message())
          .as("域层具名余额不足：资源 + 请求 + 可用")
          .contains("grain")
          .contains("请求=1000001")
          .contains("可用=1000000");

      ToolResult clothInsufficient =
          gmExecute(
              tool, transferArgs("gov-central", null, "gov-province", 1L, 100_001L, 0L, false, 1L));
      assertThat(clothInsufficient.code()).isEqualTo("REJECTED");
      assertThat(clothInsufficient.message())
          .contains("cloth")
          .contains("请求=100001")
          .contains("可用=100000");
      assertThat(world.head()).as("余额不足整条拒 ⇒ 零 revision").isEqualTo(1L);

      var actors = GovZ6WorldFixture.actorSlice(world.state());
      var central = actors.accounts().get(new HouseholdAccountKey(CENTRAL_GOV_HOUSEHOLD));
      assertThat(central.balances().getOrDefault(EconomyCommodities.GRAIN, 0L))
          .as("拒后中央粮余额逐值不变")
          .isEqualTo(1_000_000L);
      assertThat(central.balances().getOrDefault(EconomyCommodities.CLOTH, 0L))
          .as("拒后中央布余额逐值不变")
          .isEqualTo(100_000L);
    }
  }

  @Test
  void transferTreasuryAppliesThreeResourcesInOneRevision() throws Exception {
    try (World world = start(tempDir.resolve("transfer-apply"), "transfer-apply")) {
      AgentTool tool = gmTool(world.shell(), GovTransferTreasuryTool.NAME);
      ToolResult applied =
          gmExecute(
              tool,
              transferArgs("gov-central", null, "gov-province", 1_000L, 2_000L, 3_000L, false, 1L));
      assertThat(applied.success()).as(applied.message()).isTrue();
      JsonNode view = JSON.readTree(applied.message());
      assertThat(view.get("submission").get("result").asText()).isEqualTo("committed");
      assertThat(world.head()).as("三资源一条命令 = 恰一条 revision").isEqualTo(2L);

      var actors = GovZ6WorldFixture.actorSlice(world.stateAt(2L));
      var central = actors.accounts().get(new HouseholdAccountKey(CENTRAL_GOV_HOUSEHOLD));
      var province =
          actors.accounts().get(new HouseholdAccountKey(GovernmentHouseholds.of("gov-province")));
      assertThat(central.balances().getOrDefault(EconomyCommodities.GRAIN, 0L))
          .as("中央粮 1,000,000 − 1,000")
          .isEqualTo(999_000L);
      assertThat(central.balances().getOrDefault(EconomyCommodities.CLOTH, 0L))
          .as("中央布 100,000 − 2,000")
          .isEqualTo(98_000L);
      assertThat(central.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L))
          .as("中央银 100,000 − 3,000")
          .isEqualTo(97_000L);
      assertThat(province.balances().getOrDefault(EconomyCommodities.GRAIN, 0L))
          .as("省粮 1,000,000 + 1,000")
          .isEqualTo(1_001_000L);
      assertThat(province.balances().getOrDefault(EconomyCommodities.CLOTH, 0L))
          .as("省布 100,000 + 2,000")
          .isEqualTo(102_000L);
      assertThat(province.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L))
          .as("省银 100,000 + 3,000")
          .isEqualTo(103_000L);
    }
  }

  // ── P2-11：assignPosts 负向（z3c2 §8-11~13）＋ 一条正向对照 ───────────────────────────

  @Test
  void assignPostsRejectsUnknownRoleLaborAndNonMemberHousehold() throws Exception {
    try (World world = start(tempDir.resolve("assign-guards"), "assign-guards")) {
      AgentTool tool = gmTool(world.shell(), GovAssignPostsTool.NAME);

      ToolResult ghost = gmExecute(tool, assignArgs("hh-ghost-assign", "SCRIBE", null, null, true));
      assertThat(ghost.code()).isEqualTo("BAD_REQUEST");
      assertThat(ghost.message()).contains("家户不存在").contains("hh-ghost-assign");

      ToolResult notInUnit =
          gmExecute(tool, assignArgs(EXTERNAL_PEASANT.value(), "SCRIBE", null, null, true));
      assertThat(notInUnit.code()).isEqualTo("BAD_REQUEST");
      assertThat(notInUnit.message())
          .as("不在 Unit.households ⇒ 具名拒 + 指路 expandHousehold/openPostsToMarket")
          .contains("不在 GOV 单位")
          .contains("simos.gov.expandHousehold")
          .contains("simos.gov.openPostsToMarket");

      ToolResult badRole =
          gmExecute(tool, assignArgs(CENTRAL_OFFICIAL.value(), "WIZARD", null, null, true));
      assertThat(badRole.code()).isEqualTo("BAD_REQUEST");
      assertThat(badRole.message()).contains("role 不是合法角色").contains("WIZARD");

      ToolResult zeroLabor =
          gmExecute(tool, assignArgs(CENTRAL_OFFICIAL.value(), "SCRIBE", null, 0L, true));
      assertThat(zeroLabor.code()).isEqualTo("BAD_REQUEST");
      assertThat(zeroLabor.message()).contains("laborMilli 必须 > 0");

      ToolResult overLabor =
          gmExecute(tool, assignArgs(CENTRAL_OFFICIAL.value(), "SCRIBE", null, 32_001L, true));
      assertThat(overLabor.code()).isEqualTo("BAD_REQUEST");
      assertThat(overLabor.message())
          .as("超出当前 Social 权威劳动（tick0 官吏户 = 32000）不得静默截断")
          .contains("超过该户当前 Social 权威劳动")
          .contains("32000");
      assertThat(world.head()).as("assign 全部负向 ⇒ 零 revision").isEqualTo(1L);

      Map<String, Object> applyArgs =
          assignArgs(CENTRAL_OFFICIAL.value(), "YAMEN", "tier-1", 32_000L, false);
      applyArgs.put("expectedRevision", 1L);
      ToolResult applied = gmExecute(tool, applyArgs);
      assertThat(applied.success()).as(applied.message()).isTrue();
      assertThat(world.head()).as("正向指派 = 恰一条 revision").isEqualTo(2L);
      SimulationState after = world.stateAt(2L);
      GovernmentFormation afterFormation = formation(after, CENTRAL);
      GovernmentPostOfHousehold post =
          afterFormation.governmentPostsOfHousehold().get(CENTRAL_OFFICIAL);
      assertThat(post).isNotNull();
      assertThat(post.role()).isEqualTo(StaffRole.YAMEN);
      assertThat(post.tierId()).isEqualTo("tier-1");
      assertThat(afterFormation.staff())
          .as("assignPosts 只写 posts/承诺，不改 stored staff（tick0 {POST=2}）")
          .containsEntry(StaffRole.POST, 2L);
      assertThat(
              govServiceCommitment(GovZ6WorldFixture.economySlice(after), CENTRAL_OFFICIAL)
                  .laborMilli())
          .isEqualTo(32_000L);
    }
  }

  // ── P2-12：expandHousehold 负向（z3c2 §8-15）────────────────────────────────────────

  @Test
  void expandHouseholdRejectsBadCountBadRoleAndGovWithoutJurisdiction() {
    try (World world = start(tempDir.resolve("expand-guards"), "expand-guards")) {
      // ★ Z7a：创世给 gov-central 也挂了省级辖区 capital-province（rate=0）⇒ 本用例要的"无辖区 GOV"
      //   必须显式清空（regions=[]），不再能默认依赖 bootstrap 状态。清空走真 GM 窄工具（命令形状未变）。
      ToolResult cleared =
          gmExecute(
              gmTool(world.shell(), UnitSetJurisdictionTool.NAME),
              Map.of(
                  "payloadJson",
                  ToolSupport.json(Map.of("unitId", "gov-central", "regions", List.of())),
                  "branch",
                  "main",
                  "expectedRevision",
                  1L));
      assertThat(cleared.success()).as(cleared.message()).isTrue();
      assertThat(world.head()).as("清空中央辖区 = 恰一条 revision").isEqualTo(2L);

      AgentTool tool = gmTool(world.shell(), GovExpandHouseholdTool.NAME);

      ToolResult badCount = gmExecute(tool, expandArgs("gov-province", "SCRIBE", 0L, null, true));
      assertThat(badCount.code()).isEqualTo("BAD_REQUEST");
      assertThat(badCount.message()).contains("count 必须 ≥ 1");

      ToolResult badRole = gmExecute(tool, expandArgs("gov-province", "WIZARD", 1L, null, true));
      assertThat(badRole.code()).isEqualTo("BAD_REQUEST");
      assertThat(badRole.message()).contains("role 不是合法角色").contains("WIZARD");

      ToolResult noJurisdiction =
          gmExecute(tool, expandArgs("gov-central", "SCRIBE", 1L, null, true));
      assertThat(noJurisdiction.code()).isEqualTo("BAD_REQUEST");
      assertThat(noJurisdiction.message())
          .as("清空后 gov-central 无辖区 ⇒ 无招募来源，具名拒（不部分抽取、零 revision）")
          .contains("jurisdiction");
      assertThat(world.head()).as("expand 全部负向 ⇒ 零 revision（清空辖区那条除外）").isEqualTo(2L);
    }
  }

  // ── P2-13：gov.info 视野收窄 / tick0 具名 unavailable（z3c2 §8-22~24）────────────────

  @Test
  void govInfoNarrowsDecisionMakerVisionAndNamesUnavailableReadings() throws Exception {
    try (World world = start(tempDir.resolve("gov-info"), "gov-info")) {
      Shell shell = world.shell();
      AgentTool info = gmTool(shell, GovInfoTool.NAME);
      DecisionCallerFactory factory = dmFactory(shell);

      JsonNode all = JSON.readTree(gmExecute(info, Map.of()).message());
      assertThat(all.get("governmentCount").asInt()).as("GM 省略 govUnitId = 全部 GOV").isEqualTo(2);
      assertThat(all.get("governments").get(0).get("unitId").asText())
          .as("按 unit id 稳定序（gov-central < gov-province）")
          .isEqualTo("gov-central");
      assertThat(all.get("governments").get(1).get("unitId").asText()).isEqualTo("gov-province");

      ToolResult missing = gmExecute(info, Map.of("govUnitId", "gov-missing"));
      assertThat(missing.success()).isFalse();
      assertThat(missing.code()).isEqualTo("BAD_REQUEST");
      assertThat(missing.message()).contains("gov-missing");

      JsonNode central =
          JSON.readTree(gmExecute(info, Map.of("govUnitId", "gov-central")).message())
              .get("governments")
              .get(0);
      assertThat(central.get("administrationPlanSource").asText()).isEqualTo("explicit");
      assertThat(central.get("budgetPolicySource").asText()).isEqualTo("explicit");
      assertThat(central.get("efficiency").get("available").asBoolean())
          .as("tick0 未推进 ⇒ 无当日 GovOfficeState")
          .isFalse();
      assertThat(central.get("efficiency").get("reason").asText()).isEqualTo("no-gov-office-state");
      assertThat(central.get("efficiency").has("efficiencyPerMille"))
          .as("读不到不得填 0（缺字段而不是 0 值）")
          .isFalse();
      assertThat(central.get("serviceFlow").get("available").asBoolean()).isFalse();
      assertThat(central.get("serviceFlow").get("reason").asText())
          .isEqualTo("gov-service-flow-unavailable-for-tick");
      assertThat(central.get("committedLabor").get("available").asBoolean()).isTrue();
      assertThat(central.get("committedLabor").get("households").size()).isEqualTo(1);

      // ★★ Z7d-1/Z7d-2：读口的键集合/取值口径 —— supply 两套口径 + underfed；committedLabor 逐户 satiety/
      //    effective/flee 字段；新增 desertion 块；remittance 块 rate=0/无周期读数。缺一个键 = 红（不是静默 0）。
      JsonNode supply = central.get("supply");
      assertThat(supply.get("available").asBoolean()).isTrue();
      assertThat(fieldNames(supply))
          .as("supply 必须同时给承诺与有效两套口径 + underfed")
          .contains(
              "securityLaborMilli",
              "paperworkLaborMilli",
              "securityCommittedLaborMilli",
              "paperworkCommittedLaborMilli",
              "securityEffectiveLaborMilli",
              "paperworkEffectiveLaborMilli",
              "underfedHouseholds",
              "underfed",
              "source");
      assertThat(supply.get("securityLaborMilli").asLong())
          .as("tick0 无饥饿 ⇒ 有效 = 承诺（16000）")
          .isEqualTo(16_000L);
      assertThat(supply.get("securityCommittedLaborMilli").asLong()).isEqualTo(16_000L);
      assertThat(supply.get("securityEffectiveLaborMilli").asLong()).isEqualTo(16_000L);
      assertThat(supply.get("underfed").asBoolean()).isFalse();

      JsonNode commRow = central.get("committedLabor").get("households").get(0);
      assertThat(fieldNames(commRow))
          .as("逐户行必须给 satiety/actual/effective/flee 全景（Z7d-1/Z7d-2）")
          .contains(
              "householdId",
              "laborMilli",
              "committedLaborMilli",
              "satietyPerMille",
              "actualLaborMilli",
              "effectiveLaborMilli",
              "underfed",
              "underfedReason",
              "fleeRatePerMille",
              "fleeRemainderMilli",
              "fleeRateTier",
              "lastFleeDay",
              "lastFleeCount",
              "lastFleeReason",
              "lastDriverDay",
              "lastDriverReason",
              "hasPost",
              "postScope",
              "role",
              "tierId",
              "tierKnown");
      assertThat(commRow.get("satietyPerMille").asLong())
          .as("tick0 吃饱 = 1000（缺键语义）")
          .isEqualTo(1000L);
      assertThat(commRow.get("fleeRatePerMille").asLong()).isZero();
      assertThat(commRow.get("underfed").asBoolean()).isFalse();
      assertThat(central.get("committedLabor").get("timing").asText()).contains("satietyPerMille");

      JsonNode desertion = central.get("desertion");
      assertThat(desertion.get("available").asBoolean()).isTrue();
      assertThat(fieldNames(desertion))
          .as("desertion 块字段齐全")
          .contains(
              "households",
              "maxFleeRatePerMille",
              "householdsWithFleeRate",
              "totalRemainderMilli",
              "lastFleeDay",
              "source");
      assertThat(desertion.get("maxFleeRatePerMille").asLong()).isZero();
      assertThat(desertion.get("householdsWithFleeRate").asLong()).isZero();
      assertThat(fieldNames(desertion.get("households").get(0)))
          .as("逐户 flee 行字段齐全")
          .contains(
              "householdId",
              "available",
              "fleeRatePerMille",
              "fleeRateTier",
              "fleeRemainderMilli",
              "lastDriverDay",
              "lastDriverReason",
              "lastFleeDay",
              "lastFleeCount",
              "lastFleeReason",
              "satietyPerMille");

      JsonNode remittance = central.get("remittance");
      assertThat(remittance.get("ratePerMilleToSuperior").asLong()).isZero();
      assertThat(remittance.get("cycleGrainCollectedMilli").asLong()).isZero();
      assertThat(remittance.get("cycleSilverCollectedMilli").asLong()).isZero();
      assertThat(remittance.get("lastCycleCloseDay").asLong()).isZero();
      assertThat(remittance.get("lastPaidGrainMilli").asLong()).isZero();
      assertThat(remittance.get("lastPaidSilverMilli").asLong()).isZero();
      assertThat(central.get("budgetPolicy").get("remittancePerMilleToSuperior").asLong()).isZero();

      ToolResult self =
          info.execute(
              dmContext(
                  factory, world.dm("dm-central"), world.state(), world.mapId(), info, Map.of()));
      assertThat(self.success()).as(self.message()).isTrue();
      JsonNode selfView = JSON.readTree(self.message());
      assertThat(selfView.get("governmentCount").asInt()).isEqualTo(1);
      assertThat(selfView.get("governments").get(0).get("unitId").asText())
          .as("决策人只能读自己所属 GOV")
          .isEqualTo("gov-central");

      ToolResult cross =
          info.execute(
              dmContext(
                  factory,
                  world.dm("dm-central"),
                  world.state(),
                  world.mapId(),
                  info,
                  Map.of("govUnitId", "gov-province")));
      assertThat(cross.code()).as("查别 GOV ⇒ REJECTED").isEqualTo("REJECTED");
      assertThat(cross.message()).contains("越权 GOV").contains("gov-province");

      ToolResult nation =
          info.execute(
              dmContext(
                  factory, world.dm("dm-nation"), world.state(), world.mapId(), info, Map.of()));
      assertThat(nation.code()).isEqualTo("REJECTED");
      assertThat(nation.message()).contains("归属").contains("Nation");

      ToolResult army =
          info.execute(
              dmContext(
                  factory, world.dm("dm-army"), world.state(), world.mapId(), info, Map.of()));
      assertThat(army.code()).isEqualTo("REJECTED");
      assertThat(army.message()).contains("归属").contains("Army");
    }
  }

  // ── P3-14（z4 §7-11）：gov service unit 跨切片一致性先 ERROR 再 ISE ──────────────────

  @Test
  void serviceUnitConsistencyLogsFaultBeforeFailingClosed() {
    SimulationState state = GovZ6WorldFixture.smallWorldState("Map1");
    EconomyData economy = GovZ6WorldFixture.economySlice(state);
    UnitState units = GovZ6WorldFixture.unitSlice(state);

    GovernmentServiceUnitConsistency.requireConsistent(economy, units); // 真创世必须自洽

    UnitState withoutCentral = new UnitState(Map.of(PROVINCE, units.units().get(PROVINCE)));
    List<GovernmentServiceUnitConsistency.Mismatch> missingUnit =
        GovernmentServiceUnitConsistency.mismatches(economy, withoutCentral);
    assertThat(missingUnit).as("经济 unit operator=hh-gov-gov-central 但 unit 切片缺该 GOV").hasSize(1);
    assertThat(missingUnit.get(0).kind()).isEqualTo("GOV_SERVICE_GOV_UNIT_MISSING");
    assertThat(missingUnit.get(0).detail()).contains("gov-central");

    try (AppLogCapture log = AppLogCapture.appTime()) {
      assertThatThrownBy(
              () -> GovernmentServiceUnitConsistency.requireConsistent(economy, withoutCentral))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GOV_SERVICE_GOV_UNIT_MISSING");
      assertThat(
              log.hasError(
                  "GOV_SERVICE_UNIT_CONSISTENCY_FAULT",
                  "count=1",
                  "first=GOV_SERVICE_GOV_UNIT_MISSING"))
          .as("契约故障必须先落 ERROR（count + first）再抛 ISE")
          .isTrue();
    }

    Unit central = units.units().get(CENTRAL);
    Map<UnitId, Unit> replaced = new LinkedHashMap<>(units.units());
    replaced.put(
        CENTRAL,
        new Unit(
            central.id(),
            central.name(),
            central.parent(),
            central.position(),
            central.equipment(),
            central.speed(),
            central.mobilityPerMille(),
            central.movement(),
            central.status(),
            central.attached(),
            central.offset(),
            central.rejoinTarget(),
            central.visionRadius(),
            central.jurisdiction(),
            Optional.empty(),
            central.stateDescriptions(),
            List.of()));
    UnitState noFormation = new UnitState(replaced);
    List<GovernmentServiceUnitConsistency.Mismatch> missingFormation =
        GovernmentServiceUnitConsistency.mismatches(economy, noFormation);
    assertThat(missingFormation).hasSize(1);
    assertThat(missingFormation.get(0).kind()).isEqualTo("GOV_SERVICE_GOV_FORMATION_MISSING");

    try (AppLogCapture log = AppLogCapture.appTime()) {
      assertThatThrownBy(
              () -> GovernmentServiceUnitConsistency.requireConsistent(economy, noFormation))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GOV_SERVICE_GOV_FORMATION_MISSING");
      assertThat(
              log.hasError(
                  "GOV_SERVICE_UNIT_CONSISTENCY_FAULT",
                  "count=1",
                  "first=GOV_SERVICE_GOV_FORMATION_MISSING"))
          .as("有单位但无 GovernmentFormation 也必须先 ERROR 再 ISE")
          .isTrue();
    }
  }

  // ── P3-15（z4 §7-14）：tierId 悬空一致性先 ERROR 再 ISE ────────────────────────────

  @Test
  void postTierConsistencyLogsFaultBeforeFailingClosed() {
    SimulationState state = GovZ6WorldFixture.smallWorldState("Map1");
    EconomyData economy = GovZ6WorldFixture.economySlice(state);
    UnitState units = GovZ6WorldFixture.unitSlice(state);

    GovernmentPostTierConsistency.requireConsistent(economy, units); // 真创世必须自洽

    GovAdministrationPlan danglingPlan =
        new GovAdministrationPlan(
            16_000L,
            16_000L,
            List.of(
                new GovPostTier("tier-1", 1_000L, 0L),
                new GovPostTier("tier-1b", 0L, 1_000L),
                new GovPostTier("tier-1c", 500L, 500L)),
            1_000L,
            1_000L,
            1_000L,
            1_000L,
            1L);
    // ★★ 追加裁定 12（§21.9 D-1）：档位目录的唯一权威 = economy 的政府记录 ⇒ 悬空目录也造在 economy 上。
    //   GovState 只剩 offices/remittanceStates（本用例只需要一个"有该 GOV 记录、目录不含 tier-3"的 economy）。
    io.mosire.simos.economy.api.id.GovernmentId centralId = GovernmentIds.ofUnit(CENTRAL.value());
    Map<GovernmentId, Government> governments = new LinkedHashMap<>(economy.governments());
    governments.put(
        centralId,
        economy.governmentOrDefault(CENTRAL.value()).withAdministrationPlan(danglingPlan));
    EconomyData dangling = economy.withGovernments(governments);
    List<GovernmentPostTierConsistency.Mismatch> mismatches =
        GovernmentPostTierConsistency.mismatches(dangling, units);
    assertThat(mismatches).as("岗位 tier-3 不在该 GOV 计划目录（tier-1/tier-1b/tier-1c）里").hasSize(1);
    assertThat(mismatches.get(0).kind()).isEqualTo("GOV_POST_TIER_UNKNOWN");
    assertThat(mismatches.get(0).detail()).contains("tier-3").contains("hh-unit:gov-central");

    try (AppLogCapture log = AppLogCapture.appTime()) {
      assertThatThrownBy(() -> GovernmentPostTierConsistency.requireConsistent(dangling, units))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GOV_POST_TIER_UNKNOWN");
      assertThat(
              log.hasError(
                  "GOV_POST_TIER_CONSISTENCY_FAULT", "count=1", "first=GOV_POST_TIER_UNKNOWN"))
          .as("契约故障必须先落 ERROR（count + first）再抛 ISE")
          .isTrue();
    }
  }

  // ── P3-16（z4 §7-14）：政府家户闭环一致性先 ERROR 再 ISE ────────────────────────────

  @Test
  void householdWiringConsistencyLogsFaultBeforeFailingClosed() {
    SimulationState state = GovZ6WorldFixture.smallWorldState("Map1");
    EconomyData economy = GovZ6WorldFixture.economySlice(state);
    SocialData social = GovZ6WorldFixture.socialSlice(state);
    UnitState units = GovZ6WorldFixture.unitSlice(state);

    GovernmentHouseholdWiring.requireConsistent(economy, social, units); // 真创世必须自洽

    Map<HouseholdId, Household> households = new LinkedHashMap<>(social.households());
    households.remove(CENTRAL_GOV_HOUSEHOLD);
    SocialData withoutCentralGov = social.withHouseholds(households);
    List<GovernmentHouseholdWiring.Mismatch> mismatches =
        GovernmentHouseholdWiring.mismatches(economy, withoutCentralGov, units);
    assertThat(mismatches).as("删掉一个政府家户 ⇒ 闭环缺一边").hasSize(1);
    assertThat(mismatches.get(0).kind()).isEqualTo("GOV_HOUSEHOLD_NOT_IN_SOCIAL");
    assertThat(mismatches.get(0).detail()).contains("hh-gov-gov-central");

    try (AppLogCapture log = AppLogCapture.appTime()) {
      assertThatThrownBy(
              () -> GovernmentHouseholdWiring.requireConsistent(economy, withoutCentralGov, units))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GOV_HOUSEHOLD_NOT_IN_SOCIAL");
      assertThat(
              log.hasError(
                  "GOV_HOUSEHOLD_WIRING_CONSISTENCY_FAULT",
                  "count=1",
                  "first=GOV_HOUSEHOLD_NOT_IN_SOCIAL"))
          .as("契约故障必须先落 ERROR（count + first）再抛 ISE")
          .isTrue();
    }
  }

  // ── Z3d §7.2-11：裸命令边界（结构接受、但供给/工资桥不伪造读数）────────────────────

  @Test
  void rawAssignExternalGovPostIsStructurallyAcceptedWithoutFakeSupplyOrSalaryRule() {
    HouseholdId ghost = HouseholdId.parse("hh-ghost-no-social-row");
    try (World world = start(tempDir.resolve("z3d-raw-boundary"), "z3d-raw-boundary")) {
      Shell shell = world.shell();
      world.advance(0L, 1L); // offices 非空，工资桥 deriveReport 才有 GOV 面
      long headBefore = world.head();
      assertThat(headBefore).as("推进 1 天 = 一条 revision").isEqualTo(2L);

      GovernmentServiceLaborBridge.Supply beforeSupply =
          GovernmentServiceLaborBridge.supply(
              GovZ6WorldFixture.economySlice(world.state()),
              CENTRAL,
              formation(world.state(), CENTRAL),
              1L);
      GovSalaryRuleBridge.Report beforeReport =
          GovSalaryRuleBridge.deriveReport(
              GovZ6WorldFixture.unitSlice(world.state()),
              GovZ6WorldFixture.economySlice(world.state()),
              1L);
      assertThat(beforeReport.rules()).as("正向对照：既有官方承诺产生工资规则，桥不是恒空").isNotEmpty();

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("govUnitId", "gov-central");
      payload.put("householdId", ghost.value());
      payload.put("role", "SCRIBE");
      payload.put("reason", "z6:raw-boundary");
      CommandResult result =
          shell
              .coreSimos()
              .submit(
                  new CommandEnvelope(
                      "cmd-z6-raw-external-post",
                      "corr-z6-raw-external-post",
                      "test:z6",
                      MAIN,
                      new RevisionId(headBefore),
                      AssignExternalGovPostHandler.TYPE,
                      ToolSupport.json(payload)));
      assertThat(result)
          .as("裸 simos.command.submit 不经工具预检 ⇒ unit 侧结构上接受（残余边界）")
          .isInstanceOf(CommandResult.Committed.class);
      assertThat(((CommandResult.Committed) result).ref().revision().value()).isEqualTo(3L);
      assertThat(world.head()).isEqualTo(3L);

      SimulationState after = world.stateAt(3L);
      GovernmentFormation afterFormation = formation(after, CENTRAL);
      assertThat(afterFormation.externalPosts()).containsKey(ghost);

      EconomyData economyAfter = GovZ6WorldFixture.economySlice(after);
      GovernmentServiceLaborBridge.Supply afterSupply =
          GovernmentServiceLaborBridge.supply(economyAfter, CENTRAL, afterFormation, 1L);
      assertThat(afterSupply).as("该户无承诺 ⇒ 供给桥不因外岗多出任何供给（bridge 读数逐值不变）").isEqualTo(beforeSupply);
      assertThat(GovernmentServiceLaborBridge.committedLaborByHousehold(economyAfter, CENTRAL, 1L))
          .as("裸挂外岗不写承诺")
          .doesNotContainKey(ghost);

      GovSalaryRuleBridge.Report afterReport =
          GovSalaryRuleBridge.deriveReport(GovZ6WorldFixture.unitSlice(after), economyAfter, 1L);
      assertThat(afterReport.rules())
          .as("无承诺 ⇒ 不伪造工资规则")
          .noneMatch(
              rule ->
                  rule.payee().map(payee -> payee.equals(ghost)).orElse(false)
                      || rule.payer().equals(ghost));
      assertThat(afterReport.rules())
          .as("正向对照：官方官吏户的工资规则仍在（证明 noneMatch 有判别力）")
          .anyMatch(
              rule -> rule.payee().map(payee -> payee.equals(CENTRAL_OFFICIAL)).orElse(false));
    }
  }

  // ── 夹具 / 小件 ─────────────────────────────────────────────────────────────────────

  /** 真壳小世界创世：sd 片替换为带四个决策人的状态，再走真 {@code bootstrapGenesis}。 */
  private static World start(Path storeDir, String label) {
    return start(storeDir, label, UnaryOperator.identity());
  }

  private static World start(Path storeDir, String label, UnaryOperator<SimulationState> mutate) {
    ShellConfig config =
        ShellConfig.defaults(storeDir).withPorts(0, 0).withWorldId(WorldRegistry.SMALL_WORLD);
    Shell shell = Shell.start(config);
    try {
      SimulationState state = SmallWorld.state(config.mapId());
      StateMeta meta = state.meta();
      state =
          GovZ6WorldFixture.withModule(
              state,
              "ga",
              new GaSnapshot(
                  meta.ref(),
                  meta.timestamp(),
                  GaState.empty().withDecisionMakers(decisionMakers())));
      // ★★ 2026-10-13 收尾测试第 3 层：**归属行真的落表**（乙收尾起归属归模块、按 dmId 索引）——
      //   `dm-central`/`dm-province` → 各自 GOV 单位（gov 表）、`dm-nation` → 国家（gov 表）、
      //   `dm-army` → 军队（army 表）。只造裸决策人时每个都被判成"没有归属"，
      //   工具拒因退化成「归属: 」空串 ⇒ 本类四条断言（含"拒因必须写清归属"）全部落空。
      state = withAffiliations(state);
      state = mutate.apply(state);
      shell.coreSimos().bootstrapGenesis(state);
      return new World(shell, config.mapId(), label);
    } catch (RuntimeException e) {
      shell.close();
      throw e;
    }
  }

  /**
   * ★★ <b>2026-10-13 收尾测试第 3 层</b>：把四条**归属行**写进各模块自己的表（形制与 {@code
   * gov.AssignDecisionMakerGov/Nation}、{@code army.AssignDecisionMakerArmy} 逐字同形；手搭创世没有命令总线可走）。
   *
   * <p>★ 小世界创世只造 GOV 单位（{@code gov-central}/{@code gov-province}），<b>没有</b>国家与军队 ⇒ 本方法一并把 {@code
   * FRA} 国与 {@code a1} 军造出来（否则那两条归属行会被读侧的 fail-closed 裁掉，与"没有归属"不可区分）。
   */
  private static SimulationState withAffiliations(SimulationState state) {
    StateMeta meta = state.meta();
    GovState gov = ((GovSnapshot) state.module("gov").orElseThrow()).state();
    NationId nation = new NationId(NATION);
    Map<NationId, Nation> nations = new LinkedHashMap<>(gov.nations());
    nations.put(nation, new Nation(nation, "国家 " + NATION, new RegionId("r-1"), 0));
    GovState nextGov =
        gov.withNations(nations)
            .withGovAffiliations(
                Map.of(
                    new DecisionMakerId("dm-central"), CENTRAL,
                    new DecisionMakerId("dm-province"), PROVINCE))
            .withNationAffiliations(Map.of(new DecisionMakerId("dm-nation"), nation));
    ArmyData army = ((ArmySnapshot) state.module("army").orElseThrow()).data();
    ArmyId a1 = new ArmyId(ARMY);
    Map<ArmyId, Army> armies = new LinkedHashMap<>(army.armies());
    armies.put(a1, new Army(a1, Optional.empty(), CENTRAL, "军队 " + ARMY));
    ArmyData nextArmy =
        army.withArmies(armies).withArmyAffiliations(Map.of(new DecisionMakerId("dm-army"), a1));
    SimulationState withGov =
        GovZ6WorldFixture.withModule(
            state, "gov", new GovSnapshot(meta.ref(), meta.timestamp(), nextGov));
    return GovZ6WorldFixture.withModule(
        withGov, "army", new ArmySnapshot(meta.ref(), meta.timestamp(), nextArmy));
  }

  private static Map<DecisionMakerId, DecisionMaker> decisionMakers() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(new DecisionMakerId("dm-central"), maker("dm-central"));
    makers.put(new DecisionMakerId("dm-province"), maker("dm-province"));
    makers.put(new DecisionMakerId("dm-nation"), maker("dm-nation"));
    makers.put(new DecisionMakerId("dm-army"), maker("dm-army"));
    return makers;
  }

  private static DecisionMaker maker(String id) {
    return new DecisionMaker(new DecisionMakerId(id), Set.of(), AccessLimit.empty(), 1);
  }

  /** 一座真壳小世界：创世已落在 (main, 1)。 */
  private static final class World implements AutoCloseable {

    private final Shell shell;
    private final String mapId;
    private final String label;
    private long advanceSeq;

    private World(Shell shell, String mapId, String label) {
      this.shell = shell;
      this.mapId = mapId;
      this.label = label;
    }

    Shell shell() {
      return shell;
    }

    String mapId() {
      return mapId;
    }

    long head() {
      return shell.coreSimos().head(MAIN).orElseThrow().value();
    }

    SimulationState state() {
      return stateAt(head());
    }

    SimulationState stateAt(long revision) {
      return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(revision)));
    }

    long advance(long fromTick, long toTick) {
      long head = head();
      shell.advanceAndDrain(
          new AdvanceTime(
              "cmd-" + label + "-" + (advanceSeq++),
              "corr-" + label + "-" + advanceSeq,
              label,
              MAIN,
              new RevisionId(head),
              new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick)))));
      return head();
    }

    DecisionMaker dm(String id) {
      return ((GaSnapshot) state().module("ga").orElseThrow())
          .state()
          .decisionMakers()
          .get(new DecisionMakerId(id));
    }

    @Override
    public void close() {
      shell.close();
    }
  }

  /** GM 直调：system 权限（照 {@code GovGenesisZ6Test.executeRaw}）。 */
  private static ToolResult gmExecute(AgentTool tool, Map<String, Object> args) {
    return tool.execute(
        new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
            .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources())));
  }

  /** 决策人直调（不走审批）：身份 + 权限组 + 资源判定者，与生产调用同源。 */
  /**
   * 决策人调用者工厂：白名单**从这套装配的决策人桶派生**（生产路径的同一个纯函数；B4b 起生产里没有手抄清单）。
   *
   * <p>★ 以前这里写 {@code defaults(shell.toolAuthorizer())}，靠 {@code DecisionCallerFactory} 里的静态 44
   * 条清单取名字； 那份清单已删，故测试自己把"从桶派生"这一步显式写出来（与 {@code Shell} 的装配逐字同形）。
   */
  private static DecisionCallerFactory dmFactory(Shell shell) {
    return DecisionCallerFactory.defaults(
        shell.toolAuthorizer(), ToolBuckets.namesOf(shell.toolsFor(Role.DECISION_AGENT)));
  }

  private static ToolContext dmContext(
      DecisionCallerFactory factory,
      DecisionMaker dm,
      SimulationState state,
      String mapId,
      AgentTool tool,
      Map<String, Object> args) {
    ToolContext base = factory.callerFor(dm, state, mapId);
    return new ToolContext(base.caller(), base.permissions(), base.config(), args, base.identity())
        .withResources(ResourceAuthorizer.of(base.permissions(), tool.resources()));
  }

  private static AgentTool gmTool(Shell shell, String name) {
    return shell.toolRegistry().find(name).orElseThrow();
  }

  /** JSON 对象的键集合（保插入序）—— 键集合断言用，避免每处手抄三行。 */
  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static AgentTool dmTool(Shell shell, String name) {
    return shell.toolsFor(Role.DECISION_AGENT).stream()
        .filter(tool -> tool.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static ApprovalRequest awaitPending(World world) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      List<ApprovalRequest> pending = world.shell().pendingApprovals().pending();
      if (!pending.isEmpty()) {
        return pending.get(0);
      }
      Thread.sleep(10);
    }
    throw new AssertionError("DM 调用既未进审批、也未在 10s 内结束——审批链装配异常");
  }

  /** 造一个只在 Social 存在、economy 没有 class 行的"幽灵户"（不动 economy，避开其跨表不变量）。 */
  private static SimulationState withGhostSocialHousehold(
      SimulationState state, HouseholdId household) {
    SocialData social = GovZ6WorldFixture.socialSlice(state);
    PeopleLotId ghostLot = PeopleLotId.parse("rural:0_0:MALE:ghost");
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(social.groups());
    groups.put(ghostLot, new PopulationGroup(ghostLot, Sex.MALE, 5L, 30L * 365L, 0L));
    Map<HouseholdId, Household> households = new LinkedHashMap<>(social.households());
    households.put(
        household,
        new Household(
            household,
            new HouseholdLocation.Hex(new HexCoord(0, 0)),
            new HouseholdProfile("幽灵户", null, Map.of()),
            Map.of(ghostLot, 5L),
            new HouseholdVitalRates(List.of())));
    SocialData modified = social.withGroupsAndHouseholds(groups, households);
    return GovZ6WorldFixture.withModule(
        state,
        "social",
        new SocialSnapshot(state.meta().ref(), state.meta().timestamp(), modified));
  }

  private static GovernmentFormation formation(SimulationState state, UnitId govId) {
    return (GovernmentFormation)
        GovZ6WorldFixture.unitSlice(state).units().get(govId).module().orElseThrow();
  }

  private static Map<UnitId, List<HouseholdId>> unitHouseholds(SimulationState state) {
    Map<UnitId, List<HouseholdId>> out = new LinkedHashMap<>();
    for (Unit unit : GovZ6WorldFixture.unitSlice(state).units().values()) {
      out.put(unit.id(), unit.households());
    }
    return out;
  }

  private static HouseholdLaborCommitment govServiceCommitment(
      EconomyData economy, HouseholdId household) {
    return economy.allocations().values().stream()
        .filter(commitment -> commitment.kind() == LaborCommitmentKind.GOV_SERVICE)
        .filter(commitment -> commitment.household().equals(household))
        .findFirst()
        .orElseThrow();
  }

  /** 该 GOV 行政服务 unit（operator = hh-gov-&lt;govId&gt; 的唯一 office unit；Z5 创世事实）。 */
  private static ProductionUnitId serviceUnitOf(EconomyData economy, UnitId govId) {
    ActorRef operator = HouseholdActors.of(GovernmentHouseholds.of(govId.value()));
    return economy.units().values().stream()
        .filter(process -> process.operator().equals(operator))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private static Map<String, Object> openPostsArgs(
      String householdId, String tierId, Long laborMilli, boolean preview) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("govUnitId", "gov-central");
    args.put("householdId", householdId);
    args.put("role", "SCRIBE");
    if (tierId != null) {
      args.put("tierId", tierId);
    }
    if (laborMilli != null) {
      args.put("laborMilli", laborMilli);
    }
    args.put("reason", "z6:open-posts");
    args.put("preview", preview);
    return args;
  }

  private static Map<String, Object> assignArgs(
      String householdId, String role, String tierId, Long laborMilli, boolean preview) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("govUnitId", "gov-central");
    args.put("householdId", householdId);
    args.put("role", role);
    if (tierId != null) {
      args.put("tierId", tierId);
    }
    if (laborMilli != null) {
      args.put("laborMilli", laborMilli);
    }
    args.put("reason", "z6:assign-posts");
    args.put("preview", preview);
    return args;
  }

  private static Map<String, Object> expandArgs(
      String govUnitId, String role, Long count, String tierId, boolean preview) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("govUnitId", govUnitId);
    args.put("role", role);
    args.put("count", count);
    if (tierId != null) {
      args.put("tierId", tierId);
    }
    args.put("reason", "z6:expand-household");
    args.put("preview", preview);
    return args;
  }

  private static Map<String, Object> transferArgs(
      String fromGovId,
      String fromHousehold,
      String toGovId,
      Long grain,
      Long cloth,
      Long money,
      boolean preview,
      Long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("toGovId", toGovId);
    if (fromGovId != null) {
      args.put("fromGovId", fromGovId);
    }
    if (fromHousehold != null) {
      args.put("fromHousehold", fromHousehold);
    }
    args.put("grain", grain);
    args.put("cloth", cloth);
    args.put("money", money);
    args.put("reason", "z6:transfer-treasury");
    args.put("preview", preview);
    if (expectedRevision != null) {
      args.put("expectedRevision", expectedRevision);
    }
    return args;
  }

  /** 编制计划载荷（{@code unitId == null} = 决策人省略、由身份覆盖）。 */
  private static String establishmentPayload(
      String unitId, long plannedLaborMilli, long modifierPerMille, long k, String postTiersJson) {
    String unitIdField = unitId == null ? "" : "\"unitId\":\"" + unitId + "\",";
    return "{"
        + unitIdField
        + "\"securityPlannedLaborMilli\":"
        + plannedLaborMilli
        + ",\"paperworkPlannedLaborMilli\":"
        + plannedLaborMilli
        + ",\"postTiers\":"
        + postTiersJson
        + ",\"securitySupplyStaticModifierPerMille\":"
        + modifierPerMille
        + ",\"paperworkSupplyStaticModifierPerMille\":"
        + modifierPerMille
        + ",\"securityDemandStaticModifierPerMille\":"
        + modifierPerMille
        + ",\"paperworkDemandStaticModifierPerMille\":"
        + modifierPerMille
        + ",\"supernumerarySqrtCoefficient\":"
        + k
        + "}";
  }

  private static String budgetPayload(
      String unitId, String categoriesJson, long salaryGrain, long salarySilver) {
    return "{"
        + "\"unitId\":\""
        + unitId
        + "\",\"orderedCategories\":"
        + categoriesJson
        + ",\"officialSalaryRule\":{\"grainMilliPerCommittedHour\":"
        + salaryGrain
        + ",\"silverMilliPerCommittedHour\":"
        + salarySilver
        + "}}";
  }
}
