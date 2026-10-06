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
import io.mosire.simos.app.time.CalendarService;
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
 * ★★ {@code simos.gov.absorbUnit}（阶段 13A 人员流转；2026-10-19 家户口径接线）：<b>GM 组合工具</b>——把无 module
 * 纯人员单位 {@code Unit.households()} 里的<b>真实家户成员</b>吸收进 GOV 的政府家户 {@code hh-gov-<govUnitId>}
 *（可顺带解散已空的源单位），一批落一条 revision。
 *
 * <p>★★ <b>S3b 口径（用户 2026-10-19 裁定 2）</b>：{@code Unit.manpower} 已退役，本工具<b>不再发 {@code
 * unit.ApplyCasualties}、不写 Unit 侧第二本 headcount</b>；人口只从 Social 家户出、进 Social 家户（{@code
 * social.SubmitHouseholdWorkOrder} 的 {@code TRANSFER_MEMBERS}），{@code unit.RecruitStaff} 只加编制
 * {@code staff[role] += count}。
 *
 * <p>★★ <b>源单位口径</b>：默认拒带 {@code ArmyFormation} 的源（军队单位不是人口容器），带 {@code GovernmentFormation}
 * 的同样拒；<b>只有无 module 的纯人员单位才能被吸收</b>，且其 {@code Unit.households()} 必须非空。拒因具名（点名是哪种编制）。
 *
 * <p>★★ <b>批顺序（固定，一条 revision）</b>：
 *
 * <ol>
 *   <li>{@code social.SubmitHouseholdWorkOrder}（恒有）：{@code orderId=gov-absorb-unit:<batchId>:<govUnitId>:<sourceUnitId>}；
 *       {@code target = hh-gov-<govUnitId>}；{@code plan} = 逐来源 {@code TRANSFER_MEMBERS(from=源家户, to=政府家户,
 *       lotId, count)}；{@code disbandDispatched} 时再逐源家户 {@code SET_LOCATION(HEX = GOV 单位当刻
 *       effectivePosition)}——必须在 {@code unit.DisbandUnit} 之前，避免孤儿 {@code UNIT(sourceUnitId)} 位置；
 *   <li>{@code unit.RecruitStaff}（恒有）：{@code {unitId, role, count, sources}}，只入编、不扣人；
 *   <li>{@code unit.DisbandUnit}（仅 {@link GovAbsorbUnitPlan.Plan#disbandDispatched()}）：解散已清空的源单位；
 *   <li>{@code sd.PutInfo}（恒有）：地址 = GOV canonical，key={@value #INFO_KEY}，value = JSON <b>字符串</b>
 *       （govUnitId/sourceUnitId/role/count/shares/disbanded/disbandSkippedReason 等），note = 人可读摘要。
 * </ol>
 *
 * <p>★ <b>守恒</b>：{@code Σ share.taken == count == roster 增量}；源家户迁移后剩余合计 == 源人口前 − count；世界 Social
 * 总人口不变（转移只改份额归属）。Plan 构造期逐值互校；三条/四条命令的载荷都从同一份 shares/remainders 派生。
 *
 * <p>★ <b>disbandSource 的条件语义</b>：只有“所有源家户迁移后剩余人口 == 0”才落 {@code SET_LOCATION + unit.DisbandUnit}；
 * 仍有剩余人口 ⇒ <b>不解散</b>，工具结果与 {@code sd.PutInfo} 里给具名 {@code disbandSkippedReason}（不丢人）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 资源声明：写 {@code social}/{@code unit}/{@code sd} 三个命名空间（GM 侧三者 unlimited）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / GOV 或源单位不存在 / 源不是纯人员单位 / 源 households
 * 为空 / 政府家户不在 Unit.households 或 Social / 源合格人口不足 / 超 staffCap ⇒ {@link IllegalArgumentException} 折
 * {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；
 * 资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovAbsorbUnitTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.absorbUnit";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 吸收方 GOV canonical）。 */
  public static final String INFO_KEY = "absorbUnit";

  /** 本工具只写 social / unit / sd 三个命名空间（GM 侧三者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest ABSORB_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #ABSORB_WRITE} 同源的逐命名空间粗断言。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /** 历法/气候服务：选人层按它现算 MALE+ADULT 年龄档（生产路径 = CalendarService.load；旧路径 = 全缺省）。 */
  private final CalendarService calendarService;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（★ 保留在装配签名里以与同批 GOV 工具同制；本工具资源声明是三个命名空间的粗断言、
   *     来源按源单位 {@code Unit.households()} 定位，不当路径用）
   */
  // ★ 测试/旧路径：全缺省时钟，不读 store；生产 Shell 必须用带 CalendarService 的重载（CalendarService.load）。
  public GovAbsorbUnitTool(CoreSimos core, QueryService query, String initiator, String mapId) {
    this(core, query, initiator, mapId, CalendarService.defaults());
  }

  /** 生产构造器：历法时钟来自启动期 {@link CalendarService#load} 的同一实例。 */
  public GovAbsorbUnitTool(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      CalendarService calendarService) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
    Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 吸收纯人员单位进 GOV 编制（组合工具，一批 = 一条 revision；人口走 Social 家户真转移）。"
        + "参数 {unitId(必填, 吸收方 GOV), role(必填 SCRIBE|YAMEN|POST), sourceUnitId(必填, 无 module 且"
        + " Unit.households 非空的纯人员单位), count(必填 ≥ 1), disbandSource?(缺省 false；迁移后所有源家户人口=0"
        + " 才同批 SET_LOCATION + unit.DisbandUnit), reason(必填), preview?(缺省 true), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "★ 来源 = 源单位 Unit.households() 的家户成员份额，用 HouseholdManpowerAllocator 抽 MALE+ADULT 恰好 count 人；"
        + "不足整条拒。★ 源带 ArmyFormation 或 GovernmentFormation ⇒ 具名拒（不是人口容器）。"
        + "批：social.SubmitHouseholdWorkOrder（逐 share TRANSFER_MEMBERS 到 hh-gov-<unitId>；disband 时再逐源家户 "
        + "SET_LOCATION=GOV 有效位置 HEX）→ unit.RecruitStaff（role += count）→（disband）unit.DisbandUnit → "
        + "sd.PutInfo(key="
        + INFO_KEY
        + ")。不再发 unit.ApplyCasualties，也不写 Unit 第二本 headcount。守恒：Σshare.taken == count；"
        + "源家户人口前−count == 后；staff 前+count == 后。"
        + "返回 {preview, submitted, tick, unitId, governmentHouseholdId, sourceUnitId, role, count, "
        + "staffBefore, staffAfter, sourcePopulationBefore, sourcePopulationAfter, shares, householdRemainders, "
        + "disbandSource, disbanded, disbandSkippedReason, setLocationHex, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "吸收方：带 GovernmentFormation 的 GOV 单位 id"));
    props.put("role", ToolSupport.prop("string", "入编角色：SCRIBE（书吏）|YAMEN（衙门）|POST（驿传）"));
    props.put("sourceUnitId", ToolSupport.prop("string", "源人口单位 id（必须无 module：纯人员单位）"));
    props.put(
        "count",
        ToolSupport.prop(
            "integer", "吸收人数（≥ 1；必须能从源单位家户份额里抽出 MALE+ADULT 恰好 count 人，且不得超 staffCap 余额）"));
    props.put(
        "disbandSource",
        ToolSupport.prop(
            "boolean",
            "缺省 false；true 且迁移后所有源家户人口=0 才同批 SET_LOCATION(源家户→GOV 有效位置 HEX) + unit.DisbandUnit；"
                + "仍有剩余人口 ⇒ 不解散并给 disbandSkippedReason"));
    props.put("reason", ToolSupport.prop("string", "吸收原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("unitId", "role", "sourceUnitId", "count", "reason"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ABSORB_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "吸收 unitId="
            + args.get("unitId")
            + " role="
            + args.get("role")
            + " sourceUnitId="
            + args.get("sourceUnitId")
            + " count="
            + args.get("count")
            + " disbandSource="
            + args.getOrDefault("disbandSource", false)
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
      String sourceUnitId = ToolSupport.requiredText(args, "sourceUnitId");
      long count = ToolSupport.requiredLong(args, "count");
      String reason = ToolSupport.requiredText(args, "reason");
      boolean disbandSource = ToolSupport.optionalBoolean(args, "disbandSource").orElse(false);
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
      GovAbsorbUnitPlan.Plan plan =
          GovAbsorbUnitPlan.plan(
              state, unitId, role, sourceUnitId, count, disbandSource, calendarService.clock());
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
          "TOOL_ERROR", "吸收单位失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  private ToolResult apply(
      GovAbsorbUnitPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
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
   * 组批（固定顺序）：{@code social.SubmitHouseholdWorkOrder} → {@code unit.RecruitStaff} →
   * [disbandDispatched: {@code unit.DisbandUnit}] → {@code sd.PutInfo}。
   *
   * <p>★ 全部共享同一 {@code batchId}（correlationId）与同一 branch/expectedRevision ⇒ {@code submitBatch} 落一条
   * revision；第 1 条工单的 {@code orderId} 用同一 {@code batchId} 作确定性幂等键（Tool 每次 apply 生成的 UUID）。
   */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      GovAbsorbUnitPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(4);
    // ★★ 先落 Social 工单（逐 share TRANSFER_MEMBERS 到政府家户），再入编；disband 的 SET_LOCATION 也在这张工单里，
    //   排在 unit.DisbandUnit 之前 ⇒ 不会留下孤儿 UNIT 位置。
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovAbsorbUnitPlan.SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
            plan.submitHouseholdWorkOrderPayloadJson(batchId, reason)));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovAbsorbUnitPlan.RECRUIT_STAFF_TYPE,
            plan.recruitStaffPayloadJson()));
    if (plan.disbandDispatched()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              GovAbsorbUnitPlan.DISBAND_UNIT_TYPE,
              plan.disbandUnitPayloadJson()));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovAbsorbUnitPlan.PUT_INFO_TYPE,
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

  /** {@code sd.PutInfo} 载荷：吸收方 GOV canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovAbsorbUnitPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.govUnitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> planView(
      GovAbsorbUnitPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.govUnitId());
    view.put("governmentHouseholdId", plan.governmentHouseholdId().value());
    view.put("sourceUnitId", plan.sourceUnitId());
    view.put("role", plan.role().name());
    view.put("count", plan.count());
    view.put("staffBefore", plan.staffBefore());
    view.put("staffAfter", plan.staffAfter());
    view.put("sourcePopulationBefore", plan.sourcePopulationBefore());
    view.put("sourcePopulationAfter", plan.sourcePopulationAfter());
    view.put("shares", plan.sharesView());
    view.put("householdRemainders", plan.remaindersView());
    view.put("disbandSource", plan.disbandSource());
    view.put("disbanded", plan.disbandDispatched());
    view.put("disbandSkippedReason", plan.disbandSkippedReason().orElse(null));
    view.put("setLocationHex", plan.setLocationHexView());
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
