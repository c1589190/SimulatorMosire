package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.read.ArmyCombatTool;
import io.mosire.simos.app.tools.read.ArmyCombatsTool;
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CalendarInfoTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.DecisionDocsTool;
import io.mosire.simos.app.tools.read.DecisionResultsTool;
import io.mosire.simos.app.tools.read.EconomyHexTool;
import io.mosire.simos.app.tools.read.GovInfoTool;
import io.mosire.simos.app.tools.read.MapHexTool;
import io.mosire.simos.app.tools.read.MapOverviewTool;
import io.mosire.simos.app.tools.read.MapRegionTool;
import io.mosire.simos.app.tools.read.MapRenderTool;
import io.mosire.simos.app.tools.read.PopulationTool;
import io.mosire.simos.app.tools.read.SdCombatsTool;
import io.mosire.simos.app.tools.read.SdDiplomacyTool;
import io.mosire.simos.app.tools.read.SdDiplomaticEventsTool;
import io.mosire.simos.app.tools.read.SimosSdReportsTool;
import io.mosire.simos.app.tools.read.SkillTool;
import io.mosire.simos.app.tools.read.SocialHouseholdsTool;
import io.mosire.simos.app.tools.read.StateFacetsTool;
import io.mosire.simos.app.tools.read.StateResolveTool;
import io.mosire.simos.app.tools.read.TimelineRevisionsTool;
import io.mosire.simos.app.tools.read.UnitGetTool;
import io.mosire.simos.app.tools.read.UnitListTool;
import io.mosire.simos.app.tools.write.GovAssignPostsTool;
import io.mosire.simos.app.tools.write.GovDefineCurrencyTool;
import io.mosire.simos.app.tools.write.GovExpandHouseholdTool;
import io.mosire.simos.app.tools.write.GovIssueMoneyTool;
import io.mosire.simos.app.tools.write.GovOpenPostsToMarketTool;
import io.mosire.simos.app.tools.write.GovPayTool;
import io.mosire.simos.app.tools.write.GovRenameCurrencyTool;
import io.mosire.simos.app.tools.write.GovSetBudgetPolicyTool;
import io.mosire.simos.app.tools.write.GovSetEstablishmentTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.MyPacketTool;
import io.mosire.simos.app.tools.write.PacketIntentTool;
import io.mosire.simos.app.tools.write.ProposeCallTool;
import io.mosire.simos.app.tools.write.RecordDiplomaticEventTool;
import io.mosire.simos.app.tools.write.SetDiplomaticRelationTool;
import io.mosire.simos.app.tools.write.SimosSdReportTool;
import io.mosire.simos.app.tools.write.SubmitPacketTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * **决策人的调用者工厂**（spec §2.2）：进程内、**权限组每次现算**、经 {@link ToolCallAuthorizer} 执行。
 *
 * <p>★ **为什么是进程内、不是 MCP 口**：AgentLib 的 caller 在 {@code startHttp} 时定死（一个端口一份权限集，spec §1.4 的
 * C3），表达不了"每个决策人一份权限"；而决策人的可见范围**随世界状态变**（国家圈地、军队移动）⇒ **每次调用现算**是唯一可行路径。 用户原话「决策人没有暴露 MCP，全在项目内用
 * Agentlib 相关接口实现」——那条裁定不只是权限收敛，是**实现上的必然**。
 *
 * <p>★ **必须经 {@link #execute}**：资源判定者只在 {@link ToolCallAuthorizer} 的第 ③ 段注入，手工拼一个 {@code
 * ToolContext} 后直接 {@code tool.execute(ctx)} 会让**每个** Simos 工具 `RESOURCE_DENIED` （它们都调 {@code
 * require}，而手工上下文的判定者是 {@code denying()} 的 fail-closed 缺省）。
 *
 * <p>★ **注入的 authorizer 必须带审批编排器**：两条决策窄写是敏感工具 ⇒ 自报 {@code ToolGate.Ask}；用 {@code
 * ToolCallAuthorizer.standard()}（不带编排器）会在审批段一律拒。
 *
 * <p>★ **白名单与桶两处同源**：{@link #WHITELIST} 是**权限组**那一半，{@code SimosToolSource.addDecisionAgentWrites}
 * 是**注册面**那一半（用户 2026-09-22：「决策人不能直接改地图等数据」）。 只收窄一处等于留一条旁路，故两处都要通过 {@code
 * DecisionCallerFactoryTest} 的派生式用例。
 */
public final class DecisionCallerFactory {

  /**
   * 决策人的工具白名单（spec §2.2）：**共享读工具 + 两条决策行为**（★ M4 起：读工具里标了 {@code GmOnlyRead} 的那些**不在**本白名单里——它们只进
   * GM 桶）。
   *
   * <p>★ 第 10 条读工具是第 3 波第 3 步的 {@link DecisionResultsTool}（决策人查看**自己**的决策结果，用户原话「允许决策人查看不同 tick
   * 的不同决策结果」）——它只挂决策人桶，读口经 {@code RedactingQueryService#decisionResults}（可见性 = 条目 tags 含自己）。
   *
   * <p>★ 第 11 条是 Docs 系统的 {@link DecisionDocsTool}（决策人查看**发给自己的设定文档**，2026-09-23）——同样只挂决策人桶，读口经
   * {@code RedactingQueryService#docs}（可见性 = tags 含自己 **∪** affiliations 含自己的归属）。★ 它与"能力面"（{@code
   * CatalogTool} 的权限过滤）是同一件事的两半：catalog 说"你能干什么"，docs 说"这一局里你该怎么做"。
   *
   * <p>★ 第 12 条是 Skill 系统的 {@link SkillTool}（**方法论与常识**，2026-09-23）——它**两桶共享**（GM 也要读同一份口径去写 Docs），
   * 读的是外部 Markdown 库（改文件即生效，不在世界 revision 内）。三件套到此齐了：skill 说"怎么做"，docs 说"这一局的情况"， catalog 说"你能调什么"。
   *
   * <p>★ **写面只有决策行为 + D5 的三条受限写入**：用户 2026-09-22「决策人不能直接改地图等数据，只能获取有限的、被 GM 权限层限制范围的信息」—— 旧 D-1
   * 裁定给决策人挂的 unit 域 20 条窄写**已撤销**。指挥走 {@code sd.IssueDirective}。★ D5（2026-10-02 /
   * D-003、D-004、D-005、R5、 R6）追加：{@link SetDiplomaticRelationTool}（外交边，from 必须是调用者 Nation）、{@link
   * RecordDiplomaticEventTool} （事件，participants 必须含调用者 Nation）、{@link GovPayTool}（付款人 = 调用者所属
   * GOV，身份派生）——三条都只挂决策人桶且都在本白名单里。
   *
   * <p>★ **不写 {@code ALL_TOOLS} 通配**：那等于"想用什么用什么"，白名单就退化成装饰。
   */
  public static final Set<String> WHITELIST =
      Set.of(
          CatalogTool.NAME,
          StateResolveTool.NAME,
          StateFacetsTool.NAME,
          BranchListTool.NAME,
          MapOverviewTool.NAME,
          MapHexTool.NAME,
          UnitListTool.NAME,
          UnitGetTool.NAME,
          PopulationTool.NAME,
          // ★ C7a（2026-10-02，确认缺陷）：历法读口是**四桶共享读**——SimosToolSource.readTools 里
          //   new CalendarInfoTool(...) 未标 GmOnlyRead，故决策人桶已含它；白名单必须与工具面两处同源
          //   （同 DEF-T1c-01 的 army 两条）。
          CalendarInfoTool.NAME,
          // ★ 工具面 M4（2026-09-24）：两条**共享**新读口（时间轴节点清单 / 区域详情）。
          //   ★ 另三条新读口（map.path / sd.decision-makers / sd.decision-maker）**有意不在**本白名单里：
          //   它们标了 GmOnlyRead（地形探测 / 别人的底牌），只进 GM 桶。
          TimelineRevisionsTool.NAME,
          // ★ P3（2026-09-24）：把世界渲染成图（决策人也要"看图"——这是"图像生成→喂给 LLM"在决策人面的入口）
          MapRenderTool.NAME,
          MapRegionTool.NAME,
          DecisionResultsTool.NAME,
          DecisionDocsTool.NAME,
          // ★ 工具面补齐（2026-09-25）：交战记录是**世界状态**（四桶共享）⇒ 决策人也该看得见。
          SdCombatsTool.NAME,
          // ★ T1c（2026-10-02，修 DEF-T1c-01）：两条 army 交战读口同属"世界状态·四桶共享"——
          //   决策人桶（SimosToolSource.readToolsFor）已含它们，白名单必须两处同源。
          ArmyCombatsTool.NAME,
          ArmyCombatTool.NAME,
          // ★ R2a（2026-09-25）：逐格经济读数是**世界状态**（四桶共享，同 combats 的判据）——决策人要看得见
          //   辖地的产出与库存；它走 ToolSupport.hexVisible 收窄视野，故不越界。
          EconomyHexTool.NAME,
          // ★★ D1（2026-10-22 决策包计划）：家户聚合读口是 GM 与决策人**共用**读工具——
          //   桶在 SimosToolSource.readTools（未标 GmOnlyRead），这里必须同源；scope=ALL 由工具内
          //   social 面 unrestricted 判定，决策人只能用受限 scope。
          SocialHouseholdsTool.NAME,
          SkillTool.NAME,
          // ★★ D5（2026-10-02 / R6）：两条外交读口（世界级自然语言、四桶共享读）。
          SdDiplomacyTool.NAME,
          SdDiplomaticEventsTool.NAME,
          IssueDirectiveTool.NAME,
          SubmitVerdictTool.NAME,
          // ★★ D5（2026-10-02 / D-003、D-004、D-005、R5、R6）：三条决策人受限窄写
          //   （桶在 SimosToolSource.addDecisionAgentWrites，与这里必须同源）。
          SetDiplomaticRelationTool.NAME,
          RecordDiplomaticEventTool.NAME,
          GovPayTool.NAME,
          // ★★ Z3c-2（2026-10-23 政府服务模式）：五条政府配置窄写（身份派生 + 决策人桶 + 审批链）
          //   + 一条 GOV 行政运行只读读口（视野收窄到自己 GOV）。
          //   **桶**（SimosToolSource.addDecisionAgentWrites / readTools）与 **白名单**（本集合）必须同源。
          GovSetEstablishmentTool.NAME,
          GovSetBudgetPolicyTool.NAME,
          GovAssignPostsTool.NAME,
          GovExpandHouseholdTool.NAME,
          // ★★ Z3d（2026-10-23）：外部岗位开放（B 路 V1=显式选户）——决策人只能自己的 GOV，走 GM 审批；
          //   桶（SimosToolSource.addDecisionAgentWrites）与本白名单必须同源。
          GovOpenPostsToMarketTool.NAME,
          GovInfoTool.NAME,
          // ★★ D2（2026-10-22 决策包计划）：决策包四件套（桶在 SimosToolSource.addDecisionAgentWrites）。
          ProposeCallTool.NAME,
          SubmitPacketTool.NAME,
          PacketIntentTool.NAME,
          MyPacketTool.NAME,
          // ★★ D4（2026-10-22）：跨区上报写 + 共享读（桶在 SimosToolSource.addDecisionAgentWrites 与
          //   readTools，两处必须同源）。simos.sd.reports 是决策人与 GM 共用读口（不标 GmOnlyRead）。
          SimosSdReportTool.NAME,
          SimosSdReportsTool.NAME,
          // ★★ A1（2026-10-08 汇率阶段 2 §3.1-3）：货币身份三件套的**决策人侧窄写**（定义币种 / 改显示名 /
          //   发行审计）——桶在 SimosToolSource.addDecisionAgentWrites，与这里必须同源（缺了它决策人侧会停在
          //   权限组：REJECTED 的工具名不在 allowed-tools 白名单内）。三条都是敏感工具 ⇒
          //   AutoApproveGate → ConfirmGate → PendingApprovals（需 GM 在审批面点头）；作用域由身份派生
          //   （只能自己的 GOV、越权 ⇒ 具名 REJECTED），不因为进了白名单而放大权限。
          GovDefineCurrencyTool.NAME,
          GovRenameCurrencyTool.NAME,
          GovIssueMoneyTool.NAME);

  /** 决策人身份的实例 id 前缀（与将来的会话 id 同源：按决策人派生，不隐式取全局状态）。 */
  public static final String INSTANCE_ID_PREFIX = "decision-maker:";

  /** {@code sd} 域里"决策人"这一类资源（spec §3.3：{@code sd: decision-maker/<id> · nation/<id> · …}）。 */
  public static final String DECISION_MAKER_KIND = "decision-maker";

  /**
   * {@code sd} 域里"决策包"这一类资源（D2）：{@code sd:decision-packet/<决策人 id>/…}——**按决策人归前缀**， 不是按 packet
   * id。决策人只能写/读自己的 packet 前缀（propose/submit/intent/my 的目标声明都是 {@code
   * decision-packet/<proposerId>}）。
   */
  public static final String DECISION_PACKET_KIND = "decision-packet";

  /** 决策人的派生目标（进身份的 {@code goal}，只进内存态提示面与审批提示，不进事件库）。 */
  public static final String GOAL =
      "在受限可见范围内做出决策：出令（sd.IssueDirective）、裁决（sd.SubmitVerdict）、外交写入（sd.SetDiplomaticRelation / sd.RecordDiplomaticEvent）、向 GOV 付款（simos.gov.pay）与决策包提议/提交/查看（simos.sd.propose / packet.submit / packet.intent / packet.my）";

  private final DecisionScopeFunctions scopeFunctions;
  private final ToolCallAuthorizer authorizer;
  private final Set<String> whitelist;

  public DecisionCallerFactory(
      DecisionScopeFunctions scopeFunctions, ToolCallAuthorizer authorizer) {
    this(scopeFunctions, authorizer, WHITELIST);
  }

  /**
   * **包内可见**的白名单接缝：生产路径只用上面那个构造器（{@link #WHITELIST}）。它存在是为了让用例能把**夹具工具**放进白名单，
   * 从而**单独**验资源维——否则夹具会先被白名单拦下，J9 的"一个放过一个拒"就退化成"两个都拒"。
   *
   * <p>★ 生产白名单本身由 {@code
   * DecisionCallerFactoryTest.theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites} 从真 GM
   * 面派生着钉死。
   */
  DecisionCallerFactory(
      DecisionScopeFunctions scopeFunctions, ToolCallAuthorizer authorizer, Set<String> whitelist) {
    this.scopeFunctions = Objects.requireNonNull(scopeFunctions, "scopeFunctions");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    // ★ 按传入顺序冻在赋值处：whitelistFor 非空时按这份顺序求交，结果顺序是"全局白名单"的纯函数。
    this.whitelist =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(Objects.requireNonNull(whitelist, "whitelist")));
  }

  /** 按 spec §2.2 装配：范围函数取两个内置实现（国家 / 军队），执行走调用方给的 authorizer（**带审批编排器**的那个）。 */
  public static DecisionCallerFactory defaults(ToolCallAuthorizer authorizer) {
    return new DecisionCallerFactory(DecisionScopeFunctions.defaults(), authorizer);
  }

  /**
   * 本工厂的**全局**工具白名单（T11B 的旧读口；生产路径 = {@link #WHITELIST}）。
   *
   * <p>★ **为什么保留它**：它仍是"全局默认面"的读口——旧档/旧夹具的空 {@code allowedTools} 要沿用它，旧调用点（尤其用例）也不该 因为 P6a 而破坏。★
   * **per-DM 的运行路径不要直接用它**：给模型看的工具面与权限组都走 {@link #whitelistFor(DecisionMaker)}， 否则 {@code
   * allowedTools} 又退回"展示字段"。
   */
  public Set<String> whitelist() {
    return whitelist;
  }

  /**
   * ★★ **某个决策人实际生效的工具白名单**（P6a / N9 修复）：空 {@code allowedTools} ⇒ 沿用本工厂的全局白名单 （兼容旧档与夹具；见 {@link
   * #whitelist()}）；非空 ⇒ {@code 全局白名单 ∩ dm.allowedTools()}。
   *
   * <p>★★ **为什么必须是交集**：{@code allowedTools} 只能**再收窄**——它不能把全局白名单里没有的工具放进来（那就是静默提权）。落在
   * 全局白名单外的条目自然失效；若某个全局工具名**同时**出现在 {@code allowedTools} 里但不在注册表，则由工具面装配期的 {@code
   * DecisionToolDefs.requireAll} 响亮失败（fail-closed，见该类的类注）。
   *
   * <p>★ **顺序/不可变语义与全局白名单一致**：结果按全局白名单的迭代序收窄并冻结（给模型看的 {@code ToolDef} 另外按名字排序， 见 {@code
   * DecisionToolDefs}）；空名单直接返回全局白名单本身（它已经是不可变集合）。
   *
   * <p>★ **权限组与工具面只从这里取**：{@link #permissionsFor} 用它是权限那一半，{@code DecisionAgentRunner} 用它建
   * "给模型看的工具面"；两处同源才不会出现"看得见调不动"或反过来（spec §2.3 要点 1）。
   */
  public Set<String> whitelistFor(DecisionMaker dm) {
    Objects.requireNonNull(dm, "dm");
    Set<String> allowed = dm.allowedTools();
    if (allowed.isEmpty()) {
      return whitelist;
    }
    Set<String> narrowed = new LinkedHashSet<>();
    for (String tool : whitelist) {
      if (allowed.contains(tool)) {
        narrowed.add(tool);
      }
    }
    return Collections.unmodifiableSet(narrowed);
  }

  /**
   * 建这次调用的上下文：**该决策人实际生效的白名单**（{@link #whitelistFor(DecisionMaker)}）+ **现算**的资源范围 + 子 agent 身份。
   *
   * <p>★ **GM 的额外限制来自决策人自己**（{@code dm.accessLimit()}，T9）：它随 revision 落盘 ⇒ 回放/分叉后
   * 逐字复原，不需要调用方另外传一份（"两份真相"是漏配的来源）。
   */
  public ToolContext callerFor(DecisionMaker dm, SimulationState state, String mapId) {
    return callerFor(dm, state, mapId, Map.of());
  }

  /**
   * 同上，另给**本次调用的宿主编排配置**（进 {@link ToolContext#config()}）。
   *
   * <p>★★ **它是什么、不是什么**（P4，2026-09-24）：这是 AgentLib 设计好的**宿主通道**——工具可以按"宿主此刻怎么部署的"
   * 调整自己的默认行为，而不是把部署事实写进世界或写进永久消息。目前**唯一的键是 {@code vision}**（该决策人绑的路由有没有视觉能力）， 用来让 {@code
   * simos.map.render} 的 {@code format=auto} 真的按"有视觉能力就发图"回落（见 {@code MapRenderTool}）。
   *
   * <p>★ **它不是权限**：配置不改可达面、不替代 {@code accessLimit}；工具**不得**把它的值当成许可（许可只从 {@code permissions()} 来）。
   *
   * <p>★ **键缺席 = 工具各自的历史行为**（{@code MapRenderTool} 那边是"出图"）：既有调用点走上面那个重载， 行为逐字不变。
   */
  public ToolContext callerFor(
      DecisionMaker dm, SimulationState state, String mapId, Map<String, Object> toolConfig) {
    // ★ 直接拼五参（AgentLib 的 ToolContext 没有 withConfig）：config 是唯一被替换的分量，其余与 callerFor 同源。
    return new ToolContext(
        AccessToken.DEFAULT,
        permissionsFor(dm, state, mapId),
        toolConfig,
        Map.of(),
        AgentIdentity.subagent(INSTANCE_ID_PREFIX + dm.id().value(), CommandMode.LIMITED, GOAL, 1));
  }

  /** 决策人的权限组（spec §2.2）：**该决策人实际生效的白名单**（{@link #whitelistFor(DecisionMaker)}）+ 现算范围 ∩ GM 额外限制。 */
  public AgentPermissionSet permissionsFor(DecisionMaker dm, SimulationState state, String mapId) {
    return permissionSetOf(scopeFunctions, whitelistFor(dm), dm, state, mapId);
  }

  /**
   * ★★ **GM 的额外限制怎么进判定**（T9，spec §4.2）——**这里是与范围函数求交的唯一写点**。
   *
   * <p>语义 = **交集**（{@link ResourceScopeMap#narrowTo}，"两边都要满足"）⇒ GM **只能额外收紧、不能放大**。写成 {@code
   * accessLimit} **直接覆盖**范围函数的结果，就是把 GM 的配权读成了"绝对指定"——那正是被取代的 {@code viewScope} 的语义，且**不会报错**：只看"DM
   * 能看见什么"的用例可能照样绿（限制恰好比范围窄时），只有"限制比范围**宽**"的用例才分得开。
   *
   * <p>★ **{@code sd} 命名空间仍取决策人自己的决策域**（{@link #selfDecisionScope}）：它是**机制**不是配权，GM 配的 {@code sd}
   * 前缀会与之求交（只能更窄），不会被顶掉。
   *
   * <p>★ **翻译点在 app 层**（spec §六.1）：sd 只存 {@code AccessLimit}（纯字符串），AgentLib 的 {@code ResourceScope}
   * 在这里现造。{@code {"map": []}}（显式空数组）= 该命名空间**够不着**（{@code ResourceScope.none()}）；**整个键缺席** =
   * 不表态（不收紧）—— 两条方向相反，见 {@link DecisionScopeFunction#scopeOfPrefixes}。
   */
  public static ResourceScopeMap resourceScopesFor(
      DecisionScopeFunctions scopeFunctions,
      DecisionMaker dm,
      SimulationState state,
      String mapId) {
    Objects.requireNonNull(scopeFunctions, "scopeFunctions");
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    ResourceScopeMap computed =
        scopeFunctions
            .scopesFor(dm, state, mapId)
            .withNamespace(ToolSupport.SD_NAMESPACE, selfDecisionScope(dm));
    return computed.narrowTo(toResourceScopes(dm.accessLimit()));
  }

  /**
   * {@code sd.AccessLimit} → AgentLib 的资源范围图（**唯一的翻译点**）。
   *
   * <p>★ 空前缀集 ⇒ {@link ResourceScope#none()}（显式"够不着"，不是"不表态"）；空图 ⇒ 不表态（{@code narrowTo}
   * 里原样放行范围函数的值）。
   */
  public static ResourceScopeMap toResourceScopes(AccessLimit limit) {
    Objects.requireNonNull(limit, "limit");
    Map<String, ResourceScope> byNamespace = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : limit.prefixesByNamespace().entrySet()) {
      byNamespace.put(entry.getKey(), DecisionScopeFunction.scopeOfPrefixes(entry.getValue()));
    }
    return ResourceScopeMap.of(byNamespace);
  }

  /** 权限组装配（实例与非实例两条路共用的**唯一**实现）。 */
  private static AgentPermissionSet permissionSetOf(
      DecisionScopeFunctions scopeFunctions,
      Set<String> whitelist,
      DecisionMaker dm,
      SimulationState state,
      String mapId) {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allow(whitelist.toArray(String[]::new))
        .sensitiveAllowed(true)
        .resourceScopes(resourceScopesFor(scopeFunctions, dm, state, mapId))
        .build();
  }

  /**
   * **读视角上下文**（GUI 的 {@code as=} 路径用，T9）：与决策人路径**同一个权限组装配**（{@link #permissionSetOf}，含那段 {@code
   * narrowTo}），只是资源判定者按**读**的 manifest 注入（{@link ToolSupport#ALL_READ}）。
   *
   * <p>★ **为什么要有它**：GUI 的脱敏读与决策人自己的读必须**看见同样的东西**——各写一份可见性规则的话，两边**都不会报错**， 只会慢慢漂移（"同一 actor
   * 在两个端点上裁剪强度不一致"）。这里让它们共用同一段装配，判定谓词也复用 {@code ToolSupport} 的那几个 ⇒ 改一处两边一起变。
   *
   * <p>★ **identity 与 {@link #callerFor} 同源**（同一个决策人派生）：GUI 的 {@code as=} 语义就是"以这个决策人的视角读"。
   */
  public static ToolContext readContextFor(
      DecisionScopeFunctions scopeFunctions,
      DecisionMaker dm,
      SimulationState state,
      String mapId) {
    Objects.requireNonNull(scopeFunctions, "scopeFunctions");
    AgentPermissionSet permissions = permissionSetOf(scopeFunctions, Set.of(), dm, state, mapId);
    return ToolContext.of(
            AccessToken.DEFAULT,
            permissions,
            AgentIdentity.subagent(
                INSTANCE_ID_PREFIX + dm.id().value(), CommandMode.LIMITED, GOAL, 1))
        // ★ 宿主注入判定者（不是工具自己发的许可）：ToolContext.of 的缺省是 denying()，直接用它会让每个读都判否。
        .withResources(ResourceAuthorizer.of(permissions, ToolSupport.ALL_READ));
  }

  /**
   * ★★ **决策人的 sd 域 = 它自己的决策域**（{@code sd:decision-maker/<自己的 id>}，T10）。
   *
   * <p>**为什么必须有这一条**：两条决策窄写（{@code sd.IssueDirective} / {@code sd.SubmitVerdict}）是
   * **决策行为**，不是"直接改地图/单位数据"（用户 2026-09-22 原话：「决策人不能直接改地图等数据」——下指令、交判决是它的**本职**， spec §2.2
   * 把这两条放进决策人白名单就是这条意思）。它们过去的资源声明是基类缺省的"三命名空间粗断言" ⇒ 粗断言撞细围栏，决策人**连出令都出不了**（T5-T8 实测发现 1）。
   *
   * <p>**为什么按 id 配前缀、而不是"不限"**：决策人只能**以自己名义**下决策。给 {@code unlimited()} 会让"谁都能以别人的名义落一条
   * directive"变成权限上允许的事——前缀恰好只覆盖自己那一条路径。
   *
   * <p>★ 与工具侧的**成对关系**：这里是**围栏**（可达面），{@code IssueDirectiveTool#writeResources} 是**断言**
   * （要写的具体资源，从调用者身份推出）。两处必须同源——只改一处 = 要么整调被拒（旧形态），要么围栏形同虚设。
   */
  private static ResourceScope selfDecisionScope(DecisionMaker dm) {
    // ★★ D2（2026-10-22 决策包计划）：第二条自指前缀 sd:decision-packet/<dm.id()>——决策人只能写/读**自己**的
    //   packet 前缀（propose/submit/intent/my 的目标声明）。这是自指资源的第二段，不是放宽到全 sd。
    return ResourceScope.of(
        decisionDomainOf(dm.id().value()), decisionPacketDomainOf(dm.id().value()));
  }

  /** 决策人自己的决策域路径（{@code decision-maker/<id>}）——**唯一拼写点**（工具侧也从这里取）。 */
  public static String decisionDomainOf(String decisionMakerId) {
    return ToolSupport.resourceSd(DECISION_MAKER_KIND, decisionMakerId).path();
  }

  /** 决策人自己的决策包前缀（{@code decision-packet/<id>}）——**唯一拼写点**（D2）。 */
  public static String decisionPacketDomainOf(String decisionMakerId) {
    return ToolSupport.resourceSd(DECISION_PACKET_KIND, decisionMakerId).path();
  }

  /**
   * 从身份里取出"这是我（哪个决策人）"：{@code AgentIdentity.instanceId()} 形如 {@code decision-maker:<id>} （与 {@link
   * #INSTANCE_ID_PREFIX} 同源、也是会话 id 的来源）。非决策人身份（如 GM 的 {@code external-mcp}）⇒ 空。
   *
   * <p>★ **为什么从身份取、不从载荷取**：载荷是**模型自己写的**（spec §1.3 第 1 条的"自报"形态）；宿主要判"这次调用是谁在做" 只能看宿主已知的东西（spec
   * §4.3：身份从 {@code ToolContext} 来，不由参数自报）。
   */
  public static Optional<String> decisionMakerIdOf(AgentIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    String instanceId = identity.instanceId();
    return instanceId.startsWith(INSTANCE_ID_PREFIX)
        ? Optional.of(instanceId.substring(INSTANCE_ID_PREFIX.length()))
        : Optional.empty();
  }

  /**
   * 从**身份 + 世界状态**解出"这次调用是哪个决策人在做"（T11）：身份给出 id，sd 切片给出这个人。
   *
   * <p>★ **两道解析各自可能落空，都返回空**（fail-closed）：① 身份不是决策人（GM 的 {@code external-mcp}、 或 {@code
   * AgentIdentity.UNKNOWN}——四参 {@code ToolContext} 的缺省）；② id 在 sd 切片里查无此人（决策人被删、或状态与身份不同源）。
   * 调用方据此**不给**派生信息，而不是"大家都给一份"。
   *
   * <p>★ 与 {@link #decisionMakerIdOf} 一样，**输入只能是宿主已知的东西**（{@code context.identity()}）：模型自报在载荷里的 id
   * 不参与这件事（spec §4.3）。
   */
  public static Optional<DecisionMaker> decisionMakerOf(
      ToolContext context, SimulationState state) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(state, "state");
    return decisionMakerIdOf(context.identity())
        .map(id -> ToolSupport.sdState(state).decisionMakers().get(new DecisionMakerId(id)));
  }

  /**
   * 这次调用者若是**国家决策人**，它是哪个国家；其余一律空（T11 的视图分派依据）。
   *
   * <p>★ **军队决策人返回空，不是"它所属的国家"**：spec §3.4 的字段表里"邻国标识"只挂在**国家决策人**那一行 —— 军队决策人要知道的是**每个格的归属国家**（由
   * {@code map.hex} 的 {@code nation} 给）， 不是"我这一圈与哪些国家接壤"。两条信息不同源，别互相顶替。
   */
  public static Optional<NationId> viewerNationOf(ToolContext context, SimulationState state) {
    return decisionMakerOf(context, state)
        .map(DecisionMaker::affiliation)
        .filter(Affiliation.Nation.class::isInstance)
        .map(nation -> ((Affiliation.Nation) nation).nationId());
  }

  /**
   * 以给定上下文执行一次工具调用——**唯一入口**（判定链五段全走一遍，与 MCP 口同一条路）。
   *
   * <p>★ **上下文必须由 {@link #callerFor} 建**（或至少由它改写而来）：资源判定者只在 {@link ToolCallAuthorizer} 的第 ③
   * 段注入，手工拼一个 {@code ToolContext} 后直接 {@code tool.execute(ctx)} 会让每个调 {@code require} 的工具
   * `RESOURCE_DENIED`（那是"没有判定者"的 fail-closed 缺省，不是"拒"）。
   *
   * @param registry 工具注册表（生产路径应给决策人桶的注册表：{@code SimosToolSource.Role.DECISION_AGENT} 的工具面）
   * @param toolName 工具名（模型给出；不受信）
   * @param ctx 调用上下文（身份 + 权限组 + 本次参数）
   */
  public ToolResult execute(ToolRegistry registry, String toolName, ToolContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    return authorizer.execute(Objects.requireNonNull(registry, "registry"), toolName, ctx);
  }

  /**
   * 便利形态：按决策人现算上下文、把参数装进去再执行（{@link #execute(ToolRegistry, String, ToolContext)} 的壳）。
   *
   * @param args 本次调用的参数（模型填入的 JSON）
   */
  public ToolResult execute(
      ToolRegistry registry,
      String toolName,
      DecisionMaker dm,
      SimulationState state,
      String mapId,
      Map<String, Object> args) {
    return execute(registry, toolName, dm, state, mapId, args, Map.of());
  }

  /**
   * 同上，另给**宿主编排配置**（与 {@link #callerFor(DecisionMaker, SimulationState, String, Map)} 同一个 map，
   * 见其类注：目前唯一用途是让渲染工具按视觉能力选 {@code auto} 的落点）。
   */
  public ToolResult execute(
      ToolRegistry registry,
      String toolName,
      DecisionMaker dm,
      SimulationState state,
      String mapId,
      Map<String, Object> args,
      Map<String, Object> toolConfig) {
    ToolContext caller = callerFor(dm, state, mapId, toolConfig);
    // ★ 参数装进上下文（工具从 context.arguments() 读）；其余分量逐字来自 callerFor。
    return execute(
        registry,
        toolName,
        new ToolContext(
            caller.caller(), caller.permissions(), caller.config(), args, caller.identity()));
  }
}
