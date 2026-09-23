package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.spi.SetDirectiveStatusHandler;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code sd.RejectDirective} 窄工具：**GM 把一个决策人的令打回**（用户 2026-09-23 裁定的"决策人一般流程"第 2 件）。
 *
 * <pre>{@code
 * {"branch":"main","expectedRevision":7,"directiveId":"d-shanghai-0002",
 *  "reason":"【打回】目标与当前补给线不匹配，重写"}
 * }</pre>
 *
 * <p>★★ **它只做两件事**（逐条对应用户的裁定）：
 *
 * <ol>
 *   <li><b>把 {@code reason} 作为一条 user 消息投进该决策人的会话</b> —— 复用 {@code say} 通道的**同一段实现**（{@link
 *       DecisionAgentService#appendUserMessage}，即 {@code POST /api/sd/decision-makers/{id}/say}
 *       背后那一段）；
 *   <li><b>该令标 {@code CANCELLED}</b>（不执行、不落世界观变更）—— 复用既有的 {@code sd.SetDirectiveStatus} 命令，
 *       **不另写一条状态转移**（它的 {@code ISSUED → CANCELLED} 守卫就是唯一那份判据）。
 * </ol>
 *
 * <p>★★ **界线由 GM 定，代码不替它判**（用户原话：「机器可判做几个简单文本就行……界限最终也由 GM 定」）：本工具 **不做**拒因分类码、**不做**"自动打回重试 N
 * 轮"、**不做**"效果不符预期自动打回"——{@code reason} 文本**原样透传**进会话， "什么叫打回"的判定留给 GM 与决策人之间的**约定**（例如 {@code
 * reason} 以 {@code 【打回】} 开头）。
 *
 * <p>★★ **一条 revision 同时留痕三件事**（铁律 2 的"打回也是世界事实"）：状态翻转（{@code sd.SetDirectiveStatus}）+ 审计条目（ {@code
 * sd.PutInfo}，地址 {@code sd:rejection.<directiveId>}：谁的令 / 第几版 / 理由文本 / 落在哪条 revision）**同批**落一条
 * revision（{@link CoreSimos#submitBatch}，原子）。
 *
 * <p>★ **审计条目**有意**不带 tags**（无主）：它是治理/审计记录，不是"裁决结果"（{@code sd:adjudication.<tick>} 那一族）—— 挂上决策人的
 * tag 会让它混进决策结果的读口（{@code sd.DecisionResults} 的判据是"tags 含自己"），而两者 {@code value} 的形状不同。
 * 决策人知道"被打回"这件事 **走会话**（第 1 件事），不走结果列表。
 *
 * <p>★ **只在 GM 桶**（{@code Role.GM} = 运行时 MCP 口）：打回是 GM 的动作，决策人自己不打回自己的令。
 *
 * <p>★ 拒绝：{@code branch} 不存在；{@code expectedRevision} 过期（{@code CONFLICT}，原样上报真实 head）；
 * 令不存在；令**当前不是生效态** （已 {@code EXECUTED}/{@code CANCELLED}/{@code SUPERSEDED} ⇒ 走 {@code
 * sd.SetDirectiveStatus} 的转移守卫原样拒， **不静默成功**）；{@code reason} 空白（打回必须有话可说，否则会话里就落一条空消息）。
 */
public final class RejectDirectiveTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条已注册的命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "sd.RejectDirective";

  /** 状态翻转用的命令类型（取自唯一的常量来源，不另写一个字面量）。 */
  private static final String STATUS_COMMAND_TYPE = SetDirectiveStatusHandler.TYPE;

  /** 审计 INFO 的地址前缀（{@code sd:rejection.<directiveId>}——**一条令一个地址**）。 */
  public static final String REJECTION_ADDRESS_PREFIX = "sd:rejection.";

  /** 审计 INFO 的 key（同一条令只会被打回一次，故一个地址下恰好一条）。 */
  public static final String REJECTION_KEY = "rejection";

  /** 本工具的名字在 sd 域里只声明 sd 只读策略面（fail-closed：未表态 sd 的调用者在**写**上被拒）。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final CoreSimos core;
  private final String initiator;
  private final DecisionAgentService decisionAgent;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}：状态翻转 + 审计条目**同批**落一条 revision）
   * @param initiator 本工具落盘时的发起者（GM 的裁决/打回动作）
   * @param decisionAgent 决策人运行流（打回理由要落进该决策人的**会话**——复用 {@code say} 的同一段实现）
   */
  public RejectDirectiveTool(CoreSimos core, String initiator, DecisionAgentService decisionAgent) {
    this.core = Objects.requireNonNull(core, "core");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.decisionAgent = Objects.requireNonNull(decisionAgent, "decisionAgent");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 打回一条令：载荷 {branch, expectedRevision, directiveId, reason}。"
        + "只做两件事：① 把 reason **原样**作为一条 user 消息投进该决策人的会话（复用 say 通道）；"
        + "② 该令标 CANCELLED（不执行、不改世界观）。同批落一条 revision 并留审计条目 "
        + "{directiveId, decisionMakerId, tick, version, status, reason, resultRevision}。"
        + "★ 不做拒因分类码、不做自动重试——'什么叫打回'由 GM 的文本约定决定（系统只透传）。"
        + "返回 {result, ref, directiveId, decisionMakerId, tick, version, status, reason, info{address,id,key}, "
        + "conversation{decisionMakerId, conversationId}}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSupport.prop("string", "分支名"));
    props.put("expectedRevision", ToolSupport.prop("integer", "期望的 base revision（乐观并发）"));
    props.put("directiveId", ToolSupport.prop("string", "要打回的令 id"));
    props.put("reason", ToolSupport.prop("string", "打回理由（**原样**投进该决策人的会话；不得为空白）"));
    return ToolSupport.schema(
        props, List.of("branch", "expectedRevision", "directiveId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "打回令 directiveId="
            + args.get("directiveId")
            + " branch="
            + args.get("branch")
            + " expected="
            + args.get("expectedRevision"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String rejectionId = UUID.randomUUID().toString();
    try {
      ToolSupport.requireAll(context, Operation.WRITE, writeResources());
      Map<String, Object> args = context.arguments();
      BranchId branch = new BranchId(ToolSupport.requiredText(args, "branch"));
      RevisionId expected = new RevisionId(ToolSupport.requiredLong(args, "expectedRevision"));
      DirectiveId directiveId = DirectiveId.parse(ToolSupport.requiredText(args, "directiveId"));
      String reason = ToolSupport.requiredText(args, "reason");
      if (reason.isBlank()) {
        return ToolResult.error("BAD_REQUEST", "reason 不得为空白（打回必须有话可说——它要落进该决策人的会话）");
      }
      Optional<RevisionId> head = core.head(branch);
      if (head.isEmpty()) {
        return ToolResult.error("REJECTED", "分支不存在: " + branch.value());
      }
      if (head.get().value() != expected.value()) {
        // ★ 与 submitBatch 的冲突口径一致（零 revision、报真实 head）：不去打回一个"过期世界"上的令。
        return conflict(new StateRef(branch, head.get()), rejectionId);
      }
      StateRef base = new StateRef(branch, head.get());
      SdState sd = ToolSupport.sdState(core.replay(base));
      Directive directive = sd.directives().get(directiveId);
      if (directive == null) {
        return ToolResult.error("REJECTED", "决策不存在: " + directiveId.value());
      }
      return reject(base, rejectionId, sd, directive, reason);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与两条决策窄写同一条：资源拒因必须原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会丢掉"换个资源就行"这条结论）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "打回失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 本工具要写的资源：{@code sd:decision-maker}（打回是 GM 对一个决策人的令的动作）。 */
  private List<ResourceId> writeResources() {
    return List.of(
        ResourceId.of(ToolSupport.SD_NAMESPACE, DecisionCallerFactory.DECISION_MAKER_KIND));
  }

  // ── 主流程 ────────────────────────────────────────────────────────────────────────────

  /**
   * 打回本身：**一批两条命令**（状态翻转 + 审计条目）落**一条** revision，**成功之后**才把理由投进会话。
   *
   * <p>★ **顺序是有意的**（会话写入在提交成功之后）：打回没落盘就**不该**对决策人说"你被打回了"——那是把没发生的事说成发生了。 会话是 append-only 的旁路存储，无法与
   * revision 同事务，故只能"先落世界事实、再落话"；后者失败会**如实**报在 {@code conversation.error} 里（不静默吞）。
   */
  private ToolResult reject(
      StateRef base, String rejectionId, SdState sd, Directive directive, String reason) {
    int version = versionOf(sd, directive);
    long resultRevision = base.revision().value() + 1;
    List<CommandEnvelope> batch =
        List.of(
            flipEnvelope(rejectionId, base, directive.id()),
            auditEnvelope(rejectionId, base, directive, version, reason, resultRevision));
    BatchResult result = core.submitBatch(batch);
    if (result instanceof BatchResult.Conflict conflict) {
      return conflict(conflict.current(), rejectionId);
    }
    if (result instanceof BatchResult.Rejected rejected) {
      // ★ 不静默成功：令不存在 / 已终态（打回两次、打回一条已执行的令）的**真拒因**原样带出去。
      return ToolResult.error("REJECTED", firstRejectionReason(rejected));
    }
    StateRef ref = ((BatchResult.Committed) result).ref();
    String conversationId = null;
    String conversationError = null;
    try {
      // ★ 复用 say 通道的同一段实现（POST /api/sd/decision-makers/{id}/say 背后那一段）。
      conversationId = decisionAgent.appendUserMessage(ref, directive.decisionMakerId(), reason);
    } catch (RuntimeException e) {
      conversationError = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    return ToolResult.ok(
        ToolSupport.json(
            view(ref, rejectionId, directive, version, reason, conversationId, conversationError)));
  }

  /** 同一 (决策人, tick) 下的**第几条令**（1-based）——"第几版"的判据（每次重写新添一条，故条数即版本号）。 */
  private static int versionOf(SdState sd, Directive directive) {
    int count = 0;
    for (Directive candidate : sd.directives().values()) {
      if (candidate.decisionMakerId().equals(directive.decisionMakerId())
          && candidate.tick() == directive.tick()) {
        count++;
      }
    }
    return count;
  }

  /** 状态翻转命令：{@code sd.SetDirectiveStatus(directiveId, CANCELLED)}——目标态由**既有守卫**裁定合法性。 */
  private CommandEnvelope flipEnvelope(String rejectionId, StateRef base, DirectiveId directiveId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("directiveId", directiveId.value());
    payload.put("status", "CANCELLED");
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        rejectionId,
        initiator,
        base.branch(),
        base.revision(),
        STATUS_COMMAND_TYPE,
        ToolSupport.json(payload));
  }

  /**
   * 审计条目（{@code sd.PutInfo}，地址 {@code sd:rejection.<directiveId>}）：**谁的令 / 第几版 / 理由文本 / 落在哪条
   * revision**。
   *
   * <p>★ {@code value} 是 **JSON 字符串**（{@code SdInfoEntry.value} 是裸 {@code Object}，只有标量往返有保证）；{@code
   * tick} 取**令自带的 tick**（归属正确，与 {@code AdjudicateTickTool} 的决策结果同口径）。 ★ **tags
   * 故意留空**（无主）——见类注：它不是决策结果。
   */
  private CommandEnvelope auditEnvelope(
      String rejectionId,
      StateRef base,
      Directive directive,
      int version,
      String reason,
      long resultRevision) {
    Address address = Address.parse(REJECTION_ADDRESS_PREFIX + directive.id().value());
    String canonical = address.canonical();
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("directiveId", directive.id().value());
    value.put("decisionMakerId", directive.decisionMakerId().value());
    value.put("tick", directive.tick());
    value.put("version", version);
    value.put("status", "CANCELLED");
    value.put("reason", reason);
    value.put("resultRevision", resultRevision);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", canonical);
    payload.put("key", REJECTION_KEY);
    payload.put("value", ToolSupport.json(value));
    payload.put("id", SdInfoIds.synthesize(canonical, 0).value());
    payload.put("tick", directive.tick());
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        rejectionId,
        initiator,
        base.branch(),
        base.revision(),
        "sd.PutInfo",
        ToolSupport.json(payload));
  }

  /** 整批被拒时取**第一条真拒因**（批内顺序 = 状态翻转在前 ⇒ 打回不合法时取到的就是转移守卫/命令期的原文）。 */
  private static String firstRejectionReason(BatchResult.Rejected rejected) {
    List<String> reasons = new ArrayList<>();
    for (CommandOutcome outcome : rejected.outcomes()) {
      if (outcome.result() instanceof CommandResult.Rejected rejection) {
        reasons.add(rejection.reason());
      }
    }
    return reasons.isEmpty() ? "整批被拒（无逐条拒因）" : String.join("；", reasons);
  }

  private static ToolResult conflict(StateRef current, String rejectionId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "conflict");
    view.put("current", ToolSupport.stateRef(current));
    view.put("commandId", rejectionId);
    view.put("correlationId", rejectionId);
    return ToolResult.error("CONFLICT", ToolSupport.json(view));
  }

  private static Map<String, Object> view(
      StateRef ref,
      String rejectionId,
      Directive directive,
      int version,
      String reason,
      String conversationId,
      String conversationError) {
    Map<String, Object> view =
        new LinkedHashMap<>(ToolSupport.committedView(ref, rejectionId, rejectionId));
    view.put("directiveId", directive.id().value());
    view.put("decisionMakerId", directive.decisionMakerId().value());
    view.put("tick", directive.tick());
    view.put("version", version);
    view.put("status", "CANCELLED");
    view.put("reason", reason);
    String canonical = Address.parse(REJECTION_ADDRESS_PREFIX + directive.id().value()).canonical();
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("address", canonical);
    info.put("id", SdInfoIds.synthesize(canonical, 0).value());
    info.put("key", REJECTION_KEY);
    view.put("info", info);
    Map<String, Object> conversation = new LinkedHashMap<>();
    conversation.put("decisionMakerId", directive.decisionMakerId().value());
    if (conversationId != null) {
      conversation.put("conversationId", conversationId);
    }
    if (conversationError != null) {
      // ★ 世界事实已落、话没落到会话里 ⇒ **如实报**（不静默、也不假装整件事失败了）。
      conversation.put("error", conversationError);
    }
    view.put("conversation", conversation);
    return view;
  }
}
