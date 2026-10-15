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
import io.mosire.simos.ga.spi.DecisionProposable;
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
 * ★★ <b>{@code simos.gov.defineCurrency}（A1 2026-10-08；约束设计书 §3.1-3）</b>：定义一种新币种（= 该 GOV 发行的钱）——
 * {@code economy.DefineCurrency} 的窄封装（preview / apply + expectedRevision）。
 *
 * <p>★★ <b>两面并列、门禁不同</b>（照 {@link GovSetEstablishmentTool}）：GM 桶调用走 {@code GmAutoApproveGate}
 * （直接批准）、必须显式给 {@code govUnitId}；决策人桶<b>直写</b>调用走 {@code AutoApproveGate → ConfirmGate →
 * PendingApprovals}（<b>需 GM 点头</b>），{@code govUnitId} 由身份派生、给别的 GOV ⇒ 具名 {@code REJECTED} （见 {@link
 * GovToolSupport}）。★ <b>但「需 GM 点头」只对这条直写路径成立</b>：本工具在 {@code ProposalCatalog} 清单里，决策人改走 {@code
 * simos.ga.packet.propose} 是<b>零审批、不阻塞</b>（2026-10-15 批 3b §11.2 B3 实测：propose 成功且 asks 不增）。 ★
 * 本工具是<b>工具层</b>的（{@code DmUsableCommand} 是**命令层**标记，工具层没有它；工具的角色由工具自己的声明决定）： 决策人用得了它，权限由审批链 +
 * 身份派生兜。它提交的 {@code economy.DefineCurrency} 不打 {@code DmUsableCommand} ⇒ 仅 GM，"令里嵌不进去"这一层因此不放大。
 *
 * <p>★★ <b>但「决策人可省」只在直写路径成立</b>（2026-10-15 实测）：{@code simos.ga.packet.propose} 的真预览（{@code
 * ProposalCatalog.preview}）与 {@code gm.packet.execute} 的规划（{@code
 * DecisionEffectExecutor.planCall}）都用 {@code AgentIdentity.external()} ⇒ 走 GM 分支、{@code govUnitId}
 * 必填，省略 ⇒ {@code BAD_REQUEST}「govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）」（★ 逐字引用该报文的**首句**；正文其后还有「★
 * 键位按工具分叉」一段）。
 *
 * <p>★ <b>写什么</b>：币种表 + 工具表 + 该 GOV 的 {@code issuable}（三处在同一条命令、同一条 revision 里； 详见 {@link
 * EconomyDefineCurrencyHandler} 的类注）。★ <b>只作用于自己所属 GOV</b>（M6）：决策人<b>直写</b>路径下载荷里的 {@code
 * govUnitId} 必须逐字等于自己的 GOV（propose 路径见上一段）。
 *
 * <p>★ <b>资源声明</b>：只声明 {@code economy} 命名空间（世界级词表没有格路径 ⇒ 断言 {@code economy:*}； 决策人的资源范围<b>未表态</b>
 * {@code economy} ⇒ 取工具缺省策略 = {@code UNRESTRICTED}，而 GM 侧 unlimited）。 ★ 这是"命名空间级"的粗断言，真正的收窄在 ①
 * 身份派生（只能自己的 GOV）② GM 审批链 ③ handler 的守卫。
 */
public final class GovDefineCurrencyTool implements SimosTool, DecisionProposable {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.defineCurrency";

  /** 本工具提交的唯一命令类型（装配期从命令类型口取；拼写点仍在 economy 的 handler 里）。 */
  private final String commandType;

  /** 世界级词表的写面（没有格路径 ⇒ 命名空间级断言）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(ToolResources.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 词表是世界级的：路径用通配（DM 侧该命名空间未表态 ⇒ 取工具缺省策略；GM 侧 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolResources.ECONOMY_NAMESPACE, "*"));

  private final GovToolSupport support;

  /**
   * @param support 政府工具接缝束（写口/读口/视图口/视角口/经济口/命令类型口/人力口；见 {@link GovToolSupport}）
   */
  public GovDefineCurrencyTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
    this.commandType = support.commandTypes().defineCurrency();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "定义一个 GOV 的新币种（economy.DefineCurrency 的窄封装）：币种 id + 最小单位精度 + 显示名，"
        + "同时把发行权登记给该 GOV（一币一发行人；已存在的币种 ⇒ 具名拒，改显示名请用 "
        + GovRenameCurrencyTool.NAME
        + "）。参数 {govUnitId?(★ 必填：GM 面与 simos.ga.packet.propose 路径都要显式给；只有【决策人直写】可省 = 自己的 GOV，"
        + "给了必须等于自己的), currencyId(必填), scale(必填，≥0), "
        + "displayName(必填，非空白), reason?, preview?(缺省 true=只读), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "★ 需 GM 在审批面点头只对【决策人直写】成立（该路径停在待批、阻塞等 GM，超时 APPROVAL_DENIED）；"
        + "【决策人提议 simos.ga.packet.propose】零审批、不阻塞（本工具在可提议清单里）。"
        + "★ 只能作用于自己所属 GOV；币种身份建立后不可改（铁律 1）。"
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
            "发行这种钱的 GOV 单位 id（★ 必填：GM 面与 simos.ga.packet.propose 路径都要显式给；只有决策人直写可省 = 自己的 GOV，"
                + "给出别的 GOV ⇒ 越权拒）"));
    props.put("currencyId", ToolSchema.prop("string", "币种 id（必填；= 不可变身份，建立后不可改）"));
    props.put("scale", ToolSchema.prop("integer", "最小单位精度（必填；≥ 0；0 = 没有小数位）"));
    props.put("displayName", ToolSchema.prop("string", "显示名（必填；非空白；不要求唯一）"));
    props.put("reason", ToolSchema.prop("string", "审计原因（可选；给出则必须非空白）"));
    props.put(
        "preview", ToolSchema.prop("boolean", "true（缺省）= 只读预览；false = 提交 economy.DefineCurrency"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("currencyId", "scale", "displayName"));
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
        "定义币种 currency="
            + args.get("currencyId")
            + " scale="
            + args.get("scale")
            + " displayName="
            + args.get("displayName")
            + " govUnitId="
            + args.getOrDefault("govUnitId", "(身份派生)")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + "（决策人直写：只能自己的 GOV，需 GM 审批；本工具在可提议清单里，改走 simos.ga.packet.propose 零审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String currencyId = ToolSchema.requiredText(args, "currencyId");
      long scale = ToolSchema.requiredLong(args, "scale");
      String displayName = ToolSchema.requiredText(args, "displayName");
      String reason = ToolSchema.optionalText(args, "reason", null);
      if (scale < 0L) {
        throw new IllegalArgumentException("参数 scale 是最小单位精度、不得为负: " + scale);
      }
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

      // ★ 工具侧先给同一条具名拒（handler 里还会再判一次；两处同源，工具侧只是更早、更可读）。
      String existingDisplayName = support.economy().currencyDisplayName(state, currencyId);
      if (existingDisplayName != null) {
        throw new IllegalArgumentException(
            "币种 "
                + currencyId
                + " 已存在（现有显示名="
                + existingDisplayName
                + "）；改显示名请用 "
                + GovRenameCurrencyTool.NAME);
      }
      String payloadJson = payload(target.govId().value(), currencyId, scale, displayName, reason);
      Map<String, Object> view = view(preview, target, currencyId, scale, displayName, payloadJson);
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
          "TOOL_ERROR", "定义币种失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 命令载荷（字段名与 handler 的解析契约一致；{@code govUnitId} 取身份派生后的值）。 */
  private static String payload(
      String govUnitId, String currencyId, long scale, String displayName, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", govUnitId);
    payload.put("currencyId", currencyId);
    payload.put("scale", scale);
    payload.put("displayName", displayName);
    if (reason != null) {
      payload.put("reason", reason);
    }
    return ToolResults.json(payload);
  }

  /** preview / apply 共用的视图（{@code govDerivedFromIdentity} 标明{@code govUnitId} 是不是身份派生的）。 */
  private Map<String, Object> view(
      boolean preview,
      GovToolSupport.GovTarget target,
      String currencyId,
      long scale,
      String displayName,
      String payloadJson) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("currency", currencyId);
    view.put("scale", scale);
    view.put("displayName", displayName);
    view.put("instrumentId", currencyId + "-specie");
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
