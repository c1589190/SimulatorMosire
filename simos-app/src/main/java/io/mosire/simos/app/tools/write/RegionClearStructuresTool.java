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
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.region.clearStructures}（P1b2，2026-10-01 后端 + MCP 稳定化计划）：<b>GM-only 区域结构清空组合工具</b>
 * ——按 regionId 清"生成器创建的结构"：省/中央 GOV 单位、对应 GOV 决策人、建议命名形制且为目标 Region hex 真子集的省 Region； <b>不碰</b>
 * social/actor/economy 数值与目标 Region 本身。
 *
 * <p>★★ <b>与 {@code simos.region.clearData} 分开</b>：数据清空与结构清空是两个独立动作、各自 preview/apply、各自确认；
 * 本工具只组批结构命令，不替调用方做"一键清光"。
 *
 * <p>★★ <b>一批 = 一条 revision，固定批序</b>：{@code sd.DeleteDecisionMaker × N → unit.DisbandUnit × N →
 * map.DeleteRegion × N → sd.PutInfo}；所有信封共享同一 {@code batchId}（correlationId）、branch 与
 * expectedRevision ⇒ {@link CoreSimos#submitBatch} 原子落一条 revision。preview 一个字节都不写。
 *
 * <p>★★ <b>preview / apply 共用同一份只读 pre-scan</b>：唯一语义落点在 {@link
 * RegionClearStructuresPlan#scan}；本类只做四件事——参数形状解析、读 base state、把 Plan 折成视图、组批与折叠结局。
 *
 * <p>★★ <b>候选识别（详见 Plan 类注）</b>：GOV 单位按"带 GovFormation + 当刻有效位置在目标 Region hex 集"；决策人按 Gov 归属且
 * govUnit 在候选单位集；Region 只自动认 {@code sanitize(regionId) + "__P" + 两位以上数字} 且真子集的省， 其余相交 Region 只列
 * preview + warning，必须显式 {@code regionIds} 才删。显式清单里不满足自动规则的项仍按显式执行， 但会在 warning 里逐条说明。
 *
 * <p>★★ <b>批内域拒不做前置预演</b>：{@code unit.DisbandUnit} 的"仍有下属 / 仍在命令链"约束由域层在批内真判；本工具 pre-scan 只对这两种形状给
 * warning。批内任一命令被拒 ⇒ 整批零 revision、{@code REJECTED} 带逐条真拒因。
 *
 * <p>★ <b>资源声明</b>：map / unit / sd 三个命名空间全部 {@link ResourcePolicy#UNRESTRICTED}（GM 侧 Unlimited）；
 * 固定断言三个命名空间的粗资源（与既有 GM 组合工具同制）。
 *
 * <p>★ <b>失败具名</b>：参数缺失/类型错/region 不存在/显式 id 不存在/显式 Region = 目标本身/显式非 Gov 决策人 ⇒ {@code
 * BAD_REQUEST}（零 revision）；批内域拒 ⇒ {@code REJECTED}（逐条真拒因）；提交冲突 ⇒ {@code CONFLICT}（真实 head）；资源不匹配 ⇒
 * 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class RegionClearStructuresTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.region.clearStructures";

  /** 本工具写 map / unit / sd 三个命名空间（GM 侧三面 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest CLEAR_STRUCTURES_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #CLEAR_STRUCTURES_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命名空间序：sd → unit → map）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.MAP_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;
  private final String mapId;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与 apply 共用同一份扫描）
   * @param initiator 落盘时的发起者（{@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（只用于把命中日志写进 sd INFO 地址）
   */
  public RegionClearStructuresTool(
      CoreSimos core, QueryService query, String initiator, String mapId) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 区域结构清空（组合工具，一批 = 一条 revision）：按 regionId 只读 pre-scan 生成器创建的结构——"
        + "带 GovFormation 且当刻有效位置在目标 Region hex 集内的 GOV 单位、这些 GOV 的 Gov 归属决策人、以及"
        + " id = sanitize(regionId)+\"__P\"+两位以上数字 且 hexes 是目标 hex 真子集的省 Region；"
        + "其它相交 Region 只在 overlappingRegions/warnings 里列出，不自动删。"
        + "固定批序 sd.DeleteDecisionMaker × N → unit.DisbandUnit × N → map.DeleteRegion × N → sd.PutInfo；"
        + "只对有候选项且开关打开的类别下单；全部无命令 ⇒ 不 submit、零 revision，返回\"没有需要清空的结构\"。"
        + "参数 {regionId(必填，必须在当前 map.regions()), regionIds?(显式删除的 Region 数组，必须存在且 != 目标),"
        + " unitIds?(显式删除的 Unit 数组，必须存在), decisionMakerIds?(显式删除的 DM 数组，必须存在且是 Gov 归属),"
        + " deleteRegions?(缺省 true), deleteUnits?(缺省 true), deleteDecisionMakers?(缺省 true),"
        + " preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填，>=0), reason(必填非空白)}。"
        + "返回 {preview, submitted, regionId, regionName, hexCount, tick, candidates(逐类 count/ids/sample/autoIds/explicitIds/enabled),"
        + " overlappingRegions(id/name/tag/overlapHexCount), switches, commands, warnings, conflictPreflight, reason, infoText}；"
        + "apply 另加 submission；无命令另加 message。"
        + "失败语义：参数/显式 id 不合法 ⇒ BAD_REQUEST（零 revision）；批内域拒 ⇒ REJECTED（逐条真拒因）；"
        + "冲突 ⇒ CONFLICT（真实 head）；资源不匹配 ⇒ 原样抛资源拒因。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("regionId", ToolSupport.prop("string", "目标 Region id（必填；必须在当前 map.regions() 里）"));
    props.put(
        "regionIds", ToolSupport.prop("array", "显式要删的 Region id 数组（可选；每个都必须存在且不得等于目标 Region；缺省空）"));
    props.put("unitIds", ToolSupport.prop("array", "显式要删的 Unit id 数组（可选；每个都必须存在；缺省空）"));
    props.put(
        "decisionMakerIds", ToolSupport.prop("array", "显式要删的决策人 id 数组（可选；每个都必须存在且是 Gov 归属；缺省空）"));
    props.put("deleteRegions", ToolSupport.prop("boolean", "是否下单删除候选 Region（缺省 true）"));
    props.put("deleteUnits", ToolSupport.prop("boolean", "是否下单解散候选单位（缺省 true）"));
    props.put("deleteDecisionMakers", ToolSupport.prop("boolean", "是否下单删除候选决策人（缺省 true）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision（>=0）；preview 的读数也取它（缺省=该分支 head）"));
    props.put("reason", ToolSupport.prop("string", "清空原因（必填非空白；进 sd.PutInfo 行动记录与工具结果）"));
    return ToolSupport.schema(props, List.of("regionId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（走审批门链）、可外发——与其余 GM 写工具同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return CLEAR_STRUCTURES_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "Region 结构清空 regionId="
            + args.get("regionId")
            + " preview="
            + args.getOrDefault("preview", true)
            + " deleteDecisionMakers="
            + args.getOrDefault("deleteDecisionMakers", true)
            + " deleteUnits="
            + args.getOrDefault("deleteUnits", true)
            + " deleteRegions="
            + args.getOrDefault("deleteRegions", true)
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
      String regionId = ToolSupport.requiredText(args, "regionId");
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
      List<String> explicitRegionIds = optionalTextArray(args, "regionIds");
      List<String> explicitUnitIds = optionalTextArray(args, "unitIds");
      List<String> explicitDecisionMakerIds = optionalTextArray(args, "decisionMakerIds");
      boolean deleteRegions = ToolSupport.optionalBoolean(args, "deleteRegions").orElse(true);
      boolean deleteUnits = ToolSupport.optionalBoolean(args, "deleteUnits").orElse(true);
      boolean deleteDecisionMakers =
          ToolSupport.optionalBoolean(args, "deleteDecisionMakers").orElse(true);
      // ★ preview / apply 的共同输入：同一坐标上的 base state + 同一份只读扫描。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      Map<String, Object> conflictPreflight = conflictPreflight(branch, expectedRevisionArg);
      RegionClearStructuresPlan.Plan plan =
          RegionClearStructuresPlan.scan(
              state,
              regionId,
              explicitRegionIds,
              explicitUnitIds,
              explicitDecisionMakerIds,
              deleteRegions,
              deleteUnits,
              deleteDecisionMakers);
      if (!plan.hasWork()) {
        Map<String, Object> view = planView(plan, reason, preview, false, conflictPreflight);
        view.put("message", plan.hasCandidates() ? "候选结构均被 delete* 开关关闭，本批不下单" : "没有需要清空的结构");
        return ToolSupport.ok(view);
      }
      if (preview) {
        return ToolSupport.ok(planView(plan, reason, true, false, conflictPreflight));
      }
      return apply(plan, reason, branch, expectedRevision, conflictPreflight);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与其余写工具同一条：资源拒因必须原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而非"换个资源就行"）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "Region 结构清空失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 冲突预检（只读 head，不写） ───────────────────────────────────────────────────────

  /**
   * 乐观并发的 preview 预检：报出 {@code expectedRevision} 与该分支真实 head 是否一致。apply 的冲突判定仍由 {@code submitBatch}
   * 在提交锁内做，并回报真实 head——本预检只是让 preview 先把风险说清楚。
   */
  private Map<String, Object> conflictPreflight(BranchId branch, Long expectedRevision) {
    Optional<RevisionId> head = core.head(branch);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", branch.value());
    view.put("expectedRevision", expectedRevision);
    view.put("headRevision", head.map(RevisionId::value).orElse(null));
    boolean headMatchesExpected;
    if (expectedRevision == null) {
      // preview 没给 expectedRevision ⇒ 读数就是 head，不存在"提交基准已过期"的预检风险。
      headMatchesExpected = true;
    } else {
      long expected = expectedRevision;
      headMatchesExpected = head.map(rev -> rev.value() == expected).orElse(false);
    }
    view.put("headMatchesExpected", headMatchesExpected);
    view.put("wouldConflict", !headMatchesExpected);
    return view;
  }

  // ── apply：组批 + 折叠 ───────────────────────────────────────────────────────────────

  /** 提交阶段：按 Plan 组批（固定顺序），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      RegionClearStructuresPlan.Plan plan,
      String reason,
      BranchId branch,
      long expectedRevision,
      Map<String, Object> conflictPreflight) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch =
        buildBatch(batchId, plan, reason, branch, new RevisionId(expectedRevision));
    BatchResult result = core.submitBatch(batch);
    Map<String, Object> view = planView(plan, reason, false, true, conflictPreflight);
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
    // ★ 整批拒：逐条把真拒因摆出来（批是原子的，一条修复不了就全体不生效），不吞成一句"提交失败"。
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

  /** 组批：类型序由 Plan 给出；本方法按同一序为每类消耗对应 id，最后一条恒为 {@code sd.PutInfo}。 */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      RegionClearStructuresPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(plan.commands().size());
    Iterator<String> unitIds = plan.units().ids().iterator();
    Iterator<String> decisionMakerIds = plan.decisionMakers().ids().iterator();
    Iterator<String> regionIds = plan.regions().ids().iterator();
    for (String type : plan.commands()) {
      String payload;
      if (RegionClearStructuresPlan.DELETE_DECISION_MAKER_TYPE.equals(type)) {
        payload = ToolSupport.json(Map.of("decisionMakerId", decisionMakerIds.next()));
      } else if (RegionClearStructuresPlan.DISBAND_UNIT_TYPE.equals(type)) {
        payload = ToolSupport.json(Map.of("id", unitIds.next()));
      } else if (RegionClearStructuresPlan.DELETE_REGION_TYPE.equals(type)) {
        payload = ToolSupport.json(Map.of("regionId", regionIds.next()));
      } else if (RegionClearStructuresPlan.PUT_INFO_TYPE.equals(type)) {
        payload = infoPayload(plan, reason);
      } else {
        // ★ Plan 只产四种类型；出现第五种 = 实现漂移，当场炸而不是静默组一条空载荷。
        throw new IllegalStateException("clearStructures 计划含未知批内命令类型（实现漂移）: " + type);
      }
      batch.add(envelope(batchId, branch, expectedRevision, type, payload));
    }
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

  /**
   * {@code sd.PutInfo} 载荷：目标 Region canonical 地址（{@code map:<mapId>:region.<regionId>}，只用 Address
   * AST 造） + {@code key="regionClearStructures"} + {@code value}=JSON 字符串（regionId + 各类删除清单 +
   * reason） + {@code note}=人可读摘要 + {@code tick}=base state 的世界当前日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一 Region 的后续清空自然追加序号。
   */
  private String infoPayload(RegionClearStructuresPlan.Plan plan, String reason) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("regionId", plan.region().id().value());
    value.put("decisionMakers", plan.decisionMakers().ids());
    value.put("units", plan.units().ids());
    value.put("regions", plan.regions().ids());
    value.put("reason", reason);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", regionAddress(plan.region().id().value()));
    payload.put("key", RegionClearStructuresPlan.INFO_KEY);
    payload.put("value", ToolSupport.json(value));
    payload.put("note", infoNote(plan, reason));
    payload.put("tick", plan.tick());
    return ToolSupport.json(payload);
  }

  /** 目标 Region 的 canonical 地址：{@code map:<mapId>:region.<regionId>}（Address AST，名字里的特殊字符由它按需加引）。 */
  private String regionAddress(String regionId) {
    Address address =
        new Address(
            List.of(
                new Namespace(ToolSupport.MAP_NAMESPACE),
                Entity.of(mapId),
                Entity.of("region", regionId)));
    return address.canonical();
  }

  /** 行动记录 / 工具结果共用的人可读摘要。 */
  private static String infoNote(RegionClearStructuresPlan.Plan plan, String reason) {
    return "Region 结构清空 region="
        + plan.region().id().value()
        + " decisionMakers="
        + plan.decisionMakers().count()
        + " units="
        + plan.units().count()
        + " regions="
        + plan.regions().count()
        + " commands="
        + plan.commands()
        + " reason="
        + reason;
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      RegionClearStructuresPlan.Plan plan,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> conflictPreflight) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("regionId", plan.region().id().value());
    view.put("regionName", plan.region().name());
    view.put("hexCount", plan.region().hexes().size());
    view.put("tick", plan.tick());
    view.put("candidates", plan.candidatesView());
    view.put("overlappingRegions", plan.overlappingRegionsView());
    view.put("switches", plan.switchesView());
    view.put("commands", plan.commands());
    view.put("warnings", plan.warnings());
    view.put("conflictPreflight", conflictPreflight);
    view.put("reason", reason);
    view.put("infoText", infoNote(plan, reason));
    return view;
  }

  /** 可选字符串数组：缺省 ⇒ 空列表；给了必须是字符串数组（元素非空白，保序去重）。 */
  private static List<String> optionalTextArray(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是字符串数组");
    }
    LinkedHashSet<String> values = new LinkedHashSet<>();
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的元素必须是非空白字符串");
      }
      values.add(text);
    }
    return List.copyOf(values);
  }
}
