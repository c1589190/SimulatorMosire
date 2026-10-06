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
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.gov.selectExaminees}（阶段 13A 人员流转；P1.5 接完整 Social 工单路径）：<b>GM 组合工具</b>——
 * 从来源 GOV 辖区的 Social 家户份额选人 + 同批建<b>无标签纯人员单位</b>（可给目的 GOV 规划路线）+ 补家户经济行/账户 + 留行动记录，一批落一条
 * revision。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：人在 {@code social} 切片、新单位/路线在 {@code unit} 切片、家户经济行在
 * {@code economy} 切片、家户账户在 {@code actor} 切片、行动记录在 {@code sd} 切片；单条命令只能落一个命名空间。本工具走
 * {@link CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link GovSelectExamineesPlan#plan}（不碰 {@link
 * ToolContext}/{@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。来源瀑布复用 {@link
 * HouseholdManpowerAllocator#allocateMalesOfAdult} 同一份，不另写第二份。
 *
 * <p>★★ <b>批（P1.5 固定顺序，一条 revision）</b>：{@code social.SubmitHouseholdWorkOrder}（{@code
 * orderId=gov-select-examinees:<batchId>:<newUnitId>}；{@code CREATE_HOUSEHOLD(hh-unit:<newUnitId>, UNIT) + 逐来源
 * TRANSFER_MEMBERS}）→ {@code unit.CreateUnit}（households=[新家户]、equipment=[]、speed=4、mobilityPerMille=800；<b>无
 * manpower</b>）→（目的 GOV 给了且不同格）{@code unit.PlanRoute} → {@code economy.RegisterHousehold}（新家户经济行）→
 * {@code actor.EnsureHouseholdAccount}（新家户零余额账户）→ {@code sd.PutInfo}（key={@value #INFO_KEY}）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 决策人要科举仍走 {@code sd.IssueDirective} / GM 代执行这条链——本工具就是 GM 的 “代执行手”。
 *
 * <p>★ <b>资源声明</b>：写 {@code social}/{@code unit}/{@code economy}/{@code actor}/{@code sd} 五个命名空间（GM 侧五面
 * unlimited ⇒ 逐条判通过）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / 来源不是 GOV / 无管辖 / 来源不足 / 目标不是 GOV / 不可达 / 新 id 已存在 / 新家户 id 被占用
 * ⇒ {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code REJECTED} 带逐条真拒因；
 * 提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovSelectExamineesTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.selectExaminees";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 来源 GOV canonical）。 */
  public static final String INFO_KEY = "selectExaminees";

  /** 本工具写 social / unit / economy / actor / sd 五个命名空间（GM 侧五面 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest SELECT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #SELECT_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令的命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /** 历法/气候服务：四个 gov 写工具按它取时钟（生产路径 = CalendarService.load；旧路径 = 全缺省）。 */
  private final CalendarService calendarService;

  // ★ 测试/旧路径：全缺省时钟，不读 store；生产 Shell 必须用带 CalendarService 的重载（CalendarService.load）。
  public GovSelectExamineesTool(
      CoreSimos core, QueryService query, String initiator, String mapId) {
    this(core, query, initiator, mapId, CalendarService.defaults());
  }

  /** 生产构造器：历法时钟来自启动期 {@link CalendarService#load} 的同一实例。 */
  public GovSelectExamineesTool(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      CalendarService calendarService) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 科举选人（组合工具，一批 = 一条 revision）：从来源 GOV 辖区的 Social 家户份额抽 MALE+ADULT（share-aware，"
        + "同一 lot 可被多户持有）落成新人口家户 hh-unit:<newUnitId>，同批建无标签纯人员单位（unit.households=[该家户]；"
        + "★ P1.5：不再发 unit.CreateUnit(manpower=...)，也不再发 social.SeedGroups），并补齐该家户的 economy 经济行与 actor 零余额账户，"
        + "可给目的 GOV 规划一条 unit.PlanRoute，最后落 sd.PutInfo 行动记录。"
        + "参数 {unitId(必填, 带 GovernmentFormation 的来源 GOV), count(必填 ≥ 1), targetGovUnitId?(目的 GOV), "
        + "role?(行动记录角色标签，缺省 EXAMINEE), newUnitId?(可选；缺省确定性生成), reason(必填), "
        + "preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "来源：按来源 GOV jurisdiction 的 Region 键序逐个用 HouseholdManpowerAllocator 的 MALE+ADULT 家户份额瀑布分配到满额；"
        + "不足 ⇒ 整条拒（带 requested/available/缺口），不部分抽取、不截断。"
        + "apply 批（固定顺序）：social.SubmitHouseholdWorkOrder（orderId=gov-select-examinees:<batchId>:<newUnitId>，"
        + "target=hh-unit:<newUnitId>，plan=CREATE_HOUSEHOLD + 逐来源 TRANSFER_MEMBERS）→ unit.CreateUnit"
        + "（id/name/position/households=[新家户]/equipment=[]/speed=4/mobilityPerMille=800；无 manpower）→"
        + "（目的 GOV 给了且不同格）unit.PlanRoute（waypoints=A* 逐格路径）→ economy.RegisterHousehold"
        + "（household=hh-unit:<newUnitId>）→ actor.EnsureHouseholdAccount（household=hh-unit:<newUnitId>）→ sd.PutInfo"
        + "（地址=来源 GOV canonical、key="
        + INFO_KEY
        + "、value=JSON 字符串）。守恒：Σ来源 share.taken == count == 新人口家户的成员增量。"
        + "返回 {preview, submitted, tick, unitId, newUnitId, householdId, population, residence, count, role, "
        + "targetGovUnitId, at, available, route, sources, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSupport.prop("string", "来源：带 GovernmentFormation 的省级 GOV 单位 id"));
    props.put(
        "count",
        ToolSupport.prop(
            "integer",
            "选送人数（≥ 1）；只抽 MALE 且当前 tick 成年档的 Social 家户份额，落成 hh-unit:<newUnitId> 的成员"));
    props.put("targetGovUnitId", ToolSupport.prop("string", "目的 GOV 单位 id（可选；给了就规划从来源到它的路线）"));
    props.put("role", ToolSupport.prop("string", "行动记录里的角色标签（可选；缺省 EXAMINEE；不落单位字段）"));
    props.put(
        "newUnitId",
        ToolSupport.prop(
            "string", "新纯人员单位 id（可选；缺省用 exam-<来源GOV>-<tick>-<count> 确定性生成；其人口家户为 hh-unit:<id>）"));
    props.put("reason", ToolSupport.prop("string", "选送原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
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
    return SELECT_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "科举选人 unitId="
            + args.get("unitId")
            + " count="
            + args.get("count")
            + " targetGovUnitId="
            + args.get("targetGovUnitId")
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
      Optional<String> targetGovUnitId = optionalText(args, "targetGovUnitId");
      Optional<String> role = optionalText(args, "role");
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
      GovSelectExamineesPlan.Plan plan =
          GovSelectExamineesPlan.plan(
              state, unitId, count, targetGovUnitId, role, newUnitId, calendarService.clock());
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
          "TOOL_ERROR", "科举选人失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 可选文本参数：缺席 ⇒ empty；给了但空白/类型错 ⇒ 具名拒。 */
  private static Optional<String> optionalText(Map<String, Object> args, String name) {
    return ToolSupport.has(args, name)
        ? Optional.of(ToolSupport.requiredText(args, name))
        : Optional.empty();
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  private ToolResult apply(
      GovSelectExamineesPlan.Plan plan, String reason, BranchId branch, long expectedRevision) {
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
   * 组批（P1.5 固定顺序）：{@code social.SubmitHouseholdWorkOrder} → {@code unit.CreateUnit} →
   * （有路线）{@code unit.PlanRoute} → {@code economy.RegisterHousehold} → {@code actor.EnsureHouseholdAccount} →
   * {@code sd.PutInfo}。
   */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      GovSelectExamineesPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(6);
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovSelectExamineesPlan.SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
            plan.submitHouseholdWorkOrderPayloadJson(batchId, reason)));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovSelectExamineesPlan.CREATE_UNIT_TYPE,
            plan.createUnitPayloadJson()));
    if (plan.route().isPresent()) {
      batch.add(
          envelope(
              batchId,
              branch,
              expectedRevision,
              GovSelectExamineesPlan.PLAN_ROUTE_TYPE,
              plan.planRoutePayloadJson()));
    }
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovSelectExamineesPlan.REGISTER_HOUSEHOLD_TYPE,
            plan.registerHouseholdPayloadJson(reason)));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovSelectExamineesPlan.ENSURE_HOUSEHOLD_ACCOUNT_TYPE,
            plan.ensureHouseholdAccountPayloadJson(reason)));
    batch.add(
        envelope(
            batchId,
            branch,
            expectedRevision,
            GovSelectExamineesPlan.PUT_INFO_TYPE,
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
  private static String infoPayload(GovSelectExamineesPlan.Plan plan, String reason) {
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
      GovSelectExamineesPlan.Plan plan, String reason, boolean preview, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("tick", plan.tick());
    view.put("unitId", plan.unitId());
    view.put("newUnitId", plan.newUnitId());
    view.put("householdId", plan.householdId());
    view.put("population", plan.count());
    view.put("residence", plan.residence().value());
    view.put("count", plan.count());
    view.put("role", plan.role());
    view.put("targetGovUnitId", plan.targetGovUnitId().orElse(null));
    view.put("at", ToolSupport.hexCoord(plan.at()));
    view.put("available", plan.available());
    view.put("route", plan.routeView());
    view.put("sources", plan.sourcesView());
    view.put("commands", plan.commandTypes());
    view.put("infoText", plan.infoNote(reason));
    return view;
  }
}
