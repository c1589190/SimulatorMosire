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
import java.util.Set;
import java.util.UUID;

/**
 * ★★ {@code simos.gov.recruit}（阶段 10b-ii，2026-10-01 GOV/Army 计划 §2.2/§2.6；P1.1 改走 Social
 * 家户工单）：<b>GM 组合工具</b>——从辖区家户份额转移真实成员到 {@code hh-gov-<unitId>} + 同批入编 + 留行动记录， 来源可追溯，一批落一条
 * revision。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：人在 {@code social} 切片、编制在 {@code unit} 切片、行动记录在 {@code sd}
 * 切片；单条命令只能落一个命名空间。本工具走 {@link CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条
 * revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link GovRecruitPlan#plan}（不碰 {@link ToolContext}
 * / {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。不许出现第二份推导，也不许出现第二份瀑布 （{@link
 * HouseholdManpowerAllocator} 是唯一那份）。
 *
 * <p>★★ <b>批的三条命令（固定顺序）</b>：
 *
 * <ol>
 *   <li>{@code social.SubmitHouseholdWorkOrder}（恒有）：{@code
 *       orderId=gov-recruit:<unitId>:<role>:<tick>:<count>} 确定性幂等键；target = 政府家户 {@code
 *       hh-gov-<unitId>}；{@code plan} = 逐来源 {@code TRANSFER_MEMBERS(from=来源家户, to=政府家户, lotId,
 *       count=taken)}——人真的进了政府家户；
 *   <li>{@code unit.RecruitStaff}（恒有）：{@code {unitId, role, count, sources}}，{@code sources} = 逐来源
 *       {@code {kind:"household", id, lotId, count}}；命令本身只入编（不扣人——扣人在上一腿）；
 *   <li>{@code ga.PutInfo}（恒有）：地址 = 单位 canonical，key = {@value #INFO_KEY}，value = JSON <b>字符串</b>
 *       （role/count/逐来源 householdId/lotId/taken/hex/reason/tick），note = 人可读摘要。
 * </ol>
 *
 * <p>★★ <b>守恒口径</b>：Σ share.taken == {@code count} == roster 增量；Plan 构造期逐值互校（见 {@link
 * GovRecruitPlan.Plan}），三条命令的载荷都从同一份 sources 派生 ⇒ 没有第二份数字。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 有意不为 {@code unit.RecruitStaff} 配窄工具：裸命令只入编不扣人，本工具批才是受支持的完整调用面。
 *
 * <p>★ <b>资源声明</b>：只写 {@code social}/{@code unit}/{@code sd} 三个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}， GM 侧三者 unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM
 * 窄写同制。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / 单位不存在或不是 GOV / 超 staffCap / 无管辖 / 辖区
 * Region 查无 / 政府家户不在 Unit.households 或 social.households / 来源总量不足 ⇒ {@link
 * IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code REJECTED} 带逐条真拒因；提交冲突
 * ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class GovRecruitTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.recruit";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 单位 canonical，一次招募一条；后续自然追加序号）。 */
  public static final String INFO_KEY = "recruit";

  /** 本工具只写 social / unit / sd 三个命名空间（GM 侧三者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest RECRUIT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #RECRUIT_WRITE} 同源的逐命名空间粗断言（GM 三面 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.GA_NAMESPACE, "*"));

  private final GovToolSupport support;

  /**
   * @param support 政府工具接缝束（写口/读口/视图口/视角口/经济口/命令类型口/人力口；见 {@link GovToolSupport}）
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与 {@code LevyRegionTool} 等同制；本工具资源声明是三个命名空间的粗断言、 来源按 unit 的
   *     jurisdiction Region 定位，不当路径用）
   */
  public GovRecruitTool(GovToolSupport support, String mapId) {
    this.support = Objects.requireNonNull(support, "support");
    Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 从辖区家户份额转移真实成员到招募目标家户（组合工具，一批 = 一条 revision；Z4/C1 双模式）：参数"
        + " {unitId(必填, 必须是带 GovernmentFormation 的 GOV), role(必填 SCRIBE|YAMEN|POST), count(必填 ≥ 1),"
        + " reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "★ 目标家户选择：GOV 的 Unit.households 含岗位户 hh-unit:<unitId> ⇒ 新世界模式：目标 = 岗位户，批落"
        + " unit.AssignGovPost（只写 householdPosts，绝不写 staff），不校验 staffCap；否则 householdPosts 必须为空（旧档）"
        + " ⇒ 目标 = hh-gov-<unitId>，保留 unit.RecruitStaff 与 staffCap 旧行为。目标必须同时在 Unit.households 与 Social 里，"
        + "否则具名拒（不猜、不静默降级）。来源口径：按单位 jurisdiction 的 Region 顺序把各区 hex 集合交给"
        + " HouseholdManpowerAllocator 的 MALE+ADULT 家户份额瀑布（排除目标家户/财政户）；总量不足 ⇒ 整条拒（带"
        + " requested/available/缺口），不部分抽取。批：social.SubmitHouseholdWorkOrder（orderId="
        + "gov-recruit:<unitId>:<role>:<tick>:<count> 幂等键，target=目标家户，逐来源 TRANSFER_MEMBERS(from=来源家户,"
        + "to=目标家户,lotId,count=taken)）→ [unit.AssignGovPost | unit.RecruitStaff] → ga.PutInfo（key="
        + INFO_KEY
        + "）。守恒：Σ share.taken == count（旧档另 == roster 增量）。返回 {preview, submitted, mode, tick, unitId,"
        + " targetHouseholdId, role, count, [旧档 staffBefore/After/Cap], available,"
        + " sources[{householdId,lotId,taken,hex}], commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "unitId",
        ToolSchema.prop(
            "string",
            "招募主体：带 GovernmentFormation 的 GOV 单位 id（目标家户：有岗位户 hh-unit:<unitId> ⇒ 用它；否则旧档"
                + "财政户 hh-gov-<unitId> 必须已同时在 Unit.households 与 Social 里）"));
    props.put("role", ToolSchema.prop("string", "行政角色：SCRIBE（书吏）|YAMEN（衙门）|POST（驿传）"));
    props.put("count", ToolSchema.prop("integer", "招募人数（≥ 1；旧档另受 staffCap[role] 剩余额度约束，新世界落岗位指派）"));
    props.put(
        "reason", ToolSchema.prop("string", "招募原因（必填非空白；进 Social 工单 reason、ga.PutInfo 行动记录与工具结果）"));
    props.put(
        "preview",
        ToolSchema.prop(
            "boolean",
            "true（缺省）= 只算不写；false = 提交同一批三条命令（SubmitHouseholdWorkOrder + [AssignGovPost|RecruitStaff]"
                + " + PutInfo）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("unitId", "role", "count", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return RECRUIT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "招募 unitId="
            + args.get("unitId")
            + " role="
            + args.get("role")
            + " count="
            + args.get("count")
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
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          support
              .reads()
              .stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      GovRecruitPlan.Plan plan = GovRecruitPlan.plan(support, state, unitId, role, count);
      if (preview) {
        return ToolResults.ok(planView(plan, reason, true, false));
      }
      return apply(plan, reason, branch, expectedRevision, planOnly);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界，折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "招募失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组三条命令的同一批，走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      GovRecruitPlan.Plan plan,
      String reason,
      BranchId branch,
      long expectedRevision,
      boolean planOnly) {
    String batchId = UUID.randomUUID().toString();
    List<ToolWritePort.Request> batch = buildBatch(batchId, plan, reason);

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
   * 组批（固定顺序、按模式二选一）：{@code social.SubmitHouseholdWorkOrder} → [新世界 {@code unit.AssignGovPost} / 旧档
   * {@code unit.RecruitStaff}] → {@code ga.PutInfo}。
   */
  private List<ToolWritePort.Request> buildBatch(
      String batchId, GovRecruitPlan.Plan plan, String reason) {
    List<ToolWritePort.Request> batch = new ArrayList<>(3);
    batch.add(
        GovToolSupport.request(
            GovRecruitPlan.SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
            plan.submitHouseholdWorkOrderPayloadJson(reason)));
    if (plan.postHouseholdMode()) {
      batch.add(
          GovToolSupport.request(
              GovRecruitPlan.ASSIGN_GOV_POST_TYPE, plan.assignGovPostPayloadJson()));
    } else {
      batch.add(
          GovToolSupport.request(
              GovRecruitPlan.RECRUIT_STAFF_TYPE, plan.recruitStaffPayloadJson()));
    }
    batch.add(GovToolSupport.request(GovRecruitPlan.PUT_INFO_TYPE, infoPayload(plan, reason)));
    // ★ 保序冻结在赋值处（本仓口径：不用 List.copyOf）。
    return java.util.Collections.unmodifiableList(batch);
  }

  // ── 载荷：ga.PutInfo（行动记录） ─────────────────────────────────────────────────────

  /** {@code ga.PutInfo} 载荷：单位 canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovRecruitPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolResults.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      GovRecruitPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("mode", plan.postHouseholdMode() ? "post-household" : "treasury-household-legacy");
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("targetHouseholdId", plan.targetHouseholdId().value());
    view.put("role", plan.role().name());
    view.put("count", plan.count());
    if (!plan.postHouseholdMode()) {
      // ★ 旧档键名兼容：目标 = hh-gov-<unitId>。
      view.put("governmentHouseholdId", plan.targetHouseholdId().value());
      view.put("staffBefore", plan.staffBefore());
      view.put("staffAfter", plan.staffAfter());
      view.put("staffCap", plan.staffCap().orElse(null));
    }
    view.put("available", plan.available());
    view.put("sources", plan.sourcesView());
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
