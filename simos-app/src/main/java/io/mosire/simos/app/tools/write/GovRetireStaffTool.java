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
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.gov.retireStaff}（阶段 13A 人员流转，GOV/Army 计划 §2.6）：<b>GM 组合工具</b>—— 离编 + 按政策一次性支付退休待遇
 * + 把人员并入指定 hex 的既有社会批次 + 留行动记录，一批落一条 revision。
 *
 * <p>★★ <b>批顺序（固定，一条 revision）</b>：{@code unit.DismissStaff} →（待遇 &gt; 0）{@code
 * actor.AdjustAccounts}（国库银负增量）→（给了 reinsertQ/reinsertR）{@code social.SeedGroups}（并入该 hex <b>现有批次里
 * id 最小的一条</b>；<b>不新建批次</b>）→ {@code sd.PutInfo}（key={@value #INFO_KEY}）。
 *
 * <p>★★ <b>回写是具名近似</b>（类注见 {@link GovRetireStaffPlan}）：退休者并入当地既有批次，"这批人多了 count"， 身份/年龄不做精细分档；该 hex
 * 没有 {@code populations} 序列或没有任何批次时 <b>具名拒</b>（不静默丢人）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 资源声明：写 {@code unit}/{@code actor}/{@code social}/{@code sd} 四个命名空间 （GM 侧全部
 * unlimited）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / 单位不是 GOV / roster 不足 / 待遇溢出 / 国库银不足 /
 * reinsertQ/reinsertR 不成对 / 回写格无 populations 序列 / 回写格无批次 ⇒ {@link IllegalArgumentException} 折
 * {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code REJECTED} 带逐条真拒因； 提交冲突 ⇒ {@code CONFLICT} 带真实
 * head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovRetireStaffTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.retireStaff";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 离编 GOV canonical）。 */
  public static final String INFO_KEY = "retireStaff";

  /** 本工具只写 unit / actor / social / sd 四个命名空间（GM 侧全部 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest RETIRE_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #RETIRE_WRITE} 同源的逐命名空间粗断言。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public GovRetireStaffTool(CoreSimos core, QueryService query, String initiator, String mapId) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 退休离编 + 待遇支付 + 社会回写（组合工具，一批 = 一条 revision）："
        + "参数 {unitId(必填, 带 GovernmentFormation 的 GOV), role(必填 SCRIBE|YAMEN|POST), count(必填 ≥ 1), "
        + "reinsertQ?(回写格 q，与 reinsertR 成对), reinsertR?(回写格 r), reason(必填), preview?(缺省 true), "
        + "branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "待遇 = policy.retirementPerStaff × count（银）；待遇>0 时国库 = ActorRef(UNIT, unitId) @ 单位当刻有效位置，"
        + "可支配银不足 ⇒ 整条拒（带 requested/available/缺口）；待遇=0 ⇒ 批里无 actor 命令。"
        + "回写：向该 hex 现有批次里 id 最小的一条加 count（count=原count+count，其余字段原样，不新建批次）——"
        + "★ 这是具名近似（身份/年龄不细分档）；该 hex 必须有 populations 序列且已有批次，否则具名拒（不静默丢人）。"
        + "批：unit.DismissStaff → [actor.AdjustAccounts] → [social.SeedGroups] → sd.PutInfo(key="
        + INFO_KEY
        + ")。"
        + "返回 {preview, submitted, tick, unitId, role, count, staffBefore, staffAfter, retirementPerStaff, "
        + "payment, treasuryLocation, availableSilver, reinsert, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "离编主体：带 GovernmentFormation 的 GOV 单位 id"));
    props.put("role", ToolSupport.prop("string", "行政角色：SCRIBE（书吏）|YAMEN（衙门）|POST（驿传）"));
    props.put("count", ToolSupport.prop("integer", "离编人数（≥ 1；不得超过该角色现有在编）"));
    props.put("reinsertQ", ToolSupport.prop("integer", "社会回写格 q（可选；必须与 reinsertR 成对）"));
    props.put("reinsertR", ToolSupport.prop("integer", "社会回写格 r（可选；必须与 reinsertQ 成对）"));
    props.put("reason", ToolSupport.prop("string", "退休离编原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("unitId", "role", "count", "reason"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return RETIRE_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "退休离编 unitId="
            + args.get("unitId")
            + " role="
            + args.get("role")
            + " count="
            + args.get("count")
            + " reinsert=("
            + args.get("reinsertQ")
            + ","
            + args.get("reinsertR")
            + ")"
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String unitId = ToolSupport.requiredText(args, "unitId");
      String role = ToolSupport.requiredText(args, "role");
      long count = ToolSupport.requiredLong(args, "count");
      String reason = ToolSupport.requiredText(args, "reason");
      // ★ 回写格：两个坐标必须成对（成对校验在纯推导里，工具只做类型/缺省解析）。
      Optional<Long> reinsertQ = Optional.ofNullable(ToolSupport.optionalLong(args, "reinsertQ"));
      Optional<Long> reinsertR = Optional.ofNullable(ToolSupport.optionalLong(args, "reinsertR"));
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryService.QueryTarget.head(branch)
                  : QueryService.QueryTarget.at(branch, new RevisionId(expectedRevision)));
      GovRetireStaffPlan.Plan plan =
          GovRetireStaffPlan.plan(state, unitId, role, count, reinsertQ, reinsertR);
      if (preview) {
        return ToolSupport.ok(planView(plan, reason, true, false));
      }
      return apply(plan, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "退休离编失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  private ToolResult apply(
      GovRetireStaffPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch =
        buildBatch(batchId, plan, reason, branch, new RevisionId(expectedRevision));
    BatchResult result = core.submitBatch(batch);
    Map<String, Object> view = planView(plan, reason, false, true);
    if (result instanceof BatchResult.Committed committed) {
      view.put("submission", ToolSupport.committedView(committed.ref(), batchId, batchId));
      return ToolSupport.ok(view);
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolSupport.json(view));
    }
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<CommandOutcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      CommandResult outcome = outcomes.get(i).result();
      row.put(
          "reason",
          outcome instanceof CommandResult.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", rows);
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolSupport.json(view));
  }

  /** 组批：DismissStaff → [AdjustAccounts] → [SeedGroups] → PutInfo（固定顺序、按需缺席）。 */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      GovRetireStaffPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(4);
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovRetireStaffPlan.DISMISS_STAFF_TYPE,
            plan.dismissStaffPayloadJson()));
    if (plan.hasPayment()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              GovRetireStaffPlan.ADJUST_ACCOUNTS_TYPE,
              plan.adjustAccountsPayloadJson()));
    }
    if (plan.hasReinsert()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              GovRetireStaffPlan.SEED_GROUPS_TYPE,
              plan.seedGroupsPayloadJson()));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovRetireStaffPlan.PUT_INFO_TYPE,
            infoPayload(plan, reason)));
    return List.copyOf(batch);
  }

  private CommandEnvelope envelope(
      String batchId,
      BranchId branch,
      RevisionId expectedRevision,
      String type,
      String payloadJson) {
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        batchId,
        initiator,
        branch,
        expectedRevision,
        type,
        payloadJson);
  }

  /** {@code sd.PutInfo} 载荷：离编 GOV canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovRetireStaffPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.dismissal().unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.dismissal().tick());
    return ToolSupport.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> planView(
      GovRetireStaffPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    GovDismissPlan.Plan dismissal = plan.dismissal();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", dismissal.tick());
    view.put("unitId", dismissal.unitId());
    view.put("role", dismissal.role().name());
    view.put("count", dismissal.count());
    view.put("staffBefore", dismissal.staffBefore());
    view.put("staffAfter", dismissal.staffAfter());
    view.put("retirementPerStaff", dismissal.retirementPerStaff());
    view.put("payment", dismissal.payment());
    view.put(
        "treasuryLocation",
        dismissal.treasuryLocation().map(GovDismissPlan::treasuryView).orElse(null));
    view.put("availableSilver", dismissal.availableSilver());
    view.put("reinsert", plan.reinsertView());
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
