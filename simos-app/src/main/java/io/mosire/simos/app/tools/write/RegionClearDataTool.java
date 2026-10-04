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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ {@code simos.region.clearData}（P1b1，2026-10-01 后端 + MCP 稳定化计划）：<b>GM-only 区域数据清空组合工具</b> ——按
 * regionId 清 social / actor / economy 三类数值与关联记录，<b>不动</b> Region/Unit/GOV/决策人结构。
 *
 * <p>★★ <b>一批 = 一条 revision，固定批序</b>：{@code social.ClearRegion} → {@code actor.ClearRegion} →
 * {@code economy.ClearRegion} → {@code sd.PutInfo}；四个信封共享同一 {@code batchId}（correlationId）与同一
 * branch/expectedRevision ⇒ {@link CoreSimos#submitBatch} 原子落一条 revision。preview 一个字节都不写。
 *
 * <p>★★ <b>preview / apply 共用同一份只读 pre-scan</b>：唯一语义落点在 {@link RegionClearPlan#scan}（复用 {@code
 * RegionSeedPlan.inspectCleanGate} 的同一次命中扫描）；本类只做四件事——参数形状解析、读 base state、把 Plan 折成视图、组批与折叠结局。
 *
 * <p>★★ <b>只对"有命中"的域下单</b>：某域 clean gate 干净 ⇒ 该域的 ClearRegion 不进批；三域都无命中 ⇒ <b>不 submit、零
 * revision</b>，返回具名"没有需要清空的数据"。{@code sd.PutInfo} 只在有任一域命中时追加，记录本次清空的范围与原因。
 *
 * <p>★ <b>与结构清空分开</b>：{@code
 * simos.region.clearStructures}（Region/Unit/GOV/决策人结构）另行单独确认；本工具不做跨域结构清空。
 *
 * <p>★★ <b>资源声明</b>：social / actor / economy / sd 四个命名空间全部 {@link ResourcePolicy#UNRESTRICTED}（GM 侧
 * Unlimited）；map 只读用于校验 region 存在，不声明写权限。
 *
 * <p>★ <b>失败具名</b>：参数缺失/类型错/region 不存在 ⇒ {@code BAD_REQUEST}（零 revision）；批内域拒 ⇒ {@code REJECTED}
 * 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class RegionClearDataTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.region.clearData";

  /** 本工具写四个命名空间（GM 侧四面 unlimited ⇒ 逐条判通过）；map 只读、不在此列。 */
  private static final ResourceManifest CLEAR_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /** 与 {@link #CLEAR_WRITE} 同源的逐命名空间粗断言（顺序 = 批内命令命名空间序：social → actor → economy → sd）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));

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
  public RegionClearDataTool(CoreSimos core, QueryService query, String initiator, String mapId) {
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
    return "GM 区域数据清空（组合工具，一批 = 一条 revision）：按 regionId 只读 pre-scan（与 simos.region.seed 的 clean"
        + " gate 同一次扫描），只对命中域按固定批序 social.ClearRegion → actor.ClearRegion → economy.ClearRegion →"
        + " sd.PutInfo 下单；三域都无命中 ⇒ 不提交、零 revision，返回\"没有需要清空的数据\"。"
        + "清空边界：social 清目标 Region 格集内的 populations/groups/cities（含 city.region 归属命中）；actor 清目标格内的"
        + " GoodsAccount，并只删除清账后在任何位置都不再持有账户的主体；economy 至少清目标格 industries/markets 及其"
        + " unit/relations/classes/memberships 等可靠可定位的连带记录（不动世界级发行审计/在途货物/laborSupply）。"
        + "本工具只清数据，不动 Region/Unit/GOV/决策人结构（结构清空请单独调用 simos.region.clearStructures）。"
        + "参数 {regionId(必填，必须在当前 map.regions() 里), preview?(缺省 true=只算不写), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填，>=0；preview 的读数也取它，缺省=该分支 head), reason(必填非空白)}。"
        + "返回 {preview, submitted, regionId, regionName, hexCount, domains(逐域 clean gate 形状), hitCounts, commands,"
        + " conflictPreflight, reason, infoText}；apply 另加 submission；无命中另加 message。"
        + "失败语义：参数/region 不存在 ⇒ BAD_REQUEST（零 revision）；批内域拒 ⇒ REJECTED（逐条真拒因）；"
        + "冲突 ⇒ CONFLICT（真实 head）；资源不匹配 ⇒ 原样抛资源拒因。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("regionId", ToolSupport.prop("string", "目标 Region id（必填；必须在当前 map.regions() 里）"));
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
    return CLEAR_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "Region 数据清空 regionId="
            + args.get("regionId")
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
      String reason = ToolSupport.requiredText(args, "reason");
      String regionId = ToolSupport.requiredText(args, "regionId");
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
      // ★ preview / apply 的共同输入：同一坐标上的 base state + 同一份只读扫描。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      Map<String, Object> conflictPreflight = conflictPreflight(branch, expectedRevisionArg);
      RegionClearPlan.Plan plan = RegionClearPlan.scan(state, regionId);
      if (!plan.hasWork()) {
        Map<String, Object> view = planView(plan, reason, preview, false, conflictPreflight);
        view.put("message", "没有需要清空的数据");
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
          "TOOL_ERROR", "Region 数据清空失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
      RegionClearPlan.Plan plan,
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

  /** 组批（固定顺序，可复现）：只含命中域的 ClearRegion，最后必有 sd.PutInfo（{@code hasWork()} 为真时）。 */
  private List<CommandEnvelope> buildBatch(
      String batchId,
      RegionClearPlan.Plan plan,
      String reason,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(plan.commands().size());
    for (String type : plan.commands()) {
      String payload =
          RegionClearPlan.PUT_INFO_TYPE.equals(type)
              ? infoPayload(plan, reason)
              : ToolSupport.json(Map.of("regionId", plan.region().id().value()));
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
   * AST 造） + {@code key="regionClearData"} + {@code value}=JSON 字符串 + {@code note}=人可读摘要 + {@code
   * tick}=base state 的世界日。
   *
   * <p>★ {@code id} 不显式给：由 {@code sd.PutInfo} 按"该地址下的第 n 条"合成 ⇒ 同一 Region 的后续清空自然追加序号。
   */
  private String infoPayload(RegionClearPlan.Plan plan, String reason) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("regionId", plan.region().id().value());
    value.put("reason", reason);
    value.put("commands", plan.commands());
    value.put("hitCounts", plan.hitCounts());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", regionAddress(plan.region().id().value()));
    payload.put("key", RegionClearPlan.INFO_KEY);
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
  private static String infoNote(RegionClearPlan.Plan plan, String reason) {
    return "Region 数据清空 region="
        + plan.region().id().value()
        + " social="
        + plan.social().hitCount()
        + " actor="
        + plan.actor().hitCount()
        + " economy="
        + plan.economy().hitCount()
        + " commands="
        + plan.commands()
        + " reason="
        + reason;
  }

  // ── 视图 ────────────────────────────────────────────────────────────────────────────

  /** preview 与 apply 共用的结果视图（{@code preview}/{@code submitted} 指这一次调用的形态）。 */
  private static Map<String, Object> planView(
      RegionClearPlan.Plan plan,
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
    view.put("domains", plan.domainsView());
    view.put("hitCounts", plan.hitCounts());
    view.put("commands", plan.commands());
    view.put("conflictPreflight", conflictPreflight);
    view.put("reason", reason);
    view.put("infoText", infoNote(plan, reason));
    return view;
  }
}
