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
import io.mosire.docs.DocsTools;
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
import io.mosire.simos.app.testing.SocialHouseholdFixture;
import io.mosire.simos.app.tools.ga.AppGaToolIdentity;
import io.mosire.simos.app.tools.map.AppMapToolPerspective;
import io.mosire.simos.app.tools.map.AppMapToolViews;
import io.mosire.simos.app.tools.read.CatalogTool;
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
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.spi.EconomyGmAdjustments;
import io.mosire.simos.economy.tools.write.EconomyAdjustTool;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.write.GaMessageSendTool;
import io.mosire.simos.ga.tools.write.SdCancelEffectTool;
import io.mosire.simos.ga.tools.write.SdCreateArmyTool;
import io.mosire.simos.ga.tools.write.SdCreateDecisionMakerTool;
import io.mosire.simos.ga.tools.write.SdCreateNationTool;
import io.mosire.simos.ga.tools.write.SdPutInfoTool;
import io.mosire.simos.ga.tools.write.SdRecordDiplomaticEventTool;
import io.mosire.simos.ga.tools.write.SdRegisterEffectTool;
import io.mosire.simos.ga.tools.write.SdSetArmyMasterGovTool;
import io.mosire.simos.ga.tools.write.SdSetDecisionMakerProviderTool;
import io.mosire.simos.ga.tools.write.SdSetDiplomaticRelationTool;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
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
import io.mosire.simos.map.tools.read.MapOverlapsTool;
import io.mosire.simos.map.tools.read.MapRenderTool;
import io.mosire.simos.map.tools.write.MapCreateRegionTool;
import io.mosire.simos.map.tools.write.MapDeleteRegionTool;
import io.mosire.simos.map.tools.write.MapRandomizeRegionTool;
import io.mosire.simos.map.tools.write.MapRegisterPathwayGroupTool;
import io.mosire.simos.map.tools.write.MapRenameRegionTool;
import io.mosire.simos.map.tools.write.MapSetEdgeTool;
import io.mosire.simos.map.tools.write.MapSetHexAreaTool;
import io.mosire.simos.map.tools.write.MapSetTerrainTool;
import io.mosire.simos.map.tools.write.MapUpdateRegionTool;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.tools.write.UnitAdjustCompositionTool;
import io.mosire.simos.unit.tools.write.UnitApplyCasualtiesTool;
import io.mosire.simos.unit.tools.write.UnitAttachTool;
import io.mosire.simos.unit.tools.write.UnitCancelRouteTool;
import io.mosire.simos.unit.tools.write.UnitCreateCommandChainTool;
import io.mosire.simos.unit.tools.write.UnitCreateTool;
import io.mosire.simos.unit.tools.write.UnitDetachTool;
import io.mosire.simos.unit.tools.write.UnitDisbandTool;
import io.mosire.simos.unit.tools.write.UnitMergeFormationTool;
import io.mosire.simos.unit.tools.write.UnitPlaceAtTool;
import io.mosire.simos.unit.tools.write.UnitPlanRouteTool;
import io.mosire.simos.unit.tools.write.UnitPlanSparseRouteTool;
import io.mosire.simos.unit.tools.write.UnitRenameTool;
import io.mosire.simos.unit.tools.write.UnitReparentSubtreeTool;
import io.mosire.simos.unit.tools.write.UnitReparentTool;
import io.mosire.simos.unit.tools.write.UnitSetArmyFormationTool;
import io.mosire.simos.unit.tools.write.UnitSetCompositionTool;
import io.mosire.simos.unit.tools.write.UnitSetFormationOffsetTool;
import io.mosire.simos.unit.tools.write.UnitSetGovPolicyTool;
import io.mosire.simos.unit.tools.write.UnitSetGovSuperiorTool;
import io.mosire.simos.unit.tools.write.UnitSetGovernmentFormationTool;
import io.mosire.simos.unit.tools.write.UnitSetJurisdictionTool;
import io.mosire.simos.unit.tools.write.UnitSetRejoinTargetTool;
import io.mosire.simos.unit.tools.write.UnitSetStateDescriptionTool;
import io.mosire.simos.unit.tools.write.UnitSetStatusTool;
import io.mosire.simos.unit.tools.write.UnitSetTaxRateTool;
import io.mosire.simos.unit.tools.write.UnitSplitFormationTool;
import io.mosire.simos.unit.tools.write.UnitUpdateCommandChainTool;
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
import io.mosire.workspace.WorkspaceTools;
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
 * 工具集验收（M5 T5；D1–D5 后）：注册表 143 条命令（★★ 追加裁定 12：旧三条 gov.Set* 政策命令删除、新增 economy.SetGovernmentPolicy ⇒
 * 144 → 142；★★ 2026-10-13 急停协议 +1 ⇒ 142；★★ 2026-10-14 决策人互致消息 +1（ga.SetDecisionMakerPeer）⇒ 143）/
 * catalog 与注册面一致（R5）/ 写工具身份注入（R4）/ 读工具与 {@code QueryService} 逐值 对拍 / 拒绝与冲突不留 revision。
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

  /**
   * 读工具名单（35 条）：读闸**按名字选**，不用索引切片。
   *
   * <p>★★ <b>2026-10-14 决策人互致消息 −1</b>：旧读口 {@code simos.sd.reports} **整条删除**（旧上报族废弃）⇒ 36 →
   * <b>35</b>。★ 减的是**本名单**（GM 桶的读侧），**不是**"读工具覆盖"本身：新批次的读工具 {@code simos.ga.message.inbox}
   * 是**决策人专属**（{@code roles()={DM}}）⇒ 它不进本名单；它的可达性与最小形状 （{@code messages}/{@code count}）由 {@code
   * DmMessagingEndToEndTest} 用**真决策人身份**覆盖（比在这里用 GM 身份调更强）。
   */
  private static final List<String> READ_TOOL_NAMES =
      List.of(
          "simos.army.combat",
          "simos.army.combats",
          // ★★ 2026-10-13 急停协议：原因系统的 GM 读口（StopReportTool，GmOnlyRead）
          "simos.army.stopReport",
          "simos.calendar.info",
          "simos.command.catalog",
          "simos.economy.hex",
          "simos.economy.ownership",
          "simos.gm.approvals",
          "simos.gm.packet",
          "simos.gm.packets",
          "simos.gm.tool-usage",
          "simos.gov.info",
          "simos.llm.providers",
          "simos.map.block",
          "simos.map.hex",
          "simos.map.overlaps",
          "simos.map.overview",
          "simos.map.path",
          "simos.map.region",
          "simos.map.render",
          "simos.province.divide",
          "simos.sd.decision-maker",
          "simos.sd.decision-makers",
          "simos.sd.diplomacy",
          "simos.sd.diplomatic-events",
          "simos.sd.directives",
          // ★★ 2026-10-14：simos.sd.reports **已删**（旧上报读口整族废弃）。
          "simos.sd.verdicts",
          "simos.social.households",
          "simos.social.population",
          "simos.state.facets",
          "simos.state.resolve",
          "simos.timeline.branches",
          "simos.timeline.revisions",
          "simos.unit.get",
          "simos.unit.list");

  /**
   * ★ **只给 GM 桶的读工具**（实现 {@code GmOnlyRead} 的那些，15 条）。
   *
   * <p>★★ 2026-10-13 急停协议：{@code simos.army.stopReport} 的 {@code payload.hex}
   * 是**视野之外**的军队位置（中央视角信息）， 决策人看不见（D-002 中央不设全视野）⇒ 它是 GM-only 读口，必须在这里登记（名单口径 = 实现 {@code
   * GmOnlyRead} 的那些）。
   */
  private static final List<String> GM_ONLY_READ_NAMES =
      List.of(
          "simos.army.stopReport",
          "simos.economy.ownership",
          "simos.gm.approvals",
          "simos.gm.packet",
          "simos.gm.packets",
          "simos.gm.tool-usage",
          "simos.llm.providers",
          "simos.map.block",
          "simos.map.overlaps",
          "simos.map.path",
          "simos.province.divide",
          "simos.sd.decision-maker",
          "simos.sd.decision-makers",
          "simos.sd.directives",
          "simos.sd.verdicts");

  /**
   * 非窄写写工具（62 条）：**只有 GM 组有**（MCP 与 GM Agent 同权限级）。
   *
   * <p>★ 它们**不是窄写**：不继承 {@code AbstractNarrowWriteTool} ⇒ 窄写扫描器（按 {@code tools/write}
   * 目录扫源码）**扫不到**它们；判"窄写是否都挂上了"时必须先把这批从差集里扣掉。
   *
   * <p>★★ <b>2026-10-09 按实测补齐（56 → 60）</b>：A1/A2a（2026-10-08 货币词表 + 官方汇率）落地的 4 条 {@code
   * simos.gov.*} 货币工具是不继承基类的通用写（载荷各自带审计腿 / 多步校验），当时没回填这张名单 ⇒ 连带本体与 {@code
   * registryContainsExactlyTheExternalUnionGmTools} 一起红了。实测来源 = {@code
   * shell.toolRegistry().list()}。
   *
   * <p>★★ <b>2026-10-13 急停协议 +1（61 → 62）</b>：{@code simos.emergency.advance}（{@code
   * AdvanceWithStopTool}）是 **通用写**（只 {@code implements SimosTool}，不继承窄写基类）——它是本批**唯一**走 {@code
   * CoreSimos.submitAdvance} 的入口（能拿到 {@code StopReport}），既有 {@code simos.advance} 一字未动。
   */
  private static final List<String> NON_NARROW_WRITE_NAMES =
      List.of(
          "sd.AdjudicateTick",
          "sd.RejectDirective",
          "sd.VoidAdjudication",
          "simos.advance",
          "simos.army.assignGov",
          "simos.army.formatUnit",
          "simos.army.resolveCombat",
          "simos.army.startCombat",
          "simos.calendar.configure",
          "simos.command.submit",
          "simos.economy.adjust",
          "simos.economy.upsertGovUnit",
          "simos.economy.upsertIndustry",
          // ★★ 2026-10-13 急停协议：唯一的急停推进入口（非窄写；既有 simos.advance 不 drain、拿不到 StopReport）
          "simos.emergency.advance",
          "simos.fork",
          "simos.gm.adjustPopulation",
          "simos.gm.approve",
          "simos.gm.armyPayPolicy",
          "simos.gm.govMarketMandate",
          "simos.gm.mergedPlan.apply",
          "simos.gm.mergedPlan.upsert",
          "simos.gm.packet.decide",
          "simos.gm.packet.execute",
          "simos.gm.periodicAdjustment",
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
          "simos.province.apply",
          "simos.province.assignCities",
          "simos.region.clearData",
          "simos.region.clearStructures",
          "simos.region.seed",
          "simos.sd.run-decision-makers",
          "simos.social.demand",
          "simos.social.household.create",
          "simos.social.household.members",
          "simos.social.household.move",
          "simos.social.household.rates",
          "simos.social.labor",
          "simos.unit.assignHousehold",
          "simos.unit.detachHousehold",
          "simos.unit.levyRegion",
          "simos.unit.raiseUnit",
          "simos.unit.spawnArmy",
          "simos.worldgen.initialize");

  /**
   * ★★ <b>economy 侧的 8 条窄写工具名</b>（B2 的市场区 3 条 + F 的运费 + W2 的逐区 α + W5-fix6 的铸币旋钮 + ★ W5.5 的逐市场利率 α
   * + ★★ W10 的经济政策预设写口）：**只进 GM 组**，都继承 {@code AbstractNarrowWriteTool}（⇒ 窄写扫描器按 {@code
   * tools/write} 扫得到）。
   *
   * <p>★ 工具名与命令类型**不同名**（{@code simos.economy.defineMarketZone} ↔ {@code
   * economy.DefineMarketZone}）， 故命令类型另列在 {@link #ECONOMY_ZONE_WRITE_COMMAND_TYPES}；与 {@code
   * MAP_WRITE_NAMES} 那批（同名）不同形。
   */
  private static final List<String> ECONOMY_ZONE_WRITE_NAMES =
      List.of(
          "simos.economy.defineMarketZone",
          "simos.economy.reassignZoneHexes",
          "simos.economy.mergeMarketZones",
          // ★ F（2026-10-09）：商品运费系数表的窄写工具
          "simos.economy.setCommodityFreight",
          // ★★ W2（2026-10-11）：逐区价格步长 α 的窄写工具（GM 把某区价格钉死/调快调慢）
          "simos.economy.setMarketZonePriceAlpha",
          // ★★ W5-fix6（2026-10-11）：政府铸币政策旋钮的 GM 窄写（工具名 ≠ 命令类型，同 W2 那款）
          "simos.economy.setGovernmentMintScale",
          // ★★ W5.5（2026-10-11，S-12⑤）：货币市场利率的自适应步长 α 的 GM 窄写（与 W2 的逐区价格 α 同形）
          "simos.economy.setMarketZoneLoanRateAlpha",
          // ★★ W10（2026-10-11，用户裁定「凡"行为"一律做成 GM 可调的预设」）：经济侧行为值政策预设的 GM 窄写
          //   （第 8 条；工具名 ≠ 命令类型 ⇒ 命令类型列在 ECONOMY_ZONE_WRITE_COMMAND_TYPES）
          "simos.economy.setEconomyPolicy");

  /**
   * {@link #ECONOMY_ZONE_WRITE_NAMES} 里"工具名 ≠ 命令类型"的那几条对应的命令类型（catalog 与 handler 面认的键）：
   * 立区/改划/合并三区命令 + W2 的逐区 α + W5-fix6 的铸币旋钮 + W5.5 的逐市场利率 α + ★★ W10 的政策预设写口（{@code
   * economy.SetCommodityFreight} 与它同名，故不重列）。
   */
  private static final List<String> ECONOMY_ZONE_WRITE_COMMAND_TYPES =
      List.of(
          "economy.DefineMarketZone",
          "economy.ReassignZoneHexes",
          "economy.MergeMarketZones",
          "economy.SetMarketZonePriceAlpha",
          "economy.SetGovernmentMintScale",
          "economy.SetMarketZoneLoanRateAlpha",
          // ★★ W10（2026-10-11）：政策预设写口的命令类型（工具 simos.economy.setEconomyPolicy 的固定命令）
          "economy.SetEconomyPolicy");

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
          "map.RegisterPathwayGroup",
          "map.SetHexArea");

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
          "gov.CreateNation",
          "army.CreateArmy",
          "army.SetArmyMasterGov",
          "ga.CreateDecisionMaker",
          "ga.PutInfo",
          "simos.sd.set-diplomatic-relation",
          "simos.sd.record-diplomatic-event",
          "ga.RegisterEffect",
          "ga.CancelEffect",
          "ga.SetDecisionMakerProvider");

  /** {@link #SD_WRITE_NAMES} 对应的 15 个命令类型。 */
  private static final List<String> SD_WRITE_COMMAND_TYPES =
      List.of(
          "gov.CreateNation",
          "army.CreateArmy",
          "army.SetArmyMasterGov",
          "ga.CreateDecisionMaker",
          "ga.PutInfo",
          "gov.SetDiplomaticRelation",
          "gov.RecordDiplomaticEvent",
          "ga.RegisterEffect",
          "ga.CancelEffect",
          "ga.SetDecisionMakerProvider");

  /**
   * GM 桶的 <b>66</b> 条窄写（8 map + 8 economy + 28 unit + 16 ga/sd + 其余——后者含 2 army + 1 social + 1
   * actor + 1 决策共用等）。 ★ 按实测更新：W5-fix6 的 {@code simos.economy.setGovernmentMintScale} 是第 6 条 economy
   * 窄写 ⇒ 65 → 66；★★ W5.5 的 {@code simos.economy.setMarketZoneLoanRateAlpha} 是第 7 条 ⇒ 66 → 67；★★ W10
   * 的 {@code simos.economy.setEconomyPolicy} 是第 8 条 ⇒ 67 → 68。
   *
   * <p>★★ <b>2026-10-13 急停协议 +1</b>：{@code simos.army.contactWatch}（{@code
   * SetArmyContactWatchTool}，GM 设置" 是否要监控这支军的相遇"）是**窄写**（固定命令 {@code army.SetArmyContactWatch} +
   * 载荷）⇒ 这里必须登记，否则 {@code everyNarrowWriteToolClassIsWiredIntoTheGmBucket} 的"GM 窄写 == 名单"当场红。 ★
   * <b>逐条核过它数的是哪个面</b>：本名单 = **GM 桶里的窄写工具名**（不是命令类型、不是磁盘类数）；命令类型与磁盘类数 分别在 {@code
   * EXPECTED_COMMAND_TYPES} 与 {@code everyToolClassOnDiskIsRegisteredInSomeBucket} 两处各自记账。
   *
   * <p>★★ <b>2026-10-14 决策人互致消息 +1</b>：{@code ga.SetDecisionMakerPeer}（{@code
   * SetDecisionMakerPeerTool}，GM 一次授权两决策人互致消息）是**窄写**（真继承 {@code AbstractNarrowWriteTool}、走审批） ⇒ 65
   * → <b>66</b>。★ 同批的 {@code simos.ga.message.send} **不在**本名单：它 {@code gate()=ALLOW}、故意不继承窄写基类 （设计书
   * §4.2 / I7）⇒ 它是"写感知层"、不是窄写；把它算进来会让"窄写 ⇒ 逐条审批"这条不变量出现例外。
   */
  private static final List<String> NARROW_WRITE_NAMES =
      List.of(
          "actor.AdjustAccounts",
          "army.CreateArmy",
          "army.SetArmyMasterGov",
          "ga.CancelEffect",
          "ga.CreateDecisionMaker",
          "ga.IssueDirective",
          "ga.PutInfo",
          "ga.RegisterEffect",
          "ga.ResetDecisionMakerConversation",
          "ga.RunDecision",
          "ga.SetDecisionMakerAccess",
          // ★★ 2026-10-14 决策人互致消息：GM 的"授权两人互通"窄写（固定命令 ga.SetDecisionMakerPeer）。
          //   ★ 它**是**窄写（真继承 AbstractNarrowWriteTool、走审批）⇒ 登记进本名单；
          //     而 simos.ga.message.send **不是**窄写（gate=ALLOW、闸门 = 事先授权，设计书 §4.2 / I7）
          //     ⇒ 它**不进**本名单 ⇒ writesAreSensitiveAndAskWithTheToolNameAsClassKey 正确地不扫它。
          "ga.SetDecisionMakerPeer",
          "ga.SetDecisionMakerProvider",
          "ga.StartDecision",
          "ga.SubmitVerdict",
          "gov.CreateNation",
          "map.CreateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup",
          "map.RenameRegion",
          "map.SetEdge",
          "map.SetHexArea",
          "map.SetTerrain",
          "map.UpdateRegion",
          // ★★ 2026-10-13 急停协议：GM 的"相遇报警"注册窄写（固定命令 army.SetArmyContactWatch）
          "simos.army.contactWatch",
          "simos.army.recordCombat",
          "simos.economy.defineMarketZone",
          "simos.economy.mergeMarketZones",
          "simos.economy.reassignZoneHexes",
          "simos.economy.setCommodityFreight",
          "simos.economy.setEconomyPolicy",
          "simos.economy.setGovernmentMintScale",
          "simos.economy.setMarketZoneLoanRateAlpha",
          "simos.economy.setMarketZonePriceAlpha",
          "simos.sd.record-diplomatic-event",
          "simos.sd.set-diplomatic-relation",
          "simos.unit.adjust-composition",
          "simos.unit.set-composition",
          "simos.unit.set-state-description",
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

  /**
   * 写工具全集（<b>128</b> 条 = 62 非窄写 + <b>66</b> 窄写）：{@link #READ_TOOL_NAMES} 在 {@link #GM_TOOL_NAMES}
   * 里的补集。
   *
   * <p>★★ 2026-10-14 决策人互致消息：{@link #READ_TOOL_NAMES} −1（{@code simos.sd.reports} 删除）、{@link
   * #NARROW_WRITE_NAMES} +1（{@code ga.SetDecisionMakerPeer}）⇒ 127 → <b>128</b>（−1 +1 后**净 0** 的说法只对
   * "GM 面总数"成立：那条读口由一条窄写取代）。
   */
  private static final List<String> WRITE_TOOL_NAMES =
      concat(NON_NARROW_WRITE_NAMES, NARROW_WRITE_NAMES);

  /**
   * ★★ <b>外来工具（AgentLib Docs/Workspace）—— 名字一律用他们自己的常量</b>（2026-10-14 接入批次）。
   *
   * <p>★ <b>为什么这是一个具名常量、而不是"把断言放宽"</b>：这 10 条工具的<b>类不在我们的源码树里</b>（在 {@code docs-mosire} / {@code
   * workspace-mosire} 两个 jar 里）⇒ 凡是"扫我们自己的 {@code *Tool.java} 再反过来判"的用例，
   * 都必须显式知道"哪些名字是外来的"，否则它们会被当成<b>幽灵条目</b>（源码里不存在的名字）。这里列出它们是<b>加一条具名规格</b>， 不是取消反向检查：{@link
   * #everyToolClassOnDiskIsRegisteredInSomeBucket} 的反向检查仍在，只是判据从 "GM 桶 ⊆ 我们的磁盘"变成 "GM 桶 ⊆ 我们的磁盘 ∪
   * 这份外来清单"。
   *
   * <p>★ <b>读/写两侧分开列</b>不是装饰：读侧走 {@link ToolGate#ALLOW}、写侧走 {@code Ask(SENSITIVE)}，而 {@link
   * #writesAreSensitiveAndAskWithTheToolNameAsClassKey} 的判据是"写闸覆盖集 == 写工具全集"——
   * 所以"哪些算写"必须显式（不显式就只能靠猜 = 放宽）。
   *
   * <p>★ 名字引用他们的常量（{@code DocsTools.DOC_*} / {@code WorkspaceTools.*}）：他们改名 ⇒ 我们<b>编译期</b>就红 （与生产侧
   * {@code SimosToolSource.EXTERNAL_TOOL_ROLES} 同一口径）。
   */
  private static final List<String> EXTERNAL_READ_TOOL_NAMES =
      List.of(
          DocsTools.DOC_LIST,
          DocsTools.DOC_READ,
          DocsTools.DOC_QUERY,
          // ★★ 2026-10-14 上游 R8（控制方裁定 §11.3）：两条"能力自述"工具都是纯读
          //   （ResourceManifest.NONE + spec(DEFAULT,false,false)）⇒ {GM, DM} + gate=ALLOW（DM_FREE）。
          DocsTools.DOC_GUIDE,
          WorkspaceTools.LIST_WORKSPACES,
          WorkspaceTools.WORKSPACE_INFO);

  /** 外来工具里的**写**类 6 条（设计书 §4.3：读不需要审批 ⇒ 其余一律 {@code Ask(SENSITIVE)}）。 */
  private static final List<String> EXTERNAL_WRITE_TOOL_NAMES =
      List.of(
          DocsTools.DOC_APPEND,
          DocsTools.DOC_CREATE,
          DocsTools.DOC_EDIT,
          DocsTools.DOC_VOID,
          WorkspaceTools.MOUNT_WORKSPACE,
          WorkspaceTools.UNMOUNT_WORKSPACE);

  /** 外来工具全集 10 条 = 4 读 + 6 写。 */
  private static final List<String> EXTERNAL_TOOL_NAMES =
      concat(EXTERNAL_READ_TOOL_NAMES, EXTERNAL_WRITE_TOOL_NAMES);

  /** 读名单 + 外来读 = 真正落在读闸里的全部读工具（{@link #GM_TOOL_NAMES} 的读侧）。 */
  private static final List<String> ALL_READ_TOOL_NAMES =
      concat(READ_TOOL_NAMES, EXTERNAL_READ_TOOL_NAMES);

  /** 写名单 + 外来写 = 真正落在写闸里的全部写工具（{@link #GM_TOOL_NAMES} 的写侧）。 */
  private static final List<String> ALL_WRITE_TOOL_NAMES =
      concat(WRITE_TOOL_NAMES, EXTERNAL_WRITE_TOOL_NAMES);

  /**
   * **运行时 MCP 口 = GM 组**的工具面（spec §2.1）= <b>43</b> 读 + <b>134</b> 写 = **175** （35 + 8 读；128 + 6 写 =
   * 62 非窄写 + 66 窄写 + 6 外来写）。
   *
   * <p>★★ 2026-10-14 AgentLib Docs/Workspace 接入：GM 面 **+10**（7 条 {@code doc_*} + 3 条工作区）。 实测来源 =
   * 真壳两桶计数（{@code GM_SIZE=173} / {@code DM_SIZE=48}），见 test-ledger。
   *
   * <p>★★ <b>2026-10-14 决策人互致消息：成员换了两处、总数不变（仍 173）</b> —— 删 {@code simos.sd.reports}（读侧 −1）、加
   * {@code ga.SetDecisionMakerPeer}（窄写侧 +1）⇒ 读 40 → 39、写 133 → 134。
   *
   * <p>★★ <b>2026-10-14 上游 R8：+2 ⇒ 173 → 175</b>（控制方裁定 §11.3）—— {@code docs}/{@code workspace} 各加一条
   * "能力自述"工具（{@code doc_guide} / {@code workspace_info}），两者 <b>{@code {GM, DM}} + gate=ALLOW</b> ⇒
   * 两个桶各 +2：GM 面读侧 39 → <b>43</b>（35 + 8），总数 <b>175</b>；决策人面 <b>48 → 50</b>。 ★
   * 实测来源：真壳两桶计数（本文件改前实跑红在「Expected size: 173 but was: 175」）。 ★ 与"决策人互致消息"批**不同源**：那批在 GM 面净
   * 0，这两条是上游加的（本批只是**接入**它们）。
   */
  private static final List<String> GM_TOOL_NAMES =
      concat(ALL_READ_TOOL_NAMES, ALL_WRITE_TOOL_NAMES);

  /**
   * catalog 预期的 <b>143</b> 个已注册命令类型（与 Shell 注册的 handler 同源）。
   *
   * <p>★★ 2026-10-13 急停协议 +1（141 → 142）：新增 {@code army.SetArmyContactWatch}（GM 的相遇报警注册命令）⇒ 本表与
   * {@code catalogCoversEveryCommandHandlerImplementation} 的 {@code hasSize(141 → 142)} 必须同轮改。 ★
   * 实测来源 = 扫 {@code *Handler.java} 的 {@code type()}（本批实测扫描面 **142**）。★ 旧文案写"142 个"而实际是 141
   * （历史沿革未回填），本次把**实测值**改对。
   *
   * <p>★★ <b>2026-10-14 决策人互致消息 +1（142 → 143）</b>：新增 {@code ga.SetDecisionMakerPeer}（新 handler ⇒
   * 新命令 类型；扫描面 {@code SetDecisionMakerPeerHandler} 在 {@code simos-ga} 的扫描根内）⇒ 本表与 {@code
   * catalogCoversEveryCommandHandlerImplementation} 的 {@code hasSize(143)} 必须同轮改。 ★
   * 与工具面**不同源**：本批（决策人互致消息）工具面 GM/DM 各是净 0，命令面才是 +1； 随后**上游 R8** 又给两个工具桶各 +2（173 → 175 / 48 → 50）——
   * 那是另一件事，与命令面无关。
   *
   * <p>★★ <b>2026-10-09 按实测补齐（128 → 136）</b>：A1/A2a/B2（2026-10-08 货币词表 + 官方汇率 + 市场区）落地的 8
   * 条命令当时没回填本表（{@code economy.DefineCurrency}/{@code RenameCurrency}/{@code
   * RecordMoneyIssuance}/{@code SetOfficialRate}/{@code DefineMarketZone}/{@code
   * ReassignZoneHexes}/{@code MergeMarketZones}/{@code SetMarketNumeraire}）⇒ 本表与 {@code
   * catalogListsExactlyTheRegisteredCommandTypes} 一起红了。实测来源 = 扫 {@code *Handler.java} 的 {@code
   * type()}（见 {@code catalogCoversEveryCommandHandlerImplementation}）。
   *
   * <p>★★ <b>W2（2026-10-11）</b>补入 {@code economy.SetMarketZonePriceAlpha} ⇒ 140 → 141；★★
   * <b>W5-fix6（2026-10-11）</b> 补入 {@code economy.SetGovernmentMintScale} ⇒ 141 → 142（实测来源同上：扫描面 142
   * 条 handler 实现，见 {@code SHELL_ASSEMBLED handler=142}）；★★ <b>W5.5（2026-10-11）</b> 补入 {@code
   * economy.SetMarketZoneLoanRateAlpha} ⇒ 142 → 143（实测来源同上：{@code SHELL_ASSEMBLED handler=143}）；★★
   * <b>W10（2026-10-11）</b> 补入 {@code economy.SetEconomyPolicy} ⇒ 143 → 144（实测来源同上：扫描面 144 条 handler
   * 实现）；★★ <b>追加裁定 12（2026-10-12 §21.9 D-1/D-3）</b>：删除旧三条 {@code gov.SetPortPolicy} / {@code
   * gov.SetAdministrationPlan} / {@code gov.SetBudgetPolicy}、新增通用写口 {@code
   * economy.SetGovernmentPolicy} ⇒ 144 → <b>142</b>（实测来源同上）。
   */
  private static final List<String> EXPECTED_COMMAND_TYPES =
      List.of(
          "actor.AdjustAccounts",
          "actor.ClearRegion",
          "actor.DeductHouseholdStock",
          "actor.EnsureHouseholdAccount",
          "actor.RemitGovTreasury",
          "actor.Seed",
          "actor.TransferAccounts",
          "army.AppendCombatStage",
          "army.AssignDecisionMakerArmy",
          "army.CreateArmy",
          "army.RecordCombat",
          "army.ResolveCombatStage",
          // ★★ 2026-10-13 急停协议：GM 的"相遇报警"注册命令（唯一新增命令类型；不打 DmUsableCommand ⇒ 仅 GM）
          "army.SetArmyContactWatch",
          "army.SetArmyMasterGov",
          "economy.AddDemand",
          "economy.AuthorizeGovernmentMarketOrder",
          "economy.CancelDemand",
          "economy.CancelGovernmentMarketOrder",
          "economy.ClearRegion",
          "economy.DefineCurrency",
          "economy.DefineMarketZone",
          "economy.GmAdjust",
          "economy.MergeMarketZones",
          "economy.MigrateHousehold",
          "economy.ReassignZoneHexes",
          "economy.RecordMoneyIssuance",
          "economy.RegisterCandidate",
          "economy.RegisterGovernment",
          "economy.RegisterHousehold",
          "economy.RemoveHouseholdPeriodicAdjustment",
          "economy.RenameCurrency",
          "economy.Seed",
          "economy.SetCommodityFreight",
          "economy.SetEconomyPolicy",
          "economy.SetGovServiceCommitment",
          "economy.SetGovernmentMintScale",
          "economy.SetGovernmentPolicy",
          "economy.SetHouseholdClass",
          "economy.SetHouseholdLabor",
          "economy.SetHouseholdParticipation",
          "economy.SetMarketNumeraire",
          "economy.SetMarketPrice",
          "economy.SetMarketZoneLoanRateAlpha",
          "economy.SetMarketZonePriceAlpha",
          "economy.SetOfficialRate",
          "economy.SwitchMode",
          "economy.TransferAssetShare",
          "economy.UnitBorrow",
          "economy.UnitRepay",
          "economy.UpdateDemand",
          "economy.UpsertGovUnit",
          "economy.UpsertHouseholdPeriodicAdjustment",
          "economy.UpsertIndustry",
          "ga.CancelEffect",
          "ga.CreateDecisionMaker",
          "ga.DecideDecisionPacket",
          "ga.DeleteDecisionMaker",
          "ga.IssueDirective",
          "ga.PutInfo",
          "ga.RegisterEffect",
          "ga.ResetDecisionMakerConversation",
          "ga.RunDecision",
          "ga.SetDecisionMakerAccess",
          // ★★ 2026-10-14 决策人互致消息：新命令类型（一条命令写两条 DM 记录、一条 revision）
          "ga.SetDecisionMakerPeer",
          "ga.SetDecisionMakerProvider",
          "ga.SetDirectiveStatus",
          "ga.StartDecision",
          "ga.SubmitDecisionPacket",
          "ga.SubmitVerdict",
          "ga.UpsertDecisionPacket",
          "ga.UpsertMergedEffectPlan",
          "gov.AssignDecisionMakerGov",
          "gov.AssignDecisionMakerNation",
          "gov.CreateNation",
          "gov.DeleteNation",
          "gov.RecordDiplomaticEvent",
          "gov.SetDiplomaticRelation",
          "map.CreateRegion",
          "map.DeleteRegion",
          "map.MergeRegions",
          "map.RandomizeRegion",
          "map.ReassignHexes",
          "map.RegisterPathwayGroup",
          "map.RenameRegion",
          "map.SetEdge",
          "map.SetHexArea",
          "map.SetTerrain",
          "map.SplitRegion",
          "map.UpdateRegion",
          "social.AddHouseholdMembers",
          "social.AdjustHouseholdPopulation",
          "social.ClearRegion",
          "social.CreateCity",
          "social.CreateHousehold",
          "social.DeleteCity",
          "social.MoveCity",
          "social.MovePopulationLots",
          "social.RemoveHouseholdMembers",
          "social.SeedGroups",
          "social.SetDemandCoefficient",
          "social.SetGlobalVitalRates",
          "social.SetHouseholdLocation",
          "social.SetHouseholdVitalRates",
          "social.SetLaborCoefficient",
          "social.SetPopulation",
          "social.SubmitHouseholdWorkOrder",
          "social.TransferHouseholdMembers",
          "social.UpdateCity",
          "unit.AdjustComposition",
          "unit.ApplyCasualties",
          "unit.AssignExternalGovPost",
          "unit.AssignGovPost",
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
          "unit.SetArmyPayPolicy",
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
          "unit.SetUnitHouseholds",
          "unit.SetVisionRadius",
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
    // ★★ 2026-10-12 B3 试点：8 条 map 窄写已下放到 simos-map（`io.mosire.simos.map.tools.write`）⇒ 构造器
    //   改吃注入的窄写口，这里与生产装配（SimosToolSource）**同源**地传：同一个 CoreSimosToolWritePort。
    ToolWritePort mapWrites = new CoreSimosToolWritePort(core, TEST_INITIATOR);
    List<AgentTool> mapTools =
        List.of(
            new MapSetTerrainTool(mapWrites, mapId),
            new MapSetEdgeTool(mapWrites, mapId),
            new MapCreateRegionTool(mapWrites, mapId),
            new MapUpdateRegionTool(mapWrites, mapId),
            new MapRenameRegionTool(mapWrites, mapId),
            new MapDeleteRegionTool(mapWrites, mapId),
            new MapRandomizeRegionTool(mapWrites, mapId),
            new MapRegisterPathwayGroupTool(mapWrites, mapId),
            // ★★ 2026-10-13 收尾测试第 2 层：批次 2（hex 属性表）新增的窄写工具此前**没进本列表**
            //   ⇒ MAP_WRITE_NAMES 也漏了它（"8 条"是陈旧的）。补上后与生产装配（SimosToolSource）逐条对齐。
            new MapSetHexAreaTool(mapWrites, mapId));

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
    // ★★ 2026-10-12 B3 收尾（C3）：28 条 unit 窄写已下放到 simos-unit
    //   （`io.mosire.simos.unit.tools.write`）⇒ 构造器改吃注入的窄写口，与生产装配（SimosToolSource:717+）
    //   **同源**地传同一个 CoreSimosToolWritePort（命令 id/发起者/信封由它装配）。
    ToolWritePort unitWrites = new CoreSimosToolWritePort(core, TEST_INITIATOR);
    List<AgentTool> unitTools =
        List.of(
            new UnitRenameTool(unitWrites, mapId),
            new UnitCreateTool(unitWrites, mapId),
            new UnitReparentTool(unitWrites, mapId),
            new UnitSetCompositionTool(unitWrites, mapId),
            new UnitPlaceAtTool(unitWrites, mapId),
            new UnitPlanRouteTool(unitWrites, mapId),
            new UnitCancelRouteTool(unitWrites, mapId),
            new UnitDisbandTool(unitWrites, mapId),
            new UnitSetStatusTool(unitWrites, mapId),
            new UnitSetStateDescriptionTool(unitWrites, mapId),
            new UnitAttachTool(unitWrites, mapId),
            new UnitDetachTool(unitWrites, mapId),
            new UnitReparentSubtreeTool(unitWrites, mapId),
            new UnitSetFormationOffsetTool(unitWrites, mapId),
            new UnitSplitFormationTool(unitWrites, mapId),
            new UnitMergeFormationTool(unitWrites, mapId),
            new UnitPlanSparseRouteTool(unitWrites, mapId),
            new UnitSetRejoinTargetTool(unitWrites, mapId),
            new UnitCreateCommandChainTool(unitWrites, mapId),
            new UnitUpdateCommandChainTool(unitWrites, mapId),
            new UnitApplyCasualtiesTool(unitWrites, mapId),
            new UnitAdjustCompositionTool(unitWrites, mapId),
            new UnitSetJurisdictionTool(unitWrites, mapId),
            new UnitSetTaxRateTool(unitWrites, mapId),
            new UnitSetGovernmentFormationTool(unitWrites, mapId),
            new UnitSetArmyFormationTool(unitWrites, mapId),
            new UnitSetGovPolicyTool(unitWrites, mapId),
            new UnitSetGovSuperiorTool(unitWrites, mapId));

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
    // ★★ 2026-10-12 B3 收尾（C3）：15 条 Sd* 窄写已下放到 simos-ga（`io.mosire.simos.ga.tools.write`）
    //   ⇒ 构造器改吃注入的窄写口 + ga 身份口，与生产装配（SimosToolSource:847+）**同源**。
    ToolWritePort gaWrites = new CoreSimosToolWritePort(core, TEST_INITIATOR);
    GaToolIdentity gaIdentity = new AppGaToolIdentity();
    List<AgentTool> sdTools =
        List.of(
            new SdCreateNationTool(gaWrites, gaIdentity, mapId),
            new SdCreateArmyTool(gaWrites, gaIdentity, mapId),
            new SdSetArmyMasterGovTool(gaWrites, gaIdentity, mapId),
            new SdCreateDecisionMakerTool(gaWrites, gaIdentity, mapId),
            new SdPutInfoTool(gaWrites, gaIdentity, mapId),
            new SdSetDiplomaticRelationTool(gaWrites, gaIdentity, mapId),
            new SdRecordDiplomaticEventTool(gaWrites, gaIdentity, mapId),
            new SdRegisterEffectTool(gaWrites, gaIdentity, mapId),
            new SdCancelEffectTool(gaWrites, gaIdentity, mapId),
            new SdSetDecisionMakerProviderTool(gaWrites, gaIdentity, mapId));

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
   * ★★ <b>Z1/Z3 工具面验收（§4/§11.6 负向）</b>：{@code simos.economy.adjust} 的 description / schema 与
   * catalog 的 {@code economy.GmAdjust} 提示逐处暴露两个新 kind（setOutputQuantity / clearOutputQuantity
   * 的载荷与守卫），且 <b>没有任何"修正参数 / modifier"写口</b> —— schema 属性仍恰 6 个、文本对 {@code modifier|修正参数} 0 命中。
   */
  @Test
  void economyAdjustToolExposesTheTwoOutputQuantityKindsAndNoModifierWriteSurface()
      throws Exception {
    AgentTool tool = shell.toolRegistry().find(EconomyAdjustTool.NAME).orElseThrow();

    assertThat(tool.description())
        .as("description 白名单计数与两个新 kind")
        .contains("白名单（12）")
        .contains(EconomyGmAdjustments.SET_OUTPUT_QUANTITY)
        .contains(EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY);

    @SuppressWarnings("unchecked")
    Map<String, Object> schema = tool.jsonSchema();
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties)
        .as("★ 负向：没有 modifier / 修正参数 字段（schema 属性仍恰 6 个）")
        .containsOnlyKeys(
            "adjustment", "parameters", "reason", "preview", "branch", "expectedRevision");

    @SuppressWarnings("unchecked")
    String adjustmentDescription =
        String.valueOf(((Map<String, Object>) properties.get("adjustment")).get("description"));
    assertThat(adjustmentDescription)
        .contains(EconomyGmAdjustments.SET_OUTPUT_QUANTITY)
        .contains(EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY);

    @SuppressWarnings("unchecked")
    String parametersDescription =
        String.valueOf(((Map<String, Object>) properties.get("parameters")).get("description"));
    assertThat(parametersDescription)
        .contains("{industryId, commodityId, quantity")
        .contains("{industryId, commodityId}")
        .contains("QUANTITY_OUT_OF_RANGE")
        .contains("NO_OVERRIDE_TO_CLEAR");

    String schemaText = JSON.writeValueAsString(schema);
    assertThat(tool.description() + "\n" + schemaText)
        .as("★ §11.6 负向：GM 工具面不得出现修正参数写口")
        .doesNotContain("修正参数")
        .doesNotContain("modifierPerMille");

    ToolResult catalog = call("simos.command.catalog", Map.of());
    assertThat(catalog.success()).isTrue();
    JsonNode hints = JSON.readTree(catalog.message()).get("payloadHints").get("economy.GmAdjust");
    assertThat(hints).as("catalog 必须给 economy.GmAdjust 提示").isNotNull();
    String hintText = hints.asText();
    assertThat(hintText)
        .contains(EconomyGmAdjustments.SET_OUTPUT_QUANTITY)
        .contains(EconomyGmAdjustments.CLEAR_OUTPUT_QUANTITY)
        .contains("QUANTITY_OUT_OF_RANGE")
        .contains("NO_OVERRIDE_TO_CLEAR")
        .as("★ 负向：catalog 提示也不得给修正参数写口")
        .doesNotContain("修正参数")
        .doesNotContain("ProductionEfficiencyModifier");
  }

  /**
   * ★ **T9 的强判据**：catalog 的 type 集合 == **全仓 93 个 `CommandHandler` 实现**的 `type()` 集合（注册面 == 实现面），
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
            "扫描必须恰为 143 个 *Handler.java 的 type()（W2 的 economy.SetMarketZonePriceAlpha 按实测补入本表 ⇒ 140 → 141；★★ W5-fix6 的 economy.SetGovernmentMintScale 按实测补入 ⇒ 141 → 142；★★ W5.5 的 economy.SetMarketZoneLoanRateAlpha 按实测补入 ⇒ 142 → 143；★★ W10 的 economy.SetEconomyPolicy 按实测补入 ⇒ 143 → 144；★★ 追加裁定 12 删旧三条 gov.Set* 政策命令、新增 economy.SetGovernmentPolicy ⇒ 144 → 142）（扫到 0/漏文件是『扫描器静默』陷阱；R4/E6 后含全部 economy/actor handler，"
                + "P1b1/P3/R3a 的区域清空与国库上缴，辖区阶段 5–12，D1/D3a/D4/D5 的 unit/sd/army 新命令，"
                + "S3a 的 7 条 social 家户命令与 unit.SetUnitHouseholds；Z6 起纳入 simos-gov 的 2 条 handler；2026-10-09 按实测补入 A1/A2a/B2 的 8 条货币/市场区命令）；"
                + "★★ 2026-10-13 急停协议：新增 army.SetArmyContactWatch ⇒ 141 → 142（实测：本批扫 *Handler.java 的 type() 得 142）；"
                + "★★ 2026-10-14 决策人互致消息：新增 ga.SetDecisionMakerPeer（新 handler + 新命令类型）⇒ 142 → **143**"
                + "（★ 逐条核过它数的是**命令类型/handler 实现数**，不是工具数：本批命令 +1 而工具面净 0，两者不同源）")
        .hasSize(143);

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
            "ga.IssueDirective",
            "ga.SubmitVerdict",
            "ga.SetDecisionMakerAccess",
            "ga.ResetDecisionMakerConversation",
            "ga.StartDecision",
            "ga.RunDecision",
            "sd.AdjudicateTick",
            "sd.RejectDirective")
        .containsAll(SD_WRITE_NAMES)
        .containsAll(MAP_WRITE_NAMES)
        .containsAll(UNIT_WRITE_NAMES)
        .as(
            "★ W2 后：GM 桶 = 37 读 + 126 写 = 163（61 非窄写 + 65 窄写；W2 的 simos.economy.setMarketZonePriceAlpha 是窄写 ⇒ 163）；"
                + "★★ W5-fix6 的 simos.economy.setGovernmentMintScale 同样是窄写 ⇒ 163 → 164（实测：SHELL_ASSEMBLED tool=164、MCP 外发工具 164 个）；"
                + "★★ W5.5 的 simos.economy.setMarketZoneLoanRateAlpha 同样是窄写 ⇒ 164 → 165（实测：本轮真跑该类的 tool 面计数）；"
                + "★★ W10 的 simos.economy.setEconomyPolicy 同样是窄写 ⇒ 165 → 166（实测：本轮真跑该类的 tool 面计数 166 = 37 读 + 129 写）；"
                + "★★ 2026-10-13 取缔 Skill 系统：删掉 Skill 读工具一条（共享读）⇒ 161 → 160（按实测桶大小显式改本条；设计书 §4.1「工具面只减一条，且恰好是那一条 Skill 工具」）；"
                + "★★ 2026-10-13 急停协议：新增 3 条工具且**只在 GM 桶**（simos.army.stopReport 读 + simos.army.contactWatch 窄写 + simos.emergency.advance 通用写）⇒ 160 → **163**"
                + "（逐条核过：决策人桶一条都不进 ⇒ 下面那条 42 **不动**；三条的 roles() 只有 GM）；"
                + "★★ 2026-10-14 AgentLib Docs/Workspace 接入：GM 面 **+10**（7 条 doc_* + 3 条工作区；"
                + "doc_void 与 3 条工作区工具只有 GM）⇒ 163 → **173**（实测：真壳 GM_SIZE=173）；"
                + "★★ 2026-10-14 上游 R8：{@code doc_guide} / {@code workspace_info} 两条能力自述工具 {GM, DM} + ALLOW"
                + "（控制方裁定 §11.3）⇒ 173 → **175**（实测：本条改前真跑红在「Expected size: 173 but was: 175」）")
        .hasSize(175);
    assertThat(agent)
        .as(
            "★ J3（spec §2.2/§四.3）：决策人桶**没有**通用写、**没有**任何 map/unit/sd 的写工具，"
                + "只留两条决策行为（sd.IssueDirective / sd.SubmitVerdict）+ 两条只读面"
                + "（第 3 波第 3 步的 sd.DecisionResults、Docs 系统的 sd.DecisionDocs）")
        .doesNotContain("simos.command.submit", "simos.advance", "simos.fork")
        .doesNotContain(
            "ga.SetDecisionMakerAccess",
            "ga.ResetDecisionMakerConversation",
            "ga.StartDecision",
            "ga.RunDecision",
            "sd.AdjudicateTick",
            "sd.RejectDirective")
        .contains("ga.IssueDirective", "ga.SubmitVerdict", "sd.DecisionResults", "sd.DecisionDocs")
        // ★★ 2026-10-14 决策人互致消息：决策人面的**成员**换了两处（条数不变 ⇒ 只靠 hasSize 看不出来，
        //   故这里逐条钉住换的是哪两条）：新的收发两工具**必须在**、旧上报族**必须不在**。
        .contains("simos.ga.message.send", "simos.ga.message.inbox")
        // ★★ 2026-10-14 上游 R8：两条能力自述工具也进决策人桶（纯读、DM_FREE）
        .contains(DocsTools.DOC_GUIDE, WorkspaceTools.WORKSPACE_INFO)
        .doesNotContain("simos.sd.report", "simos.sd.reports")
        .doesNotContain("ga.SetDecisionMakerPeer")
        // ★ D5：决策人侧的三条受限写（两条外交 + 给 XXX 政府钱）必须恰在此桶（GM 桶另有同名命令型的
        //   simos.sd.* 工具，不在本桶）。
        .contains("gov.SetDiplomaticRelation", "gov.RecordDiplomaticEvent", "simos.gov.pay")
        .doesNotContainAnyElementsOf(UNIT_WRITE_NAMES)
        .doesNotContainAnyElementsOf(MAP_WRITE_NAMES)
        .doesNotContainAnyElementsOf(SD_WRITE_NAMES)
        .doesNotContainAnyElementsOf(GM_ONLY_READ_NAMES)
        .as(
            "★ Z6 后：决策人桶 = 30 共享读 + 14 写（含 Z2/Z3 双桶 gov 工具，以及 2026-10-09 按实测补入的 4 条 A1/A2a 货币工具）= 44；"
                + "★★ 2026-10-13 取缔 Skill 系统：删掉 Skill 读工具一条（决策人面读口）⇒ 43 → 42（按实测桶大小显式改本条；设计书 §4.1「工具面只减一条，且恰好是那一条 Skill 工具」）；"
                + "★★ 2026-10-14 AgentLib Docs/Workspace 接入：决策人面 **+6**（doc_list/read/query/append/create/edit）⇒ 42 → **48**"
                + "（★ 逐条核过：doc_void 与 3 条工作区工具**只有 GM** ⇒ P5 成立；实测：真壳 DM_SIZE=48）；"
                + "★★ 2026-10-14 决策人互致消息：**总数仍 48**（−simos.sd.report −simos.sd.reports +simos.ga.message.send +simos.ga.message.inbox）"
                + "⇒ 那一步 hasSize 不动，但上面几行**逐条钉住了换的是哪两个名字**（只数条数会漏掉这种成员替换）；"
                + "★★ 2026-10-14 上游 R8：{@code doc_guide} / {@code workspace_info} 两条也 {GM, DM} ⇒ 48 → **50**"
                + "（实测：与本批的 GM 175 同一次真壳计数）")
        .hasSize(50);
  }

  private static List<String> toolNames(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  /**
   * 写闸**实际覆盖**的工具名。★★ 它从**真工具面**（GM 组）派生、减去读名单，**不是**从名单常量取下标 切片。
   *
   * <p>这是 M1 修掉的那处缺陷的替代形态：原实现用 {@code subList(9, 16)}，名单加到 30 条后切片**仍然合法** ⇒ 新增的 map
   * 写工具完全不被写闸覆盖、且没有任何症状。现在覆盖集从工具面派生，退化成切片会当场红。
   *
   * <p>★★ 2026-10-14：读名单现在有**两段**（我们自己的 {@link #READ_TOOL_NAMES} + 外来 {@link
   * #EXTERNAL_READ_TOOL_NAMES}）⇒ 两段都要减掉，否则 4 条外来读工具（gate 是 {@code ALLOW}）会被算成"写"， 在 {@link
   * #writesAreSensitiveAndAskWithTheToolNameAsClassKey} 里要求它们 {@code Ask} ⇒ 假红。
   */
  private List<String> writeFaceCoveredByTheWriteGate() {
    return shell.toolsFor(SimosToolSource.Role.GM).stream()
        .map(AgentTool::name)
        .filter(name -> !ALL_READ_TOOL_NAMES.contains(name))
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
   * ★★ **M2 判据 §5.5（本任务新增的同源强判据）**：扫**各模块**的 {@code tools/write/*Tool.java}（★ 2026-10-12 B3 起含
   * {@code simos-map} 的同名目录，见 {@link #narrowWriteSourceRoots()}）把**实现了窄写工具类**的 {@code NAME} 常量抽出来，
   * 断言 **"实现了窄写工具类的集合 == GM 桶的窄写工具集合"**。
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
    assertThat(implemented)
        .as(
            "扫描必须恰为 70 个窄写工具类（W2 的 simos.economy.setMarketZonePriceAlpha 继承 AbstractNarrowWriteTool ⇒ "
                + "66 → 67；★★ W5-fix6 的 simos.economy.setGovernmentMintScale 同样继承它 ⇒ 67 → 68；"
                + "★★ W5.5 的 simos.economy.setMarketZoneLoanRateAlpha 同样继承它 ⇒ 68 → 69；"
                + "★★ W10 的 simos.economy.setEconomyPolicy 同样继承它 ⇒ 69 → 70；"
                + "★★ 2026-10-12 B3 把**各域**的窄写全部下放到它们自己的模块（map 8 / army 1 / economy 8 / "
                + "social 1 / unit 28 / ga 23；扫描根见 narrowWriteSourceRoots()）——下放只移动落点、不增减工具 ⇒ "
                + "总数仍是 70（扫描根没跟上就会静默掉数，这里当场红）；"
                + "★★ 2026-10-13 急停协议：army 新增 1 个窄写类（SetArmyContactWatchTool）⇒ **实测 66 → 67**"
                + "（★ 旧文案里的『70』与实际值 66 早有偏差，本次只把实测值改对）；"
                + "★★ 2026-10-14 决策人互致消息：ga 新增 1 个窄写类（SetDecisionMakerPeerTool，真继承基类）⇒ 67 → **68**"
                + "（★ 同批的 GaMessageSendTool 也住在 tools/write/ 但**不继承**基类 ⇒ 扫描器扫不到它，这正是 I7 要的形状）；"
                + "扫到 0 个/漏文件是『扫描器静默』陷阱 ⇒ 空 == 空 恒真）")
        .hasSize(68);

    // 真工具面派生的 GM 窄写 = GM 桶 ∖ 读名单 ∖ 非窄写写工具 ∖ 外来工具。
    // ★★ 2026-10-14：外来工具（doc_*/工作区）既不是本仓窄写类、也不在磁盘扫描面里 ⇒ 必须显式排除，
    //   否则它们会被算成"本仓窄写"⇒ union 与 implemented（磁盘上的窄写类）对不上。
    List<String> gmNarrow =
        shell.toolsFor(SimosToolSource.Role.GM).stream()
            .map(AgentTool::name)
            .filter(name -> !ALL_READ_TOOL_NAMES.contains(name))
            .filter(name -> !NON_NARROW_WRITE_NAMES.contains(name))
            .filter(name -> !EXTERNAL_TOOL_NAMES.contains(name))
            .toList();
    assertThat(gmNarrow)
        .as(
            "GM 窄写必须逐条等于 NARROW_WRITE_NAMES（66 条：其余 + 8 map + 8 economy + 28 unit + 15 sd + 1 peer；"
                + "2026-10-13 急停协议 +1 条 army 窄写。★★ 2026-10-14 起外来 10 条在派生前就排除——"
                + "它们不在本仓源码里，不是窄写类）")
        .containsExactlyInAnyOrderElementsOf(NARROW_WRITE_NAMES);

    // ★ D5：决策人桶里也有窄写（两条外交命令型窄工具）——它们同样必须在册，不能成为"扫描器盲区"。
    Set<String> decisionNarrow = new LinkedHashSet<>(implemented);
    decisionNarrow.retainAll(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)));
    assertThat(decisionNarrow)
        .as("决策人桶里的窄写恰为四条：两条决策共用窄写（IssueDirective/SubmitVerdict）+ D5 的两条外交命令型窄工具")
        .containsExactlyInAnyOrder(
            "ga.IssueDirective",
            "ga.SubmitVerdict",
            "gov.SetDiplomaticRelation",
            "gov.RecordDiplomaticEvent");
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
    List<Path> roots = narrowWriteSourceRoots();
    Pattern nameConstant = Pattern.compile("String\\s+NAME\\s*=\\s*\"([^\"]+)\"");
    Pattern nameHandlerType = Pattern.compile("String\\s+NAME\\s*=\\s*([A-Za-z0-9_]+)\\.TYPE");
    Map<String, String> handlerTypes = handlerTypeConstantsFromSources();
    Set<String> names = new LinkedHashSet<>();
    for (Path root : roots) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file :
            files.filter(path -> path.getFileName().toString().endsWith("Tool.java")).toList()) {
          String source = Files.readString(file);
          if (!source.contains("extends AbstractNarrowWriteTool")) {
            continue;
          }
          // ★★ 2026-10-13 收尾测试第 2 层：扫描必须**镜像运行时的名字来源**（{@code AbstractNarrowWriteTool.name()}）——
          //   基类的 {@code name()} 返回 {@code toolName()}，而 {@code toolName()} 的缺省实现是
          //   {@code return commandType();}。**只有覆写了 toolName() 的类**才用 NAME 常量当工具名。
          //   ⇒ 未覆写时读 {@code COMMAND_TYPE}（或 {@code NAME = XxxHandler.TYPE} 解析出的命令类型）；
          //     覆写时读 NAME 字面量。
          //   ★ 这条修的是**真红**：{@code SdCreateArmyTool}/{@code SdSetArmyMasterGovTool} 的 NAME 常量
          //     有意保留 {@code sd.} 前缀（工具面身份另批正名），但它们的 {@code name()} 实际返回
          //     {@code army.CreateArmy}/{@code army.SetArmyMasterGov} —— 旧扫描读 NAME 常量 ⇒
          // 与真工具面**对不上**，
          //     且 NAME/commandType 分叉这种缺陷本扫描器**看不见**。改正后两类名字都能还原。
          boolean overridesToolName = source.contains("protected String toolName()");
          if (overridesToolName) {
            Matcher matcher = nameConstant.matcher(source);
            if (matcher.find()) {
              names.add(matcher.group(1));
              continue;
            }
          } else {
            Matcher commandType =
                Pattern.compile("String COMMAND_TYPE = \"([^\"]+)\"").matcher(source);
            if (commandType.find()) {
              names.add(commandType.group(1));
              continue;
            }
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
    }
    return names;
  }

  /**
   * 实现了窄写基类的工具类所在源码根（**按模块逐条给出**）。
   *
   * <p>★★ 2026-10-12 B3：工具实现**全部下放到各自的域模块**（试点 map + army + economy + social + unit + gov + ga
   * 共七批）⇒ 扫描面必须跟着架构走。只扫 app 会让下放的工具**静默漏出**扫描面（本仓踩过的"扫描器静默"陷阱）， 故这里把 **app + 七个域**逐条列出；★
   * **计数（{@link #everyNarrowWriteToolClassIsWiredIntoTheGmBucket} 的 {@code hasSize(68)}；2026-10-14
   * 决策人互致消息前是 67、2026-10-13 急停协议前是 66）不随下放改动** —— 下放只移动落点、不增减工具（漏一个根 ⇒ 当场红，见该用例的变异自证）。
   *
   * <p>★ 路径相对 {@code simos-app/}（surefire 的工作目录）：自己的树写 {@code src/…}，兄弟模块写 {@code
   * ../simos-<模块>/…}（与 {@link #commandHandlerSourceRoots()} 同款，主树与 worktree 里都成立）。
   */
  private static List<Path> narrowWriteSourceRoots() {
    return List.of(
        Paths.get("src", "main", "java", "io", "mosire", "simos", "app", "tools", "write"),
        domainToolsRoot("map", "write"),
        domainToolsRoot("army", "write"),
        domainToolsRoot("economy", "write"),
        domainToolsRoot("social", "write"),
        domainToolsRoot("unit", "write"),
        domainToolsRoot("gov", "write"),
        domainToolsRoot("ga", "write"));
  }

  /**
   * 某个域模块的 {@code io/mosire/simos/<域>/tools/<read|write>} 源码根（★ 2026-10-12 B3 收尾新增）。
   *
   * <p>工具实现住在各自的域模块里（"app 只负责组装"）⇒ 扫描根一词一改就够：新增一个域或一个子目录时只动本方法。 路径相对 {@code simos-app/}（surefire
   * 的工作目录），兄弟模块写 {@code ../simos-<模块>/…}。
   */
  private static Path domainToolsRoot(String module, String sub) {
    return Paths.get(
        "..",
        "simos-" + module,
        "src",
        "main",
        "java",
        "io",
        "mosire",
        "simos",
        module,
        "tools",
        sub);
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
            // ★★ 2026-10-13 收尾测试第 2 层：旧 {@code simos.sd.combats} 读工具**已随旧战斗族退役**
            //   （批次 3d-2：唯一的交战读口是 {@code simos.army.combats}，见下面那条）⇒ 本表删掉该行。
            //   ★ 不是漏测：它守的"交战读口最小形状"由 {@code simos.army.combats} 那一行原样承担。
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
            // ★ D0–D4 新读口：GM 决策包清单/单包、家户聚合查询。
            new Case("simos.gm.packets", Map.of(), "packets"),
            // ★★ 2026-10-14 决策人互致消息：旧跨区上报读口 simos.sd.reports **整条删除** ⇒ 本表删掉那一行。
            //   ★ 不是漏测：取代它的读工具 simos.ga.message.inbox 是**决策人专属**（roles()={DM}），
            //     本表用的是 SYSTEM 身份 ⇒ 在这里调它只会得到 FORBIDDEN（身份里没有 decision-maker: 前缀），
            //     测不到任何东西。它的可达性 + 最小形状（messages/count）+ 身份派生由
            //     DmMessagingEndToEndTest 用**真决策人身份**覆盖 —— 覆盖面只增不减。
            new Case("simos.social.households", Map.of(), "rows"),
            new Case(
                "simos.map.render",
                Map.of("q", 1L, "r", 1L, "radius", 1L, "format", "text"),
                "summary"),
            // ★ Z3c-2（2026-10-23）：政府读口最小形状（GM 省略 govUnitId ⇒ 全部 GOV 按 id 序）。
            new Case("simos.gov.info", Map.of(), "governments"),
            // ★★ 2026-10-13 急停协议：原因系统读口的最小形状。本进程还没急停过 ⇒ present=false
            //   （★ 进程内观测、重启即失 —— 读不到 ≠ 世界从没停过，见工具 description 的三处写明）。
            new Case("simos.army.stopReport", Map.of(), "present"));
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
    // ★ D2：单条决策包按 id 读；未知 id ⇒ NOT_FOUND（同上，不静默空对象）。
    ToolResult missingPacket = call("simos.gm.packet", Map.of("packetId", "pkt-none-0"));
    assertThat(missingPacket.success()).as("未知决策包不得静默给空对象").isFalse();
    assertThat(missingPacket.code()).isEqualTo("NOT_FOUND");
    covered.add("simos.gm.packet");

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

    // ★★ 2026-10-12 B3 试点：读工具已下放到 simos-map（`io.mosire.simos.map.tools.read`）⇒ 构造器改吃注入的
    //   四个接缝，这里与生产装配（SimosToolSource 的 readTools）**同源**地传同一批 app 侧实现。
    ToolReadPort mapReads = new QueryServiceToolReadPort(shell.queryService());
    AgentTool tool = shell.toolRegistry().find("simos.map.overlaps").orElseThrow();
    // ★ 注册表里的 GM 面是 RecordingToolSource.RecordingTool 装饰器，标记要看生产类本身。
    // ★★ 2026-10-12 B3 试点：map.overlaps 随下放改标**契约层**的 {@code ga-spi.GmOnlyRead}（域模块看不见 app
    //   的标记）⇒ 这里与生产桶装配**同一判据**（SimosToolSource.readToolsFor 的两支并集；漏任一支 = 这条工具悄悄
    //   进决策人桶 = 权限放大）。
    assertThat(
            new MapOverlapsTool(
                mapReads,
                new AppMapToolViews(),
                new AppMapToolPerspective(),
                ShellConfig.defaults(tempDir).mapId()))
        .as("生产类必须实现 GmOnlyRead（只进 GM 桶的标记来源；app 与 ga-spi 两支之一）")
        .isInstanceOfAny(GmOnlyRead.class, io.mosire.simos.ga.spi.GmOnlyRead.class);
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
   * <p>口径：扫**各模块**的 {@code tools/read} 与 {@code tools/write} 两目录（★ 2026-10-12 B3 起含 {@code
   * simos-map} 的同名两目录，见 {@link #toolSourceRoots()}），抽 {@code NAME = "…"}；断言 **扫描到的名字 ⊆
   * 两个桶的并集**，且**GM 桶里没有源码里不存在的名字**（双向：既不漏注册、也没有幽灵条目）。
   */
  @Test
  void everyToolClassOnDiskIsRegisteredInSomeBucket() throws Exception {
    Set<String> onDisk = toolNamesFromSources();
    assertThat(onDisk)
        .as(
            "扫描必须恰为 170 个 *Tool.java 的 NAME（★★ 2026-10-13 取缔 Skill 系统：删掉 Skill 读工具类那一个 *Tool.java ⇒ 扫描面 171 → 170。"
                + "★ 本条旧文案里的『176』与实际值 171 早有偏差（历史累加未回填）⇒ 本次只把**实测值**改对：171 → 170，"
                + "历史沿革原样保留在下面。设计书 §2.4 未列本条 ⇒ 由 task-2 按本类『显式改并说明为什么』的纪律补上）"
                + "（W2 新增 simos.economy.setMarketZonePriceAlpha ⇒ 172 → 173；"
                + "★★ W5-fix6 新增 simos.economy.setGovernmentMintScale ⇒ 173 → 174；"
                + "★★ W5.5 新增 simos.economy.setMarketZoneLoanRateAlpha ⇒ 174 → 175；"
                + "★★ W10 新增 simos.economy.setEconomyPolicy ⇒ 175 → 176；"
                + "★★ 2026-10-12 B3 把**各域**的工具全部下放到它们自己的模块（map 15 / army 12 / economy 15 / "
                + "social 12 / unit 36 / gov 36 / ga 49；扫描根见 toolSourceRoots()）——下放只移动落点、不增减工具 ⇒ "
                + "总数仍是 176（扫描根没跟上就会静默掉数）；"
                + "★★ 2026-10-13 急停协议：磁盘上真多了 3 个 *Tool.java（StopReportTool / SetArmyContactWatchTool / "
                + "AdvanceWithStopTool）⇒ **实测 170 → 173**；"
                + "★★ 2026-10-14 决策人互致消息：磁盘上净 +1 个 *Tool.java —— 删 {@code SimosSdReportTool} / "
                + "{@code SimosSdReportsTool}（旧上报族整族退役，−2）、新增 {@code GaMessageSendTool} / "
                + "{@code GaMessageInboxTool}（新收发两工具，+2）、新增 {@code SetDecisionMakerPeerTool}（GM 的授权命令"
                + "工具，+1）⇒ **实测 173 → 174**（★ 判别力就在这个值本身：它由磁盘源码扫描给出，故『多写一个工具类却忘了"
                + "注册』与『注册了源码里没有的名字』两侧都靠它钉着 —— 不改成正则/动态推导）；"
                + "扫到 0/漏文件是『扫描器静默』陷阱）")
        .hasSize(174);

    List<String> union =
        Stream.concat(
                toolNames(shell.toolsFor(SimosToolSource.Role.GM)).stream(),
                toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)).stream())
            .distinct()
            .toList();

    assertThat(onDisk).as("★ 磁盘上的每个 *Tool.java 都要挂在某个桶里（类写了却忘了注册 ⇒ 这里红）").isSubsetOf(union);
    // ★★ 2026-10-14（本批唯一需要判断的一处，方向：**具名排除，不放宽成正向检查**）：
    //   磁盘扫描只认**本仓**的 `*Tool.java`，而 doc_*/workspace_* 的类在 docs-mosire / workspace-mosire 两个 jar 里
    //   ⇒ 若照旧写 `isSubsetOf(onDisk)`，这 10 条外来工具会被误判成"幽灵条目"。
    //   判据因此改成 `GM 桶 ⊆ 我们的磁盘 ∪ 外来工具清单`：**反向检查仍在**（我们自己的工具类写了没注册、
    //   或桶里冒出本仓源码不存在且不在外来清单里的名字 ⇒ 照样红），只是把"外来"这一维显式化。
    //   ★ 为什么不用"放宽成正向检查"：正向检查（磁盘 ⊆ 桶）对"桶里多出幽灵"完全没有判别力 —— 那正是本条存在的理由。
    assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
        .as("反向：GM 桶里的名字必须 ∈（本仓源码里的 *Tool.java ∪ 具名外来工具集 10 条）⇒ 幽灵条目仍然当场红")
        .isSubsetOf(concat(new ArrayList<>(onDisk), EXTERNAL_TOOL_NAMES));
  }

  /**
   * 扫工具面 {@code NAME = "…"} 的全部源码根（{@code tools/read} + {@code tools/write}，M4 同源判据用）。
   *
   * <p>★★ 2026-10-12 B3：工具实现**全部下放到各自的域模块**（试点 map + army + economy + social + unit + gov + ga
   * 共七批）⇒ 扫描面必须跟着架构走（只扫 app 会让下放的工具静默漏出扫描面 ⇒ "扫描器静默"陷阱）。★ **计数（{@link
   * #everyToolClassOnDiskIsRegisteredInSomeBucket} 的 {@code hasSize(174)}；2026-10-13 急停协议前是
   * 170、急停协议后 173、2026-10-14 决策人互致消息后 174）不随下放改动** —— 下放只移动落点、不增减工具（漏一个根 ⇒ 当场红）。
   *
   * <p>★ 路径相对 {@code simos-app/}（surefire 的工作目录）：自己的树写 {@code src/…}，兄弟模块写 {@code
   * ../simos-<模块>/…}（与 {@link #commandHandlerSourceRoots()} 同款）。
   */
  private static List<Path> toolSourceRoots() {
    return List.of(
        Paths.get("src", "main", "java", "io", "mosire", "simos", "app", "tools", "read"),
        Paths.get("src", "main", "java", "io", "mosire", "simos", "app", "tools", "write"),
        domainToolsRoot("map", "read"),
        domainToolsRoot("map", "write"),
        domainToolsRoot("army", "read"),
        domainToolsRoot("army", "write"),
        domainToolsRoot("economy", "read"),
        domainToolsRoot("economy", "write"),
        domainToolsRoot("social", "read"),
        domainToolsRoot("social", "write"),
        domainToolsRoot("unit", "read"),
        domainToolsRoot("unit", "write"),
        domainToolsRoot("gov", "read"),
        domainToolsRoot("gov", "write"),
        domainToolsRoot("ga", "read"),
        domainToolsRoot("ga", "write"));
  }

  /** 扫 {@code tools/read} + {@code tools/write} 的 {@code NAME = "…"}（M4 同源判据用）。 */
  private static Set<String> toolNamesFromSources() throws IOException {
    List<Path> roots = toolSourceRoots();
    Map<String, String> handlerTypes = handlerTypeConstantsFromSources();
    Pattern nameLiteral = Pattern.compile("String NAME = \"([^\"]+)\"");
    Pattern nameHandlerType = Pattern.compile("String NAME = ([A-Za-z0-9_]+)\\.TYPE");
    Set<String> names = new LinkedHashSet<>();
    for (Path root : roots) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file);
          // ★★ 2026-10-13 收尾测试第 2 层：窄写工具的**运行时名字**是 {@code AbstractNarrowWriteTool.name()}
          //   = {@code toolName()}，其缺省实现返回 {@code commandType()}。未覆写 toolName() 的类即使留着
          //   一个 {@code NAME} 常量，那个常量**也不是**它在桶里的名字（{@code SdCreateArmyTool} /
          //   {@code SdSetArmyMasterGovTool} 就是这一形态：NAME 有意保留 {@code sd.} 前缀，name() 却是
          //   {@code army.*}）⇒ 这里先读 {@code COMMAND_TYPE}，否则下面那条"GM 桶不得有源码里不存在的名字"
          //   会与真工具面**对不上**（旧写法正是如此）。
          if (source.contains("extends AbstractNarrowWriteTool")
              && !source.contains("protected String toolName()")) {
            Matcher commandType =
                Pattern.compile("String COMMAND_TYPE = \"([^\"]+)\"").matcher(source);
            if (commandType.find()) {
              names.add(commandType.group(1));
              continue;
            }
          }
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

  /**
   * 全仓实现 {@code CommandHandler} 的模块源码根（handler 类型扫描共用一份，避免两处漂移）。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 2 层</b>：两处随架构轮更正 ——
   *
   * <ol>
   *   <li><b>摘掉 {@code simos-sd}</b>：模块已整模块退役（批次 3e，用户裁定「sd 整个弃用！」）⇒ 目录不存在， {@code Files.walk}
   *       对不存在的根抛 {@code NoSuchFileException}（这是**当前红点**的直接原因）；
   *   <li><b>补上 {@code simos-ga}</b>：决策族 7 组件自 sd 迁入，它的 handler（{@code ga.CreateDecisionMaker} 等 9
   *       条）此前**不在扫描面里**——不补就是"扫描器静默"（新模块的实现面没人守）。
   * </ol>
   */
  private static List<Path> commandHandlerSourceRoots() {
    return List.of(
        Paths.get("..", "simos-unit", "src", "main", "java"),
        Paths.get("..", "simos-map", "src", "main", "java"),
        Paths.get("..", "simos-social", "src", "main", "java"),
        Paths.get("..", "simos-economy", "src", "main", "java"),
        Paths.get("..", "simos-actor", "src", "main", "java"),
        Paths.get("..", "simos-army", "src", "main", "java"),
        Paths.get("..", "simos-gov", "src", "main", "java"),
        Paths.get("..", "simos-ga", "src", "main", "java"));
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
        .as(
            "★ 写闸覆盖集 == 写工具全集（现有口 ∖ 读名单）：退回索引切片会让新增的写工具静默逃出写闸。"
                + "★★ 2026-10-14：写工具全集 = 我们自己的 128 条 + 外来 6 条（doc_append/create/edit/void、"
                + "mount_workspace/unmount_workspace）—— 外来写工具**并入**这个派生集合，"
                + "不改断言强度（它们实测就是 Ask(classKey=工具名, SENSITIVE)）")
        .containsExactlyInAnyOrderElementsOf(ALL_WRITE_TOOL_NAMES);
    // ★★ 2026-10-14 决策人互致消息（设计书 §4.2 / 不变量 I7）——**这条审计的边界，就地写清**：
    //   simos.ga.message.send 写的是**感知层**、GM 的管控点是**事先授权**（accessLimit.peer）⇒ 它
    //   gate()=ALLOW、**故意不继承** AbstractNarrowWriteTool。所以它**不属于"窄写工具"家族**，
    //   本审计**正确地不扫它**（它也不在 GM 面 ⇒ 派生集里根本没有它）。
    //   ★ 这不是把断言改松：上面那条 containsExactlyInAnyOrderElementsOf(ALL_WRITE_TOOL_NAMES) 一字未动，
    //     仍然要求"GM 面派生的写闸覆盖集"逐条精确；谁要是把 send 塞进 ALL_WRITE_TOOL_NAMES，上面那条**立刻红**。
    //   ★ 下面三条把这条边界从"隐含"变成"具名"：不扫它 + 它确实是 ALLOW + 它确实不标 sensitive。
    assertThat(covered)
        .as("★ I7 边界：simos.ga.message.send 不是窄写（gate=ALLOW）⇒ 写闸覆盖集必须**不含**它")
        .doesNotContain(GaMessageSendTool.NAME);
    // ★★ 查找面（2026-10-14 实测修）：★ 这条断言判的是**决策人面**上的 send，而它的 roles()={DM} ⇒ 它
    //   **不在** GM 的 MCP 注册表里（{@code shell.toolRegistry()} 只装 GM 面，见 Shell 的 toolBridge 装配）
    //   —— 旧写法在 GM 注册表里 find 它 ⇒ NoSuchElementException（实测：本条在基线里正是那个 Error）。
    //   ★ 这不是"改找得到一个替身"，而是**按 toolsFor(DECISION_AGENT) 取真身**：与 DmMessagingEndToEndTest
    //     同源（那儿也从决策人桶取 send/inbox）。★ 找不到 ⇒ 显式断言失败（不退化成静默跳过）。
    AgentTool messageSend =
        shell.toolsFor(SimosToolSource.Role.DECISION_AGENT).stream()
            .filter(tool -> GaMessageSendTool.NAME.equals(tool.name()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "★ 决策人面上必须有 "
                            + GaMessageSendTool.NAME
                            + "（roles()={DM} ⇒ GM 的 MCP 注册表里必然没有它）"));
    assertThat(messageSend.gate(context(messageSend, Map.of())))
        .as("★ I7：send 的闸门是 ALLOW（事先授权在 accessLimit.peer，不是逐条审批）")
        .isEqualTo(ToolGate.ALLOW);
    assertThat(messageSend.spec().sensitive())
        .as("★ I7：send 也不标 sensitive —— 标了会让「要不要停下来问人」出现第二处真相")
        .isFalse();
    // ★★ 反面锚（边界另一侧）：{@code ga.SetDecisionMakerPeer} 是**窄写**（Ask）⇒ 它只在 GM 桶、不在决策人桶
    //   —— 那条已由 {@link #roleBucketsNeverCarryGenericWrite} 的 {@code doesNotContain} 逐字钉着，不在此重复。
    assertThat(ALL_READ_TOOL_NAMES)
        .as("读名单与写名单互斥")
        .doesNotContainAnyElementsOf(ALL_WRITE_TOOL_NAMES);
    assertThat(concat(ALL_READ_TOOL_NAMES, ALL_WRITE_TOOL_NAMES))
        .as("读名单 + 写名单 == 现有口全名单（完整）：名单加项却没登记到任一侧，这里红")
        .containsExactlyInAnyOrderElementsOf(GM_TOOL_NAMES);
    for (String name : covered) {
      AgentTool tool = shell.toolRegistry().find(name).orElseThrow();
      ToolGate gate = tool.gate(context(tool, Map.of()));
      assertThat(gate).as("%s 需审批", name).isInstanceOf(ToolGate.Ask.class);
      assertThat(((ToolGate.Ask) gate).classKey()).isEqualTo(name);
      assertThat(((ToolGate.Ask) gate).kind())
          .as("M1 判据 3：%s 的 gate 是 AskKind.SENSITIVE（走审批门链）", name)
          .isEqualTo(AskKind.SENSITIVE);
      if (EXTERNAL_TOOL_NAMES.contains(name)) {
        // ★★ 外来写工具**不查** spec 上的两个旗标 —— 理由（不是"放宽"，是这两条对外来工具**不成立**）：
        //   ① 适配层按 I3 **逐字委托** `spec()`（`SimosToolAdapter#spec` 原样转给被包装工具）⇒ 它是
        //      **vendor 的** spec，我们无权要求 `sensitive()==true`（要它 true 就得改 `spec()` ⇒ 破 I3）；
        //   ② `spec().sensitive()` 在本仓 **main 侧零读者**（审批链的权威是上面已钉死的 `gate()`）——
        //      它只是我们自家写工具的**声明惯例**，不是审批的执行依据。
        //   ⇒ 外来工具的审批强度由上面三条 gate 断言承担（Ask + classKey==工具名 + SENSITIVE），一字未减。
        //   ★ 记：外来 10 条的 spec 旗标与自家写工具不一致 ⇒ 已作为**候选生产侧缺口**上报控制方裁定。
        continue;
      }
      assertThat(tool.spec().sensitive()).as("%s 是敏感写", name).isTrue();
      assertThat(tool.spec().noExport()).as("%s 不外发标记为假（无内部工具）", name).isFalse();
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
   * ★ 2026-09-25 §十一：{@code simos.advance} 支持**一次推进 N 天**（{@code to = from + N}）——一条命令，**N 条
   * revision** （口径 1=(i)：Core 逐日落 revision，世界真的停在每一天、可直接续推）。判别力：把工具/Core 改回"恰好一天" ⇒ 本条的 3 天区间当场被拒 ⇒
   * 红。
   *
   * <p>★★ <b>2026-10-13 急停协议逐日化后改断言</b>：旧口径是"一次 N 天落**一条** revision、时间戳 = to"；逐日化后 N 天 = N 条
   * revision（main:2/3/4，时间戳分别 = 8/9/10）⇒ 终点坐标 = **main:4**。★ {@code loadCalls} 仍为 1 那条判据在 core
   * 侧（Core 在内存里逐日推进）。
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
    assertThat(body.get("ref").get("revision").asLong())
        .as("★ 逐日化：3 天 = 3 条 revision ⇒ 终点坐标 = main:4（旧口径的 main:2 已作废）")
        .isEqualTo(4L);
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      Timeline timeline = new Timeline(store, CHECKPOINT_INTERVAL);
      assertThat(timeline.row(ref("main", 2)).orElseThrow().timestamp())
          .as("第 1 天的 revision（main:2）时间戳 = 8")
          .isEqualTo(SimosTimestamp.of(8L));
      assertThat(timeline.row(ref("main", 3)).orElseThrow().timestamp())
          .as("第 2 天（main:3）= 9")
          .isEqualTo(SimosTimestamp.of(9L));
      assertThat(timeline.row(ref("main", 4)).orElseThrow().timestamp())
          .as("推进终点 to（10）落在第 3 天那条 revision（main:4）上")
          .isEqualTo(SimosTimestamp.of(10L));
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
            + "\"equipment\":[],"
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
        "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"步兵\",\"amount\":-1}]}",
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
                + "\"equipment\":[],"
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

  /** ★ 坏载荷 = **未知装备类型**：P14 明写不视作 0（S3b 起战损只有装备一表；未知 type 判定在符号之前）。 */
  @Test
  void unitApplyCasualtiesToolSurfacesTheDomainRejectionForAnUnknownEquipmentType()
      throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        UnitApplyCasualtiesTool.NAME,
        "{\"id\":\"u-1\",\"equipment\":[{\"type\":\"没这个装备\",\"amount\":-1}]}",
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
    // ★★ 2026-10-13 收尾测试第 2 层：本工具在注册表里的名字是**运行时名字** ——
    //   `AbstractNarrowWriteTool.name()` 返回 `toolName()`，而 `SdCreateArmyTool` **没有覆写**它
    //   ⇒ 名字 = `commandType()` = `army.CreateArmy`。它的 `NAME` 常量有意保留 `sd.` 前缀（工具面身份，
    //   正名另批）⇒ **那个常量不是它的注册名**，拿它去 call 会 NoSuchElement。
    assertDomainRejectedAndHeadUnchanged(
        SdCreateArmyTool.COMMAND_TYPE,
        "{\"armyId\":\"a1\",\"nationId\":\"n1\",\"rootUnitId\":\"u-1\",\"name\":\"第一军\"}",
        "不再接受 nationId");
  }

  /**
   * ★ 坏载荷 = **allowedTools 里含通用写**（N9）。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 2 层</b>：旧载荷还带一个 {@code affiliation} 键（当年要靠它过归属检查，
   * 否则断言会退化成判「affiliation 目标不存在」）。乙收尾（用户裁定「<b>归属肯定归模块</b>」）把归属从 {@code DecisionMaker} 上删掉、改走三条赋权命令
   * ⇒ 该键现在会**先**被具名拒（"不再接受 affiliation"）， 本用例就再也走不到 allowedTools 那条判据。故载荷删掉该键 ——
   * <b>断言一个字未放宽</b>（仍是"通用写 ⇒ 域层具名拒"）， 只是让它落回它要测的那条判据上。★ 上面两步（建区域 / 建国）保留：世界齐备时仍然拒，判别力更强。
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
        "{\"id\":\"dm-1\"," + "\"allowedTools\":[\"simos.command.submit\"],\"cadence\":5}",
        "不得含通用写");
  }

  /** ★ 坏载荷 = **地址连两段都没有**：`Address.parse` 的原文理由是地址语法的前置（载荷层）。 */
  @Test
  void sdPutInfoToolSurfacesThePayloadRejectionForAMalformedAddress() throws Exception {
    assertDomainRejectedAndHeadUnchanged(
        SdPutInfoTool.NAME, "{\"address\":\"nope\",\"key\":\"k\",\"value\":\"v\"}", "地址至少两段");
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
    Map<PeopleLotId, PopulationGroup> groups = mixedGroups();
    SocialData social =
        SocialHouseholdFixture.withHouseholdsAt(
            new LinkedHashMap<>(Map.of(H11, populationSeries())), Map.of(), groups, H11);
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "ga", new GaSnapshot(ref("main", 1), T7, GaState.empty()),
                // ★ D1/D4：army 切片在场（读取口 simos.army.combats/combat 与交战目标检查都需要它；
                //   本夹具为空表 ⇒ 读口给空数组 / 单条详情给 NOT_FOUND）。
                "army", new ArmySnapshot(ref("main", 1), T7, ArmyData.empty()),
                // ★ Z3c-2（Z6a）：gov 切片必须在场（simos.gov.info 即使 GM 省略 govUnitId 也要先取 gov 片；
                //   本夹具为空表 ⇒ governmentCount=0/governments=[]，键仍在）。
                "gov", new GovSnapshot(ref("main", 1), T7, GovState.empty()),
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
                    new GaCodec(),
                    new EconomyCodec(),
                    new ActorCodec(),
                    new ArmyCodec(),
                    new GovCodec())));
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
    groups.put(id, new PopulationGroup(id, sex, count, ageAtAnchorDays, T0.tick()));
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
