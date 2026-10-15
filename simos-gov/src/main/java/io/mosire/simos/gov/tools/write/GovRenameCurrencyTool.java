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
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>{@code simos.gov.renameCurrency}（A1 2026-10-08；约束设计书 §3.1-3）</b>：<b>只改币种的显示名</b> —— {@code
 * economy.RenameCurrency} 的窄封装（preview / apply + expectedRevision）。
 *
 * <p>★★ <b>它改不动的东西（这就是它的全部价值）</b>：币种 id、精度、工具表、任何余额/流水/债务/市场键。载荷里<b>没有</b>这些字段 ⇒
 * 模型给不出"顺手换身份"的调用；命令侧也只写 {@code currencies} 一张表（不变量 <b>I16</b> / 判据 <b>F1</b> / 负向用例 <b>M5</b>）。
 *
 * <p>★★ <b>权限不得放大（M6）</b>：只有<b>该币种的发行 GOV</b>能改它的显示名 —— 由 ① 身份派生（决策人<b>直写</b>时只能自己的 GOV） ② handler
 * 的 {@code issuable} 判据（不是发行人 ⇒ 具名拒）③ GM 审批链（**直写路径**的那条；提议路径的等价物是 catalog 的目标/资源断言）三层共同保证。
 *
 * <p>★ 两面并列、门禁不同（同 {@link GovDefineCurrencyTool}）：GM 桶 {@code GmAutoApproveGate} + 显式 {@code
 * govUnitId}；决策人桶<b>直写</b>需 GM 审批 + 身份派生。★ <b>「需 GM 审批」只对直写路径成立</b>：本工具在 {@code ProposalCatalog}
 * 清单里，决策人改走 {@code simos.ga.packet.propose} 是<b>零审批、不阻塞</b>（2026-10-15 批 3b §11.2 B3 实测）。
 *
 * <p>★★ <b>但「决策人可省」只在直写路径成立</b>（2026-10-15 实测）：{@code simos.ga.packet.propose} 的真预览（{@code
 * ProposalCatalog.preview}）与 {@code gm.packet.execute} 的规划（{@code
 * DecisionEffectExecutor.planCall}）都用 {@code AgentIdentity.external()} ⇒ 走 GM 分支、{@code govUnitId}
 * 必填，省略 ⇒ {@code BAD_REQUEST}「govUnitId 必填（GM 调用 必须显式点名要操作的 GOV 单位）」。
 */
public final class GovRenameCurrencyTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.renameCurrency";

  /** 本工具提交的唯一命令类型（与 handler 的 {@code TYPE} 同一个拼写点）。 */
  private final String commandType;

  /** 世界级词表的写面（没有格路径 ⇒ 命名空间级断言）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 见 {@link GovDefineCurrencyTool} 的同名字段说明。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolResources.ECONOMY_NAMESPACE, "*"));

  private final GovToolSupport support;

  /**
   * @param support 政府工具接缝束（写口/读口/视图口/视角口/经济口/命令类型口/人力口；见 {@link GovToolSupport}）
   */
  public GovRenameCurrencyTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
    this.commandType = support.commandTypes().renameCurrency();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "改一个 GOV 所发行币种的显示名（economy.RenameCurrency 的窄封装）：只改 displayName —— "
        + "币种 id / 精度 / 工具表 / 任何账（余额、流水、债务、市场键）都不动（I16）。参数 "
        + "{govUnitId?(★ 必填：GM 面与 simos.ga.packet.propose 路径都要显式给；只有【决策人直写】可省 = 自己的 GOV，"
        + "给了必须等于自己的), currencyId(必填), displayName(必填，新显示名), "
        + "reason?, preview?(缺省 true=只读), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "★ 需 GM 在审批面点头只对【决策人直写】成立（该路径停在待批、阻塞等 GM，超时 APPROVAL_DENIED）；"
        + "【决策人提议 simos.ga.packet.propose】零审批、不阻塞（本工具在可提议清单里）。"
        + "★ 只有该币种的发行 GOV 能改它的显示名（越权 ⇒ 具名拒）。"
        + "★ 身份与路径（2026-10-15 实测）：只有【决策人直写】可省 govUnitId（省略 = 自己的 GOV，给出别人的 GOV ⇒ 具名 "
        + "REJECTED）；【GM 面调用】与【simos.ga.packet.propose 提议】都必须显式给 —— propose 的真预览（ProposalCatalog.preview）"
        + "与 gm.packet.execute 的规划（DecisionEffectExecutor.planCall）都走 AgentIdentity.external()（GM 分支），"
        + "省略 ⇒ BAD_REQUEST「govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）」。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSchema.prop(
            "string",
            "该币种的发行 GOV 单位 id（★ 必填：GM 面与 simos.ga.packet.propose 路径都要显式给；只有决策人直写可省 = 自己的 GOV，"
                + "给出别的 GOV ⇒ 越权拒）"));
    props.put("currencyId", ToolSchema.prop("string", "币种 id（必填；= 不可变身份，本工具改不动它）"));
    props.put("displayName", ToolSchema.prop("string", "新显示名（必填；非空白；不要求唯一）"));
    props.put("reason", ToolSchema.prop("string", "审计原因（可选；给出则必须非空白）"));
    props.put(
        "preview", ToolSchema.prop("boolean", "true（缺省）= 只读预览；false = 提交 economy.RenameCurrency"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("currencyId", "displayName"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：决策人【直写】走 AutoApproveGate → ConfirmGate → 待批（需 GM 点头）；【提议】simos.ga.packet.propose 零审批。
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
        "币种改名 currency="
            + args.get("currencyId")
            + " displayName="
            + args.get("displayName")
            + " govUnitId="
            + args.getOrDefault("govUnitId", "(身份派生)")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + "（决策人直写：只改显示名、不动账，需 GM 审批；改走 simos.ga.packet.propose 则零审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String currencyId = ToolSchema.requiredText(args, "currencyId");
      String displayName = ToolSchema.requiredText(args, "displayName");
      String reason = ToolSchema.optionalText(args, "reason", null);
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      // ★ D3 内部参数：不进 jsonSchema / 工具描述，只被系统执行器注入（见 GovBatchViews#planOnly）。
      boolean planOnly = !preview && ToolSchema.optionalBoolean(args, "planOnly").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      SimulationState state = support.stateAt(preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      GovToolSupport.GovTarget target =
          support.resolveGov(context, state, ToolSchema.optionalText(args, "govUnitId", null));
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);

      String currentDisplayName = support.economy().currencyDisplayName(state, currencyId);
      if (currentDisplayName == null) {
        throw new IllegalArgumentException("币种 " + currencyId + " 在本世界的词表里没有定义");
      }
      Long currentScale = support.economy().currencyScale(state, currencyId);
      GovernmentId governmentId = GovernmentIds.ofUnit(target.govId().value());
      if (!support.economy().governmentRegistered(state, target.govId().value())) {
        throw new GovToolSupport.GovRejectedException(
            "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
      }
      if (!support.economy().governmentIssuesCurrency(state, target.govId().value(), currencyId)) {
        // ★ M6：越权改名 —— 只有发行这种钱的 GOV 能改它（权限判据 ⇒ REJECTED，不是 BAD_REQUEST）。
        throw new GovToolSupport.GovRejectedException(
            "政府 "
                + governmentId.value()
                + " 不发行币种 "
                + currencyId
                + "（issuable="
                + support.economy().governmentIssuableText(state, target.govId().value())
                + "；只有发行政府能改它的显示名）");
      }
      if (currentDisplayName.equals(displayName)) {
        throw new IllegalArgumentException("新显示名与现值逐字相同（" + currentDisplayName + "），无需改名");
      }
      String payloadJson = payload(target.govId().value(), currencyId, displayName, reason);
      Map<String, Object> view =
          view(
              preview,
              target,
              currencyId,
              currentScale,
              currentDisplayName,
              displayName,
              payloadJson);
      if (preview) {
        return ToolResults.ok(view);
      }
      if (planOnly) {
        // ★★ 只规划不提交：单命令工具的批 = 一条（与 submitOne 落盘的那一条同源，一个字节都不写）。
        return ToolResults.ok(
            GovBatchViews.planOnly(List.of(GovToolSupport.request(commandType, payloadJson))));
      }
      return support.submitOne(view, commandType, payloadJson, branch, expectedRevision);
    } catch (GovToolSupport.GovRejectedException e) {
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（不折成 TOOL_ERROR）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "币种改名失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 命令载荷（字段名与 handler 的解析契约一致；{@code govUnitId} 取身份派生后的值）。 */
  private static String payload(
      String govUnitId, String currencyId, String displayName, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", govUnitId);
    payload.put("currencyId", currencyId);
    payload.put("displayName", displayName);
    if (reason != null) {
      payload.put("reason", reason);
    }
    return ToolResults.json(payload);
  }

  /** preview / apply 共用的视图：前后显示名对照 + 明确写出"没有动的东西"。 */
  private Map<String, Object> view(
      boolean preview,
      GovToolSupport.GovTarget target,
      String currencyId,
      Long currentScale,
      String currentDisplayName,
      String displayName,
      String payloadJson) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("currency", currencyId);
    view.put("scale", currentScale);
    view.put("displayNameBefore", currentDisplayName);
    view.put("displayNameAfter", displayName);
    view.put(
        "unchanged", List.of("id", "scale", "moneyInstruments", "balances", "transfers", "debts"));
    view.put("at", target.at().map(GovToolReads::hexCoord).orElse(null));
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", commandType);
    command.put("payloadJson", payloadJson);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
