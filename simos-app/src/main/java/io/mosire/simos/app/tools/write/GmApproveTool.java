package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code simos.gm.approve}（P7b，2026-10-01 后端 + MCP 稳定化计划）：**GM-only MCP 裁决口**——外部 GM/MCP 对 {@code
 * sd.IssueDirective} / {@code sd.SubmitVerdict} 等敏感写阻塞等待的审批项作答。
 *
 * <p>★★ <b>与 HTTP 审批面的裁决语义同源</b>（{@code ApprovalHttpEndpoint.handleDecide}）：
 *
 * <ol>
 *   <li>先 {@link PendingApprovals#get(String)}；空 ⇒ {@code NOT_FOUND}（从未存在 / 已过期 / 已被编排器 fail-closed
 *       摘除，三者外部不可区分）；
 *   <li>解析 {@code decision}/{@code scope}（approve 缺省 {@code once}；deny 带 scope 是非法组合；未知词 ⇒ {@code
 *       BAD_REQUEST}），映射为 {@link ApprovalDecision}；
 *   <li>{@link PendingApprovals#decide(String, ApprovalDecision, String)} 返回 {@code false} ⇒ {@code
 *       CONFLICT} （已决议过；幂等保护，不覆盖首次决定）；
 *   <li>成功 ⇒ {@link ApprovalCoordinator#effectiveDecision(String, ApprovalDecision)} 算**实际生效**的决议
 *       （会话级收窄的唯一实现，本类不复制规则），回 {@code {id,requested,effective,decision,scope,by}}——{@code
 *       requested}/{@code effective} 是 {@link ApprovalDecision} 的 {@link Enum#name()}，{@code
 *       decision}/{@code scope} 是 HTTP 同款小写口径。
 * </ol>
 *
 * <p>★★ <b>不是世界写</b>：审批是控制面，不产生 {@code Command → ChangeSet → Revision}（本类不持有 {@code
 * CoreSimos}）。资源声明取 {@link ResourceManifest#NONE}（不读写任何世界命名空间），工具名也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。
 *
 * <p>★ <b>权限声明</b>：{@code sensitive=true, destructive=false}、身份级别 {@code DEFAULT}——与其余 GM 敏感写同制； GM
 * 面的 {@code ToolGate.Ask} 由 {@code GmAutoApproveGate} 无脑放行（若将来挂到别的审批链上，它仍是一条要人裁决的工具）。 GM 口的每次实际执行另有
 * {@code GmToolUsage} 留痕（工具名 + 结果码 + 时刻）。
 *
 * <p>★ <b>未接入审批面（{@link PendingApprovals} 或 {@link ApprovalCoordinator} 缺席）</b>⇒ 具名 {@code
 * UNAVAILABLE}，不回一个假成功、也不静默当"已裁决"。
 *
 * <p>★ <b>{@code by} 只是审计注记</b>：正文原样进登记表，不参与任何判定；缺省 {@value #DEFAULT_BY}（本通道名）。
 */
public final class GmApproveTool implements AgentTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.approve";

  /** {@code by} 缺省值 = 本通道名（审计注记，不参与判定）。 */
  public static final String DEFAULT_BY = "mcp-gm";

  private final PendingApprovals pending;
  private final ApprovalCoordinator coordinator;

  /**
   * {@code pending}/{@code coordinator} 允许为 null（未接入审批面的装配）⇒ 执行期回可读的 {@code UNAVAILABLE}，
   * 不静默当"没有待裁决项 / 已裁决"。
   */
  public GmApproveTool(PendingApprovals pending, ApprovalCoordinator coordinator) {
    this.pending = pending;
    this.coordinator = coordinator;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "裁决一条待审批项（GM 专用，控制面，不落世界 revision）："
        + "id(必填非空白) + decision(approve|deny，必填) + "
        + "scope?(仅 approve 允许 once|session，缺省 once；deny 带 scope 拒) + "
        + "by?(可选审计注记，缺省 "
        + DEFAULT_BY
        + "，不参与判定)。"
        + "回 {id,requested,effective,decision,scope,by}（scope 取实际生效值）；"
        + "未知/已过期 id ⇒ NOT_FOUND；重复决议 ⇒ CONFLICT；坏参数/非法组合 ⇒ BAD_REQUEST；"
        + "未接入审批面 ⇒ UNAVAILABLE。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("id", ToolSupport.prop("string", "审批 id（必填非空白）"));
    props.put("decision", ToolSupport.prop("string", "approve|deny（必填）"));
    props.put(
        "scope", ToolSupport.prop("string", "仅 approve 允许：once|session（缺省 once）；deny 带 scope 会被拒"));
    props.put("by", ToolSupport.prop("string", "可选审计注记（缺省 " + DEFAULT_BY + "，不参与判定）"));
    return ToolSupport.schema(props, List.of("id", "decision"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（走审批门链）、可外发（noExport=false）——GM 面无脑过；与其余 GM 敏感写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    // 审批是控制面：不读也不写任何世界命名空间（所以 NONE，而不是 map/unit/social/sd 的任一面）。
    return ResourceManifest.NONE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    // ★ 摘要只做字段回显、不解析参数：gate() 在 execute() 的校验之前调用，解析失败不能让审批链先炸。
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        NAME,
        "裁决审批 id="
            + args.get("id")
            + " decision="
            + args.get("decision")
            + " scope="
            + args.get("scope")
            + " by="
            + args.get("by"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    if (pending == null || coordinator == null) {
      return ToolResult.error("UNAVAILABLE", "审批面未接入（PendingApprovals / ApprovalCoordinator 缺席）");
    }
    try {
      Map<String, Object> args = context.arguments();
      // ★ 判定顺序照 HTTP：id 先落到 get(id)；空 ⇒ 404，再解析 body。id 本身缺失/空白是坏参数 ⇒ 400。
      String id = ToolSupport.requiredText(args, "id");
      Optional<ApprovalRequest> registered = pending.get(id);
      if (registered.isEmpty()) {
        // 从未存在 / 已过期 / 已被编排器摘除——三者外部不可区分，一律 404
        return ToolResult.error("NOT_FOUND", "未知或已失效的审批 id: " + id);
      }
      ApprovalDecision requested = requestedDecision(args);
      String by = auditNote(args);
      if (!pending.decide(id, requested, by)) {
        // 有值却决不动 = 已经被人/别处决议过（幂等保护：不覆盖首次决定）
        return ToolResult.error("CONFLICT", "该审批已决议（幂等保护：重复决议不覆盖首次决定）: " + id);
      }
      // ★ 实际生效的 scope：收窄只在编排器里实现一处，本层只调它（不得复制 "SYSTEM" 字面量或降级规则）
      ApprovalDecision effective =
          coordinator.effectiveDecision(registered.get().callerKey(), requested);
      Map<String, Object> receipt = new LinkedHashMap<>();
      receipt.put("id", id);
      receipt.put("requested", requested.name());
      receipt.put("effective", effective.name());
      receipt.put("decision", effective == ApprovalDecision.DENY ? "deny" : "approve");
      receipt.put("scope", scopeOf(effective));
      receipt.put("by", by);
      return ToolSupport.ok(receipt);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /**
   * 解析并校验 {@code decision}/{@code scope}；非法时抛 {@link IllegalArgumentException}（由 {@link #execute}
   * 折成 {@code BAD_REQUEST}）。口径逐条照 HTTP 的 {@code readBody}。
   */
  private static ApprovalDecision requestedDecision(Map<String, Object> args) {
    String decision = ToolSupport.requiredText(args, "decision").strip();
    String scope = optionalScope(args);
    switch (decision) {
      case "approve" -> {
        if (scope == null || scope.equals("once")) {
          return ApprovalDecision.APPROVE_ONCE;
        }
        if (scope.equals("session")) {
          return ApprovalDecision.APPROVE_SESSION;
        }
        throw new IllegalArgumentException("scope 只接受 once|session");
      }
      case "deny" -> {
        if (scope != null) {
          // deny 没有作用域可给：带 scope 的 deny 是非法组合（不猜人的意思）
          throw new IllegalArgumentException("deny 不接受 scope（拒绝没有作用域）");
        }
        return ApprovalDecision.DENY;
      }
      default -> throw new IllegalArgumentException("decision 只接受 approve|deny");
    }
  }

  /** {@code scope} 缺省/JSON null ⇒ null；非文本 ⇒ 400；文本保留去空白后的值（空串仍算"给了 scope"，口径同 HTTP）。 */
  private static String optionalScope(Map<String, Object> args) {
    Object value = args.get("scope");
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("scope 必须是文本（once|session）");
    }
    return text.strip();
  }

  /** {@code by} 缺省/空白 ⇒ {@value #DEFAULT_BY}；非文本 ⇒ 400（它只是审计注记，不参与判定）。 */
  private static String auditNote(Map<String, Object> args) {
    Object value = args.get("by");
    if (value == null) {
      return DEFAULT_BY;
    }
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("by 必须是文本（它只是审计注记，不参与判定）");
    }
    String stripped = text.strip();
    return stripped.isEmpty() ? DEFAULT_BY : stripped;
  }

  /** 决议作用域口径（与 HTTP/编排器事件面同口径：拒没有作用域，记 {@code none}）。 */
  private static String scopeOf(ApprovalDecision decision) {
    return switch (decision) {
      case APPROVE_ONCE -> "once";
      case APPROVE_SESSION -> "session";
      case DENY -> "none";
    };
  }
}
