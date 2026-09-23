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
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.DecisionResultsTool;
import io.mosire.simos.app.tools.read.MapHexTool;
import io.mosire.simos.app.tools.read.MapOverviewTool;
import io.mosire.simos.app.tools.read.PopulationTool;
import io.mosire.simos.app.tools.read.StateFacetsTool;
import io.mosire.simos.app.tools.read.StateResolveTool;
import io.mosire.simos.app.tools.read.UnitGetTool;
import io.mosire.simos.app.tools.read.UnitListTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
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
   * 决策人的工具白名单（spec §2.2）：**10 条读工具 + 两条决策行为**。
   *
   * <p>★ 第 10 条读工具是第 3 波第 3 步的 {@link DecisionResultsTool}（决策人查看**自己**的决策结果，用户原话「允许决策人查看不同 tick
   * 的不同决策结果」）——它只挂决策人桶，读口经 {@code RedactingQueryService#decisionResults}（可见性 = 条目 tags 含自己）。
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
          DecisionResultsTool.NAME,
          IssueDirectiveTool.NAME,
          SubmitVerdictTool.NAME);

  /** 决策人身份的实例 id 前缀（与将来的会话 id 同源：按决策人派生，不隐式取全局状态）。 */
  public static final String INSTANCE_ID_PREFIX = "decision-maker:";

  /** {@code sd} 域里"决策人"这一类资源（spec §3.3：{@code sd: decision-maker/<id> · nation/<id> · …}）。 */
  public static final String DECISION_MAKER_KIND = "decision-maker";

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

  /**
   * 本工厂给决策人的工具白名单（T11B：运行流**必须**用它当"模型能看到的工具面"）。
   *
   * <p>★ **为什么要有这个读口**：spec §2.3 要点 1 要求"工具面 = 决策人权限组下的工具"⇒ 给模型看的 {@code ToolDef} 列表与
   * 权限组白名单**必须是同一份数据**。分成两份（一处写死工具名、一处另有一张表）就会出现"模型看得见、调了却被拒"或反过来"能调但没露出来"
   * 的错位，而两者都不会报错。这里把它交出去，让运行流只有这一个来源。
   */
  public Set<String> whitelist() {
    return whitelist;
  }

  /**
   * 建这次调用的上下文：白名单（{@link #WHITELIST}）+ **现算**的资源范围 + 子 agent 身份。
   *
   * <p>★ **GM 的额外限制来自决策人自己**（{@code dm.accessLimit()}，T9）：它随 revision 落盘 ⇒ 回放/分叉后
   * 逐字复原，不需要调用方另外传一份（"两份真相"是漏配的来源）。
   */
  public ToolContext callerFor(DecisionMaker dm, SimulationState state, String mapId) {
    return ToolContext.of(
        AccessToken.DEFAULT,
        permissionsFor(dm, state, mapId),
        AgentIdentity.subagent(INSTANCE_ID_PREFIX + dm.id().value(), CommandMode.LIMITED, GOAL, 1));
  }

  /** 决策人的权限组（spec §2.2）：白名单 + **现算范围 ∩ GM 额外限制**。 */
  public AgentPermissionSet permissionsFor(DecisionMaker dm, SimulationState state, String mapId) {
    return permissionSetOf(scopeFunctions, whitelist, dm, state, mapId);
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
    return ResourceScope.of(decisionDomainOf(dm.id().value()));
  }

  /** 决策人自己的决策域路径（{@code decision-maker/<id>}）——**唯一拼写点**（工具侧也从这里取）。 */
  public static String decisionDomainOf(String decisionMakerId) {
    return ToolSupport.resourceSd(DECISION_MAKER_KIND, decisionMakerId).path();
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
    ToolContext caller = callerFor(dm, state, mapId);
    // ★ 参数装进上下文（工具从 context.arguments() 读）；其余分量逐字来自 callerFor。
    return execute(
        registry,
        toolName,
        new ToolContext(
            caller.caller(), caller.permissions(), caller.config(), args, caller.identity()));
  }
}
