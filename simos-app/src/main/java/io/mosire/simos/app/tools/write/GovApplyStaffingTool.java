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
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.gov.GovDemand;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ {@code simos.gov.applyStaffing}（R4 / R5 步骤 4）：<b>GM-only 组合工具</b>——逐 GOV 按辖区行政需求精确配满编。
 *
 * <p>★★ <b>需求的唯一来源是 {@link GovDemand}</b>：本工具只做"逐格需求求和 + 组批"，<b>不重写公式</b>—— {@code
 * GovDemand.of(GameMap, SocialData, Unit)} 的 {@link GovDemand.HexDemand#security()} / {@link
 * GovDemand.HexDemand#paperwork()} 逐格求和得 {@code securityDemand}/{@code paperworkDemand}。
 *
 * <p>★★ <b>目标编制</b>：{@code YAMEN = securityDemand}、{@code SCRIBE = paperworkDemand}、{@code POST} 不设
 * （= 0）；两维都为 0 ⇒ 空 staff map（不写 0 键）；当前 {@code GovernmentFormation.staff()} 与目标相等 ⇒ 该 GOV 不改。 每个要改的 GOV
 * 折一条 {@code unit.SetGovFormation}：载荷带全 {@code level}、{@code superiorGov?}（有才给）、 {@code staff} 与
 * <b>当前 policy 的五个字段</b>（{@code grainPerStaffPerTick}/{@code clothPerStaffPerCycle}/{@code
 * moneyPerStaffPerTick}/{@code retirementPerStaff}/{@code staffCap}）——同类型是整体 替换，缺 policy 会回落
 * defaults、缺 staffCap 会静默丢上限，故必须逐字段原样带全。
 *
 * <p>★★ <b>preview / apply 共用同一份推导</b>：preview=true 只读 base state，一个字节都不写；preview=false 把同一批
 * 改动组批（共享 batchId / branch / expectedRevision）走 {@link CoreSimos#submitBatch} ⇒ 恰一条 revision；
 * {@code changedGovs == 0} ⇒ 不提交（{@code submitted:false}，零 revision）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}；它提交的 {@code unit.SetGovFormation} 是既有命令（本工具不新增命令）。
 *
 * <p>★ <b>资源声明</b>：读 map / social，写 unit（{@link ResourcePolicy#UNRESTRICTED}；GM 侧 unit unlimited）。
 * 实际断言只对 {@code unit:*} 取 {@link Operation#WRITE}——map/social 的 {@code READ_ONLY} 是声明的读面。
 *
 * <p>★ <b>不做的事</b>：不招募人（本工具只改编制人数，人员从哪来仍由 R5 的 {@code recruit} 流程承担）、不改税率/管辖/ 上级；不需要 {@code
 * GovEfficiency}（只读公式，不改 GOV 状态）。
 *
 * <p>★ <b>失败具名</b>：参数缺失/类型错 / {@code expectedRevision} 为负 / {@code preview=false} 缺
 * expectedRevision ⇒ {@code BAD_REQUEST}（零 revision）；批内域拒（单位消失、superiorGov 变化等）⇒ {@code REJECTED}
 * 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}。
 */
public final class GovApplyStaffingTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.applyStaffing";

  /** 本工具提交的唯一命令类型（引用窄工具的字面量，不在本类另抄一份）。 */
  private static final String COMMAND_TYPE = UnitSetGovernmentFormationTool.NAME;

  /**
   * 本工具声明的资源面：map / social 只读、unit 可写（GM 侧 unit unlimited）。
   *
   * <p>★ 读面声明与实际断言拆开是既有口径：{@code execute} 里按用户给定只对 {@code unit:*} 取 WRITE 断言（GM 侧 unlimited ⇒
   * 放行），map/social 的 READ_ONLY 由工具的 {@link #resources()} 声明。
   */
  private static final ResourceManifest APPLY_STAFFING_MANIFEST =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 写断言：unit 命名空间整片（GM 侧 unlimited；决策人桶没有本工具）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（apply 走 {@code submitBatch}；preview 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与 apply 共用同一份推导）
   * @param initiator 落盘时的发起者（{@code <kind>:<id>} 形态）
   */
  public GovApplyStaffingTool(CoreSimos core, QueryService query, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 按辖区行政需求给所有 GOV 精确配满编（组合工具，一批 = 一条 revision）：逐 GOV 调 GovDemand.of 求"
        + " security/paperwork 总量，目标 staff = {YAMEN: securityDemand, SCRIBE: paperworkDemand}（两维都为 0 ⇒ 空表；"
        + "POST 不设）；当前 staff 与目标相等则不改。参数 {preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填), reason(必填非空白)}。返回 {preview, submitted, totalGovs,"
        + " changedGovs, govs[{unitId, level, superiorGov, jurisdictionRegionIds, securityDemand, paperworkDemand,"
        + " currentStaff, targetStaff, changed}], commandsPreview[{type, payloadJson}], reason, submission?}；"
        + "govs 按 unitId 字典序；apply 时每个要改的 GOV 一条 unit.SetGovFormation（policy 五字段与 staffCap 原样带全），"
        + "shared batchId/branch/expectedRevision；changedGovs==0 ⇒ 不提交（submitted:false）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "preview",
        ToolSupport.prop("boolean", "true（缺省）= 只读预览，一个字节都不写；false = 提交同一批 SetGovFormation"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    props.put("reason", ToolSupport.prop("string", "操作原因（必填非空白；进工具结果与审批摘要）"));
    return ToolSupport.schema(props, List.of("reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余组合写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return APPLY_STAFFING_MANIFEST;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "按行政需求配满编 preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + " expected="
            + args.get("expectedRevision")
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String reason = ToolSupport.requiredText(args, "reason");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      GameMap map = ToolSupport.gameMap(state);
      SocialData social = ToolSupport.socialData(state);
      UnitState units = ToolSupport.unitState(state);
      List<GovAssessment> assessments = derive(map, social, units);
      if (preview) {
        return ToolSupport.ok(view(assessments, reason, true, false, null));
      }
      List<GovAssessment> changed = changedOnly(assessments);
      if (changed.isEmpty()) {
        return ToolSupport.ok(view(assessments, reason, false, false, null));
      }
      return apply(assessments, changed, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "按行政需求配满编失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 推导（preview / apply 共用；纯读，不碰 core）──────────────────────────────────────

  /**
   * ★ 唯一推导入口：扫描所有 {@link Unit}，按 {@code unit.id().value()} 字典序只处理带 {@link GovernmentFormation} 的单位； 逐 GOV
   * 用 {@link GovDemand#of} 求和并算出目标 staff 与是否要改。不改任何状态。
   */
  private static List<GovAssessment> derive(GameMap map, SocialData social, UnitState units) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    List<Unit> govs = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation) {
        govs.add(unit);
      }
    }
    govs.sort(Comparator.comparing(unit -> unit.id().value()));
    List<GovAssessment> assessments = new ArrayList<>(govs.size());
    for (Unit unit : govs) {
      GovernmentFormation formation = (GovernmentFormation) unit.module().orElseThrow();
      Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);
      long securityDemand = 0L;
      long paperworkDemand = 0L;
      for (GovDemand.HexDemand hexDemand : demand.values()) {
        securityDemand += hexDemand.security();
        paperworkDemand += hexDemand.paperwork();
      }
      Map<StaffRole, Long> targetStaff = targetStaff(securityDemand, paperworkDemand);
      boolean changed = !formation.staff().equals(targetStaff);
      Map<String, Object> payload = commandPayload(unit.id(), formation, targetStaff);
      assessments.add(
          new GovAssessment(
              unit.id().value(),
              formation,
              jurisdictionRegionIds(unit),
              securityDemand,
              paperworkDemand,
              targetStaff,
              changed,
              payload));
    }
    return List.copyOf(assessments);
  }

  /**
   * 目标编制：{@code YAMEN = securityDemand}、{@code SCRIBE = paperworkDemand}、{@code POST} 不设（= 0）；两维都为
   * 0 ⇒ 空表（不写 0 键）。
   */
  private static Map<StaffRole, Long> targetStaff(long securityDemand, long paperworkDemand) {
    if (securityDemand == 0L && paperworkDemand == 0L) {
      return Map.of();
    }
    Map<StaffRole, Long> target = new LinkedHashMap<>();
    target.put(StaffRole.YAMEN, securityDemand);
    target.put(StaffRole.SCRIBE, paperworkDemand);
    return target;
  }

  /** 要改的那些（保持 unitId 字典序）。 */
  private static List<GovAssessment> changedOnly(List<GovAssessment> assessments) {
    List<GovAssessment> changed = new ArrayList<>();
    for (GovAssessment assessment : assessments) {
      if (assessment.changed()) {
        changed.add(assessment);
      }
    }
    return List.copyOf(changed);
  }

  /**
   * 管辖 Region id 表：取 {@code jurisdiction().taxRatePerMilleByRegion().keySet()} 的保序值；缺 jurisdiction
   * ⇒ 空表。
   */
  private static List<String> jurisdictionRegionIds(Unit unit) {
    return unit.jurisdiction()
        .map(
            jurisdiction -> {
              List<String> ids = new ArrayList<>();
              for (RegionId regionId : jurisdiction.taxRatePerMilleByRegion().keySet()) {
                ids.add(regionId.value());
              }
              return List.copyOf(ids);
            })
        .orElseGet(List::of);
  }

  /**
   * 一条 {@code unit.SetGovFormation} 的载荷：{@code level} 用当前层级名、{@code superiorGov} 有才给、{@code staff}
   * 是目标表、{@code policy} 是当前政策<b>五字段全带</b>（同类型整体替换，缺项会回落 defaults / 丢 staffCap）。
   */
  private static Map<String, Object> commandPayload(
      UnitId unitId, GovernmentFormation formation, Map<StaffRole, Long> targetStaff) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unitId.value());
    payload.put("level", formation.level().name());
    formation
        .superiorGov()
        .ifPresent(superiorGov -> payload.put("superiorGov", superiorGov.value()));
    payload.put("staff", staffView(targetStaff));
    payload.put("policy", policyView(formation.policy()));
    return payload;
  }

  /** 政策视图：五个字段一个不少（{@code staffCap} 空表也要出现为 {@code {}}）。 */
  private static Map<String, Object> policyView(OfficePolicy policy) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("grainPerStaffPerTick", policy.grainPerStaffPerTick());
    view.put("clothPerStaffPerCycle", policy.clothPerStaffPerCycle());
    view.put("moneyPerStaffPerTick", policy.moneyPerStaffPerTick());
    view.put("retirementPerStaff", policy.retirementPerStaff());
    view.put("staffCap", staffView(policy.staffCap()));
    return view;
  }

  /** 编制表视图：按 {@link StaffRole} 词表序输出在场角色（含计数为 0 的原有角色；目标空表 ⇒ {@code {}}）。 */
  private static Map<String, Object> staffView(Map<StaffRole, Long> staff) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (StaffRole role : StaffRole.values()) {
      Long count = staff.get(role);
      if (count != null) {
        view.put(role.name(), count);
      }
    }
    return view;
  }

  // ── 提交与结果视图 ──────────────────────────────────────────────────────────────────

  /** 提交阶段：按推导组批（每个要改的 GOV 一条 {@code unit.SetGovFormation}），走唯一批量写入口，折叠三结局。 */
  private ToolResult apply(
      List<GovAssessment> assessments,
      List<GovAssessment> changed,
      String reason,
      BranchId branch,
      long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch = new ArrayList<>(changed.size());
    for (GovAssessment assessment : changed) {
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              initiator,
              branch,
              new RevisionId(expectedRevision),
              COMMAND_TYPE,
              ToolSupport.json(assessment.payload())));
    }
    BatchResult result = core.submitBatch(batch);
    if (result instanceof BatchResult.Committed committed) {
      Map<String, Object> submission = ToolSupport.committedView(committed.ref(), batchId, batchId);
      return ToolSupport.ok(view(assessments, reason, false, true, submission));
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      return ToolResult.error(
          "CONFLICT", ToolSupport.json(view(assessments, reason, false, false, submission)));
    }
    // ★ 整批拒：逐条把**真拒因**摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<CommandOutcome> outcomes = rejected.outcomes();
    List<Map<String, Object>> rows = new ArrayList<>();
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
    submission.put("commands", List.copyOf(rows));
    return ToolResult.error(
        "REJECTED", ToolSupport.json(view(assessments, reason, false, false, submission)));
  }

  /** preview / apply 共用的结果视图（{@code submission} 为 null 时不落该键）。 */
  private static Map<String, Object> view(
      List<GovAssessment> assessments,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> submission) {
    int changedGovs = 0;
    for (GovAssessment assessment : assessments) {
      if (assessment.changed()) {
        changedGovs++;
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("totalGovs", assessments.size());
    view.put("changedGovs", changedGovs);
    List<Map<String, Object>> govs = new ArrayList<>(assessments.size());
    for (GovAssessment assessment : assessments) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("unitId", assessment.unitId());
      row.put("level", assessment.formation().level().name());
      row.put("superiorGov", assessment.formation().superiorGov().map(UnitId::value).orElse(null));
      row.put("jurisdictionRegionIds", assessment.jurisdictionRegionIds());
      row.put("securityDemand", assessment.securityDemand());
      row.put("paperworkDemand", assessment.paperworkDemand());
      row.put("currentStaff", staffView(assessment.formation().staff()));
      row.put("targetStaff", staffView(assessment.targetStaff()));
      row.put("changed", assessment.changed());
      govs.add(row);
    }
    view.put("govs", List.copyOf(govs));
    List<Map<String, Object>> commandsPreview = new ArrayList<>(changedGovs);
    for (GovAssessment assessment : assessments) {
      if (!assessment.changed()) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", COMMAND_TYPE);
      row.put("payloadJson", ToolSupport.json(assessment.payload()));
      commandsPreview.add(row);
    }
    view.put("commandsPreview", List.copyOf(commandsPreview));
    view.put("reason", reason);
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }

  /**
   * 一个 GOV 的推导结果（纯数据；preview / apply 共用）。
   *
   * @param unitId 单位 id 裸值（输出序 = unitId 字典序）
   * @param formation 当前编制（currentStaff / level / superiorGov / policy 的来源）
   * @param securityDemand 逐格 {@code HexDemand.security} 之和
   * @param paperworkDemand 逐格 {@code HexDemand.paperwork} 之和
   * @param targetStaff 目标编制（{@code YAMEN}/{@code SCRIBE}；两维为 0 ⇒ 空表）
   * @param changed 当前 staff 与目标是否不等
   * @param payload 要改时提交的 {@code unit.SetGovFormation} 载荷
   */
  private record GovAssessment(
      String unitId,
      GovernmentFormation formation,
      List<String> jurisdictionRegionIds,
      long securityDemand,
      long paperworkDemand,
      Map<StaffRole, Long> targetStaff,
      boolean changed,
      Map<String, Object> payload) {

    GovAssessment {
      jurisdictionRegionIds = List.copyOf(jurisdictionRegionIds);
      // ★ 不用 Map.copyOf：它不承诺迭代序（同一状态两次调用可能给出不同 payloadJson 字节）。保序冻结。
      targetStaff = Collections.unmodifiableMap(new LinkedHashMap<>(targetStaff));
      payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
  }
}
