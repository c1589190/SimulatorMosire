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
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.spi.EconomyGovUnitUpserts;
import io.mosire.simos.economy.spi.EconomyUpsertGovUnitHandler;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.economy.upsertGovUnit}（Z1c）：GM 行政服务生产 unit 创建/补齐窄工具</b> —— {@code
 * economy.UpsertGovUnit} 的预览 / 提交入口（照 {@code simos.economy.upsertIndustry} 的 GM 经济写工具先例）。
 *
 * <p>★★ <b>载荷逐字透传</b>：{@code payloadJson} 与命令 handler 的载荷同形；工具不做第二套字段翻译，直接原样交给 {@link
 * EconomyGovUnitUpserts#project(EconomyData, SocialData, String)} 预览、原样装进 {@link CommandEnvelope}
 * 提交（ADR-1 R11：Core 逐字节转交）。
 *
 * <ul>
 *   <li>{@code preview=true}（缺省）：读当前状态跑同一个纯函数，返回三表前后差异 + changeSetEmpty；<b>不提交、不写状态</b>；
 *   <li>{@code preview=false}：同一条载荷走 {@link CoreSimos#submit}（一条命令 = 一条 revision）；空变更集直接返回 {@code
 *       noop}（不落空 revision）。
 * </ul>
 *
 * <p>★★ <b>unit 切片预检（控制方 2026-10-23 裁定 A，preview 与 apply 两条路径都做）</b>：economy handler 因模块边界（economy
 * 禁依赖 simos-unit）看不见 {@code Unit}/{@code GovernmentFormation}；app 是组合根，本工具在调用语义纯函数之后、返回预览/提交之前，对
 * {@code state.module("unit")} 预检：unit 必须存在且 {@code Unit.module()} 是 {@link
 * GovernmentFormation}。不满足 ⇒ {@code REJECTED}，零提交。★ 残余边界：GM 裸 {@code simos.command.submit}
 * 绕过本预检（已在设计书 §18 / Z1c 台账记名，Z4/Z6 必做项）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有本工具，{@code
 * DecisionCallerFactory.WHITELIST} 也不含它；命令本身标了 {@code GmOnlyCommand} ⇒ 令 / {@code RegisterEffect} /
 * 决策人 catalog 三条路径同样排除。
 */
public final class EconomyUpsertGovUnitTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.economy.upsertGovUnit";

  /** 本工具只写 economy 命名空间（GM 侧 economy 未受限 ⇒ 逐条判通过）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(Map.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前状态；预览与提交取同一个坐标）
   * @param initiator 落盘时的发起者
   */
  public EconomyUpsertGovUnitTool(CoreSimos core, QueryService query, String initiator) {
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
    return "GM 为 GOV 单位创建/补齐行政服务生产 unit（economy.UpsertGovUnit 的窄封装；一条命令 = 一条 revision）。"
        + "payloadJson：{govUnitId, industryId(base kind 必须=office，office/office_v2…),"
        + " assets({AssetKind:数量}，至少覆盖 recipe capacityPerUnit 的 1 单位规模),"
        + " modeKey?(缺省 gov_service), reason}。operator=HOUSEHOLD:hh-gov-<govUnitId>，"
        + "unitId=ProductionUnitId.idOf(industryId, operator)；一次写 units + assetShares(OWNED 补足) +"
        + " relations(空规则/residualOwner=operator)。同载荷重放 = 幂等 noop；已存在字段冲突 ⇒ 具名拒，不静默覆盖。"
        + "守卫：economy 已激活、GOV 已在 economy.RegisterGovernment 登记、hh-gov 在 Social 家户表且经济侧有"
        + " HouseholdEconomy 行、industry 存在且 base kind=office、格已激活、assets 覆盖规模；"
        + "工具另在 preview/apply 前预检 unit 切片存在且带 GovernmentFormation。preview=true（缺省）只算差异、不写；"
        + "preview=false 提交同一条 payloadJson。★ 只在 GM 桶，决策人不可调用。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "payloadJson",
        ToolSupport.prop(
            "string",
            "命令载荷 JSON 文本（逐字透传给 economy.UpsertGovUnit）："
                + "{govUnitId, industryId(<kind>@<q>_<r>；base kind 必须=office),"
                + " assets({AssetKind:数量}，逐值≥0 且逐 capacityPerUnit kind ≥ 1 单位规模),"
                + " modeKey?(缺省 gov_service), reason}"));
    props.put(
        "preview",
        ToolSupport.prop("boolean", "true（缺省）= 只算前后差异、不写；false = 提交 economy.UpsertGovUnit"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("payloadJson"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ECONOMY_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        name()
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String payloadJson = ToolSupport.requiredText(args, "payloadJson");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        throw new IllegalArgumentException("expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        throw new IllegalArgumentException(
            "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      // ★ preview / apply 的共同输入：同一个坐标上的完整状态 + 同一条 payloadJson。
      SimulationState state =
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      EconomyData economy = ToolSupport.economyData(state);
      SocialData social = ToolSupport.socialData(state);
      EconomyGovUnitUpserts.Projection projection =
          EconomyGovUnitUpserts.project(economy, social, payloadJson);
      // ★ 控制方裁定 A：preview 与 apply 都做 unit 切片预检；不满足 ⇒ REJECTED、零提交。
      requireGovUnitFormation(state, projection.govUnitId());
      Map<String, Object> view = diffView(projection, payloadJson);
      if (preview) {
        view.put("preview", true);
        view.put("submitted", false);
        return ToolSupport.ok(view);
      }
      if (projection.noop()) {
        // ★ 逐值相同的幂等重放：不提交空变更（一个 revision 都不落）。
        view.put("preview", false);
        view.put("submitted", false);
        view.put("noop", true);
        view.put("noopReason", "逐值相同的幂等重放（changeSet 为空）");
        return ToolSupport.ok(view);
      }
      return commit(view, payloadJson, branch, expectedRevision);
    } catch (EconomyGovUnitUpserts.Rejection e) {
      // ★ 业务拒绝（GOV 未登记/非 office/assets 不足或超出/字段冲突/unit 预检失败）：具名 REJECTED，零 revision。
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      // ★ 载荷形状/字段值/坏 id 等：BAD_REQUEST，不提交半笔。
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与 EconomyUpsertIndustryTool / AbstractNarrowWriteTool 同一条：资源拒因原样逃到 ToolCallAuthorizer 边界。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR",
          "GOV 生产 unit upsert 工具失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * unit 切片预检（app 组合根专有；economy handler 因模块边界查不到）：unit 存在且 {@code module()} 是 {@link
   * GovernmentFormation}。不满足抛 {@link EconomyGovUnitUpserts.Rejection} ⇒ 工具折 {@code REJECTED}。
   */
  private static void requireGovUnitFormation(SimulationState state, String govUnitId) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "state 里没有 unit 切片（装配故障：GOV 生产 unit 要判 GovernmentFormation）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    Unit unit = unitSnapshot.state().units().get(UnitId.parse(govUnitId));
    if (unit == null) {
      throw new EconomyGovUnitUpserts.Rejection("GOV 单位不存在: " + govUnitId);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new EconomyGovUnitUpserts.Rejection(
          "unit 不是 GOV 编制单位（缺 GovernmentFormation）: " + govUnitId);
    }
  }

  /** 提交阶段：同一条 payloadJson 原样装信封、走唯一写入口，把三结局与预览差异拼在一份结果里。 */
  private ToolResult commit(
      Map<String, Object> view, String payloadJson, BranchId branch, long expectedRevision) {
    String commandId = UUID.randomUUID().toString();
    CommandEnvelope envelope =
        new CommandEnvelope(
            commandId,
            commandId,
            initiator,
            branch,
            new RevisionId(expectedRevision),
            EconomyUpsertGovUnitHandler.TYPE,
            payloadJson);
    CommandResult result = core.submit(envelope);
    view.put("preview", false);
    view.put("submitted", true);
    view.put("commandId", commandId);
    return switch (result) {
      case CommandResult.Committed committed -> {
        view.put("submission", ToolSupport.committedView(committed.ref(), commandId, commandId));
        yield ToolSupport.ok(view);
      }
      case CommandResult.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolSupport.stateRef(conflict.current()));
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolSupport.json(view));
      }
      case CommandResult.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolSupport.json(view));
      }
    };
  }

  /** 预览/结果共同部分：身份口径、三表写入标志、逐 kind 资产前后、变更集是否为空、待提交命令（type + payloadJson 原样）。 */
  private static Map<String, Object> diffView(
      EconomyGovUnitUpserts.Projection projection, String payloadJson) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("govUnitId", projection.govUnitId());
    view.put("household", projection.household().value());
    view.put("operator", actorView(projection.operator()));
    view.put("industryId", projection.industryId().value());
    view.put("unitId", projection.unitId().value());
    view.put("modeKey", projection.modeKey());
    view.put("unitCreated", projection.unitCreated());
    view.put("relationCreated", projection.relationCreated());
    view.put("sharesAdded", projection.sharesCreated().size());
    view.put("createdShareIds", shareIds(projection.sharesCreated()));
    view.put("assetsBefore", assetAmounts(projection.assetTotalsBefore()));
    view.put("assetsAfter", assetAmounts(projection.assetTotalsAfter()));
    view.put("noop", projection.noop());
    view.put("changeSetEmpty", projection.noop());
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", EconomyUpsertGovUnitHandler.TYPE);
    command.put("payloadJson", payloadJson);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  private static Map<String, Object> actorView(io.mosire.simos.actor.api.actor.ActorRef actor) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("kind", actor.kind().name());
    view.put("id", actor.id());
    return view;
  }

  private static List<String> shareIds(List<AssetShareId> ids) {
    List<String> values = new ArrayList<>(ids.size());
    for (AssetShareId id : ids) {
      values.add(id.value());
    }
    return List.copyOf(values);
  }

  private static Map<String, Object> assetAmounts(Map<AssetKind, Long> amounts) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : amounts.entrySet()) {
      view.put(entry.getKey().name(), entry.getValue());
    }
    return view;
  }
}
