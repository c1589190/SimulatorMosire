package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.gov.spi.GovAdministrationProjections;
import io.mosire.simos.gov.spi.SetAdministrationPlanHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.setEstablishment}（Z3c-2）：行政编制计划设置工具（GM + 该 GOV 决策人）</b> —— {@code
 * gov.SetAdministrationPlan} 的窄封装（preview / apply、payloadJson 逐字透传、expectedRevision）。
 *
 * <p>★★ <b>两面并列、门禁不同</b>：GM 桶调用走 {@code GmAutoApproveGate}（直接批准）；决策人桶调用走 {@code AutoApproveGate →
 * ConfirmGate → PendingApprovals}（需 GM 在审批面点头）。身份派生与越权拒见 {@link GovToolSupport}：决策人只能操作自己所属
 * GOV；载荷若带别的 {@code govUnitId} ⇒ 具名 {@code REJECTED}。
 *
 * <p>★★ <b>命令仍是 GM-only 标记（控制方 2026-10-23 裁定）</b>：{@code gov.SetAdministrationPlan} 实现 {@code
 * GmOnlyCommand}，只影响 Shell 派生的令 / {@code RegisterEffect} / 决策人 catalog 三条路径；命令总线不认该标记，
 * 因此决策人窄工具直接提交同一命令是受控的（审批链承担门禁）。标记不删 ⇒ 权限面不放大。
 *
 * <p>★ <b>preview 与 apply 同源</b>：两者都读同一坐标的 base 状态、跑 {@link GovAdministrationProjections}（与 handler
 * 共用 {@code GovPayloads} 解析与 GOV 单位守卫），preview 不写状态、不发"已应用"日志；逐值相同的重放 = noop（不落空 revision）。
 */
public final class GovSetEstablishmentTool implements AgentTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.setEstablishment}）。 */
  public static final String NAME = "simos.gov.setEstablishment";

  /** 本工具提交的唯一命令类型（与 handler 的 TYPE 同一个拼写点）。 */
  private static final String COMMAND_TYPE = SetAdministrationPlanHandler.TYPE;

  /**
   * 资源声明：只声明 unit 命名空间（本工具的资源断言锚点 = 目标 GOV 单位）。
   *
   * <p>★ 决策人桶的 {@code GovScope} 显式表态 unit 面（自己 + 直辖区单位），因此"自己单位"必过、别的 GOV 必拒；GM 的 unit 面是 {@code
   * unlimited}。
   */
  private static final ResourceManifest GOV_WRITE =
      ResourceManifest.of(ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的 base 状态；preview 与提交取同一坐标）
   * @param initiator 落盘时的发起者
   */
  public GovSetEstablishmentTool(CoreSimos core, QueryService query, String initiator) {
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
    return "设置一个 GOV 的行政编制计划（gov.SetAdministrationPlan 窄封装）。payloadJson："
        + "{unitId(GM 必填；决策人可省=自己的 GOV，若给出必须等于自己的 GOV), securityPlannedLaborMilli?,"
        + " paperworkPlannedLaborMilli?, postTiers?(恰 3 档 [{tierId,securityWeightPerMille,"
        + "paperworkWeightPerMille}]), securitySupplyStaticModifierPerMille?, paperworkSupplyStaticModifierPerMille?,"
        + " securityDemandStaticModifierPerMille?, paperworkDemandStaticModifierPerMille?,"
        + " supernumerarySqrtCoefficient?(k)}；缺省展开一次（计划 0 / 默认 3 档 / 修正 1000‰ / k=1）。"
        + "GM 调用需显式 unitId；决策人调用身份派生、只能自己的 GOV（越权 GOV 具名拒，且必须过 GM 审批）。"
        + "preview=true（缺省）只算前后差异、不写；preview=false 必须给 expectedRevision，逐值相同的重放 = noop。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "payloadJson",
        ToolSupport.prop(
            "string",
            "编制计划 JSON 文本（逐字透传给 gov.SetAdministrationPlan）：{unitId,securityPlannedLaborMilli?,"
                + "paperworkPlannedLaborMilli?,postTiers?[{tierId,securityWeightPerMille,paperworkWeightPerMille}],"
                + "securitySupplyStaticModifierPerMille?,paperworkSupplyStaticModifierPerMille?,"
                + "securityDemandStaticModifierPerMille?,paperworkDemandStaticModifierPerMille?,"
                + "supernumerarySqrtCoefficient?}"));
    props.put(
        "preview",
        ToolSupport.prop("boolean", "true（缺省）= 只算前后差异、不写；false = 提交 gov.SetAdministrationPlan"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("payloadJson"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：决策人链路会停在待批（需 GM 点头），GM 链路 GmAutoApproveGate 直接批准。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return GOV_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "设置行政编制计划 preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + "（决策人只能自己的 GOV，需 GM 审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String payloadJson = ToolSupport.requiredText(args, "payloadJson");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      SimulationState state = GovToolSupport.stateAt(query, preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      String declaredUnitId = GovAdministrationProjections.declaredUnitId(payloadJson);
      GovToolSupport.GovTarget target = GovToolSupport.resolveGov(context, state, declaredUnitId);
      GovToolSupport.requireGovWrite(context, target);
      String effectivePayload =
          GovAdministrationProjections.withUnitId(payloadJson, target.govId().value());
      GovAdministrationProjections.PlanProjection projection =
          GovAdministrationProjections.administrationPlan(state, effectivePayload);
      Map<String, Object> view = projectionView(projection, effectivePayload, preview, target);
      if (preview) {
        return ToolSupport.ok(view);
      }
      if (projection.noop()) {
        view.put("preview", false);
        view.put("submitted", false);
        view.put("noop", true);
        view.put("noopReason", "逐值相同的幂等重放（changeSet 为空，不落 revision）");
        return ToolSupport.ok(view);
      }
      return GovToolSupport.submitOne(
          core,
          view,
          initiator,
          UUID.randomUUID().toString(),
          COMMAND_TYPE,
          effectivePayload,
          branch,
          expectedRevision);
    } catch (GovToolSupport.GovRejectedException e) {
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（不折成 TOOL_ERROR）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "设置行政编制计划失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** preview / apply 共用的视图：前后计划 + 命令预览 + 身份派生标记。 */
  private static Map<String, Object> projectionView(
      GovAdministrationProjections.PlanProjection projection,
      String effectivePayload,
      boolean preview,
      GovToolSupport.GovTarget target) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("govUnitId", projection.unitId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("keyExisted", projection.keyExisted());
    view.put("noop", projection.noop());
    view.put("planBefore", planView(projection.previous()));
    view.put("planAfter", planView(projection.next()));
    view.put("at", target.at().map(ToolSupport::hexCoord).orElse(null));
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", COMMAND_TYPE);
    command.put("payloadJson", effectivePayload);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  /** 编制计划的只读视图（字段与载荷同形；档位表保序）。 */
  static Map<String, Object> planView(GovAdministrationPlan plan) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("securityPlannedLaborMilli", plan.securityPlannedLaborMilli());
    view.put("paperworkPlannedLaborMilli", plan.paperworkPlannedLaborMilli());
    List<Map<String, Object>> tiers = new ArrayList<>(plan.postTiers().size());
    for (GovPostTier tier : plan.postTiers()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tierId", tier.tierId());
      row.put("securityWeightPerMille", tier.securityWeightPerMille());
      row.put("paperworkWeightPerMille", tier.paperworkWeightPerMille());
      tiers.add(row);
    }
    view.put("postTiers", List.copyOf(tiers));
    view.put("securitySupplyStaticModifierPerMille", plan.securitySupplyStaticModifierPerMille());
    view.put("paperworkSupplyStaticModifierPerMille", plan.paperworkSupplyStaticModifierPerMille());
    view.put("securityDemandStaticModifierPerMille", plan.securityDemandStaticModifierPerMille());
    view.put("paperworkDemandStaticModifierPerMille", plan.paperworkDemandStaticModifierPerMille());
    view.put("supernumerarySqrtCoefficient", plan.supernumerarySqrtCoefficient());
    return view;
  }
}
