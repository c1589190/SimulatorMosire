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
 * ★★ <b>{@code simos.gov.setBudgetPolicy}（Z3c-2）：国库预算政策设置工具（GM + 该 GOV 决策人）</b> —— {@code
 * economy.SetGovernmentPolicy} 的 {@code policies.budget} 子集窄封装（preview / apply、payloadJson
 * 逐字透传、expectedRevision；★★ 2026-10-12 追加裁定 12 §21.9 D-3 起预算政策改由 economy 的通用政府政策写口承接）。
 *
 * <p>★★ <b>两面并列、门禁不同</b>：GM 桶调用走 {@code GmAutoApproveGate}；决策人桶<b>直写</b>调用走 {@code AutoApproveGate
 * → ConfirmGate → PendingApprovals}（需 GM 点头）。★ <b>但「需 GM 点头」只对这条直写路径成立</b>：本工具在 {@code
 * ProposalCatalog} 清单里，决策人改走 {@code simos.ga.packet.propose} 是<b>零审批、不阻塞</b>（2026-10-15 批 3b §11.2
 * B3 实测：propose 成功且 asks 不增）。身份派生与越权拒见 {@link GovToolSupport}。
 *
 * <p>★★ <b>但「决策人可省 unitId」只在直写路径成立</b>（2026-10-15 实测）：{@code simos.ga.packet.propose} 的真预览（{@code
 * ProposalCatalog.preview}）与 {@code gm.packet.execute} 的规划（{@code
 * DecisionEffectExecutor.planCall}）都用 {@code AgentIdentity.external()} ⇒ 走 GM 分支、载荷 {@code unitId}
 * 必填，省略 ⇒ {@code BAD_REQUEST}「govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）」。
 *
 * <p>★★ <b>命令仍是 GM-only 标记（控制方 2026-10-23 裁定）</b>：标记只影响令 / {@code RegisterEffect} / 决策人 catalog
 * 三条路径，不拦命令总线 ⇒ 决策人窄工具直接提交同一命令受控、且不放大任何路径。
 *
 * <p>★ <b>preview 与 apply 同源</b>：同一坐标、同一 {@link GovPolicyPlanSupport}（与 handler 共用字段解析 + GOV
 * 单位守卫）；逐值相同的重放 = noop（不落空 revision）。
 */
public final class GovSetBudgetPolicyTool implements SimosTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.setBudgetPolicy}）。 */
  public static final String NAME = "simos.gov.setBudgetPolicy";

  /** 本工具提交的唯一命令类型（与 handler 的 TYPE 同一个拼写点）。 */
  private final String commandType;

  /** 资源声明 = unit 命名空间；断言锚点 = 目标 GOV 单位（口径同 {@link GovSetEstablishmentTool}）。 */
  private static final ResourceManifest GOV_WRITE =
      ResourceManifest.of(ToolResources.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final GovToolSupport support;

  public GovSetBudgetPolicyTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
    this.commandType = support.commandTypes().setGovernmentPolicy();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "设置一个 GOV 的国库预算政策（economy.SetGovernmentPolicy 的 policies.budget 窄封装）。payloadJson："
        + "{unitId(★ 必填：GM 面与 simos.ga.packet.propose 路径都要显式给；只有【决策人直写】可省 = 自己的 GOV，"
        + "给出必须等于自己的 GOV), orderedCategories?"
        + "[{category(ADMIN_STIPEND|MILITARY_STIPEND|ADMIN_SALARY|DEBT_SERVICE|OTHER), minPerCycle?,"
        + "capPerCycle?}](顺序即预算优先级), officialSalaryRule? "
        + "{grainMilliPerCommittedHour?, silverMilliPerCommittedHour?}, "
        + "remittancePerMilleToSuperior?(0..1000‰；周期末按本周期实收税上缴 superiorGov 国库)，"
        + "mode?(PATCH|REPLACE，缺省 PATCH)}。★ PATCH（缺省）：缺省字段保留现值——只改 remittance 不会清空类别表/工资规则，"
        + "要清空类别表须显式 orderedCategories:[]；REPLACE = 旧整表替换（缺省=空表/0/0/0）。工具另可传顶层 mode 参数覆盖 payloadJson 里的 mode。"
        + "GM 调用需显式 unitId；决策人直写调用身份派生、只能自己的 GOV（越权 GOV 具名拒），且直写会停在待批、阻塞等 GM "
        + "点头（超时 APPROVAL_DENIED）；【决策人提议 simos.ga.packet.propose】零审批、不阻塞（本工具在可提议清单里）。"
        + "preview=true（缺省）只算前后差异、不写；preview=false 必须给 expectedRevision，逐值相同的重放 = noop。"
        + "★ 身份与路径（2026-10-15 实测）：只有【决策人直写】可省载荷里的 unitId；【GM 面调用】与"
        + "【simos.ga.packet.propose 提议】都必须显式给 —— propose 的真预览（ProposalCatalog.preview）与 "
        + "gm.packet.execute 的规划（DecisionEffectExecutor.planCall）都走 AgentIdentity.external()（GM 分支），"
        + "省略 ⇒ BAD_REQUEST「govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）」（★ 该正文里的字段名是 "
        + "govUnitId，指的就是本载荷的 unitId）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "payloadJson",
        ToolSchema.prop(
            "string",
            "预算政策 JSON 文本（平铺；工具嵌成 economy.SetGovernmentPolicy 的 policies.budget）：{unitId（★ 必填："
                + "GM 面与 simos.ga.packet.propose 路径都要显式给；只有决策人直写可省 = 自己的 GOV）,orderedCategories?["
                + "{category,minPerCycle?,capPerCycle?}],officialSalaryRule?{grainMilliPerCommittedHour?,"
                + "silverMilliPerCommittedHour?},remittancePerMilleToSuperior?(0..1000)，mode?(PATCH|REPLACE，缺省 PATCH)}；"
                + "★ PATCH：缺省字段保留现值（orderedCategories:[] 才清空类别表；工资规则逐内层字段合并）；"
                + "REPLACE：旧整表替换（缺省 = 空表/0/0/0）。类别表顺序 = 预算优先级，capPerCycle 缺省 = 不封顶。"));
    props.put(
        "mode",
        ToolSchema.prop(
            "string", "PATCH|REPLACE（缺省随 payloadJson；给出则覆盖 payloadJson 的 mode）。PATCH = 缺省字段保留现值"));
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
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + "（决策人直写：只能自己的 GOV，需 GM 审批；本工具在可提议清单里，改走 simos.ga.packet.propose 零审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String payloadJson = ToolSchema.requiredText(args, "payloadJson");
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      // ★ D3 内部参数：不进 jsonSchema / 工具描述，只被系统执行器注入（见 GovBatchViews#planOnly）。
      boolean planOnly = !preview && ToolSchema.optionalBoolean(args, "planOnly").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      String modeArg = ToolSchema.optionalText(args, "mode", null);
      String toolMode = modeArg == null ? null : support.economy().parseBudgetEditMode(modeArg);
      SimulationState state = support.stateAt(preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      String declaredUnitId = GovPolicyPlanSupport.declaredUnitId(payloadJson);
      GovToolSupport.GovTarget target = support.resolveGov(context, state, declaredUnitId);
      support.requireGovWrite(context, target);
      String flatPayload = GovPolicyPlanSupport.withUnitId(payloadJson, target.govId().value());
      if (toolMode != null) {
        flatPayload = GovPolicyPlanSupport.withMode(flatPayload, toolMode);
      }
      String effectivePayload =
          GovPolicyPlanSupport.wrapAsCommand(
              flatPayload, support.economy().budgetSubsetName(), true);
      GovPolicyPlanSupport.BudgetProjection projection =
          GovPolicyPlanSupport.budgetPolicyProjection(support.economy(), state, effectivePayload);
      Map<String, Object> view =
          projectionView(projection, effectivePayload, flatPayload, preview, target);
      if (preview) {
        return ToolResults.ok(view);
      }
      if (projection.noop()) {
        if (planOnly) {
          // ★★ 幂等重放：preview=false 也不落 revision ⇒ 规划期同样交出**空**命令批（执行器记 empty，不落效果命令）。
          return ToolResults.ok(GovBatchViews.planOnly(List.of()));
        }
        view.put("preview", false);
        view.put("submitted", false);
        view.put("noop", true);
        view.put("noopReason", "逐值相同的幂等重放（changeSet 为空，不落 revision）");
        return ToolResults.ok(view);
      }
      if (planOnly) {
        // ★★ 只规划不提交：单命令工具的批 = 一条（与 submitOne 落盘的那一条同源，一个字节都不写）。
        return ToolResults.ok(
            GovBatchViews.planOnly(List.of(GovToolSupport.request(commandType, effectivePayload))));
      }
      return support.submitOne(view, commandType, effectivePayload, branch, expectedRevision);
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

  private Map<String, Object> projectionView(
      GovPolicyPlanSupport.BudgetProjection projection,
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
    view.put("editMode", projection.requestedMode());
    view.put("budgetBefore", policyView(projection.previous()));
    view.put("budgetAfter", policyView(projection.next()));
    view.put("at", target.at().map(GovToolReads::hexCoord).orElse(null));
    // ★ 两个视图都留：flatPayloadJson = 调用方原样给的那份（工具参数面未变）；
    //   commandsPreview 里的 payloadJson = 真正提交的新命令载荷（含 governmentId + policies.budget）。
    view.put("flatPayloadJson", flatPayload);
    view.put(
        "commandsPreview",
        List.of(GovPolicyPlanSupport.commandPreview(commandType, effectivePayload)));
    return view;
  }

  /** 预算政策的只读视图（字段与载荷同形；类别表保序）。 */
  static Map<String, Object> policyView(GovToolEconomy.BudgetPolicy policy) {
    Map<String, Object> view = new LinkedHashMap<>();
    List<Map<String, Object>> categories = new ArrayList<>(policy.orderedCategories().size());
    for (GovToolEconomy.BudgetLine line : policy.orderedCategories()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", line.category());
      row.put("minPerCycle", line.minPerCycle());
      row.put("capPerCycle", line.capPerCycle());
      categories.add(row);
    }
    view.put("orderedCategories", List.copyOf(categories));
    view.put("remittancePerMilleToSuperior", policy.remittancePerMilleToSuperior());
    GovToolEconomy.OfficialSalaryRule rule = policy.officialSalaryRule();
    Map<String, Object> salary = new LinkedHashMap<>();
    salary.put("grainMilliPerCommittedHour", rule.grainMilliPerCommittedHour());
    salary.put("silverMilliPerCommittedHour", rule.silverMilliPerCommittedHour());
    view.put("officialSalaryRule", salary);
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
