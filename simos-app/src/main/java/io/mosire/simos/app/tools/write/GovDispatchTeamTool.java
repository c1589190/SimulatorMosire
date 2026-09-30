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
 * ★★ {@code simos.gov.dispatchTeam}（阶段 13A 人员流转，GOV/Army 计划 §2.6）：<b>GM 组合工具</b>——从 GOV 编制
 * 出人、同批建无标签纯人员单位（{@code armed=true} 时加 ArmyFormation），一批落一条 revision。
 *
 * <p>★★ <b>调查组设计口径（用户裁定 3/10）</b>：调查组 = <b>只有移动状态、没有额外状态的纯人员单位</b>；武装调查组 = 给该单位加 {@code
 * ArmyFormation}（= 训练一支小军队，走通用接口），<b>不造“调查组”新类型</b>。
 *
 * <p>★★ <b>出人不付退休待遇</b>：本工具直接走 {@code unit.DismissStaff}（只减 roster），<b>不经</b> {@code
 * simos.gov.dismiss} 的付款逻辑——退休待遇只在 {@code simos.gov.retireStaff} 那条链路里支付。
 *
 * <p>★★ <b>批顺序（固定，一条 revision）</b>：{@code unit.DismissStaff} → {@code
 * unit.CreateUnit}（member=count、 equipment={}、speed=6、mobilityPerMille=900、position=来源 GOV
 * 当刻有效位置）→（armed=true）{@code unit.SetArmyFormation}（masterGov=来源 GOV、role="armed-team"）→ {@code
 * sd.PutInfo}（key={@value #INFO_KEY}， 含 armed 标记）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 资源声明：只写 {@code unit}/{@code sd} 两个命名空间。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / role 不在词表 / count &lt; 1 / 来源不是 GOV / roster 不足 / 无有效位置 / 新 id 已存在
 * ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code REJECTED}
 * 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovDispatchTeamTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.dispatchTeam";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 来源 GOV canonical）。 */
  public static final String INFO_KEY = "dispatchTeam";

  /** 本工具只写 unit / sd 两个命名空间（GM 侧二者 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest DISPATCH_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #DISPATCH_WRITE} 同源的逐命名空间粗断言。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public GovDispatchTeamTool(CoreSimos core, QueryService query, String initiator, String mapId) {
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
    return "GM 从 GOV 编制派出调查组（组合工具，一批 = 一条 revision）："
        + "参数 {unitId(必填, 带 GovFormation 的 GOV), count(必填 ≥ 1), role?(SCRIBE|YAMEN|POST，缺省 SCRIBE), "
        + "armed?(缺省 false；true = 同批加 ArmyFormation masterGov=unitId role=armed-team), newUnitId?(可选；"
        + "缺省确定性生成), reason(必填), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "出人不付退休待遇（直接 unit.DismissStaff，只减 roster）。"
        + "批：unit.DismissStaff → unit.CreateUnit（untagged 纯人员单位：member=count、equipment={}、speed=6、"
        + "mobilityPerMille=900、position=来源 GOV 当刻有效位置）→（armed）unit.SetArmyFormation → sd.PutInfo(key="
        + INFO_KEY
        + ")。守恒：roster−count == 出人后 roster == 新单位 member 对应。"
        + "返回 {preview, submitted, tick, unitId, newUnitId, count, role, staffBefore, staffAfter, armed, at, "
        + "commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "来源：带 GovFormation 的 GOV 单位 id"));
    props.put("count", ToolSupport.prop("integer", "出人数量（≥ 1；不得超过该角色现有在编与 int 上限）"));
    props.put("role", ToolSupport.prop("string", "出人角色：SCRIBE|YAMEN|POST（可选；缺省 SCRIBE）"));
    props.put(
        "armed", ToolSupport.prop("boolean", "true = 武装调查组（同批加 ArmyFormation，通用接口）；缺省 false"));
    props.put(
        "newUnitId",
        ToolSupport.prop("string", "新调查组单位 id（可选；缺省用 team-<来源GOV>-<tick>-<count> 确定性生成）"));
    props.put("reason", ToolSupport.prop("string", "派出原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("unitId", "count", "reason"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return DISPATCH_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "派调查组 unitId="
            + args.get("unitId")
            + " count="
            + args.get("count")
            + " role="
            + args.getOrDefault("role", GovDispatchTeamPlan.DEFAULT_ROLE)
            + " armed="
            + args.getOrDefault("armed", false)
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
      long count = ToolSupport.requiredLong(args, "count");
      String reason = ToolSupport.requiredText(args, "reason");
      Optional<String> role = optionalText(args, "role");
      boolean armed = ToolSupport.optionalBoolean(args, "armed").orElse(false);
      Optional<String> newUnitId = optionalText(args, "newUnitId");
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
      GovDispatchTeamPlan.Plan plan =
          GovDispatchTeamPlan.plan(state, unitId, count, role, armed, newUnitId);
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
          "TOOL_ERROR", "派调查组失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Optional<String> optionalText(Map<String, Object> args, String name) {
    return ToolSupport.has(args, name)
        ? Optional.of(ToolSupport.requiredText(args, name))
        : Optional.empty();
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  private ToolResult apply(
      GovDispatchTeamPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
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

  /** 组批：DismissStaff → CreateUnit → [SetArmyFormation] → PutInfo（固定顺序）。 */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      GovDispatchTeamPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(4);
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovDispatchTeamPlan.DISMISS_STAFF_TYPE,
            plan.dismissStaffPayloadJson()));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovDispatchTeamPlan.CREATE_UNIT_TYPE,
            plan.createUnitPayloadJson()));
    if (plan.armed()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              GovDispatchTeamPlan.SET_ARMY_FORMATION_TYPE,
              plan.setArmyFormationPayloadJson()));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovDispatchTeamPlan.PUT_INFO_TYPE,
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

  /** {@code sd.PutInfo} 载荷：来源 GOV canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovDispatchTeamPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> planView(
      GovDispatchTeamPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("newUnitId", plan.newUnitId());
    view.put("count", plan.count());
    view.put("role", plan.role().name());
    view.put("staffBefore", plan.staffBefore());
    view.put("staffAfter", plan.staffAfter());
    view.put("armed", plan.armed());
    view.put("at", ToolSupport.hexCoord(plan.at()));
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
