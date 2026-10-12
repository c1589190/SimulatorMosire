package io.mosire.simos.app.tools;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.access.AppDecisionMakerAffiliations;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.decision.ProposalCatalog;
import io.mosire.simos.app.llm.AgentLibLlmConfig;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.app.skill.SkillLibrary;
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
import io.mosire.simos.app.tools.read.SkillTool;
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
import io.mosire.simos.ga.tools.read.GmApprovalsTool;
import io.mosire.simos.ga.tools.read.GmPacketTool;
import io.mosire.simos.ga.tools.read.GmPacketsTool;
import io.mosire.simos.ga.tools.read.GmToolUsageTool;
import io.mosire.simos.ga.tools.read.SdDiplomacyTool;
import io.mosire.simos.ga.tools.read.SdDiplomaticEventsTool;
import io.mosire.simos.ga.tools.read.SdDirectivesTool;
import io.mosire.simos.ga.tools.read.SdVerdictsTool;
import io.mosire.simos.ga.tools.read.SimosSdReportsTool;
import io.mosire.simos.ga.tools.write.GmApproveTool;
import io.mosire.simos.ga.tools.write.GmDecidePacketTool;
import io.mosire.simos.ga.tools.write.GmMergedPlanApplyTool;
import io.mosire.simos.ga.tools.write.GmMergedPlanUpsertTool;
import io.mosire.simos.ga.tools.write.GmPacketExecuteTool;
import io.mosire.simos.ga.tools.write.IssueDirectiveTool;
import io.mosire.simos.ga.tools.write.MyPacketTool;
import io.mosire.simos.ga.tools.write.PacketIntentTool;
import io.mosire.simos.ga.tools.write.ProposeCallTool;
import io.mosire.simos.ga.tools.write.RecordDiplomaticEventTool;
import io.mosire.simos.ga.tools.write.RejectDirectiveTool;
import io.mosire.simos.ga.tools.write.ResetDecisionMakerConversationTool;
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
import io.mosire.simos.ga.tools.write.SetDiplomaticRelationTool;
import io.mosire.simos.ga.tools.write.SimosSdReportTool;
import io.mosire.simos.ga.tools.write.StartDecisionTool;
import io.mosire.simos.ga.tools.write.SubmitPacketTool;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
      SkillLibrary skills,
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
        skills,
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
      SkillLibrary skills,
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
        skills,
        renderService,
        gmToolUsage,
        llmConfig,
        Map.of(),
        role,
        null,
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
   * @param skills Skill 库（外部 Markdown：决策方法论与常识）。★ **必填**：它是决策人"该怎么做决策"的唯一来源，
   *     装配期少一条不该退化成"模型自己猜"——故这里 {@code requireNonNull}，不搞"传 null 就不挂"的静默兜底。
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
      SkillLibrary skills,
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
        skills,
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
      SkillLibrary skills,
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
        skills,
        renderService,
        gmToolUsage,
        llmConfig,
        commandTargets,
        role,
        decisionAgent,
        pendingApprovals,
        approvalCoordinator);
  }

  /**
   * 生产全参装配（含 GM 审批面接线）：读工具两档共享；写面各自不同（{@link Role#GM} = 通用写 ∪ 全部窄写）， GM 桶另挂两条审批控制面工具（{@code
   * simos.gm.approvals} / {@code simos.gm.approve}）；历法时钟来自启动期 {@link CalendarService#load} 的同一实例。
   *
   * <p>★ 审批依赖**只被 {@link Role#GM} 用到**：两条工具标了 {@link GmOnlyRead} / 只加在 {@link #addGmWrites}，
   * 决策人桶里不出现；{@link Role#DECISION_AGENT} 下传 null 是合法装配（那两条根本不会被构造到可执行面）。
   *
   * @param calendarService 启动期装载的历法服务（非空；四个 gov 写工具按它取时钟）
   * @param pendingApprovals 进程内唯一的审批登记表（GM 裁决口读写它；null = 未接入 ⇒ 工具回 {@code UNAVAILABLE}）
   * @param approvalCoordinator 决策人链的审批编排器（成功回执取 {@code effectiveDecision(...)} 算实际生效 scope； null =
   *     未接入 ⇒ 工具回 {@code UNAVAILABLE}）
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
      SkillLibrary skills,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig,
      Map<String, CommandTargets> commandTargets,
      Role role,
      DecisionAgentService decisionAgent,
      PendingApprovals pendingApprovals,
      ApprovalCoordinator approvalCoordinator) {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(calendarService, "calendarService");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(worldgenConfigFile, "worldgenConfigFile");
    Objects.requireNonNull(commandTypes, "commandTypes");
    Objects.requireNonNull(embeddableCommandTypes, "embeddableCommandTypes");
    Objects.requireNonNull(skills, "skills");
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
                    skills,
                    renderService,
                    gmToolUsage,
                    llmConfig,
                    pendingApprovals),
                role));
    switch (role) {
      case GM -> {
        addGenericWrites(built, core, initiator, mapId);
        addGmWrites(
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
            approvalCoordinator);
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
        addDecisionAgentWrites(built, core, calendarService, query, initiator, mapId);
      }
    }
    // ★★ 2026-10-12 B4（设计书 §4.4 / 不变量 I-G3）：**装配完成处一次性校验本桶** —— 桶里每条工具都必须实现
    //   SimosTool 且 roles() 含本桶角色；不一致**当场抛**（fail-closed），不是静默少注册、也不是 warn。
    //   ★ 双桶那一半（"标了 DM 却没注册进 DM 桶"）单桶看不见，由 Shell 用 ToolRoleAudit.requireFaces 校验
    //     （那里同时拿得到两桶）。★ 校验是 O(n) 一次，不是每条工具建的时候各查一遍。
    ToolRoleAudit.requireFace(built, role == Role.GM ? ToolRole.GM : ToolRole.DM);
    this.source = GaToolSource.of(built);
  }

  /** GM 面必须带上决策人运行流（{@code ga.RunDecision} 没有它就只是半条工具）——缺了当场抛，不静默少一条。 */
  private static DecisionAgentService requireDecisionAgent(DecisionAgentService decisionAgent) {
    return Objects.requireNonNull(
        decisionAgent, "decisionAgent（GM 面含 ga.RunDecision，需要决策人 agent 运行流）");
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
  private static void addGmWrites(
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
      ApprovalCoordinator approvalCoordinator) {
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
    //   **只在 GM 桶**；命令本身打 {@code DmUsableCommand}（与既有 unit 命令同待遇）。★ 工具名不是命令类型 ⇒ 不进
    // catalog/PAYLOAD_HINTS。
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
    ProposalCatalog gmProposalCatalog =
        new ProposalCatalog(core, calendarService, query, initiator, mapId);
    GaProposalHost gaProposal = new AppGaProposalHost(gmProposalCatalog);
    GaEffectExecution gaEffects = new AppGaEffectExecution(core, gmProposalCatalog);
    built.add(new GmMergedPlanUpsertTool(gaWrites, gaReads, gaProposal));
    built.add(new GmMergedPlanApplyTool(gaReads, initiator, gaEffects));
    built.add(new GmPacketExecuteTool(gaReads, initiator, gaEffects));
    // ★★ D5（2026-10-02 / D-003、D-005、R6）：外交关系边 + 外交事件的 GM 窄写。
    //   **只在 GM 桶**；工具名（simos.sd.*）不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；写面只声明 sd。
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
  private static void addDecisionAgentWrites(
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
    // ★★ Z3c-2（2026-10-23 政府服务模式）：五条政府配置窄写——与 GM 桶**同名注册**（同一份实现按身份派生：
    //   GM 必须显式 govUnitId；决策人省略 = 自己的 GOV、给出别的 GOV ⇒ 越权 REJECTED）。敏感工具 ⇒
    //   AutoApproveGate → ConfirmGate → PendingApprovals（需 GM 点头）。
    //   **桶**（本方法）就是权限组白名单的来源（由 {@code Shell} 派生，不另立清单）。
    built.add(new GovSetEstablishmentTool(gov));
    built.add(new GovSetBudgetPolicyTool(gov));
    built.add(new GovAssignPostsTool(gov));
    built.add(new GovExpandHouseholdTool(gov));
    // ★★ Z3d（2026-10-23）：外部户承接行政任务（B 路 V1=显式选户）——与 GM 桶同名注册；只写 externalPosts +
    //   GOV_SERVICE 承诺，不改归属/位置；敏感工具 ⇒ AutoApproveGate → ConfirmGate → PendingApprovals。
    //   **桶**（本方法）就是权限组白名单的来源（由 {@code Shell} 派生，不另立清单）。
    built.add(new GovOpenPostsToMarketTool(gov));
    // ★★ A1（2026-10-08 汇率阶段 2 §3.1-3）：货币身份三件套的**决策人侧注册面**——与 GM 桶同名注册（同一份实现按身份
    //   派生：GM 必须显式 govUnitId；决策人省略 = 自己的 GOV、给出别的 GOV ⇒ 具名 REJECTED）。三条都是敏感工具 ⇒
    //   AutoApproveGate → ConfirmGate → PendingApprovals（需 GM 在审批面点头）。
    //   ★★ **桶（本方法）就是决策人白名单的来源**——{@code roles()} 是唯一声明，白名单由 {@code Shell}
    //      从这个桶派生（B4 起生产路径不再有手抄清单）。
    built.add(new GovDefineCurrencyTool(gov));
    built.add(new GovRenameCurrencyTool(gov));
    built.add(new GovIssueMoneyTool(gov));
    //   ★★ A2a（2026-10-08 汇率阶段 2 §3.3）：官方汇率 —— 同一形制（GM 桶 + 决策人桶同名两处注册 +
    //     决策人侧白名单/审批链）。★ 定下它就是"激活这个币对的外汇市场"（没定过 ⇒ 没有外汇面，逐值退回）。
    built.add(new GovSetFxRateTool(gov));
    // ★★ D2（2026-10-22 决策包计划）：决策包四件套——propose / submit / intent / my。
    //   **只在决策人桶**；白名单即由该桶派生。
    //   ProposeCallTool 内含 ProposalCatalog（真预览 + 目标提取）；submit/intent 走 own-packet 围栏。
    ProposalCatalog proposalCatalog =
        new ProposalCatalog(core, calendarService, query, initiator, mapId);
    GaProposalHost gaProposal = new AppGaProposalHost(proposalCatalog);
    built.add(new ProposeCallTool(gaWrites, gaReads, mapId, gaProposal, gaIdentity));
    built.add(new SubmitPacketTool(gaWrites, gaReads, gaIdentity));
    built.add(new PacketIntentTool(gaWrites, gaReads, gaIdentity));
    built.add(new MyPacketTool(gaReads, gaIdentity));
    // ★★ D4（2026-10-22）：跨区上报写口——发送人=调用者身份（context.identity()），只写自己的
    //   sd:decision-maker/<自己> 自指域；落一条 ga.PutInfo（key=report）= 一条 revision。
    //   **只在决策人桶**；白名单即由该桶派生。
    built.add(new SimosSdReportTool(gaWrites, gaReads, gaIdentity));
  }

  private static List<AgentTool> readTools(
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      SkillLibrary skills,
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
    GaToolIdentity gaIdentity = new AppGaToolIdentity();
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
        // ★★ D4（2026-10-22）：跨区上报读口（simos.sd.reports）——**两桶共享**（决策人桶 + GM 桶；不标
        //   GmOnlyRead）。决策人只看到 tags 含自己 / affiliations 含自己归属 / 自己发的报告，GM 读全部；
        //   读报告不授予任何跨区原始数据读权（报告内容由发送方负责）。
        new SimosSdReportsTool(gaReads, gaQueries, gaIdentity),
        // ★★ D1（2026-10-02 / D-012）：army 切片的交战记录读口（"当前 tick 在哪发生交战"的唯一直接读口）。
        //   四桶共享（世界状态、可回放）；逐条可见性按记录所在格判（ToolSupport.hexVisible）——看不见的格不进结果。
        //   形状与 GUI 同源（ApiViews.armyCombat）。
        new ArmyCombatsTool(armyReads, armyViews, armyPerspective, mapId),
        new ArmyCombatTool(armyReads, armyViews, armyPerspective, mapId),
        // ★ 判决（模型原始输出 + meta）：省略 actor = FULL 全量披露 ⇒ 只给 GM 桶（GmOnlyRead），见类注。
        new SdVerdictsTool(gaQueries),
        // ★★ D2（2026-10-22 决策包计划）：GM 决策包两条读口（摘要列表 + 单包全量）。
        //   只给 GM 桶（GmOnlyRead）：决策包是 GM 的裁决工作面，决策人用 simos.sd.packet.my 读自己的。
        new GmPacketsTool(gaReads),
        new GmPacketTool(gaReads),
        // ★ GM 面观测/配置读口（只给 GM 桶）：工具使用记录（运行时监督数据）与 LLM provider 掩码配置。
        new GmToolUsageTool(gmToolUsage, gaViews),
        // ★★ P7b（2026-10-01 后端 + MCP 稳定化计划）：待裁决审批清单（控制面；只在 GM 桶，GmOnlyRead）。
        //   读的是传入的 PendingApprovals（null = 未接入 ⇒ 执行期 UNAVAILABLE）；资源声明 NONE、不落 revision。
        new GmApprovalsTool(pendingApprovals),
        new LlmProvidersTool(llmConfig),
        // ★ Skill 系统（2026-09-23）：方法论与常识（外部 Markdown，改文件即生效）。**两桶共享**——
        //   决策人读它是本职，GM 读它是为了写出与之一致的文档（Docs）。
        new SkillTool(skills));
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
