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
 * ★★ {@code simos.gov.retireStaff}（阶段 13A 人员流转；P1.2 改走 Social 家户工单）：<b>GM 组合工具</b>—— 离编 + 按政策一次性
 * 支付退休待遇（支付口径未改，仍复用 {@link GovDismissPlan}）+ 从政府家户转出真实成员到明确目标家户 + 留行动记录，一批落一条
 * revision。
 *
 * <p>★★ <b>批顺序（固定，一条 revision）</b>：{@code social.SubmitHouseholdWorkOrder}（
 * {@code orderId=gov-retire:<unitId>:<role>:<tick>:<count>:<目标家户>} 幂等键；逐 lot {@code
 * TRANSFER_MEMBERS(from=hh-gov-<unitId>, to=目标家户, lotId, count=taken)}）→ {@code unit.DismissStaff}（形状不变）
 * →（待遇 &gt; 0）{@code actor.AdjustAccounts} → {@code sd.PutInfo}（key={@value #INFO_KEY}）。
 *
 * <p>★★ <b>目标家户必须明确</b>：{@code toHouseholdId} 精确指定（必须存在于 Social、≠ 政府家户）；或给
 * {@code reinsertQ}/{@code reinsertR} 成对坐标，在该 hex 按 household id 升序取第一个有人口的家户；两者都没给 ⇒
 * plan 级具名拒（人不能凭空消失，也不再合并进最小批次）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字不是命令类型 ⇒ 不进 catalog /
 * {@code PAYLOAD_HINTS}。★ 资源声明：写 {@code unit}/{@code actor}/{@code social}/{@code sd} 四个命名空间 （GM 侧全部
 * unlimited）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / 单位不是 GOV / roster 不足 / 待遇溢出 / 国库银不足 /
 * 目标家户缺失或与源相同 / 政府家户不在 Unit.households 或 Social / 政府家户人口不足 / reinsert 坐标不成对 /
 * 回写格无有人口家户 ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code
 * REJECTED} 带逐条真拒因； 提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}。
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
    return "GM 退休离编 + 待遇校验/支付 + 从政府家户转出真实成员到目标家户（组合工具，一批 = 一条 revision）："
        + "参数 {unitId(必填, 带 GovernmentFormation 的 GOV), role(必填 SCRIBE|YAMEN|POST), count(必填 ≥ 1), "
        + "toHouseholdId?(精确目标家户，必须存在于 Social 且 ≠ 政府家户；与 reinsertQ/reinsertR 二选一), "
        + "reinsertQ?(回退目标格 q，必须与 reinsertR 成对；在该 hex 按 household id 升序取第一个有人口的家户), "
        + "reinsertR?(回退目标格 r), reason(必填), preview?(缺省 true), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "目标家户必须明确：toHouseholdId 与 reinsertQ/reinsertR 二选一；都没给 ⇒ 具名拒（人不能凭空消失，"
        + "也不会留在政府编制家户）。退休源 = hh-gov-<unitId>，必须同时存在于 Unit.households 与 Social，"
        + "且政府家户人口 ≥ count，否则具名拒。"
        + "待遇 = policy.retirementPerStaff × count（银）；待遇>0 时国库 = ActorRef(UNIT, unitId) @ 单位当刻有效位置，"
        + "可支配银不足 ⇒ 整条拒（带 requested/available/缺口）；待遇=0 ⇒ 批里无 actor 命令（本批支付口径未改）。"
        + "批：social.SubmitHouseholdWorkOrder（orderId=gov-retire:<unitId>:<role>:<tick>:<count>:<目标家户> 幂等键，"
        + "target=目标家户，逐 lot TRANSFER_MEMBERS(from=hh-gov-<unitId>,to=目标家户,lotId,count=taken)）→ "
        + "unit.DismissStaff → [actor.AdjustAccounts] → sd.PutInfo(key="
        + INFO_KEY
        + ")。"
        + "返回 {preview, submitted, tick, unitId, role, count, staffBefore, staffAfter, retirementPerStaff, "
        + "payment, treasuryLocation, availableSilver, governmentHouseholdId, governmentPopulationBefore/After, "
        + "targetHouseholdId, targetHex, targetPopulationBefore/After, available, "
        + "sources[{householdId,lotId,taken,hex}], workOrder, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "离编主体：带 GovernmentFormation 的 GOV 单位 id"));
    props.put("role", ToolSupport.prop("string", "行政角色：SCRIBE（书吏）|YAMEN（衙门）|POST（驿传）"));
    props.put("count", ToolSupport.prop("integer", "离编人数（≥ 1；不得超过该角色现有在编）"));
    props.put(
        "toHouseholdId",
        ToolSupport.prop(
            "string",
            "精确目标家户 id（可选；必须存在于 Social 且 ≠ 政府家户；与 reinsertQ/reinsertR 二选一）"));
    props.put(
        "reinsertQ",
        ToolSupport.prop(
            "integer",
            "回退目标格 q（可选；与 reinsertR 成对且不与 toHouseholdId 同给；在该 hex 按 household id 升序取第一个有人口的家户）"));
    props.put("reinsertR", ToolSupport.prop("integer", "回退目标格 r（可选；必须与 reinsertQ 成对）"));
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
            + " toHouseholdId="
            + args.get("toHouseholdId")
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
      // ★ 目标家户：toHouseholdId 精确指定；未给时用 reinsertQ/reinsertR（成对校验在纯推导里，工具只做类型/缺省解析）。
      Optional<String> toHouseholdId =
          Optional.ofNullable(ToolSupport.optionalText(args, "toHouseholdId", null));
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
          GovRetireStaffPlan.plan(state, unitId, role, count, toHouseholdId, reinsertQ, reinsertR);
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

  /**
   * 组批（固定顺序、按需缺席）：{@code social.SubmitHouseholdWorkOrder} → {@code unit.DismissStaff} →
   * （待遇 &gt; 0）{@code actor.AdjustAccounts} → {@code sd.PutInfo}。
   */
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
            GovRetireStaffPlan.SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
            plan.submitHouseholdWorkOrderPayloadJson(reason)));
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
    view.put("governmentHouseholdId", plan.governmentHouseholdId().value());
    view.put("governmentPopulationBefore", plan.governmentPopulationBefore());
    view.put("governmentPopulationAfter", plan.governmentPopulationAfter());
    view.put("targetHouseholdId", plan.targetHouseholdId().value());
    view.put("targetHex", plan.targetHex().map(GovRetireStaffPlan::hexView).orElse(null));
    view.put("targetPopulationBefore", plan.targetPopulationBefore());
    view.put("targetPopulationAfter", plan.targetPopulationAfter());
    view.put("available", plan.available());
    view.put("sources", plan.sourcesView());
    view.put("workOrder", plan.workOrderPayload(reason));
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
