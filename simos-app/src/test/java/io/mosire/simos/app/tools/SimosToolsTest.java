package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.render.ArtifactStore;
import io.mosire.simos.app.render.RenderCache;
import io.mosire.simos.app.render.RenderRequest;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.MapOverlapsTool;
import io.mosire.simos.app.tools.read.MapRenderTool;
import io.mosire.simos.app.tools.write.MapCreateRegionTool;
import io.mosire.simos.app.tools.write.MapDeleteRegionTool;
import io.mosire.simos.app.tools.write.MapRandomizeRegionTool;
import io.mosire.simos.app.tools.write.MapRegisterPathwayGroupTool;
import io.mosire.simos.app.tools.write.MapRenameRegionTool;
import io.mosire.simos.app.tools.write.MapSetEdgeTool;
import io.mosire.simos.app.tools.write.MapSetTerrainTool;
import io.mosire.simos.app.tools.write.MapUpdateRegionTool;
import io.mosire.simos.app.tools.write.SdAddCombatStageTool;
import io.mosire.simos.app.tools.write.SdCancelEffectTool;
import io.mosire.simos.app.tools.write.SdCommitCombatOutcomeTool;
import io.mosire.simos.app.tools.write.SdCreateArmyTool;
import io.mosire.simos.app.tools.write.SdCreateCombatTool;
import io.mosire.simos.app.tools.write.SdCreateDecisionMakerTool;
import io.mosire.simos.app.tools.write.SdCreateNationTool;
import io.mosire.simos.app.tools.write.SdPutInfoTool;
import io.mosire.simos.app.tools.write.SdRecordCasualtiesTool;
import io.mosire.simos.app.tools.write.SdRecordDiplomaticEventTool;
import io.mosire.simos.app.tools.write.SdRegisterEffectTool;
import io.mosire.simos.app.tools.write.SdSetArmyMasterGovTool;
import io.mosire.simos.app.tools.write.SdSetDecisionMakerProviderTool;
import io.mosire.simos.app.tools.write.SdSetDiplomaticRelationTool;
import io.mosire.simos.app.tools.write.SdSetStageOutcomeTableTool;
import io.mosire.simos.app.tools.write.UnitAdjustCompositionTool;
import io.mosire.simos.app.tools.write.UnitApplyCasualtiesTool;
import io.mosire.simos.app.tools.write.UnitAttachTool;
import io.mosire.simos.app.tools.write.UnitCancelRouteTool;
import io.mosire.simos.app.tools.write.UnitCreateCommandChainTool;
import io.mosire.simos.app.tools.write.UnitCreateTool;
import io.mosire.simos.app.tools.write.UnitDetachTool;
import io.mosire.simos.app.tools.write.UnitDisbandTool;
import io.mosire.simos.app.tools.write.UnitMergeFormationTool;
import io.mosire.simos.app.tools.write.UnitPlaceAtTool;
import io.mosire.simos.app.tools.write.UnitPlanRouteTool;
import io.mosire.simos.app.tools.write.UnitPlanSparseRouteTool;
import io.mosire.simos.app.tools.write.UnitRenameTool;
import io.mosire.simos.app.tools.write.UnitReparentSubtreeTool;
import io.mosire.simos.app.tools.write.UnitReparentTool;
import io.mosire.simos.app.tools.write.UnitSetArmyFormationTool;
import io.mosire.simos.app.tools.write.UnitSetCompositionTool;
import io.mosire.simos.app.tools.write.UnitSetFormationOffsetTool;
import io.mosire.simos.app.tools.write.UnitSetGovFormationTool;
import io.mosire.simos.app.tools.write.UnitSetGovPolicyTool;
import io.mosire.simos.app.tools.write.UnitSetGovSuperiorTool;
import io.mosire.simos.app.tools.write.UnitSetJurisdictionTool;
import io.mosire.simos.app.tools.write.UnitSetRejoinTargetTool;
import io.mosire.simos.app.tools.write.UnitSetStateDescriptionTool;
import io.mosire.simos.app.tools.write.UnitSetStatusTool;
import io.mosire.simos.app.tools.write.UnitSetTaxRateTool;
import io.mosire.simos.app.tools.write.UnitSplitFormationTool;
import io.mosire.simos.app.tools.write.UnitUpdateCommandChainTool;
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
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.map.CityId;
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
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.unit.CompositionEntry;
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
import java.util.ArrayList;
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
 * 工具集验收（M5 T5；D1–D5 后）：注册表 126 条（GM 桶全量 = 32 读 + 94 写）/ catalog 与注册面一致（R5）/ 写工具身份注入（R4）/ 读工具与
 * {@code QueryService} 逐值 对拍 / 拒绝与冲突不留 revision。
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

  /** M4 夹具里的区域（两格：H11+H12）。 */
  private static final RegionId REGION = new RegionId("r-m4");

  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 {@code agent:external-mcp} 不同，让"写死成别的值"这类变异当场现形（R4）。 */
  private static final String TEST_INITIATOR = "agent:t5-test";

  /** 读工具名单（32 条）：读闸**按名字选**，不用索引切片。 */
  private static final List<String> READ_TOOL_NAMES =
      List.of(
          "simos.command.catalog",
          "simos.state.resolve",
          "simos.state.facets",
          "simos.timeline.branches",
          "simos.timeline.revisions",
          "simos.map.overview",
          "simos.map.hex",
          "simos.map.region",
          // ★ 用户 2026-10-02：区域重合检测（只给 GM 桶，见 GM_ONLY_READ_NAMES）。
          "simos.map.overlaps",
          // ★ P3（2026-10-01）：省份划分建议（只给 GM 桶，见 GM_ONLY_READ_NAMES）。
          "simos.province.divide",
          "simos.map.path",
          "simos.map.block",
          "simos.unit.list",
          "simos.unit.get",
          "simos.social.population",
          "simos.economy.hex",
          "simos.economy.ownership",
          "simos.map.render",
          "simos.sd.decision-makers",
          "simos.sd.decision-maker",
          // ★ P7c（2026-10-01）：决策记录清单（只给 GM 桶）。
          "simos.sd.directives",
          "simos.sd.combats",
          // ★★ D5（2026-10-02 / R6）：外交关系边 + 外交事件（四桶共享读）。
          "simos.sd.diplomacy",
          "simos.sd.diplomatic-events",
          // ★★ D1（2026-10-02 / D-012）：Army 交战记录读口（四桶共享；逐格可见性过滤）。
          "simos.army.combats",
          "simos.army.combat",
          "simos.sd.verdicts",
          "simos.gm.tool-usage",
          // ★ P7b（2026-10-01）：待裁决审批清单（只给 GM 桶）。
          "simos.gm.approvals",
          "simos.llm.providers",
          "simos.skill",
          // ★ C5b（2026-10-02）：历法/气候信息（四桶共享读）。
          "simos.calendar.info");

  /** ★ **只给 GM 桶的读工具**（实现 {@code GmOnlyRead} 的那些，12 条）。 */
  private static final List<String> GM_ONLY_READ_NAMES =
      List.of(
          "simos.map.path",
          "simos.map.block",
          "simos.map.overlaps",
          "simos.province.divide",
          "simos.sd.decision-makers",
          "simos.sd.decision-maker",
          "simos.sd.directives",
          "simos.sd.verdicts",
          "simos.economy.ownership",
          "simos.gm.tool-usage",
          "simos.gm.approvals",
          "simos.llm.providers");

  /**
   * 非窄写写工具（34 条）：**只有 GM 组有**（MCP 与 GM Agent 同权限级）。
   *
   * <p>★ 它们**不是窄写**：不继承 {@code AbstractNarrowWriteTool} ⇒ 窄写扫描器（按 {@code tools/write}
   * 目录扫源码）**扫不到**它们；判"窄写是否都挂上了"时必须先把这 34 条从差集里扣掉。
   */
  private static final List<String> NON_NARROW_WRITE_NAMES =
      List.of(
          "simos.command.submit",
          "simos.advance",
          "simos.fork",
          // ★ P7a（2026-10-01）：显式名单批量派决策人。
          "simos.sd.run-decision-makers",
          "sd.AdjudicateTick",
          "sd.RejectDirective",
          "sd.VoidAdjudication",
          "simos.worldgen.initialize",
          // ★ P1a1/P1b1/P1b2 + P3（2026-10-01）：区域/省组合写。
          "simos.region.seed",
          "simos.region.clearData",
          "simos.region.clearStructures",
          "simos.province.apply",
          "simos.province.assignCities",
          // ★ 阶段 10b-ii/13A（2026-10-01）：GOV 组合写。
          "simos.gov.createOffice",
          "simos.gov.recruit",
          "simos.gov.dismiss",
          "simos.gov.selectExaminees",
          "simos.gov.dispatchTeam",
          "simos.gov.absorbUnit",
          "simos.gov.retireStaff",
          // ★ R3a/R4：国库上缴 / 配满。
          "simos.gov.remit",
          "simos.gov.applyStaffing",
          // ★ 辖区阶段 6–8：levy/debt/组军。
          "simos.unit.levyRegion",
          "simos.unit.issueDebt",
          "simos.unit.repayDebt",
          "simos.unit.raiseUnit",
          "simos.unit.spawnArmy",
          "simos.army.assignGov",
          // ★ D4/D3a（2026-10-02）：Army 编排层组合工具。
          "simos.army.startCombat",
          "simos.army.resolveCombat",
          "simos.army.formatUnit",
          "simos.economy.adjust",
          "simos.gm.approve",
          // ★ C5b（2026-10-02）：历法/气候配置（GM 写；不继承窄写基类）。
          "simos.calendar.configure");

  /** M1 的 8 条 map 窄写：**只进 GM 组**（= MCP 口），**不进**决策人组（决策人不能直接改数据）。 */
  private static final List<String> MAP_WRITE_NAMES =
      List.of(
          "map.SetTerrain",
          "map.SetEdge",
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.RenameRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup");

  /**
   * unit 的 28 条窄写：**只进 GM 组**。前 24 条 {@code name() == commandType()}；D3a/D1 新增的三条 （set-composition
   * / set-state-description / adjust-composition）按用户给定名，**工具名不是命令类型**。
   */
  private static final List<String> UNIT_WRITE_NAMES =
      List.of(
          "unit.RenameUnit",
          "unit.CreateUnit",
          "unit.ReparentUnit",
          "simos.unit.set-composition",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.DisbandUnit",
          "unit.SetStatus",
          "simos.unit.set-state-description",
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
          "simos.unit.adjust-composition",
          "unit.SetJurisdiction",
          "unit.SetTaxRate",
          "unit.SetGovFormation",
          "unit.SetArmyFormation",
          "unit.SetGovPolicy",
          "unit.SetGovSuperior");

  /** {@link #UNIT_WRITE_NAMES} 对应的 28 个命令类型（catalog 与 handler 面认的键）。 */
  private static final List<String> UNIT_WRITE_COMMAND_TYPES =
      List.of(
          "unit.RenameUnit",
          "unit.CreateUnit",
          "unit.ReparentUnit",
          "unit.SetComposition",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.DisbandUnit",
          "unit.SetStatus",
          "unit.SetStateDescription",
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
          "unit.AdjustComposition",
          "unit.SetJurisdiction",
          "unit.SetTaxRate",
          "unit.SetGovFormation",
          "unit.SetArmyFormation",
          "unit.SetGovPolicy",
          "unit.SetGovSuperior");

  /**
   * GM 桶的 15 条 {@code Sd*} 窄写：**只进 GM 组**（决策人桶不得含；IssueDirective/SubmitVerdict 两条决策共用窄写 另列在 {@link
   * #NARROW_WRITE_NAMES}）。
   */
  private static final List<String> SD_WRITE_NAMES =
      List.of(
          "sd.CreateNation",
          "sd.CreateArmy",
          "sd.SetArmyMasterGov",
          "sd.CreateDecisionMaker",
          "sd.PutInfo",
          // ★★ D5：外交关系边 + 外交事件的 GM 窄写（工具名不是命令类型）。
          "simos.sd.set-diplomatic-relation",
          "simos.sd.record-diplomatic-event",
          "sd.CreateCombat",
          "sd.AddCombatStage",
          "sd.SetStageOutcomeTable",
          "sd.CommitCombatOutcome",
          "sd.RecordCasualties",
          "sd.RegisterEffect",
          "sd.CancelEffect",
          "sd.SetDecisionMakerProvider");

  /** {@link #SD_WRITE_NAMES} 对应的 15 个命令类型。 */
  private static final List<String> SD_WRITE_COMMAND_TYPES =
      List.of(
          "sd.CreateNation",
          "sd.CreateArmy",
          "sd.SetArmyMasterGov",
          "sd.CreateDecisionMaker",
          "sd.PutInfo",
          "sd.SetDiplomaticRelation",
          "sd.RecordDiplomaticEvent",
          "sd.CreateCombat",
          "sd.AddCombatStage",
          "sd.SetStageOutcomeTable",
          "sd.CommitCombatOutcome",
          "sd.RecordCasualties",
          "sd.RegisterEffect",
          "sd.CancelEffect",
          "sd.SetDecisionMakerProvider");

  /** GM 桶的 60 条窄写（8 map + 28 unit + 15 sd + 1 army + 1 social + 1 actor + 6 决策共用/其他）。 */
  private static final List<String> NARROW_WRITE_NAMES =
      concat(
          concat(MAP_WRITE_NAMES, concat(UNIT_WRITE_NAMES, SD_WRITE_NAMES)),
          List.of(
              "sd.IssueDirective",
              "sd.SubmitVerdict",
              "sd.SetDecisionMakerAccess",
              "sd.ResetDecisionMakerConversation",
              "sd.StartDecision",
              "sd.RunDecision",
              "simos.army.recordCombat",
              "social.UpdateCity",
              "actor.AdjustAccounts"));

  /** 写工具全集（94 条）：{@link #READ_TOOL_NAMES} 在 {@link #GM_TOOL_NAMES} 里的补集。 */
  private static final List<String> WRITE_TOOL_NAMES =
      concat(NON_NARROW_WRITE_NAMES, NARROW_WRITE_NAMES);

  /** **运行时 MCP 口 = GM 组**的工具面（spec §2.1）= 32 读 + 94 写 = 126（**34 非窄写** + **60 窄写**）。 */
  private static final List<String> GM_TOOL_NAMES = concat(READ_TOOL_NAMES, WRITE_TOOL_NAMES);

  /** catalog 预期的 85 个已注册命令类型（与 Shell 注册的 handler 同源）。 */
  private static final List<String> EXPECTED_COMMAND_TYPES =
      List.of(
          "actor.AdjustAccounts",
          "actor.ClearRegion",
          "actor.RemitGovTreasury",
          "actor.Seed",
          "army.AppendCombatStage",
          "army.RecordCombat",
          "army.ResolveCombatStage",
          "economy.AddDemand",
          "economy.CancelDemand",
          "economy.ClearRegion",
          "economy.GmAdjust",
          "economy.MigrateHousehold",
          "economy.RegisterCandidate",
          "economy.Seed",
          "economy.SetMarketPrice",
          "economy.SwitchMode",
          "economy.TransferAssetShare",
          "economy.UnitBorrow",
          "economy.UnitRepay",
          "map.CreateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup",
          "map.RenameRegion",
          "map.SetEdge",
          "map.SetTerrain",
          "map.UpdateRegion",
          "sd.AddCombatStage",
          "sd.CancelEffect",
          "sd.CommitCombatOutcome",
          "sd.CreateArmy",
          "sd.CreateCombat",
          "sd.CreateDecisionMaker",
          "sd.CreateNation",
          "sd.DeleteDecisionMaker",
          "sd.IssueDirective",
          "sd.PutInfo",
          "sd.RecordCasualties",
          "sd.RecordDiplomaticEvent",
          "sd.RegisterEffect",
          "sd.ResetDecisionMakerConversation",
          "sd.RunDecision",
          "sd.SetArmyMasterGov",
          "sd.SetDecisionMakerAccess",
          "sd.SetDecisionMakerProvider",
          "sd.SetDiplomaticRelation",
          "sd.SetDirectiveStatus",
          "sd.SetStageOutcomeTable",
          "sd.StartDecision",
          "sd.SubmitVerdict",
          "social.ClearRegion",
          "social.CreateCity",
          "social.SeedGroups",
          "social.SetPopulation",
          "social.UpdateCity",
          "unit.AdjustComposition",
          "unit.ApplyCasualties",
          "unit.AttachUnit",
          "unit.CancelRoute",
          "unit.CreateCommandChain",
          "unit.CreateUnit",
          "unit.DetachUnit",
          "unit.DisbandUnit",
          "unit.DismissStaff",
          "unit.MergeFormation",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.PlanSparseRoute",
          "unit.RecruitStaff",
          "unit.RenameUnit",
          "unit.ReparentSubtree",
          "unit.ReparentUnit",
          "unit.SetArmyFormation",
          "unit.SetComposition",
          "unit.SetFormationOffset",
          "unit.SetGovFormation",
          "unit.SetGovPolicy",
          "unit.SetGovSuperior",
          "unit.SetJurisdiction",
          "unit.SetRejoinTarget",
          "unit.SetStateDescription",
          "unit.SetStatus",
          "unit.SetTaxRate",
          "unit.SplitFormation",
          "unit.UpdateCommandChain");

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

  // ── 注册面 ────────────────────────────────────────────────────────────

  @Test
  void registryContainsExactlyTheExternalUnionGmTools() {
    assertThat(shell.toolRegistry().list())
        .extracting(AgentTool::name)
        .containsExactlyInAnyOrderElementsOf(GM_TOOL_NAMES);
  }

  /**
   * ★ M1 判据 2：**名字同源** —— 8 条 map 窄工具各自钉死的命令类型 == 它在名单里登记的名字（{@code AbstractNarrowWriteTool.name()}
   * 直返 {@code commandType()}），且同一批名字在 GM 桶里能按名找到、7 个类型都已在 {@code catalog} 里（catalog 与已注册 handler 同源
   * ⇒ 名能到达 handler）。
   *
   * <p>★ **逐个构造真工具**而不是只查桶：把任一工具的 {@code commandType()} 改成别的已注册类型，这里当场红。
   */
  @Test
  void mapNarrowWriteToolsAreNamedAfterTheirFixedCommandType() throws Exception {
    CoreSimos core = shell.coreSimos();
    String mapId = ShellConfig.defaults(tempDir).mapId();
    List<AgentTool> mapTools =
        List.of(
            new MapSetTerrainTool(core, TEST_INITIATOR, mapId),
            new MapSetEdgeTool(core, TEST_INITIATOR, mapId),
            new MapCreateRegionTool(core, TEST_INITIATOR, mapId),
            new MapUpdateRegionTool(core, TEST_INITIATOR, mapId),
            new MapRenameRegionTool(core, TEST_INITIATOR, mapId),
            new MapDeleteRegionTool(core, TEST_INITIATOR, mapId),
            new MapRandomizeRegionTool(core, TEST_INITIATOR, mapId),
            new MapRegisterPathwayGroupTool(core, TEST_INITIATOR, mapId));

    assertThat(toolNames(mapTools))
        .as("8 条 map 窄工具的 name() == 它们各自钉死的命令类型")
        .containsExactlyElementsOf(MAP_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("同一批名字在 GM 桶里按名可寻")
        .containsAll(MAP_WRITE_NAMES);

    JsonNode types = JSON.readTree(call("simos.command.catalog", Map.of()).message()).get("types");
    assertThat(textValues(types))
        .as("8 个 map 类型都已在 catalog 里（⇒ 名字能到达已注册 handler）")
        .containsAll(MAP_WRITE_NAMES);
  }

  /**
   * ★ M2 判据 2：**名字同源**（28 条 unit 窄写 = M2 的 20 条 + 辖区 2 条 + 阶段 9–12 的 4 条 + D1/D3a 换名/新增的 2 条）——
   * 每条工具钉死的命令类型 == 它在名单里登记的名字（{@code AbstractNarrowWriteTool.name()} 直返 {@code
   * commandType()}），同一批名字在 **GM 桶**里都按名可寻、在**决策人桶**里一条都不出现 （后者由紧随的 {@code
   * doesNotContainAnyElementsOf} 断言），且 22 个类型都已在 {@code catalog} 里（catalog 与已注册 handler 同源 ⇒ 名能到达
   * handler）。
   *
   * <p>★ **逐个构造真工具**而不是只查桶：把任一工具的 {@code commandType()} 改成别的已注册类型，这里当场红（M2 的 m2 变异体）。
   */
  @Test
  void unitNarrowWriteToolsAreNamedAfterTheirFixedCommandType() throws Exception {
    CoreSimos core = shell.coreSimos();
    String mapId = ShellConfig.defaults(tempDir).mapId();
    List<AgentTool> unitTools =
        List.of(
            new UnitRenameTool(core, TEST_INITIATOR, mapId),
            new UnitCreateTool(core, TEST_INITIATOR, mapId),
            new UnitReparentTool(core, TEST_INITIATOR, mapId),
            new UnitSetCompositionTool(core, TEST_INITIATOR, mapId),
            new UnitPlaceAtTool(core, TEST_INITIATOR, mapId),
            new UnitPlanRouteTool(core, TEST_INITIATOR, mapId),
            new UnitCancelRouteTool(core, TEST_INITIATOR, mapId),
            new UnitDisbandTool(core, TEST_INITIATOR, mapId),
            new UnitSetStatusTool(core, TEST_INITIATOR, mapId),
            new UnitSetStateDescriptionTool(core, TEST_INITIATOR, mapId),
            new UnitAttachTool(core, TEST_INITIATOR, mapId),
            new UnitDetachTool(core, TEST_INITIATOR, mapId),
            new UnitReparentSubtreeTool(core, TEST_INITIATOR, mapId),
            new UnitSetFormationOffsetTool(core, TEST_INITIATOR, mapId),
            new UnitSplitFormationTool(core, TEST_INITIATOR, mapId),
            new UnitMergeFormationTool(core, TEST_INITIATOR, mapId),
            new UnitPlanSparseRouteTool(core, TEST_INITIATOR, mapId),
            new UnitSetRejoinTargetTool(core, TEST_INITIATOR, mapId),
            new UnitCreateCommandChainTool(core, TEST_INITIATOR, mapId),
            new UnitUpdateCommandChainTool(core, TEST_INITIATOR, mapId),
            new UnitApplyCasualtiesTool(core, TEST_INITIATOR, mapId),
            new UnitAdjustCompositionTool(core, TEST_INITIATOR, mapId),
            new UnitSetJurisdictionTool(core, TEST_INITIATOR, mapId),
            new UnitSetTaxRateTool(core, TEST_INITIATOR, mapId),
            new UnitSetGovFormationTool(core, TEST_INITIATOR, mapId),
            new UnitSetArmyFormationTool(core, TEST_INITIATOR, mapId),
            new UnitSetGovPolicyTool(core, TEST_INITIATOR, mapId),
            new UnitSetGovSuperiorTool(core, TEST_INITIATOR, mapId));

    assertThat(toolNames(unitTools))
        .as("28 条 unit 窄工具的登记名逐条等于 UNIT_WRITE_NAMES（含 D3a/D1 三条非命令型工具名）")
        .containsExactlyElementsOf(UNIT_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("同一批名字在 GM 桶里按名可寻")
        .containsAll(UNIT_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("★ 判据 J3：决策人桶里**没有**任何 unit 窄写（决策人不能直接改地图等数据）")
        .doesNotContainAnyElementsOf(UNIT_WRITE_NAMES);

    JsonNode types = JSON.readTree(call("simos.command.catalog", Map.of()).message()).get("types");
    assertThat(textValues(types))
        .as("28 个 unit 命令类型都已在 catalog 里（⇒ 名字能到达已注册 handler）")
        .containsAll(UNIT_WRITE_COMMAND_TYPES);
  }

  /**
   * ★ M3 判据 2：**名字同源**（15 条 GM-only Sd* 窄写；D5 两条外交工具的工具名不是命令类型）—— 每条工具钉死的命令类型 == 它在名单里登记的名字（{@code
   * AbstractNarrowWriteTool.name()} 直返 {@code commandType()}），同一批名字在 **GM 桶与 {@code GM 组（= MCP 口）}
   * 里都按名可寻， 且 12 个类型都已在 {@code catalog} 里（catalog 与已注册 handler 同源 ⇒ 名能到达 handler）。
   *
   * <p>★ **逐个构造真工具**而不是只查桶：把任一工具的 {@code commandType()} 改成别的类型，这里当场红（M3 的 m2 变异体）。与 M1/M2 同形； ★
   * 本批**只进 GM 组**——{@code DECISION_AGENT} 桶的"不得含"由 {@link #roleBucketsNeverCarryGenericWrite}
   * 按名反向断言（两处分工：这里证"在"，那里证"不在"）。
   */
  @Test
  void sdNarrowWriteToolsAreNamedAfterTheirFixedCommandType() throws Exception {
    CoreSimos core = shell.coreSimos();
    String mapId = ShellConfig.defaults(tempDir).mapId();
    List<AgentTool> sdTools =
        List.of(
            new SdCreateNationTool(core, TEST_INITIATOR, mapId),
            new SdCreateArmyTool(core, TEST_INITIATOR, mapId),
            new SdSetArmyMasterGovTool(core, TEST_INITIATOR, mapId),
            new SdCreateDecisionMakerTool(core, TEST_INITIATOR, mapId),
            new SdPutInfoTool(core, TEST_INITIATOR, mapId),
            new SdSetDiplomaticRelationTool(core, TEST_INITIATOR, mapId),
            new SdRecordDiplomaticEventTool(core, TEST_INITIATOR, mapId),
            new SdCreateCombatTool(core, TEST_INITIATOR, mapId),
            new SdAddCombatStageTool(core, TEST_INITIATOR, mapId),
            new SdSetStageOutcomeTableTool(core, TEST_INITIATOR, mapId),
            new SdCommitCombatOutcomeTool(core, TEST_INITIATOR, mapId),
            new SdRecordCasualtiesTool(core, TEST_INITIATOR, mapId),
            new SdRegisterEffectTool(core, TEST_INITIATOR, mapId),
            new SdCancelEffectTool(core, TEST_INITIATOR, mapId),
            new SdSetDecisionMakerProviderTool(core, TEST_INITIATOR, mapId));

    assertThat(toolNames(sdTools))
        .as("15 条 GM-only sd 窄工具的登记名逐条等于 SD_WRITE_NAMES")
        .containsExactlyElementsOf(SD_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("同一批名字在 GM 桶里按名可寻")
        .containsAll(SD_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("★ 决策人桶不得含 GM-only sd 窄写（两条共用窄写 IssueDirective/SubmitVerdict 另列）")
        .doesNotContainAnyElementsOf(SD_WRITE_NAMES);

    JsonNode types = JSON.readTree(call("simos.command.catalog", Map.of()).message()).get("types");
    assertThat(textValues(types))
        .as("15 个 sd 命令类型都已在 catalog 里（⇒ 名字能到达已注册 handler）")
        .containsAll(SD_WRITE_COMMAND_TYPES);
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
   * ★ **T9 的强判据**：catalog 的 type 集合 == **全仓 85 个 `CommandHandler` 实现**的 `type()` 集合（注册面 == 实现面），
   * 而不只是"与一份手抄的期望表相等"。扫描 simos-unit/map/social/sd 的 main 源码抽 `type()` 的返回串——**任一 handler 存在却没注册进
   * {@code Shell}，或注册了一条没有实现的 type，这里都会红**。
   *
   * <p>★ 扫描范围是 surefire 工作目录（模块根 {@code simos-app/}）⇒ 相对路径 {@code ../simos-unit/src/main/java} 在主树与
   * worktree 里都成立；**非空自证**：条数由紧随的 {@code hasSize} 断言钉住（扫到 0 个是"扫描器静默"陷阱，不是通过）。
   */
  @Test
  void catalogCoversEveryCommandHandlerImplementation() throws Exception {
    Set<String> implementationTypes = handlerTypesFromSources();
    assertThat(implementationTypes)
        .as(
            "扫描必须恰为 85 个 *Handler.java 的 type()（扫到 0/漏文件是『扫描器静默』陷阱；R4/E6 后含全部 economy/actor handler，"
                + "P1b1/P3/R3a 的区域清空与国库上缴，辖区阶段 5–12，D1/D3a/D4/D5 的 unit/sd/army 新命令）")
        .hasSize(85);

    ToolResult result = call("simos.command.catalog", Map.of());
    assertThat(result.success()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(textValues(body.get("types")))
        .as("catalog 的 type 集合必须等于全仓实现的 type() 集合（强判据：注册面 == 实现面）")
        .containsExactlyInAnyOrderElementsOf(implementationTypes);
  }

  /**
   * ★ D6 判据（N9/N11）+ T4（D2="加"）：工具面按角色分载——**GM 与决策 Agent 桶都没有通用写** {@code simos.command.submit}，都有
   * {@code sd.*} 窄工具；**运行时 MCP 口 = GM 组**（通用写 ∪ 全部窄写，读共享）；**决策人组只有两条决策窄写**（J3）。
   */
  @Test
  void roleBucketsNeverCarryGenericWrite() {
    List<String> gm = toolNames(shell.toolsFor(SimosToolSource.Role.GM));
    List<String> agent = toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));

    assertThat(gm)
        .as(
            "★ J1（spec §2.1）：GM 桶 = 运行时 MCP 口（用户裁定：MCP 与 GM 同权限级，想改什么改什么）"
                + " ⇒ 通用写**在**其中，且 sd/map/unit 三族窄写逐条都在")
        .contains("simos.command.submit", "simos.advance", "simos.fork")
        .contains(
            "sd.IssueDirective",
            "sd.SubmitVerdict",
            "sd.SetDecisionMakerAccess",
            "sd.ResetDecisionMakerConversation",
            "sd.StartDecision",
            "sd.RunDecision",
            "sd.AdjudicateTick",
            "sd.RejectDirective")
        .containsAll(SD_WRITE_NAMES)
        .containsAll(MAP_WRITE_NAMES)
        .containsAll(UNIT_WRITE_NAMES)
        .as("★ D1–D5 后 + C5b：GM 桶 = 32 读 + 94 写 = 126（34 非窄写 + 60 窄写）")
        .hasSize(126);
    assertThat(agent)
        .as(
            "★ J3（spec §2.2/§四.3）：决策人桶**没有**通用写、**没有**任何 map/unit/sd 的写工具，"
                + "只留两条决策行为（sd.IssueDirective / sd.SubmitVerdict）+ 两条只读面"
                + "（第 3 波第 3 步的 sd.DecisionResults、Docs 系统的 sd.DecisionDocs）")
        .doesNotContain("simos.command.submit", "simos.advance", "simos.fork")
        .doesNotContain(
            "sd.SetDecisionMakerAccess",
            "sd.ResetDecisionMakerConversation",
            "sd.StartDecision",
            "sd.RunDecision",
            "sd.AdjudicateTick",
            "sd.RejectDirective")
        .contains("sd.IssueDirective", "sd.SubmitVerdict", "sd.DecisionResults", "sd.DecisionDocs")
        // ★ D5：决策人侧的三条受限写（两条外交 + 给 XXX 政府钱）必须恰在此桶（GM 桶另有同名命令型的
        //   simos.sd.* 工具，不在本桶）。
        .contains("sd.SetDiplomaticRelation", "sd.RecordDiplomaticEvent", "simos.gov.pay")
        .doesNotContainAnyElementsOf(UNIT_WRITE_NAMES)
        .doesNotContainAnyElementsOf(MAP_WRITE_NAMES)
        .doesNotContainAnyElementsOf(SD_WRITE_NAMES)
        .doesNotContainAnyElementsOf(GM_ONLY_READ_NAMES)
        .as("★ M4 + D5 + C5b：决策人桶 = 20 共享读 + 2 决策只读 + 5 受限写 = 27")
        .hasSize(27);
  }

  private static List<String> toolNames(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  /**
   * 写闸**实际覆盖**的工具名。★★ 它从**真工具面**（GM 组）派生、减去读名单，**不是**从名单常量取下标 切片。
   *
   * <p>这是 M1 修掉的那处缺陷的替代形态：原实现用 {@code subList(9, 16)}，名单加到 30 条后切片**仍然合法** ⇒ 新增的 map
   * 写工具完全不被写闸覆盖、且没有任何症状。现在覆盖集从工具面派生，退化成切片会当场红。
   */
  private List<String> writeFaceCoveredByTheWriteGate() {
    return shell.toolsFor(SimosToolSource.Role.GM).stream()
        .map(AgentTool::name)
        .filter(name -> !READ_TOOL_NAMES.contains(name))
        .toList();
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
    List<Path> roots = commandHandlerSourceRoots();
    Pattern typeReturn = Pattern.compile("public String type\\(\\)\\s*\\{\\s*return\\s*([^;]+);");
    Pattern literalConstant = Pattern.compile("\\bString\\s+([A-Z_]+)\\s*=\\s*\"([^\"]+)\"");
    Set<String> types = new LinkedHashSet<>();
    for (Path root : roots) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file :
            files.filter(path -> path.getFileName().toString().endsWith("Handler.java")).toList()) {
          String source = Files.readString(file);
          Matcher matcher = typeReturn.matcher(source);
          if (!matcher.find()) {
            continue;
          }
          String expression = matcher.group(1).trim();
          if (expression.startsWith("\"") && expression.endsWith("\"")) {
            types.add(expression.substring(1, expression.length() - 1));
            continue;
          }
          Matcher constant = literalConstant.matcher(source);
          while (constant.find()) {
            if (constant.group(1).equals(expression)) {
              types.add(constant.group(2));
              break;
            }
          }
        }
      }
    }
    return types;
  }

  /**
   * ★★ **M2 判据 §5.5（本任务新增的同源强判据）**：扫 {@code tools/write/*Tool.java} 把**实现了窄写工具类**的 {@code NAME}
   * 常量抽出来，断言 **"实现了窄写工具类的集合 == GM 桶的窄写工具集合"**。
   *
   * <p>**它挡住的失效模式**：写了 {@code write/UnitXxxTool.java} 却**忘了接进任何桶** —— 工具面看不见它、两份手抄名单也不认识它，
   * 于是**没有任何断言会红**（M4 侦察点名的「同源判据只覆盖命令、不覆盖工具」那个缺口）。M2 的变异 m3（孤儿工具）就是它的靶子。
   *
   * <p>★ **判据两侧都是"真源"**：左边 = 磁盘上的类（{@code extends AbstractNarrowWriteTool} 是**必须**的过滤条件 —— {@code
   * AdvanceTool}/{@code CommandSubmitTool}/{@code ForkTool} 同样住在 {@code tools/write/} 且各有 {@code
   * NAME}， 但它们是**通用写**、不继承基类）；右边 = **真工具面**（{@code shell.toolsFor(GM)} 减读名单），不是手抄常量。
   *
   * <p>★ **先断言扫描非空且条数正确**：扫到 0 个或漏文件是「扫描器静默」陷阱，会让"空 == 空"恒真（本仓已踩过）。
   */
  @Test
  void everyNarrowWriteToolClassIsWiredIntoTheGmBucket() throws Exception {
    Set<String> implemented = narrowWriteToolNamesFromSources();
    assertThat(implemented).as("扫描必须恰为 62 个窄写工具类（扫到 0 个/漏文件是『扫描器静默』陷阱 ⇒ 空 == 空 恒真）").hasSize(62);

    // 真工具面派生的 GM 窄写 = GM 桶 ∖ 读名单 ∖ 非窄写写工具。
    List<String> gmNarrow =
        shell.toolsFor(SimosToolSource.Role.GM).stream()
            .map(AgentTool::name)
            .filter(name -> !READ_TOOL_NAMES.contains(name))
            .filter(name -> !NON_NARROW_WRITE_NAMES.contains(name))
            .toList();
    assertThat(gmNarrow)
        .as("GM 窄写必须逐条等于 NARROW_WRITE_NAMES（60 条）")
        .containsExactlyInAnyOrderElementsOf(NARROW_WRITE_NAMES);

    // ★ D5：决策人桶里也有窄写（两条外交命令型窄工具）——它们同样必须在册，不能成为"扫描器盲区"。
    Set<String> decisionNarrow = new LinkedHashSet<>(implemented);
    decisionNarrow.retainAll(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)));
    assertThat(decisionNarrow)
        .as("决策人桶里的窄写恰为四条：两条决策共用窄写（IssueDirective/SubmitVerdict）+ D5 的两条外交命令型窄工具")
        .containsExactlyInAnyOrder(
            "sd.IssueDirective",
            "sd.SubmitVerdict",
            "sd.SetDiplomaticRelation",
            "sd.RecordDiplomaticEvent");
    Set<String> union = new LinkedHashSet<>(gmNarrow);
    union.addAll(decisionNarrow);
    assertThat(union)
        .as("GM 窄写 ∪ 决策人窄写必须逐条等于磁盘上实现了窄写工具的集合（孤儿工具 ⇒ 这里红）")
        .containsExactlyInAnyOrderElementsOf(implemented);
  }

  /**
   * 从 {@code tools/write/*Tool.java} 抽继承了 {@link AbstractNarrowWriteTool} 的类的 {@code NAME} 常量。
   *
   * <p>★ 路径是**相对模块目录**（surefire 的工作目录是 {@code simos-app/}），故主树与 worktree 里都成立；★ 类名后缀 {@code
   * Tool.java} 连基类自己也会被走到 —— 靠 {@code extends AbstractNarrowWriteTool} 这条把基类排除掉（基类没有 {@code NAME}，
   * 也没有这条继承）。
   */
  private static Set<String> narrowWriteToolNamesFromSources() throws IOException {
    Path root = Paths.get("src", "main", "java", "io", "mosire", "simos", "app", "tools", "write");
    Pattern nameConstant = Pattern.compile("String\\s+NAME\\s*=\\s*\"([^\"]+)\"");
    Pattern nameHandlerType = Pattern.compile("String\\s+NAME\\s*=\\s*([A-Za-z0-9_]+)\\.TYPE");
    Map<String, String> handlerTypes = handlerTypeConstantsFromSources();
    Set<String> names = new LinkedHashSet<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file :
          files.filter(path -> path.getFileName().toString().endsWith("Tool.java")).toList()) {
        String source = Files.readString(file);
        if (!source.contains("extends AbstractNarrowWriteTool")) {
          continue;
        }
        Matcher matcher = nameConstant.matcher(source);
        if (matcher.find()) {
          names.add(matcher.group(1));
          continue;
        }
        Matcher constant = nameHandlerType.matcher(source);
        assertThat(constant.find()).as("%s 继承了窄写基类却抽不到 NAME 常量（扫描器要当场响，不许静默跳过）", file).isTrue();
        String resolved = handlerTypes.get(constant.group(1));
        assertThat(resolved)
            .as("%s 的 NAME = %s.TYPE 解析不到命令类型（静态扫描必须能还原）", file, constant.group(1))
            .isNotNull();
        names.add(resolved);
      }
    }
    return names;
  }

  /**
   * ★★ 工具面 M4（2026-09-24）判据 ①：**每条读工具都真被调一次**（可达 + 最小形状）。
   *
   * <p>由来：M4 侦察报告 §四-1 实测——现有 15 条守卫**全在名字集合层**，没有一条真的调过读工具 ⇒ 一条读工具可以有正确的名字、正确的 {@code
   * spec()}，却**形状错 / 抛异常 / 压根不可达**而全绿。 本用例把那张表补上：逐条按**名字**调（不是索引切片），断言"不报错 + 最小形状键在场"。
   *
   * <p>★ 覆盖面自证在末行：表格必须**逐条覆盖** {@link #READ_TOOL_NAMES}——新增读工具却没进本表 ⇒ 当场红。
   */
  @Test
  void everyReadToolIsReachableAndAnswersWithItsMinimalShape() throws Exception {
    record Case(String tool, Map<String, Object> args, String key) {}
    List<Case> cases =
        List.of(
            new Case("simos.command.catalog", Map.of(), "types"),
            new Case("simos.state.resolve", Map.of("address", hexAddress()), "candidates"),
            new Case("simos.state.facets", Map.of("address", hexAddress()), "entries"),
            new Case("simos.timeline.branches", Map.of(), "branches"),
            new Case("simos.timeline.revisions", Map.of(), "nodes"),
            new Case("simos.map.overview", Map.of(), "hexCount"),
            new Case("simos.map.hex", Map.of("q", 1L, "r", 1L), "terrain"),
            new Case("simos.map.region", Map.of("regionId", REGION.value()), "hexCount"),
            // ★ 用户 2026-10-02：区域重合检测（GM 专用；创世只有 r-m4 ⇒ 空 overlaps 也是合法最小形状）。
            new Case("simos.map.overlaps", Map.of(), "overlaps"),
            // ★ P3（2026-10-01）：省份划分建议（只给 GM 桶）。
            new Case("simos.province.divide", Map.of("regionId", REGION.value()), "provinces"),
            new Case("simos.map.path", Map.of("unit", U1.value(), "q", 1L, "r", 2L), "reachable"),
            new Case("simos.map.block", Map.of("q", 1L, "r", 1L), "hexes"),
            new Case("simos.unit.list", Map.of(), "units"),
            new Case("simos.unit.get", Map.of("id", U1.value()), "id"),
            new Case("simos.social.population", Map.of("q", 1L, "r", 1L), "population"),
            new Case("simos.economy.hex", Map.of("q", 1L, "r", 1L), "industries"),
            // ★ H0.6：产权读口的最小形状 = 三个键之一（accounts 可以为空数组 —— 那正是本轮的正确状态）。
            new Case("simos.economy.ownership", Map.of("q", 1L, "r", 1L), "accounts"),
            new Case("simos.sd.decision-makers", Map.of(), "decisionMakers"),
            new Case("simos.sd.combats", Map.of(), "combats"),
            // ★ P7c（2026-10-01）：决策记录清单（只给 GM 桶）。
            new Case("simos.sd.directives", Map.of(), "directives"),
            // ★★ D5（2026-10-02 / R6）：外交关系边 + 外交事件（四桶共享）。
            new Case("simos.sd.diplomacy", Map.of(), "relations"),
            new Case("simos.sd.diplomatic-events", Map.of(), "events"),
            // ★★ D1（2026-10-02 / D-012）：Army 交战记录读口（四桶共享）。
            new Case("simos.army.combats", Map.of(), "combats"),
            new Case("simos.sd.verdicts", Map.of(), "verdicts"),
            new Case("simos.gm.tool-usage", Map.of(), "entries"),
            // ★ P7b（2026-10-01）：待裁决审批清单（只给 GM 桶）。
            new Case("simos.gm.approvals", Map.of(), "pending"),
            new Case("simos.llm.providers", Map.of(), "providers"),
            // ★ C5b（2026-10-02）：历法/气候信息读工具的最小形状（config+sources+date+solarTerm+notes）。
            new Case("simos.calendar.info", Map.of(), "sources"),
            new Case(
                "simos.map.render",
                Map.of("q", 1L, "r", 1L, "radius", 1L, "format", "text"),
                "summary"),
            new Case("simos.skill", Map.of(), "skills"));
    List<String> covered = new ArrayList<>();
    for (Case c : cases) {
      shell.toolRegistry().find(c.tool()).orElseThrow(); // 前置：名字必须真在注册表里
      ToolResult result = call(c.tool(), c.args());
      assertThat(result.success()).as("%s 必须可达且不报错: %s", c.tool(), result.message()).isTrue();
      assertThat(JSON.readTree(result.message()).has(c.key()))
          .as("%s 的最小形状里必须有键 %s（形状错 ⇒ 这里红）", c.tool(), c.key())
          .isTrue();
      covered.add(c.tool());
    }
    // 需要特定实体的那两条：未知 id ⇒ 可读的 NOT_FOUND（同样是**真调用**，不是跳过）
    ToolResult missing = call("simos.sd.decision-maker", Map.of("decisionMakerId", "dm-m4-none"));
    assertThat(missing.success()).as("未知决策人不得静默给空对象").isFalse();
    assertThat(missing.code()).isEqualTo("NOT_FOUND");
    covered.add("simos.sd.decision-maker");
    // ★★ D1：单条 Army 交战详情也是按 id 读；未知 id ⇒ NOT_FOUND（不是静默空对象）。
    ToolResult missingCombat = call("simos.army.combat", Map.of("id", "c-m4-none"));
    assertThat(missingCombat.success()).as("未知交战记录不得静默给空对象").isFalse();
    assertThat(missingCombat.code()).isEqualTo("NOT_FOUND");
    covered.add("simos.army.combat");

    assertThat(covered)
        .as("本表必须逐条覆盖读工具全集（新增读工具却没加进本表 ⇒ 红）")
        .containsExactlyInAnyOrderElementsOf(READ_TOOL_NAMES);
  }

  // ── ★ simos.map.overlaps（用户 2026-10-02 的新读工具）────────────────────

  /**
   * ★ 行为主用例：造 5 个区域（创世 r-m4 + 四个新建），覆盖 equal / aContainsB / bContainsA / partial 四种 relation，
   * 并逐值校验聚合计数、行序、行上的 tag/sameTag/hexCount。
   *
   * <p>★ 判别力：把 relation 的任一分支写错 ⇒ 行上的 relation 或 equal/containment/partialPairCount 当场红；把 tag 过滤放宽
   * ⇒ {@link #mapOverlapsToolFiltersByTagSameTagWholeOnlyAndMinOverlap()} 红。
   */
  @Test
  void mapOverlapsToolReportsEveryRelationAndExactAggregates() throws Exception {
    seedOverlapRegions();

    ToolResult result = call("simos.map.overlaps", Map.of());

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(fieldNames(body))
        .as("顶层键必须与 spec 逐条一致（少一个键 = 契约破相）")
        .containsExactlyInAnyOrder(
            "mapId",
            "branch",
            "revision",
            "regionCount",
            "candidateRegionCount",
            "pairCount",
            "returnedPairCount",
            "truncated",
            "involvedRegionCount",
            "overlappingHexCount",
            "equalPairCount",
            "containmentPairCount",
            "partialPairCount",
            "sameTagPairCount",
            "filters",
            "overlaps");
    assertThat(body.get("mapId").asText()).isEqualTo("Map1");
    assertThat(body.get("branch").asText()).isEqualTo("main");
    assertThat(body.get("revision").asLong()).isEqualTo(5L);
    assertThat(body.get("regionCount").asInt()).isEqualTo(5);
    assertThat(body.get("candidateRegionCount").asInt()).isEqualTo(5);
    assertThat(body.get("pairCount").asInt()).isEqualTo(9);
    assertThat(body.get("returnedPairCount").asInt()).isEqualTo(9);
    assertThat(body.get("truncated").asBoolean()).isFalse();
    assertThat(body.get("involvedRegionCount").asInt()).isEqualTo(5);
    assertThat(body.get("overlappingHexCount").asInt()).isEqualTo(3);
    assertThat(body.get("equalPairCount").asInt()).isEqualTo(1);
    assertThat(body.get("containmentPairCount").asInt()).isEqualTo(6);
    assertThat(body.get("partialPairCount").asInt()).isEqualTo(2);
    assertThat(body.get("sameTagPairCount").asInt()).isEqualTo(1);

    JsonNode filters = body.get("filters");
    assertThat(filters.has("regionId")).isFalse();
    assertThat(filters.has("tag")).isFalse();
    assertThat(filters.get("sameTagOnly").asBoolean()).isFalse();
    assertThat(filters.get("minOverlapHexes").asLong()).isEqualTo(1L);
    assertThat(filters.get("wholeOnly").asBoolean()).isFalse();
    assertThat(filters.get("includeHexes").asBoolean()).isFalse();
    assertThat(filters.get("limit").asLong()).isEqualTo(50L);

    record ExpectedPair(
        String a,
        int aHex,
        String aTag,
        String b,
        int bHex,
        String bTag,
        int overlap,
        String relation,
        boolean sameTag) {}
    List<ExpectedPair> expected =
        List.of(
            new ExpectedPair("r-eq", 2, "Nation", "r-m4", 2, null, 2, "equal", false),
            new ExpectedPair("r-eq", 2, "Nation", "r-super", 3, "", 2, "bContainsA", false),
            new ExpectedPair("r-m4", 2, null, "r-super", 3, "", 2, "bContainsA", false),
            new ExpectedPair("r-part", 2, "Nation", "r-super", 3, "", 2, "bContainsA", false),
            new ExpectedPair("r-eq", 2, "Nation", "r-part", 2, "Nation", 1, "partial", true),
            new ExpectedPair("r-eq", 2, "Nation", "r-small", 1, null, 1, "aContainsB", false),
            new ExpectedPair("r-m4", 2, null, "r-part", 2, "Nation", 1, "partial", false),
            new ExpectedPair("r-m4", 2, null, "r-small", 1, null, 1, "aContainsB", false),
            new ExpectedPair("r-small", 1, null, "r-super", 3, "", 1, "bContainsA", false));

    JsonNode rows = body.get("overlaps");
    assertThat(rows).hasSize(expected.size());
    assertThat(fieldNames(rows.get(0)))
        .as("行键（未请求 includeHexes 时恰好 11 个）")
        .containsExactlyInAnyOrder(
            "aRegionId",
            "aName",
            "aTag",
            "aHexCount",
            "bRegionId",
            "bName",
            "bTag",
            "bHexCount",
            "overlapHexCount",
            "relation",
            "sameTag");
    for (int i = 0; i < expected.size(); i++) {
      JsonNode row = rows.get(i);
      ExpectedPair pair = expected.get(i);
      String where = "第 " + i + " 行（" + pair.a() + " / " + pair.b() + "）";
      assertThat(row.get("aRegionId").asText()).as(where + " aRegionId").isEqualTo(pair.a());
      assertThat(row.get("aHexCount").asInt()).as(where + " aHexCount").isEqualTo(pair.aHex());
      assertThat(optionalTag(row, "aTag")).as(where + " aTag").isEqualTo(pair.aTag());
      assertThat(row.get("bRegionId").asText()).as(where + " bRegionId").isEqualTo(pair.b());
      assertThat(row.get("bHexCount").asInt()).as(where + " bHexCount").isEqualTo(pair.bHex());
      assertThat(optionalTag(row, "bTag")).as(where + " bTag").isEqualTo(pair.bTag());
      assertThat(row.get("overlapHexCount").asInt())
          .as(where + " overlap")
          .isEqualTo(pair.overlap());
      assertThat(row.get("relation").asText()).as(where + " relation").isEqualTo(pair.relation());
      assertThat(row.get("sameTag").asBoolean()).as(where + " sameTag").isEqualTo(pair.sameTag());
      assertThat(row.has("overlapHexes")).as(where + " 未请求 includeHexes ⇒ 不得夹带交集格").isFalse();
    }
  }

  /**
   * ★ 过滤面逐值：tag / sameTagOnly / wholeOnly / minOverlapHexes 必须在**候选集与计数**上同时生效。
   *
   * <p>★ 判别力：把同一 tag 过滤放宽成"任一匹配" ⇒ tag 用例的 pairCount=1 / candidateRegionCount=2 红；sameTagOnly 若把空
   * tag 也算同 tag ⇒ 会多出空 tag 对，pairCount 不再是 1；wholeOnly 若把 partial 也留下 ⇒ pairCount=7 /
   * partialPairCount=0 红。
   */
  @Test
  void mapOverlapsToolFiltersByTagSameTagWholeOnlyAndMinOverlap() throws Exception {
    seedOverlapRegions();

    JsonNode tagged = executeOverlaps(Map.of("tag", "Nation"));
    assertThat(tagged.get("candidateRegionCount").asInt()).isEqualTo(2);
    assertThat(tagged.get("pairCount").asInt()).isEqualTo(1);
    assertThat(tagged.get("equalPairCount").asInt()).isZero();
    assertThat(tagged.get("containmentPairCount").asInt()).isZero();
    assertThat(tagged.get("partialPairCount").asInt()).isEqualTo(1);
    assertThat(tagged.get("sameTagPairCount").asInt()).isEqualTo(1);
    assertThat(tagged.get("involvedRegionCount").asInt()).isEqualTo(2);
    assertThat(tagged.get("overlappingHexCount").asInt()).isEqualTo(1);
    assertThat(tagged.get("filters").get("tag").asText()).isEqualTo("Nation");
    JsonNode onlyPair = tagged.get("overlaps").get(0);
    assertThat(onlyPair.get("aRegionId").asText()).isEqualTo("r-eq");
    assertThat(onlyPair.get("bRegionId").asText()).isEqualTo("r-part");
    assertThat(onlyPair.get("overlapHexCount").asInt()).isEqualTo(1);
    assertThat(onlyPair.get("relation").asText()).isEqualTo("partial");
    assertThat(onlyPair.get("sameTag").asBoolean()).isTrue();

    JsonNode sameTagOnly = executeOverlaps(Map.of("sameTagOnly", true));
    assertThat(sameTagOnly.get("candidateRegionCount").asInt())
        .as("空白/null tag 的区域必须整批挡在候选外")
        .isEqualTo(2);
    assertThat(sameTagOnly.get("pairCount").asInt())
        .as("空 tag 对（r-m4 / r-super）不得被算作同 tag")
        .isEqualTo(1);
    assertThat(sameTagOnly.get("sameTagPairCount").asInt()).isEqualTo(1);
    assertThat(sameTagOnly.get("overlaps")).hasSize(1);
    assertThat(sameTagOnly.get("overlaps").get(0).get("sameTag").asBoolean()).isTrue();
    assertThat(sameTagOnly.get("filters").get("sameTagOnly").asBoolean()).isTrue();

    JsonNode wholeOnly = executeOverlaps(Map.of("wholeOnly", true));
    assertThat(wholeOnly.get("pairCount").asInt()).isEqualTo(7);
    assertThat(wholeOnly.get("equalPairCount").asInt()).isEqualTo(1);
    assertThat(wholeOnly.get("containmentPairCount").asInt()).isEqualTo(6);
    assertThat(wholeOnly.get("partialPairCount").asInt()).isZero();
    assertThat(wholeOnly.get("involvedRegionCount").asInt()).isEqualTo(5);
    assertThat(wholeOnly.get("truncated").asBoolean()).isFalse();
    assertThat(wholeOnly.get("overlaps")).hasSize(7);
    for (JsonNode row : wholeOnly.get("overlaps")) {
      assertThat(row.get("relation").asText())
          .as("wholeOnly 输出里不得再出现 partial")
          .isNotEqualTo("partial");
    }
    assertThat(wholeOnly.get("filters").get("wholeOnly").asBoolean()).isTrue();

    JsonNode minTwo = executeOverlaps(Map.of("minOverlapHexes", 2));
    assertThat(minTwo.get("pairCount").asInt()).isEqualTo(4);
    assertThat(minTwo.get("equalPairCount").asInt()).isEqualTo(1);
    assertThat(minTwo.get("containmentPairCount").asInt()).isEqualTo(3);
    assertThat(minTwo.get("partialPairCount").asInt()).isZero();
    assertThat(minTwo.get("sameTagPairCount").asInt())
        .as("唯一同 tag 对只有 1 格交集 —— 下界 2 必须把它滤掉，计数不得停留在旧值")
        .isZero();
    assertThat(minTwo.get("filters").get("minOverlapHexes").asLong()).isEqualTo(2L);
  }

  /**
   * ★ regionId 只留涉及它的对；includeHexes 的交集格必须是真实交集、(q,r) 升序、且与 overlapHexCount 一致；limit 真截断但 pairCount
   * 保持真值。
   */
  @Test
  void mapOverlapsToolFiltersByRegionAndProjectsIntersectionHexes() throws Exception {
    seedOverlapRegions();

    JsonNode region = executeOverlaps(Map.of("regionId", "r-part"));
    assertThat(region.get("regionCount").asInt()).isEqualTo(5);
    assertThat(region.get("candidateRegionCount").asInt()).as("regionId 不再收窄候选集本身的定义").isEqualTo(5);
    assertThat(region.get("pairCount").asInt()).isEqualTo(3);
    assertThat(region.get("equalPairCount").asInt()).isZero();
    assertThat(region.get("containmentPairCount").asInt()).isEqualTo(1);
    assertThat(region.get("partialPairCount").asInt()).isEqualTo(2);
    assertThat(region.get("sameTagPairCount").asInt()).isEqualTo(1);
    assertThat(region.get("involvedRegionCount").asInt())
        .as("涉及 r-part 的三对还牵出 r-eq / r-m4 / r-super ⇒ 共 4 个区域")
        .isEqualTo(4);
    assertThat(region.get("overlappingHexCount").asInt()).isEqualTo(2);
    assertThat(region.get("filters").get("regionId").asText()).isEqualTo("r-part");
    for (JsonNode row : region.get("overlaps")) {
      assertThat(
              "r-part".equals(row.get("aRegionId").asText())
                  || "r-part".equals(row.get("bRegionId").asText()))
          .as("regionId 过滤后每一行都必须涉及 r-part")
          .isTrue();
    }

    JsonNode hexes = executeOverlaps(Map.of("includeHexes", true));
    assertThat(hexList(overlapRow(hexes, "r-eq", "r-super").get("overlapHexes")))
        .as("bContainsA 的交集 = 被包含区域的全部格，按 (q,r) 升序")
        .containsExactly("1,1", "1,2");
    assertThat(hexList(overlapRow(hexes, "r-part", "r-super").get("overlapHexes")))
        .containsExactly("1,2", "1,3");
    assertThat(hexList(overlapRow(hexes, "r-eq", "r-part").get("overlapHexes")))
        .as("partial 的交集恰是真实共有格（H12）")
        .containsExactly("1,2");
    assertThat(hexList(overlapRow(hexes, "r-small", "r-super").get("overlapHexes")))
        .containsExactly("1,1");
    for (JsonNode row : hexes.get("overlaps")) {
      assertThat(row.get("overlapHexes").size())
          .as("交集格清单长度 == overlapHexCount")
          .isEqualTo(row.get("overlapHexCount").asInt());
      assertThat(row.get("overlapHexesTruncated").asBoolean()).isFalse();
      assertThat(fieldNames(row))
          .as("includeHexes=true ⇒ 每行必须多出这两个键")
          .contains("overlapHexes", "overlapHexesTruncated");
    }
    assertThat(hexes.get("filters").get("includeHexes").asBoolean()).isTrue();
    assertThat(
            JSON.readTree(call("simos.map.overlaps", Map.of()).message())
                .get("overlaps")
                .get(0)
                .has("overlapHexes"))
        .as("未请求 includeHexes ⇒ 行上不得夹带交集格")
        .isFalse();

    JsonNode limited = executeOverlaps(Map.of("limit", 2));
    assertThat(limited.get("pairCount").asInt()).as("pairCount 是真值，不随 limit 变小").isEqualTo(9);
    assertThat(limited.get("returnedPairCount").asInt()).isEqualTo(2);
    assertThat(limited.get("truncated").asBoolean()).isTrue();
    assertThat(limited.get("overlaps")).hasSize(2);
    assertThat(limited.get("overlaps").get(0).get("aRegionId").asText()).isEqualTo("r-eq");
    assertThat(limited.get("overlaps").get(0).get("bRegionId").asText()).isEqualTo("r-m4");
    assertThat(limited.get("overlaps").get(1).get("aRegionId").asText()).isEqualTo("r-eq");
    assertThat(limited.get("overlaps").get(1).get("bRegionId").asText()).isEqualTo("r-super");
    assertThat(executeOverlaps(Map.of("limit", 500)).get("returnedPairCount").asInt())
        .as("limit=500 是上界内合法值")
        .isEqualTo(9);
  }

  /**
   * ★ 错误路径 + 桶归属：{@code limit>500} / {@code limit<=0} / {@code minOverlapHexes<1} ⇒
   * BAD_REQUEST（不静默截断）； 未知 regionId ⇒ NOT_FOUND；工具只进 GM 桶（{@code GmOnlyRead} + map 只读资源）。
   */
  @Test
  void mapOverlapsToolRejectsBadBoundsAndIsGmOnly() {
    ToolResult tooMany = call("simos.map.overlaps", Map.of("limit", 501L));
    assertThat(tooMany.success()).isFalse();
    assertThat(tooMany.code()).isEqualTo("BAD_REQUEST");
    assertThat(tooMany.message()).contains("500");

    ToolResult zeroLimit = call("simos.map.overlaps", Map.of("limit", 0L));
    assertThat(zeroLimit.success()).isFalse();
    assertThat(zeroLimit.code()).isEqualTo("BAD_REQUEST");
    assertThat(zeroLimit.message()).contains("limit");

    ToolResult negativeLimit = call("simos.map.overlaps", Map.of("limit", -1L));
    assertThat(negativeLimit.success()).isFalse();
    assertThat(negativeLimit.code()).isEqualTo("BAD_REQUEST");

    ToolResult zeroMin = call("simos.map.overlaps", Map.of("minOverlapHexes", 0L));
    assertThat(zeroMin.success()).isFalse();
    assertThat(zeroMin.code()).isEqualTo("BAD_REQUEST");
    assertThat(zeroMin.message()).contains("minOverlapHexes");

    ToolResult missing = call("simos.map.overlaps", Map.of("regionId", "r-none"));
    assertThat(missing.success()).isFalse();
    assertThat(missing.code()).isEqualTo("NOT_FOUND");
    assertThat(missing.message()).contains("r-none");

    AgentTool tool = shell.toolRegistry().find("simos.map.overlaps").orElseThrow();
    // ★ 注册表里的 GM 面是 RecordingToolSource.RecordingTool 装饰器，标记要看生产类本身。
    assertThat(new MapOverlapsTool(shell.queryService(), ShellConfig.defaults(tempDir).mapId()))
        .as("生产类必须实现 GmOnlyRead（只进 GM 桶的标记来源）")
        .isInstanceOf(GmOnlyRead.class);
    assertThat(tool.resources()).isEqualTo(ToolSupport.MAP_READ);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM))).contains("simos.map.overlaps");
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("决策人桶不得有全图区域几何读口（GM_ONLY_READ_NAMES 的身份）")
        .doesNotContain("simos.map.overlaps");
  }

  /** 执行 {@code simos.map.overlaps} 并要求成功，返回 JSON 体。 */
  private JsonNode executeOverlaps(Map<String, Object> args) throws Exception {
    ToolResult result = call("simos.map.overlaps", args);
    assertThat(result.success()).as(result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  /** 按 (aRegionId, bRegionId) 取行；取不到直接抛（空集合上的断言不许变成恒真）。 */
  private static JsonNode overlapRow(JsonNode body, String a, String b) {
    for (JsonNode row : body.get("overlaps")) {
      if (a.equals(row.get("aRegionId").asText()) && b.equals(row.get("bRegionId").asText())) {
        return row;
      }
    }
    throw new AssertionError("没有重合对 " + a + " / " + b + ": " + body);
  }

  /** 行上的 tag：JSON null（缺 meta / meta.tag=null）读回 Java null，空串仍是空串。 */
  private static String optionalTag(JsonNode row, String field) {
    JsonNode node = row.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  /** JSON 对象的键清单（形状断言用；键集合变多/变少都要当场可见）。 */
  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** 交集格清单 → ["q,r", …]，便于钉升序与逐值。 */
  private static List<String> hexList(JsonNode hexes) {
    List<String> out = new ArrayList<>();
    for (JsonNode hex : hexes) {
      out.add(hex.get("q").asInt() + "," + hex.get("r").asInt());
    }
    return out;
  }

  /**
   * 造 4 个与创世 r-m4（H11+H12）成关系的区域：
   *
   * <pre>
   * r-eq    {H11,H12}     tag=Nation ⇒ 与 r-m4 equal
   * r-part  {H12,H13}     tag=Nation ⇒ 与 r-m4 partial
   * r-super {H11,H12,H13} tag=""     ⇒ 包含 r-m4（bContainsA；空白 tag 必须被 sameTagOnly 当空）
   * r-small {H11}         tag=null   ⇒ 被 r-m4 包含（aContainsB）
   * </pre>
   */
  private void seedOverlapRegions() {
    createRegion(
        "{\"regionId\":\"r-eq\",\"name\":\"相等区\","
            + "\"hexes\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}],\"meta\":{\"tag\":\"Nation\"}}");
    createRegion(
        "{\"regionId\":\"r-part\",\"name\":\"部分区\","
            + "\"hexes\":[{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}],\"meta\":{\"tag\":\"Nation\"}}");
    createRegion(
        "{\"regionId\":\"r-super\",\"name\":\"超集区\","
            + "\"hexes\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}],"
            + "\"meta\":{\"tag\":\"\"}}");
    createRegion("{\"regionId\":\"r-small\",\"name\":\"小子集区\",\"hexes\":[{\"q\":1,\"r\":1}]}");
  }

  /** 窄写：expectedRevision 现取 head（与其它窄写用例同一纪律），失败当场红。 */
  private void createRegion(String payloadJson) {
    ToolResult created =
        callNarrowWrite(
            MapCreateRegionTool.NAME,
            payloadJson,
            shell.coreSimos().head(main()).orElseThrow().value());
    assertThat(created.success()).as("建区域必须成功: %s", created.message()).isTrue();
  }

  /**
   * ★ P3（2026-09-24）：{@code simos.map.render} 出图 → 工件可解析（**同一份字节**）→ 同参同 revision **同一 assetId**。
   *
   * <p>判别性：把 {@code assetDocIds} 从结果里丢掉（图不再随结果出站）⇒ 首条必红；把缓存键里的参数指纹去掉（只按 revision 缓存）⇒ "半径 2 应给出不同
   * id"必红。
   */
  @Test
  void mapRenderProducesAnImageAssetWithStableIdentity() throws Exception {
    ToolResult first =
        call("simos.map.render", Map.of("q", 1L, "r", 1L, "radius", 1L, "format", "image"));
    assertThat(first.success()).as(first.message()).isTrue();
    JsonNode body = JSON.readTree(first.message());
    String assetId = body.get("assetId").asText();
    assertThat(assetId).hasSize(64);
    assertThat(first.assetDocIds()).as("图片资产必须随结果出站（否则 MCP / 决策人两面都拿不到图）").containsExactly(assetId);

    ToolAsset asset = shell.artifactStore().resolve(assetId).orElseThrow();
    assertThat(asset.mediaType()).isEqualTo("image/png");
    assertThat(asset.bytes()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');

    ToolResult again =
        call("simos.map.render", Map.of("q", 1L, "r", 1L, "radius", 1L, "format", "image"));
    assertThat(JSON.readTree(again.message()).get("assetId").asText())
        .as("同参数同 revision ⇒ 同一张图 ⇒ 同一 assetId")
        .isEqualTo(assetId);

    ToolResult wider =
        call("simos.map.render", Map.of("q", 1L, "r", 1L, "radius", 2L, "format", "image"));
    assertThat(JSON.readTree(wider.message()).get("assetId").asText())
        .as("参数不同 ⇒ 另一张图 ⇒ 另一个 id")
        .isNotEqualTo(assetId);
  }

  /**
   * ★★ P4（2026-09-24）：{@code format=auto} **按宿主编排的视觉能力**选形态。
   *
   * <p>装置：同一个工具、同一份世界，只换 {@code ToolContext.config} 里的那一位（决策人链路由运行流按该决策人绑的路由注入）。
   *
   * <p>判别性：把 {@code resolveFormat} 里 auto 的能力判断去掉（回到"无条件出图"）⇒ 第一条断言当场红；把"键缺席 = 出图" 改成"键缺席 = 字符图"⇒
   * 第三条红（那正是**其它调用点行为漂移**的形态：GUI / MCP / 用例谁都没注这个键）。
   */
  @Test
  void mapRenderAutoFollowsTheHostsVisionCapability() throws Exception {
    Map<String, Object> frame = Map.of("q", 1L, "r", 1L, "radius", 1L);

    ToolResult blind =
        call("simos.map.render", frame, Map.of(MapRenderTool.VISION_CONFIG_KEY, false));
    assertThat(blind.success()).as(blind.message()).isTrue();
    assertThat(JSON.readTree(blind.message()).get("format").asText())
        .as("★ 无视觉能力 ⇒ auto 落到字符图（否则模型只会拿到一句「PNG 已生成」）")
        .isEqualTo("text");
    assertThat(blind.assetDocIds()).as("字符图不产工件").isEmpty();

    ToolResult sighted =
        call("simos.map.render", frame, Map.of(MapRenderTool.VISION_CONFIG_KEY, true));
    assertThat(JSON.readTree(sighted.message()).get("format").asText()).isEqualTo("image");
    assertThat(sighted.assetDocIds()).as("有视觉能力 ⇒ 出图并随结果出站").hasSize(1);

    ToolResult absent = call("simos.map.render", frame);
    assertThat(JSON.readTree(absent.message()).get("format").asText())
        .as("★ 键缺席 = 历史行为（出图）：GUI / MCP / 用例不注这个键 ⇒ 形态逐字不变")
        .isEqualTo("image");

    Map<String, Object> explicitText = new LinkedHashMap<>(frame);
    explicitText.put("format", "text");
    ToolResult asked =
        call("simos.map.render", explicitText, Map.of(MapRenderTool.VISION_CONFIG_KEY, true));
    assertThat(JSON.readTree(asked.message()).get("format").asText())
        .as("调用者明确要 text ⇒ 能力位不覆盖它")
        .isEqualTo("text");
  }

  /** ★ P3：图超预算时**自动降采样**（边长折半），直到进预算或触到最小边长——绝不把一张大图塞进上下文预算。 */
  @Test
  void renderServiceDownscalesImagesThatExceedTheByteBudget() {
    RenderService tight =
        new RenderService(
            shell.queryService(),
            new RenderCache(4),
            new ArtifactStore(tempDir.resolve("budget-artifacts")),
            900);

    RenderService.Rendered rendered =
        tight.renderImage(QueryTarget.head(main()), RenderRequest.map(H11, 3));

    boolean withinBudget = rendered.byteSize() <= 900;
    boolean atMinimumSide =
        rendered.width() == RenderRequest.MIN_SIDE || rendered.height() == RenderRequest.MIN_SIDE;
    assertThat(withinBudget || atMinimumSide)
        .as(
            "降采样要么进了预算、要么已到最小边长（实际 %d×%d，%d 字节）",
            rendered.width(), rendered.height(), rendered.byteSize())
        .isTrue();
    assertThat(rendered.width()).isLessThanOrEqualTo(RenderRequest.DEFAULT_SIDE);
    assertThat(rendered.assetId()).isPresent();
  }

  /**
   * ★★ 工具面 M4（2026-09-24）判据 ②：**同源**——磁盘上每个 {@code *Tool.java} 的 {@code NAME} 都必须出现在
   * 某个桶里（防"写了工具类却忘了注册"）。
   *
   * <p>由来：M4 侦察报告 §四-2 实测——catalog 那条"扫源码求同源"的强判据**只覆盖命令类型** （{@code
   * *Handler.java}），对工具面**没有任何等价物** ⇒ 加一条 {@code read/*Tool.java} 却忘了加进 {@code SimosToolSource}
   * 不会红。
   *
   * <p>口径：扫 {@code tools/read} 与 {@code tools/write} 两目录，抽 {@code NAME = "…"}；断言 **扫描到的名字 ⊆
   * 两个桶的并集**，且**GM 桶里没有源码里不存在的名字**（双向：既不漏注册、也没有幽灵条目）。
   */
  @Test
  void everyToolClassOnDiskIsRegisteredInSomeBucket() throws Exception {
    Set<String> onDisk = toolNamesFromSources();
    assertThat(onDisk)
        .as("扫描必须恰为 131 个 *Tool.java 的 NAME（126 条 GM 桶 + 5 条只进决策人桶的条目；扫到 0/漏文件是『扫描器静默』陷阱）")
        .hasSize(131);

    List<String> union =
        Stream.concat(
                toolNames(shell.toolsFor(SimosToolSource.Role.GM)).stream(),
                toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)).stream())
            .distinct()
            .toList();

    assertThat(onDisk).as("★ 磁盘上的每个 *Tool.java 都要挂在某个桶里（类写了却忘了注册 ⇒ 这里红）").isSubsetOf(union);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("反向：GM 桶里不得有源码里不存在的名字（幽灵条目）")
        .isSubsetOf(onDisk);
  }

  /** 扫 {@code tools/read} + {@code tools/write} 的 {@code NAME = "…"}（M4 同源判据用）。 */
  private static Set<String> toolNamesFromSources() throws IOException {
    List<Path> roots =
        List.of(
            Paths.get("src", "main", "java", "io", "mosire", "simos", "app", "tools", "read"),
            Paths.get("src", "main", "java", "io", "mosire", "simos", "app", "tools", "write"));
    Map<String, String> handlerTypes = handlerTypeConstantsFromSources();
    Pattern nameLiteral = Pattern.compile("String NAME = \"([^\"]+)\"");
    Pattern nameHandlerType = Pattern.compile("String NAME = ([A-Za-z0-9_]+)\\.TYPE");
    Set<String> names = new LinkedHashSet<>();
    for (Path root : roots) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file);
          Matcher literal = nameLiteral.matcher(source);
          if (literal.find()) {
            names.add(literal.group(1));
            continue;
          }
          Matcher constant = nameHandlerType.matcher(source);
          if (constant.find()) {
            String resolved = handlerTypes.get(constant.group(1));
            if (resolved == null) {
              throw new IllegalStateException(
                  "解析不到 " + constant.group(1) + ".TYPE（工具名无法从源码静态还原）: " + file);
            }
            names.add(resolved);
          }
        }
      }
    }
    return names;
  }

  /** 各域 {@code *Handler.java} 的 {@code TYPE} 常量（解析 {@code NAME = XxxHandler.TYPE} 用）。 */
  private static Map<String, String> handlerTypeConstantsFromSources() throws IOException {
    Pattern typeConstant = Pattern.compile("String TYPE = \"([^\"]+)\"");
    Map<String, String> types = new LinkedHashMap<>();
    for (Path root : commandHandlerSourceRoots()) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file :
            files.filter(path -> path.getFileName().toString().endsWith("Handler.java")).toList()) {
          Matcher matcher = typeConstant.matcher(Files.readString(file));
          if (matcher.find()) {
            String simple = file.getFileName().toString();
            types.put(simple.substring(0, simple.length() - ".java".length()), matcher.group(1));
          }
        }
      }
    }
    return types;
  }

  /** 全仓实现 {@code CommandHandler} 的模块源码根（handler 类型扫描共用一份，避免两处漂移）。 */
  private static List<Path> commandHandlerSourceRoots() {
    return List.of(
        Paths.get("..", "simos-unit", "src", "main", "java"),
        Paths.get("..", "simos-map", "src", "main", "java"),
        Paths.get("..", "simos-social", "src", "main", "java"),
        Paths.get("..", "simos-sd", "src", "main", "java"),
        Paths.get("..", "simos-economy", "src", "main", "java"),
        Paths.get("..", "simos-actor", "src", "main", "java"),
        Paths.get("..", "simos-army", "src", "main", "java"));
  }

  /** 夹具里那个 hex 的规范地址（{@code resolve}/{@code facets} 用）。 */
  private static String hexAddress() {
    return "map:Map1:hex:1_1";
  }

  @Test
  void readsAreAllowGatedAndDeclareTheirResources() {
    // ★ 按**名字**选，不用索引切片（切片在名单变长后仍合法 ⇒ 判别力静默流失）。
    for (String name : READ_TOOL_NAMES) {
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
    assertThat(shell.toolRegistry().find("simos.economy.hex").orElseThrow().resources())
        .as("economy 是数据、map 是视野判据（hexVisible）——两个命名空间都要表态")
        .isEqualTo(
            ResourceManifest.of(
                Map.of("economy", ResourcePolicy.READ_ONLY, "map", ResourcePolicy.READ_ONLY)));
    assertThat(shell.toolRegistry().find("simos.command.catalog").orElseThrow().resources())
        .isEqualTo(ResourceManifest.NONE);
    assertThat(shell.toolRegistry().find("simos.timeline.branches").orElseThrow().resources())
        .isEqualTo(ResourceManifest.NONE);
  }

  @Test
  void writesAreSensitiveAndAskWithTheToolNameAsClassKey() {
    List<String> covered = writeFaceCoveredByTheWriteGate();
    assertThat(covered)
        .as("★ 写闸覆盖集 == 写工具全集（现有口 ∖ 读名单）：退回索引切片会让新增的写工具静默逃出写闸")
        .containsExactlyInAnyOrderElementsOf(WRITE_TOOL_NAMES);
    assertThat(READ_TOOL_NAMES).as("读名单与写名单互斥").doesNotContainAnyElementsOf(WRITE_TOOL_NAMES);
    assertThat(Stream.concat(READ_TOOL_NAMES.stream(), WRITE_TOOL_NAMES.stream()).toList())
        .as("读名单 + 写名单 == 现有口全名单（完整）：名单加项却没登记到任一侧，这里红")
        .containsExactlyInAnyOrderElementsOf(GM_TOOL_NAMES);
    for (String name : covered) {
      AgentTool tool = shell.toolRegistry().find(name).orElseThrow();
      assertThat(tool.spec().sensitive()).as("%s 是敏感写", name).isTrue();
      assertThat(tool.spec().noExport()).as("%s 不外发标记为假（无内部工具）", name).isFalse();
      ToolGate gate = tool.gate(context(tool, Map.of()));
      assertThat(gate).as("%s 需审批", name).isInstanceOf(ToolGate.Ask.class);
      assertThat(((ToolGate.Ask) gate).classKey()).isEqualTo(name);
      assertThat(((ToolGate.Ask) gate).kind())
          .as("M1 判据 3：%s 的 gate 是 AskKind.SENSITIVE（走审批门链）", name)
          .isEqualTo(AskKind.SENSITIVE);
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
    // ★ §十一：本用例只验"推进一天"这条基本路径（to = from + 1）；多日推进由 AdvanceTool 的 to=from+N 承担。
    args.put("to", 8L);

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

  /**
   * ★ 日制裁定（设计稿 §3）：{@code simos.advance} 的 {@code to} **缺省 = from + 1**（本工具推进一天） ⇒ 不传 {@code to}
   * 也必须提交，且落盘那一行的世界时间戳恰好 = {@code from + 1}。 判别力：把工具改回"缺省 = 无上界"（旧口径）⇒ Core 第 0 项拒（缺 to 无上界）⇒ 本条红。
   */
  @Test
  void advanceToolDefaultsToExactlyOneDay() throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", 1);
    args.put("from", 7L); // ★ 刻意不传 to

    ToolResult result = call("simos.advance", args);

    assertThat(result.success()).as(result.message()).isTrue();
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      long head = shell.coreSimos().head(main()).orElseThrow().value();
      RevisionRow row =
          new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", head)).orElseThrow();
      assertThat(row.timestamp().tick()).as("缺省 to = from + 1 ⇒ 世界只前进一天（7 → 8）").isEqualTo(8L);
    }
  }

  /**
   * ★ 2026-09-25 §十一：{@code simos.advance} 支持**一次推进 N 天**（{@code to = from + N}）——一条命令、一条 revision，
   * 时间戳 = {@code to}。判别力：把工具/Core 改回"恰好一天" ⇒ 本条的 3 天区间当场被拒 ⇒ 红。
   */
  @Test
  void advanceToolAcceptsAMultiDaySpanInOneCommand() throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", 1);
    args.put("from", 7L);
    args.put("to", 10L); // ★ 3 天

    ToolResult result = call("simos.advance", args);

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("ref").get("revision").asLong()).as("一次 N 天落一条 revision").isEqualTo(2L);
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 2)).orElseThrow();
      assertThat(row.timestamp().tick()).as("时间戳 = to（7 → 10，一次推进 3 天）").isEqualTo(10L);
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

  // ── M1 判据 4：前置即错——8 条 map 窄写各一条「坏载荷 ⇒ 可读 REJECTED 且 head 不变」────────
  //    ★ 域层守卫逐条已在（工具层不再写一遍：那份校验能被 simos.command.submit 绕过 ⇒ 是装饰）。
  //    ★ **每条各一个用例**：写成循环里断 7 次时，变异杀掉一条其余六条照样绿（判别力被稀释）。

  @Test
  void mapSetTerrainToolSurfacesTheDomainRejectionForAnUnknownTerrain() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        MapSetTerrainTool.NAME,
        "{\"hexes\":[{\"q\":1,\"r\":1}],\"terrain\":\"not_a_terrain\"}",
        "未知地形类型");
  }

  @Test
  void mapSetEdgeToolSurfacesTheDomainRejectionForAnUnregisteredKind() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        MapSetEdgeTool.NAME,
        "{\"kind\":\"not_registered\",\"edges\":[\"1_1|1_2\"],\"mode\":\"merge\"}",
        "未知连通性类型");
  }

  /**
   * ★ {@code map.CreateRegion} 的坏载荷 = **regionId 已存在**：本夹具里没有任何区域，故先经**同一条窄工具**真建一个， 再用同一个 id
   * 建第二次——第二次必须被域层拒、head 停在第一次之后的那个号。
   */
  @Test
  void mapCreateRegionToolSurfacesTheDomainRejectionForADuplicateId() throws Exception {
    String payload = "{\"regionId\":\"t1-region\",\"name\":\"甲区\",\"hexes\":[{\"q\":1,\"r\":1}]}";
    ToolResult created = callNarrowWrite(MapCreateRegionTool.NAME, payload, 1L);
    assertThat(created.success()).as(created.message()).isTrue();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("第一次建区域成功 ⇒ head 前进一步")
        .isEqualTo(2L);

    assertDomainRejectedAndHeadUnchanged(MapCreateRegionTool.NAME, payload, "区域已存在");
  }

  @Test
  void mapUpdateRegionToolSurfacesTheDomainRejectionWhenNeitherHexesNorMetaIsGiven()
      throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        MapUpdateRegionTool.NAME,
        "{\"regionId\":\"t1-region\"}",
        "map.UpdateRegion 必须至少给 hexes 与 meta 之一");
  }

  @Test
  void mapDeleteRegionToolSurfacesTheDomainRejectionForAnUnknownRegion() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        MapDeleteRegionTool.NAME, "{\"regionId\":\"没有这个区域\"}", "区域不存在");
  }

  @Test
  void mapRandomizeRegionToolSurfacesTheDomainRejectionForAnEmptySelection() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        MapRandomizeRegionTool.NAME,
        "{\"hexes\":[],\"terrainA\":\"plains\",\"terrainB\":\"desert\",\"seed\":7}",
        "hexes 不得为空：一条 map.RandomizeRegion 至少要选一格");
  }

  @Test
  void mapRegisterPathwayGroupToolSurfacesTheDomainRejectionForAMalformedColor() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        MapRegisterPathwayGroupTool.NAME,
        "{\"id\":\"t1-canal\",\"name\":\"运河\",\"color\":\"red\"}",
        "color 必须是 #RRGGBB 形式");
  }

  // ── M2 判据 4：前置即错——20 条 unit 窄写各一条「坏载荷 ⇒ 可读 REJECTED 且 head 不变」────────
  //    ★ 与 M1 同裁决：拒绝文案已在（载荷层 / 域层），工具层**不重复校验**——那份校验能被
  //      simos.command.submit 绕过 ⇒ 是装饰。本任务的义务是**证明理由真的到达调用方**。
  //    ★ **每条各一个用例**：写成循环里断 20 次时，变异杀掉一条其余十九条照样绿（判别力被稀释）。
  //    ★ 断的是**完整消息片段**（如「字段 status 不是合法状态」），不是字段 token——只判 token 判不出是哪一层拒的
  //      （域层的兜底消息往往也含同一个字段名，T4/T10-b 那一族的坑）。

  @Test
  void unitRenameToolSurfacesTheDomainRejectionForAnUnknownUnit() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitRenameTool.NAME, "{\"id\":\"nope\",\"name\":\"新名\"}", "单位不存在");
  }

  @Test
  void unitCreateToolSurfacesTheDomainRejectionForADuplicateId() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitCreateTool.NAME,
        "{\"id\":\"u-1\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":1},"
            + "\"manpower\":[{\"type\":\"步兵\",\"amount\":1}],\"equipment\":[],"
            + "\"speed\":2,\"mobilityPerMille\":500}",
        "单位 id 已存在");
  }

  @Test
  void unitReparentToolSurfacesTheDomainRejectionForAnUnknownParent() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitReparentTool.NAME, "{\"id\":\"u-1\",\"parent\":\"nope\"}", "父单位不存在");
  }

  @Test
  void unitSetCompositionToolSurfacesTheDomainRejectionForANegativeAmount() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitSetCompositionTool.NAME,
        "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-1}],\"equipment\":[]}",
        "amount 必须 ≥ 0");
  }

  @Test
  void unitPlaceAtToolSurfacesTheDomainRejectionForAnUnknownUnit() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitPlaceAtTool.NAME, "{\"id\":\"nope\",\"hex\":{\"q\":1,\"r\":1}}", "单位不存在");
  }

  @Test
  void unitPlanRouteToolSurfacesTheDomainRejectionForASingleWaypoint() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitPlanRouteTool.NAME,
        "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1}]}",
        "waypoints 至少两个");
  }

  @Test
  void unitCancelRouteToolSurfacesTheDomainRejectionForAnUnknownUnit() throws Exception {
    assertDomainRejectedAndHeadUnchanged(UnitCancelRouteTool.NAME, "{\"id\":\"nope\"}", "单位不存在");
  }

  /** ★ 坏载荷 = **单位仍是链的 commander**：先经**同一条窄工具**真建一条链，再解散它的 commander。 */
  @Test
  void unitDisbandToolSurfacesTheDomainRejectionForAUnitThatIsStillAChainCommander()
      throws Exception {
    ToolResult chained =
        callNarrowWrite(
            UnitCreateCommandChainTool.NAME,
            "{\"chainId\":\"c-1\",\"name\":\"一路\",\"commander\":\"u-1\",\"members\":[\"u-1\"]}",
            1L);
    assertThat(chained.success()).as(chained.message()).isTrue();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("建链成功 ⇒ head 前进一步")
        .isEqualTo(2L);

    assertDomainRejectedAndHeadUnchanged(UnitDisbandTool.NAME, "{\"id\":\"u-1\"}", "仍是链");
  }

  /**
   * ★ 词表外的状态串**在载荷层就被拒**（`UnitPayloads.requireStatus`），不落到域层 ⇒ 断的是**载荷层的完整消息**。
   *
   * <p>★ 这条**特意不取 domain 那条兜底文案**：`status` 这个 token 在域层消息里也会出现 ⇒ 只判 token 判不出是哪一层拒的。
   */
  @Test
  void unitSetStatusToolSurfacesThePayloadRejectionForAnUnknownStatusWord() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitSetStatusTool.NAME, "{\"id\":\"u-1\",\"status\":\"NOT_A_STATUS\"}", "字段 status 不是合法状态");
  }

  @Test
  void unitAttachToolSurfacesTheDomainRejectionForACyclicParent() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitAttachTool.NAME, "{\"id\":\"u-1\",\"parent\":\"u-1\"}", "会成环");
  }

  @Test
  void unitDetachToolSurfacesTheDomainRejectionForAnAlreadyRootUnit() throws Exception {
    assertDomainRejectedAndHeadUnchanged(UnitDetachTool.NAME, "{\"id\":\"u-1\"}", "没有父");
  }

  @Test
  void unitReparentSubtreeToolSurfacesTheDomainRejectionForACyclicParent() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitReparentSubtreeTool.NAME, "{\"rootId\":\"u-1\",\"parent\":\"u-1\"}", "会成环");
  }

  @Test
  void unitSetFormationOffsetToolSurfacesTheRetiredCommandRejection() throws Exception {
    // ★ 2026-09-24 编制 v2：RelativeOffset 无消费点，unit.SetFormationOffset 命令已退役（具名拒、不做参数校验）；
    //   窄工具面仍照常登记（工具名 == 命令类型），拒因原样到达调用方。
    assertDomainRejectedAndHeadUnchanged(
        UnitSetFormationOffsetTool.NAME,
        "{\"id\":\"nope\",\"dq\":1,\"dr\":-1}",
        "unit.SetFormationOffset 已退役");
  }

  @Test
  void unitSplitFormationToolSurfacesTheDomainRejectionForAnEmptySubUnitList() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitSplitFormationTool.NAME, "{\"rootId\":\"u-1\",\"subUnitIds\":[]}", "subUnitIds 不得为空");
  }

  /** ★ 坏载荷 = **两个不同格的单位**：先真建 u-2 到 `(1,2)`，再让它与 `(1,1)` 的 u-1 合体。 */
  @Test
  void unitMergeFormationToolSurfacesTheDomainRejectionForTwoUnitsOnDifferentHexes()
      throws Exception {
    ToolResult created =
        callNarrowWrite(
            UnitCreateTool.NAME,
            "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":2},"
                + "\"manpower\":[{\"type\":\"步兵\",\"amount\":1}],\"equipment\":[],"
                + "\"speed\":2,\"mobilityPerMille\":500}",
            1L);
    assertThat(created.success()).as(created.message()).isTrue();

    assertDomainRejectedAndHeadUnchanged(
        UnitMergeFormationTool.NAME, "{\"childId\":\"u-2\",\"parentId\":\"u-1\"}", "只有同格才能合体");
  }

  /** ★ 坏载荷 = **中段不可达**：本夹具只有 `(1,1)/(1,2)/(1,3)` 三格，第三点 `(5,5)` 在图外。 */
  @Test
  void unitPlanSparseRouteToolSurfacesTheDomainRejectionForAnUnreachableSegment() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitPlanSparseRouteTool.NAME,
        "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2},{\"q\":5,\"r\":5}]}",
        "稀疏路线的段不可达");
  }

  @Test
  void unitSetRejoinTargetToolSurfacesTheDomainRejectionForASelfTarget() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitSetRejoinTargetTool.NAME, "{\"id\":\"u-1\",\"target\":\"u-1\"}", "回归目标不得是自身");
  }

  /** ★ 坏载荷 = **chainId 已存在**：先经同一条窄工具真建一条链，再用同一个 id 建第二次。 */
  @Test
  void unitCreateCommandChainToolSurfacesTheDomainRejectionForADuplicateChainId() throws Exception {
    String payload =
        "{\"chainId\":\"c-1\",\"name\":\"一路\",\"commander\":\"u-1\",\"members\":[\"u-1\"]}";
    ToolResult created = callNarrowWrite(UnitCreateCommandChainTool.NAME, payload, 1L);
    assertThat(created.success()).as(created.message()).isTrue();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("建链成功 ⇒ head 前进一步")
        .isEqualTo(2L);

    assertDomainRejectedAndHeadUnchanged(UnitCreateCommandChainTool.NAME, payload, "链 id 已存在");
  }

  @Test
  void unitUpdateCommandChainToolSurfacesTheDomainRejectionForAnUnknownChain() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitUpdateCommandChainTool.NAME, "{\"chainId\":\"nope\",\"name\":\"新名\"}", "链不存在");
  }

  /** ★ 坏载荷 = **未知装备类型**：P14 明写不视作 0（manpower 给空增量，确保先撞的是装备那一关）。 */
  @Test
  void unitApplyCasualtiesToolSurfacesTheDomainRejectionForAnUnknownEquipmentType()
      throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitApplyCasualtiesTool.NAME,
        "{\"id\":\"u-1\",\"manpower\":[],\"equipment\":[{\"type\":\"没这个装备\",\"amount\":-1}]}",
        "未知装备类型");
  }

  // ── M3 判据 4：前置即错——15 条 GM-only Sd* 窄写各一条「坏载荷 ⇒ 可读 REJECTED 且 head 不变」──────────
  //    ★ 与 M1/M2 同裁决：拒绝文案已在（载荷层 / 域层），工具层**不重复校验**——那份校验能被
  //      simos.command.submit 绕过 ⇒ 是装饰。本任务的义务是**证明理由真的到达调用方**。
  //    ★ **每条各一个用例**：写成循环里断 12 次时，变异杀掉一条其余十一条照样绿（判别力被稀释）。
  //    ★ **每条都先造出使该前置可达的状态**：跳过前置去撞另一个错，断言就退化成判别的前置。
  //    ★ 断的是**完整消息片段**（如「action 引用的阶段不存在」），不是字段 token——只判 token
  //      判不出是哪一层拒的（域层的兜底文案往往也含同一个字段名）。

  /** ★ 坏载荷 = **homeRegion 无 `nation:` 前缀 tag**（R13）：先经真窄写建一个 tag 为 `plain` 的区域。 */
  @Test
  void sdCreateNationToolSurfacesTheDomainRejectionForARegionWithoutANationTag() throws Exception {
    ToolResult region =
        callNarrowWrite(
            MapCreateRegionTool.NAME,
            "{\"regionId\":\"t3-region\",\"name\":\"甲区\",\"hexes\":[{\"q\":1,\"r\":1}],"
                + "\"meta\":{\"tag\":\"plain\"}}",
            1L);
    assertThat(region.success()).as(region.message()).isTrue();

    assertDomainRejectedAndHeadUnchanged(
        SdCreateNationTool.NAME,
        "{\"nationId\":\"n1\",\"name\":\"甲国\",\"homeRegionId\":\"t3-region\","
            + "\"adminBudgetPerTick\":10}",
        "无国家 tag");
  }

  /**
   * ★ 坏载荷 = **已删除的 {@code nationId} 键**（阶段 12：Army 改认 {@code masterGovUnitId}）：域层必须具名拒并把理由原文送到调用方——
   * 静默忽略会让调用方以为"国家归属已经写进去了"。
   */
  @Test
  void sdCreateArmyToolSurfacesTheNamedRejectionForTheRemovedNationId() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        SdCreateArmyTool.NAME,
        "{\"armyId\":\"a1\",\"nationId\":\"n1\",\"rootUnitId\":\"u-1\",\"name\":\"第一军\"}",
        "不再接受 nationId");
  }

  /**
   * ★ 坏载荷 = **allowedTools 里含通用写**（N9）：先经真窄写造出 `nation:n1` tag 的区域与国家，使 affiliation 检查通过——
   * 否则断言会退化成判「affiliation 目标不存在」。
   */
  @Test
  void sdCreateDecisionMakerToolSurfacesTheDomainRejectionForAGenericWriteInAllowedTools()
      throws Exception {
    ToolResult region =
        callNarrowWrite(
            MapCreateRegionTool.NAME,
            "{\"regionId\":\"t3-region\",\"name\":\"甲区\",\"hexes\":[{\"q\":1,\"r\":1}],"
                + "\"meta\":{\"tag\":\"nation:n1\"}}",
            1L);
    assertThat(region.success()).as(region.message()).isTrue();
    ToolResult nation =
        callNarrowWrite(
            SdCreateNationTool.NAME,
            "{\"nationId\":\"n1\",\"name\":\"甲国\",\"homeRegionId\":\"t3-region\","
                + "\"adminBudgetPerTick\":10}",
            2L);
    assertThat(nation.success())
        .as("前置可达：带 `nation:` tag 的区域能建出国家 —— %s", nation.message())
        .isTrue();

    assertDomainRejectedAndHeadUnchanged(
        SdCreateDecisionMakerTool.NAME,
        "{\"id\":\"dm-1\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
            + "\"allowedTools\":[\"simos.command.submit\"],\"cadence\":5}",
        "不得含通用写");
  }

  /** ★ 坏载荷 = **地址连两段都没有**：`Address.parse` 的原文理由是地址语法的前置（载荷层）。 */
  @Test
  void sdPutInfoToolSurfacesThePayloadRejectionForAMalformedAddress() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        SdPutInfoTool.NAME, "{\"address\":\"nope\",\"key\":\"k\",\"value\":\"v\"}", "地址至少两段");
  }

  /** ★ 坏载荷 = **combatId 已存在**：先经同一条窄工具真建一个交战，再用同一个 id 建第二次。 */
  @Test
  void sdCreateCombatToolSurfacesTheDomainRejectionForADuplicateId() throws Exception {
    String payload = "{\"combatId\":\"c1\",\"name\":\"战役甲\",\"participants\":[\"u-1\"]}";
    ToolResult created = callNarrowWrite(SdCreateCombatTool.NAME, payload, 1L);
    assertThat(created.success()).as(created.message()).isTrue();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("第一次建交战成功 ⇒ head 前进一步")
        .isEqualTo(2L);

    assertDomainRejectedAndHeadUnchanged(SdCreateCombatTool.NAME, payload, "交战已存在");
  }

  /** ★ 坏载荷 = **combatId 不存在**：`stage` 本身**必须合法**（载荷解析在交战查询之前），否则拒的是载荷层而不是这一关。 */
  @Test
  void sdAddCombatStageToolSurfacesTheDomainRejectionForAnUnknownCombat() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        SdAddCombatStageTool.NAME,
        "{\"combatId\":\"c-nope\",\"stage\":{\"stageId\":\"s1\",\"name\":\"阶段一\","
            + "\"outcomes\":{\"options\":[{\"id\":\"opt-a\",\"label\":\"甲\",\"weight\":1}]}}}",
        "交战不存在");
  }

  /** ★ 坏载荷 = **stageId 不在该交战里**：先造出「有交战、有首阶段」的状态。 */
  @Test
  void sdSetStageOutcomeTableToolSurfacesTheDomainRejectionForAnUnknownStage() throws Exception {
    seedCombatWithFirstStage();

    assertDomainRejectedAndHeadUnchanged(
        SdSetStageOutcomeTableTool.NAME,
        "{\"combatId\":\"c1\",\"stageId\":\"s-nope\","
            + "\"outcomes\":{\"options\":[{\"id\":\"opt-b\",\"label\":\"乙\",\"weight\":1}]}}",
        "阶段不存在");
  }

  /** ★ 坏载荷 = **结局不在该阶段的 outcomeTable 里**（N2）：首阶段的表里只有 `opt-a`，提交 `opt-b`。 */
  @Test
  void sdCommitCombatOutcomeToolSurfacesTheDomainRejectionForAnOutcomeOutsideTheTable()
      throws Exception {
    seedCombatWithFirstStage();

    assertDomainRejectedAndHeadUnchanged(
        SdCommitCombatOutcomeTool.NAME,
        "{\"combatId\":\"c1\",\"stageId\":\"s1\",\"selectedOutcomeId\":\"opt-b\"}",
        "结局不在该阶段的 outcomeTable 里");
  }

  /** ★ 坏载荷 = **未知装备类型**（P14：不视作 0）；`personnel` 给 0 确保先撞的是装备那一关。 */
  @Test
  void sdRecordCasualtiesToolSurfacesTheDomainRejectionForAnUnknownEquipmentKey() throws Exception {
    seedCombatWithFirstStage();

    assertDomainRejectedAndHeadUnchanged(
        SdRecordCasualtiesTool.NAME,
        "{\"combatId\":\"c1\",\"stageId\":\"s1\",\"deltas\":[{\"unit\":\"u-1\",\"personnel\":0,"
            + "\"equipment\":{\"没这个装备\":-1},\"lossClass\":\"PERMANENT\"}]}",
        "未知装备类型");
  }

  /**
   * ★ 坏载荷 = **action 引用的阶段不存在**：`Action.SetStage` 先查 CombatState（首阶段兼建的那个 `cs-1` 在），再查阶段——所以 `cs-1`
   * 必须真存在，否则拒的是「CombatState 不存在」那一关。
   *
   * <p>★★ **两个内层 id 必须写成对象形态 `{"value":"…"}`、不能写标量**——这是**当场实测**的线格式约束，不是笔误：
   * `CombatStateId`/`CombatStageId` 是单 `String` record 且无 `@JsonCreator`，写成 `"cs-1"` 时 Jackson
   * 在**载荷层** 就报 {@code no String-argument constructor/factory method to deserialize from String
   * value ('cs-1')}， 于是拒绝理由变成载荷层那句、断言会退化成判另一个前置（改回标量即红在此）。★ 该缺口属 **sd 域**（本任务不改
   * `simos-sd`），已记入报告「我未能核实的」。
   */
  @Test
  void sdRegisterEffectToolSurfacesTheDomainRejectionForAStageThatDoesNotExist() throws Exception {
    seedCombatWithFirstStage();

    assertDomainRejectedAndHeadUnchanged(
        SdRegisterEffectTool.NAME,
        "{\"effectId\":\"e-1\",\"kind\":\"SCHEDULED\","
            + "\"trigger\":{\"@class\":\"at_or_after_tick\",\"tick\":5},"
            + "\"action\":{\"@class\":\"set_stage\",\"combatState\":{\"value\":\"cs-1\"},"
            + "\"stage\":{\"value\":\"s-nope\"}}}",
        "action 引用的阶段不存在");
  }

  /** ★ 坏载荷 = **effectId 不存在**（无需前置：效果表本来就是空的）。 */
  @Test
  void sdCancelEffectToolSurfacesTheDomainRejectionForAnUnknownEffect() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        SdCancelEffectTool.NAME, "{\"effectId\":\"e-nope\"}", "效果不存在");
  }

  /**
   * ★ 坏载荷 = **decisionMakerId 不存在**；`providerId` 是一个**从没登记过**的值。
   *
   * <p>★★ **§4 的判据在这一条上兑现**：本工具**不写 provider 存在性校验**——理由不是「某条既有用例会红」，而是那条校验落在工具层 **能被 {@code
   * simos.command.submit} 绕过 ⇒ 是装饰**。故这里的拒绝理由**只能**来自域层的 decisionMaker 查询，而 `providerId`
   * 照旧原样落到域层（登记与否由绑定期的真实消费者裁决）。
   */
  @Test
  void sdSetDecisionMakerProviderToolSurfacesTheDomainRejectionForAnUnknownDecisionMaker()
      throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        SdSetDecisionMakerProviderTool.NAME,
        "{\"decisionMakerId\":\"dm-nope\",\"providerId\":\"p-never-registered\"}",
        "决策人不存在");
  }

  /**
   * M3 判据 4 的共用前置：经**真窄写**建一个交战 + 它的第一个阶段（首阶段兼建 {@code CombatState cs-1}，见 C1-a 的取代说明）。
   *
   * <p>★ 只用同一条工具链造状态、不塞夹具——这样「前置可达」本身也被走到（与 M1 的 {@code
   * mapCreateRegionToolSurfacesTheDomainRejectionForADuplicateId} 同法）。
   */
  private void seedCombatWithFirstStage() throws Exception {
    ToolResult combat =
        callNarrowWrite(SdCreateCombatTool.NAME, "{\"combatId\":\"c1\",\"name\":\"战役甲\"}", 1L);
    assertThat(combat.success()).as(combat.message()).isTrue();
    ToolResult stage =
        callNarrowWrite(
            SdAddCombatStageTool.NAME,
            "{\"combatId\":\"c1\",\"combatStateId\":\"cs-1\",\"hex\":{\"q\":1,\"r\":1},"
                + "\"stage\":{\"stageId\":\"s1\",\"name\":\"阶段一\","
                + "\"outcomes\":{\"options\":[{\"id\":\"opt-a\",\"label\":\"甲\",\"weight\":1}]}}}",
            2L);
    assertThat(stage.success()).as(stage.message()).isTrue();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("交战 + 首阶段各留一条 revision")
        .isEqualTo(3L);
  }

  /**
   * 窄写调用形态：{@code payloadJson} + {@code branch=main} + {@code expectedRevision}（类型由工具钉死，schema
   * 里没有）。
   */
  private ToolResult callNarrowWrite(String toolName, String payloadJson, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", payloadJson);
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return call(toolName, args);
  }

  /**
   * M1 判据 4 的共同断言：坏载荷经窄工具提交 ⇒ {@code REJECTED} + **域层理由原文到达调用方** + head 不变。
   *
   * <p>★ 理由断的是**域层文案的关键片段**（如「未知地形类型」），不是 {@code Rejected} 这个词——只判 token 判不出是哪一层拒的
   * （域层的空载荷兜底消息往往也含同一个字段名）。
   */
  private void assertDomainRejectedAndHeadUnchanged(
      String toolName, String payloadJson, String reasonFragment) throws Exception {
    long headBefore = shell.coreSimos().head(main()).orElseThrow().value();
    ToolResult result = callNarrowWrite(toolName, payloadJson, headBefore);

    assertThat(result.success()).as("%s：坏载荷不得成功", toolName).isFalse();
    assertThat(result.code()).as("%s 折成 REJECTED", toolName).isEqualTo("REJECTED");
    assertThat(JSON.readTree(result.message()).get("reason").asText())
        .as("%s：域层理由原文必须到达调用方", toolName)
        .contains(reasonFragment);
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("%s：被拒的命令不留 revision", toolName)
        .isEqualTo(headBefore);
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
    // ★ R2（T0）：口径 = 有批次 ⇒ 批次求和（H11 有批次 ⇒ Σ = 1,350，算式见 mixedGroups），
    //   不再是农村序列的取值（那是**回退**口径，由 GuiApiTest 的 H13 那条用例钉）。
    assertThat(JSON.readTree(population.message()).get("population").asLong()).isEqualTo(1_350L);

    ToolResult list = call("simos.unit.list", Map.of());
    JsonNode units = JSON.readTree(list.message()).get("units");
    assertThat(units).hasSize(1);
    assertThat(units.get(0).get("id").asText()).isEqualTo("u-1");
    assertThat(units.get(0).get("position").get("q").asInt()).isEqualTo(1);
    assertThat(units.get(0).get("position").get("r").asInt()).isEqualTo(1);
  }

  /**
   * ★★ **R1.5：MCP 读口与 GUI 端点发的是同一份视图** —— {@code simos.social.population} 的体（含批次的现算读数） 必须**逐字段等于**
   * {@code ApiViews.population}（GUI 路由调的就是它）。
   *
   * <p>★ 判别力分工（两条各挡一件事，别混）：
   *
   * <ul>
   *   <li>**逐值断言**（1,350 / 1,000 / 350 / 400·900·50 / 450·900，算式见 {@link
   *       #mixedGroups()}）挡的是"**装配错**" ——把城乡接反、漏掉某一档、只算农村（R1.5 之前的实现就是只读农村序列）都会当场红；
   *   <li>**与 {@code ApiViews} 的整体相等**挡的是"**面分叉**"：{@code ToolSupport.population} 曾**另有一份**四行字段清单
   *       （R1.5 才合并成转调）——谁再照抄一份、忘了带 {@code groups} 块，这里就红（AGENT.md §8.3 的硬规矩）。
   * </ul>
   *
   * <p>★ 期望值**从视图层现取**（{@code ApiViews.population}）而不是另写一份字面量表：两张表相等才有意义——作者写第二份表
   * 就等于把"同一资源的两个形状"再抄一遍（正是本用例要禁的东西）。
   */
  @Test
  void populationToolServesTheSameViewAsTheGuiRoute() throws Exception {
    ToolResult result = call("simos.social.population", Map.of("q", 1, "r", 1));
    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());

    // 同一个函数（GUI 的 /api/social/population 调的就是它）⇒ 两边的体必须逐字段相同。
    Map<String, Object> expected =
        ApiViews.population(
            ApiViews.socialData(shell.queryService().stateAt(QueryTarget.head(main()))),
            ApiViews.economyData(shell.queryService().stateAt(QueryTarget.head(main()))),
            H11,
            T7);
    // ★ 比**文本**而不是比 JsonNode：工具面的体是 Jackson 序列化过的（小整数被读成 IntNode），而期望树是
    //   `valueToTree(Long)`（LongNode）——同一份 JSON 的两种节点类型，逐节点比会假红（本用例实测踩过一次）。
    //   比文本同时还钉住了**键序**（两边都是 LinkedHashMap 保序）。
    assertThat(body.toString())
        .as("MCP 工具的体 == GUI 路由视图（同一份 ApiViews.population）")
        .isEqualTo(JSON.valueToTree(expected).toString());

    JsonNode groups = body.get("groups");
    // ★ 先判"块在不在"再取值：上面那条整体相等**挡不住"两份一起变了"**（比如共享视图自己被改掉了块）——
    //   那种情况下两边同时没有 groups 仍然相等 ⇒ 必须由逐值断言接手（本断言就是它的干净入口）。
    assertThat(groups).as("MCP 读口必须带 groups 块（R1.5）").isNotNull();
    assertThat(groups.get("total").asLong()).as("Σ 批次").isEqualTo(1_350L);
    assertThat(groups.get("urban").asLong()).as("城镇 300 + 700").isEqualTo(1_000L);
    assertThat(groups.get("rural").asLong()).as("农村 100 + 200 + 50").isEqualTo(350L);
    assertThat(groups.get("ageBrackets").get("0-14").asLong())
        .as("男 5 岁 100 + 男 10 岁 300")
        .isEqualTo(400L);
    assertThat(groups.get("ageBrackets").get("15-59").asLong())
        .as("女 20 岁 200 + 女 30 岁 700")
        .isEqualTo(900L);
    assertThat(groups.get("ageBrackets").get("60+").asLong()).as("男 70 岁 50").isEqualTo(50L);
    assertThat(groups.get("sex").get("MALE").asLong()).as("男 100 + 50 + 300").isEqualTo(450L);
    assertThat(groups.get("sex").get("FEMALE").asLong()).as("女 200 + 700").isEqualTo(900L);

    // ★★ R2：MCP 读口也带住了新两维 —— T0 的**口径来源**与 T4 的**劳动块**。
    //   ★ 本夹具的经济切片是**未激活**的（{@code EconomyData.empty()}，见 seedGenesis 的注释）⇒ 劳动块全 0：
    //     它证明的是"**工具面确实带了这一维**"（漏了 `labor` 键 ⇒ 这里 NPE ⇒ 红），
    //     而**逐值的**劳动判别力在 GuiApiTest 的富夹具上（那边有真实配额：715,000 / 915,000 / 781）。
    assertThat(body.get("source").asText()).as("T0：来源 = 批次").isEqualTo("batches");
    JsonNode labor = body.get("labor");
    assertThat(labor).as("劳动块必须在（R2 的 T4）").isNotNull();
    assertThat(labor.get("availableMilli").asLong()).as("未激活的经济 ⇒ 没有供给记录").isZero();
    assertThat(labor.get("allocatedMilli").asLong()).isZero();
    assertThat(labor.get("utilizationPerMille").asLong()).as("可用为 0 ⇒ 占用率 0（不做除零）").isZero();
    assertThat(labor.get("actors")).isEmpty();
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
    return call(toolName, args, Map.of());
  }

  /**
   * 带**宿主编排配置**的调用（P4：{@code ToolContext.config} 通道；键见 {@code MapRenderTool#VISION_CONFIG_KEY}）。
   */
  private ToolResult call(
      String toolName, Map<String, Object> args, Map<String, Object> toolConfig) {
    AgentTool tool = shell.toolRegistry().find(toolName).orElseThrow();
    return tool.execute(context(tool, args, toolConfig));
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return context(tool, args, Map.of());
  }

  private static ToolContext context(
      AgentTool tool, Map<String, Object> args, Map<String, Object> toolConfig) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), toolConfig, args)
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
    // ★★ R1.5：H11 挂**刻意混合**的批次（男女 × 城乡 × 三档各非零，见 mixedGroups）——MCP 读口的用例靠它避免假绿。
    SocialData social =
        new SocialData(
            new LinkedHashMap<>(Map.of(H11, populationSeries())), Map.of(), mixedGroups());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty()),
                // ★ D1/D4：army 切片在场（读取口 simos.army.combats/combat 与交战目标检查都需要它；
                //   本夹具为空表 ⇒ 读口给空数组 / 单条详情给 NOT_FOUND）。
                "army", new ArmySnapshot(ref("main", 1), T7, ArmyData.empty()),
                // ★ R2a：经济切片在场（本夹具未激活 ⇒ simos.economy.hex 应给 activated=false、空 industries）。
                "economy",
                    new EconomySnapshot(
                        ref("main", 1),
                        T7,
                        EconomyData.empty()), // ★ T5：actor 切片也必须在场（产权落账口要求它 —— 缺席 ⇒ 协调器当场抛）。
                "actor", new ActorSnapshot(ref("main", 1), T7, ActorData.empty())),
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
                    new SdCodec(),
                    new EconomyCodec(),
                    new ActorCodec(),
                    new ArmyCodec())));
  }

  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
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

  /**
   * ★★ **H11 的混合批次夹具**（与 {@code GuiApiTest} 同一份算式，R1.5 的读口用例用）：**每个有批次的格**都要"男女 × 城乡 ×
   * 三档"齐全——夹具若是清一色农村，`simos.social.population` 里"只看农村"的实现照样绿（假绿）。
   *
   * <pre>
   * 农村 男 5 岁 100（0-14） | 农村 女 20 岁 200（15-59） | 农村 男 70 岁 50（60+）
   * 城镇 男 10 岁 300（0-14）| 城镇 女 30 岁 700（15-59）
   * ⇒ total 1,350 = 城镇 1,000 + 农村 350；男 450 / 女 900；0-14 400 / 15-59 900 / 60+ 50
   * </pre>
   *
   * <p>★ 锚点 = {@code T0}（本夹具的创世态时间戳是 {@code T7}）⇒ head 上现算出的年龄是"锚点年龄 + 7 天"。
   */
  private static Map<PeopleLotId, PopulationGroup> mixedGroups() {
    CityId city = new CityId("c-1_1");
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    addLot(groups, PopulationLots.rural(H11, Sex.MALE, "0"), Sex.MALE, 100L, 5L * 365L);
    addLot(groups, PopulationLots.rural(H11, Sex.FEMALE, "1"), Sex.FEMALE, 200L, 20L * 365L);
    addLot(groups, PopulationLots.rural(H11, Sex.MALE, "2"), Sex.MALE, 50L, 70L * 365L);
    addLot(groups, PopulationLots.urban(city, Sex.MALE, "0"), Sex.MALE, 300L, 10L * 365L);
    addLot(groups, PopulationLots.urban(city, Sex.FEMALE, "1"), Sex.FEMALE, 700L, 30L * 365L);
    return groups;
  }

  private static void addLot(
      Map<PeopleLotId, PopulationGroup> groups,
      PeopleLotId id,
      Sex sex,
      long count,
      long ageAtAnchorDays) {
    groups.put(id, new PopulationGroup(id, H11, sex, count, ageAtAnchorDays, T0.tick()));
  }

  private static GameMap corridorMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    // ★ M4（2026-09-24）：夹具带一个区域——`simos.map.region` 要有东西可读（此前这张图 regions 为空）。
    Map<RegionId, Region> regions =
        Map.of(REGION, Region.of(REGION, "M4 区", Set.of(H11, H12), RegionMeta.empty()));
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

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  /** 把若干名单拼成一条（varargs；本文件的名单常量拼接用）。 */
  @SafeVarargs
  private static List<String> concat(List<String>... groups) {
    return Stream.of(groups).flatMap(List::stream).toList();
  }
}
