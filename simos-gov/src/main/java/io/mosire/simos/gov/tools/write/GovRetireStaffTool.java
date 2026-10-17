package io.mosire.simos.gov.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.DecisionProposable;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ★★ {@code simos.gov.retireStaff}（阶段 13A 人员流转；P1.2 改走 Social 家户工单）：<b>GM 组合工具</b>—— 离编 + 按政策一次性
 * 支付退休待遇（支付口径未改，仍复用 {@link GovDismissPlan}）+ 从政府家户转出真实成员到明确目标家户 + 留行动记录，一批落一条 revision。
 *
 * <p>★★ <b>批顺序（固定，一条 revision）</b>：{@code social.SubmitHouseholdWorkOrder}（ {@code
 * orderId=gov-retire:<unitId>:<role>:<tick>:<count>:<目标家户>} 幂等键；逐 lot {@code
 * TRANSFER_MEMBERS(from=hh-gov-<unitId>, to=目标家户, lotId, count=taken)}）→ {@code
 * unit.DismissStaff}（形状不变） →（待遇 &gt; 0）{@code actor.AdjustAccounts} → {@code
 * ga.PutInfo}（key={@value #INFO_KEY}）。
 *
 * <p>★★ <b>目标家户必须明确</b>：{@code toHouseholdId} 精确指定（必须存在于 Social、≠ 政府家户）；或给 {@code reinsertQ}/{@code
 * reinsertR} 成对坐标，在该 hex 按 household id 升序取第一个有人口的家户；两者都没给 ⇒ plan 级具名拒（人不能凭空消失，也不再合并进最小批次）。
 *
 * <p>★★ <b>两桶都注册</b>（{@code toolRoles()} 含 DM 与 GM）：GM 桶在 {@code SimosToolSource.addGmWrites}、决策人桶在
 * {@code addDecisionAgentWrites} 的**可提议工具族**里。★ 2026-10-15 用户裁定「甲」：本工具本来就是**决策人的核心动作**，当年只是**漏打了 DM
 * 标签** ⇒ 补上；它本来就是 {@code gate=Ask} ⇒ 自动成**类2 {@code DM_APPROVAL}**——DM 直写停在待批等 GM 点头（超时 {@code
 * APPROVAL_DENIED}），或走 {@code simos.ga.packet.propose} 由 GM 统一批（零审批、不阻塞）。★ 它**不是一条命令类型** ⇒
 * 不进**命令目录**（{@code simos.command.catalog} / {@code PAYLOAD_HINTS}）；★ 但它在 {@code ProposalCatalog} 的
 * **可提议清单**里（声明 = 本类实现 {@code DecisionProposable}）。★ 资源声明：写 {@code unit}/{@code actor}/{@code
 * social}/{@code sd} 四个命名空间 （GM 侧全部 unlimited）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / 单位不是 GOV / roster 不足 / 待遇溢出 / 国库银不足 /
 * 目标家户缺失或与源相同 / 政府家户不在 Unit.households 或 Social / 政府家户人口不足 / reinsert 坐标不成对 / 回写格无有人口家户 ⇒ {@link
 * IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code REJECTED} 带逐条真拒因； 提交冲突
 * ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovRetireStaffTool implements SimosTool, DecisionProposable {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.retireStaff";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 离编 GOV canonical）。 */
  public static final String INFO_KEY = "retireStaff";

  /** 本工具只写 unit / actor / social / sd 四个命名空间（GM 侧全部 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest RETIRE_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #RETIRE_WRITE} 同源的逐命名空间粗断言。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.GA_NAMESPACE, "*"));

  private final GovToolSupport support;

  public GovRetireStaffTool(GovToolSupport support, String mapId) {
    this.support = Objects.requireNonNull(support, "support");
    Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 退休离编 + 待遇校验/支付 + 从官吏岗位户（或旧档财政户）转出真实成员到目标家户（组合工具，一批 = 一条 revision）："
        + "参数 {unitId(必填, 带 GovernmentFormation 的 GOV), role(必填 SCRIBE|YAMEN|POST), count(必填 ≥ 1), "
        + "toHouseholdId?(精确目标家户，必须存在于 Social 且 ≠ 退休源；与 reinsertQ/reinsertR 二选一), "
        + "reinsertQ?(回退目标格 q，必须与 reinsertR 成对；在该 hex 按 household id 升序取第一个有人口的家户), "
        + "reinsertR?(回退目标格 r), reason(必填), preview?(缺省 true), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "目标家户必须明确：toHouseholdId 与 reinsertQ/reinsertR 二选一；都没给 ⇒ 具名拒（人不能凭空消失）。"
        + "★ Z4/C1 双模式：GOV 的 Unit.households 含岗位户 hh-unit:<unitId> ⇒ 退休源 = 该岗位户，人口 ≥ count，"
        + "批里没有 unit.DismissStaff（staff 是岗位家户投影）；否则 householdPosts 必须为空（旧档）⇒ 退休源 = "
        + "hh-gov-<unitId>，另校验现有在编并保留 unit.DismissStaff 腿。"
        + "待遇 = policy.retirementPerStaff × count（银）；待遇>0 时国库 = 政府家户 hh-gov-<unitId> @ 单位当刻有效位置，"
        + "可支配银不足 ⇒ 整条拒（带 requested/available/缺口）；待遇=0 ⇒ 批里无 actor 命令。"
        + "批顺序：social.SubmitHouseholdWorkOrder（orderId=gov-retire:<unitId>:<role>:<tick>:<count>:<目标家户> 幂等键，"
        + "target=目标家户，逐 lot TRANSFER_MEMBERS(from=退休源,to=目标家户,lotId,count=taken)）→ "
        + "[旧档 unit.DismissStaff] → [actor.AdjustAccounts] → ga.PutInfo(key="
        + INFO_KEY
        + ")。"
        + "返回 {preview, submitted, mode, tick, unitId, role, count, [旧档 staffBefore/After], retirementPerStaff, "
        + "payment, treasuryLocation, availableSilver, sourceHouseholdId, sourcePopulationBefore/After, "
        + "targetHouseholdId, targetHex, targetPopulationBefore/After, available, "
        + "sources[{householdId,lotId,taken,hex}], workOrder, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSchema.prop("string", "离编主体：带 GovernmentFormation 的 GOV 单位 id"));
    props.put("role", ToolSchema.prop("string", "行政角色：SCRIBE（书吏）|YAMEN（衙门）|POST（驿传）"));
    props.put("count", ToolSchema.prop("integer", "离编人数（≥ 1；新世界 ≤ 岗位户人口，旧档 ≤ 该角色现有在编）"));
    props.put(
        "toHouseholdId",
        ToolSchema.prop("string", "精确目标家户 id（可选；必须存在于 Social 且 ≠ 退休源；与 reinsertQ/reinsertR 二选一）"));
    props.put(
        "reinsertQ",
        ToolSchema.prop(
            "integer",
            "回退目标格 q（可选；与 reinsertR 成对且不与 toHouseholdId 同给；在该 hex 按 household id 升序取第一个有人口的家户）"));
    props.put("reinsertR", ToolSchema.prop("integer", "回退目标格 r（可选；必须与 reinsertQ 成对）"));
    props.put("reason", ToolSchema.prop("string", "退休离编原因（必填非空白；进 ga.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批命令"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("unitId", "role", "count", "reason"));
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
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String unitId = ToolSchema.requiredText(args, "unitId");
      String role = ToolSchema.requiredText(args, "role");
      long count = ToolSchema.requiredLong(args, "count");
      String reason = ToolSchema.requiredText(args, "reason");
      // ★ 目标家户：toHouseholdId 精确指定；未给时用 reinsertQ/reinsertR（成对校验在纯推导里，工具只做类型/缺省解析）。
      Optional<String> toHouseholdId =
          Optional.ofNullable(ToolSchema.optionalText(args, "toHouseholdId", null));
      Optional<Long> reinsertQ = Optional.ofNullable(ToolSchema.optionalLong(args, "reinsertQ"));
      Optional<Long> reinsertR = Optional.ofNullable(ToolSchema.optionalLong(args, "reinsertR"));
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      boolean planOnly = !preview && ToolSchema.optionalBoolean(args, "planOnly").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      SimulationState state =
          support
              .reads()
              .stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      GovRetireStaffPlan.Plan plan =
          GovRetireStaffPlan.plan(
              support, state, unitId, role, count, toHouseholdId, reinsertQ, reinsertR);
      if (preview) {
        return ToolResults.ok(planView(plan, reason, true, false));
      }
      return apply(plan, reason, branch, expectedRevision, planOnly);
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
      GovRetireStaffPlan.Plan plan,
      String reason,
      BranchId branch,
      long expectedRevision,
      boolean planOnly) {
    String batchId = UUID.randomUUID().toString();
    List<ToolWritePort.Request> batch = buildBatch(batchId, plan, reason);
    // ★★ D3 执行器契约（DecisionEffectExecutor 类注）：planOnly=true 走与 preview=false **完全相同**的纯推导与组批，
    //    但在提交**之前**把命令批交回执行器（一个字节都不写）。缺这一支 ⇒ 执行器的"规划阶段"真落盘 + 无
    //    plannedCommands ⇒ PLANNING_FAILED ⇒ GM 重试再落一条（双写）。
    if (planOnly) {
      return ToolResults.ok(GovBatchViews.planOnly(batch));
    }

    ToolWritePort.BatchReceipt receipt =
        support
            .writes()
            .submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch, batchId);
    Map<String, Object> view = planView(plan, reason, false, true);
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committed) {
      view.put("submission", ToolResults.committedView(committed.ref(), batchId, batchId));
      return ToolResults.ok(view);
    }
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Conflict conflict) {
      view.put("submission", GovBatchViews.conflictSubmission(batchId, conflict.current()));
      return ToolResult.error("CONFLICT", ToolResults.json(view));
    }
    view.put(
        "submission",
        GovBatchViews.rejectedSubmission(
            batchId, batch, ((ToolWritePort.BatchOutcome.Rejected) receipt.outcome()).outcomes()));
    return ToolResult.error("REJECTED", ToolResults.json(view));
  }

  /**
   * 组批（固定顺序、按需缺席）：{@code social.SubmitHouseholdWorkOrder} → [旧档 {@code unit.DismissStaff}] → （待遇
   * &gt; 0）{@code actor.AdjustAccounts} → {@code ga.PutInfo}。
   */
  private List<ToolWritePort.Request> buildBatch(
      String batchId, GovRetireStaffPlan.Plan plan, String reason) {
    List<ToolWritePort.Request> batch = new ArrayList<>(4);
    batch.add(
        GovToolSupport.request(
            GovRetireStaffPlan.SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
            plan.submitHouseholdWorkOrderPayloadJson(reason)));
    if (!plan.postHouseholdMode()) {
      batch.add(
          GovToolSupport.request(
              GovRetireStaffPlan.DISMISS_STAFF_TYPE, plan.dismissStaffPayloadJson()));
    }
    if (plan.hasPayment()) {
      batch.add(
          GovToolSupport.request(
              GovRetireStaffPlan.ADJUST_ACCOUNTS_TYPE, plan.adjustAccountsPayloadJson()));
    }
    batch.add(GovToolSupport.request(GovRetireStaffPlan.PUT_INFO_TYPE, infoPayload(plan, reason)));
    // ★ 保序冻结在赋值处（本仓口径：不用 List.copyOf）。
    return java.util.Collections.unmodifiableList(batch);
  }

  /** {@code ga.PutInfo} 载荷：离编 GOV canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovRetireStaffPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolResults.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> planView(
      GovRetireStaffPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("mode", plan.postHouseholdMode() ? "post-household" : "treasury-household-legacy");
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("role", plan.role().name());
    view.put("count", plan.count());
    if (!plan.postHouseholdMode()) {
      view.put("staffBefore", plan.legacyStaffBefore());
      view.put("staffAfter", plan.legacyStaffAfter());
    }
    view.put("retirementPerStaff", plan.retirementPerStaff());
    view.put("payment", plan.payment());
    view.put(
        "treasuryLocation", plan.treasuryLocation().map(GovDismissPlan::treasuryView).orElse(null));
    view.put("availableSilver", plan.availableSilver());
    view.put("sourceHouseholdId", plan.sourceHouseholdId().value());
    view.put("sourcePopulationBefore", plan.sourcePopulationBefore());
    view.put("sourcePopulationAfter", plan.sourcePopulationAfter());
    if (!plan.postHouseholdMode()) {
      // ★ 旧档键名兼容：源 = hh-gov-<unitId>；旧视图的 governmentHouseholdId/Population* 逐值保留。
      view.put("governmentHouseholdId", plan.sourceHouseholdId().value());
      view.put("governmentPopulationBefore", plan.sourcePopulationBefore());
      view.put("governmentPopulationAfter", plan.sourcePopulationAfter());
    }
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    // ★★ 2026-10-15 用户裁定「甲」：本工具**本来就该是决策人能提议的**（决策人的核心动作），当年只是**漏打了 DM 标签** ⇒ 补上。
    //   它本来就是 gate=Ask ⇒ 补上后自动成**类2 DM_APPROVAL**（「DM/GM 混合就是要交给 GM 审核」，与用户口径一致）；
    //   装配面同步注册进**决策人桶**（`SimosToolSource` 的可提议工具族）⇒ `ToolRoleAudit` 的「桶 ⇔ toolRoles()」逐条一致。
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
