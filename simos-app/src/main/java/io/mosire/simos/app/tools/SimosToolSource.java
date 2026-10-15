package io.mosire.simos.app.tools;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.docs.DocsCapability;
import io.mosire.docs.DocsTools;
import io.mosire.simos.app.access.AppDecisionMakerAffiliations;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.decision.ProposalCatalog;
import io.mosire.simos.app.llm.AgentLibLlmConfig;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.army.AppArmyToolPerspective;
import io.mosire.simos.app.tools.economy.AppEconomyToolPerspective;
import io.mosire.simos.app.tools.economy.AppEconomyToolViews;
import io.mosire.simos.app.tools.ga.AppGaDecisionHost;
import io.mosire.simos.app.tools.ga.AppGaEffectExecution;
import io.mosire.simos.app.tools.ga.AppGaProposalHost;
import io.mosire.simos.app.tools.ga.AppGaToolIdentity;
import io.mosire.simos.app.tools.ga.AppGaToolQueries;
import io.mosire.simos.app.tools.ga.AppGaToolViews;
import io.mosire.simos.app.tools.gov.AppGovToolCommandTypes;
import io.mosire.simos.app.tools.gov.AppGovToolEconomy;
import io.mosire.simos.app.tools.gov.AppGovToolPerspective;
import io.mosire.simos.app.tools.gov.AppGovToolViews;
import io.mosire.simos.app.tools.map.AppMapPathPort;
import io.mosire.simos.app.tools.map.AppMapRenderPort;
import io.mosire.simos.app.tools.map.AppMapToolPerspective;
import io.mosire.simos.app.tools.map.AppMapToolViews;
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CalendarInfoTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.LlmProvidersTool;
import io.mosire.simos.app.tools.read.SocialHouseholdsTool;
import io.mosire.simos.app.tools.read.StateFacetsTool;
import io.mosire.simos.app.tools.read.StateResolveTool;
import io.mosire.simos.app.tools.read.TimelineRevisionsTool;
import io.mosire.simos.app.tools.social.AppSocialToolPerspective;
import io.mosire.simos.app.tools.social.AppSocialToolViews;
import io.mosire.simos.app.tools.social.AppSocialUnitPort;
import io.mosire.simos.app.tools.write.ActorAdjustAccountsTool;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.app.tools.write.AdvanceTool;
import io.mosire.simos.app.tools.write.AppArmyManpowerPort;
import io.mosire.simos.app.tools.write.AppArmyToolViews;
import io.mosire.simos.app.tools.write.AppGovToolManpower;
import io.mosire.simos.app.tools.write.AppUnitToolAllocations;
import io.mosire.simos.app.tools.write.AppUnitToolCommandTypes;
import io.mosire.simos.app.tools.write.AppUnitToolPerspective;
import io.mosire.simos.app.tools.write.AppUnitToolViews;
import io.mosire.simos.app.tools.write.CalendarConfigureTool;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.app.tools.write.ForkTool;
import io.mosire.simos.app.tools.write.RegionClearDataTool;
import io.mosire.simos.app.tools.write.RegionSeedTool;
import io.mosire.simos.app.tools.write.VoidAdjudicationTool;
import io.mosire.simos.app.tools.write.WorldgenInitializeTool;
import io.mosire.simos.army.tools.ArmyManpowerPort;
import io.mosire.simos.army.tools.ArmyToolPerspective;
import io.mosire.simos.army.tools.ArmyToolViews;
import io.mosire.simos.army.tools.read.ArmyCombatTool;
import io.mosire.simos.army.tools.read.ArmyCombatsTool;
import io.mosire.simos.army.tools.write.ArmyRecordCombatTool;
import io.mosire.simos.army.tools.write.AssignArmyGovTool;
import io.mosire.simos.army.tools.write.FormatUnitTool;
import io.mosire.simos.army.tools.write.GmArmyPayPolicyTool;
import io.mosire.simos.army.tools.write.ResolveCombatTool;
import io.mosire.simos.army.tools.write.StartCombatTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.tools.EconomyToolPerspective;
import io.mosire.simos.economy.tools.EconomyToolViews;
import io.mosire.simos.economy.tools.read.EconomyHexTool;
import io.mosire.simos.economy.tools.read.EconomyOwnershipTool;
import io.mosire.simos.economy.tools.write.EconomyAdjustTool;
import io.mosire.simos.economy.tools.write.EconomyDefineMarketZoneTool;
import io.mosire.simos.economy.tools.write.EconomyMergeMarketZonesTool;
import io.mosire.simos.economy.tools.write.EconomyReassignZoneHexesTool;
import io.mosire.simos.economy.tools.write.EconomySetCommodityFreightTool;
import io.mosire.simos.economy.tools.write.EconomySetEconomyPolicyTool;
import io.mosire.simos.economy.tools.write.EconomySetGovernmentMintScaleTool;
import io.mosire.simos.economy.tools.write.EconomySetMarketZoneLoanRateAlphaTool;
import io.mosire.simos.economy.tools.write.EconomySetMarketZonePriceAlphaTool;
import io.mosire.simos.economy.tools.write.EconomyUpsertGovUnitTool;
import io.mosire.simos.economy.tools.write.EconomyUpsertIndustryTool;
import io.mosire.simos.economy.tools.write.GmGovMarketMandateTool;
import io.mosire.simos.economy.tools.write.GmPeriodicAdjustmentTool;
import io.mosire.simos.ga.decision.DecisionToolCaller;
import io.mosire.simos.ga.spi.SimosToolAdapter;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaDecisionHost;
import io.mosire.simos.ga.tools.GaEffectExecution;
import io.mosire.simos.ga.tools.GaProposalHost;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolQueries;
import io.mosire.simos.ga.tools.GaToolSource;
import io.mosire.simos.ga.tools.GaToolViews;
import io.mosire.simos.ga.tools.GmToolUsage;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.ga.tools.ToolRoleAudit;
import io.mosire.simos.ga.tools.read.DecisionDocsTool;
import io.mosire.simos.ga.tools.read.DecisionMakerTool;
import io.mosire.simos.ga.tools.read.DecisionMakersTool;
import io.mosire.simos.ga.tools.read.DecisionResultsTool;
import io.mosire.simos.ga.tools.read.GaMessageInboxTool;
import io.mosire.simos.ga.tools.read.GmApprovalsTool;
import io.mosire.simos.ga.tools.read.GmInboxTool;
import io.mosire.simos.ga.tools.read.GmPacketTool;
import io.mosire.simos.ga.tools.read.GmPacketsTool;
import io.mosire.simos.ga.tools.read.GmRoundTool;
import io.mosire.simos.ga.tools.read.GmToolUsageTool;
import io.mosire.simos.ga.tools.read.SdDiplomacyTool;
import io.mosire.simos.ga.tools.read.SdDiplomaticEventsTool;
import io.mosire.simos.ga.tools.read.SdDirectivesTool;
import io.mosire.simos.ga.tools.read.SdVerdictsTool;
import io.mosire.simos.ga.tools.write.AdjudicationAckTool;
import io.mosire.simos.ga.tools.write.ConfirmPacketTool;
import io.mosire.simos.ga.tools.write.DropCallTool;
import io.mosire.simos.ga.tools.write.GaMessageSendTool;
import io.mosire.simos.ga.tools.write.GmActAsTool;
import io.mosire.simos.ga.tools.write.GmApproveTool;
import io.mosire.simos.ga.tools.write.GmDecidePacketTool;
import io.mosire.simos.ga.tools.write.GmMergedPlanApplyTool;
import io.mosire.simos.ga.tools.write.GmMergedPlanUpsertTool;
import io.mosire.simos.ga.tools.write.GmMessageSendTool;
import io.mosire.simos.ga.tools.write.GmPacketExecuteTool;
import io.mosire.simos.ga.tools.write.IssueDirectiveTool;
import io.mosire.simos.ga.tools.write.MyPacketTool;
import io.mosire.simos.ga.tools.write.PacketIntentTool;
import io.mosire.simos.ga.tools.write.ProposeCallTool;
import io.mosire.simos.ga.tools.write.RecordDiplomaticEventTool;
import io.mosire.simos.ga.tools.write.RejectDirectiveTool;
import io.mosire.simos.ga.tools.write.ReopenPacketTool;
import io.mosire.simos.ga.tools.write.ResetDecisionMakerConversationTool;
import io.mosire.simos.ga.tools.write.ReviseCallTool;
import io.mosire.simos.ga.tools.write.RunDecisionMakersTool;
import io.mosire.simos.ga.tools.write.RunDecisionTool;
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
import io.mosire.simos.ga.tools.write.SetDecisionMakerAccessTool;
import io.mosire.simos.ga.tools.write.SetDecisionMakerPeerTool;
import io.mosire.simos.ga.tools.write.SetDiplomaticRelationTool;
import io.mosire.simos.ga.tools.write.StartDecisionTool;
import io.mosire.simos.ga.tools.write.SubmitVerdictTool;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.gov.tools.read.GovInfoTool;
import io.mosire.simos.gov.tools.read.ProvinceDivideTool;
import io.mosire.simos.gov.tools.write.GovAbsorbUnitTool;
import io.mosire.simos.gov.tools.write.GovApplyStaffingTool;
import io.mosire.simos.gov.tools.write.GovAssignPostsTool;
import io.mosire.simos.gov.tools.write.GovCreateOfficeTool;
import io.mosire.simos.gov.tools.write.GovDefineCurrencyTool;
import io.mosire.simos.gov.tools.write.GovDismissTool;
import io.mosire.simos.gov.tools.write.GovDispatchTeamTool;
import io.mosire.simos.gov.tools.write.GovExpandHouseholdTool;
import io.mosire.simos.gov.tools.write.GovIssueMoneyTool;
import io.mosire.simos.gov.tools.write.GovOpenPostsToMarketTool;
import io.mosire.simos.gov.tools.write.GovPayTool;
import io.mosire.simos.gov.tools.write.GovRecruitTool;
import io.mosire.simos.gov.tools.write.GovRemitTool;
import io.mosire.simos.gov.tools.write.GovRenameCurrencyTool;
import io.mosire.simos.gov.tools.write.GovRetireStaffTool;
import io.mosire.simos.gov.tools.write.GovSelectExamineesTool;
import io.mosire.simos.gov.tools.write.GovSetBudgetPolicyTool;
import io.mosire.simos.gov.tools.write.GovSetEstablishmentTool;
import io.mosire.simos.gov.tools.write.GovSetFxRateTool;
import io.mosire.simos.gov.tools.write.GovTransferTreasuryTool;
import io.mosire.simos.gov.tools.write.ProvinceApplyTool;
import io.mosire.simos.gov.tools.write.ProvinceAssignCitiesTool;
import io.mosire.simos.gov.tools.write.RegionClearStructuresTool;
import io.mosire.simos.map.tools.MapPathPort;
import io.mosire.simos.map.tools.MapRenderPort;
import io.mosire.simos.map.tools.MapToolPerspective;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.map.tools.read.MapBlockTool;
import io.mosire.simos.map.tools.read.MapHexTool;
import io.mosire.simos.map.tools.read.MapOverlapsTool;
import io.mosire.simos.map.tools.read.MapOverviewTool;
import io.mosire.simos.map.tools.read.MapPathTool;
import io.mosire.simos.map.tools.read.MapRegionTool;
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
import io.mosire.simos.social.tools.SocialToolPerspective;
import io.mosire.simos.social.tools.SocialToolViews;
import io.mosire.simos.social.tools.SocialUnitPort;
import io.mosire.simos.social.tools.read.PopulationTool;
import io.mosire.simos.social.tools.write.GmAdjustPopulationTool;
import io.mosire.simos.social.tools.write.GmVitalRatesTool;
import io.mosire.simos.social.tools.write.SocialDemandTool;
import io.mosire.simos.social.tools.write.SocialHouseholdCreateTool;
import io.mosire.simos.social.tools.write.SocialHouseholdMembersTool;
import io.mosire.simos.social.tools.write.SocialHouseholdMoveTool;
import io.mosire.simos.social.tools.write.SocialHouseholdRatesTool;
import io.mosire.simos.social.tools.write.SocialLaborTool;
import io.mosire.simos.social.tools.write.SocialUpdateCityTool;
import io.mosire.simos.social.tools.write.UnitAssignHouseholdTool;
import io.mosire.simos.social.tools.write.UnitDetachHouseholdTool;
import io.mosire.simos.unit.tools.UnitToolAllocations;
import io.mosire.simos.unit.tools.UnitToolCommandTypes;
import io.mosire.simos.unit.tools.UnitToolPerspective;
import io.mosire.simos.unit.tools.UnitToolViews;
import io.mosire.simos.unit.tools.read.UnitGetTool;
import io.mosire.simos.unit.tools.read.UnitListTool;
import io.mosire.simos.unit.tools.write.LevyRegionTool;
import io.mosire.simos.unit.tools.write.RaiseUnitTool;
import io.mosire.simos.unit.tools.write.SpawnArmyTool;
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
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.CapabilityCatalogs;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.MountName;
import io.mosire.workspace.Origin;
import io.mosire.workspace.Resolvers;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceException;
import io.mosire.workspace.WorkspacePermissions;
import io.mosire.workspace.WorkspaceRegistry;
import io.mosire.workspace.WorkspaceTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Simos 工具供给源（M5 T5，spec §7.1）：**读工具各桶共享 + 各桶自己的写面**（{@link Role}）。
 *
 * <p>★ **它是工具清单的唯一出处**：{@code Shell} 建源后经 {@code McpSourceBridge.bind} 把这份快照同步进 {@link
 * io.mosire.agentlib.tool.ToolRegistry}，T7 再把该注册表交给 {@code
 * AgentToMcpServer}。源本身**不向任何全局状态注册**（AgentLib 的 {@code ToolSource} 契约）。
 *
 * <p>★ **{@code onChange} 是 no-op**：本源的集合是构造期定死的静态集（没有插件式的动态增删口子），故订阅即时成立、无需变更通知。若将来工具集
 * 变成动态的，替换这里的空实现即可（契约要求返回可关闭句柄）。
 *
 * <p>★ **写工具的身份与资源**：{@code initiator} 由 {@code Shell} 从 {@link
 * io.mosire.simos.app.ShellConfig#mcpInitiator()} 注入（spec §九，R4 的断言对象）；{@code mapId} 用于 hex 工具的
 * canonical 地址与资源断言（{@code GameMap} 无 id，见 M2/M3 挂起项）。
 */
public final class SimosToolSource implements ToolSource {

  /** 供给源标识（同一运行时内唯一；审计与按源卸载依赖它）。★ 值住在契约实现 {@link GaToolSource#ID}（B2b 迁入 ga）。 */
  public static final String ID = GaToolSource.ID;

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // ★★ 2026-10-14 AgentLib Docs/Workspace 接入（设计书 §4.2/§4.3，指南 §3/§4）：外来工具面的
  //    「谁能用 + 要不要审批」两件事**只在装配处**声明（契约层的 SimosToolAdapter 对文档零知识）。
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>外来 Docs/Workspace 工具面的<b>进程内唯一</b>持有者</b>（设计书 §4.1/§4.2）。
   *
   * <p>★ <b>为什么"唯一"是硬要求</b>：两个工具面是<b>两个</b> {@code SimosToolSource} 实例（{@code Shell} 里决策人桶与 GM
   * 桶各建一次；{@code Shell#toolsFor} 还会再建）——而外来库的可见性是<b>按使用者切片</b>的： {@code
   * ToolContext.identity().instanceId()} 就是工作区调用者键（指南 §4 第 4 条逐字：「必须等于挂载的 {@code
   * user}，否则连挂载都解析不到」）。各建一份注册表的话，GM 用 {@code mount_workspace} 挂出来的工作区 <b>决策人那条路根本看不见</b>（两份注册表不是同一个
   * ⇒ 挂载查不到）——P2/P3 直接不可达。
   *
   * <p>★ <b>为什么是静态持有</b>：两个工具面是**两个** {@code SimosToolSource} 实例，而 {@code SimosToolSource} 的构造配方里
   * 没有"宿主单例"这一类形参（加形参会改掉用例在用的构造签名）⇒ 静态持有是**两桶 + 装配期挂载（{@code Shell}）共用同一份注册表**的 唯一做法。代价（如实记账，见
   * {@code impl-ledger.md}）：① 同 JVM 内多个 {@code Shell} 实例共享挂载（用例间可能串味）； ② 挂载<b>不落盘</b>——{@code
   * DefaultWorkspaceRegistry} 是纯内存实现（{@code slices}/{@code history} 两个字段，反编译核实） ⇒ 每次启动都要重新挂（{@link
   * #provisionWorkspaces}，幂等）。
   */
  private static final class ExternalWorkspaceHost {

    private static final ExternalWorkspaceHost INSTANCE = new ExternalWorkspaceHost();

    private final WorkspaceRegistry registry;

    /** 装配期给的文档物理根（{@link #provisionWorkspaces} 设置）；{@code null} = 装配期没挂过（懒补不动）。 */
    private Path docsRoot;

    private ExternalWorkspaceHost() {
      // ★ 指南 §3.1 的库门装配第一步：能力目录（docs 能力必须注册，否则挂载声明不了 `docs` 能力 ⇒ 工具门全拒）。
      CapabilityCatalog catalog = CapabilityCatalogs.inMemory();
      DocsCapability.register(catalog);
      // ★★ 自定义血缘是"GM 替 DM 挂"的**必要条件**（默认恒等映射 ⇒ M2 拒，逐条实测见 workspaceLineage 的注释）。
      this.registry =
          DefaultWorkspaceRegistry.create(catalog, null, SimosToolSource::workspaceLineage);
    }

    private static WorkspaceRegistry registry() {
      return INSTANCE.registry;
    }

    /**
     * 该使用者切片里已有**同名**挂载？（幂等判据 —— 与 {@code DefaultWorkspaceRegistry.mount} 的 M4 判据 同源：{@code
     * lookup(user, name) != null} 就是"该使用者名下已有一个同名挂载"）。
     */
    private boolean hasMount(String userKey, String mountName) {
      return registry.mounts(userKey).stream()
          .anyMatch(mount -> mount.name().value().equals(mountName));
    }

    /** 默认工作区那一块已有？（等价 {@code hasMount(userKey, DEFAULT_WORKSPACE_NAME)}） */
    private boolean hasMount(String userKey) {
      return hasMount(userKey, DEFAULT_WORKSPACE_NAME);
    }

    /** 宿主根挂载（{@code <docsRoot>/gm}）：{@code mount_workspace} 的 {@code parent} 只能从它派生。 */
    private boolean mountRoot(Path docsRoot) {
      if (hasMount(WORKSPACE_HOST_KEY)) {
        return false;
      }
      Path dir = createDir(docsRoot.resolve("gm"));
      registry.mount(
          Mount.of(
              new MountName(DEFAULT_WORKSPACE_NAME),
              WORKSPACE_HOST_KEY,
              WORKSPACE_HOST_KEY,
              dir,
              Kind.FILE,
              Set.of(DocsCapability.ID),
              Origin.ASSEMBLY),
          new WorkspaceCaller(WORKSPACE_HOST_KEY, assemblyPermissions(docsRoot)));
      return true;
    }

    /**
     * 一个决策人的默认工作区：{@code holder=宿主}、{@code user=decision-maker:<id>}、 {@code
     * derivedFrom=宿主的根挂载}、{@code dir=<docsRoot>/dm/<id>}（宿主根下的**真**子目录）。
     */
    private boolean mountDecisionMaker(Path docsRoot, String decisionMakerId) {
      String userKey = DecisionCallerFactory.INSTANCE_ID_PREFIX + decisionMakerId;
      if (hasMount(userKey)) {
        return false;
      }
      Path dir = createDir(docsRoot.resolve("dm").resolve(decisionMakerId));
      try {
        registry.mount(
            new Mount(
                new MountName(DEFAULT_WORKSPACE_NAME),
                WORKSPACE_HOST_KEY,
                userKey,
                dir,
                Resolvers.direct(),
                Kind.FILE,
                Set.of(DocsCapability.ID),
                Origin.ASSEMBLY,
                Optional.of(new MountName(DEFAULT_WORKSPACE_NAME))),
            new WorkspaceCaller(WORKSPACE_HOST_KEY, assemblyPermissions(docsRoot)));
        return true;
      } catch (WorkspaceException e) {
        // ★ 并发对手先挂上了 ⇒ 幂等（两条决策回合同时补同一块地是可能的）；真失败照抛（fail-closed）。
        if (hasMount(userKey)) {
          return false;
        }
        throw e;
      }
    }

    /** 物理落点：建不出来是**装配故障**（不静默降级成"没工作区"）。 */
    private static Path createDir(Path dir) {
      try {
        return Files.createDirectories(dir);
      } catch (IOException e) {
        throw new IllegalStateException("工作区物理落点建不出来（装配故障）: " + dir, e);
      }
    }

    /**
     * ★★ <b>公用推演日志挂载的<u>唯一</u>实现</b>（{@code <docsRoot>/shared/sim-log/}，2026-10-14 用户裁定
     * 「首先应该有一个<b>目录</b>，<b>所有 DM/大部分 DM 都有读取权限</b>，具体读取权限怎么实现你自己看着办」）。
     *
     * <p>★ <b>怎么实现"多人都能读"</b>：<u>不是</u>共享权限位，而是把<b>同一块物理地</b>分头挂给多个使用者 —— 挂载表按 {@code (user, name)}
     * 分片、唯一性只查 {@code lookup(user, name)}（<b>不查 dir</b>）⇒ 宿主一条、每个决策人各一条，指向同一个 dir。读得到是因为 {@link
     * #withDecisionMakerDocsScopes} 把 {@code docs} 面铺到它名下<b>全部</b>挂载的落点上。
     *
     * <p>★ <b>幂等</b>：判据与 {@link #hasMount} 同款（"同名挂载已存在"），且并发对手先挂上时按 M4 当场拒 ⇒ 视同幂等 （与 {@link
     * #mountDecisionMaker} 同一处理）。装配期与懒补<b>都会</b>走到这里。
     *
     * <p>★ <b>为什么不带 {@code derivedFrom}</b>：它<b>不是</b>任何人的子工作区（M9/W1 的"真子目录"不适用），也不是"派生形态" ——
     * 它是一块由宿主直接开出来的公用平地。
     *
     * <p>★★ <b>{@code holder == user == 这条挂载的"挂给谁"（userKey）}</b>（2026-10-14 修正：旧注释曾写「{@code holder}
     * 恒为宿主、 {@code user} 才是"挂给谁"」，<b>该说法已被实测证伪</b>）。理由是守卫给出的一条硬约束 —— <b>同一个 {@code holder} 名下、同一物理
     * dir 只能有一条挂载</b>，逐条对上报错经过的三个判据（行号 = 依赖 jar 源码）：
     *
     * <ol>
     *   <li>{@code DefaultWorkspaceRegistry:103}（M2）：{@code caller.holder().equals(spec.holder())}
     *       —— holder 恒为创建者本人；
     *   <li>{@code DefaultWorkspaceRegistry:113}（M2）：{@code
     *       Lineage.inSubtree(lineageOf(caller.holder()), lineageOf(spec.user()))} —— caller 必须是
     *       user 的祖先<b>或本身</b>（{@code Lineage:37}：{@code candidate.equals(ancestor) ||
     *       startsWith(ancestor + "/")}，<b>含自身</b>）；
     *   <li>{@code DefaultWorkspaceRegistry:164-173}（M9/W1）：{@code Mount own =
     *       ownMountContaining(caller.holder(), spec.dir())}， 若 {@code own != null &&
     *       !own.strictlyContains(spec.dir())} ⇒ 当场拒。而 {@code ownMountContaining:479-494} <b>只查
     *       {@code slices.get(holder)} 这一片</b>（切片键是 {@code spec.user()}：{@code :204} 落格 / {@code
     *       mounts():265} 取片）， {@code Mount.contains:95} 的口径是"{@code equals || startsWith}" ⇒ ★
     *       <b>"等于"也算包含</b>，而 {@code Mount.strictlyContains:104} 要求的是<b>真子目录</b>。
     * </ol>
     *
     * <p>⇒ <b>因果链</b>：① {@link #mountSharedSimLogForHost} 以 holder=宿主挂上 {@code
     * <docsRoot>/shared/sim-log} —— 成功（宿主那一片里只有 {@code <docsRoot>/gm}，是<b>兄弟</b>目录 ⇒ {@code own ==
     * null}）；② 决策人循环里要挂<b>同一块物理地</b>，若 holder 仍是宿主，则③ 命中①那条 <b>同 dir</b> 的 {@code
     * sim-log}，"等于"过不了"真子目录" ⇒ 拒。这正是 {@code Shell.start} 在「世界里已有决策人」时起不来的那个生产缺陷 （原始报错：{@code
     * 工作区操作被拒（M9/W1）: 子工作区必须是自己的目录下的真子目录（等于自己的工作区根 = 把权限整份复制）}）。 把 holder 与 caller 都换成"这条挂载自己的
     * user"后：① 逐字相等 ✓；② {@code inSubtree(x, x) == true} ✓；③ 每个 user 的切片里只装<b>自己那一条</b> （宿主切片只有
     * {@code <docsRoot>/gm}、决策人切片只有 {@code <docsRoot>/dm/<id>}，都与 {@code <docsRoot>/shared/sim-log}
     * 互不包含） ⇒ {@code own == null} ⇒ M9/W1 整段跳过 ✓。<b>设计意图（同一块物理地分头挂给多个使用者）不变 —— 错的是 holder，不是意图。</b>
     *
     * <p>★ <b>改 holder 不影响读面/写面</b>：幂等判据 {@link #hasMount} 与 {@link #withDecisionMakerDocsScopes}
     * 都走 {@code registry.mounts(userKey)}（<b>按 user 取切片</b>，方法形参名虽叫 {@code holder}）⇒ 决策人仍枚举得到
     * {@code sim-log}（读）， 而写面只收 {@code name == DEFAULT_WORKSPACE_NAME} 的落点 ⇒ {@code sim-log}
     * 对决策人<b>仍然只读</b>。
     *
     * @param userKey 挂给谁（宿主键或 {@code decision-maker:<id>}）—— ★ 同时也是本挂载的 {@code holder}（见上）
     * @return true = 这次真挂了；false = 已有同名挂载（不改任何东西）
     */
    private boolean mountSharedSimLog(Path docsRoot, String userKey) {
      if (hasMount(userKey, SHARED_SIM_LOG_NAME)) {
        return false;
      }
      // ★ 同一物理目录（多次调用 resolve 到同一路径；createDirectories 幂等）。
      Path dir = createDir(docsRoot.resolve("shared").resolve(SHARED_SIM_LOG_NAME));
      try {
        // ★★ holder 必须是"这条挂载自己的 user"，不能恒为宿主：同一个 holder 名下、同一物理 dir 只能有一条挂载
        //    （M9/W1 的 ownMountContaining 只查 slices.get(holder)，且 Mount.contains 把"等于"也算包含 ⇒
        // 第二条会被拒）。
        //    挂给宿主时 userKey == WORKSPACE_HOST_KEY，与旧写法逐字等价；差别只在决策人那一条。详见上方方法注释。
        registry.mount(
            Mount.of(
                new MountName(SHARED_SIM_LOG_NAME),
                userKey,
                userKey,
                dir,
                Kind.LOG,
                Set.of(DocsCapability.ID),
                Origin.ASSEMBLY),
            new WorkspaceCaller(userKey, assemblyPermissions(docsRoot)));
        return true;
      } catch (WorkspaceException e) {
        // ★ 并发对手先挂上了 ⇒ 幂等；真失败照抛（fail-closed）。
        if (hasMount(userKey, SHARED_SIM_LOG_NAME)) {
          return false;
        }
        throw e;
      }
    }

    /** 宿主的公用推演日志挂载（★ GM 要用它来写日志 —— 用户 2026-10-14：「目前都交给 GM 吧」）。 */
    private boolean mountSharedSimLogForHost(Path docsRoot) {
      return mountSharedSimLog(docsRoot, WORKSPACE_HOST_KEY);
    }

    /**
     * 决策人的公用推演日志挂载 —— ★ <b>同源</b>：装配期（{@link #provisionWorkspaces}）与懒补（{@link
     * #ensureDecisionMakerWorkspace}）都调这里，<b>不复制第二份挂载逻辑</b>。
     *
     * <p>★★ <b>排除口子</b>（用户裁定里的「部分私密」）：命中的决策人<b>直接不挂</b> —— <b>排除 = 不挂，不是加权限位</b>。 没有挂载 ⇒ {@code
     * docs} 面自动不覆盖 ⇒ 读不到，且"不可见"与"不存在"同码。★ 默认空集（所有 DM 都给）， 判据未定 ⇒ 这里不接任何真实规则，见 {@link
     * SimosToolSource#simLogExcludedDecisionMakers}。
     *
     * @param decisionMakerId 世界的决策人 <b>id</b>（不带 {@code decision-maker:} 前缀）
     * @return true = 这次真挂了；false = 被口子排除 / 已有同名挂载
     */
    private boolean mountSharedSimLogForDecisionMaker(Path docsRoot, String decisionMakerId) {
      if (simLogExcludedDecisionMakers.contains(decisionMakerId)) {
        return false;
      }
      return mountSharedSimLog(
          docsRoot, DecisionCallerFactory.INSTANCE_ID_PREFIX + decisionMakerId);
    }

    /**
     * 装配期挂载用的**宿主权限集**：三个命名空间各自表态（指南 §4 第 1/3 条：不表态 = 一个挂载都建不了）。
     *
     * <p>★ 只用于挂载期的 {@code WorkspacePermissions.admits} 判定（{@code SYSTEM} 档 = 宿主本人），不进任何运行期调用者。
     */
    private static AgentPermissionSet assemblyPermissions(Path docsRoot) {
      return AgentPermissionSet.builder(AccessToken.SYSTEM)
          .allowAll()
          .sensitiveAllowed(true)
          .destructiveAllowed(true)
          .resourceScopes(
              ResourceScopeMap.of(
                  Map.of(
                      WorkspacePermissions.WORKSPACES,
                      ResourceScope.ofDir(docsRoot),
                      DOCS_NAMESPACE,
                      ResourceScope.ofDir(docsRoot),
                      DOCS_EDIT_NAMESPACE,
                      ResourceScope.ofDir(docsRoot))))
          .build();
    }
  }

  /**
   * 角色表里的「两桶共享」（U2：doc 系列在<b>指定工作区</b>的情况下给 DM 自用）。
   *
   * <p>★ 与各工具自己的 {@code roles()} <b>同集</b>（{@code {DM, GM}}）；<b>顺序无语义</b>（{@code Set.of} 本就无序 ——
   * 这里的元素序只是与多数派 {@code roles()} 的拼法对齐，让读代码的人一眼看出是同一个集合，D1 / 设计书 §3.3）。
   */
  private static final Set<ToolRole> ROLES_GM_DM = Set.of(ToolRole.DM, ToolRole.GM);

  /** 角色表里的「GM 独有」（U1：<b>工作区由 GM 维护</b>；作废也是 GM 动作）。 */
  private static final Set<ToolRole> ROLES_GM = Set.of(ToolRole.GM);

  /**
   * ★★ <b>外来工具的角色表</b>（设计书 §4.2 / I1 / I6）：名字 → 角色，<b>逐条、无缺省</b>。
   *
   * <p>★ <b>为什么逐条而不是"整库默认"</b>：他们将来加一条工具时，<b>我们这边必须当场红</b>（{@link #rolesOf} 抛）， 而不是静默拿到一个默认角色——静默兜底
   * = 权限面悄悄变化，正是本仓最贵的一类失效（{@code ToolRoleAudit} 的类注同款口径）。
   *
   * <p>★ <b>名字一律用他们的常量</b>（{@code DocsTools.DOC_*} / {@code WorkspaceTools.*}）而不是字面量：这样他们改名
   * 我们**编译期**就红，而不是运行期悄悄少一条工具（I7 保留原名，不改名）。
   *
   * <p>★ <b>逐条口径</b>：{@code doc_*} 8 条里 7 条（list/read/query/append/create/edit/guide）= {@code {GM,
   * DM}}（U2）；{@code doc_void} = {@code {GM}}（作废别人的记录是 GM 动作）；工作区 4 条里 3 条 = {@code {GM}}（U1），
   * {@code workspace_info} = {@code {GM, DM}}。★ 三条工作区写工具里 {@code unmount_workspace} 在设计书 §4.2 的表里
   * 没有单列（那张表只点了 mount/list 两条）——按 U1 同款口径补为 {@code {GM}}，并已记入账本「与设计书的偏差」。
   *
   * <p>★ <b>2026-10-14 新增两条「能力自述」工具</b>（上游 {@code 4a4084d}：{@code DocsTools.DOC_GUIDE} / {@code
   * WorkspaceTools.WORKSPACE_INFO}）⇒ {@code {GM, DM}}。★ <b>用户原则逐字</b>：「只要涉及到真实世界变动…那 GM
   * 必须审核，如果只是查看，查看范围应当是对决策人的权限范围的限制，GM 理论上事先已经做好了，所以不需要也不应该让 GM 审批，走 DM_FREE」
   * ——两条都是<b>纯读</b>（{@code ResourceManifest.NONE} + {@code ToolSpec.level(DEFAULT, false,
   * false)}：可导出、不敏感、不破坏），不产生真实世界变动 ⇒ 对决策人<b>不设限</b>（他自己的挂载在哪，他自己最该看得见）。
   */
  private static final Map<String, Set<ToolRole>> EXTERNAL_TOOL_ROLES =
      Map.ofEntries(
          Map.entry(DocsTools.DOC_LIST, ROLES_GM_DM),
          Map.entry(DocsTools.DOC_READ, ROLES_GM_DM),
          Map.entry(DocsTools.DOC_QUERY, ROLES_GM_DM),
          Map.entry(DocsTools.DOC_APPEND, ROLES_GM_DM),
          Map.entry(DocsTools.DOC_CREATE, ROLES_GM_DM),
          Map.entry(DocsTools.DOC_EDIT, ROLES_GM_DM),
          // ★ 自述（只回答"这族工具有哪些、怎么用、我在哪块地上能用"）：零挂载也给清楚回答、不抛异常，两桶都要有。
          Map.entry(DocsTools.DOC_GUIDE, ROLES_GM_DM),
          // ★ 作废（再追加一条 VOIDED 记录）不可逆：留在 GM 手里，决策人能写自己的、但不能标掉别人的。
          Map.entry(DocsTools.DOC_VOID, ROLES_GM),
          Map.entry(WorkspaceTools.MOUNT_WORKSPACE, ROLES_GM),
          Map.entry(WorkspaceTools.UNMOUNT_WORKSPACE, ROLES_GM),
          Map.entry(WorkspaceTools.LIST_WORKSPACES, ROLES_GM),
          // ★ 自述（回答"工作区是什么 + 调用者自己切片的挂载清单"）：纯读、零挂载也给清楚回答。
          Map.entry(WorkspaceTools.WORKSPACE_INFO, ROLES_GM_DM));

  /**
   * 外来工具里的<b>读类</b>（设计书 §4.3：读不需要审批 ⇒ {@link ToolGate#ALLOW}）。
   *
   * <p>★ <b>不在本集合里的外来工具一律走 {@code Ask(SENSITIVE)}</b>——"没列进读类"就是"要审批"， 这是 fail-closed
   * 的那一侧（将来他们加一条写工具，我们这边不会静默放行）。
   *
   * <p>★ <b>2026-10-14 新增两条自述工具</b>（{@code doc_guide} / {@code workspace_info}）：两者都是 {@code
   * ResourceManifest.NONE} + {@code ToolSpec.level(DEFAULT, false, false)} ⇒
   * <b>纯读</b>（可导出、不敏感、不破坏）， 按上面的判据进本集合 ⇒ {@link ToolGate#ALLOW}（用户原则：「如果只是查看…不需要也不应该让 GM 审批，走
   * DM_FREE」）。
   */
  private static final Set<String> EXTERNAL_READ_TOOLS =
      Set.of(
          DocsTools.DOC_LIST,
          DocsTools.DOC_READ,
          DocsTools.DOC_QUERY,
          DocsTools.DOC_GUIDE,
          WorkspaceTools.LIST_WORKSPACES,
          WorkspaceTools.WORKSPACE_INFO);

  /**
   * ★★ <b>审批 summary 的白名单取键表</b>（U3 用户裁定 / I5）：<b>只有这里列出的键才可能进 summary</b>， 其余字段（正文 {@code
   * text}/{@code value}、可见性轴 {@code owner}/{@code tags}/{@code affiliations}、 查询条件 {@code
   * tick}/{@code from}/{@code to}/{@code limit}、编辑载荷 {@code ops}、{@code fields}/{@code note}…）
   * <b>一律不出现</b>。
   *
   * <p>★ <b>白名单，不是黑名单</b>（设计书 §4.3 明令）：黑名单会随他们的 schema 演进而漏——新字段自动进 summary； 白名单只认已知的路径键，加字段也不会泄漏。
   *
   * <p>★ 三条工作区键也是路径：{@code mount_workspace} 的形态是 {@code parent} + {@code subdir}（派生挂载； 指南 §6
   * 逐字：「工具门只接受派生形态」），{@code name} 是挂载名（虚拟路径第一段）。
   */
  private static final Map<String, List<String>> APPROVAL_PATH_KEYS =
      Map.of(
          DocsTools.DOC_APPEND, List.of("mount"),
          DocsTools.DOC_VOID, List.of("mount"),
          DocsTools.DOC_CREATE, List.of("mount"),
          DocsTools.DOC_EDIT, List.of("mount"),
          WorkspaceTools.MOUNT_WORKSPACE, List.of("parent", "subdir", "name"),
          WorkspaceTools.UNMOUNT_WORKSPACE, List.of("name"));

  /** summary 里单个路径字段的字符上限。★ 超长只截断——截断只会让 GM 看到<b>更少</b>信息，绝不更多。 */
  private static final int SUMMARY_FIELD_LIMIT = 512;

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // ★★ 2026-10-14 W2（设计书 §10.2 / 用户裁定「给每个决策人一个默认工作区」）：**装配期挂载**。
  //   工具门（`mount_workspace`）只能挂 user==调用者自己（schema 无 `user`）⇒ "GM 替 DM 挂"只能走
  //   `WorkspaceRegistry.mount(...)`（宿主的活）。血缘规则逐条实测见 `impl-ledger.md` §12。
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /** 工作区的宿主身份键 = MCP 口（{@code Shell.gmCaller()}）的身份，反编译核实即 {@code AgentIdentity.EXTERNAL_ID}。 */
  public static final String WORKSPACE_HOST_KEY = AgentIdentity.EXTERNAL_ID;

  /**
   * 默认工作区的**虚拟挂载名**（GM 与每个 DM 都叫这个名字——切片按使用者隔离，各自看得见自己那块）。
   *
   * <p>★ 形态必须匹配 {@code MountName.PATTERN}（{@code [a-z0-9][a-z0-9-]{0,63}}）。
   */
  public static final String DEFAULT_WORKSPACE_NAME = "workspace";

  /**
   * ★★ <b>公用推演日志的虚拟挂载名</b>（{@code <docsRoot>/shared/sim-log}，2026-10-14 用户裁定「首先应该有一个<b>目录</b>，
   * <b>所有 DM/大部分 DM 都有读取权限</b>」）。
   *
   * <p>★ <b>同一块物理地，分头挂给多个使用者</b>：挂载表按 {@code (user, name)} 分片、唯一性只查 {@code lookup(user,
   * name)}（<b>不查 dir</b>，{@code DefaultWorkspaceRegistry:193}）⇒ 宿主（GM）与每个决策人可以各自有一条 {@code sim-log}
   * 挂载、指向同一个目录。★ 不是"共享权限位"，是<b>多条挂载指向同一 dir</b>。
   *
   * <p>★ <b>为什么 {@code kind = }{@link Kind#LOG}</b>：推演日志是<b>追加型、不可变</b>语义（纪律 {@code Kind}
   * 与资源面是两条独立的轴）——LOG 地只服务 {@code doc_query}/{@code doc_append}/{@code doc_void} 这一族。
   *
   * <p>★ <b>形态必须匹配 {@code MountName.PATTERN}（{@code [a-z0-9][a-z0-9-]{0,63}}）</b>：不能有下划线。
   */
  public static final String SHARED_SIM_LOG_NAME = "sim-log";

  /**
   * ★★ <b>「不给挂 {@code sim-log}」的决策人 id 集合</b>（**具名排除口子**，2026-10-14 用户裁定里的「部分私密」）。
   *
   * <p>★★ <b>排除 = 不挂，<u>不是</u>加权限位</b>（这是本口子的全部设计）：挂载表里没有这一条 ⇒ {@code withDecisionMakerDocsScopes}
   * 的 {@code docs} 面自动不覆盖它 ⇒ 读不到；而且"不可见"与"不存在"<b>同码</b> （不泄露存在性）。★ 零额外权限位、零额外判据。
   *
   * <p>★ <b>默认空集 = 所有决策人都给</b>。★ <b>目前是空口子</b>：用户尚未给出"哪些 DM 不发"的判据 ⇒ 这里<b>不接任何真实排除逻辑</b>，
   * 只留入口与传播路径（谁将来定判据，只改这个集合的填充处，不动挂载实现）。
   *
   * <p>★ 并发：整体替换 + {@code volatile}（本仓口径：读多写少的口子不做细粒度锁；挂载本身另有注册表锁）。
   */
  private static volatile Set<String> simLogExcludedDecisionMakers = Set.of();

  /**
   * 设置「不给挂 {@code sim-log} 公用推演日志」的决策人 <b>id</b> 集合（不是 {@code decision-maker:} 前缀的 user 键）。
   *
   * <p>★ 排除 = <b>不挂</b>（不是加权限位）：下次装配/懒补时该决策人拿不到 {@code sim-log} 挂载 ⇒ {@code docs} 面自动不覆盖它 ⇒
   * 它读不到公用日志，且"不可见"与"不存在"同码。★ 传 {@code null} 视同空集。
   *
   * <p>★ 已经是空口子（见 {@link #simLogExcludedDecisionMakers}）：本方法存在是为了让"部分私密"有<b>形状</b>， 不是因为现在有判据。
   */
  public static void setSimLogExcludedDecisionMakers(Set<String> decisionMakerIds) {
    simLogExcludedDecisionMakers =
        decisionMakerIds == null ? Set.of() : Set.copyOf(decisionMakerIds);
  }

  /** 读回当前的口子内容（自检/用例断言用）；空集 = 所有决策人都给。 */
  public static Set<String> simLogExcludedDecisionMakers() {
    return simLogExcludedDecisionMakers;
  }

  /** 文档能力的**读命名空间**（他们的 {@code DocsTools} 读工具 manifest 用的就是这个字面）。 */
  public static final String DOCS_NAMESPACE = "docs";

  /** 文档能力的**写命名空间**（{@code doc_append/create/edit/void} 的 manifest 用的字面）。 */
  public static final String DOCS_EDIT_NAMESPACE = "docs-edit";

  /** 决策人 id 的**安全形态**（进物理目录名前必须过它：id 是世界里的字符串，`..`/`/` 会把落点带出根外）。 */
  private static final Pattern SAFE_DECISION_MAKER_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  /**
   * ★★ <b>血缘映射</b>（`DefaultWorkspaceRegistry.create(catalog, sink, lineagePathOf)` 的宿主定制点）。
   *
   * <p>★ <b>为什么必须有</b>（实测）：`DefaultWorkspaceRegistry.mount` 的 M2 判据是 {@code
   * Lineage.inSubtree(lineageOf(caller.holder()), lineageOf(spec.user()))}，而默认血缘是**恒等映射** ⇒ {@code
   * "decision-maker:<id>"} <b>不在</b> {@code "external-mcp"} 的子树里（{@code user.equals(lineage) ||
   * user.startsWith(lineage + "/")} 两条都不成立）⇒ <b>"GM 替 DM 挂"被 M2 当场拒</b> （原始报错：{@code 工作区操作被拒（M2）:
   * 只能为自己或自己的后代创建工作区（user 必须落在自己的血缘子树内）}）。
   *
   * <p>⇒ 宿主在此**显式声明血缘**：「决策人键是宿主（GM/MCP 口）的子节点」。★ 只动决策人这一支——其余键**恒等**，
   * 他们默认语义（"只能为自己或后代建"）对其它任何身份一个字不变。
   */
  private static String workspaceLineage(String holderKey) {
    String key = holderKey == null ? "" : holderKey.strip();
    return key.startsWith(DecisionCallerFactory.INSTANCE_ID_PREFIX)
        ? WORKSPACE_HOST_KEY
            + "/"
            + key.substring(DecisionCallerFactory.INSTANCE_ID_PREFIX.length())
        : key;
  }

  /** 装配期挂载的结果（给调用方日志/用例读回；挂载失败一律抛，不在这里吞）。 */
  public record WorkspaceProvision(int roots, int decisionMakers, int skipped) {}

  /**
   * ★★ <b>装配期挂载</b>（设计书 §10.2 / 用户裁定「在1的基础上给每个决策人一个默认工作区」）：<b>幂等</b>。
   *
   * <ol>
   *   <li><b>宿主根挂载</b>：{@code mount_workspace} 只做<u>派生形态</u>（{@code parent + subdir}）⇒ 没有根挂载就无从起步
   *       （P2 的可达前提）；
   *   <li><b>逐决策人默认工作区</b>：{@code holder=宿主键}、{@code user=decision-maker:<id>}、 {@code
   *       derivedFrom=根}、{@code dir=宿主根下的真子目录} —— M1 ✓（holder=创建者）、M2 ✓（血缘映射，见 {@link
   *       #workspaceLineage}）、M10 ✓（派生来源在创建者自己切片里）、M9/W1 ✓（真子目录）。
   * </ol>
   *
   * <p>★ <b>为什么由宿主做而不是 GM 手工做</b>：{@code mount_workspace} 的 schema 只有 {@code {name, parent, subdir,
   * kind}}，<b>没有 {@code user}</b> ⇒ 工具门永远只能建 {@code user == 调用者自己}；给隔壁加参数 = 改他们的库（破 I2）。
   *
   * <p>★ <b>装配期只覆盖"此刻已存在的决策人"</b>：空库的 bootstrap 发生在 {@code Shell.start} <b>之后</b> （{@code
   * ShellMain:162-168} 逐字），故那时可能一个 DM 都没有 ⇒ 装配之后才建的 DM 由 {@link #ensureDecisionMakerWorkspace}
   * 在它**跑第一轮之前**补上（同一个幂等实现）。
   *
   * @param hostKey 宿主（MCP 口）身份键；<b>必须</b>与 {@link #WORKSPACE_HOST_KEY} 逐字一致（不一致当场抛：挂载的 {@code
   *     holder} 与运行期调用者键对不上就永远解析不到，而"不可见"与"不存在"同码）
   * @param docsRoot 文档物理根（宿主给的，如 {@code <store>/workspaces}）；根挂载落在 {@code <docsRoot>/gm}
   * @param decisionMakerIds 此刻世界里的决策人 id（非法形态的跳过并计数——它们会把落点带出根外）
   */
  public static WorkspaceProvision provisionWorkspaces(
      String hostKey, Path docsRoot, Collection<String> decisionMakerIds) {
    Objects.requireNonNull(hostKey, "hostKey");
    Objects.requireNonNull(docsRoot, "docsRoot");
    Objects.requireNonNull(decisionMakerIds, "decisionMakerIds");
    if (!WORKSPACE_HOST_KEY.equals(hostKey)) {
      throw new IllegalStateException(
          "工作区宿主身份键不一致：装配给的 "
              + hostKey
              + " ≠ "
              + WORKSPACE_HOST_KEY
              + " —— 挂载的 holder 必须与 MCP 口（Shell.gmCaller）的身份逐字一致，否则运行期连挂载都解析不到"
              + "（M1：holder 恒为创建者本人；指南 §4 第 4 条）。");
    }
    ExternalWorkspaceHost host = ExternalWorkspaceHost.INSTANCE;
    host.docsRoot = docsRoot;
    int roots = host.mountRoot(docsRoot) ? 1 : 0;
    // ★★ 公用推演日志（`<docsRoot>/shared/sim-log`）：宿主一条（GM 要用它写日志）+ 每个决策人一条（读取权限）。
    //    ★ 装配期与懒补同源：都走 ExternalWorkspaceHost.mountSharedSimLog*（不复制第二份挂载逻辑）。
    host.mountSharedSimLogForHost(docsRoot);
    int mounted = 0;
    int skipped = 0;
    for (String id : decisionMakerIds) {
      if (id == null || !SAFE_DECISION_MAKER_ID.matcher(id).matches()) {
        skipped++;
        continue;
      }
      if (host.mountDecisionMaker(docsRoot, id)) {
        mounted++;
      }
      host.mountSharedSimLogForDecisionMaker(docsRoot, id);
    }
    return new WorkspaceProvision(roots, mounted, skipped);
  }

  /**
   * 补一个决策人的默认工作区 + 公用推演日志（**幂等、fail-soft**）：装配之后才建的 DM 在它跑第一轮之前补上。
   *
   * <p>★ <b>为什么 fail-soft（不抛）</b>：这条走在决策人每一轮的调用者装配路径上（{@code DecisionCallerFactory}）——
   * 补挂失败**不该**让一个不碰文档的回合整轮失败。代价是"没补上"这件事只在文档工具被调用时以 {@code WORKSPACE_NOT_FOUND}
   * 现形（那正是他们"不可见/不存在同码"的既有形态），且装配期没挂过根时本方法**直接不动** （用例自搭的壳不该被越界补）。
   *
   * <p>★★ <b>与装配期同源</b>（2026-10-14）：公用推演日志（{@code sim-log}）也在这里补，且调的是同一段实现 （{@code
   * ExternalWorkspaceHost.mountSharedSimLogForDecisionMaker}）——<b>不复制第二份挂载逻辑</b>。⇒ 空库 bootstrap
   * 之后才建的 DM，既有一块私有地、也拿得到公用日志的读取权限。
   *
   * @return true = 这次真补上了<b>默认工作区</b>；false = 已存在 / 没根 / id 形态非法（★ 注意：返回 false 时 {@code sim-log}
   *     仍可能被补上——本返回值只对"默认工作区"这一块表态）
   */
  public static boolean ensureDecisionMakerWorkspace(String decisionMakerUserKey) {
    Objects.requireNonNull(decisionMakerUserKey, "decisionMakerUserKey");
    ExternalWorkspaceHost host = ExternalWorkspaceHost.INSTANCE;
    Path root = host.docsRoot;
    if (root == null
        || !decisionMakerUserKey.startsWith(DecisionCallerFactory.INSTANCE_ID_PREFIX)) {
      return false;
    }
    String id = decisionMakerUserKey.substring(DecisionCallerFactory.INSTANCE_ID_PREFIX.length());
    if (!SAFE_DECISION_MAKER_ID.matcher(id).matches()) {
      return false;
    }
    boolean mounted = host.mountDecisionMaker(root, id);
    // ★ 同源：装配期调的是同一个 mountSharedSimLogForDecisionMaker（排除口子也在这段里，不重复判）。
    host.mountSharedSimLogForDecisionMaker(root, id);
    return mounted;
  }

  /**
   * 读回口径：某个使用者切片里**当前**的挂载（装配自检、控制方真机核对、用例断言用）。
   *
   * <p>★ 只读快照（{@code Mount} 是不可变记录）——<b>写入口只有</b> {@link #provisionWorkspaces} 与 {@link
   * #ensureDecisionMakerWorkspace} 两个（不暴露注册表本体：那会变成第二条挂载路径）。
   */
  public static List<Mount> mountsOf(String userKey) {
    Objects.requireNonNull(userKey, "userKey");
    return ExternalWorkspaceHost.INSTANCE.registry.mounts(userKey);
  }

  /**
   * 决策人的**文档资源范围**（{@code docs}/{@code docs-edit} 两个命名空间）。
   *
   * <p>★★ <b>读面与写面<u>不是</u>同一个集合</b>（2026-10-14 用户裁定「首先应该有一个<b>目录</b>，<b>所有 DM/大部分 DM
   * 都有读取权限</b>；然后<b>每个 DM 自己额外有一个私有目录，这个目录 DM 随便干啥都行</b>」）：
   *
   * <ul>
   *   <li>{@code docs}（<b>读</b>）= 该使用者名下<b>全部</b>挂载的落点 —— 私有地 + 公用区（{@code sim-log}）⇒ <b>两处都能读</b>；
   *   <li>{@code docs-edit}（<b>写</b>）= <b>只有挂载名 == {@link #DEFAULT_WORKSPACE_NAME}</b> 那些挂载的落点 ⇒
   *       公用区<b>只读</b>。
   * </ul>
   *
   * <p>★★ <b>为什么写面按「挂载名」收窄</b>（本方法的全部要点）：
   *
   * <ol>
   *   <li><b>公用区必须是只读的，而这件事只能由资源面保证</b>：AgentLib 的文档引擎逐字是「读面看 {@code docs}，写面看 {@code
   *       docs-edit}」（{@code DocsEngine.require}）——两个命名空间是<b>两条独立的闸</b>。若 {@code docs-edit} 与
   *       {@code docs} 取同一个集合，则"给 DM 再挂一个公用目录"这一步会<b>顺带把公用区变成可写</b>（不是只读）；
   *   <li><b>纪律（{@code Kind}）管不了这件事</b>：{@code Kind.LOG} 与 {@code Kind.FILE} 是**另一条轴**（一块地只服务
   *       哪个工具族），它<b>不表态</b>谁能写。★ 本仓最贵的一类失效就是"看起来有条边界、其实不在判定路径上" ⇒ 写面的收窄必须
   *       落在<b>资源面</b>（判定路径）上，落在纪律上等于没有；
   *   <li><b>判据用挂载名，不用 {@code Kind}、也不加新字段</b>：私有地 = 既有的 {@link #DEFAULT_WORKSPACE_NAME} （GM 与每个 DM
   *       都叫这个名字，切片按使用者隔离）——这是一个<b>已经存在</b>的、装配期就在写的名字，不需要新的元数据 （新字段 = 多一处会漂移的口径）。★ 判据<b>不是</b>
   *       {@code Kind}：{@code Kind} 表达的是"这块地服务哪个工具族"， 不是"谁可以写"。
   * </ol>
   *
   * <p>★ <b>为什么按挂载取而不是给整棵树</b>：可达面 = **它自己名下那些地**（他们按使用者切片本就把别人的挂载挡在外面， 这里只是把同一件事写进权限面）。
   *
   * <p>★ <b>fail-closed</b>：<b>没有挂载 ⇒ 两个命名空间都是</b> {@link ResourceScope#none()}（够不着，与"未表态"两种取值相反）。
   * ★ 有挂载但没有一块叫 {@code workspace} 时：读面照给（那些挂载本来就该能读）、写面为 {@code none()} —— 这正是"公用区只读"的形态。
   *
   * <p>★ <b>单调性</b>：DM 的两个范围 ⊆ 宿主（GM）的 {@code unlimited()} ⇒ 放行不多于父级。
   *
   * <p>★ <b>与"部分私密"的关系</b>：「不给某个 DM 公用日志」= <b>不给他挂</b> {@code sim-log}（{@link
   * #setSimLogExcludedDecisionMakers}）⇒ 本方法读面自动不覆盖它 ⇒ <b>零额外权限位</b>。
   */
  public static ResourceScopeMap withDecisionMakerDocsScopes(
      ResourceScopeMap base, String decisionMakerUserKey) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(decisionMakerUserKey, "decisionMakerUserKey");
    List<Mount> mounts = ExternalWorkspaceHost.INSTANCE.registry.mounts(decisionMakerUserKey);
    // 读面：名下全部挂载的落点（私有 + 公用）。
    List<Path> readableDirs = mounts.stream().map(Mount::dir).toList();
    // 写面：只有私有工作区（挂载名 == DEFAULT_WORKSPACE_NAME）那些落点；公用区 ⇒ 只读。
    List<Path> writableDirs =
        mounts.stream()
            .filter(mount -> DEFAULT_WORKSPACE_NAME.equals(mount.name().value()))
            .map(Mount::dir)
            .toList();
    ResourceScope readScope =
        readableDirs.isEmpty() ? ResourceScope.none() : ResourceScope.ofDirs(readableDirs);
    ResourceScope writeScope =
        writableDirs.isEmpty() ? ResourceScope.none() : ResourceScope.ofDirs(writableDirs);
    return base.withNamespace(DOCS_NAMESPACE, readScope)
        .withNamespace(DOCS_EDIT_NAMESPACE, writeScope);
  }

  /**
   * 工具面按角色分载（spec §八.3 的 N9/N11，**2026-09-22 起按 spec §2.1/§四.3 重划**）：**只有两档**。
   *
   * <p>★ **合并的理由**（用户 2026-09-22 原话：「MCP 和 GM Agent 处于同一权限级，想改什么改什么」）：旧枚举里 {@code EXTERNAL}（读 +
   * 通用写）与 {@code EXTERNAL_WITH_GM}（读 + 通用写 + 全部窄写）**表达不出两种不同的权限级别**， 分得越细越像"有边界"；并入 {@link #GM}
   * 之后，"MCP 口 = GM 组"是**一条**口径。旧值**删除、不留别名**（留别名 = 留一条 谁也不会再走的岔路，且 {@code switch} 会替它编译通过）。
   */
  public enum Role {
    /**
     * **GM 组**（= 运行时 MCP 口，spec §2.1）：读工具 + 通用写（submit/advance/fork）+ 全部窄写（sd 配权与开始决策、map 域、unit
     * 域）。
     *
     * <p>★ **与 SDSimos 裁定 N9 的冲突在此显式记账**：N9 的原意是「专用窄工具，不给决策 Agent 通用 {@code
     * simos.command.submit}」；GM 组保留通用写是**用户裁定 D2 的取舍、不是缺陷**（其持有者可绕过窄工具直接提交任意命令）。 N9
     * 在**决策人组**（{@link #DECISION_AGENT} 桶）与 {@code DecisionMaker.allowedTools} 白名单上**照旧有效**。
     */
    GM,
    /**
     * **决策人组**（spec §2.2/§四.3）：**只有决策行为**（{@code ga.IssueDirective} / {@code ga.SubmitVerdict}）+
     * 读工具。**无通用写、无 map/unit/sd 的写工具**（用户 2026-09-22：「决策人不能直接改地图等数据， 只能获取有限的、被 GM 权限层限制范围的信息」）——旧
     * D-1 裁定把 unit 域 20 条窄写挂进本桶，**已撤销**。
     */
    DECISION_AGENT
  }

  /**
   * ★★ 2026-10-12 B2b：工具源的**运行时承载**（静态快照 + {@code ToolSource} 三个出口）已迁入 {@code simos-ga} （{@link
   * GaToolSource}）；本类只剩**装配**（认识全部 16 个模块并 {@code new} 出工具实例）—— 那是组合根的活， 搬进 ga 就是 {@code app → ga →
   * app} 环（设计书 §5 I-G2）。
   */
  private final GaToolSource source;

  /**
   * ★★ <b>本档的可提议目录</b>（批 K ①，2026-10-15）：名单从**本档工具面**派生（{@code instanceof DecisionProposable}），
   * 实例与桶里是同一批（见 {@link #decisionProposalTools}）；装配完成处由 {@code ProposalDeclarationAudit} 审计。
   */
  private final ProposalCatalog proposalCatalog;

  /**
   * 测试/旧路径：全缺省时钟（不读 store）；生产 Shell 必须用带 {@link CalendarService} 的重载（{@link CalendarService#load}）。
   *
   * <p>决策人桶的装配（**不带目标表**）：{@link Role#DECISION_AGENT} 走这条——本桶不含 {@code sd.AdjudicateTick}（它是 GM
   * 的活），故不需要 {@link CommandTargets}，也不需要运行流。
   *
   * <p>★ GM 桶请用带 {@code commandTargets} 的那条：缺了它 {@code sd.AdjudicateTick} 会因"没有任何命令有目标声明"
   * 而**一律拒**（fail-closed，不会静默放行——但这显然不是想要的行为）。
   *
   * @param commandTypes E6b 起 = catalog 可见的**完整注册面**（含 GM-only）；只读
   * @param embeddableCommandTypes E6b 起 = **可嵌入令白名单的输入集**（注册面 − GM-only；{@code DirectiveWhitelist}
   *     会再过滤 {@code sd.*} 自指与通用写）；{@code CatalogTool} 的决策人可见性判据与 {@code sd.AdjudicateTick} 的
   *     whitelist 都用它（见 {@link #SimosToolSource} 的类注）
   */
  public SimosToolSource(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Path worldgenConfigFile,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig,
      Role role) {
    this(
        core,
        CalendarService.defaults(),
        query,
        initiator,
        mapId,
        worldgenConfigFile,
        commandTypes,
        embeddableCommandTypes,
        renderService,
        gmToolUsage,
        llmConfig,
        role);
  }

  /** 决策人桶的生产装配：历法时钟来自启动期 {@link CalendarService#load} 的同一实例。 */
  public SimosToolSource(
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId,
      Path worldgenConfigFile,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig,
      Role role) {
    this(
        core,
        calendarService,
        query,
        initiator,
        mapId,
        worldgenConfigFile,
        commandTypes,
        embeddableCommandTypes,
        renderService,
        gmToolUsage,
        llmConfig,
        Map.of(),
        role,
        null,
        null,
        null,
        // ★ 2026-10-14 批 G（GM 代打）：决策人桶那条路**不需要**执行面与决策人桶注册表 ⇒ 两处 null（同 pendingApprovals 的惯例）。
        null,
        null);
  }

  /**
   * 测试/旧路径（源码兼容）：全缺省时钟、审批面装配为「未接入」；生产 Shell 必须用带 {@link CalendarService} 的全参重载。
   *
   * <p>全参装配：读工具两档共享；写面各自不同（{@link Role#GM} = 通用写 ∪ 全部窄写）。
   *
   * @param commandTypes catalog 可见的完整注册面（含 GM-only；E6b 起与 embeddableCommandTypes 拆开）
   * @param embeddableCommandTypes 可嵌入令白名单的输入集（注册面 − GM-only；E6b 起供 {@code CatalogTool} 的决策人过滤与
   *     {@code sd.AdjudicateTick} 的 whitelist，两者各自经 {@code DirectiveWhitelist} 过滤自指/通用写）
   * @param commandTargets {@code type → 目标声明}（**必须**由同一份已注册 handler 清单派生，见 {@link CommandTargets}）
   * @param decisionAgent **只被 {@link Role#GM} 用到**（触发工具只在 GM 面）；该角色下为 null ⇒ **当场抛**
   *     （装配故障不静默兜底），{@link Role#DECISION_AGENT} 下**无关**（它没有触发工具，也不需要运行流）
   * @param worldgenConfigFile 世界生成器冻结输入 JSON 的路径（{@code simos.worldgen.initialize} 用；app 层拼，见
   *     {@code Shell}）。★ 只被 {@link Role#GM} 用到，但两档都要求非 null（省掉"哪档才要传"的静默分支）。
   */
  public SimosToolSource(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Path worldgenConfigFile,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig,
      Map<String, CommandTargets> commandTargets,
      Role role,
      DecisionAgentService decisionAgent) {
    // ★ 测试/旧路径（源码兼容）：全缺省时钟、审批面装配为「未接入」（执行期具名 UNAVAILABLE）；
    //   生产 Shell 必须用带 CalendarService 的全参构造器（CalendarService.load）。
    this(
        core,
        query,
        initiator,
        mapId,
        worldgenConfigFile,
        commandTypes,
        embeddableCommandTypes,
        renderService,
        gmToolUsage,
        llmConfig,
        commandTargets,
        role,
        decisionAgent,
        null,
        null);
  }

  /**
   * 测试/旧路径（源码兼容）：全缺省时钟（不读 store）、审批面装配为「未接入」⇒ 执行期具名 UNAVAILABLE； 生产 Shell 必须用带 {@link
   * CalendarService} 的重载（{@link CalendarService#load}）。
   */
  public SimosToolSource(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Path worldgenConfigFile,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig,
      Map<String, CommandTargets> commandTargets,
      Role role,
      DecisionAgentService decisionAgent,
      PendingApprovals pendingApprovals,
      ApprovalCoordinator approvalCoordinator) {
    this(
        core,
        CalendarService.defaults(),
        query,
        initiator,
        mapId,
        worldgenConfigFile,
        commandTypes,
        embeddableCommandTypes,
        renderService,
        gmToolUsage,
        llmConfig,
        commandTargets,
        role,
        decisionAgent,
        pendingApprovals,
        approvalCoordinator,
        // ★ 2026-10-14 批 G：测试/旧路径不接 GM 代打面（同审批面的"未接入"惯例）。
        null,
        null);
  }

  /**
   * 生产全参装配（含 GM 审批面接线）：读工具两档共享；写面各自不同（{@link Role#GM} = 通用写 ∪ 全部窄写）， GM 桶另挂两条审批控制面工具（{@code
   * simos.gm.approvals} / {@code simos.gm.approve}）；历法时钟来自启动期 {@link CalendarService#load} 的同一实例。
   *
   * <p>★ 审批依赖**只被 {@link Role#GM} 用到**：两条工具标了 {@link GmOnlyRead} / 只加在 {@link #addGmWrites}，
   * 决策人桶里不出现；{@link Role#DECISION_AGENT} 下传 null 是合法装配（那两条根本不会被构造到可执行面）。
   *
   * <p>★★ <b>2026-10-14 批 G（GM 代打）：{@code decisionToolCaller} / {@code decisionToolRegistry} 同样只被
   * {@link Role#GM} 用到</b>——它们是 {@code simos.gm.actAs} 的两个依赖（执行面 + <b>决策人桶</b>注册表）， 该工具只在 {@link
   * #addGmWrites} 注册；{@link Role#DECISION_AGENT} 下传 null 是合法装配（代打是 GM 的活，决策人不代打别人）。 ★ 装配顺序由 {@code
   * Shell.start} 保证：决策人桶（含注册表）与 {@code DecisionCallerFactory} 都在 GM 工具源**之前**建好。
   *
   * @param calendarService 启动期装载的历法服务（非空；四个 gov 写工具按它取时钟）
   * @param pendingApprovals 进程内唯一的审批登记表（GM 裁决口读写它；null = 未接入 ⇒ 工具回 {@code UNAVAILABLE}）
   * @param approvalCoordinator 决策人链的审批编排器（成功回执取 {@code effectiveDecision(...)} 算实际生效 scope； null =
   *     未接入 ⇒ 工具回 {@code UNAVAILABLE}）
   * @param decisionToolCaller <b>只有 GM 档用</b>：决策人权限链的执行面（生产 = {@code DecisionCallerFactory}， 见
   *     {@link DecisionToolCaller}）。决策人档传 null。
   * @param decisionToolRegistry <b>只有 GM 档用</b>：<b>决策人桶</b>的工具注册表（代打只能用那个 DM 够得着的工具）。 决策人档传 null。
   */
  public SimosToolSource(
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId,
      Path worldgenConfigFile,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig,
      Map<String, CommandTargets> commandTargets,
      Role role,
      DecisionAgentService decisionAgent,
      PendingApprovals pendingApprovals,
      ApprovalCoordinator approvalCoordinator,
      DecisionToolCaller decisionToolCaller,
      ToolRegistry decisionToolRegistry) {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(calendarService, "calendarService");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(worldgenConfigFile, "worldgenConfigFile");
    Objects.requireNonNull(commandTypes, "commandTypes");
    Objects.requireNonNull(embeddableCommandTypes, "embeddableCommandTypes");
    Objects.requireNonNull(gmToolUsage, "gmToolUsage");
    Objects.requireNonNull(commandTargets, "commandTargets");
    Objects.requireNonNull(role, "role");
    List<AgentTool> built =
        new ArrayList<>(
            readToolsFor(
                readTools(
                    core,
                    calendarService,
                    query,
                    initiator,
                    mapId,
                    commandTypes,
                    embeddableCommandTypes,
                    renderService,
                    gmToolUsage,
                    llmConfig,
                    pendingApprovals),
                role));
    ProposalCatalog catalog =
        switch (role) {
          case GM -> {
            addGenericWrites(built, core, initiator, mapId);
            yield addGmWrites(
                built,
                core,
                calendarService,
                query,
                initiator,
                mapId,
                worldgenConfigFile,
                embeddableCommandTypes,
                commandTargets,
                requireDecisionAgent(decisionAgent),
                pendingApprovals,
                approvalCoordinator,
                // ★ 2026-10-14 批 G（GM 代打）：这两个依赖只在这一档（GM）用得上——见全参构造器的 javadoc。
                requireDecisionToolCaller(decisionToolCaller),
                requireDecisionToolRegistry(decisionToolRegistry));
          }
          case DECISION_AGENT -> {
            // ★ 第 3 波第 3 步：决策人**只读**自己能看的决策结果（用户原话「允许决策人查看不同 tick 的不同决策结果」）。
            //   **只在决策人桶**——GM 侧看不到它（GM 有 GUI 面与 sd 只读全权，不靠这条）；它的可见性判据在
            //   RedactingQueryService#decisionResults（tags 含调用者自己）。
            GaToolQueries gaQueries = new AppGaToolQueries(query, mapId);
            GaToolIdentity gaIdentity = new AppGaToolIdentity();
            built.add(new DecisionResultsTool(gaQueries, gaIdentity));
            // ★ Docs 系统（2026-09-23）：决策人**只读**发给自己的设定文档。同样**只在决策人桶**（GM 侧有 GUI 的文档子页，
            //   可按任意决策人的视角预览实际可见集合）；可见性判据在 RedactingQueryService#docs
            //   （tags 含调用者自己 **或** affiliations 含调用者归属，两轴取并集）。
            built.add(new DecisionDocsTool(gaQueries, gaIdentity));
            yield addDecisionAgentWrites(built, core, calendarService, query, initiator, mapId);
          }
        };
    // ★★ 2026-10-14 AgentLib Docs/Workspace 接入（设计书 §4.2）：外来工具面的**唯一注册点**，两行照设计书逐字落。
    //   ① `doc_*` 7 条里 6 条声明 {GM, DM}（U2：「doc系列在指定工作区的情况下给dm自用」）⇒ 两桶都注册；
    //      而 `doc_void` 声明 {GM} ⇒ **只进 GM 桶**（下面按角色表过滤，不搞"整库无条件 addAll"）。
    //   ② 工作区 3 条声明 {GM}（U1：「工作区由gm维护」）⇒ 决策人那一趟过滤后一条不剩。
    //   ★★ 分桶**由角色表派生**（不另写一份"哪条进哪个桶"的清单）：那张表就是这 10 条工具"谁能用"的唯一拼写点
    //      （I1）——另立清单 = 第二处真相，本仓最贵的一类失效。
    //   ★ 两条都用同一份注册表（ExternalWorkspaceHost）：GM 挂出来的工作区，决策人那条路看得见——否则 P2/P3 不可达。
    //   ★★ 为什么不是"无条件 addAll"（设计书 §4.2 那两行照抄会炸）：`doc_void` 声明 {GM}，无条件塞进决策人桶会被
    //      下面那行 requireFace 当场抓住（探针实测原文见账本 §「实测：照抄设计书那两行会炸」）。
    built.addAll(adaptExternal(DocsTools.all(ExternalWorkspaceHost.registry()), role));
    built.addAll(adaptExternal(WorkspaceTools.all(ExternalWorkspaceHost.registry()), role));
    // ★★ 2026-10-12 B4（设计书 §4.4 / 不变量 I-G3）：**装配完成处一次性校验本桶** —— 桶里每条工具都必须实现
    //   SimosTool 且 roles() 含本桶角色；不一致**当场抛**（fail-closed），不是静默少注册、也不是 warn。
    //   ★ 双桶那一半（"标了 DM 却没注册进 DM 桶"）单桶看不见，由 Shell 用 ToolRoleAudit.requireFaces 校验
    //     （那里同时拿得到两桶）。★ 校验是 O(n) 一次，不是每条工具建的时候各查一遍。
    ToolRoleAudit.requireFace(built, role == Role.GM ? ToolRole.GM : ToolRole.DM);
    // ★★ 批 K ①（2026-10-15）：本档的**可提议目录**（名单从工具面派生）——暴露给 Shell，供装配完成处的
    //   `ProposalDeclarationAudit` 把「目录成员 ⇔ 工具面上的 DecisionProposable 实现者」逐条钉死（C1/C2）。
    this.proposalCatalog = catalog;
    this.source = GaToolSource.of(built);
  }

  /**
   * ★★ <b>本档的可提议目录</b>（批 K ①）：名单由工具面里实现 {@code DecisionProposable} 的工具派生 ⇒ 与 {@link #listTools()}
   * 是**同一批实例**（决策人桶注册进目录的那个 List）。
   *
   * <p>★ 装配期审计（{@code ProposalDeclarationAudit.requireProposable}）读它做 C1/C2；诊断与用例也用它。
   */
  public ProposalCatalog proposalCatalog() {
    return proposalCatalog;
  }

  /** GM 面必须带上决策人运行流（{@code ga.RunDecision} 没有它就只是半条工具）——缺了当场抛，不静默少一条。 */
  private static DecisionAgentService requireDecisionAgent(DecisionAgentService decisionAgent) {
    return Objects.requireNonNull(
        decisionAgent, "decisionAgent（GM 面含 ga.RunDecision，需要决策人 agent 运行流）");
  }

  /**
   * ★ 2026-10-14 批 G：GM 面必须带上决策人权限链的执行面（{@code simos.gm.actAs} 没有它就只能 fail-closed）——缺了当场抛。
   *
   * <p>★ 为什么是"当场抛"而不是"少一条工具"：代打是**唯一**的"以某个决策人的环境落一次调用"的口，静默少一条 = 那条路整个消失
   * 且不报错（本仓最贵的一类失效）。决策人桶不调本方法（那边传 null 是合法装配，见全参构造器 javadoc）。
   */
  private static DecisionToolCaller requireDecisionToolCaller(
      DecisionToolCaller decisionToolCaller) {
    return Objects.requireNonNull(
        decisionToolCaller, "decisionToolCaller（GM 面含 simos.gm.actAs，需要决策人权限链的执行面）");
  }

  /** ★ 同上：代打必须用**决策人桶**的注册表（用 GM 桶 = 绕过"它只能干那个 DM 能干的"，当场抛、不静默换桶）。 */
  private static ToolRegistry requireDecisionToolRegistry(ToolRegistry decisionToolRegistry) {
    return Objects.requireNonNull(
        decisionToolRegistry, "decisionToolRegistry（GM 面含 simos.gm.actAs，需要决策人桶的注册表）");
  }

  // ── 2026-10-14 外来工具面的适配三件套（角色 / gate / 注册）────────────────────────────────────

  /**
   * 把一批外来工具里<b>本桶该有的那些</b>逐条适配（{@link SimosToolAdapter}）：角色与 gate 都<b>逐条查表</b>。
   *
   * <p>★ <b>分桶口径</b>：角色表里声明了本桶角色的才进本桶（{@code {GM}} 的只进 GM 桶、{@code {GM, DM}} 的两桶都进）。 这条判据是"roles()
   * 是唯一拼写点"（I1）的直接后果——装配面不再手抄第二份"哪条进哪个桶"。
   *
   * <p>★ 名字与其余七条行为由适配器<b>逐字委托</b>（I3）；本方法只补"角色"和"审批"这两件外来工具面没有的事。 ★ 查不到角色 ⇒ {@link #rolesOf}
   * 当场抛（I6）。★ 他们的工具实例每次 {@code all(...)} 都是新的，但都指向同一份注册表。
   *
   * @param external 外来工具（{@code DocsTools.all(registry)} / {@code WorkspaceTools.all(registry)}
   *     的一批）
   * @param role 本趟装的是哪个桶（决定哪些工具进来）
   */
  private static List<AgentTool> adaptExternal(List<AgentTool> external, Role role) {
    ToolRole face = role == Role.GM ? ToolRole.GM : ToolRole.DM;
    List<AgentTool> adapted = new ArrayList<>(external.size());
    for (AgentTool tool : external) {
      String name = tool.name();
      Set<ToolRole> roles = rolesOf(name);
      if (!roles.contains(face)) {
        continue;
      }
      adapted.add(new SimosToolAdapter(tool, roles, gatePolicyFor(name)));
    }
    return adapted;
  }

  /**
   * 角色表查询（I6）：<b>查不到当场抛</b>——他们加一条工具，我们这边装配期就红，而不是静默给它一个默认角色。
   *
   * <p>★ 报错文案里带上补救动作（补进 {@link #EXTERNAL_TOOL_ROLES}），因为这条异常的意义就是"有人改了隔壁的工具面"。
   */
  private static Set<ToolRole> rolesOf(String toolName) {
    Set<ToolRole> roles = EXTERNAL_TOOL_ROLES.get(toolName);
    if (roles == null) {
      throw new IllegalStateException(
          "外来工具角色表里没有 "
              + toolName
              + "（AgentLib 侧新增或改名了工具？）—— 「这个工具能被谁用」必须逐条声明，"
              + "不许有缺省角色（设计书 §4.2 / I6）：请把它补进 EXTERNAL_TOOL_ROLES。");
    }
    return roles;
  }

  /**
   * 审批策略（设计书 §4.3）：读类 {@link ToolGate#ALLOW}；其余（4 条 doc 写 + 2 条工作区写） {@code Ask(工具名, 只含路径的 summary,
   * SENSITIVE)}。
   *
   * <p>★ <b>为什么审批口在我们手里</b>（反编译核实）：他们的 {@code DocTool} 只覆写了 {@code spec()} / {@code resources()} /
   * {@code execute()}——<b>没有覆写 {@code gate()}</b>，构造用的是 {@code ToolSpec.level(AccessToken.DEFAULT,
   * …)} ⇒ 他们的工具默认不要求审批。所以"只给路径"（U3）做得成： summary 由本方法<b>自己合成</b>，不转发他们的（他们没有可转发的）。
   *
   * <p>★ <b>诚实标注</b>：{@code Mount/Unmount} 在设计书 §4.3 的表里没有单列 gate（那张表只点了 doc 系列）——
   * 这里按同一条判据处理（"改盘上/注册表状态的都进审批链"，I4），GM 面那条链是 {@code GmAutoApproveGate} 无脑过 ⇒
   * 行为上无额外摩擦。已记入账本「与设计书的偏差」。
   */
  private static Function<ToolContext, ToolGate> gatePolicyFor(String toolName) {
    if (EXTERNAL_READ_TOOLS.contains(toolName)) {
      return context -> ToolGate.ALLOW;
    }
    return context ->
        new ToolGate.Ask(
            toolName, pathOnlySummary(toolName, context.arguments()), AskKind.SENSITIVE);
  }

  /**
   * ★★ <b>只含路径的审批摘要</b>（U3 用户裁定 / I5）：白名单取键 + <b>只认非空字符串值</b>。
   *
   * <p>★ <b>宁可少，绝不多</b>：任何"把整个 arguments 拼进去"的写法都会把正文 / 可见性轴 / 查询条件送进 GM 的审批面 （负向用例 N3
   * 抓的正是这一条）。取不到就回落到只有工具名——少信息是安全的失效方向。值不是字符串（模型可以给对象） 时<b>不 stringify</b>、直接跳过：那可能是正文的另一种壳。
   *
   * <p>★ 诚实的代价（设计书 §4.3 要求写进交付说明）：GM 审批外来文档写时<b>只看到路径 ⇒ 看不到正文</b>， 只能按"路径 +
   * 谁在写"判断，不能按内容判断。<b>这是用户的有意裁定（U3），不是缺陷</b>——但 GM 必须知道自己看不到什么。
   */
  private static String pathOnlySummary(String toolName, Map<String, Object> arguments) {
    List<String> pathKeys = APPROVAL_PATH_KEYS.get(toolName);
    if (pathKeys == null || arguments == null || arguments.isEmpty()) {
      return toolName;
    }
    StringBuilder summary = new StringBuilder(toolName);
    for (String key : pathKeys) {
      Object raw = arguments.get(key);
      if (!(raw instanceof String path) || path.isBlank()) {
        continue;
      }
      summary.append(' ').append(key).append('=').append(oneLine(path));
    }
    return summary.toString();
  }

  /** 路径压成一行（换行会把 GM 的"一行摘要"读成多行）并按 {@link #SUMMARY_FIELD_LIMIT} 截断。 */
  private static String oneLine(String path) {
    String flat = path.replace('\n', ' ').replace('\r', ' ').strip();
    return flat.length() <= SUMMARY_FIELD_LIMIT
        ? flat
        : flat.substring(0, SUMMARY_FIELD_LIMIT) + "…";
  }

  /** 通用写（spec §八.3）：submit / advance / fork —— 只有 {@link Role#GM} 有。 */
  private static void addGenericWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new CommandSubmitTool(core, initiator, mapId));
    built.add(new AdvanceTool(core, initiator, mapId));
    built.add(new ForkTool(core, initiator));
  }

  /**
   * GM 窄写（N11）：sd 域两条决策窄工具 + 配权工具（`ga.SetDecisionMakerAccess`）+ 「开始决策」（T10）+ **map 域与 unit 域的窄写**。
   *
   * <p>★ **窄写工具进的是 GM 组**（不是通用写、也不是决策人组）：它们的命令类型在工具里固定死，模型只能给载荷 ——与 {@code simos.command.submit}
   * 的"自选 type"相对（spec §八.3）。{@link Role#GM} 是运行时 MCP 口，因此口上也含这些。★ **T11C 的那条（{@code
   * ga.RunDecision}） 是唯一的例外形态**：它落一条触发事实之后**还要跑一轮真 LLM**，故 GM 面需要{@link
   * DecisionAgentService}（见那个构造器）。
   *
   * <p>★ **M3（spec §八.3）：sd 域余下的写命令也在此追加**——命令类型固定、与 map/unit 窄写同形。★ **新窄写一律加在这里**， 加完**不必回来改本注**
   * （**本注刻意不写条数**：数字是漂移源；M3 起本文件与 {@code Shell} 的工具面注释一律不钉条数）。
   */
  private static ProposalCatalog addGmWrites(
      List<AgentTool> built,
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId,
      Path worldgenConfigFile,
      Set<String> embeddableCommandTypes,
      Map<String, CommandTargets> commandTargets,
      DecisionAgentService decisionAgent,
      PendingApprovals pendingApprovals,
      ApprovalCoordinator approvalCoordinator,
      // ★ 2026-10-14 批 G（GM 代打）：两个依赖都只被 addGmWrites 里的 simos.gm.actAs 用（见全参构造器 javadoc）。
      DecisionToolCaller decisionToolCaller,
      ToolRegistry decisionToolRegistry) {
    // ★★ 2026-10-12 B3 第三/四域（economy + social）：经济/社会工具**已下放**（`economy.tools.write` /
    //   `social.tools.write`），这里只做装配——注入窄写口 + 窄读口 + 各自域声明的端口：
    //   ① 经济视角口（可见性围栏 + unit 编制预检 + 周期规则到期判据，唯一实现在 app）；
    //   ② 社会单位编制口（家户归属的权威在 unit 侧，social 结构上看不见 unit）。
    ToolWritePort toolWrites = new CoreSimosToolWritePort(core, initiator);
    ToolReadPort toolReads = new QueryServiceToolReadPort(query);
    // ★★ 2026-10-12 B3 第七域：sd/决策族的窄写与决策包工具**已下放**（`ga.tools.{read,write}`），这里只做装配
    //   ——写口/读口复用同一对适配器，另注入三条 ga 自有接缝（身份口、决策运行口、提议/执行口）。
    ToolWritePort gaWrites = new CoreSimosToolWritePort(core, initiator);
    ToolReadPort gaReads = new QueryServiceToolReadPort(query);
    GaToolIdentity gaIdentity = new AppGaToolIdentity();
    GaToolQueries gaQueries = new AppGaToolQueries(query, mapId);
    GaDecisionHost gaDecision = new AppGaDecisionHost(decisionAgent);
    EconomyToolPerspective economyPerspective = new AppEconomyToolPerspective();
    SocialUnitPort socialUnits = new AppSocialUnitPort();
    built.add(new IssueDirectiveTool(gaWrites, gaIdentity, mapId));
    built.add(new SubmitVerdictTool(gaWrites, gaIdentity, mapId));
    built.add(new SetDecisionMakerAccessTool(gaWrites, gaIdentity, mapId));
    // ★★ 2026-10-14 决策人互致消息（设计书 §4.1）：GM 授权两个决策人互通 —— 一次调用写**两条** DM 记录、
    //   落**一条 revision** ⇒ 授权天然双向（不存在"A 能发 B、B 回不了"的半开态）。**只在 GM 桶**（配权是 GM 的活；
    //   决策人不得给自己开闸）。底层机制仍是 accessLimit（零新存储/零新可见性/零新切片）。
    built.add(new SetDecisionMakerPeerTool(gaWrites, gaIdentity, mapId));
    // ★★ 2026-10-14 GM⇄DM 双向信件（设计书 §3.1，缺口 G2）：GM **回信**——给一个**已存在**的决策人发一条消息。
    //   **只在 GM 桶**（roles={GM}，不是 {DM,GM}：DM 侧有它自己的 simos.ga.message.send 那条）；地址/条目 id/上限
    //   全部从 GaMessageSendTool 的静态助手取（那个类是信件形状的唯一拼写点，本行不复制第二份）。
    //   落点是 info 感知层（一条 ga.PutInfo ⇒ 一条 revision），不写世界命名空间。
    built.add(new GmMessageSendTool(gaWrites, gaReads));
    // ★★ 2026-10-14 批 G（GM 代打，设计书 §3.7 + §3.7.1）：GM 以**某个决策人的环境**调一次任意工具（从外部把真人玩家的东西输进去）。
    //   ★★ 这条装配的要害是**两个"用哪个桶"的选择**，两处都必须是决策人那一侧：
    //     ① 执行面 = DecisionCallerFactory（callerFor 按那个 DM 建身份 + 现算权限组）；
    //     ② 注册表 = decisionToolRegistry（**决策人桶**，不是 GM 桶）⇒ 代打只能干那个 DM 能干的。
    //   ★ 判据不许放宽（红线）：那个 DM 够不着就照实回 TOOL_NOT_FOUND / PERMISSION_DENIED / RESOURCE_DENIED。
    //   ★ 只在 GM 桶（roles={GM}）：代打是 GM 的活，决策人不代打别人；★ 工具名不是命令类型 ⇒ 不进 catalog。
    //   ★ 它自己不碰世界命名空间（resources()=NONE）：内层工具各有各的 manifest，且在那个 DM 的权限集下判（理由见该工具类注）。
    built.add(new GmActAsTool(decisionToolCaller, decisionToolRegistry, gaReads, mapId));
    // 「把某个决策人的会话换一段新的」：只在 GM 桶——决策人不重置自己（那等于给自己开一个"忘掉刚才答应过什么"的按钮）。
    built.add(new ResetDecisionMakerConversationTool(gaWrites, gaIdentity, mapId));
    built.add(new StartDecisionTool(gaWrites, gaIdentity, mapId));
    // T11C：触发**决策人自己**跑一轮（真 LLM + 真工具）。★ **只在 GM 桶**——决策人不触发自己（那是自环）。
    built.add(new RunDecisionTool(gaWrites, gaIdentity, mapId, gaDecision));
    // ★★ P7a（2026-10-01 后端 + MCP 稳定化计划）：显式名单批量派决策人（GM-only 组合工具）。
    //   不做自动筛选/自动派出：due 只读展示、不参与选择；名单由调用方显式给。先同批落 N 条 ga.RunDecision 触发事实
    //   （一条 revision），批成功后按名单顺序逐个调运行流跑真轮；批拒/冲突零 revision 且不跑 LLM。
    //   本工具不做跨调用锁、不做自动重试；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    //   ★★ 2026-10-12 A1（ga 摘掉 core 依赖）：本行起共 8 处装配改吃同一只窄写口 `gaWrites` —— 已下放到 ga 的
    //      工具不再看得见 `CoreSimos`（命令 id / correlationId / initiator 仍由 `CoreSimosToolWritePort`
    // 装配并回报）。
    built.add(new RunDecisionMakersTool(gaWrites, gaDecision, gaQueries));
    // 第 3 波第 2 步：把某 tick 里所有决策人的令**一起**判效果、一次落一条 revision（原子）。
    //   ★ **只在 GM 桶**（裁决是 GM 的活）；★ 它**不是**一条命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    //   ★★ E6b：它吃的是**可嵌入令**白名单（注册面 − GM-only）——economy.SwitchMode / economy.GmAdjust
    //     不会作为决策命令被代执行。
    built.add(
        new AdjudicateTickTool(core, initiator, mapId, embeddableCommandTypes, commandTargets));
    // 2026-09-23 用户裁定「只有生效裁决和作废裁决」：把某 tick 的裁决**作废**——世界回滚 + 令退回待裁决 + 记录标 VOIDED，
    //   一条 revision 原子（走 core.submitRestore）。★ **只在 GM 桶**；★ 也不是命令类型 ⇒ 不进 catalog。
    built.add(new VoidAdjudicationTool(core, initiator));
    // 2026-09-23 用户裁定（"决策人一般流程"第 2 件）：GM 打回一条令 —— 理由原样**投进该决策人的会话**（复用 say 通道）
    //   + 该令标 CANCELLED，同批留审计条目。★ 只在 GM 桶；★ 也不是命令类型 ⇒ 不进 catalog。
    built.add(new RejectDirectiveTool(gaWrites, gaDecision, gaIdentity));
    // 2026-09-23：GM 世界初始化 —— 把聚落生成器接到真写路径（1 条 SetPopulation + N 条 CreateCity + 1 条 economy.Seed，
    //   一批一条 revision；dryRun 只算不写）。★ 它不是某一条命令的窄封装 ⇒ 不继承 AbstractNarrowWriteTool；★
    //   也不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。只在 GM 桶。
    built.add(new WorldgenInitializeTool(core, initiator, mapId, worldgenConfigFile));
    // ★★ P1a1（2026-10-01 后端 + MCP 稳定化计划）：任意 Region 数据播种（生成模式）——复用同一份
    //   SettlementGenerator / PopulationSeeder / EconomySeeder，固定批序同 WorldgenInitializeTool，
    //   clean gate 命中 ⇒ NEEDS_CLEAR + 零 revision。**只在 GM 桶**；★ 工具名不是命令类型 ⇒ 不进 catalog。
    built.add(new RegionSeedTool(core, query, initiator, mapId));
    // ★★ P1b1（2026-10-01）：区域数据清空（social/actor/economy 三类，固定批序 + 只对有命中域下单）。**只在 GM 桶**；
    //   ★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；map 只读（校验 region 存在），写面声明 social/actor/economy/sd。
    built.add(new RegionClearDataTool(core, query, initiator, mapId));
    // ★★ 2026-10-12 B3 第六域（gov）：36 个 gov 工具 + 省份划分器**已下放**
    //   （`io.mosire.simos.gov.tools.{read,write}`），这里只做装配——注入 ga-spi 的窄写口/窄读口 + gov 自己声明的
    //   六条接缝（一个接缝束 `GovToolSupport`）：
    //   ① 视图口（ApiViews.economyGovernment / 供给桥 / tick 流量表 / AvailableStock 三项唯一实现）；
    //   ② 视角口（身份派生 + 区域围栏 + sd 决策人事实 + 国库家户解析 + 逃亡档位；围栏不抄第二份）；
    //   ③ 经济口（政府政策现值/解析/合并 + 币种/政府记录/经济行/危机信号；economy 实现不在 gov 允许面）；
    //   ④ 跨域命令类型口（economy/actor/sd 的 handler 拼写点）；
    //   ⑤ 人力口（选人瀑布 + 家户劳动量；历法时钟由适配器持有）；
    //   ⑥ 命令类型/写口/读口同上（命令 id / 发起者 / 信封由 app 装配）。
    GovToolSupport gov =
        new GovToolSupport(
            new CoreSimosToolWritePort(core, initiator),
            new QueryServiceToolReadPort(query),
            new AppGovToolViews(),
            new AppGovToolPerspective(),
            new AppGovToolEconomy(),
            new AppGovToolCommandTypes(),
            new AppGovToolManpower(calendarService));
    // ★★ P1b2（2026-10-01）：区域结构清空（GOV 单位/对应决策人/建议命名形制的省 Region，固定批序 + 开关 + 只读
    //   pre-scan）。**只在 GM 桶**；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面声明 map/unit/sd。
    built.add(new RegionClearStructuresTool(gov, mapId));
    // ★★ P3（2026-10-01）：省份一键落盘（复用 simos.province.divide 的同一份只读建议；固定批序落
    //   Region + GOV Unit + 决策人；相关结构门 NEEDS_CLEAR / 重叠门 OVERLAP_OVERRIDE_REQUIRED，零 revision）。
    //   **只在 GM 桶**；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面声明 map/unit/sd。
    built.add(new ProvinceApplyTool(gov, mapId));
    // ★★ R2a（2026-10-01 行政区划修复计划 §1.2/§R4）：按 SocialCity.at 批量归省——候选省/首都区取
    //   hexCount 最小、并列按 regionId 字典序；一批 social.UpdateCity 共享 batchId ⇒ 恰一条 revision。
    //   **只在 GM 桶**；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面只声明 social。
    built.add(new ProvinceAssignCitiesTool(gov));
    // ★★ 2026-10-12 B3 试点（设计书 §4.3 / 台账 §5）：地图的 8 条窄写**已下放到 simos-map**
    //   （`io.mosire.simos.map.tools.write`），这里只剩装配——注入 ga-spi 的窄写口（适配器在 app，见
    //   `CoreSimosToolWritePort`：命令 id / 发起者 / 信封由它装配，工具不再直连 CoreSimos）。
    ToolWritePort mapWrites = new CoreSimosToolWritePort(core, initiator);
    // ★★ 2026-10-12 B3 第五域（unit，最大的域）：28 条 unit 窄写 + 3 条 GM 组合工具**已下放**
    //   （`io.mosire.simos.unit.tools.write`），这里只做装配——注入 ga-spi 的窄写口/窄读口（适配器在 app，
    //   命令 id / 发起者 / 信封由它装配）+ unit 自己声明的五类端口：
    //   ① 视图口（ApiViews.unit / ToolSupport 的 hexCoord / compositionView / compositionEntries 同一份实现）；
    //   ② 视角口（可见性围栏 + sd 侧 army id 占用 + GOV 国库家户解析 + 落点城乡判据，四者唯一实现在 app）；
    //   ③ 分摊口（家户账瀑布 + share-aware 选人层；唯一实现在 app.tools.write，历法时钟由适配器持有）；
    //   ④ 跨域命令类型口（economy.RegisterHousehold / actor.EnsureHouseholdAccount 的拼写点在各自 handler）。
    ToolWritePort unitWrites = new CoreSimosToolWritePort(core, initiator);
    ToolReadPort unitReads = new QueryServiceToolReadPort(query);
    UnitToolViews unitViews = new AppUnitToolViews(calendarService);
    UnitToolPerspective unitPerspective = new AppUnitToolPerspective();
    UnitToolAllocations unitAllocations = new AppUnitToolAllocations(calendarService);
    UnitToolCommandTypes unitCommandTypes = new AppUnitToolCommandTypes();
    built.add(new MapSetTerrainTool(mapWrites, mapId));
    // ★★ 2026-10-12 批次 2（hex 属性表）：GM 改单格可用地面积（map.SetHexArea，命令不打 {@code DmUsableCommand}
    //   ⇒ 决策人令 / RegisterEffect / 决策人 catalog 三条路径都到不了；**只在 GM 桶**）。──
    built.add(new MapSetHexAreaTool(mapWrites, mapId));
    built.add(new MapSetEdgeTool(mapWrites, mapId));
    built.add(new MapCreateRegionTool(mapWrites, mapId));
    built.add(new MapUpdateRegionTool(mapWrites, mapId));
    // ★★ R4（2026-10-01 行政区划修复计划 §1.4）：GM 改区域名的窄面——命令 map.RenameRegion 不打 {@code DmUsableCommand}
    //   ⇒ 决策人令 / RegisterEffect / 决策人 catalog 三条路径都到不了；GM 直接提交与这条窄工具照常可用。
    //   **只在 GM 桶**；改名只动 name，不动内容与边界。──
    built.add(new MapRenameRegionTool(mapWrites, mapId));
    built.add(new MapDeleteRegionTool(mapWrites, mapId));
    built.add(new MapRandomizeRegionTool(mapWrites, mapId));
    built.add(new MapRegisterPathwayGroupTool(mapWrites, mapId));
    // M2（spec §八.3）：unit 域窄写 —— ★ **只在 GM 组**（D-1 曾让它们也挂决策人桶，2026-09-22 已撤销）。
    built.add(new UnitRenameTool(unitWrites, mapId));
    built.add(new UnitCreateTool(unitWrites, mapId));
    built.add(new UnitReparentTool(unitWrites, mapId));
    built.add(new UnitSetCompositionTool(unitWrites, mapId));
    built.add(new UnitPlaceAtTool(unitWrites, mapId));
    built.add(new UnitPlanRouteTool(unitWrites, mapId));
    built.add(new UnitCancelRouteTool(unitWrites, mapId));
    built.add(new UnitDisbandTool(unitWrites, mapId));
    built.add(new UnitSetStatusTool(unitWrites, mapId));
    // ★★ D1（2026-10-02 / D-012）：当前回合状态 ↔ 状态描述地址的窄写。**只在 GM 桶**；★★ 2026-10-13 批次 4
    //   起命令**摘掉**了 {@code DmUsableCommand} ⇒ 决策人也不能再经令嵌入。★ 工具名不是命令类型 ⇒
    //   不进 catalog/PAYLOAD_HINTS。
    built.add(new UnitSetStateDescriptionTool(unitWrites, mapId));
    built.add(new UnitAttachTool(unitWrites, mapId));
    built.add(new UnitDetachTool(unitWrites, mapId));
    built.add(new UnitReparentSubtreeTool(unitWrites, mapId));
    built.add(new UnitSetFormationOffsetTool(unitWrites, mapId));
    built.add(new UnitSplitFormationTool(unitWrites, mapId));
    built.add(new UnitMergeFormationTool(unitWrites, mapId));
    built.add(new UnitPlanSparseRouteTool(unitWrites, mapId));
    built.add(new UnitSetRejoinTargetTool(unitWrites, mapId));
    built.add(new UnitCreateCommandChainTool(unitWrites, mapId));
    built.add(new UnitUpdateCommandChainTool(unitWrites, mapId));
    built.add(new UnitApplyCasualtiesTool(unitWrites, mapId));
    // ★★ D3a（2026-10-02 / D-009 补裁）：GM 对单独 Unit 人力/装备直接变动的调试直通口（有符号增量，一条命令原子）。
    //   **只在 GM 桶**；命令本身**不打** {@code DmUsableCommand}（控制器 2026-10-02 裁定收紧：它是 GM 调试直改原语 ⇒
    //   不进令白名单 / {@code RegisterEffect} / 决策人命令目录；回代码核实见 {@code AdjustCompositionHandler} 类注与
    //   {@code UnitAdjustCompositionTool} 类注 —— 两处都写"不打"）。★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    built.add(new UnitAdjustCompositionTool(unitWrites, mapId));
    // 辖区阶段 5（2026-09-30）：管辖区域集合 + 长期税率。**只在 GM 桶**（与既有 unit 窄写同待遇）。
    built.add(new UnitSetJurisdictionTool(unitWrites, mapId));
    built.add(new UnitSetTaxRateTool(unitWrites, mapId));
    // ★★ R2a（2026-10-01 行政区划修复计划 §1.2/§R4）：social 域窄写——改城市 name/props/region 的唯一窄面
    //   （region 键缺席=保持、字符串=设值、null=清空）。**只在 GM 桶**；命令 handler 的 targetPaths 仍为空。
    built.add(new SocialUpdateCityTool(toolWrites, mapId));
    // 阶段 10a（2026-09-30 GOV/Army 计划）：两条"立编制"命令的窄封装（编制字段在 Unit.module ⇒ 是 unit 域命令）。
    //   **只在 GM 桶**；决策人侧要改编制仍走 ga.IssueDirective 的审批链（这两条命令打 {@code DmUsableCommand}，可嵌入令）。
    built.add(new UnitSetGovernmentFormationTool(unitWrites, mapId));
    built.add(new UnitSetArmyFormationTool(unitWrites, mapId));
    // 阶段 10b-i（2026-10-01 GOV 计划）：GOV 政策 / 上级层级两条窄工具。**只在 GM 桶**。
    //   ★ **有意不为 unit.RecruitStaff / unit.DismissStaff 配窄工具**：那会变成"凭空造人 / 跳过退休支付"的
    //   直通口。两条命令本身已注册；★★ 2026-10-13 批次 4 起**摘掉** {@code DmUsableCommand}（② 建军/凭空造人）
    //   ⇒ 决策令面也不再含它们，配套工具批（createOffice / recruit / dismiss）经 GM 桶提交。
    //   ★ 离编人员的**社会回写**明确留阶段 13（见 GovDismissTool/GovDismissPlan 类注的具名缺口）。
    built.add(new UnitSetGovPolicyTool(unitWrites, mapId));
    built.add(new UnitSetGovSuperiorTool(unitWrites, mapId));
    // 阶段 10b-ii（2026-10-01 GOV 计划）：三条 GOV 组合工具（建 GOV+绑决策人 / 招募 / 离编+退休待遇）。
    //   **只在 GM 桶**；★ 三个工具名都不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；各自走 CoreSimos.submitBatch
    //   一批一条 revision（批内固定顺序与载荷见各自 *Plan 类）。
    built.add(new GovCreateOfficeTool(gov, mapId));
    built.add(new GovRecruitTool(gov, mapId));
    built.add(new GovDismissTool(gov, mapId));
    // 阶段 13A（2026-10-01 GOV/Army 计划 §2.6 人员流转）：四条 GOV 组合工具——
    //   selectExaminees（辖区选人 → 无标签纯人员单位，可规划到目的 GOV）、dispatchTeam（GOV 编制出人 → 纯人员单位，
    //   armed 时加 ArmyFormation）、absorbUnit（吸收纯人员单位进编制，可解散已空源）、retireStaff（离编 + 退休待遇 +
    //   社会回写）。**只在 GM 桶**；★ 四个工具名都不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；各自走
    //   CoreSimos.submitBatch 一批一条 revision（批内固定顺序与载荷见各自 *Plan 类）。
    //   ★★ P1.5（2026-10-16）：selectExaminees / dispatchTeam 已接完整 Social 工单路径（新人口家户 hh-unit:<id>），
    //   两个计划类都需要生产历法时钟判定 MALE+ADULT 年龄档。
    built.add(new GovSelectExamineesTool(gov, mapId));
    built.add(new GovDispatchTeamTool(gov, mapId));
    // ★ 2026-10-19 absorbUnit 接 Social 家户真转移：来源选人层要按生产历法判 MALE+ADULT 年龄档 ⇒ 同传 calendarService。
    built.add(new GovAbsorbUnitTool(gov, mapId));
    built.add(new GovRetireStaffTool(gov, mapId));
    // 辖区阶段 6（2026-09-30 / 计划 §6.2）：actor 净增量账原语（app 级抽取/组军组合工具的落账腿）。
    //   **只在 GM 桶**：命令类型固定，模型只能给载荷；整条原子由域层判。
    built.add(new ActorAdjustAccountsTool(core, initiator, mapId));
    // ★★ R3a（2026-10-01 行政区划修复计划 §1.3/§R4）：GM 显式 GOV 国库上缴 / 转移窄工具——
    //   两个 GOV 当刻有效位置的国库账之间一条 actor.RemitGovTreasury（源扣目标加、一条 revision、整条原子）。
    //   **只在 GM 桶**；允许任意两个 GOV（不要求 to 是 from.superiorGov）；工具名不是命令类型 ⇒ 不进 catalog。
    //   写面只声明 actor 命名空间（GM 侧 unlimited）。
    built.add(new GovRemitTool(gov));
    // ★★ Z3c-2（2026-10-23 政府服务模式，GM + 决策人两面并列）：五条政府配置窄写 + 一条国库转账 GM 窄写。
    //   五条配置工具在 GM 桶与决策人桶**同名两处注册**（决策人侧另加白名单与审批链）；GM 侧走 GmAutoApproveGate。
    //   身份派生见 GovToolSupport：决策人只能自己的 GOV，载荷指定别的 GOV ⇒ 具名 REJECTED。
    //   ★ 两 gov 命令都不打 DmUsableCommand（声明只喂 ga 规则层派生的令/RegisterEffect/catalog 白名单，命令总线不拦）：
    //     决策人窄工具直接提交同一命令受控，而令/RegisterEffect/catalog 四条路径不放大。
    built.add(new GovSetEstablishmentTool(gov));
    built.add(new GovSetBudgetPolicyTool(gov));
    built.add(new GovAssignPostsTool(gov));
    built.add(new GovExpandHouseholdTool(gov));
    //   ★★ Z3d（2026-10-23）：外部户承接行政任务（B 路 V1=显式选户）——只写 externalPosts + GOV_SERVICE 承诺，
    //     绝不改 Unit.households / Social 位置；GM 与决策人同名两处注册 + 审批链（同上面五条配置工具）。
    built.add(new GovOpenPostsToMarketTool(gov));
    //   ★ 国库注资 / 政府间转账（F2 前置 G1）：只在 GM 桶；决策人路径走既有 simos.gov.pay。
    built.add(new GovTransferTreasuryTool(gov));
    //   ★★ A1（2026-10-08 汇率阶段 2 §3.1-3）：货币身份三件套（定义币种 / 改名 / DM 发行）——**GM 桶 + 决策人桶
    //     同名两处注册**（决策人侧另加审批链，见 addDecisionAgentWrites；决策人白名单即由那个桶派生）。
    //     GM 侧走 GmAutoApproveGate 且必须显式给 govUnitId；决策人侧身份派生、只能自己的 GOV（越权 ⇒ 具名 REJECTED）。
    //     ★ 三条命令（economy.DefineCurrency / RenameCurrency / RecordMoneyIssuance）不打 {@code
    // DmUsableCommand} ⇒
    //       令 / RegisterEffect / 决策人命令目录三条路径不放大；工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    built.add(new GovDefineCurrencyTool(gov));
    built.add(new GovRenameCurrencyTool(gov));
    built.add(new GovIssueMoneyTool(gov));
    //   ★★ A2a（2026-10-08 汇率阶段 2 §3.3）：官方汇率 —— 同一形制（GM 桶 + 决策人桶同名两处注册 +
    //     决策人侧白名单/审批链）。★ 定下它就是"激活这个币对的外汇市场"（没定过 ⇒ 没有外汇面，逐值退回）。
    built.add(new GovSetFxRateTool(gov));
    // ★★ R4 / R5 步骤 4（2026-10-01 行政区划修复计划）：GM 按辖区行政需求精确配满编组合工具——逐 GOV 调
    //   GovDemand.of 求 security/paperwork 总量，目标 staff{YAMEN=security, SCRIBE=paperwork}，每个要改的 GOV
    //   一条 unit.SetGovFormation（policy 五字段原样带全），一批共享 batchId ⇒ 恰一条 revision。
    //   **只在 GM 桶**；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面只声明 unit。
    built.add(new GovApplyStaffingTool(gov));
    // 辖区阶段 6（2026-09-30 / 计划 §6.1）：GM 组合工具——一次抽粮/钱/人力，三条命令同批落一条 revision。
    //   **只在 GM 桶**；★ 它不是一条命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。actor.AdjustAccounts 不打
    //   DmUsableCommand ⇒ 这条组合工具的上限/管辖区口径不会被"嵌进决策令"绕过。
    built.add(
        new LevyRegionTool(unitWrites, unitReads, unitViews, unitPerspective, unitAllocations));
    // 辖区阶段 8（2026-09-30 / 计划 §5）：组军 GM 组合工具——从地方抽人力 + 抽粮/钱，同批 unit.CreateUnit +
    //   actor.AdjustAccounts + social.SeedGroups + ga.PutInfo，四条命令同批落一条 revision。**只在 GM 桶**；
    //   ★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；分摊与 levyRegion 共用 RegionAllocations 一份瀑布。
    built.add(
        new RaiseUnitTool(
            unitWrites, unitReads, unitViews, unitPerspective, unitAllocations, unitCommandTypes));
    // P5（2026-10-01 后端 + MCP 稳定化计划）：按格直接建军 GM 组合工具——GM 特权不抽人口/粮饷，同批
    //   unit.CreateUnit + [role 非空: unit.SetArmyFormation] + army.CreateArmy + ga.PutInfo，一条
    // revision。
    //   **只在 GM 桶**；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；raiseUnit 的抽取语义不变。
    built.add(
        new SpawnArmyTool(unitWrites, unitReads, unitViews, unitPerspective, unitCommandTypes));
    // ★★ 2026-10-12 B3 第二域（army，设计书 §4.3 / 台账 §5 + 试点 §6.5）：army 的编排工具**已下放到
    //   `simos-army`**（`io.mosire.simos.army.tools.{read,write}`），这里只剩装配——注入四类接缝：
    //   ① 窄写口（命令 id / correlationId / initiator 由 `CoreSimosToolWritePort` 生成并**回报**，见
    // ToolWritePort.BatchReceipt）；
    //   ② 窄读口（QueryService 的三方法面，含 head 解析/创世等待/读缓存）；
    //   ③ 视图口（ApiViews / ToolSupport 的**同一份** view 函数，形状不另写第二份）；
    //   ④ 视角口 + 选人口（可见性围栏 + sd 侧 Army 事实 + MALE+ADULT 家户份额瀑布，实现都只有一份）。
    ArmyToolViews armyViews = new AppArmyToolViews();
    ArmyToolPerspective armyPerspective = new AppArmyToolPerspective();
    ArmyManpowerPort armyManpower = new AppArmyManpowerPort(calendarService);
    // 阶段 12 后续赋值缺口（2026-10-01）：Army 创建后的主子改派/解除 GM 组合工具——unit.SetArmyFormation
    //   → army.SetArmyMasterGov → ga.PutInfo 同批落一条 revision，避免 army / unit 两侧主子漂移。**只在 GM
    //   桶**；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    built.add(new AssignArmyGovTool(toolWrites, toolReads, armyPerspective));
    // M3（spec §八.3）：sd 域余下的写命令 —— ★ **只在 GM 桶**（决策人桶有意不含这批）。
    built.add(new SdCreateNationTool(gaWrites, gaIdentity, mapId));
    built.add(new SdCreateArmyTool(gaWrites, gaIdentity, mapId));
    built.add(new SdSetArmyMasterGovTool(gaWrites, gaIdentity, mapId));
    built.add(new SdCreateDecisionMakerTool(gaWrites, gaIdentity, mapId));
    built.add(new SdPutInfoTool(gaWrites, gaIdentity, mapId));
    // ★★ D2（2026-10-22 决策包计划）：GM 裁决决策包（写）。**只在 GM 桶**；工具名不是命令类型
    //   ⇒ 不进 catalog/PAYLOAD_HINTS；资源声明 sd（GM 侧 unlimited）。
    built.add(new GmDecidePacketTool(gaWrites, gaReads));
    // ★★ D3（2026-10-22 合并计划契约 §9）：三条 GM 决策效果工具——建计划 / 执行计划 / 执行已批准 call。
    //   **只在 GM 桶**；工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；资源声明 sd（GM 侧 unlimited）；
    //   执行器复用决策人桶同源的 ProposalCatalog（真工具实例 + 目标清单 fail-closed）。
    // ★★ 批 K ①（2026-10-15）：目录的名单**从工具面派生**（`instanceof DecisionProposable`），不再手抄 16 条 ——
    //   这里取**可提议工具族的唯一构造点**（{@link #decisionProposalTools}），与决策人桶注册的是同一批类/同一份接缝束。
    ProposalCatalog gmProposalCatalog =
        new ProposalCatalog(
            decisionProposalTools(core, calendarService, query, initiator, mapId), mapId);
    GaProposalHost gaProposal = new AppGaProposalHost(gmProposalCatalog);
    GaEffectExecution gaEffects = new AppGaEffectExecution(core, gmProposalCatalog);
    built.add(new GmMergedPlanUpsertTool(gaWrites, gaReads, gaProposal));
    built.add(new GmMergedPlanApplyTool(gaReads, initiator, gaEffects));
    built.add(new GmPacketExecuteTool(gaReads, initiator, gaEffects));
    // ★★ D5（2026-10-02 / D-003、D-005、R6）：外交关系边 + 外交事件的 GM 窄写。
    //   **只在 GM 桶**；工具名（下面 5 条 = 3 条 ga.* + 2 条 simos.sd.*）不是命令类型 ⇒ 不进
    //   catalog/PAYLOAD_HINTS；写面只声明 ga（2026-10-14 批次 F 由 sd 改名；此处原文写「工具名（simos.sd.*）」，与实况失真，一并纠正）。
    //   决策人侧另有同名命令型的窄工具（见 addDecisionAgentWrites）。──
    built.add(new SdSetDiplomaticRelationTool(gaWrites, gaIdentity, mapId));
    built.add(new SdRecordDiplomaticEventTool(gaWrites, gaIdentity, mapId));
    built.add(new SdRegisterEffectTool(gaWrites, gaIdentity, mapId));
    built.add(new SdCancelEffectTool(gaWrites, gaIdentity, mapId));
    built.add(new SdSetDecisionMakerProviderTool(gaWrites, gaIdentity, mapId));
    // ★★ D1（2026-10-02 / D-012）：army 切片的交战记录写入（GM-only 命令 army.RecordCombat 的窄封装）。
    //   **只在 GM 桶**；★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面只声明 army 命名空间。
    built.add(new ArmyRecordCombatTool(toolWrites, mapId));
    // ★★ D4（2026-10-02 / D-009 补裁 + D-010 + D-012；P2 2026-10-13 人员伤亡回写 Social）：Army 编排层的两条组合
    //   工具——让单位进入交战（RecordCombat + 逐单位 SetStateDescription，一批一条 revision）与结算一个阶段
    //   （逐单位 SubmitHouseholdWorkOrder + AdjustComposition + ResolveCombatStage +（全阶段判定完）清除状态链接，
    //   一批一条 revision）。**只在 GM 桶**；两条工具名都不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面各自声明
    //   social/unit/army 命名空间（与批内命令逐条对齐）。
    built.add(new StartCombatTool(toolWrites, toolReads, armyViews));
    built.add(new ResolveCombatTool(toolWrites, toolReads, armyViews, armyManpower));
    // ★★ D3a（2026-10-02 / D-010 + 补裁 R2 最小环）：Army 侧"按统一格式整表复写单位人力/装备"的工具链入口——
    //   按格式（有序条目 + 可选 jitter/上下限 + 可选 seed）算出目标表，经 unit.SetComposition 整表复写，
    //   同批写 ga.PutInfo（含生效 seed，可复现证据）。**只在 GM 桶**；工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    built.add(new FormatUnitTool(toolWrites, toolReads, armyViews));
    // ★★ E6b：GM 经济调整（economy.GmAdjust 的窄封装：预览 / 原因 / 前后差异 / 审计）。
    //   **只在 GM 桶**：决策人桶（addDecisionAgentWrites）没有它（白名单派生自那个桶，故也不会有它），
    //   且 economy.GmAdjust 本身不打 {@code DmUsableCommand}（令/RegisterEffect/决策人 catalog 三条路径都排除）。
    built.add(new EconomyAdjustTool(toolWrites, toolReads, mapId));
    // ★★ Z1a（2026-10-23 政府专属生产方式设计 §5）：产业模板创建/修改（economy.UpsertIndustry 的窄封装：
    //   payloadJson 与 economy.Seed 的 industries[] 节点同形 + preview / apply + expectedRevision）。
    //   **只在 GM 桶**：决策人桶没有它（白名单派生自那个桶）⇒ 也不会含它；命令本身不打 {@code DmUsableCommand}
    //   ⇒ 令 / RegisterEffect / 决策人 catalog 三条路径同样排除。★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    built.add(new EconomyUpsertIndustryTool(toolWrites, toolReads));
    // ★★ Z1c（2026-10-23 政府专属生产方式设计 §4.2/§5）：GOV 行政服务生产 unit 创建/补齐
    //   （economy.UpsertGovUnit 的窄封装：payloadJson + preview / apply + expectedRevision；一次写
    //   units + assetShares + relations，operator=hh-gov-<id>）。**只在 GM 桶**：决策人桶没有它，
    //   白名单派生自那个桶 ⇒ 也不含它；命令本身不打 {@code DmUsableCommand} ⇒ 令 / RegisterEffect /
    //   决策人 catalog 三条路径同样排除。★ app 侧预检 unit 存在 + module() 是 GovernmentFormation
    //   （preview/apply 两条路径；控制方 2026-10-23 裁定 A）。★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    built.add(new EconomyUpsertGovUnitTool(toolWrites, toolReads, economyPerspective));
    // ★★ B2（2026-10-08 阶段 2-B；约束设计书 §4.2 / 用户 §1.2「退让市场区、覆盖范围、合并」）：市场区持久状态的三条
    //   GM 窄写（定义 / 逐格改划 / 合并）。**只在 GM 桶**：决策人桶没有它们（白名单派生自那个桶）；
    //   三条命令本身都不打 {@code DmUsableCommand} ⇒ 令 / RegisterEffect / 决策人 catalog 三条路径同样排除。
    //   ★ 三条工具都只透传 payloadJson（载荷形状的唯一权威 = handler 类注 + catalog 载荷提示）；
    //     工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面只声明 economy 命名空间（handler 只产 EconomyChangeSet）。
    built.add(new EconomyDefineMarketZoneTool(toolWrites, mapId));
    built.add(new EconomyReassignZoneHexesTool(toolWrites, mapId));
    built.add(new EconomyMergeMarketZonesTool(toolWrites, mapId));
    // ★★ 商品基础运费表的 GM 窄写（2026-10-09 裁定「甲」后的纠正版；economy.SetCommodityFreight 的
    //   薄封装：payloadJson + branch + expectedRevision）。**只在 GM 桶**：决策人桶没有它，
    //   白名单派生自那个桶 ⇒ 也不含；命令本身不打 {@code DmUsableCommand} ⇒ 令 / RegisterEffect /
    //   决策人 catalog 三条路径同样排除。★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；
    //   写面只声明 economy 命名空间（handler 只产 EconomyChangeSet）。──
    built.add(new EconomySetCommodityFreightTool(toolWrites, mapId));
    // ★★ W2（2026-10-11，S-8/§2.1）：**市场区动态价步长 α 的 GM 窄写**（economy.SetMarketZonePriceAlpha 的薄封装）。
    //   用户 2026-09-25 裁定「凡是行为一律做成 GM 可调的预设」⇒ α 不是编译期常量而是逐区状态，本工具是它的写口。
    //   **只在 GM 桶**：决策人桶没有它（白名单派生自那个桶）；命令不打 {@code DmUsableCommand} ⇒
    //   令 / RegisterEffect / 决策人 catalog 三条路径同样排除。★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；
    //   写面只声明 economy 命名空间（handler 只产 EconomyChangeSet）。
    built.add(new EconomySetMarketZonePriceAlphaTool(toolWrites, mapId));
    // ★★★ W5.5（2026-10-11，S-12⑤）：**市场区货币市场利率步长 α 的 GM 窄写**（economy.SetMarketZoneLoanRateAlpha 的
    //   薄封装）。利率 = 钱的价（与商品价同一套自适应算式：缺钱涨、钱多跌、下限 0），而"每轮最多动多少"必须是 GM 可调的
    //   预设（用户 2026-09-25 裁定）⇒ 这是它的写口。**只在 GM 桶**：决策人桶没有它（白名单派生自那个桶）
    //   ⇒ 也不含；命令不打 {@code DmUsableCommand} ⇒ 令 / RegisterEffect / 决策人 catalog 三条路径同样排除。
    //   ★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面只声明 economy 命名空间（handler 只产 EconomyChangeSet）。
    built.add(new EconomySetMarketZoneLoanRateAlphaTool(toolWrites, mapId));
    // ★★ W5-fix6（2026-10-11，D1）：**政府铸币规模旋钮的 GM 窄写**（economy.SetGovernmentMintScale 的薄封装）。
    //   旋钮是"本政府每周期最多铸多少毫"的唯一闸门，而改前唯一能写它的命令只认 gov-unit-<id> 形态 ⇒ 世界级
    //   world-silver（唯一会铸币的政府）够不着（W5 验收报告 §3-D1 的 HTTP 422）。本工具补这条写口。
    //   **只在 GM 桶**：决策人桶没有它（白名单派生自那个桶）；命令不打 {@code DmUsableCommand} ⇒
    //   令 / RegisterEffect / 决策人 catalog 三条路径同样排除。★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；
    //   写面只声明 economy 命名空间（handler 只产 EconomyChangeSet）。
    built.add(new EconomySetGovernmentMintScaleTool(toolWrites, mapId));
    // ★★★ W10（2026-10-11）：**经济侧行为值的政策写口**（economy.SetEconomyPolicy 的薄封装）——
    //   用户 2026-09-25 裁定第 18 条「凡"行为"一律做成 GM 可调的预设」。缺省值与缺省中性由 EconomyPolicyKey 保证。
    //   **只在 GM 桶**（同上一行：命令不打 {@code DmUsableCommand} ⇒ 令/RegisterEffect/决策人目录三条路径排除）。
    built.add(new EconomySetEconomyPolicyTool(toolWrites, mapId));
    // ★★ S3a（2026-10-09 Unit/Gov 家户容纳 §4.3）：家户/人口 GM 窄写六条。**只在 GM 桶**；
    //   工具名都不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。四条 social 工具：创建 / 移动（HEX↔UNIT，跨 unit 时
    //   同批改 unit.households）/ 成员增删转移 / 出生死亡率；两条组合工具：assignHousehold（social.SetHouseholdLocation
    //   + unit.SetUnitHouseholds，一批一条 revision）与 detachHousehold（反向）。preview=true（缺省）一个字节都不写。
    built.add(new SocialHouseholdCreateTool(toolWrites, toolReads, socialUnits));
    built.add(new SocialHouseholdMoveTool(toolWrites, toolReads, socialUnits));
    built.add(new SocialHouseholdMembersTool(toolWrites, toolReads));
    built.add(new SocialHouseholdRatesTool(toolWrites, toolReads));
    // ★★ Batch 4（2026-10-09 家户结构修复计划）：Social 需求/劳动系数 GM 窄写两条。**只在 GM 桶**；
    //   各自封装一条 GM-only 命令（不打 DmUsableCommand；global 默认 + 单家户覆盖的 upsert/清除）；preview（缺省）走
    // SocialProvisioning
    //   的纯 copy-with，不写状态、不产生 revision；apply 才经 submitCommand 落一条 revision。
    //   ★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS（命令类型本体已注册并登记载荷提示）。
    built.add(new SocialDemandTool(toolWrites, toolReads));
    built.add(new SocialLaborTool(toolWrites, toolReads));
    built.add(new UnitAssignHouseholdTool(toolWrites, toolReads, socialUnits));
    built.add(new UnitDetachHouseholdTool(toolWrites, toolReads, socialUnits));
    // ★★ D4（2026-10-22 决策包/家户可用性计划）：GM 参数工具四条（**只在 GM 桶**；工具名都不是命令类型 ⇒
    //   不进 catalog/PAYLOAD_HINTS）——周期扣增规则表（economy，复用 P4a 两条命令）、军俸政策（unit，
    //   复用 unit.SetArmyPayPolicy）、生死率（social，新增 social.SetGlobalVitalRates + 复用
    //   social.SetHouseholdVitalRates）、家户人口直调（逐字转发 simos.social.household.members）。
    //   四条都是 preview 缺省 true、apply 才落一条 revision 的窄写口径。
    built.add(new GmPeriodicAdjustmentTool(toolWrites, toolReads, economyPerspective));
    // ★★ R1（2026-10-09）：政府市场授权（行政家户挂单）的 GM 窄工具 —— 只写一张授权表，订单仍由日结算的
    //   家户→订单→撮合→结算 生成（用户 §1.3：不加政策层）。
    built.add(new GmGovMarketMandateTool(toolWrites, toolReads));
    built.add(new GmArmyPayPolicyTool(toolWrites, toolReads));
    built.add(new GmVitalRatesTool(toolWrites, toolReads));
    built.add(new GmAdjustPopulationTool(toolWrites, toolReads));
    // ★★ P7b（2026-10-01 后端 + MCP 稳定化计划）：GM 审批队列裁决口（控制面，不落世界 revision）。
    //   **只在 GM 桶**；与读口 simos.gm.approvals 共用同一份 PendingApprovals / ApprovalCoordinator。
    //   ★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；资源声明 NONE（不读写世界命名空间）。
    built.add(new GmApproveTool(pendingApprovals, approvalCoordinator));
    // ★★ C5b（2026-10-02 年份系统）：历法/气候配置写口（store_meta.calendar，**不落 revision**）。
    //   **只在 GM 桶**（注册点就是这里）：改锚点会让所有历史日期整体平移、改分带会改全世界的季节判定，是"世界级配置"。
    //   ★ 它**不继承** AbstractNarrowWriteTool、不调 core.submit（库级元数据与 time_base 同类）⇒ 工具名也不是命令类型，
    //     不进 catalog/PAYLOAD_HINTS；资源声明 NONE（没有对应的世界命名空间，见 CalendarConfigureTool 类注）。
    //   ★ 敏感写 + ToolGate.Ask（classKey = 工具名）；GM 面的审批放行与 GmToolUsage 留痕见类注。
    built.add(new CalendarConfigureTool(query, calendarService));
    // ★ 批 K ①：本档（GM）的可提议目录随本档工具面一起交回（装配期审计读它；实例见 decisionProposalTools）。
    return gmProposalCatalog;
  }

  /**
   * ★★ <b>可提议工具族：唯一构造点</b>（批 K ① 根治版，设计书 §3.2；用户 2026-10-15：「这玩意不是在程序代码上已经写死了吗……为啥额外要张表？」）。
   *
   * <p>★ <b>为什么是"一个方法"而不是"两张清单"</b>：这 16 条工具的实例**只在这里 {@code new} 一次** —— 决策人桶用 {@code
   * built.addAll(...)} 注册**这一批对象**，{@code ProposalCatalog} 用**同一个 List** 建索引 ⇒
   * 「谁在可提议名单里」不再有第二处手写清单；「我能不能被提议」的唯一声明是**工具自己的** {@code implements
   * io.mosire.simos.ga.spi.DecisionProposable}（目录按 {@code instanceof} 派生，本方法不写任何名单语义）。
   *
   * <p>★ <b>顺序 = 内容</b>：与历史目录的成员序逐条一致（unit/social 三条旧的 + 批 3b 的九条 {@code simos.gov.*}
   * 类2），便于与既往证据对读。★ 这 16 条**全部** {@code roles()} 含 DM：前 7 条是 2026-10-15 用户裁定「甲」补的 DM 标签
   * （它们本来就是决策人的核心动作：造兵 / 征税 / 招吏 / 辞退 / 选人 / 派队 / 家户成员直调），后 9 条是批 3b 进目录的类2 世界写。
   *
   * <p>★ <b>为什么前 7 条也必须进决策人桶</b>：{@code ToolRoleAudit.requireFaces} 把「桶 ⇔ {@code roles()}」双向钉死 ——
   * 声明了 DM 却不注册进决策人桶 = 装配期当场抛。★ 注册进桶**不会**让 DM 绕过 GM：它们的 {@code gate()} 全是 {@code Ask} ⇒ 自动成**类2
   * {@code DM_APPROVAL}**（直写停在待批等 GM 点头；或走 {@code simos.ga.packet.propose} 由 GM 统一批）。
   *
   * <p>★ <b>依赖</b>：五类接缝束都在本方法里现建（窄写口 / 窄读口 / unit 五口 / gov 七口）——与两桶的装配同形， 不引入第二份适配器实现。
   *
   * @param core 组合根（窄写口的落点）
   * @param calendarService 历法时钟（unit 视图口与人力口要用生产历法判年龄档）
   * @param query 查询层（窄读口的落点）
   * @param initiator 命令发起者（写口装配用；预览/规划路径绝不落盘）
   * @param mapId 本世界的 map 称谓
   * @return 可提议工具族的**不可变**实例清单（顺序见上）；不是"名单"——能不能被提议仍由目录按接口派生
   */
  public static List<AgentTool> decisionProposalTools(
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId) {
    ToolWritePort writes = new CoreSimosToolWritePort(core, initiator);
    ToolReadPort reads = new QueryServiceToolReadPort(query);
    // unit 五口（视图/视角/分摊/命令类型 + 读）——与 addGmWrites 同形，唯一实现在 app。
    UnitToolViews unitViews = new AppUnitToolViews(calendarService);
    UnitToolPerspective unitPerspective = new AppUnitToolPerspective();
    UnitToolAllocations unitAllocations = new AppUnitToolAllocations(calendarService);
    UnitToolCommandTypes unitCommandTypes = new AppUnitToolCommandTypes();
    // gov 七口（写/读/视图/视角/经济/命令类型/人力）——与两桶的接缝束同一形制。
    GovToolSupport gov =
        new GovToolSupport(
            new CoreSimosToolWritePort(core, initiator),
            new QueryServiceToolReadPort(query),
            new AppGovToolViews(),
            new AppGovToolPerspective(),
            new AppGovToolEconomy(),
            new AppGovToolCommandTypes(),
            new AppGovToolManpower(calendarService));
    return List.of(
        // ── ① 2026-10-15 裁定「甲」补 DM 标签的 7 条（本来就该是 DM 能提议的决策人核心动作）──
        //   造兵：命令面**没有** unit.RaiseUnit（本工具独有）⇒ 移出名单 = DM 彻底不能造兵；
        //   征税：levyRegion 同样是工具独有；招吏/辞退：命令面有对应命令，但仍以本工具为受支持的完整调用面。
        new RaiseUnitTool(
            writes, reads, unitViews, unitPerspective, unitAllocations, unitCommandTypes),
        new GovRecruitTool(gov, mapId),
        new GovRetireStaffTool(gov, mapId),
        new SocialHouseholdMembersTool(writes, reads),
        new LevyRegionTool(writes, reads, unitViews, unitPerspective, unitAllocations),
        new GovSelectExamineesTool(gov, mapId),
        new GovDispatchTeamTool(gov, mapId),
        // ── ② 批 3b（设计书 §8.3 第 1 条）进目录的九条 `simos.gov.*` 类2（`{DM,GM}` + `gate=Ask(SENSITIVE)`）──
        //   无人值守时 DM 走 `simos.ga.packet.propose`（**零阻塞**）、GM 用 `gm.packet.decide` 真审、
        //   `gm.packet.execute` 执行；类2 的直写路径**一条没删**（有真人看着的场合照旧可用）。
        //   ★ 九条都不建单位（`expandHousehold` 建的是**家户**、不是单位）⇒ `createsUnit=false`、`skipKeys` 空：
        //     它们的参数/预览里没有"将要成为的目标的新 id"，只有既存资源（GOV 单位 / 家户 / 国库 / 格）。
        new GovSetEstablishmentTool(gov),
        new GovSetBudgetPolicyTool(gov),
        new GovAssignPostsTool(gov),
        new GovExpandHouseholdTool(gov),
        new GovOpenPostsToMarketTool(gov),
        new GovDefineCurrencyTool(gov),
        new GovRenameCurrencyTool(gov),
        new GovIssueMoneyTool(gov),
        new GovSetFxRateTool(gov));
  }

  /**
   * 决策人组的写面（N9 + 用户 2026-09-22 的收窄 + D5 的三条新增）：**决策行为 + 外交/支付两条受限写入** —— {@code
   * ga.IssueDirective}（出令）、{@code ga.SubmitVerdict}（裁决者冻结判决）、{@code gov.SetDiplomaticRelation}
   * （以调用者 Nation 名义写外交边）、{@code gov.RecordDiplomaticEvent}（参与者必须含调用者 Nation）、 {@code
   * simos.gov.pay}（付款人 = 调用者所属 GOV，身份派生；收款方由既有审批链把关）。
   *
   * <p>★ **无** `ga.SetDecisionMakerAccess`（配权是 GM 的活）、**无**通用写、**无** map/unit/sd
   * 的任何其他写工具：用户原话「决策人不能直接改地图等数据， 只能获取有限的、被 GM 权限层限制范围的信息」。指挥一律走 `ga.IssueDirective`（spec §八.2 的 D2
   * 原设计）。★ D5 新增的三条也都是**受限面**：两条外交写只以自己的 Nation 名义、支付只能花自己所属 GOV 的国库， 且都走同一条敏感审批链。
   *
   * <p>★ **本清单要与 {@code DecisionCallerFactory} 的白名单同源**：那边给的是**权限组**（工具名白名单），这里给的是**桶**（注册进 MCP
   * 口用）——两处都收窄才算"改不掉"，只改一处等于留一条旁路。
   *
   * <p>★★ <b>E6b 负向证明：{@code simos.economy.adjust} 不在决策人可达面上</b>——它只在上面的 {@link #addGmWrites}
   * 里注册（本桶没有）；决策人白名单（派生自那个桶）也不含 {@link EconomyAdjustTool#NAME}；它封装 {@code economy.GmAdjust}，而该命令不打
   * {@code DmUsableCommand} ⇒ 令白名单 / {@code RegisterEffect} / 决策人 catalog
   * 三条路径同样排除。三层同源收窄，缺一层就等于留一条绕过政治能力的入口。
   */
  private static ProposalCatalog addDecisionAgentWrites(
      List<AgentTool> built,
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId) {
    ToolWritePort gaWrites = new CoreSimosToolWritePort(core, initiator);
    ToolReadPort gaReads = new QueryServiceToolReadPort(query);
    GaToolIdentity gaIdentity = new AppGaToolIdentity();
    built.add(new IssueDirectiveTool(gaWrites, gaIdentity, mapId));
    built.add(new SubmitVerdictTool(gaWrites, gaIdentity, mapId));
    // ★★ D5（2026-10-02 / D-003、D-005、R5、R6）：决策人侧的三条新增窄写——
    //   外交关系边（from 必须是调用者 Nation）、外交事件（participants 必须含调用者 Nation）、
    //   "给 XXX 政府钱"（付款人 = 调用者所属 GOV，身份派生；任意收款 GOV 由既有审批链把关）。
    //   **桶**（本方法）就是 **权限组白名单**的来源——{@code Shell} 从本桶派生工具名，{@code roles()} 是唯一声明。
    // ★★ 2026-10-12 乙收尾：署名判定要问"调用者以谁的名义"⇒ 注入归属读口（端口在 ga-spi、实现住 app，
    //   它同时服务决策人运行流的身份消息）。
    built.add(
        new SetDiplomaticRelationTool(
            gaWrites, gaReads, gaIdentity, AppDecisionMakerAffiliations.INSTANCE, mapId));
    built.add(
        new RecordDiplomaticEventTool(
            gaWrites, gaReads, gaIdentity, AppDecisionMakerAffiliations.INSTANCE, mapId));
    GovToolSupport gov =
        new GovToolSupport(
            new CoreSimosToolWritePort(core, initiator),
            new QueryServiceToolReadPort(query),
            new AppGovToolViews(),
            new AppGovToolPerspective(),
            new AppGovToolEconomy(),
            new AppGovToolCommandTypes(),
            new AppGovToolManpower(calendarService));
    built.add(new GovPayTool(gov));
    // ★★ 批 K ①（2026-10-15 根治版，设计书 §3.2）：**可提议工具族**（16 条）在这里**一次注册**，并与
    //   `ProposalCatalog` 共用**同一个 List**（下一段 `new ProposalCatalog(proposable, mapId)`）⇒ 目录的成员
    //   =「本桶里实现了 `DecisionProposable` 的工具」，**不再有第二处手写清单**（唯一构造点见 `decisionProposalTools`）。
    //   ★ 组成 ①（2026-10-15 用户裁定「甲」）：raiseUnit / levyRegion / gov.recruit / gov.retireStaff /
    //     gov.selectExaminees / gov.dispatchTeam / social.household.members —— 这 7
    // 条**本来就是决策人的核心动作**
    //     （造兵、征税在命令面**没有**对应命令 ⇒ 移出名单 = DM 彻底做不到），当年只是漏打了 DM 标签；它们**本来就是
    //     gate=Ask** ⇒ 补上标签后自动成**类2 DM_APPROVAL**（「DM/GM 混合就是要交给 GM 审核」，与用户口径一致）。
    //   ★ 组成 ②（批 3b / Z3c-2 / Z3d / A1 / A2a）：九条 `simos.gov.*` 类2 —— setEstablishment /
    // setBudgetPolicy /
    //     assignPosts / expandHousehold / openPostsToMarket / defineCurrency / renameCurrency /
    // issueMoney / setFxRate。
    //   ★ 两条路径（2026-10-15 批 3b §11.2 B3 实测）：
    //     ①【决策人直写】AutoApproveGate → ConfirmGate → PendingApprovals：阻塞等 GM 在审批面点头
    //        （上限 APPROVAL_TIMEOUT，到点 APPROVAL_DENIED、asks=1）；
    //     ②【决策人提议】{@code simos.ga.packet.propose}：零审批、asks 不增、不阻塞。GM 侧用
    //        {@code simos.gm.packet.decide} 真审、{@code simos.gm.packet.execute} 执行。
    //   ★ 身份派生仍逐条由工具自己负责（GM 必须显式 govUnitId；决策人省略 = 自己的 GOV、给出别的 GOV ⇒ 具名 REJECTED）。
    //   **桶**（本方法）就是权限组白名单的来源（由 {@code Shell} 派生，不另立清单）。
    List<AgentTool> proposable =
        decisionProposalTools(core, calendarService, query, initiator, mapId);
    built.addAll(proposable);
    // ★★ D2（2026-10-22 决策包计划）+ 2026-10-14 批 B（设计书 §3.2/§3.3）决策族定型：**七件套**
    //   propose / revise / drop / intent / confirm / reopen / my。
    //   **只在决策人桶**；白名单即由该桶派生（roles() 是唯一声明）。
    //   ProposeCallTool 内含 ProposalCatalog（真预览 + 目标提取）；confirm/intent/reopen 走 own-packet 围栏。
    //   ★ 权限：写口六条全 gate=ALLOW（类3 DM_FREE）—— 决策表达**零世界效果**（不变量 I1：只写自己的 sd
    //     包/INFO 覆盖层），真正的闸门在 GM 侧（gm.packet.decide / mergedPlan.* / packet.execute 本批一处不放松）；
    //     my 是只读口（无 gate 覆写）。
    //   ★ 相对次序保持原样（propose → confirm → intent → my），三条新工具**追加在 my 之后**。
    ProposalCatalog proposalCatalog = new ProposalCatalog(proposable, mapId);
    GaProposalHost gaProposal = new AppGaProposalHost(proposalCatalog);
    ProposeCallTool propose = new ProposeCallTool(gaWrites, gaReads, mapId, gaProposal, gaIdentity);
    built.add(propose);
    built.add(new ConfirmPacketTool(gaWrites, gaReads, gaIdentity));
    built.add(new PacketIntentTool(gaWrites, gaReads, gaIdentity));
    built.add(new MyPacketTool(gaReads, gaIdentity));
    //   ★★ 三条新工具（批 B）：改 / 删 / 撤回确认。
    //     revise 复用**同一个** propose 实例：校验段（catalog 校验 → 真 preview → 目标 scope → 造 FormattedCall）的
    //       唯一实现 = ProposeCallTool.validateCall，两个调用点（propose 自己的逐条循环 + revise）共用它，
    //       注入同一对象 ⇒ 装配面不可能凑出"第二份校验"。
    built.add(new ReviseCallTool(gaWrites, gaReads, propose, gaIdentity));
    built.add(new DropCallTool(gaWrites, gaReads, gaIdentity));
    built.add(new ReopenPacketTool(gaWrites, gaReads, gaIdentity));
    // ★★ 2026-10-14 决策人互致消息（设计书 §4.2/§4.3）：旧的 simos.sd.report 跨区上报**整族废弃**（用户原话
    //   「把这个工具扔了得了……老的直接扔掉」），换成"GM 一次授权 ⇒ 两人双向互致消息、每 tick 每方向最多 128 条（命令期判）"。
    //   **两条都只在决策人桶**（GM 不是决策人、没有 decision-maker: 身份 ⇒ 它读 info 全量、不需要这个收件箱）；
    //   白名单即由该桶派生 ⇒ 两个工具自动进决策人可达面。
    //   ★ send 的闸门是 **GM 事先授权**（accessLimit.peer），不是逐条审批 ⇒ 它**故意不继承 AbstractNarrowWriteTool**
    //     （那个基类硬编码 Ask；继承会让"窄写 ⇒ 审批"出现例外，见 I7 与那个类的注释）。
    built.add(new GaMessageSendTool(gaWrites, gaReads, gaIdentity));
    //   ★ inbox 的可见性判据**不许自己写**（DecisionResultsVisibilityGuardTest 钉着"归属判据全仓恰一份"）
    //     ⇒ 注入既有端口 GaToolQueries.infoVisibleToDecisionMaker（实现 = AppGaToolQueries）。
    GaToolQueries gaQueries = new AppGaToolQueries(query, mapId);
    built.add(new GaMessageInboxTool(gaQueries, gaReads, gaIdentity));
    // ★★ 2026-10-14 GM⇄DM 双向信件（设计书 §3.5）：**确认推演**——DM 的一次表态，零世界效果（类3 DM_FREE，
    //   gate=ALLOW）。落一条 ga.PutInfo（地址 ga:adjudicationAck.<tick>.<dm>、显式 id ack-<tick>-<dm>
    //   ⇒ 同 tick 第二条被命令期具名拒 = 天然幂等一次）。**只在决策人桶**（roles={DM}）：这是 DM 说"我认了这次推演"，
    //   GM 侧的表态面是只读的 simos.gm.round。
    built.add(new AdjudicationAckTool(gaWrites, gaReads, gaIdentity));
    // ★★ 2026-10-14 决策人互致消息（设计书 §4.1）：GM 侧"授权两人互通"的窄写（一次写两条 DM 记录、一条 revision）。
    //   **不在本桶**（roles={GM}，它是配权命令）⇒ 它注册在 addGmWrites。
    // ★ 批 K ①：本档（决策人）的可提议目录随本档工具面一起交回（成员即上面那批 proposable 实例）。
    return proposalCatalog;
  }

  private static List<AgentTool> readTools(
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig,
      PendingApprovals pendingApprovals) {
    // ★★ 2026-10-12 B3 试点（设计书 §4.3 / 台账 §5）：地图的 7 条读工具**已下放到 simos-map**
    //   （`io.mosire.simos.map.tools.read`），这里只剩装配——注入四类接缝：
    //   ① 窄读口（QueryService 的三个方法面，含 head 解析/创世等待/读缓存）；
    //   ② 视图口（ApiViews / ToolSupport 的**同一份** view 函数，形状不另写第二份）；
    //   ③ 视角口（可见性围栏 + 归属国家/邻国这两个 sd 派生事实）；
    //   ④ 服务口（渲染 RenderService、寻路 unit.PathFinder）。
    // ★★ 2026-10-12 B3 第七域（ga/sd 决策族）：读工具**已下放**（`io.mosire.simos.ga.tools.read`），
    //   这里只做装配——注入窄读口 + 三条 ga 自有的接缝：
    //   ① 视图口（ApiViews 的 combats/diplomacy/events/gmToolUsage 逐条转调，形状只此一份）；
    //   ② 查询口（SdQueryService + RedactingQueryService 的裁剪读面；窗口类型是 ga 的镜像，转换只在此一处）；
    //   ③ 身份口（DecisionCallerFactory 的身份派生 + 自指域 kind）。
    ToolReadPort gaReads = new QueryServiceToolReadPort(query);
    GaToolViews gaViews = new AppGaToolViews();
    GaToolQueries gaQueries = new AppGaToolQueries(query, mapId);
    ToolReadPort mapReads = new QueryServiceToolReadPort(query);
    MapToolViews mapViews = new AppMapToolViews();
    MapToolPerspective mapPerspective = new AppMapToolPerspective();
    MapRenderPort mapRender = new AppMapRenderPort(renderService);
    MapPathPort mapPaths = new AppMapPathPort();
    // ★★ 2026-10-12 B3 第二域（army）：两条 army 读工具**已下放到 `simos-army`**（`army.tools.read`），
    //   注入同一套接缝——窄读口（通用适配器，与 map 共用一个形制）+ 视图口 + 视角口（可见性围栏唯一实现）。
    ToolReadPort armyReads = new QueryServiceToolReadPort(query);
    ArmyToolViews armyViews = new AppArmyToolViews();
    ArmyToolPerspective armyPerspective = new AppArmyToolPerspective();
    // ★★ 2026-10-12 B3 第三/四域（economy + social）：两域读工具**已下放**（`economy.tools.read` /
    //   `social.tools.read`），注入同一套接缝——窄读口（通用适配器）+ 视图口（ApiViews 的同一份函数）
    //   + 视角口（可见性围栏唯一实现）。
    ToolReadPort economyReads = new QueryServiceToolReadPort(query);
    EconomyToolViews economyViews = new AppEconomyToolViews();
    EconomyToolPerspective economyPerspective = new AppEconomyToolPerspective();
    ToolReadPort socialReads = new QueryServiceToolReadPort(query);
    SocialToolViews socialViews = new AppSocialToolViews(calendarService);
    SocialToolPerspective socialPerspective = new AppSocialToolPerspective();
    // ★★ 2026-10-12 B3 第五域（unit）：两条 unit 读工具**已下放**（`unit.tools.read`），注入同一套接缝——
    //   窄读口（通用适配器）+ 视图口（ApiViews.unit / ToolSupport.units 的同一份实现，持历法服务）
    //   + 视角口（可见性围栏唯一实现）。
    ToolReadPort unitReads = new QueryServiceToolReadPort(query);
    UnitToolViews unitViews = new AppUnitToolViews(calendarService);
    UnitToolPerspective unitPerspective = new AppUnitToolPerspective();
    // ★★ 2026-10-12 B3 第六域（gov）：两条 gov 读工具（province.divide / gov.info）**已下放**
    //   （`gov.tools.read`），注入同一个接缝束（与写侧同一形制）。
    GovToolSupport gov =
        new GovToolSupport(
            new CoreSimosToolWritePort(core, initiator),
            new QueryServiceToolReadPort(query),
            new AppGovToolViews(),
            new AppGovToolPerspective(),
            new AppGovToolEconomy(),
            new AppGovToolCommandTypes(),
            new AppGovToolManpower(calendarService));
    return List.of(
        // ★★ E6b：catalog 的**命令清单**取完整注册面（含 GM-only），决策人可见性由显式"可嵌入令"白名单过滤
        //   （CatalogVisibility）—— GM 看得到 economy.SwitchMode / economy.GmAdjust，决策人看不到。
        new CatalogTool(commandTypes, embeddableCommandTypes),
        new StateResolveTool(query, mapId),
        new StateFacetsTool(query, mapId),
        new BranchListTool(core),
        // ★ 工具面 M4（2026-09-24）：补五条缺口的读口 —— 时间轴节点清单 / 区域详情 / 寻路试算 /
        //   决策人清单 / 决策人详情。（`timeline.branches` 只给"有哪些分支、head 在哪"，看不到节点；sd 侧此前
        //   只能 CreateDecisionMaker 写、写完看不见。）
        new TimelineRevisionsTool(core, calendarService),
        // ★★ C5b（2026-10-02 年份系统）：历法/气候配置 + 指定 tick 的日期/节气（可选季节）。**四桶共享**——
        //   历法只是"今天几号、什么节气"，不含可见性侧信道（配置是 store_meta 库级元数据、不落 revision）。
        //   ★ 不标 GmOnlyRead、**不进** PAYLOAD_HINTS（它不是命令类型）。
        new CalendarInfoTool(query, calendarService),
        new MapOverviewTool(mapReads, mapViews, mapPerspective, mapId),
        new MapHexTool(mapReads, mapViews, mapPerspective, mapId),
        new MapRegionTool(mapReads, mapViews, mapPerspective, mapId),
        // ★ 用户 2026-10-02：GM 专用只读重合检测——同一 tag（如 Nation）下找区域的部分重合 / 完全重合 /
        //   包含；决策人不给，避免越过视野泄露全图区域几何。
        new MapOverlapsTool(mapReads, mapViews, mapPerspective, mapId),
        // ★ P3（2026-10-01）：省份划分建议（只出建议、零写入）——递归主轴二分 + 可选首都圈 + 相交 Region 清单。
        //   只给 GM 桶（GmOnlyRead）：划分是 GM 的规划动作，决策人不做行政区划。
        new ProvinceDivideTool(gov, mapId),
        new MapPathTool(mapReads, mapViews, mapPaths),
        // ★ 工具面补齐（2026-09-25）：GUI `/api/map/block` 的对应读口——某格所在的**整块地形**（成员格清单）。
        //   与 hex（单格）/ overview（全块多边形）不同形；GUI 该端点拒 as= ⇒ 只给 GM 桶（GmOnlyRead）。
        new MapBlockTool(mapReads, mapViews),
        // ★ C6a：单位/人口的只读时间戳（movement.departedAt / at）要带日期 ⇒ 三件都传世界同一份 CalendarService。
        new UnitListTool(unitReads, unitViews),
        new UnitGetTool(unitReads, unitViews, unitPerspective),
        new PopulationTool(socialReads, socialViews, socialPerspective),
        // ★★ D1（2026-10-22 决策包计划）：家户聚合读口（GM 与决策人共用；scope=ALL 由工具内 social 面
        //   unrestricted 判定，不标 GmOnlyRead）。资源声明 map/social/unit/economy/actor 全 READ_ONLY。
        new SocialHouseholdsTool(query, calendarService, mapId),
        // ★★ Z3c-2（2026-10-23）：GOV 行政运行只读读口（GM + 该 GOV 决策人；决策人视野收窄到自己 GOV）。
        //   四桶共享（不标 GmOnlyRead）；资源断言 = unit:<govId> + 座位 map/social/actor；
        //   白名单即由该桶派生（{@code roles()} 是唯一声明）。
        new GovInfoTool(gov, mapId),
        // ★ R2a（2026-09-25）：逐格经济读数（GUI `/api/economy/hex` 的对应读口；**四桶共享**——经济是世界状态，
        //   决策人该看得见辖地的产出与库存；视野由 ToolSupport.hexVisible 收窄）。
        new EconomyHexTool(economyReads, economyViews, economyPerspective, mapId),
        // ★ H0.6（2026-09-27）：产权读口 —— 行侧家户库存与 actor 侧 HouseholdInventory **并排**发（堵"把一本账读成全系统"）。
        //   只在 GM 桶（GmOnlyRead）：响应含 actor 切片的商品余额，而 actor 面在本仓的资源表态里是缺省拒。
        new EconomyOwnershipTool(economyReads, economyViews, economyPerspective, mapId),
        // ★ P3（2026-09-24）：把世界渲染成图——中心+半径、可选图层；图片随结果出站（MCP ImageContent / 决策人图片分片）
        new MapRenderTool(mapReads, mapViews, mapRender),
        // ★ 同上：sd 侧的两条（决策人清单 / 详情）——此前只能 `ga.CreateDecisionMaker` 写、写完看不见。
        new DecisionMakersTool(gaQueries),
        // ★ 2026-09-25：详情并入 GUI 的 `/{id}/scope`（现算可见范围）——同一资源的两个端点合成一条工具。
        new DecisionMakerTool(gaQueries),
        // ★★ P7c（2026-10-01 后端 + MCP 稳定化计划）：决策记录清单（GUI GET /api/sd/directives 的 MCP 读口）。
        //   与 decision-makers/decision-maker 同属"读别人的底牌" ⇒ 只给 GM 桶（GmOnlyRead）；视图直接复用
        //   ApiViews.directives，不另写一份。
        new SdDirectivesTool(gaQueries),
        // ★★ D5（2026-10-02 / D-003、D-005、R6）：外交关系边 + 外交事件（世界级自然语言文本 ⇒
        //   四桶共享读；不标 GmOnlyRead；**不做逐格视野过滤**、也绝不借它给中央 DM 地图/单位视野——D-002）。
        //   形状复用 ApiViews.diplomaticRelations / ApiViews.diplomaticEvents。
        new SdDiplomacyTool(gaReads, gaViews),
        new SdDiplomaticEventsTool(gaReads, gaViews),
        // ★★ 2026-10-14：旧读口 simos.sd.reports **整条删除**（旧族废弃，用户原话「老的直接扔掉」）；
        //   接替它的是决策人桶里的 simos.ga.message.inbox（只读寄给自己的消息，不泄漏名册）。
        // ★★ D1（2026-10-02 / D-012）：army 切片的交战记录读口（"当前 tick 在哪发生交战"的唯一直接读口）。
        //   四桶共享（世界状态、可回放）；逐条可见性按记录所在格判（ToolSupport.hexVisible）——看不见的格不进结果。
        //   形状与 GUI 同源（ApiViews.armyCombat）。
        new ArmyCombatsTool(armyReads, armyViews, armyPerspective, mapId),
        new ArmyCombatTool(armyReads, armyViews, armyPerspective, mapId),
        // ★ 判决（模型原始输出 + meta）：省略 actor = FULL 全量披露 ⇒ 只给 GM 桶（GmOnlyRead），见类注。
        new SdVerdictsTool(gaQueries),
        // ★★ D2（2026-10-22 决策包计划）：GM 决策包两条读口（摘要列表 + 单包全量）。
        //   只给 GM 桶（GmOnlyRead）：决策包是 GM 的裁决工作面，决策人用 simos.ga.packet.my 读自己的。
        new GmPacketsTool(gaReads),
        new GmPacketTool(gaReads),
        // ★ GM 面观测/配置读口（只给 GM 桶）：工具使用记录（运行时监督数据）与 LLM provider 掩码配置。
        new GmToolUsageTool(gmToolUsage, gaViews),
        // ★★ P7b（2026-10-01 后端 + MCP 稳定化计划）：待裁决审批清单（控制面；只在 GM 桶，GmOnlyRead）。
        //   读的是传入的 PendingApprovals（null = 未接入 ⇒ 执行期 UNAVAILABLE）；资源声明 NONE、不落 revision。
        new GmApprovalsTool(pendingApprovals),
        // ★★ 2026-10-14 GM⇄DM 双向信件（设计书 §3.1/§3.5）：**两条 GM-only 读口**——
        //   ① simos.gm.inbox：决策人寄给 GM 的信 + 逐条 answered（GM 回话的前提；缺它就是缺口 G2）；
        //   ② simos.gm.round：轮次看板（逐 DM：包状态 / 未答问题数 / 是否已确认推演 + 三个 pending 清单）。
        //   ★ 两条都靠 GmOnlyRead **自动只进 GM 桶**（读工具默认四桶共享，见 readToolsFor 的唯一拼写点）：
        //     决策人读自己的收件箱走 simos.ga.message.inbox，那条按可见性三轴筛、只回 to==自己的信——两条读口各读各的那一半。
        //   ★ 都是纯派生读口（零新状态）：资源声明 sd 只读 + 断言 sd:*（与 simos.gm.packets 同款）。
        new GmInboxTool(gaReads),
        new GmRoundTool(gaReads),
        new LlmProvidersTool(llmConfig));
  }

  /**
   * ★★ **读工具的桶归属**（2026-09-24，工具面 M4）：默认四桶共享（结构默认），显式标了 {@link GmOnlyRead} 的**只进 GM 桶**。
   *
   * <p>为什么需要它：M4 侦察报告逐条核过，有几条读口照抄 GUI 会**越过决策人的可见范围**（{@code map.path} 是地形探测、{@code
   * sd.decision-makers} 是别人的底牌）。creed 五要求"哪个工具归哪个桶要**写出来**"， 而当时无处可写 —— 本方法就是那个"写出来的地方"。
   *
   * <p>★★ <b>2026-10-12 B3 试点的并集</b>：地图的 GM-only 读工具（{@code map.path}/{@code map.block}/{@code
   * map.overlaps}）随下放改标契约层的 {@link io.mosire.simos.ga.spi.GmOnlyRead}（域模块看不见 app 的标记），
   * 故这里**两个标记取并集**——漏掉契约层那支 = 这三条工具**悄悄进决策人桶**（权限放大，且没有任何症状）； 漏掉 app 那支 = 其余 12 条 GM-only
   * 读工具一起放大。两支各自都是"显式声明"，并集不放松任何一条判定。 （B4 收口后 app 的标记退役，这里只剩一支。）
   */
  private static List<AgentTool> readToolsFor(List<AgentTool> all, Role role) {
    if (role == Role.GM) {
      return all;
    }
    // ★★ 2026-10-12 B2b：过滤**规则**已迁入 simos-ga（ToolBuckets.decisionAgentReads）；两支 GM-only 标记
    //   （契约层 ga.spi.GmOnlyRead ∪ app 尚未退役的 GmOnlyRead）的**并集**仍在这里（唯一拼写点）——本类才同时看得见两支。
    return ToolBuckets.decisionAgentReads(
        all,
        tool -> tool instanceof GmOnlyRead || tool instanceof io.mosire.simos.ga.spi.GmOnlyRead);
  }

  @Override
  public String id() {
    return GaToolSource.ID;
  }

  @Override
  public List<AgentTool> listTools() {
    return source.listTools();
  }

  /** 静态工具集：订阅即时成立、永不变更，故返回空句柄（契约要求非 null）。 */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }
}
