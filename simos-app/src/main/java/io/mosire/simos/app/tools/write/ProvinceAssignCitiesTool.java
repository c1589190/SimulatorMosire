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
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
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
 * ★★ {@code simos.province.assignCities}（R2a，行政区划修复计划 §1.2/§R4）：<b>GM-only 组合工具</b>——按每座 {@code
 * SocialCity} 的落点 {@code at} 落在哪个"省/首都区" Region，批量把 {@code city.region} 改成该 Region；一批 {@code
 * social.UpdateCity} 共享同一 batchId / branch / expectedRevision ⇒ 恰一条 revision（原子）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：唯一语义落点是 {@link ProvinceAssignCitiesPlan#derive}（不碰 {@link
 * ToolContext} / {@code CoreSimos}）；本类只做资源断言、参数形状解析、读 base state、把推导折成视图、组批、折叠结局。
 *
 * <p>★★ <b>候选与选择</b>（详见 {@link ProvinceAssignCitiesPlan}）：包含城 {@code at} 的 Region 中，id 含 {@code
 * "__P"} 或以 {@code "__CAP"} 结尾者为候选，且排除带 {@code nation:} tag 的国家 Region 自身；多重候选取 {@code hexCount}
 * 最小、并列按 regionId 字典序。无候选 ⇒ 保持原 region（不猜、不编）；目标与当前相同 ⇒ 不改。输出按 cityId 字典序。
 *
 * <p>★★ <b>{@code regionId?} 父 Region 过滤</b>：候选 id 以 {@code <regionId>__} 开头，或城市当前 {@code region ==
 * regionId} 且候选包含其 {@code at}；父 Region 自身永远不会是候选。
 *
 * <p>★ <b>只改 region</b>：每条命令的载荷只有 {@code {id, region}}，{@code name}/{@code props} 原样保留（不是整城覆盖）。
 * {@code reason} 必填，进本工具结果与审批摘要；本工具<b>不</b>带 {@code sd.PutInfo} 审计（所以资源只声明 social 写）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；工具名不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。{@code UpdateCityHandler.targetPaths} 仍为空 ⇒ 决策令路径继续 fail-closed，GM 路径不受影响。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / {@code regionId} 不在当前地图 / {@code expectedRevision} 形态错 / {@code
 * preview=false} 缺 expectedRevision ⇒ {@code BAD_REQUEST}（零 revision）；批内域拒（城市消失、region 串非法等） ⇒
 * {@code REJECTED} 带逐条真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head；资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}（由唯一入口折资源拒因）。
 *
 * <p>★ <b>{@code changedCities == 0}</b> ⇒ 不组批、不提交，返回 {@code submitted:false}、 {@code
 * changedCities:0}（零 revision）。本工具是给既有/新建世界补归省用的；R5 重建时在 {@code province.apply} 之后 显式调用它。
 */
public final class ProvinceAssignCitiesTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.province.assignCities";

  /** 本工具只写 social 命名空间（{@link ResourcePolicy#UNRESTRICTED}，GM 侧 social 是 unlimited）。 */
  private static final ResourceManifest ASSIGN_CITIES_WRITE =
      ResourceManifest.of(ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 与 {@link #ASSIGN_CITIES_WRITE} 同源的逐命名空间粗断言。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  /** 每条命令固定的类型（与 {@link SocialUpdateCityTool#NAME} 同一个拼写点）。 */
  private static final String UPDATE_CITY_TYPE = ProvinceAssignCitiesPlan.UPDATE_CITY_TYPE;

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}；preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前 {@link SimulationState}；preview 与同一份推导共用它）
   * @param initiator 落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）
   */
  public ProvinceAssignCitiesTool(CoreSimos core, QueryService query, String initiator) {
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
    return "GM 批量城市归省（组合工具，一批 = 一条 revision）：按 SocialCity.at 落在哪个\"省/首都区\" Region "
        + "重设 SocialCity.region（只改 region，name/props 原样保留）。参数 {regionId?(可选父 Region 过滤：只处理 "
        + "id 以 <regionId>__ 开头的省/首都区，或当前 region==regionId 且落在候选区内的城市), preview?(缺省 true), "
        + "branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填), reason(必填)}。候选：包含 at 且 id 含 __P 或 id 以 __CAP "
        + "结尾的 Region（排除 nation: Region 自身）；多个候选取 hexCount 最小、并列按 regionId 字典序；无候选保持原 "
        + "region。返回 {preview, submitted, regionId, totalCities, changedCities, "
        + "cities[{id,name,at,fromRegion,toRegion}], commandsPreview, reason, submission?}；"
        + "preview=false 时 changedCities==0 不提交（submitted:false）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "regionId",
        ToolSupport.prop(
            "string",
            "可选父 Region id（如国家 Region）；给了就只处理 id 以 <regionId>__ 开头的省/首都区候选，"
                + "或当前 region==regionId 且落在候选区内的城市"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交同一份改动"));
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
    return ASSIGN_CITIES_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "批量城市归省 regionId="
            + args.get("regionId")
            + " preview="
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
      Optional<RegionId> regionId = parseRegionId(args, map);
      ProvinceAssignCitiesPlan.Derivation derived =
          ProvinceAssignCitiesPlan.derive(
              map, social, new ProvinceAssignCitiesPlan.Params(regionId));
      if (preview) {
        return ToolSupport.ok(view(derived, regionId, reason, true, false, null));
      }
      if (derived.assignments().isEmpty()) {
        return ToolSupport.ok(view(derived, regionId, reason, false, false, null));
      }
      return apply(derived, regionId, reason, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与既有写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "批量城市归省失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** {@code regionId?}：缺省 = 不过滤；给了必须非空白且在当前 map 的 regions 里（否则 BAD_REQUEST，零 revision）。 */
  private static Optional<RegionId> parseRegionId(Map<String, Object> args, GameMap map) {
    String raw = ToolSupport.optionalText(args, "regionId", null);
    if (raw == null) {
      return Optional.empty();
    }
    RegionId regionId = RegionId.parse(raw);
    if (!map.regions().containsKey(regionId)) {
      throw new IllegalArgumentException("参数 regionId 不在当前地图 regions() 里: " + raw);
    }
    return Optional.of(regionId);
  }

  /** 提交阶段：按推导组批（每条改动一条 social.UpdateCity），走唯一批量写入口，把三结局折进同一份视图。 */
  private ToolResult apply(
      ProvinceAssignCitiesPlan.Derivation derived,
      Optional<RegionId> regionId,
      String reason,
      BranchId branch,
      long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch =
        buildBatch(derived.assignments(), batchId, branch, new RevisionId(expectedRevision));
    BatchResult result = core.submitBatch(batch);
    if (result instanceof BatchResult.Committed committed) {
      Map<String, Object> submission = ToolSupport.committedView(committed.ref(), batchId, batchId);
      return ToolSupport.ok(view(derived, regionId, reason, false, true, submission));
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      return ToolResult.error(
          "CONFLICT", ToolSupport.json(view(derived, regionId, reason, false, false, submission)));
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
    submission.put("commands", List.copyOf(rows));
    return ToolResult.error(
        "REJECTED", ToolSupport.json(view(derived, regionId, reason, false, false, submission)));
  }

  /**
   * 组批：按 cityId 序每条一条 {@code social.UpdateCity}；载荷只给 {@code {id, region}}（name/props 不动）。 共享
   * batchId / branch / expectedRevision ⇒ 一批一条 revision。
   */
  private List<CommandEnvelope> buildBatch(
      List<ProvinceAssignCitiesPlan.Assignment> assignments,
      String batchId,
      BranchId branch,
      RevisionId expectedRevision) {
    List<CommandEnvelope> batch = new ArrayList<>(assignments.size());
    for (ProvinceAssignCitiesPlan.Assignment assignment : assignments) {
      batch.add(
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              batchId,
              initiator,
              branch,
              expectedRevision,
              UPDATE_CITY_TYPE,
              ToolSupport.json(assignment.payloadView())));
    }
    return List.copyOf(batch);
  }

  /** preview / apply 共用的结果视图（{@code submission} 为 null 时不落该键）。 */
  private static Map<String, Object> view(
      ProvinceAssignCitiesPlan.Derivation derived,
      Optional<RegionId> regionId,
      String reason,
      boolean preview,
      boolean submitted,
      Map<String, Object> submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("regionId", regionId.map(RegionId::value).orElse(null));
    view.put("totalCities", derived.totalCities());
    view.put("changedCities", derived.assignments().size());
    List<Map<String, Object>> cities = new ArrayList<>(derived.assignments().size());
    for (ProvinceAssignCitiesPlan.Assignment assignment : derived.assignments()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", assignment.cityId().value());
      row.put("name", assignment.cityName());
      row.put("at", ToolSupport.hexCoord(assignment.at()));
      row.put("fromRegion", assignment.fromRegion());
      row.put("toRegion", assignment.toRegion());
      cities.add(row);
    }
    view.put("cities", List.copyOf(cities));
    view.put("commandsPreview", commandsPreview(derived.assignments()));
    view.put("reason", reason);
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }

  /** 每人/每城一条的预览：type + cityId + 裸载荷 + payloadJson（与 {@code buildBatch} 同源）。 */
  private static List<Map<String, Object>> commandsPreview(
      List<ProvinceAssignCitiesPlan.Assignment> assignments) {
    List<Map<String, Object>> out = new ArrayList<>(assignments.size());
    for (ProvinceAssignCitiesPlan.Assignment assignment : assignments) {
      Map<String, Object> payload = assignment.payloadView();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", UPDATE_CITY_TYPE);
      row.put("cityId", assignment.cityId().value());
      row.put("payload", payload);
      row.put("payloadJson", ToolSupport.json(payload));
      out.add(row);
    }
    return List.copyOf(out);
  }
}
