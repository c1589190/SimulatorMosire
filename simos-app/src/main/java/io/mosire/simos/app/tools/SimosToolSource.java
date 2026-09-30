package io.mosire.simos.app.tools;

import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.llm.AgentLibLlmConfig;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.app.skill.SkillLibrary;
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.DecisionDocsTool;
import io.mosire.simos.app.tools.read.DecisionMakerTool;
import io.mosire.simos.app.tools.read.DecisionMakersTool;
import io.mosire.simos.app.tools.read.DecisionResultsTool;
import io.mosire.simos.app.tools.read.EconomyHexTool;
import io.mosire.simos.app.tools.read.EconomyOwnershipTool;
import io.mosire.simos.app.tools.read.GmToolUsageTool;
import io.mosire.simos.app.tools.read.LlmProvidersTool;
import io.mosire.simos.app.tools.read.MapBlockTool;
import io.mosire.simos.app.tools.read.MapHexTool;
import io.mosire.simos.app.tools.read.MapOverviewTool;
import io.mosire.simos.app.tools.read.MapPathTool;
import io.mosire.simos.app.tools.read.MapRegionTool;
import io.mosire.simos.app.tools.read.MapRenderTool;
import io.mosire.simos.app.tools.read.PopulationTool;
import io.mosire.simos.app.tools.read.SdCombatsTool;
import io.mosire.simos.app.tools.read.SdVerdictsTool;
import io.mosire.simos.app.tools.read.SkillTool;
import io.mosire.simos.app.tools.read.StateFacetsTool;
import io.mosire.simos.app.tools.read.StateResolveTool;
import io.mosire.simos.app.tools.read.TimelineRevisionsTool;
import io.mosire.simos.app.tools.read.UnitGetTool;
import io.mosire.simos.app.tools.read.UnitListTool;
import io.mosire.simos.app.tools.write.ActorAdjustAccountsTool;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.app.tools.write.AdvanceTool;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.app.tools.write.EconomyAdjustTool;
import io.mosire.simos.app.tools.write.ForkTool;
import io.mosire.simos.app.tools.write.IssueDebtTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.LevyRegionTool;
import io.mosire.simos.app.tools.write.MapCreateRegionTool;
import io.mosire.simos.app.tools.write.MapDeleteRegionTool;
import io.mosire.simos.app.tools.write.MapRandomizeRegionTool;
import io.mosire.simos.app.tools.write.MapRegisterPathwayGroupTool;
import io.mosire.simos.app.tools.write.MapSetEdgeTool;
import io.mosire.simos.app.tools.write.MapSetTerrainTool;
import io.mosire.simos.app.tools.write.MapUpdateRegionTool;
import io.mosire.simos.app.tools.write.RaiseUnitTool;
import io.mosire.simos.app.tools.write.RejectDirectiveTool;
import io.mosire.simos.app.tools.write.RepayDebtTool;
import io.mosire.simos.app.tools.write.ResetDecisionMakerConversationTool;
import io.mosire.simos.app.tools.write.RunDecisionTool;
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
import io.mosire.simos.app.tools.write.SetDecisionMakerAccessTool;
import io.mosire.simos.app.tools.write.StartDecisionTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
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
import io.mosire.simos.app.tools.write.UnitSetFormationOffsetTool;
import io.mosire.simos.app.tools.write.UnitSetGovFormationTool;
import io.mosire.simos.app.tools.write.UnitSetGovPolicyTool;
import io.mosire.simos.app.tools.write.UnitSetGovSuperiorTool;
import io.mosire.simos.app.tools.write.UnitSetJurisdictionTool;
import io.mosire.simos.app.tools.write.UnitSetRejoinTargetTool;
import io.mosire.simos.app.tools.write.UnitSetStatusTool;
import io.mosire.simos.app.tools.write.UnitSetStrengthTool;
import io.mosire.simos.app.tools.write.UnitSetTaxRateTool;
import io.mosire.simos.app.tools.write.UnitSplitFormationTool;
import io.mosire.simos.app.tools.write.UnitUpdateCommandChainTool;
import io.mosire.simos.app.tools.write.VoidAdjudicationTool;
import io.mosire.simos.app.tools.write.WorldgenInitializeTool;
import io.mosire.simos.core.CoreSimos;
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

  /** 供给源标识（同一运行时内唯一；审计与按源卸载依赖它）。 */
  public static final String ID = "simos";

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
     * **决策人组**（spec §2.2/§四.3）：**只有决策行为**（{@code sd.IssueDirective} / {@code sd.SubmitVerdict}）+
     * 读工具。**无通用写、无 map/unit/sd 的写工具**（用户 2026-09-22：「决策人不能直接改地图等数据， 只能获取有限的、被 GM 权限层限制范围的信息」）——旧
     * D-1 裁定把 unit 域 20 条窄写挂进本桶，**已撤销**。
     */
    DECISION_AGENT
  }

  private final List<AgentTool> tools;

  /**
   * 决策人桶的装配（**不带目标表**）：{@link Role#DECISION_AGENT} 走这条——本桶不含 {@code sd.AdjudicateTick}（它是 GM
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
        null);
  }

  /**
   * 全参装配：读工具两档共享；写面各自不同（{@link Role#GM} = 通用写 ∪ 全部窄写）。
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
    Objects.requireNonNull(core, "core");
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
                    query,
                    mapId,
                    commandTypes,
                    embeddableCommandTypes,
                    skills,
                    renderService,
                    gmToolUsage,
                    llmConfig),
                role));
    switch (role) {
      case GM -> {
        addGenericWrites(built, core, initiator, mapId);
        addGmWrites(
            built,
            core,
            query,
            initiator,
            mapId,
            worldgenConfigFile,
            embeddableCommandTypes,
            commandTargets,
            requireDecisionAgent(decisionAgent));
      }
      case DECISION_AGENT -> {
        // ★ 第 3 波第 3 步：决策人**只读**自己能看的决策结果（用户原话「允许决策人查看不同 tick 的不同决策结果」）。
        //   **只在决策人桶**——GM 侧看不到它（GM 有 GUI 面与 sd 只读全权，不靠这条）；它的可见性判据在
        //   RedactingQueryService#decisionResults（tags 含调用者自己）。
        built.add(new DecisionResultsTool(query, mapId));
        // ★ Docs 系统（2026-09-23）：决策人**只读**发给自己的设定文档。同样**只在决策人桶**（GM 侧有 GUI 的文档子页，
        //   可按任意决策人的视角预览实际可见集合）；可见性判据在 RedactingQueryService#docs
        //   （tags 含调用者自己 **或** affiliations 含调用者归属，两轴取并集）。
        built.add(new DecisionDocsTool(query, mapId));
        addDecisionAgentWrites(built, core, initiator, mapId);
      }
    }
    this.tools = List.copyOf(built);
  }

  /** GM 面必须带上决策人运行流（{@code sd.RunDecision} 没有它就只是半条工具）——缺了当场抛，不静默少一条。 */
  private static DecisionAgentService requireDecisionAgent(DecisionAgentService decisionAgent) {
    return Objects.requireNonNull(
        decisionAgent, "decisionAgent（GM 面含 sd.RunDecision，需要决策人 agent 运行流）");
  }

  /** 通用写（spec §八.3）：submit / advance / fork —— 只有 {@link Role#GM} 有。 */
  private static void addGenericWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new CommandSubmitTool(core, initiator, mapId));
    built.add(new AdvanceTool(core, initiator, mapId));
    built.add(new ForkTool(core, initiator));
  }

  /**
   * GM 窄写（N11）：sd 域两条决策窄工具 + 配权工具（`sd.SetDecisionMakerAccess`）+ 「开始决策」（T10）+ **map 域与 unit 域的窄写**。
   *
   * <p>★ **窄写工具进的是 GM 组**（不是通用写、也不是决策人组）：它们的命令类型在工具里固定死，模型只能给载荷 ——与 {@code simos.command.submit}
   * 的"自选 type"相对（spec §八.3）。{@link Role#GM} 是运行时 MCP 口，因此口上也含这些。★ **T11C 的那条（{@code
   * sd.RunDecision}） 是唯一的例外形态**：它落一条触发事实之后**还要跑一轮真 LLM**，故 GM 面需要{@link
   * DecisionAgentService}（见那个构造器）。
   *
   * <p>★ **M3（spec §八.3）：sd 域余下的写命令也在此追加**——命令类型固定、与 map/unit 窄写同形。★ **新窄写一律加在这里**， 加完**不必回来改本注**
   * （**本注刻意不写条数**：数字是漂移源；M3 起本文件与 {@code Shell} 的工具面注释一律不钉条数）。
   */
  private static void addGmWrites(
      List<AgentTool> built,
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Path worldgenConfigFile,
      Set<String> embeddableCommandTypes,
      Map<String, CommandTargets> commandTargets,
      DecisionAgentService decisionAgent) {
    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
    built.add(new SetDecisionMakerAccessTool(core, initiator, mapId));
    // 「把某个决策人的会话换一段新的」：只在 GM 桶——决策人不重置自己（那等于给自己开一个"忘掉刚才答应过什么"的按钮）。
    built.add(new ResetDecisionMakerConversationTool(core, initiator, mapId));
    built.add(new StartDecisionTool(core, initiator, mapId));
    // T11C：触发**决策人自己**跑一轮（真 LLM + 真工具）。★ **只在 GM 桶**——决策人不触发自己（那是自环）。
    built.add(new RunDecisionTool(core, initiator, mapId, decisionAgent));
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
    built.add(new RejectDirectiveTool(core, initiator, decisionAgent));
    // 2026-09-23：GM 世界初始化 —— 把聚落生成器接到真写路径（1 条 SetPopulation + N 条 CreateCity + 1 条 economy.Seed，
    //   一批一条 revision；dryRun 只算不写）。★ 它不是某一条命令的窄封装 ⇒ 不继承 AbstractNarrowWriteTool；★
    //   也不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。只在 GM 桶。
    built.add(new WorldgenInitializeTool(core, initiator, mapId, worldgenConfigFile));
    built.add(new MapSetTerrainTool(core, initiator, mapId));
    built.add(new MapSetEdgeTool(core, initiator, mapId));
    built.add(new MapCreateRegionTool(core, initiator, mapId));
    built.add(new MapUpdateRegionTool(core, initiator, mapId));
    built.add(new MapDeleteRegionTool(core, initiator, mapId));
    built.add(new MapRandomizeRegionTool(core, initiator, mapId));
    built.add(new MapRegisterPathwayGroupTool(core, initiator, mapId));
    // M2（spec §八.3）：unit 域窄写 —— ★ **只在 GM 组**（D-1 曾让它们也挂决策人桶，2026-09-22 已撤销）。
    built.add(new UnitRenameTool(core, initiator, mapId));
    built.add(new UnitCreateTool(core, initiator, mapId));
    built.add(new UnitReparentTool(core, initiator, mapId));
    built.add(new UnitSetStrengthTool(core, initiator, mapId));
    built.add(new UnitPlaceAtTool(core, initiator, mapId));
    built.add(new UnitPlanRouteTool(core, initiator, mapId));
    built.add(new UnitCancelRouteTool(core, initiator, mapId));
    built.add(new UnitDisbandTool(core, initiator, mapId));
    built.add(new UnitSetStatusTool(core, initiator, mapId));
    built.add(new UnitAttachTool(core, initiator, mapId));
    built.add(new UnitDetachTool(core, initiator, mapId));
    built.add(new UnitReparentSubtreeTool(core, initiator, mapId));
    built.add(new UnitSetFormationOffsetTool(core, initiator, mapId));
    built.add(new UnitSplitFormationTool(core, initiator, mapId));
    built.add(new UnitMergeFormationTool(core, initiator, mapId));
    built.add(new UnitPlanSparseRouteTool(core, initiator, mapId));
    built.add(new UnitSetRejoinTargetTool(core, initiator, mapId));
    built.add(new UnitCreateCommandChainTool(core, initiator, mapId));
    built.add(new UnitUpdateCommandChainTool(core, initiator, mapId));
    built.add(new UnitApplyCasualtiesTool(core, initiator, mapId));
    // 辖区阶段 5（2026-09-30）：管辖区域集合 + 长期税率。**只在 GM 桶**（与既有 unit 窄写同待遇）。
    built.add(new UnitSetJurisdictionTool(core, initiator, mapId));
    built.add(new UnitSetTaxRateTool(core, initiator, mapId));
    // 阶段 10a（2026-09-30 GOV/Army 计划）：两条"立编制"命令的窄封装（编制字段在 Unit.module ⇒ 是 unit 域命令）。
    //   **只在 GM 桶**；决策人侧要改编制仍走 sd.IssueDirective 的审批链（这两条命令非 GmOnly，可嵌入令）。
    built.add(new UnitSetGovFormationTool(core, initiator, mapId));
    built.add(new UnitSetArmyFormationTool(core, initiator, mapId));
    // 阶段 10b-i（2026-10-01 GOV 计划）：GOV 政策 / 上级层级两条窄工具。**只在 GM 桶**。
    //   ★ **有意不为 unit.RecruitStaff / unit.DismissStaff 配窄工具**：那会变成"凭空造人 / 跳过退休支付"的
    //   直通口。两条命令本身已注册（非 GmOnly），由 10b-ii 的配套工具批（social.SeedGroups + RecruitStaff +
    //   sd.PutInfo / actor 支付 + DismissStaff + social 回写）或决策令批使用。
    built.add(new UnitSetGovPolicyTool(core, initiator, mapId));
    built.add(new UnitSetGovSuperiorTool(core, initiator, mapId));
    // 辖区阶段 6（2026-09-30 / 计划 §6.2）：actor 净增量账原语（app 级抽取/组军组合工具的落账腿）。
    //   **只在 GM 桶**：命令类型固定，模型只能给载荷；整条原子由域层判。
    built.add(new ActorAdjustAccountsTool(core, initiator, mapId));
    // 辖区阶段 6（2026-09-30 / 计划 §6.1）：GM 组合工具——一次抽粮/钱/人力，三条命令同批落一条 revision。
    //   **只在 GM 桶**；★ 它不是一条命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。actor.AdjustAccounts 已标
    //   GmOnlyCommand ⇒ 这条组合工具的上限/管辖区口径不会被"嵌进决策令"绕过。
    built.add(new LevyRegionTool(core, query, initiator, mapId));
    // 辖区阶段 7 第二段（2026-09-30 / 计划 §4）：地方债 GM 组合工具——economy.UnitBorrow/UnitRepay + 国库
    //   入/出账 + sd.PutInfo 行动记录，三条命令同批落一条 revision（单提原语会造成悬空腿）。
    //   **只在 GM 桶**；★ 两个工具名都不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS。
    built.add(new IssueDebtTool(core, query, initiator, mapId));
    built.add(new RepayDebtTool(core, query, initiator, mapId));
    // 辖区阶段 8（2026-09-30 / 计划 §5）：组军 GM 组合工具——从地方抽人力 + 抽粮/钱，同批 unit.CreateUnit +
    //   actor.AdjustAccounts + social.SeedGroups + sd.PutInfo，四条命令同批落一条 revision。**只在 GM 桶**；
    //   ★ 工具名不是命令类型 ⇒ 不进 catalog/PAYLOAD_HINTS；分摊与 levyRegion 共用 RegionAllocations 一份瀑布。
    built.add(new RaiseUnitTool(core, query, initiator, mapId));
    // M3（spec §八.3）：sd 域余下的写命令 —— ★ **只在 GM 桶**（决策人桶有意不含这批）。
    built.add(new SdCreateNationTool(core, initiator, mapId));
    built.add(new SdCreateArmyTool(core, initiator, mapId));
    built.add(new SdCreateDecisionMakerTool(core, initiator, mapId));
    built.add(new SdPutInfoTool(core, initiator, mapId));
    built.add(new SdCreateCombatTool(core, initiator, mapId));
    built.add(new SdAddCombatStageTool(core, initiator, mapId));
    built.add(new SdSetStageOutcomeTableTool(core, initiator, mapId));
    built.add(new SdCommitCombatOutcomeTool(core, initiator, mapId));
    built.add(new SdRecordCasualtiesTool(core, initiator, mapId));
    built.add(new SdRegisterEffectTool(core, initiator, mapId));
    built.add(new SdCancelEffectTool(core, initiator, mapId));
    built.add(new SdSetDecisionMakerProviderTool(core, initiator, mapId));
    // ★★ E6b：GM 经济调整（economy.GmAdjust 的窄封装：预览 / 原因 / 前后差异 / 审计）。
    //   **只在 GM 桶**：决策人桶（addDecisionAgentWrites）没有它，DecisionCallerFactory.WHITELIST 也没有它，
    //   且 economy.GmAdjust 本身标了 GmOnlyCommand（令/RegisterEffect/决策人 catalog 三条路径都排除）。
    built.add(new EconomyAdjustTool(core, query, initiator, mapId));
  }

  /**
   * 决策人组的写面（N9 + 用户 2026-09-22 的收窄）：**只有决策行为** —— {@code sd.IssueDirective}（出令）与 {@code
   * sd.SubmitVerdict}（裁决者冻结判决）。
   *
   * <p>★ **无** `sd.SetDecisionMakerAccess`（配权是 GM 的活）、**无**通用写、**无** map/unit/sd
   * 的任何其他写工具：用户原话「决策人不能直接改地图等数据， 只能获取有限的、被 GM 权限层限制范围的信息」。指挥一律走 `sd.IssueDirective`（spec §八.2 的 D2
   * 原设计）。
   *
   * <p>★ **本清单要与 {@code DecisionCallerFactory} 的白名单同源**：那边给的是**权限组**（工具名白名单），这里给的是**桶**（注册进 MCP
   * 口用）——两处都收窄才算"改不掉"，只改一处等于留一条旁路。
   *
   * <p>★★ <b>E6b 负向证明：{@code simos.economy.adjust} 不在决策人可达面上</b>——它只在上面的 {@link #addGmWrites}
   * 里注册（本桶没有）；{@code DecisionCallerFactory.WHITELIST} 也不含 {@link EconomyAdjustTool#NAME}；它封装 {@code
   * economy.GmAdjust}，而该命令标了 {@code GmOnlyCommand} ⇒ 令白名单 / {@code RegisterEffect} / 决策人 catalog
   * 三条路径同样排除。三层同源收窄，缺一层就等于留一条绕过政治能力的入口。
   */
  private static void addDecisionAgentWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
  }

  private static List<AgentTool> readTools(
      CoreSimos core,
      QueryService query,
      String mapId,
      Set<String> commandTypes,
      Set<String> embeddableCommandTypes,
      SkillLibrary skills,
      RenderService renderService,
      GmToolUsage gmToolUsage,
      AgentLibLlmConfig llmConfig) {
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
        new TimelineRevisionsTool(core),
        new MapOverviewTool(query, mapId),
        new MapHexTool(query, mapId),
        new MapRegionTool(query, mapId),
        new MapPathTool(query),
        // ★ 工具面补齐（2026-09-25）：GUI `/api/map/block` 的对应读口——某格所在的**整块地形**（成员格清单）。
        //   与 hex（单格）/ overview（全块多边形）不同形；GUI 该端点拒 as= ⇒ 只给 GM 桶（GmOnlyRead）。
        new MapBlockTool(query),
        new UnitListTool(query),
        new UnitGetTool(query),
        new PopulationTool(query),
        // ★ R2a（2026-09-25）：逐格经济读数（GUI `/api/economy/hex` 的对应读口；**四桶共享**——经济是世界状态，
        //   决策人该看得见辖地的产出与库存；视野由 ToolSupport.hexVisible 收窄）。
        new EconomyHexTool(query, mapId),
        // ★ H0.6（2026-09-27）：产权读口 —— 行侧家户库存与 actor 侧 GoodsAccount **并排**发（堵"把一本账读成全系统"）。
        //   只在 GM 桶（GmOnlyRead）：响应含 actor 切片的商品余额，而 actor 面在本仓的资源表态里是缺省拒。
        new EconomyOwnershipTool(query, mapId),
        // ★ P3（2026-09-24）：把世界渲染成图——中心+半径、可选图层；图片随结果出站（MCP ImageContent / 决策人图片分片）
        new MapRenderTool(query, renderService),
        // ★ 同上：sd 侧的两条（决策人清单 / 详情）——此前只能 `sd.CreateDecisionMaker` 写、写完看不见。
        new DecisionMakersTool(query),
        // ★ 2026-09-25：详情并入 GUI 的 `/{id}/scope`（现算可见范围）——同一资源的两个端点合成一条工具。
        new DecisionMakerTool(query, mapId),
        // ★ 工具面补齐（2026-09-25）：交战记录（世界状态 ⇒ 四桶共享，复用 ApiViews.combats）。
        new SdCombatsTool(query),
        // ★ 判决（模型原始输出 + meta）：省略 actor = FULL 全量披露 ⇒ 只给 GM 桶（GmOnlyRead），见类注。
        new SdVerdictsTool(query, mapId),
        // ★ GM 面观测/配置读口（只给 GM 桶）：工具使用记录（运行时监督数据）与 LLM provider 掩码配置。
        new GmToolUsageTool(gmToolUsage),
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
   */
  private static List<AgentTool> readToolsFor(List<AgentTool> all, Role role) {
    if (role == Role.GM) {
      return all;
    }
    List<AgentTool> shared = new ArrayList<>(all.size());
    for (AgentTool tool : all) {
      if (!(tool instanceof GmOnlyRead)) {
        shared.add(tool);
      }
    }
    return List.copyOf(shared);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<AgentTool> listTools() {
    return tools;
  }

  /** 静态工具集：订阅即时成立、永不变更，故返回空句柄（契约要求非 null）。 */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }
}
