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
import java.util.UUID;

/**
 * ★★ {@code simos.gov.dismiss}（阶段 10b-ii，2026-10-01 GOV/Army 计划 §2.2/§2.6）：<b>GM 组合工具</b>—— 离编 +
 * 按政策从国库一次性支付退休待遇 + 留行动记录，一批落一条 revision。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：离编在 {@code unit} 切片、待遇在 {@code actor} 国库、行动记录在 {@code sd}
 * 切片；单条命令只能落一个命名空间。本工具走 {@link CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条
 * revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link GovDismissPlan#plan}（不碰 {@link ToolContext}
 * / {@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。不许出现第二份推导。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：
 *
 * <ol>
 *   <li>{@code unit.DismissStaff}（恒有）：{@code {unitId, role, count}}；
 *   <li>{@code actor.AdjustAccounts}（仅 {@code payment > 0} 时）：国库银<b>负增量</b>一条；待遇 = {@code
 *       policy.retirementPerStaff × count}，来源 = {@code ActorRef(UNIT, unitId)} @ 单位当刻有效位置；
 *   <li>{@code sd.PutInfo}（恒有）：地址 = 单位 canonical，key = {@value #INFO_KEY}，value = JSON <b>字符串</b>
 *       （role/count/待遇/tick），note = 人可读摘要。
 * </ol>
 *
 * <p>★★ <b>具名缺口（本工具明确不做，绝不静默）</b>：离编人员的<b>社会回写</b>（回老家/父老乡亲：写回指定社会批次或 hex）留 <b>阶段 13</b>
 * 的人员流转链路；本工具只减 roster、只支付银，<b>不产生任何 social.* 命令</b>，也在结果与 info 里显式标注 {@code socialWriteback=阶段 13
 * 未做（具名缺口）}。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字也不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 有意不为 {@code unit.DismissStaff} 配窄工具：裸命令会跳过退休支付，本工具批才是受支持的完整调用面。
 *
 * <p>★ <b>资源声明</b>：只写 {@code unit}/{@code actor}/{@code sd} 三个命名空间（{@link
 * ResourcePolicy#UNRESTRICTED}，GM 侧三者 unlimited）；{@code requireAll(Operation.WRITE, …)} 与其余 GM
 * 窄写同制。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / 单位不存在或不是 GOV / 现有在编不足 / 待遇乘法溢出 / 待遇 &gt;
 * 0 但无有效位置或国库可支配银不足 ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒
 * {@code REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class GovDismissTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它**不是**一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.dismiss";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 单位 canonical，一次离编一条；后续自然追加序号）。 */
  public static final String INFO_KEY = "dismiss";

  /** 本工具只写 unit / actor / sd 三个命名空间（GM 侧三者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest DISMISS_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #DISMISS_WRITE} 同源的逐命名空间粗断言（GM 三面 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与 {@code LevyRegionTool} 等同制；本工具资源声明是三个命名空间的粗断言、 国库落点按单位 id
   *     + 当刻有效位置定位，不当路径用）
   */
  public GovDismissTool(CoreSimos core, QueryService query, String initiator, String mapId) {
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
    return "GM 离编并按政策支付退休待遇（组合工具，一批 = 一条 revision）："
        + "参数 {unitId(必填, 必须是带 GovernmentFormation 的 GOV), role(必填 SCRIBE|YAMEN|POST), count(必填 ≥ 1), "
        + "reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "待遇 = policy.retirementPerStaff × count（银）；待遇>0 时国库 = ActorRef(UNIT,unitId) @ 单位当刻有效位置，"
        + "可支配银不足 ⇒ 整条拒（带 requested/available/缺口）；待遇=0 ⇒ 批里无 actor 命令。"
        + "批：unit.DismissStaff → [actor.AdjustAccounts 国库银负增量] → sd.PutInfo(key="
        + INFO_KEY
        + ")。"
        + "★ 具名缺口：离编人员的社会回写（回老家/父老乡亲）留阶段 13，本工具只减 roster + 支付银，不产生任何 social.* 命令。"
        + "返回 {preview, submitted, tick, unitId, role, count, staffBefore, staffAfter, retirementPerStaff, "
        + "payment, treasuryLocation, availableSilver, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "离编主体：带 GovernmentFormation 的 GOV 单位 id"));
    props.put("role", ToolSupport.prop("string", "行政角色：SCRIBE（书吏）|YAMEN（衙门）|POST（驿传）"));
    props.put("count", ToolSupport.prop("integer", "离编人数（≥ 1；不得超过该角色现有在编）"));
    props.put("reason", ToolSupport.prop("string", "离编原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交三条命令的同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("unitId", "role", "count", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return DISMISS_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "离编 unitId="
            + args.get("unitId")
            + " role="
            + args.get("role")
            + " count="
            + args.get("count")
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
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      if (!preview && expectedRevision < 0L) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      // ★ preview / apply 的共同输入：同一坐标上的 base 状态 + 同一份纯推导。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryService.QueryTarget.head(branch)
                  : QueryService.QueryTarget.at(branch, new RevisionId(expectedRevision)));
      GovDismissPlan.Plan plan = GovDismissPlan.plan(state, unitId, role, count);
      if (preview) {
        return ToolSupport.ok(planView(plan, reason, true, false));
      }
      return apply(plan, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界，折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "离编失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组批（固定顺序、按需缺席），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      GovDismissPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
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
    // ★ 整批拒：逐条把**真拒因**摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
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

  /** 组批：{@code unit.DismissStaff} → [payment] {@code actor.AdjustAccounts} → {@code sd.PutInfo}。 */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      GovDismissPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(3);
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovDismissPlan.DISMISS_STAFF_TYPE,
            plan.dismissStaffPayloadJson()));
    if (plan.hasPayment()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              GovDismissPlan.ADJUST_ACCOUNTS_TYPE,
              plan.adjustAccountsPayloadJson()));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovDismissPlan.PUT_INFO_TYPE,
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

  // ── 载荷：sd.PutInfo（行动记录） ─────────────────────────────────────────────────────

  /** {@code sd.PutInfo} 载荷：单位 canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovDismissPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      GovDismissPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("role", plan.role().name());
    view.put("count", plan.count());
    view.put("staffBefore", plan.staffBefore());
    view.put("staffAfter", plan.staffAfter());
    view.put("retirementPerStaff", plan.retirementPerStaff());
    view.put("payment", plan.payment());
    view.put(
        "treasuryLocation", plan.treasuryLocation().map(GovDismissPlan::treasuryView).orElse(null));
    view.put("availableSilver", plan.availableSilver());
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
