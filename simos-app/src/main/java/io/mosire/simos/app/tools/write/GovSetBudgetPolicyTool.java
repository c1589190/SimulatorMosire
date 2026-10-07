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
import io.mosire.simos.gov.GovBudgetLine;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovBudgetPolicyEditMode;
import io.mosire.simos.gov.GovOfficialSalaryRule;
import io.mosire.simos.gov.spi.GovAdministrationProjections;
import io.mosire.simos.gov.spi.SetBudgetPolicyHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.setBudgetPolicy}（Z3c-2）：国库预算政策设置工具（GM + 该 GOV 决策人）</b> —— {@code
 * gov.SetBudgetPolicy} 的窄封装（preview / apply、payloadJson 逐字透传、expectedRevision）。
 *
 * <p>★★ <b>两面并列、门禁不同</b>：GM 桶调用走 {@code GmAutoApproveGate}；决策人桶调用走 {@code AutoApproveGate →
 * ConfirmGate → PendingApprovals}（需 GM 点头）。身份派生与越权拒见 {@link GovToolSupport}。
 *
 * <p>★★ <b>命令仍是 GM-only 标记（控制方 2026-10-23 裁定）</b>：标记只影响令 / {@code RegisterEffect} / 决策人 catalog
 * 三条路径，不拦命令总线 ⇒ 决策人窄工具直接提交同一命令受控、且不放大任何路径。
 *
 * <p>★ <b>preview 与 apply 同源</b>：同一坐标、同一 {@link GovAdministrationProjections}（与 handler 共用字段解析 +
 * GOV 单位守卫）；逐值相同的重放 = noop（不落空 revision）。
 */
public final class GovSetBudgetPolicyTool implements AgentTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.setBudgetPolicy}）。 */
  public static final String NAME = "simos.gov.setBudgetPolicy";

  /** 本工具提交的唯一命令类型（与 handler 的 TYPE 同一个拼写点）。 */
  private static final String COMMAND_TYPE = SetBudgetPolicyHandler.TYPE;

  /** 资源声明 = unit 命名空间；断言锚点 = 目标 GOV 单位（口径同 {@link GovSetEstablishmentTool}）。 */
  private static final ResourceManifest GOV_WRITE =
      ResourceManifest.of(ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public GovSetBudgetPolicyTool(CoreSimos core, QueryService query, String initiator) {
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
    return "设置一个 GOV 的国库预算政策（gov.SetBudgetPolicy 窄封装）。payloadJson："
        + "{unitId(GM 必填；决策人可省=自己的 GOV，给出必须等于自己的 GOV), orderedCategories?"
        + "[{category(ADMIN_STIPEND|MILITARY_STIPEND|ADMIN_SALARY|DEBT_SERVICE|OTHER), minPerCycle?,"
        + "capPerCycle?}](顺序即预算优先级), officialSalaryRule? "
        + "{grainMilliPerCommittedHour?, silverMilliPerCommittedHour?}, "
        + "remittancePerMilleToSuperior?(0..1000‰；周期末按本周期实收税上缴 superiorGov 国库)，"
        + "mode?(PATCH|REPLACE，缺省 PATCH)}。★ PATCH（缺省）：缺省字段保留现值——只改 remittance 不会清空类别表/工资规则，"
        + "要清空类别表须显式 orderedCategories:[]；REPLACE = 旧整表替换（缺省=空表/0/0/0）。工具另可传顶层 mode 参数覆盖 payloadJson 里的 mode。"
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
            "预算政策 JSON 文本（逐字透传给 gov.SetBudgetPolicy）：{unitId,orderedCategories?["
                + "{category,minPerCycle?,capPerCycle?}],officialSalaryRule?{grainMilliPerCommittedHour?,"
                + "silverMilliPerCommittedHour?},remittancePerMilleToSuperior?(0..1000)，mode?(PATCH|REPLACE，缺省 PATCH)}；"
                + "★ PATCH：缺省字段保留现值（orderedCategories:[] 才清空类别表；工资规则逐内层字段合并）；"
                + "REPLACE：旧整表替换（缺省 = 空表/0/0/0）。类别表顺序 = 预算优先级，capPerCycle 缺省 = 不封顶。"));
    props.put(
        "mode",
        ToolSupport.prop(
            "string", "PATCH|REPLACE（缺省随 payloadJson；给出则覆盖 payloadJson 的 mode）。PATCH = 缺省字段保留现值"));
    props.put(
        "preview",
        ToolSupport.prop("boolean", "true（缺省）= 只算前后差异、不写；false = 提交 gov.SetBudgetPolicy"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("payloadJson"));
  }

  @Override
  public ToolSpec spec() {
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
        "设置国库预算政策 preview="
            + args.getOrDefault("preview", true)
            + " mode="
            + args.getOrDefault("mode", "payload")
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
      String modeArg = ToolSupport.optionalText(args, "mode", null);
      GovBudgetPolicyEditMode toolMode =
          modeArg == null ? null : GovBudgetPolicyEditMode.parse(modeArg);
      SimulationState state = GovToolSupport.stateAt(query, preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      String declaredUnitId = GovAdministrationProjections.declaredUnitId(payloadJson);
      GovToolSupport.GovTarget target = GovToolSupport.resolveGov(context, state, declaredUnitId);
      GovToolSupport.requireGovWrite(context, target);
      String effectivePayload =
          GovAdministrationProjections.withUnitId(payloadJson, target.govId().value());
      if (toolMode != null) {
        effectivePayload = GovAdministrationProjections.withMode(effectivePayload, toolMode);
      }
      GovAdministrationProjections.BudgetProjection projection =
          GovAdministrationProjections.budgetPolicy(state, effectivePayload);
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
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "设置国库预算政策失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Map<String, Object> projectionView(
      GovAdministrationProjections.BudgetProjection projection,
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
    view.put("editMode", projection.requestedMode().name());
    view.put("budgetBefore", policyView(projection.previous()));
    view.put("budgetAfter", policyView(projection.next()));
    view.put("at", target.at().map(ToolSupport::hexCoord).orElse(null));
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", COMMAND_TYPE);
    command.put("payloadJson", effectivePayload);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  /** 预算政策的只读视图（字段与载荷同形；类别表保序）。 */
  static Map<String, Object> policyView(GovBudgetPolicy policy) {
    Map<String, Object> view = new LinkedHashMap<>();
    List<Map<String, Object>> categories = new ArrayList<>(policy.orderedCategories().size());
    for (GovBudgetLine line : policy.orderedCategories()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", line.category().name());
      row.put("minPerCycle", line.minPerCycle());
      row.put("capPerCycle", line.capPerCycle());
      categories.add(row);
    }
    view.put("orderedCategories", List.copyOf(categories));
    view.put("remittancePerMilleToSuperior", policy.remittancePerMilleToSuperior());
    GovOfficialSalaryRule rule = policy.officialSalaryRule();
    Map<String, Object> salary = new LinkedHashMap<>();
    salary.put("grainMilliPerCommittedHour", rule.grainMilliPerCommittedHour());
    salary.put("silverMilliPerCommittedHour", rule.silverMilliPerCommittedHour());
    view.put("officialSalaryRule", salary);
    return view;
  }
}
