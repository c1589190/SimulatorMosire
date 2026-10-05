package io.mosire.simos.app;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalHttpEndpoint;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.ConfirmGate;
import io.mosire.agentlib.approval.HttpApprovalChannel;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.resolve.ActorResolver;
import io.mosire.simos.actor.spi.ActorClearRegionHandler;
import io.mosire.simos.actor.spi.ActorSeedHandler;
import io.mosire.simos.actor.spi.AdjustAccountsHandler;
import io.mosire.simos.actor.spi.DeductHouseholdStockHandler;
import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.actor.spi.RemitGovTreasuryHandler;
import io.mosire.simos.actor.spi.TransferAccountsHandler;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.GmAutoApproveGate;
import io.mosire.simos.app.decision.DecisionAgentRunner;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.decision.NationOpeningSnapshot;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.gm.RecordingToolSource;
import io.mosire.simos.app.gui.GuiServer;
import io.mosire.simos.app.llm.AgentLibLlmConfig;
import io.mosire.simos.app.llm.LlmProviderResolver;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.render.ArtifactStore;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.app.sd.DecisionAdjudicationService;
import io.mosire.simos.app.sd.SdCommandDrain;
import io.mosire.simos.app.sd.channel.CliDecisionChannel;
import io.mosire.simos.app.sd.channel.GuiDecisionChannel;
import io.mosire.simos.app.sd.channel.HttpDecisionChannel;
import io.mosire.simos.app.skill.SkillLibrary;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.time.PopulationEconomyTimeParticipant;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.army.resolve.ArmyResolver;
import io.mosire.simos.army.spi.AppendCombatStageHandler;
import io.mosire.simos.army.spi.RecordCombatHandler;
import io.mosire.simos.army.spi.ResolveCombatStageHandler;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.resolve.EconomyResolver;
import io.mosire.simos.economy.spi.EconomyAddDemandHandler;
import io.mosire.simos.economy.spi.EconomyCancelDemandHandler;
import io.mosire.simos.economy.spi.EconomyClearRegionHandler;
import io.mosire.simos.economy.spi.EconomyGmAdjustHandler;
import io.mosire.simos.economy.spi.EconomyMigrateHouseholdHandler;
import io.mosire.simos.economy.spi.EconomyRegisterCandidateHandler;
import io.mosire.simos.economy.spi.EconomyRegisterGovernmentHandler;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.economy.spi.EconomySetHouseholdClassHandler;
import io.mosire.simos.economy.spi.EconomySetHouseholdLaborHandler;
import io.mosire.simos.economy.spi.EconomySetHouseholdParticipationHandler;
import io.mosire.simos.economy.spi.EconomySetMarketPriceHandler;
import io.mosire.simos.economy.spi.EconomySwitchModeHandler;
import io.mosire.simos.economy.spi.EconomyTransferOwnershipStakeHandler;
import io.mosire.simos.economy.spi.EconomyUpdateDemandHandler;
import io.mosire.simos.economy.spi.UnitBorrowHandler;
import io.mosire.simos.economy.spi.UnitRepayHandler;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.spi.CreateRegionHandler;
import io.mosire.simos.map.spi.DeleteRegionHandler;
import io.mosire.simos.map.spi.MergeRegionsHandler;
import io.mosire.simos.map.spi.RandomizeRegionHandler;
import io.mosire.simos.map.spi.ReassignHexesHandler;
import io.mosire.simos.map.spi.RegisterPathwayGroupHandler;
import io.mosire.simos.map.spi.RenameRegionHandler;
import io.mosire.simos.map.spi.SetEdgeHandler;
import io.mosire.simos.map.spi.SetTerrainHandler;
import io.mosire.simos.map.spi.SplitRegionHandler;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.sd.channel.ActorId;
import io.mosire.simos.sd.channel.DecisionChannel;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.guard.RegionDeleteGuard;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.resolve.SdResolver;
import io.mosire.simos.sd.spi.AddStageHandler;
import io.mosire.simos.sd.spi.CancelEffectHandler;
import io.mosire.simos.sd.spi.CommitOutcomeHandler;
import io.mosire.simos.sd.spi.CreateArmyHandler;
import io.mosire.simos.sd.spi.CreateCombatHandler;
import io.mosire.simos.sd.spi.CreateDecisionMakerHandler;
import io.mosire.simos.sd.spi.CreateNationHandler;
import io.mosire.simos.sd.spi.DeleteDecisionMakerHandler;
import io.mosire.simos.sd.spi.DeleteNationHandler;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import io.mosire.simos.sd.spi.IssueDirectiveHandler;
import io.mosire.simos.sd.spi.PutInfoHandler;
import io.mosire.simos.sd.spi.RecordCasualtiesHandler;
import io.mosire.simos.sd.spi.RecordDiplomaticEventHandler;
import io.mosire.simos.sd.spi.RegisterEffectHandler;
import io.mosire.simos.sd.spi.ResetDecisionMakerConversationHandler;
import io.mosire.simos.sd.spi.RunDecisionHandler;
import io.mosire.simos.sd.spi.SetArmyMasterGovHandler;
import io.mosire.simos.sd.spi.SetDecisionMakerAccessHandler;
import io.mosire.simos.sd.spi.SetDecisionMakerProviderHandler;
import io.mosire.simos.sd.spi.SetDiplomaticRelationHandler;
import io.mosire.simos.sd.spi.SetDirectiveStatusHandler;
import io.mosire.simos.sd.spi.SetOutcomeTableHandler;
import io.mosire.simos.sd.spi.StartDecisionHandler;
import io.mosire.simos.sd.spi.SubmitVerdictHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.facet.PopulationFacet;
import io.mosire.simos.social.resolve.SocialResolver;
import io.mosire.simos.social.spi.AddHouseholdMembersHandler;
import io.mosire.simos.social.spi.AdjustHouseholdPopulationHandler;
import io.mosire.simos.social.spi.ClearRegionHandler;
import io.mosire.simos.social.spi.CreateCityHandler;
import io.mosire.simos.social.spi.CreateHouseholdHandler;
import io.mosire.simos.social.spi.DeleteCityHandler;
import io.mosire.simos.social.spi.MoveCityHandler;
import io.mosire.simos.social.spi.MovePopulationLotsHandler;
import io.mosire.simos.social.spi.RemoveHouseholdMembersHandler;
import io.mosire.simos.social.spi.SeedGroupsHandler;
import io.mosire.simos.social.spi.SetDemandCoefficientHandler;
import io.mosire.simos.social.spi.SetHouseholdLocationHandler;
import io.mosire.simos.social.spi.SetHouseholdVitalRatesHandler;
import io.mosire.simos.social.spi.SetLaborCoefficientHandler;
import io.mosire.simos.social.spi.SetPopulationHandler;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.social.spi.TransferHouseholdMembersHandler;
import io.mosire.simos.social.spi.UpdateCityHandler;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.facet.UnitsHereFacet;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.resolve.UnitResolver;
import io.mosire.simos.unit.spi.AdjustCompositionHandler;
import io.mosire.simos.unit.spi.ApplyCasualtiesHandler;
import io.mosire.simos.unit.spi.AttachUnitHandler;
import io.mosire.simos.unit.spi.CancelRouteHandler;
import io.mosire.simos.unit.spi.CreateCommandChainHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.DetachUnitHandler;
import io.mosire.simos.unit.spi.DisbandUnitHandler;
import io.mosire.simos.unit.spi.DismissStaffHandler;
import io.mosire.simos.unit.spi.MergeFormationHandler;
import io.mosire.simos.unit.spi.PlaceAtHandler;
import io.mosire.simos.unit.spi.PlanRouteHandler;
import io.mosire.simos.unit.spi.PlanSparseRouteHandler;
import io.mosire.simos.unit.spi.RecruitStaffHandler;
import io.mosire.simos.unit.spi.RenameUnitHandler;
import io.mosire.simos.unit.spi.ReparentSubtreeHandler;
import io.mosire.simos.unit.spi.ReparentUnitHandler;
import io.mosire.simos.unit.spi.SetArmyFormationHandler;
import io.mosire.simos.unit.spi.SetCompositionHandler;
import io.mosire.simos.unit.spi.SetFormationOffsetHandler;
import io.mosire.simos.unit.spi.SetGovernmentFormationHandler;
import io.mosire.simos.unit.spi.SetGovPolicyHandler;
import io.mosire.simos.unit.spi.SetGovSuperiorHandler;
import io.mosire.simos.unit.spi.SetJurisdictionHandler;
import io.mosire.simos.unit.spi.SetRejoinTargetHandler;
import io.mosire.simos.unit.spi.SetStateDescriptionHandler;
import io.mosire.simos.unit.spi.SetStatusHandler;
import io.mosire.simos.unit.spi.SetTaxRateHandler;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.unit.spi.SetVisionRadiusHandler;
import io.mosire.simos.unit.spi.SplitFormationHandler;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.unit.spi.UpdateCommandChainHandler;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外壳：**唯一的装配点**（spec §3.2 的 1~7 步）。
 *
 * <p>★ **它是全仓唯一组装 CoreSimos 与领域模块的地方**：Core 的 main scope 看不见任何领域类型（ADR-1），全部模块 codec / 全部 handler /
 * 全部 participant 必须由组合根注入。审批链（T6）、MCP 服务（T7）与 GUI（T8）都已接上——{@link #start} 走到"世界能提交命令、能重放、
 * 能推进、能查询、能经 {@code /api} 与 MCP 工具面读写、写命令要过人审批"为止。
 *
 * <p>★ **装配清单**（spec §3.2 第 1~7 步）：各模块 codec（map/social/unit/sd/economy/actor/gov/army）+ 全部 handler
 * + 全部 participant（{@link CoreSimos} 侧，另有一个写前守卫 {@code RegionDeleteGuard}）+ 各域 {@code Resolver}
 * （map/social/unit/sd/economy/actor/army）+ 两 {@code FacetProvider}（unitsHere/population）→ {@link
 * QueryService}（查询层，T3）；审批链（T6，S5：{@code PendingApprovals → HttpApprovalChannel →
 * ApprovalCoordinator → ApprovalHttpEndpoint}，无 Superior 判定）→ {@link SimosToolSource}（spec
 * §2.1：**唯一的 MCP 口 = {@link SimosToolSource.Role#GM}** = 读工具 + 通用写 + 全部窄写；**条数以工具面为准**，不在此钉死）经
 * {@code McpSourceBridge.bind} 同步进 {@link ToolRegistry}（T5）→ {@link
 * AgentToMcpServer#startHttp}（**一次**，第 6 步，T7）→ GUI（第 7 步，T8）。
 *
 * <p>★ **本条刻意不钉 handler 条数**：写「四十二」的时候**实际已经是 44**（漏改过两次），而条数由下面那个注册块唯一决定、 看一眼就知道 ⇒
 * 钉死只会制造一处没人维护的谎（与工具面注释同一条纪律）。
 *
 * <p>★ **本类不持有任何存储写路径**：{@code SqliteStore} / {@code Timeline.appendRevision} / {@code
 * CheckpointStore} 一个都不在 app 源码里（铁律 2 的结构化，R1 的扫描对象）。唯一的写入口是 {@link
 * CoreSimos#submit(io.mosire.simos.util.state.Command)}。
 */
public final class Shell implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  /** MCP server 自报名称（spec §7.2）。 */
  private static final String MCP_SERVER_NAME = "simos-shell";

  /** MCP server 自报版本（spec §7.2 的 {@code "0.1.0-SNAPSHOT"}）。 */
  private static final String MCP_SERVER_VERSION = "0.1.0-SNAPSHOT";

  /** 审批等待上限（spec 未定值，M5 取 5 分钟）：到点没人答 ⇒ fail-closed 拒（AgentLib 契约）。 */
  private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);

  /**
   * 世界生成器冻结输入（v17levant 三国）的路径：**相对工作目录（仓根）拼**，与 {@code SkillLibrary} 的 {@code config/skills}
   * 同一约定。★ 路径不存在时由 {@code WorldgenConfig.load} **fail-closed**（{@code simos.worldgen.initialize}
   * 把它折成带绝对路径的 {@code BAD_REQUEST}），不静默兜底。
   */
  private static final Path WORLDGEN_CONFIG_FILE =
      Path.of("config", "worldgen", "v17levant-nations.json");

  private final ShellConfig config;
  private final CoreSimos coreSimos;

  /** 历法/气候配置服务（C5）：启动时从 {@code store_meta.calendar} 装载，participant 与四个 gov 写工具共用同一实例。 */
  private final CalendarService calendarService;

  /** 查询层（T3）：GUI 与工具集唯一的只读入口（spec §5.1）。 */
  private final QueryService queryService;

  /**
   * 工具注册表（T5）：GM 组（{@link SimosToolSource.Role#GM}，= 唯一的 MCP 口）的全部工具经桥同步进此表，交给 {@code
   * AgentToMcpServer}。
   */
  private final ToolRegistry toolRegistry;

  /** 工具源 ↔ 注册表的同步桥（T5）：{@link #close()} 时整组下架本桥带入的工具。 */
  private final McpSourceBridge toolBridge;

  /** MCP 服务（T7，spec §7.2）：5715 的流式 HTTP 面；关闭次序里排第二（spec §3.3）。★ 全仓**唯一**一个 MCP 口。 */
  private final AgentToMcpServer mcpServer;

  /** GUI 服务器（T8）：5711 的静态页 + {@code /api}；关闭次序里排第一（spec §3.3）。 */
  private final GuiServer guiServer;

  /** 审批登记表（T6，spec §3.2 第 3 步）：进程内唯一的那一份，端点与通道共享同一 id。 */
  private final PendingApprovals pendingApprovals;

  /** HTTP 审批通道（T6）：端点真的绑定成功后才 {@code markUp()}（可用性认"端口在监听"）。 */
  private final HttpApprovalChannel approvalChannel;

  /** 审批编排器（T6，S5）：gates = {@code AutoApproveGate → ConfirmGate}，M5 无 LLM 上级判定。 */
  private final ApprovalCoordinator approvalCoordinator;

  /** 审批 HTTP 端点（T6，spec §3.2 第 4 步）：恒绑回环；关闭次序在 GUI 之后（spec §3.3）。 */
  private final ApprovalHttpEndpoint approvalEndpoint;

  /** 工具调用唯一入口（T6）：**带审批**（非 {@code standard()}），T7 交给 {@code startHttp}。 */
  private final ToolCallAuthorizer toolAuthorizer;

  /**
   * ★ GM 面（MCP 口）的 authorizer：与 {@link #toolAuthorizer} **同一套权限/资源判定**，唯一差别是审批链上挂的是 {@link
   * GmAutoApproveGate}（无脑过，2026-09-24 用户裁定）。装配自检与用例的读回口径，不是对外 API。
   */
  private final ToolCallAuthorizer gmToolAuthorizer;

  /** 已注册模块 codec 的个数（map/social/unit/sd）；由实际注册动作数出来，不是写死的常量。 */
  private final int registeredModuleCount;

  /**
   * 跨模块效果落点（C5，spec §五.3）：{@code AdvanceTime} 提交成功后把 sd 的 pending 指令经 {@code submit} 落成真 revision。
   */
  private final SdCommandDrain sdCommandDrain;

  /** 决策提交渠道（D5，spec §十三）：GUI / MCP / CLI / 外部 HTTP 各一实现——**新增渠道不改领域代码**。 */
  private final List<DecisionChannel> decisionChannels;

  /**
   * **GM catalog 可见的命令类型**（E6b 起 = 完整注册面，含 {@link GmOnlyCommand}）：按角色重建工具面时供 catalog 读。 ★ 决策人侧仍由
   * {@code CatalogVisibility} 按权限过滤 —— GM-only 命令不在其"可嵌入令"白名单里，故决策人看不到。
   */
  private final Set<String> commandTypes;

  /**
   * **可嵌入令 / 可入 RegisterEffect 的白名单输入集**（E6b 起与 catalog 面拆开；= 注册面 − {@link GmOnlyCommand}）： {@code
   * IssueDirectiveHandler} 的 {@code DirectiveWhitelist}（再过滤 {@code sd.*} 自指与通用写）、{@code
   * sd.AdjudicateTick} 重建的白名单与 {@code RegisterEffectHandler} 的可入队白名单共用这一份。★ GM-only 命令仍注册在 Core、仍进
   * {@code commandTargets}，GM 的 {@code simos.command.submit} 不受影响。
   */
  private final Set<String> directiveCommandTypes;

  /**
   * Skill 库（2026-09-23）：决策人的**外部方法论与常识**（仓库种子 {@code config/skills} + store 覆盖 {@code
   * <storeDir>/skills}）。
   *
   * <p>★ **由 {@code storeDir} 现推、不进装配参数**：它不在世界 revision 内（与 {@code conversations.db}、 {@code
   * agentlib/} 同族），没有"该配给哪个世界"这一维；多一个装配参数只会多一处可能传错的地方。
   */
  private final SkillLibrary skillLibrary;

  /** 渲染服务（P3）：三面共用（决策人 / MCP 工具 / GUI 出图）；工件库与它同源（一个实例，别处不要再 new）。 */
  private final RenderService renderService;

  /** 工件库：渲染产出的 PNG（内容寻址）——同时也是 AgentLib 的资产解析器（工具结果的 assetId 指这里）。 */
  private final ArtifactStore artifactStore;

  /**
   * {@code 命令类型 → 目标声明}（第 3 波第 2 步）：从**已注册的 handler 清单**派生——实现了 {@link CommandTargets}
   * 的那些把自己的目标交出来。{@code sd.AdjudicateTick} 拿它判"GM 代执行的这条命令动的 是谁"；**未实现者不在表里** ⇒ 工具侧 fail-closed 拒。
   */
  private final Map<String, CommandTargets> commandTargets;

  /**
   * LLM provider 配置（M11′ 对接版）：由 AgentLib 的 {@code ConfigStore} 承载 {@code llm.routes.*} / {@code
   * keys.*} （用户裁定「Provider 配置归 AgentLib」）。
   */
  private final AgentLibLlmConfig llmConfig;

  /**
   * GM 口工具使用记录（T8）：{@code /api/gm/tool-usage} 与 MCP 的 {@code simos.gm.tool-usage} 的唯一数据源； {@code
   * RecordingToolSource} 与 GUI 共用**同一个**实例（工具面补齐 2026-09-25 起它也要在工具源装配时就绪）。
   */
  private final GmToolUsage gmToolUsage;

  /** 决策编排（T3）：把 {@code AdjudicatorRunner} 接进壳——「开始决策」真的会跑 LLM 判决。 */
  private final DecisionAdjudicationService decisionAdjudicationService;

  /** 决策人 agent 运行流（T11C）：{@code sd.RunDecision} 触发的那一轮（真 LLM + 真工具）。 */
  private final DecisionAgentService decisionAgentService;

  /** 决策人的会话存储（T11C）：落 {@code <store>/conversations.db} ⇒ 跨 tick / 跨重启沿用同一段会话。 */
  private final SqliteConversationStore decisionConversations;

  private volatile boolean closed;

  private Shell(
      ShellConfig config,
      CoreSimos coreSimos,
      CalendarService calendarService,
      QueryService queryService,
      ToolRegistry toolRegistry,
      McpSourceBridge toolBridge,
      AgentToMcpServer mcpServer,
      GuiServer guiServer,
      PendingApprovals pendingApprovals,
      HttpApprovalChannel approvalChannel,
      ApprovalCoordinator approvalCoordinator,
      ApprovalHttpEndpoint approvalEndpoint,
      ToolCallAuthorizer toolAuthorizer,
      ToolCallAuthorizer gmToolAuthorizer,
      int registeredModuleCount,
      SdCommandDrain sdCommandDrain,
      List<DecisionChannel> decisionChannels,
      Set<String> commandTypes,
      Set<String> directiveCommandTypes,
      Map<String, CommandTargets> commandTargets,
      SkillLibrary skillLibrary,
      RenderService renderService,
      ArtifactStore artifactStore,
      AgentLibLlmConfig llmConfig,
      GmToolUsage gmToolUsage,
      DecisionAdjudicationService decisionAdjudicationService,
      DecisionAgentService decisionAgentService,
      SqliteConversationStore decisionConversations) {
    this.config = config;
    this.coreSimos = coreSimos;
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
    this.queryService = queryService;
    this.toolRegistry = toolRegistry;
    this.toolBridge = toolBridge;
    this.mcpServer = mcpServer;
    this.guiServer = guiServer;
    this.pendingApprovals = pendingApprovals;
    this.approvalChannel = approvalChannel;
    this.approvalCoordinator = approvalCoordinator;
    this.approvalEndpoint = approvalEndpoint;
    this.toolAuthorizer = toolAuthorizer;
    this.gmToolAuthorizer = gmToolAuthorizer;
    this.registeredModuleCount = registeredModuleCount;
    this.sdCommandDrain = sdCommandDrain;
    this.decisionChannels = List.copyOf(decisionChannels);
    this.commandTypes = Set.copyOf(commandTypes);
    this.directiveCommandTypes = Set.copyOf(directiveCommandTypes);
    this.skillLibrary = Objects.requireNonNull(skillLibrary, "skillLibrary");
    this.renderService = Objects.requireNonNull(renderService, "renderService");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.commandTargets = Map.copyOf(commandTargets);
    this.llmConfig = llmConfig;
    this.gmToolUsage = Objects.requireNonNull(gmToolUsage, "gmToolUsage");
    this.decisionAdjudicationService = decisionAdjudicationService;
    this.decisionAgentService = decisionAgentService;
    this.decisionConversations = decisionConversations;
  }

  /**
   * 起壳：建 CoreSimos 并按其装配顺序注册**各模块 codec + 全部 handler + 全部 participant**，再装**查询层**（各域 resolver + 两个真
   * facet + {@link QueryService}）。
   *
   * <p>★ {@code MovementCost} 由 app 注入（M3 口径）：{@link TerrainMovementCost} 是当前唯一实现，取它的单例 {@link
   * TerrainMovementCost#INSTANCE}（构造器私有，不能 {@code new}——这是对派单文字 {@code new TerrainMovementCost()}
   * 的一处就地校正）。
   *
   * <p>★ **GUI 在此启动**（T8，spec §3.2 第 7 步）：{@code guiPort=0} 时由 OS 分配随机端口，实际端口经 {@link
   * #boundGuiPort()} 读回；关闭由 {@link #close()} 按 spec §3.3 的次序（GUI 第一）负责。
   *
   * @param config 装配配置
   * @return 已装配、尚未封存的壳（封存发生在第一次 {@code submit}/{@code replay}）
   * @throws NullPointerException {@code config} 为 null
   * @throws IllegalStateException GUI 绑定失败（端口被占等）
   */
  public static Shell start(ShellConfig config) {
    return start(config, null);
  }

  /**
   * 同 {@link #start(ShellConfig)}，但可**显式注入决策人 agent 的 LLM 客户端来源**（T11C）。
   *
   * <p>★ **为什么有这条缝**：{@code sd.RunDecision} 触发的那一轮要**真调 LLM**，而本仓的用例纪律是"测试不打真网络" ⇒ 用例要能把 {@code
   * FakeLlmClient} 注入**真壳**（真 MCP 口、真审批链、真工具面、真 store），而不是另搭一套 装配——那样测到的就不是"壳接到哪里"了。
   *
   * <p>★ **它只替得掉"怎么造客户端"**：未绑定 provider 的 fail-closed 判定在 {@link DecisionAgentService} 里（世界事实那一半），
   * 注入什么实现都绕不过去；生产路径（{@code null}）用 {@link LlmProviderResolver}（配置那一半也 fail-closed）。
   *
   * @param decisionLlmClients {@code null} ⇒ 生产路径（按决策人 {@code providerId} 解析真 provider）
   */
  public static Shell start(
      ShellConfig config, DecisionAgentService.LlmClients decisionLlmClients) {
    Objects.requireNonNull(config, "config");
    CoreSimos coreSimos =
        new CoreSimos(
            new CoreConfig(
                config.storeDir(), config.checkpointInterval(), SimosObjectMapper.create()));
    // ★ C5：历法配置随 core 立刻装载（store_meta.calendar；缺省不写盘）；解析/校验失败即启动 fail-closed。
    CalendarService calendarService = CalendarService.load(coreSimos);

    List<ModuleCodec> codecs =
        List.of(
            new MapCodec(),
            new SocialCodec(),
            new UnitCodec(),
            new SdCodec(),
            new EconomyCodec(),
            // ★ S1 阶段 2：第六个切片（actor）。★ 它的 namespace() 恒 "actor" —— 必须与 ActorSnapshot.namespace()
            //   同字面（ToolSupport.ACTOR_NAMESPACE 是第三处），写歪 SimulationState 构造期当场抛。
            new ActorCodec(),
            // ★ 阶段 10a（2026-09-30 GOV/Army 计划 §2.2）：第七个切片（gov）。★ namespace() 恒 "gov" —— 必须与
            //   GovSnapshot.namespace() 同字面，写歪 SimulationState 构造期当场抛。本阶段只注册 codec
            //   （无 participant/handler，每 tick 结算由阶段 11 并入 actor 写者）。
            new GovCodec(),
            // ★★ D1（2026-10-02 / D-012）：第八个切片（army）——交战记录的唯一载体。★ namespace() 恒 "army" ——
            //   必须与 ArmySnapshot.namespace() 同字面，写歪 SimulationState 构造期当场抛。
            new ArmyCodec());
    for (ModuleCodec codec : codecs) {
      coreSimos.register(codec);
    }

    List<CommandHandler> handlers =
        new ArrayList<>(
            List.of(
                new SetTerrainHandler(),
                new CreateRegionHandler(),
                new UpdateRegionHandler(),
                // ★★ P1.2（2026-10-09 后端行政批次）：区划语义命令——map 只改自己的 regions；
                //    jurisdiction/城市/税率/编制跟随重算由 app 组合根 submitBatch 协调（不反向依赖）。
                new MergeRegionsHandler(),
                new SplitRegionHandler(),
                new ReassignHexesHandler(),
                // ★ R4（行政区划修复计划 §1.4）：GM 改区域名。标 GmOnlyCommand ⇒ 排除出令白名单 / RegisterEffect /
                //   决策人目录；GM 直接提交照常可用。──
                new RenameRegionHandler(),
                new DeleteRegionHandler(),
                new SetEdgeHandler(),
                new RegisterPathwayGroupHandler(),
                new RandomizeRegionHandler(),
                new RenameUnitHandler(),
                new CreateUnitHandler(),
                new ReparentUnitHandler(),
                new SetCompositionHandler(),
                new PlaceAtHandler(),
                new PlanRouteHandler(),
                new CancelRouteHandler(),
                new DisbandUnitHandler(),
                new SetStatusHandler(),
                // ★★ P1.2 / A6：视野半径命令（字段早已存在，本次补写路径；非 GmOnly，目标声明见 handler）。
                new SetVisionRadiusHandler(),
                // ★★ D1（2026-10-02 / D-012）：当前回合状态 ↔ 状态描述地址的 upsert / 删除。★ 非 GmOnly ⇒
                //   与既有 unit 命令同待遇（可嵌进决策令，目标声明见 handler 自己的 CommandTargets）；
                //   GM 侧的窄写工具 simos.unit.set-state-description 走这一条 handler。
                new SetStateDescriptionHandler(),
                new AttachUnitHandler(),
                new DetachUnitHandler(),
                new ReparentSubtreeHandler(),
                new SetFormationOffsetHandler(),
                new SplitFormationHandler(),
                new MergeFormationHandler(),
                new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE),
                new SetRejoinTargetHandler(),
                new CreateCommandChainHandler(),
                new UpdateCommandChainHandler(),
                new ApplyCasualtiesHandler(),
                // ★★ D3a（2026-10-02 / D-009 补裁）：单单位人力/装备的 GM 调试直改原语（有符号增量，一条命令原子）。
                //   GmOnly（控制器 2026-10-02 收紧）⇒ 不进决策令 / RegisterEffect 白名单；GM 直通口是窄工具
                //   simos.unit.adjust-composition（只在 GM 桶），以及 GM 的 simos.command.submit。──
                new AdjustCompositionHandler(),
                // ── S3a（2026-10-09）：单个 unit 容纳的家户列表整体替换（unit 侧唯一写口；非 GmOnly ⇒ 与既有 unit
                //   命令同待遇；"加入/移出 Unit"的两侧一致性由 app 组合工具同批保证）。──
                new SetUnitHouseholdsHandler(),
                // ── 辖区阶段 5（2026-09-30）：管辖区域集合 + 长期税率两条窄写。非 GmOnly ⇒ 与既有 unit 命令同待遇
                //   （仍可嵌入决策人令；"只在 GM 桶"说的是配套窄工具）。──
                new SetJurisdictionHandler(),
                new SetTaxRateHandler(),
                // ── 阶段 10a（2026-09-30）：两条"立编制"命令（编制字段在 Unit.module ⇒ unit 域命令）。非 GmOnly
                //   ⇒ 与既有 unit 命令同待遇（仍可嵌入决策人令；"只在 GM 桶"说的是配套窄工具）。──
                new SetGovernmentFormationHandler(),
                new SetArmyFormationHandler(),
                // ── 阶段 10b-i（2026-10-01）：GOV 政策 / 层级 / 入编 / 离编四条 unit 命令。全部非 GmOnly
                //   ⇒ 与既有 unit 命令同待遇（可嵌入决策令）。★ Recruit/Dismiss 有意**不配** GM 窄工具
                //   （那会变成"凭空造人/跳过支付"的直通口）：它们只作为命令，由 10b-ii 的配套工具批/决策令批使用。──
                new SetGovPolicyHandler(),
                new SetGovSuperiorHandler(),
                new RecruitStaffHandler(),
                new DismissStaffHandler(),
                // ── social（4 条既有）：逐格农村人口 + 城市节点 + **人口批次**（R1 的 T3：人口的唯一落盘入口）。
                //   非 sd 前缀 ⇒ 自动进 drainableCommandTypes（见下）──
                new SetPopulationHandler(),
                new CreateCityHandler(),
                new UpdateCityHandler(),
                // ★★ P1.2（2026-10-09 后端行政批次）：城市落点迁移 / 严格删城 / 按居住格或家户批量迁移人口批次。
                //   三条都只写 SocialData；城籍由批次 id 前缀承载，MoveCity 身份不变 ⇒ 人口派生不丢。
                new MoveCityHandler(),
                new DeleteCityHandler(),
                new MovePopulationLotsHandler(),
                new SeedGroupsHandler(),
                // ── S3a（2026-10-09 家户/人口架构 §4.1）：家户生命周期七条逐操作命令——创建 / 位置 / 增人 / 减人 /
                //   转移 / 设率 / GM 直调人口。全部只写 SocialData、返回 SocialChangeSet；UNIT 位置的 unit 侧一致性
                //   由 app 组合工具同批保证（social 域不认识 unit）。非 sd 前缀 ⇒ 自动进 drainableCommandTypes。──
                new CreateHouseholdHandler(),
                new SetHouseholdLocationHandler(),
                new AddHouseholdMembersHandler(),
                new RemoveHouseholdMembersHandler(),
                new TransferHouseholdMembersHandler(),
                new SetHouseholdVitalRatesHandler(),
                new AdjustHouseholdPopulationHandler(),
                // ── Batch 4（2026-10-09 家户结构修复计划）：Social 需求/劳动系数两条 GM 命令——全局默认 + 单家户
                //   覆盖的 upsert/清除（只写 SocialData 第 6 组件 provisioning，结果经 withProvisioning 写回）。
                //   两条都标 GmOnlyCommand ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 直接提交与
                //   simos.social.demand / simos.social.labor 两条窄工具照常可用。──
                new SetDemandCoefficientHandler(),
                new SetLaborCoefficientHandler(),
                // ── S3b（2026-10-09 用户裁定）：**唯一家户人口变更受理口**——工单（target + 有序 plan + reason +
                //   source）在一张工作副本上顺序应用，任一操作失败整单具名拒；成功落一条 SocialChangeSet。
                //   ★ 不作 GmOnly：它是本批给 Unit/Eco 等调用方预留的正式入口，且逐操作旧命令仍并存。──
                new SubmitHouseholdWorkOrderHandler(),
                // ── P1b1（2026-10-01）：GM-only 区域社会数据清空（目标 Region 格集内的 populations/groups/cities）。
                //   标 GmOnlyCommand ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 直接提交照常可用。──
                new ClearRegionHandler(),
                // ── economy（1 条，R2a）：一次播种某国全部格的初始经济状态（§十"验收目标 A"）──
                new EconomySeedHandler(),
                // ── economy（P2-C §13.7）：把一个 GOV 单位登记成"该单位恰一份政府 + 恰一个政府家户"。
                //   身份由 govUnitId 派生（政府 = gov-unit-<id>、家户 = hh-gov-<id>），写
                //   classes/classStandings/governments 三张表。★ GM-only（结构身份，非日常家户配置）。
                new EconomyRegisterGovernmentHandler(),
                // ── economy（S3）：家户迁移的最小合法入口（只搬视图/份额，不生成人口；账 location 不搬）──
                new EconomyMigrateHouseholdHandler(),
                // ── economy（R4-B.3b）：GM/事件用的实物资产份额拆分/转移（确定性新 id、逐资产守恒）──
                new EconomyTransferOwnershipStakeHandler(),
                // ── economy（R4-E2a）：价格 / 需求账本 / 候选预设 —— GM simos.command.submit 路径可用；
                //   进入采用算法（E2b）不在本片。──
                new EconomySetMarketPriceHandler(),
                new EconomyAddDemandHandler(),
                new EconomyCancelDemandHandler(),
                new EconomyRegisterCandidateHandler(),
                // ── economy（P2-B §13.6）：家户经济配置窄写四条 —— Class 归属、可参与生产方式（多生产方式）、
                //   劳动时间/参与率、需求更新。★ 全部**非 GmOnly**（与 social 家户命令、AddDemand 同待遇）
                //   ⇒ 进 directive 白名单/RegisterEffect；是否到得了决策令由 economy 命名空间的目标格可达面决定
                //   （handler 的 CommandTargets 从载荷 at 声明目标格，见各自类注）。──
                new EconomySetHouseholdClassHandler(),
                new EconomySetHouseholdParticipationHandler(),
                new EconomySetHouseholdLaborHandler(),
                new EconomyUpdateDemandHandler(),
                // ── economy（E6a）：模式变迁登记（只写 PENDING；执行在日结算自动组织之前）。★ GM-only：
                //   标 GmOnlyCommand ⇒ 排除出 DirectiveWhitelist / 工具目录，但 handler 仍注册、仍进
                // commandTargets，
                //   GM 的 simos.command.submit 照常可用（见下方 directiveCommandTypes）。──
                new EconomySwitchModeHandler(),
                // ── economy（E6b）：GM 经济调整（减免债务 / upsert 清算政策）。★ 同样 GM-only：handler 照常注册、
                //   照进 commandTargets；但排除出令白名单 / RegisterEffect / 决策人目录（见下方
                //   catalogCommandTypes 与 directiveCommandTypes 的拆分）。──
                new EconomyGmAdjustHandler(),
                // ── P1b1（2026-10-01）：GM-only 区域经济数据清空（目标格 industries/markets + 可靠可定位的连带记录）。
                //   标 GmOnlyCommand ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 直接提交照常可用。──
                new EconomyClearRegionHandler(),
                // ── P2-D：单位向放贷方借/还的债务腿两条（GM-only；放贷方 = 家户/政府家户）。
                //   资金腿由组合根用 actor.TransferAccounts 与它们同批提交（单条命令只能落一个命名空间）；
                //   见两个 handler 的类注与 P2-D 报告。──
                new UnitBorrowHandler(),
                new UnitRepayHandler(),
                // ── actor（1 条，S1 阶段 2）：actor.Seed —— 一次种入某地图的 actor 分片（主体/产权/商品库存三张表）。
                //   非 sd 前缀 ⇒ 自动进 drainableCommandTypes（见下）；同时也进 commandTypes ⇒
                //   simos.command.submit 的目标声明表（CommandTargets）同源认得它。──
                new ActorSeedHandler(),
                // ── actor（阶段 6 / 计划 §6.2）：actor.AdjustAccounts —— 按有符号净增量改 actor 账
                //   （整条原子；缺账 + 纯正增量新建）。非 sd 前缀 ⇒ 自动进 drainableCommandTypes；
                //   同时进 commandTypes ⇒ simos.command.submit 的目标声明表（CommandTargets）同源认得它。──
                new AdjustAccountsHandler(),
                // ── P1.2（2026-10-09 通用扣除接口裁定）：actor.DeductHouseholdStock —— 传入「家户 + 库存 + reason
                //   (+ 可选收款方)」的通用扣除；整条原子、走 AvailableStock、reason 为封闭词表。
                //   ★ GM-only：与 Adjust/Transfer 同待遇的裸账目原语；税 / 行政俸禄由 app 侧共享 StockDeductionService
                //     直接落 AccountSession，军队俸禄先用本命令 + reason=military_salary 提供后端可用路径。
                //   只写 accounts ⇒ 单 namespace ActorChangeSet；家户账户无格 ⇒ targetPaths 空。──
                new DeductHouseholdStockHandler(),
                // ── P2-C §13.7：给政府家户（或任意新家户）补一本零余额账户（幂等；账户键 = 家户身份）。
                //   ★ GM-only：它是组合工具的裸原语；GM 直接提交照常可用。
                new EnsureHouseholdAccountHandler(),
                // ── P1.2 / P2-A：actor.TransferAccounts（任意两个**家户**账户间商品/货币原子转移）。
                //   GM-only、只写 accounts。★ actor.MoveAccount 已随 P2-A §13.3 退役（账户键不再带格，
                //   位置从 Household.location 派生 ⇒ "搬账"不再是一个动作）。
                new TransferAccountsHandler(),
                // ── R3a（2026-10-01 行政区划修复计划 §1.3）：actor.RemitGovTreasury —— 显式 GOV 国库上缴 /
                //   任意两个 GOV 单位之间转移（整条原子；只动 accounts）。★ **非 GmOnly**：省份决策人可嵌进
                //   sd.IssueDirective；targetPaths 返回源/目标两个 actor 格路径（决策 scope 在 R3b 贯通）。
                //   非 sd 前缀 ⇒ 自动进 drainableCommandTypes；同时进 commandTypes ⇒ CommandTargets 同源认得它。──
                new RemitGovTreasuryHandler(),
                // ── P1b1（2026-10-01）：GM-only 区域 actor 账本清空（目标格账本 + 清账后不再持有账户的主体）。──
                new ActorClearRegionHandler(),
                new CreateNationHandler(),
                // ── P1.2（2026-10-09 后端行政批次）：sd.DeleteNation —— 默认严格引用检查（决策人 / 外交关系 /
                //   map nation tag），显式 clearDiplomaticReferences=true 才连关系与外交事件一起清。GM-only。──
                new DeleteNationHandler(),
                new CreateArmyHandler(),
                // ── 阶段 12 后续赋值（2026-10-01 Army 主子改派缺口）：Army 创建后的主子改派/解除。★ GM-only
                //   （handler 标 GmOnlyCommand ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 直接提交照常可用）。──
                new SetArmyMasterGovHandler(),
                new CreateDecisionMakerHandler(),
                // ── P1b2（2026-10-01）：GM-only 决策人身份删除（只删 decisionMakers 的一条；不级联历史 Directive /
                //   文档 / 会话）。标 GmOnlyCommand ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 直接提交照常可用。──
                new DeleteDecisionMakerHandler(),
                new PutInfoHandler(),
                // ★★ D5（2026-10-02 / D-003、D-005、R6）：sd 的两条外交命令（非 GmOnly；sd.* 不进指令白名单）。
                //   变更集走 SdChangeSet 的第 11/12 个组件（diplomaticRelations / diplomaticEvents），
                //   旧档缺这两个组件 ⇒ 空表（见 SdState / SdChangeSet 的构造期兼容）。
                new SetDiplomaticRelationHandler(),
                new RecordDiplomaticEventHandler(),
                new CreateCombatHandler(),
                new AddStageHandler(),
                new SetOutcomeTableHandler(),
                new CommitOutcomeHandler(),
                new RecordCasualtiesHandler(),
                new CancelEffectHandler(),
                new StartDecisionHandler(),
                new RunDecisionHandler(),
                // ★ 第 3 波最后一块：裁决用的状态翻转命令（sd.SetDirectiveStatus）。它不是对外窄工具——
                //   由 sd.AdjudicateTick 内部编排产生；注册在此是为了它在批里能被 CommandBus 路由到。
                new SetDirectiveStatusHandler(),
                // ★★ D1（2026-10-02 / D-012）：army 切片的第一条命令（写一条单 tick 单场交战记录）。★ 标
                //   GmOnlyCommand ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 直接提交与 GM 窄工具照常可用。
                new RecordCombatHandler(),
                // ★★ D4（2026-10-02 / D-009 补裁 + D-010）：记录演进的两条命令——追加阶段与投骰判定。★ 都标
                //   GmOnlyCommand（同 D1 的边界）；判定算法在 CombatResolution（唯一拼写点），本注册只负责路由。
                new AppendCombatStageHandler(),
                new ResolveCombatStageHandler()));
    Set<String> drainableCommandTypes = new LinkedHashSet<>();
    for (CommandHandler handler : handlers) {
      // ★★ E6a：GM-only 标记同样排除出 `sd.RegisterEffect` 的可入队命令白名单 —— 它是**第三条**决策人可间接
      //   触发的路径（效果在后续 tick FIRED 后由 SdCommandDrain 提交）。判定与 DirectiveWhitelist 同源
      //   （handler 上的标记接口），不另写清单。
      if (!handler.type().startsWith("sd.") && !(handler instanceof GmOnlyCommand)) {
        drainableCommandTypes.add(handler.type());
      }
    }
    handlers.add(new RegisterEffectHandler(drainableCommandTypes));
    // ★★ E6b：把"**GM catalog 可见的命令类型**"与"**可嵌入令/可入 RegisterEffect 的白名单**"拆开：
    //   - catalogCommandTypes = 完整注册面（含 GmOnlyCommand）⇒ GM 的 simos.command.catalog 看得到
    //     economy.SwitchMode / economy.GmAdjust；
    //   - directiveCommandTypes = 注册面 − GmOnlyCommand（E6a 口径不变）⇒ IssueDirective 白名单、
    //     sd.AdjudicateTick 重建的白名单、RegisterEffect 可入队白名单与决策人工具目录继续排除它们。
    //   ★ catalog 的过滤仍在 CatalogVisibility（受限调用者只看到"自己可触发的"）；两条命令对决策人不可见。
    Set<String> catalogCommandTypes = new LinkedHashSet<>();
    Set<String> directiveCommandTypes = new LinkedHashSet<>();
    for (CommandHandler handler : handlers) {
      coreSimos.register(handler);
      catalogCommandTypes.add(handler.type());
      if (!(handler instanceof GmOnlyCommand)) {
        directiveCommandTypes.add(handler.type());
      }
    }

    // ★ 第 3 波第 2 步：命令的**目标声明**从同一份 handler 清单派生（实现了 CommandTargets 的那些）——
    //   与执行面同源 ⇒ 不需要另立一张会漂移的表；未实现者不在表里 ⇒ 工具侧 fail-closed 拒。
    Map<String, CommandTargets> commandTargets = new LinkedHashMap<>();
    for (CommandHandler handler : handlers) {
      if (handler instanceof CommandTargets targets) {
        commandTargets.put(handler.type(), targets);
      }
    }

    // ★ D1：决策命令白名单从**注册面**推导（禁 sd 自指/通用写）⇒ 用上面那份已排除 GM-only 的 directiveCommandTypes。
    IssueDirectiveHandler issueDirectiveHandler =
        new IssueDirectiveHandler(new DirectiveWhitelist(directiveCommandTypes));
    SubmitVerdictHandler submitVerdictHandler = new SubmitVerdictHandler();
    SetDecisionMakerAccessHandler setDecisionMakerAccessHandler =
        new SetDecisionMakerAccessHandler();
    SetDecisionMakerProviderHandler setDecisionMakerProviderHandler =
        new SetDecisionMakerProviderHandler();
    ResetDecisionMakerConversationHandler resetDecisionMakerConversationHandler =
        new ResetDecisionMakerConversationHandler();
    for (CommandHandler late :
        List.of(
            issueDirectiveHandler,
            submitVerdictHandler,
            setDecisionMakerAccessHandler,
            setDecisionMakerProviderHandler,
            resetDecisionMakerConversationHandler)) {
      handlers.add(late);
      coreSimos.register(late);
      catalogCommandTypes.add(late.type());
      directiveCommandTypes.add(late.type());
    }

    // ★ T10-h：participant 由**清单**注册、条数由清单长度数出来（曾把 `participant=1` 写死在日志里 ⇒ 将来加第二个会静默说谎）。
    //   ★ 2026-10-09：生产路径收敛到 **production-runtime** —— 经济/人口/actor 三片由
    //     PopulationEconomyTimeParticipant（EconomySettlement 的日结算）推进；class-first 路由已删除。
    //   ★ 旧注释（R4 为何 economy 与 social 合为一个参与者）仍成立：出生/死亡要同时看两侧，且"同一模块只能有一个写者"。
    List<TimeParticipant> participants =
        List.of(
            new UnitTimeParticipant(TerrainMovementCost.INSTANCE, config.mapId()),
            new SdTimeParticipant(config.mapId()),
            // ★ 唯一的经济—人口协调器 = production-runtime 单日入口（缺省单线程退化路径）。
            // ★ C5：历法与人口/经济同取一份 CalendarService 快照（生产路径必须由 CalendarService.load 注入）。
            new PopulationEconomyTimeParticipant(config.mapId()));
    for (TimeParticipant participant : participants) {
      coreSimos.register(participant);
    }

    // 写前跨模块守卫（A6，spec §九）：带国家 tag 的区域不可删。
    coreSimos.register(new RegionDeleteGuard());

    ResolverRegistry resolverRegistry = new ResolverRegistry();
    resolverRegistry.register(new MapResolver());
    resolverRegistry.register(new SocialResolver());
    resolverRegistry.register(new UnitResolver());
    resolverRegistry.register(new SdResolver());
    // ★ R2a：economy 自己的地址解析器（economy:<mapId>[:industry.<id> | :debt.<id> | :class.<i>.<s> |
    // :flow.<i>.<s>]）。
    resolverRegistry.register(new EconomyResolver());
    // ★ S1 阶段 2：actor 自己的地址解析器（actor:<mapId>[:actor.<KIND>.<id> | :holding.<key> |
    // :goods.<key>]）——注册它，`/api/resolve` 读口才认识第六个命名空间。
    resolverRegistry.register(new ActorResolver());
    // ★★ D1（2026-10-02 / D-012）：army 自己的地址解析器（army:combat.<id>）——注册它，
    //   `simos.state.resolve` / GUI `/api/resolve` 才认识第八个命名空间的交战记录主体
    //   （可见性由 ToolSupport.subjectVisible 按记录所在格判）。
    resolverRegistry.register(new ArmyResolver());

    FacetRegistry facetRegistry = new FacetRegistry();
    facetRegistry.register(new UnitsHereFacet());
    facetRegistry.register(new PopulationFacet());

    QueryService queryService = new QueryService(coreSimos, resolverRegistry, facetRegistry);

    // ★ M11′：LLM provider 配置落在 AgentLib 的 ConfigStore（<store>/agentlib/config.json），
    //   旧格式（<store>/llm-providers.json）只作一次性迁移来源；决策编排把 AdjudicatorRunner 接进壳。
    AgentLibLlmConfig llmConfig = AgentLibLlmConfig.open(config.storeDir());
    LlmProviderResolver llmProviderResolver = new LlmProviderResolver(llmConfig, config.storeDir());
    DecisionAdjudicationService decisionAdjudicationService =
        new DecisionAdjudicationService(coreSimos, llmProviderResolver);

    // ★ D5：三条决策渠道（spec §十三.3 / §四.3 撤销项）——声明各自可代表的 actor（当前世界里的决策人），最终写同一落点。
    //   ★ 决策人**不经 MCP**（用户裁定③）：旧 McpDecisionChannel 已删除，渠道回到 GUI / CLI / Http 三条。
    Supplier<Set<ActorId>> representableActors = () -> currentActorIds(coreSimos);
    List<DecisionChannel> decisionChannels =
        List.of(
            new GuiDecisionChannel(coreSimos, representableActors),
            new CliDecisionChannel(coreSimos, representableActors),
            new HttpDecisionChannel(coreSimos, representableActors));

    // 审批链（T6，spec §3.2 第 3 步；S5：无 Superior 判定，M5 无 LLM）。
    PendingApprovals pendingApprovals = new PendingApprovals();
    HttpApprovalChannel approvalChannel = new HttpApprovalChannel(pendingApprovals);
    ApprovalCoordinator approvalCoordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pendingApprovals), new ConfirmGate()),
            List.of(approvalChannel),
            pendingApprovals,
            APPROVAL_TIMEOUT,
            null);
    // ★★ 2026-09-24 用户裁定：「为啥这种 GM 级命令要额外审批？改成 MCP/GM Agent **无脑过**」。
    //    ⇒ **GM 面（MCP 口）另起一条链**：只挂 GmAutoApproveGate（直接批准），不再进「待批 → 人点」。
    //    决策人那条链（上面的 approvalCoordinator）**一个字不改**：出令仍要 GM 在「决策 → 审批」点头。
    //    ★ 为什么按链分而不是按请求分：两张面的 caller 桶都是 DEFAULT，从 ApprovalRequest 上分不开（见该类注）。
    ApprovalCoordinator gmApprovalCoordinator =
        new ApprovalCoordinator(
            List.of(new GmAutoApproveGate()),
            List.of(approvalChannel),
            pendingApprovals,
            APPROVAL_TIMEOUT,
            null);
    ToolCallAuthorizer toolAuthorizer =
        ToolCallAuthorizer.of(new ToolExecutionGuard(), approvalCoordinator);
    ToolCallAuthorizer gmToolAuthorizer =
        ToolCallAuthorizer.of(new ToolExecutionGuard(), gmApprovalCoordinator);

    // 端点先真的绑上端口，再 markUp 通道（可用性认"端口在监听"，spec §3.2 第 4 步）。
    // ★ 恒回环，**不**随 config.bindAddress() 变（AgentLib 无 host 形参；对外面是 GUI 的 /api/approvals 代理）。
    ApprovalHttpEndpoint approvalEndpoint =
        ApprovalHttpEndpoint.start(config.approvalPort(), pendingApprovals, approvalCoordinator);
    approvalChannel.markUp();

    // ★ T11C：决策人 agent 运行流的装配（三样在它之前就绪：带审批的 authorizer、provider 解析链、查询层）。
    //   ① 权限组走**同一个** authorizer（决策人的两条窄写是敏感工具 ⇒ 没有审批编排器就永远进不了工具体）；
    //   ② 工具面取**决策人桶**（与白名单同源：DecisionToolDefs.requireAll 对不上就当场炸）；
    //   ③ 会话落 <store> 下（与 simos.db 同层 ⇒ 跨进程重启沿用同一段会话）。
    // ★ Skill 库（2026-09-23）：**装配期建一次**，两个工具面与 Shell 字段共用同一个实例
    //   （外部 Markdown：仓库种子 config/skills + store 覆盖 <storeDir>/skills；读时按 mtime 热更）。
    SkillLibrary skillLibrary = SkillLibrary.open(config.storeDir());
    // ★ P3（2026-09-24）：渲染产出的 PNG 落 <store>/artifacts（内容寻址；同一张图只存一份）。
    //   渲染服务三面共用（决策人链路 / MCP 工具 / GUI 出图路由）——键 = revision + 参数指纹。
    ArtifactStore artifactStore = new ArtifactStore(config.storeDir().resolve("artifacts"));
    RenderService renderService = new RenderService(queryService, artifactStore);
    // ★ T8：GM 交互界面的数据源——GM 口每次工具执行的留痕（工具名 + 结果），经 /api/gm/tool-usage 只读导出；
    //   工具面补齐（2026-09-25）起它同时是 MCP 的 `simos.gm.tool-usage` 的数据源。★ **必须在两个工具源之前建**：
    //   两档的读工具装配都要求它非 null（GM-only 读工具虽会被决策人桶过滤，仍先被构造）。
    GmToolUsage gmToolUsage = new GmToolUsage();
    DecisionCallerFactory decisionCallerFactory = DecisionCallerFactory.defaults(toolAuthorizer);
    ToolRegistry decisionTools = new ToolRegistry();
    decisionTools.registerAll(
        new SimosToolSource(
                coreSimos,
                calendarService,
                queryService,
                config.mcpInitiator(),
                config.mapId(),
                WORLDGEN_CONFIG_FILE,
                catalogCommandTypes,
                directiveCommandTypes,
                skillLibrary,
                renderService,
                gmToolUsage,
                llmConfig,
                SimosToolSource.Role.DECISION_AGENT)
            .listTools());
    // ★ 决策人桶**不带**触发工具（决策人不触发自己，那是自环）⇒ 这里用不带运行流的那条构造器。
    SqliteConversationStore decisionConversations =
        SqliteConversationStore.open(
            config.storeDir().resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME));
    DecisionAgentService decisionAgentService =
        new DecisionAgentService(
            coreSimos,
            decisionLlmClients == null
                // ★★ P4：生产路径给的是「客户端 + 能力」一个值（{@link LlmProviderResolver#providerFor}）——
                //   客户端**带工件解析器**（图在这个实例里出站），能力位一起带出来（图发不发由它定）。
                ? providerId -> llmProviderResolver.providerFor(providerId, artifactStore)
                : decisionLlmClients,
            decisionCallerFactory,
            decisionTools,
            decisionConversations,
            config.mapId(),
            DecisionAgentRunner.DEFAULT_MAX_LLM_CALLS,
            // ★ P4 开场快照：**开关关着就一个字节都不装**（传 NONE ⇒ 运行流那边分得出"没开"与"开了没出图"）。
            config.openingSnapshot()
                ? new NationOpeningSnapshot(renderService)
                : DecisionAgentRunner.OpeningSnapshot.NONE);

    // 工具集（spec §2.1）：MCP 口 = **GM 组** = 读工具 + 通用写 + 全部窄写（条数以工具面为准）；
    //   GM 组的权限集是显式构造的那一份（gmCaller 的 resourceScopes 逐命名空间表态），不再是 unrestricted 的空资源图。
    SimosToolSource toolSource =
        new SimosToolSource(
            coreSimos,
            calendarService,
            queryService,
            config.mcpInitiator(),
            config.mapId(),
            WORLDGEN_CONFIG_FILE,
            catalogCommandTypes,
            directiveCommandTypes,
            skillLibrary,
            renderService,
            gmToolUsage,
            llmConfig,
            commandTargets,
            SimosToolSource.Role.GM,
            decisionAgentService,
            pendingApprovals,
            approvalCoordinator);
    // ★ T8：GM 交互界面的数据源（`gmToolUsage` 已在上方、两个工具源之前建好）——只包 GM 组的源 ⇒ 记录的就是
    //   "GM MCP 的工具使用"（spec §七.4 C22）。
    ToolRegistry toolRegistry = new ToolRegistry();
    McpSourceBridge toolBridge =
        McpSourceBridge.bind(RecordingToolSource.record(toolSource, gmToolUsage), toolRegistry);

    // MCP 服务（T7，spec §3.2 第 6 步）：**唯一的**口 = GM 组；authorizer 是带审批的那个。
    // ★ 决策人**不开 MCP**（spec §2.2 的 C3：caller 在 startHttp 时定死，一个端口一份权限集 ⇒ 表达不了"每个决策人一份权限"；
    //   而范围随世界状态变 ⇒ 进程内现算是唯一可行路径）。
    AgentToMcpServer mcpServer = null;
    boolean mcpUp = false;
    try {
      mcpServer =
          AgentToMcpServer.startHttp(
              config.bindAddress(),
              config.mcpPort(),
              config.mcpPath(),
              toolRegistry,
              MCP_SERVER_NAME,
              MCP_SERVER_VERSION,
              gmCaller(),
              gmToolAuthorizer,
              artifactStore);
      mcpUp = true;
    } finally {
      if (!mcpUp) {
        // 口绑定失败：桥与审批端点/通道都不能留着占端口（GUI 尚未起，spec §3.3 里它排第一）。
        toolBridge.close();
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    // 走到这里口已监听（绑定失败在上面的 finally 里已收尾并抛出）；显式断言以钉死该后置条件。
    Objects.requireNonNull(mcpServer, "mcpServer");

    // GUI（T8，spec §3.2 第 7 步）：审批面 base URL 指向刚绑定的端点，5711 的 /api/approvals 是它的透传代理。
    GuiServer guiServer =
        new GuiServer(
            queryService,
            coreSimos,
            config.mapId(),
            "http://127.0.0.1:" + approvalEndpoint.boundPort(),
            gmToolUsage,
            llmConfig,
            decisionAdjudicationService,
            // ★ T11C 的运行流（不是判决定编）：/api/sd/run-decision 落地时就是拿它跑那一轮——与 GM 侧的
            //   sd.RunDecision 窄工具**同一个实例**（同一个会话库、同一条 provider 解析链）。
            decisionAgentService,
            // ★ C6a/D-020：GUI 三条读口与 MCP 单位/人口读口共用启动期 CalendarService.load 的**同一实例**——
            //   GM 改历法/分带后日期与季节当场整体切换，不需重启。
            calendarService);
    boolean guiUp = false;
    try {
      guiServer.start(config.bindAddress(), config.guiPort());
      guiUp = true;
    } finally {
      if (!guiUp) {
        // GUI 绑定失败：MCP 口与审批端点/通道、工具桥不能留着占端口（spec §3.3 里 GUI 排第一，此处它还没起来）。
        mcpServer.close();
        toolBridge.close();
        approvalEndpoint.close();
        approvalChannel.close();
      }
    }

    LOG.info(
        "Shell 装配完成: store={} checkpointInterval={} codec={} handler={} participant={}"
            + " resolver={} facet={} tool={} mapId={} bindAddress={} mcpPort={} guiPort={}"
            + " approvalPort={}",
        config.storeDir(),
        config.checkpointInterval(),
        codecs.size(),
        handlers.size(),
        participants.size(),
        resolverRegistry.namespaces().size(),
        facetRegistry.facetNames().size(),
        toolRegistry.size(),
        config.mapId(),
        config.bindAddress(),
        mcpServer.boundPort(),
        guiServer.boundPort(),
        approvalEndpoint.boundPort());
    return new Shell(
        config,
        coreSimos,
        calendarService,
        queryService,
        toolRegistry,
        toolBridge,
        mcpServer,
        guiServer,
        pendingApprovals,
        approvalChannel,
        approvalCoordinator,
        approvalEndpoint,
        toolAuthorizer,
        gmToolAuthorizer,
        codecs.size(),
        new SdCommandDrain(coreSimos),
        decisionChannels,
        catalogCommandTypes,
        directiveCommandTypes,
        commandTargets,
        skillLibrary,
        renderService,
        artifactStore,
        llmConfig,
        gmToolUsage,
        decisionAdjudicationService,
        decisionAgentService,
        decisionConversations);
  }

  /** 当前世界里的决策人（作为渠道可代表的 actor；空库 ⇒ 空集）。 */
  private static Set<ActorId> currentActorIds(CoreSimos core) {
    BranchId main = new BranchId("main");
    Optional<RevisionId> head = core.head(main);
    if (head.isEmpty()) {
      return Set.of();
    }
    SimulationState state = core.replay(new StateRef(main, head.get()));
    Snapshot slice = state.module("sd").orElse(null);
    if (!(slice instanceof SdSnapshot sd)) {
      return Set.of();
    }
    Set<ActorId> actors = new LinkedHashSet<>();
    for (DecisionMakerId id : sd.state().decisionMakers().keySet()) {
      actors.add(new ActorId(id.value()));
    }
    return actors;
  }

  /**
   * 工件库（P3）：渲染产出的 PNG（内容寻址）——GUI 出图路由与测试都从这里取字节。
   *
   * <p>★ 返回类型是 **AgentLib 的只读面** {@link ToolAssetResolver}（只有 {@code resolve}），不是 {@code
   * ArtifactStore} 本体：写入口（{@code putPng}）只归 {@code RenderService} 的装配链，外部拿不到它 ⇔ 也就无从绕过内容寻址。 这同时消掉了
   * SpotBugs 的 EI_EXPOSE_REP（对外暴露可变内部表示的旧形态）。
   */
  // ★ SpotBugs 的 EI_EXPOSE_REP 按**字段类型**判（字段仍是 ArtifactStore，本类内部要用它的写面），
  //   而对外暴露的**声明类型**已经是只读接口 ⇒ 调用方无致变途径。豁免写在这一处。
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "对外返回只读接口 ToolAssetResolver（无致变方法）；ArtifactStore 的写面仅本类内部使用")
  public ToolAssetResolver artifactStore() {
    return artifactStore;
  }

  /** 渲染服务（P3）：三个消费面共用。 */
  public RenderService renderService() {
    return renderService;
  }

  /** 决策提交渠道（D5）：测试与运维读回装配的四条渠道。 */
  public List<DecisionChannel> decisionChannels() {
    return decisionChannels;
  }

  /**
   * 按角色重建工具面（D6/§2.1/§四.3）：{@link SimosToolSource.Role#GM} = 运行时 MCP 口（读 + 通用写 + 全部窄写）； {@link
   * SimosToolSource.Role#DECISION_AGENT} = 决策人组（读 + 两条决策窄写）——决策人**不走 MCP**，它那条路的权限由 {@code
   * DecisionCallerFactory} 现算的权限组承担。
   */
  public List<AgentTool> toolsFor(SimosToolSource.Role role) {
    // ★ P7b：审批控制面两条工具**只在 GM 桶**——决策人角色传 null（那两条根本到不了决策人面；
    //   这里传 null 让"决策人不接线"成为明确装配，而不是靠状态兜底）。
    boolean gm = role == SimosToolSource.Role.GM;
    return new SimosToolSource(
            coreSimos,
            calendarService,
            queryService,
            config.mcpInitiator(),
            config.mapId(),
            WORLDGEN_CONFIG_FILE,
            commandTypes,
            directiveCommandTypes,
            skillLibrary,
            renderService,
            gmToolUsage,
            llmConfig,
            commandTargets,
            role,
            decisionAgentService,
            gm ? pendingApprovals : null,
            gm ? approvalCoordinator : null)
        .listTools();
  }

  /** GUI 服务器实际绑定端口（{@code guiPort=0} 时由 OS 分配；spec §3.1 的读回口径，测试用）。 */
  public int boundGuiPort() {
    return guiServer.boundPort();
  }

  /**
   * GUI 服务器实际绑定主机（M10）：回显 {@link ShellConfig#bindAddress()} 的**生效值**（{@code 0.0.0.0} 时 JDK 读回通配形态，
   * 见 {@link java.net.InetAddress#isAnyLocalAddress()}）。运维与测试据此确认绑定面。
   */
  public String boundGuiHost() {
    return guiServer.boundHost();
  }

  /** MCP 服务实际绑定端口（{@code mcpPort=0} 时由 OS 分配；spec §3.1 的读回口径，经 AgentLib {@code boundPort()}）。 */
  public int boundMcpPort() {
    return mcpServer.boundPort();
  }

  /**
   * **GM 组的调用上下文**（J1，spec §2.1）：MCP 口上每次工具调用的调用者（装配期定死；spec §3.2 的 caller，S/P2-F 无 per-session
   * 身份：caller 在 {@code startHttp} 时定死，一个端口一份权限集）。
   *
   * <p>★ **权限边界在"组"、不在"端口"**（本阶段的口径取代）：端口只决定"谁连得上"，能不能碰某个资源由 {@link #gmPermissionSet()}
   * 那份**显式权限集**说了算——决策人组不再靠"另一个端口"表达，而是靠进程内现算的权限集（spec §二）。
   *
   * <p>★ **桶取 {@link AccessToken#DEFAULT} 而非 spec §3.2 字面的 {@code GUEST}**——这是装配期实测的**取代说明**：三条写工具
   * （{@code command.submit}/{@code advance}/{@code fork}）的 {@code ToolSpec} 是 {@code
   * ToolSpec.level(DEFAULT, sensitive=true, destructive=false)}，而 {@code PermissionChecker}
   * 在身份级别不足时**硬拒** （{@code PERMISSION_DENIED}），根本进不了审批闸。{@code GUEST} 桶下三条通用写工具 （{@code
   * command.submit}/{@code advance}/{@code fork}）全部不可达，MCP 只能读、不能写 ⇒ 与 S3/S4 的工具面设计矛盾。{@code
   * DEFAULT} 是**满足该工具面全部工具的最小桶**（读工具只要求 {@code GUEST}）。
   *
   * <p>★ **放行 = 免审批（2026-09-24 用户裁定，取代此前的"放行 ≠ 免审批"）**：敏感工具的 {@code ToolGate.Ask} 在 **GM 面**由
   * {@link GmAutoApproveGate} **直接批准**（不再进「待批 → 人点」）；决策人那条链**不变**， 其 {@code Ask} 仍落 {@code
   * ConfirmGate}。
   *
   * <p>★ 身份取 {@link AgentIdentity#external()}（{@code external-mcp} 实例 + {@code FULL}
   * 档）：外部客户端不是本进程派生的 Agent，审批面据它认得出"这不是我派的下级"（AgentLib 契约）。注意 AgentLib 的审批 {@code callerKey}
   * 取的是**桶名**（{@code DEFAULT}），不是本 identity 的实例 id（T6 裁定 63 已实测）。
   *
   * <p>★ **包内可见**：这是装配自检与用例的读回口径，**不是**对外 API——对外面只有端口。
   */
  static ToolContext gmCaller() {
    return ToolContext.of(AccessToken.DEFAULT, gmPermissionSet(), AgentIdentity.external());
  }

  /**
   * GM 权限组（J1，spec §2.1）：**显式构造**的权限集 —— 全工具白名单 + {@code sensitiveAllowed} + {@code
   * destructiveAllowed} + **四个命名空间各自 {@code unlimited()}**。
   *
   * <p>★ **它取代 {@code AgentPermissionSet.unrestricted(DEFAULT)}**（spec §1.1 记的根因形态）：那条路走**六参兼容构造**
   * ⇒ {@code resourceScopes} 为 null ⇒ 规范化成**空图** ⇒ 资源判定走"调用者未表态"分支 ⇒ **每一次 {@code require} 都恒真**
   * （断言在跑，但永远通过）。空图与 {@code unlimited()} 在**当前工具面**上行为恰好相同（工具声明的三命名空间缺省策略本就放行），
   * 差别是**表过态**：可达面从此是**显式数据**，而不是"缺省恰好放行"。
   *
   * <p>★ **{@code null} ≠ deny-all**（spec §5.2 第 3 条）：本层是限制层，"没表态"回落下一层（工具自己的 {@code
   * ResourceManifest} 缺省）；想表达"够不着"必须显式写 {@code none()}——两条方向相反，配错即静默放宽。
   *
   * <p>★ **身份仍是 {@link AgentIdentity#external()}**（用户裁定①）：它是**审批面语义**（"这不是本进程派生的下级"），
   * 与权限**级别**正交。GM 若是经 MCP 连入的外部 agent，这个身份是对的。
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
                    ToolSupport.SD_NAMESPACE, ResourceScope.unlimited(),
                    // ★ R2a：第六个命名空间（economy:<q>_<r>）——不在这里表态，GM 面就落到"未表态"分支。
                    ToolSupport.ECONOMY_NAMESPACE, ResourceScope.unlimited(),
                    // ★ S1 阶段 2：第七个命名空间（actor:<mapId>[:…]）——同款，不表态就回落"未表态"分支。
                    ToolSupport.ACTOR_NAMESPACE, ResourceScope.unlimited(),
                    // ★★ D1（2026-10-02 / D-012）：第八个命名空间（army:combat.<id>）——写侧表态（GM 的
                    //   army.RecordCombat 窄工具与通用提交）；读侧的可见性按记录所在格判，不落在这个命名空间上。
                    ToolSupport.ARMY_NAMESPACE, ResourceScope.unlimited())))
        .build();
  }

  /** 审批端点实际绑定端口（{@code approvalPort=0} 时由 OS 分配；spec §3.1 的读回口径）。 */
  public int boundApprovalPort() {
    return approvalEndpoint.boundPort();
  }

  /**
   * 审批登记表（T6）：T7 的 MCP 调用触发审批时，测试/调试面经此读待裁决项并作答。
   *
   * <p>★ SpotBugs 未判它 {@code EI_EXPOSE_REP}（实测：加了抑制反被 {@code US_USELESS_SUPPRESSION_ON_METHOD}
   * 判红）——它本就是给人面 与测试的进程内共享件。
   */
  public PendingApprovals pendingApprovals() {
    return pendingApprovals;
  }

  /**
   * 工具调用唯一入口（T6，spec §7.1/§7.2）：**带审批**（非 {@code standard()}）。T7 把它交给 {@code
   * AgentToMcpServer.startHttp}；测试用它执行写工具以验证 R3（未审批的写调用必须被拒且不留 revision）。
   */
  public ToolCallAuthorizer toolAuthorizer() {
    return toolAuthorizer;
  }

  /** ★ GM 面（MCP 口）的 authorizer：审批链上挂 {@code GmAutoApproveGate}（无脑过）。 */
  public ToolCallAuthorizer gmToolAuthorizer() {
    return gmToolAuthorizer;
  }

  /** 查询门面（spec §5.1）：GUI（T8）与工具集（T5）经此读状态、解析地址、取 facet。**只读**——写面仍只有 {@link CoreSimos#submit}。 */
  public QueryService queryService() {
    return queryService;
  }

  /** 工具注册表（spec §7.1；T5/T4）：现有口全部工具（通用写 + GM 窄写 + 读）的活清单，T7 交给 {@code AgentToMcpServer}。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "spec §3.2/§7.2 要求把注册表交给 MCP 服务（T7）；它不是内部表示而是本壳的产物本身，与 coreSimos() 同法")
  public ToolRegistry toolRegistry() {
    return toolRegistry;
  }

  /**
   * 底层门面（提交 / 重放 / 只读分支面）。★ 唯一写入口仍是 {@link CoreSimos#submit}。
   *
   * <p>★ **{@code EI_EXPOSE_REP} 是有意豁免（M5 T1 门禁实测）**：spec §3.2 要求组合根对外交出 {@code
   * CoreSimos}；它不是"内部表示"而是本壳的产物本身。SpotBugs 判它可变故报 {@code EI_EXPOSE_REP}，用
   * {@code @SuppressFBWarnings} 精确豁免在**这一个方法**上（注解依赖 provided、不进产物；与 AgentLibMosire/BrainMosire
   * 同法）。护栏不因此松：铁律 2 仍由"app 源码无 store/timeline 写面"的扫描 + 行为面守（R1，T8 落地）。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "spec §3.2 要求对外交出 CoreSimos 引用；唯一写入口仍是 CoreSimos.submit，豁免只作用于本方法")
  public CoreSimos coreSimos() {
    return coreSimos;
  }

  /** 生效配置（原样回显，不重算）。 */
  public ShellConfig config() {
    return config;
  }

  /** 已注册的模块 codec 个数（map/social/unit/sd = 4）。{@code ShellMain} 用它打印装配实况。 */
  public int registeredModuleCount() {
    return registeredModuleCount;
  }

  /**
   * 跨模块效果落点（C5，spec §五.3）：{@code AdvanceTime} 提交成功后 drain（本方法就是"提交后调 drain"的兑现）。
   *
   * <p>★ **只对 {@code AdvanceTime} + 提交成功**触发；被拒/冲突不 drain（没有新 head 可读）。
   */
  public List<CommandResult> advanceAndDrain(AdvanceTime command) {
    Objects.requireNonNull(command, "command");
    CommandResult result = coreSimos.submit(command);
    if (result instanceof CommandResult.Committed) {
      return sdCommandDrain.drainAfterAdvance(command.branch());
    }
    return List.of();
  }

  /** 跨模块效果落点（C5）：测试/调试面可直接调它（不经 {@code advanceAndDrain}）。 */
  public SdCommandDrain sdCommandDrain() {
    return sdCommandDrain;
  }

  /** LLM provider 配置（M11′ 对接版）：AgentLib 的 {@code ConfigStore} 门面（测试/运维读回装配实况）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "本壳要把配置门面交给测试/运维读回装配实况；它不是内部表示而是本壳的产物，与 toolRegistry() 同法")
  public AgentLibLlmConfig llmConfig() {
    return llmConfig;
  }

  /** 决策编排（T3）：测试/调试面可直接调它（不经 GUI 端点）。 */
  public DecisionAdjudicationService decisionAdjudicationService() {
    return decisionAdjudicationService;
  }

  /**
   * 决策人 agent 运行流（T11C）：测试/调试面读回装配实况（{@code sd.RunDecision} 那条路内部就是它）。
   *
   * <p>★★ **本方法原先刻意不加抑制**（理由写在"它是本壳的产物、不是内部表示"上），但 2026-09-23 的门禁把它报了 （{@code
   * EI_EXPOSE_REP}，Medium）——而**同类的** {@link #llmConfig()} 一直带着同一条抑制。按"门禁是判据"处理： 在这里补上与 {@code
   * llmConfig()} 逐字同源的抑制，而不是改 API 形状（把运行流包一层只读壳会改变测试/调试的用法， 换不到任何真实保护——**它本来就是给测试与调试直接调的那个对象**）。
   *
   * <p>★ 诚实记一笔：**为什么会"同一份代码这次才报"没有查清**（本仓纪律形态 6 有先例：分析器的判定不是被分析文件的
   * 纯函数——它随**类集**变）。本改动确实新增了同类引用（{@code DecisionRunRegistry}），但**没有证据**证明因果。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "本壳要把决策人运行流交给测试/调试直接调；它不是内部表示而是本壳的产物，与 llmConfig()/toolRegistry() 同法")
  public DecisionAgentService decisionAgentService() {
    return decisionAgentService;
  }

  /**
   * 关闭：GUI → MCP → 工具桥 → 审批端点 → 审批通道 → {@link CoreSimos}（spec §3.3 的次序）。幂等。
   *
   * <p>★ spec §3.3 的完整次序是 GUI → MCP → 审批端点 → 审批通道 → CoreSimos；工具桥是 app 内的注册表卸载，插在 MCP 与审批之间不改变
   * 端口/线程的释放次序（MCP 先 {@code closeGracefully} 再停 server 由 AgentLib 契约实现）。★ **决策人会话库**（T11C）是 app 自己的
   * 存储（不占端口、不参与 spec §3.3 的次序），与 CoreSimos 同批关（都在最后）。
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    guiServer.close();
    mcpServer.close();
    toolBridge.close();
    approvalEndpoint.close();
    approvalChannel.close();
    decisionConversations.close();
    coreSimos.close();
  }
}
