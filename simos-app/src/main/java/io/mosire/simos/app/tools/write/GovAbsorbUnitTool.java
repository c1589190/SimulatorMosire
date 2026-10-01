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
 * ★★ {@code simos.gov.absorbUnit}（阶段 13A 人员流转，GOV/Army 计划 §2.6）：<b>GM 组合工具</b>—— 把无标签纯人员单位吸收进 GOV
 * 编制（可顺带解散已空源单位），一批落一条 revision。
 *
 * <p>★★ <b>源单位口径（用户裁定 3/10）</b>：默认拒带 {@code ArmyFormation} 的源（军队单位不是人口容器），带 {@code GovFormation}
 * 的同样拒；<b>只有无 module 的纯人员单位才能被吸收</b>。拒因具名（点名是哪种编制）。
 *
 * <p>★★ <b>批顺序（固定，一条 revision）</b>：{@code unit.ApplyCasualties}（源 {@code manpower=[{type,−count}]}、
 * {@code equipment=[]}）→ {@code unit.RecruitStaff}（{@code sources=[{kind:"unit", id,
 * count}]}）→（{@code disbandSource} 且吸收后源人力合计==0）{@code unit.DisbandUnit} → {@code
 * sd.PutInfo}（key={@value #INFO_KEY}）。
 *
 * <p>★ <b>守恒</b>：源人力合计前 − count == 源人力合计后；GOV roster 前 + count == roster 后；Plan 构造期逐值互校。源有
 * 多种人力时按**源表序**逐 type 扣（min(条目余额, 剩余)），不跳到后面的 type 补。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 资源声明：只写 {@code unit}/{@code sd} 两个命名空间。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / GOV 或源单位不存在 / 源不是纯人员单位 / 源人力合计不足 / 超
 * staffCap ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code
 * REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovAbsorbUnitTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.absorbUnit";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 吸收方 GOV canonical）。 */
  public static final String INFO_KEY = "absorbUnit";

  /** 本工具只写 unit / sd 两个命名空间（GM 侧二者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest ABSORB_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #ABSORB_WRITE} 同源的逐命名空间粗断言。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public GovAbsorbUnitTool(CoreSimos core, QueryService query, String initiator, String mapId) {
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
    return "GM 吸收纯人员单位进 GOV 编制（组合工具，一批 = 一条 revision）："
        + "参数 {unitId(必填, 吸收方 GOV), role(必填 SCRIBE|YAMEN|POST), sourceUnitId(必填, 无 module 的纯人员单位), "
        + "count(必填 ≥ 1), disbandSource?(缺省 false；源吸收后已空才同批 unit.DisbandUnit), reason(必填), "
        + "preview?(缺省 true), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "★ 源带 ArmyFormation（军队不是人口容器）或 GovFormation ⇒ 默认具名拒。"
        + "批：unit.ApplyCasualties（manpower=[{type,−taken}]、equipment=[]）→ unit.RecruitStaff（sources=[{kind:\"unit\","
        + "id:sourceUnitId,count}]）→（disbandSource 且源已空）unit.DisbandUnit → sd.PutInfo(key="
        + INFO_KEY
        + ")。守恒：源人力合计前−count == 后；GOV roster 前+count == 后；源多类型时按源表序逐 type 扣。"
        + "返回 {preview, submitted, tick, unitId, sourceUnitId, role, count, sourceMemberBefore, "
        + "sourceMemberAfter, staffBefore, staffAfter, disbandSource, disbanded, disbandSkippedReason, commands, "
        + "infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "吸收方：带 GovFormation 的 GOV 单位 id"));
    props.put("role", ToolSupport.prop("string", "入编角色：SCRIBE（书吏）|YAMEN（衙门）|POST（驿传）"));
    props.put("sourceUnitId", ToolSupport.prop("string", "源人口单位 id（必须无 module：纯人员单位）"));
    props.put("count", ToolSupport.prop("integer", "吸收人数（≥ 1；不得超过源单位人力合计与 staffCap 余额）"));
    props.put("disbandSource", ToolSupport.prop("boolean", "缺省 false；true 且吸收后源人力合计=0 才同批解散源"));
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
          GovAbsorbUnitPlan.plan(state, unitId, role, sourceUnitId, count, disbandSource);
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

  /** 组批：ApplyCasualties → RecruitStaff → [DisbandUnit] → PutInfo（固定顺序）。 */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      GovAbsorbUnitPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(4);
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovAbsorbUnitPlan.APPLY_CASUALTIES_TYPE,
            plan.applyCasualtiesPayloadJson()));
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
    view.put("sourceUnitId", plan.sourceUnitId());
    view.put("role", plan.role().name());
    view.put("count", plan.count());
    view.put("sourceMemberBefore", plan.sourceMemberBefore());
    view.put("sourceMemberAfter", plan.sourceMemberAfter());
    view.put("staffBefore", plan.staffBefore());
    view.put("staffAfter", plan.staffAfter());
    view.put("disbandSource", plan.disbandSource());
    view.put("disbanded", plan.disbandDispatched());
    view.put("disbandSkippedReason", plan.disbandSkippedReason().orElse(null));
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
