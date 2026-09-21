package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
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
import io.mosire.simos.app.tools.write.MapCreateRegionTool;
import io.mosire.simos.app.tools.write.MapDeleteRegionTool;
import io.mosire.simos.app.tools.write.MapRandomizeRegionTool;
import io.mosire.simos.app.tools.write.MapRegisterPathwayGroupTool;
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
import io.mosire.simos.app.tools.write.SdRegisterEffectTool;
import io.mosire.simos.app.tools.write.SdSetDecisionMakerProviderTool;
import io.mosire.simos.app.tools.write.SdSetStageOutcomeTableTool;
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
import io.mosire.simos.app.tools.write.UnitSetFormationOffsetTool;
import io.mosire.simos.app.tools.write.UnitSetRejoinTargetTool;
import io.mosire.simos.app.tools.write.UnitSetStatusTool;
import io.mosire.simos.app.tools.write.UnitSetStrengthTool;
import io.mosire.simos.app.tools.write.UnitSplitFormationTool;
import io.mosire.simos.app.tools.write.UnitUpdateCommandChainTool;
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

  /**
   * 现有口（T4：EXTERNAL ∪ GM）的工具面 = 9 读 + 46 写（3 通用写 + **16 sd 窄写**（4 + M3 的 12）+ **7 map 窄写**， M1 +
   * **20 unit 窄写**，M2）。
   */
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
          "sd.StartDecision",
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
          "sd.SetDecisionMakerProvider",
          "map.SetTerrain",
          "map.SetEdge",
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup",
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
          "unit.ApplyCasualties");

  /**
   * 读工具名单（9 条）：读闸**按名字选**，不用索引切片。
   *
   * <p>★ 索引切片（{@code subList(0, 9)}）在名单变长后**仍然合法** ⇒ 断言照绿、判别力静默流失。
   */
  private static final List<String> READ_TOOL_NAMES =
      List.of(
          "simos.command.catalog",
          "simos.state.resolve",
          "simos.state.facets",
          "simos.timeline.branches",
          "simos.map.overview",
          "simos.map.hex",
          "simos.unit.list",
          "simos.unit.get",
          "simos.social.population");

  /**
   * 写工具全集（46 条）：{@link #READ_TOOL_NAMES} 在 {@link #EXTERNAL_UNION_GM_TOOL_NAMES} 里的**补集**。
   *
   * <p>★★ **它是写闸的判据对象**：写闸覆盖集必须 == 本名单，而不是"名单的某一段下标"。M1 之前写闸用 {@code subList(9, 16)}——名单加了 7 条 map
   * 写之后切片仍合法，于是新工具**完全不被写闸覆盖**，且没有任何症状 （本仓「把没发生伪装成没发生」那一族）。
   */
  private static final List<String> WRITE_TOOL_NAMES =
      List.of(
          "simos.command.submit",
          "simos.advance",
          "simos.fork",
          "sd.IssueDirective",
          "sd.SubmitVerdict",
          "sd.SetViewScope",
          "sd.StartDecision",
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
          "sd.SetDecisionMakerProvider",
          "map.SetTerrain",
          "map.SetEdge",
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup",
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
          "unit.ApplyCasualties");

  /** M1 的 7 条 map 窄写：进 **GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含），**不进** EXTERNAL 桶、**不进**决策桶。 */
  private static final List<String> MAP_WRITE_NAMES =
      List.of(
          "map.SetTerrain",
          "map.SetEdge",
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup");

  /**
   * M2 的 20 条 unit 窄写：**同一批同时进 GM 桶与决策人桶**（用户裁定 D-1）⇒ 复合口也含、EXTERNAL 桶不含。
   *
   * <p>★ 名单顺序与 {@code SimosToolSource.addGmWrites} / {@code addDecisionAgentWrites}
   * 的登记顺序一致（**判据不依赖顺序**， 只为对齐可读）。
   */
  private static final List<String> UNIT_WRITE_NAMES =
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
          "unit.ApplyCasualties");

  /**
   * M3 的 12 条 sd 窄写：**只进 GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含）——**不进** EXTERNAL 桶、**也不进**决策人桶。
   *
   * <p>★ 与 M1 的 {@link #MAP_WRITE_NAMES} 同形；M2 的 unit 那批**两桶都有**（用户裁定 D-1），这批**不是**——决策人只出令 / 判决。
   */
  private static final List<String> SD_WRITE_NAMES =
      List.of(
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
          "sd.SetDecisionMakerProvider");

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

  /**
   * ★ M1 判据 2：**名字同源** —— 7 条 map 窄工具各自钉死的命令类型 == 它在名单里登记的名字（{@code AbstractNarrowWriteTool.name()}
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
            new MapDeleteRegionTool(core, TEST_INITIATOR, mapId),
            new MapRandomizeRegionTool(core, TEST_INITIATOR, mapId),
            new MapRegisterPathwayGroupTool(core, TEST_INITIATOR, mapId));

    assertThat(toolNames(mapTools))
        .as("7 条 map 窄工具的 name() == 它们各自钉死的命令类型")
        .containsExactlyElementsOf(MAP_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("同一批名字在 GM 桶里按名可寻")
        .containsAll(MAP_WRITE_NAMES);

    JsonNode types = JSON.readTree(call("simos.command.catalog", Map.of()).message()).get("types");
    assertThat(textValues(types))
        .as("7 个 map 类型都已在 catalog 里（⇒ 名字能到达已注册 handler）")
        .containsAll(MAP_WRITE_NAMES);
  }

  /**
   * ★ M2 判据 2：**名字同源**（20 条 unit 窄写）—— 每条工具钉死的命令类型 == 它在名单里登记的名字（{@code
   * AbstractNarrowWriteTool.name()} 直返 {@code commandType()}），同一批名字在 **GM 桶与决策人桶**里都按名可寻（用户裁定 D-1 的
   * "同一批挂两个桶"），且 20 个类型都已在 {@code catalog} 里（catalog 与已注册 handler 同源 ⇒ 名能到达 handler）。
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
            new UnitSetStrengthTool(core, TEST_INITIATOR, mapId),
            new UnitPlaceAtTool(core, TEST_INITIATOR, mapId),
            new UnitPlanRouteTool(core, TEST_INITIATOR, mapId),
            new UnitCancelRouteTool(core, TEST_INITIATOR, mapId),
            new UnitDisbandTool(core, TEST_INITIATOR, mapId),
            new UnitSetStatusTool(core, TEST_INITIATOR, mapId),
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
            new UnitApplyCasualtiesTool(core, TEST_INITIATOR, mapId));

    assertThat(toolNames(unitTools))
        .as("20 条 unit 窄工具的 name() == 它们各自钉死的命令类型")
        .containsExactlyElementsOf(UNIT_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("同一批名字在 GM 桶里按名可寻")
        .containsAll(UNIT_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("同一批名字在决策人桶里也按名可寻（用户裁定 D-1：同一批挂两个桶）")
        .containsAll(UNIT_WRITE_NAMES);

    JsonNode types = JSON.readTree(call("simos.command.catalog", Map.of()).message()).get("types");
    assertThat(textValues(types))
        .as("20 个 unit 类型都已在 catalog 里（⇒ 名字能到达已注册 handler）")
        .containsAll(UNIT_WRITE_NAMES);
  }

  /**
   * ★ M3 判据 2：**名字同源**（12 条 sd 窄写）—— 每条工具钉死的命令类型 == 它在名单里登记的名字（{@code
   * AbstractNarrowWriteTool.name()} 直返 {@code commandType()}），同一批名字在 **GM 桶与 {@code EXTERNAL_WITH_GM} 复合口**里都按名可寻，
   * 且 12 个类型都已在 {@code catalog} 里（catalog 与已注册 handler 同源 ⇒ 名能到达 handler）。
   *
   * <p>★ **逐个构造真工具**而不是只查桶：把任一工具的 {@code commandType()} 改成别的类型，这里当场红（M3 的 m2 变异体）。与 M1/M2 同形；
   * ★ 本批**只进这两个桶**——{@code EXTERNAL} 与 {@code DECISION_AGENT} 桶的"不得含"由 {@link
   * #roleBucketsNeverCarryGenericWrite} 按名反向断言（两处分工：这里证"在"，那里证"不在"）。
   */
  @Test
  void sdNarrowWriteToolsAreNamedAfterTheirFixedCommandType() throws Exception {
    CoreSimos core = shell.coreSimos();
    String mapId = ShellConfig.defaults(tempDir).mapId();
    List<AgentTool> sdTools =
        List.of(
            new SdCreateNationTool(core, TEST_INITIATOR, mapId),
            new SdCreateArmyTool(core, TEST_INITIATOR, mapId),
            new SdCreateDecisionMakerTool(core, TEST_INITIATOR, mapId),
            new SdPutInfoTool(core, TEST_INITIATOR, mapId),
            new SdCreateCombatTool(core, TEST_INITIATOR, mapId),
            new SdAddCombatStageTool(core, TEST_INITIATOR, mapId),
            new SdSetStageOutcomeTableTool(core, TEST_INITIATOR, mapId),
            new SdCommitCombatOutcomeTool(core, TEST_INITIATOR, mapId),
            new SdRecordCasualtiesTool(core, TEST_INITIATOR, mapId),
            new SdRegisterEffectTool(core, TEST_INITIATOR, mapId),
            new SdCancelEffectTool(core, TEST_INITIATOR, mapId),
            new SdSetDecisionMakerProviderTool(core, TEST_INITIATOR, mapId));

    assertThat(toolNames(sdTools))
        .as("12 条 sd 窄工具的 name() == 它们各自钉死的命令类型")
        .containsExactlyElementsOf(SD_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("同一批名字在 GM 桶里按名可寻")
        .containsAll(SD_WRITE_NAMES);
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.EXTERNAL_WITH_GM)))
        .as("同一批名字在 EXTERNAL_WITH_GM 复合口里也按名可寻（D2 的复合面）")
        .containsAll(SD_WRITE_NAMES);

    JsonNode types = JSON.readTree(call("simos.command.catalog", Map.of()).message()).get("types");
    assertThat(textValues(types))
        .as("12 个 sd 类型都已在 catalog 里（⇒ 名字能到达已注册 handler）")
        .containsAll(SD_WRITE_NAMES);
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
        .as("M1/M2 判据 1 + M3 判据 §5.1：GM 桶无通用写，且含 sd 与 map 与 unit 三族窄写")
        .doesNotContain("simos.command.submit")
        .contains("sd.IssueDirective", "sd.SubmitVerdict", "sd.SetViewScope", "sd.StartDecision")
        .containsAll(SD_WRITE_NAMES)
        .containsAll(MAP_WRITE_NAMES)
        .containsAll(UNIT_WRITE_NAMES)
        .hasSize(52);
    assertThat(agent)
        .as(
            "M2 判据 1 + M3 判据 §5.1：决策桶**含全部 20 条 unit 窄写**，但**没有**任何 map 窄写、**也没有 M3 的 12 条 sd 窄写**（也没有通用写与 SetViewScope/StartDecision）")
        .doesNotContain("simos.command.submit", "sd.SetViewScope", "sd.StartDecision")
        .contains("sd.IssueDirective", "sd.SubmitVerdict")
        .containsAll(UNIT_WRITE_NAMES)
        .doesNotContainAnyElementsOf(MAP_WRITE_NAMES)
        .doesNotContainAnyElementsOf(SD_WRITE_NAMES)
        .hasSize(31);
    assertThat(external)
        .as("M1/M2/M3 判据 1：EXTERNAL 桶**没有**任何 sd / map / unit 窄写（恒 9 读 + 3 通用写）")
        .contains("simos.command.submit")
        .doesNotContain(
            "sd.IssueDirective", "sd.SubmitVerdict", "sd.SetViewScope", "sd.StartDecision")
        .doesNotContainAnyElementsOf(SD_WRITE_NAMES)
        .doesNotContainAnyElementsOf(MAP_WRITE_NAMES)
        .doesNotContainAnyElementsOf(UNIT_WRITE_NAMES)
        .hasSize(12);
    assertThat(externalWithGm)
        .as(
            "T4/D2 + M1/M2/M3 判据 1：现有口 = EXTERNAL ∪ GM（9 读 + 3 通用写 + 4 sd 窄写 + 12 sd 窄写(M3) + 7 map 窄写 + 20 unit 窄写）")
        .contains(
            "simos.command.submit",
            "simos.advance",
            "simos.fork",
            "sd.IssueDirective",
            "sd.SubmitVerdict",
            "sd.SetViewScope",
            "sd.StartDecision")
        .containsAll(SD_WRITE_NAMES)
        .containsAll(MAP_WRITE_NAMES)
        .containsAll(UNIT_WRITE_NAMES)
        .hasSize(55);
  }

  private static List<String> toolNames(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  /**
   * 写闸**实际覆盖**的工具名。★★ 它从**真工具面**（{@code EXTERNAL_WITH_GM} 桶）派生、减去读名单，**不是**从名单常量取下标 切片。
   *
   * <p>这是 M1 修掉的那处缺陷的替代形态：原实现用 {@code subList(9, 16)}，名单加到 23 条后切片**仍然合法** ⇒ 新增的 7 条 map
   * 写工具完全不被写闸覆盖、且没有任何症状。现在覆盖集从工具面派生，退化成切片会当场红。
   */
  private List<String> writeFaceCoveredByTheWriteGate() {
    return shell.toolsFor(SimosToolSource.Role.EXTERNAL_WITH_GM).stream()
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
    assertThat(implemented).as("扫描必须恰为 43 个窄写工具类（扫到 0 个/漏文件是『扫描器静默』陷阱 ⇒ 空 == 空 恒真）").hasSize(43);

    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("GM 桶 ∖ 读名单必须**逐条等于**磁盘上实现了窄写工具的集合（孤儿工具 ⇒ 这里红）")
        .filteredOn(name -> !READ_TOOL_NAMES.contains(name))
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
    Set<String> names = new LinkedHashSet<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file :
          files.filter(path -> path.getFileName().toString().endsWith("Tool.java")).toList()) {
        String source = Files.readString(file);
        if (!source.contains("extends AbstractNarrowWriteTool")) {
          continue;
        }
        Matcher matcher = nameConstant.matcher(source);
        assertThat(matcher.find()).as("%s 继承了窄写基类却抽不到 NAME 常量（扫描器要当场响，不许静默跳过）", file).isTrue();
        names.add(matcher.group(1));
      }
    }
    return names;
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
        .containsExactlyInAnyOrderElementsOf(EXTERNAL_UNION_GM_TOOL_NAMES);
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

  // ── M1 判据 4：前置即错——7 条 map 窄写各一条「坏载荷 ⇒ 可读 REJECTED 且 head 不变」────────
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
        "{\"hexes\":[],\"seed\":7}",
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
        "{\"id\":\"u-1\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":1},\"member\":1,"
            + "\"equipment\":{},\"speed\":2,\"mobilityPerMille\":500}",
        "单位 id 已存在");
  }

  @Test
  void unitReparentToolSurfacesTheDomainRejectionForAnUnknownParent() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitReparentTool.NAME, "{\"id\":\"u-1\",\"parent\":\"nope\"}", "父单位不存在");
  }

  @Test
  void unitSetStrengthToolSurfacesTheDomainRejectionForANegativeMember() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitSetStrengthTool.NAME,
        "{\"id\":\"u-1\",\"member\":-1,\"equipment\":{}}",
        "member 必须 ≥ 0");
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
    assertDomainRejectedAndHeadUnchanged(UnitDetachTool.NAME, "{\"id\":\"u-1\"}", "已是根单位");
  }

  @Test
  void unitReparentSubtreeToolSurfacesTheDomainRejectionForACyclicParent() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitReparentSubtreeTool.NAME, "{\"rootId\":\"u-1\",\"parent\":\"u-1\"}", "会成环");
  }

  @Test
  void unitSetFormationOffsetToolSurfacesTheDomainRejectionForAnUnknownUnit() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitSetFormationOffsetTool.NAME, "{\"id\":\"nope\",\"dq\":1,\"dr\":-1}", "单位不存在");
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
            "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":2},\"member\":1,"
                + "\"equipment\":{},\"speed\":2,\"mobilityPerMille\":500}",
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

  /** ★ 坏载荷 = **未知装备键**：P14 明写不视作 0（`personnel` 给 0，确保先撞的是装备那一关）。 */
  @Test
  void unitApplyCasualtiesToolSurfacesTheDomainRejectionForAnUnknownEquipmentKey()
      throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitApplyCasualtiesTool.NAME,
        "{\"id\":\"u-1\",\"personnel\":0,\"equipment\":{\"没这个装备\":-1}}",
        "未知装备键");
  }

  // ── M3 判据 4：前置即错——12 条 sd 窄写各一条「坏载荷 ⇒ 可读 REJECTED 且 head 不变」──────────
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

  /** ★ 坏载荷 = **nationId 不存在**（本夹具里没有任何国家）；存在性检查在 nationId 上先撞。 */
  @Test
  void sdCreateArmyToolSurfacesTheDomainRejectionForAnUnknownNation() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        SdCreateArmyTool.NAME,
        "{\"armyId\":\"a1\",\"nationId\":\"没有这个国家\",\"rootUnitId\":\"u-1\",\"name\":\"第一军\"}",
        "nationId 不存在");
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

  /** ★ 坏载荷 = **未知装备键**（P14：不视作 0）；`personnel` 给 0 确保先撞的是装备那一关。 */
  @Test
  void sdRecordCasualtiesToolSurfacesTheDomainRejectionForAnUnknownEquipmentKey() throws Exception {
    seedCombatWithFirstStage();

    assertDomainRejectedAndHeadUnchanged(
        SdRecordCasualtiesTool.NAME,
        "{\"combatId\":\"c1\",\"stageId\":\"s1\",\"deltas\":[{\"unit\":\"u-1\",\"personnel\":0,"
            + "\"equipment\":{\"没这个装备\":-1},\"lossClass\":\"PERMANENT\"}]}",
        "未知装备键");
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
