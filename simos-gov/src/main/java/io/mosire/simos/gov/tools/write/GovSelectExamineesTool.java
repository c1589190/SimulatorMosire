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
import io.mosire.simos.gov.tools.GovToolReads;
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
 * ★★ {@code simos.gov.selectExaminees}（阶段 13A 人员流转；P1.5 接完整 Social 工单路径）：<b>GM 组合工具</b>—— 从来源 GOV
 * 辖区的 Social 家户份额选人 + 同批建<b>无标签纯人员单位</b>（可给目的 GOV 规划路线）+ 补家户经济行/账户 + 留行动记录，一批落一条 revision。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：人在 {@code social} 切片、新单位/路线在 {@code unit} 切片、家户经济行在 {@code
 * economy} 切片、家户账户在 {@code actor} 切片、行动记录在 {@code sd} 切片；单条命令只能落一个命名空间。本工具走 {@link
 * CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link GovSelectExamineesPlan#plan}（不碰 {@link
 * ToolContext}/{@code CoreSimos}）；本类只做四件事——读态、把 Plan 折成视图、组批、折叠结局。来源瀑布复用 {@link
 * GovToolManpower#allocateMalesOfAdult} 同一份，不另写第二份。
 *
 * <p>★★ <b>批（P1.5 固定顺序，一条 revision）</b>：{@code social.SubmitHouseholdWorkOrder}（{@code
 * orderId=gov-select-examinees:<batchId>:<newUnitId>}；{@code CREATE_HOUSEHOLD(hh-unit:<newUnitId>,
 * UNIT) + 逐来源 TRANSFER_MEMBERS}）→ {@code
 * unit.CreateUnit}（households=[新家户]、equipment=[]、speed=4、mobilityPerMille=800；<b>无
 * manpower</b>）→（目的 GOV 给了且不同格）{@code unit.PlanRoute} → {@code economy.RegisterHousehold}（新家户经济行）→
 * {@code actor.EnsureHouseholdAccount}（新家户零余额账户）→ {@code ga.PutInfo}（key={@value #INFO_KEY}）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；名字不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。★ 决策人要科举仍走 {@code sd.IssueDirective} / GM 代执行这条链——本工具就是 GM 的 “代执行手”。
 *
 * <p>★ <b>资源声明</b>：写 {@code social}/{@code unit}/{@code economy}/{@code actor}/{@code sd} 五个命名空间（GM
 * 侧五面 unlimited ⇒ 逐条判通过）。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / 来源不是 GOV / 无管辖 / 来源不足 / 目标不是 GOV / 不可达 / 新 id 已存在 / 新家户 id 被占用 ⇒
 * {@link IllegalArgumentException} 折 {@code BAD_REQUEST}（零 revision）；批内域层拒 ⇒ {@code REJECTED}
 * 带逐条真拒因； 提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovSelectExamineesTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.selectExaminees";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 来源 GOV canonical）。 */
  public static final String INFO_KEY = "selectExaminees";

  /** 本工具写 social / unit / economy / actor / sd 五个命名空间（GM 侧五面 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest SELECT_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #SELECT_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令的命名空间序）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolResources.ECONOMY_NAMESPACE, "*"),
          ResourceId.of(ToolResources.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolResources.SD_NAMESPACE, "*"));

  private final GovToolSupport support;

  public GovSelectExamineesTool(GovToolSupport support, String mapId) {
    this.support = Objects.requireNonNull(support, "support");
    Objects.requireNonNull(mapId, "mapId");
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
        + "可给目的 GOV 规划一条 unit.PlanRoute，最后落 ga.PutInfo 行动记录。"
        + "参数 {unitId(必填, 带 GovernmentFormation 的来源 GOV), count(必填 ≥ 1), targetGovUnitId?(目的 GOV), "
        + "role?(行动记录角色标签，缺省 EXAMINEE), newUnitId?(可选；缺省确定性生成), reason(必填), "
        + "preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision(preview=false 时必填)}。"
        + "来源：按来源 GOV jurisdiction 的 Region 键序逐个用 HouseholdManpowerAllocator 的 MALE+ADULT 家户份额瀑布分配到满额；"
        + "不足 ⇒ 整条拒（带 requested/available/缺口），不部分抽取、不截断。"
        + "apply 批（固定顺序）：social.SubmitHouseholdWorkOrder（orderId=gov-select-examinees:<batchId>:<newUnitId>，"
        + "target=hh-unit:<newUnitId>，plan=CREATE_HOUSEHOLD + 逐来源 TRANSFER_MEMBERS）→ unit.CreateUnit"
        + "（id/name/position/households=[新家户]/equipment=[]/speed=4/mobilityPerMille=800；无 manpower）→"
        + "（目的 GOV 给了且不同格）unit.PlanRoute（waypoints=A* 逐格路径）→ economy.RegisterHousehold"
        + "（household=hh-unit:<newUnitId>）→ actor.EnsureHouseholdAccount（household=hh-unit:<newUnitId>）→ ga.PutInfo"
        + "（地址=来源 GOV canonical、key="
        + INFO_KEY
        + "、value=JSON 字符串）。守恒：Σ来源 share.taken == count == 新人口家户的成员增量。"
        + "返回 {preview, submitted, tick, unitId, newUnitId, householdId, population, residence, count, role, "
        + "targetGovUnitId, at, available, route, sources, commands, infoText}；apply 另加 submission。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("unitId", ToolSchema.prop("string", "来源：带 GovernmentFormation 的省级 GOV 单位 id"));
    props.put(
        "count",
        ToolSchema.prop(
            "integer", "选送人数（≥ 1）；只抽 MALE 且当前 tick 成年档的 Social 家户份额，落成 hh-unit:<newUnitId> 的成员"));
    props.put("targetGovUnitId", ToolSchema.prop("string", "目的 GOV 单位 id（可选；给了就规划从来源到它的路线）"));
    props.put("role", ToolSchema.prop("string", "行动记录里的角色标签（可选；缺省 EXAMINEE；不落单位字段）"));
    props.put(
        "newUnitId",
        ToolSchema.prop(
            "string", "新纯人员单位 id（可选；缺省用 exam-<来源GOV>-<tick>-<count> 确定性生成；其人口家户为 hh-unit:<id>）"));
    props.put("reason", ToolSchema.prop("string", "选送原因（必填非空白；进 ga.PutInfo 行动记录与工具结果）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批命令"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("unitId", "count", "reason"));
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
      long count = ToolSchema.requiredLong(args, "count");
      String reason = ToolSchema.requiredText(args, "reason");
      Optional<String> targetGovUnitId = optionalText(args, "targetGovUnitId");
      Optional<String> role = optionalText(args, "role");
      Optional<String> newUnitId = optionalText(args, "newUnitId");
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
      GovSelectExamineesPlan.Plan plan =
          GovSelectExamineesPlan.plan(
              support, state, unitId, count, targetGovUnitId, role, newUnitId);
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
          "TOOL_ERROR", "科举选人失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 可选文本参数：缺席 ⇒ empty；给了但空白/类型错 ⇒ 具名拒。 */
  private static Optional<String> optionalText(Map<String, Object> args, String name) {
    return ToolSchema.has(args, name)
        ? Optional.of(ToolSchema.requiredText(args, name))
        : Optional.empty();
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  private ToolResult apply(
      GovSelectExamineesPlan.Plan plan,
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
   * 组批（P1.5 固定顺序）：{@code social.SubmitHouseholdWorkOrder} → {@code unit.CreateUnit} → （有路线）{@code
   * unit.PlanRoute} → {@code economy.RegisterHousehold} → {@code actor.EnsureHouseholdAccount} →
   * {@code ga.PutInfo}。
   */
  private List<ToolWritePort.Request> buildBatch(
      String batchId, GovSelectExamineesPlan.Plan plan, String reason) {
    List<ToolWritePort.Request> batch = new ArrayList<>(6);
    batch.add(
        GovToolSupport.request(
            GovSelectExamineesPlan.SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE,
            plan.submitHouseholdWorkOrderPayloadJson(batchId, reason)));
    batch.add(
        GovToolSupport.request(
            GovSelectExamineesPlan.CREATE_UNIT_TYPE, plan.createUnitPayloadJson()));
    if (plan.route().isPresent()) {
      batch.add(
          GovToolSupport.request(
              GovSelectExamineesPlan.PLAN_ROUTE_TYPE, plan.planRoutePayloadJson()));
    }
    batch.add(
        GovToolSupport.request(
            support.commandTypes().registerHousehold(), plan.registerHouseholdPayloadJson(reason)));
    batch.add(
        GovToolSupport.request(
            support.commandTypes().ensureHouseholdAccount(),
            plan.ensureHouseholdAccountPayloadJson(reason)));
    batch.add(GovToolSupport.request(support.commandTypes().putInfo(), infoPayload(plan, reason)));
    // ★ 保序冻结在赋值处（本仓口径：不用 List.copyOf）。
    return java.util.Collections.unmodifiableList(batch);
  }

  /** {@code ga.PutInfo} 载荷：来源 GOV canonical 地址 + key + value JSON 字符串 + note + 当前 tick。 */
  private static String infoPayload(GovSelectExamineesPlan.Plan plan, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", GovCreateOfficePlan.unitAddress(plan.unitId()));
    payload.put("key", INFO_KEY);
    payload.put("value", plan.infoValueJson(reason));
    payload.put("note", plan.infoNote(reason));
    payload.put("tick", plan.tick());
    return ToolResults.json(payload);
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  private Map<String, Object> planView(
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
    view.put("at", GovToolReads.hexCoord(plan.at()));
    view.put("available", plan.available());
    view.put("route", plan.routeView());
    view.put("sources", plan.sourcesView());
    view.put("commands", plan.commandTypes(support.commandTypes()));
    view.put("infoText", plan.infoNote(reason));
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
