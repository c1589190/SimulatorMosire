package io.mosire.simos.gov.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
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
import io.mosire.simos.gov.tools.GovToolEconomy;
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>{@code simos.gov.setEstablishment}（Z3c-2）：行政编制计划设置工具（GM + 该 GOV 决策人）</b> —— {@code
 * economy.SetGovernmentPolicy} 的 {@code policies.administration} 子集窄封装（preview / apply、payloadJson
 * 逐字透传、expectedRevision；★★ 2026-10-12 追加裁定 12 §21.9 D-3 起编制计划改由 economy 的通用政府政策写口承接）。
 *
 * <p>★★ <b>两面并列、门禁不同</b>：GM 桶调用走 {@code GmAutoApproveGate}（直接批准）；决策人桶调用走 {@code AutoApproveGate →
 * ConfirmGate → PendingApprovals}（需 GM 在审批面点头）。身份派生与越权拒见 {@link GovToolSupport}：决策人只能操作自己所属
 * GOV；载荷若带别的 {@code govUnitId} ⇒ 具名 {@code REJECTED}。
 *
 * <p>★★ <b>命令仍是 GM-only 标记（控制方 2026-10-23 裁定）</b>：{@code economy.SetGovernmentPolicy} 实现 {@code
 * GmOnlyCommand}，只影响 Shell 派生的令 / {@code RegisterEffect} / 决策人 catalog 三条路径；命令总线不认该标记，
 * 因此决策人窄工具直接提交同一命令是受控的（审批链承担门禁）。标记不删 ⇒ 权限面不放大。
 *
 * <p>★ <b>preview 与 apply 同源</b>：两者都读同一坐标的 base 状态、跑 {@link GovPolicyPlanSupport}（与 handler 共用
 * {@code EconomyGovPolicyPayloads} 解析与 GOV 单位守卫），preview 不写状态、不发"已应用"日志；逐值相同的重放 = noop（不落空
 * revision）。
 */
public final class GovSetEstablishmentTool implements SimosTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.setEstablishment}）。 */
  public static final String NAME = "simos.gov.setEstablishment";

  /** 本工具提交的唯一命令类型（装配期从命令类型口取；拼写点仍在 economy 的 handler 里）。 */
  private final String commandType;

  /**
   * 资源声明：只声明 unit 命名空间（本工具的资源断言锚点 = 目标 GOV 单位）。
   *
   * <p>★ 决策人桶的 {@code GovScope} 显式表态 unit 面（自己 + 直辖区单位），因此"自己单位"必过、别的 GOV 必拒；GM 的 unit 面是 {@code
   * unlimited}。
   */
  private static final ResourceManifest GOV_WRITE =
      ResourceManifest.of(ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final GovToolSupport support;

  /**
   * @param support 政府工具接缝束（写口/读口/视图口/视角口/经济口/命令类型口/人力口；见 {@link GovToolSupport}）
   */
  public GovSetEstablishmentTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
    this.commandType = support.commandTypes().setGovernmentPolicy();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "设置一个 GOV 的行政编制计划（economy.SetGovernmentPolicy 的 policies.administration 窄封装）。payloadJson："
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
        ToolSchema.prop(
            "string",
            "编制计划 JSON 文本（平铺；工具嵌成 economy.SetGovernmentPolicy 的 policies.administration）："
                + "{unitId,securityPlannedLaborMilli?,"
                + "paperworkPlannedLaborMilli?,postTiers?[{tierId,securityWeightPerMille,paperworkWeightPerMille}],"
                + "securitySupplyStaticModifierPerMille?,paperworkSupplyStaticModifierPerMille?,"
                + "securityDemandStaticModifierPerMille?,paperworkDemandStaticModifierPerMille?,"
                + "supernumerarySqrtCoefficient?}"));
    props.put(
        "preview",
        ToolSchema.prop("boolean", "true（缺省）= 只算前后差异、不写；false = 提交 economy.SetGovernmentPolicy"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("payloadJson"));
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
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + "（决策人只能自己的 GOV，需 GM 审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String payloadJson = ToolSchema.requiredText(args, "payloadJson");
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      SimulationState state = support.stateAt(preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      String declaredUnitId = GovPolicyPlanSupport.declaredUnitId(payloadJson);
      GovToolSupport.GovTarget target = support.resolveGov(context, state, declaredUnitId);
      support.requireGovWrite(context, target);
      String flatPayload = GovPolicyPlanSupport.withUnitId(payloadJson, target.govId().value());
      String effectivePayload =
          GovPolicyPlanSupport.wrapAsCommand(
              flatPayload, support.economy().administrationSubsetName(), false);
      GovPolicyPlanSupport.PlanProjection projection =
          GovPolicyPlanSupport.administrationPlanProjection(
              support.economy(), state, effectivePayload);
      Map<String, Object> view =
          projectionView(projection, effectivePayload, flatPayload, preview, target);
      if (preview) {
        return ToolResults.ok(view);
      }
      if (projection.noop()) {
        view.put("preview", false);
        view.put("submitted", false);
        view.put("noop", true);
        view.put("noopReason", "逐值相同的幂等重放（changeSet 为空，不落 revision）");
        return ToolResults.ok(view);
      }
      return support.submitOne(view, commandType, effectivePayload, branch, expectedRevision);
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
  private Map<String, Object> projectionView(
      GovPolicyPlanSupport.PlanProjection projection,
      String effectivePayload,
      String flatPayload,
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
    view.put("at", target.at().map(GovToolReads::hexCoord).orElse(null));
    // ★ 两个视图都留：flatPayloadJson = 调用方原样给的那份（工具参数面未变）；
    //   commandsPreview 里的 payloadJson = 真正提交的新命令载荷（含 governmentId + policies.admin...）。
    view.put("flatPayloadJson", flatPayload);
    view.put(
        "commandsPreview",
        List.of(GovPolicyPlanSupport.commandPreview(commandType, effectivePayload)));
    return view;
  }

  /** 编制计划的只读视图（字段与载荷同形；档位表保序）。 */
  static Map<String, Object> planView(GovToolEconomy.AdministrationPlan plan) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("securityPlannedLaborMilli", plan.securityPlannedLaborMilli());
    view.put("paperworkPlannedLaborMilli", plan.paperworkPlannedLaborMilli());
    List<Map<String, Object>> tiers = new ArrayList<>(plan.postTiers().size());
    for (GovToolEconomy.PostTier tier : plan.postTiers()) {
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
