package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.MapHexTool;
import io.mosire.simos.app.tools.read.MapOverviewTool;
import io.mosire.simos.app.tools.read.PopulationTool;
import io.mosire.simos.app.tools.read.StateFacetsTool;
import io.mosire.simos.app.tools.read.StateResolveTool;
import io.mosire.simos.app.tools.read.UnitGetTool;
import io.mosire.simos.app.tools.read.UnitListTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
   * 决策人的工具白名单（spec §2.2）：**9 条读工具 + 两条决策行为**。
   *
   * <p>★ **一条写工具都没有**（除两条决策行为外）：用户 2026-09-22「决策人不能直接改地图等数据，只能获取有限的、被 GM 权限层限制范围的信息」——旧 D-1
   * 裁定给决策人挂的 unit 域 20 条窄写**已撤销**。指挥走 {@code sd.IssueDirective}。
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
          IssueDirectiveTool.NAME,
          SubmitVerdictTool.NAME);

  /** 决策人身份的实例 id 前缀（与将来的会话 id 同源：按决策人派生，不隐式取全局状态）。 */
  public static final String INSTANCE_ID_PREFIX = "decision-maker:";

  /** 决策人的派生目标（进身份的 {@code goal}，只进内存态提示面与审批提示，不进事件库）。 */
  public static final String GOAL = "在受限可见范围内做出决策：出令（sd.IssueDirective）与裁决（sd.SubmitVerdict）";

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
    this.whitelist = Set.copyOf(Objects.requireNonNull(whitelist, "whitelist"));
  }

  /** 按 spec §2.2 装配：范围函数取两个内置实现（国家 / 军队），执行走调用方给的 authorizer（**带审批编排器**的那个）。 */
  public static DecisionCallerFactory defaults(ToolCallAuthorizer authorizer) {
    return new DecisionCallerFactory(DecisionScopeFunctions.defaults(), authorizer);
  }

  /** 无 GM 额外限制的调用者（生产路径当前的形态；{@code accessLimit} 属 T9，sd 域还没有那个字段）。 */
  public ToolContext callerFor(DecisionMaker dm, SimulationState state, String mapId) {
    return callerFor(dm, state, mapId, null);
  }

  /**
   * 建这次调用的上下文：白名单（{@link #WHITELIST}）+ **现算**的资源范围 + 子 agent 身份。
   *
   * @param gmAccessLimit ★ **T9 的接缝**：GM 用 {@code sd.SetDecisionMakerAccess} 配的**额外限制**，语义 = **交集**
   *     （{@link ResourceScopeMap#narrowTo}）——GM 只能额外收紧、不能放大（那是 AgentLib {@code narrowTo}/{@code
   *     covers} 的原生保证）。本阶段 sd 域**还没有** {@code accessLimit} 字段 ⇒ 生产路径恒传 {@code null} （=
   *     无额外限制）；参数位先立在这里，T9 接上时**只改这一个调用点**。
   */
  public ToolContext callerFor(
      DecisionMaker dm, SimulationState state, String mapId, ResourceScopeMap gmAccessLimit) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    ResourceScopeMap computed = scopeFunctions.scopesFor(dm, state, mapId);
    return ToolContext.of(
        AccessToken.DEFAULT,
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow(whitelist.toArray(String[]::new))
            .sensitiveAllowed(true)
            .resourceScopes(gmAccessLimit == null ? computed : computed.narrowTo(gmAccessLimit))
            .build(),
        AgentIdentity.subagent(INSTANCE_ID_PREFIX + dm.id().value(), CommandMode.LIMITED, GOAL, 1));
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
    ToolContext caller = callerFor(dm, state, mapId);
    // ★ 参数装进上下文（工具从 context.arguments() 读）；其余分量逐字来自 callerFor。
    return execute(
        registry,
        toolName,
        new ToolContext(
            caller.caller(), caller.permissions(), caller.config(), args, caller.identity()));
  }
}
