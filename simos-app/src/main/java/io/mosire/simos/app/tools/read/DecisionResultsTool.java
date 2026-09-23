package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.DecisionResults}（第 3 波第 3 步）：**决策人查看自己的决策结果**——按 tick 看不同裁决的逐条结局。
 *
 * <p>★★ **用户的原始诉求**：「允许决策人查看不同 tick 的不同决策结果是必要的」；「把能查看的决策结果列表以及相关查看组件做成 tool，Agent 决策人想看直接看」。本类就是那条
 * tool。
 *
 * <p>★★ **可见性 = 喂给 {@link RedactingQueryService#decisionResults}**（不是本类自己筛）：判据是「条目 {@code tags}
 * 含调用者自己的 {@link DecisionMakerId}」，**无主（tags 空）对决策人不可见**。规则**只此一处**——前端决策结果子页将来复用同一个方法。
 * 理由（为什么这条判据不能用资源谓词表达）写在那个方法的 javadoc 里。
 *
 * <p>★ **调用者身份取自宿主已知的东西**（{@code context.identity()}，见 {@link
 * DecisionCallerFactory#decisionMakerIdOf}）——**不由载荷自报**（载荷是模型写的）。解不出决策人 ⇒ 明确失败，不给数据。
 *
 * <p>★ **资源声明 = sd 只读**：本工具读 sd 的 INFO 覆盖层。取 {@link ResourcePolicy#READ_ONLY} 而非 {@code
 * UNRESTRICTED}——后者的失效方向是"调用者没表态 sd 时反而放行"（台账记过 {@code sd.IssueDirective} 的 fail-open 教训）；{@code
 * READ_ONLY} 下"未表态"在写操作上被拒，失效方向是 fail-closed。★ 本工具**不**对具体 INFO 条目拉资源断言：条目的地址（{@code
 * sd:adjudication.<tick>}）不在任何决策人的可达面（其 sd 域只是 {@code decision-maker/<自己>}）⇒ 拉断言会把全部结果判成不可见； 归属由上面的
 * tags 判据承担。
 *
 * <p>★ **载荷有界**：{@code limit} 恒有界（缺省 {@value #DEFAULT_LIMIT}，上限 {@value
 * #MAX_LIMIT}，超限**明确拒**而非静默截断）； {@code tick} 或 {@code [fromTick, toTick]}
 * 二选一。**不引入"选世界"这类额外层级**（只有既有的 {@code branch}/{@code revision}）。
 *
 * <p>★ **只在决策人桶**（{@code Role.DECISION_AGENT}）：GM 侧另有 GUI 面；决策人**不走 MCP**，权限由 {@code
 * DecisionCallerFactory} 现算的权限组承担，本工具与 {@link DecisionCallerFactory#WHITELIST} 两处同步（否则 {@code
 * DecisionToolDefs.requireAll} 当场炸）。
 */
public final class DecisionResultsTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "sd.DecisionResults";

  /**
   * {@code limit} 的缺省：最近 20 条（一次对话要看的那一档；也保证缺省查询有界）。
   *
   * <p>★ **公开是给 GUI 的决策结果子页复用**（{@code GET /api/sd/decision-results}）：那个端点与本工具是**同一份数据、两个入口**，
   * 边界必须是同一份。GUI 另写一组 20/200 就是"两份都对、只会漂移"的老形态。
   */
  public static final int DEFAULT_LIMIT = 20;

  /**
   * {@code limit} 的硬上限：无界地一次吐出全部历史是本工具明确要避免的形态。
   *
   * <p>★ 同 {@link #DEFAULT_LIMIT}：GUI 决策结果子页引用本常量，不另写一份。
   */
  public static final int MAX_LIMIT = 200;

  /** 本工具的资源声明：sd 只读（见类注的 fail-closed 理由）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final RedactingQueryService redacting;

  /**
   * @param query 只读查询门面（与既有读工具同一个）
   * @param mapId 地图称谓（与 GUI 的 {@code as=} 路径**同一段装配**：{@link DecisionScopeFunctions#defaults()}
   *     是无状态注册表，故各自 new 一份与共用一份等价）
   */
  public DecisionResultsTool(QueryService query, String mapId) {
    this.redacting = new RedactingQueryService(query, DecisionScopeFunctions.defaults(), mapId);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查看**本人的**决策结果（决策人只读）：返回 sd INFO 里 tags 含调用者自己 id 的条目。"
        + "载荷 {branch?, revision?, tick?, fromTick?, toTick?, limit?}——tick 只看该 tick，"
        + "fromTick/toTick 给闭区间（与 tick 互斥），limit 截断（缺省 "
        + DEFAULT_LIMIT
        + "、上限 "
        + MAX_LIMIT
        + "）。返回 {results:[{tick, id, tags, value, at{branch, revision}}], count, note?}，"
        + "按 tick 降序（同 tick 按 id）。无主（tags 为空）的结果对决策人不可见";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("tick", ToolSupport.prop("integer", "只看该 tick（与 fromTick/toTick 互斥）"));
    props.put("fromTick", ToolSupport.prop("integer", "起始 tick（闭区间下界）"));
    props.put("toTick", ToolSupport.prop("integer", "结束 tick（闭区间上界）"));
    props.put(
        "limit",
        ToolSupport.prop("integer", "最多返回多少条（缺省 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      QueryTarget target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      // ★ 身份取自宿主已知的 AgentIdentity（不由载荷自报）；解不出决策人 ⇒ 明确失败，不给任何数据。
      DecisionMakerId actor =
          DecisionCallerFactory.decisionMakerIdOf(context.identity())
              .map(DecisionMakerId::new)
              .orElse(null);
      if (actor == null) {
        return ToolResult.error("BAD_REQUEST", "本工具只对决策人可用（调用者身份不是决策人）");
      }
      RedactingQueryService.DecisionResultWindow window = windowOf(args);
      List<Map<String, Object>> results = redacting.decisionResults(actor, target, window);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("results", results);
      view.put("count", results.size());
      if (results.isEmpty()) {
        // ★ **明确可读的"无"**（不是静默成功）：调用方/模型据此知道"确实没有可看的"，而不是"调用坏了"。
        view.put("note", "没有可查看的决策结果（该决策人在这次查询范围里没有命中任何决策结果）");
      }
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** 载荷 → 可见窗口；所有边界在此校成 {@code BAD_REQUEST}（不把含糊输入交给下层猜）。 */
  private static RedactingQueryService.DecisionResultWindow windowOf(Map<String, Object> args) {
    Long tick = ToolSupport.optionalLong(args, "tick");
    Long fromTick = ToolSupport.optionalLong(args, "fromTick");
    Long toTick = ToolSupport.optionalLong(args, "toTick");
    Long limitArg = ToolSupport.optionalLong(args, "limit");
    requireNonNegative("tick", tick);
    requireNonNegative("fromTick", fromTick);
    requireNonNegative("toTick", toTick);
    int limit;
    if (limitArg == null) {
      limit = DEFAULT_LIMIT;
    } else if (limitArg > MAX_LIMIT) {
      // ★ 超限**明确拒**（不是静默截断——静默会让调用方以为拿到了全部）。
      throw new IllegalArgumentException("limit 上限为 " + MAX_LIMIT + ": " + limitArg);
    } else {
      limit = limitArg.intValue();
    }
    // 互斥 / 方向 / limit 为正 的判定住在 DecisionResultWindow 的构造期（同一处口径）。
    return new RedactingQueryService.DecisionResultWindow(tick, fromTick, toTick, limit);
  }

  /** 给了就必须 ≥ 0（{@code tick} 的既有口径；缺省不算违规）。 */
  private static void requireNonNegative(String name, Long value) {
    if (value != null && value < 0) {
      throw new IllegalArgumentException("参数 " + name + " 必须 ≥ 0: " + value);
    }
  }
}
