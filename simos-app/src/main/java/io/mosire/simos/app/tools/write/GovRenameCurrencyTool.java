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
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.spi.EconomyRenameCurrencyHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.renameCurrency}（A1 2026-10-08；约束设计书 §3.1-3）</b>：<b>只改币种的显示名</b> —— {@code
 * economy.RenameCurrency} 的窄封装（preview / apply + expectedRevision）。
 *
 * <p>★★ <b>它改不动的东西（这就是它的全部价值）</b>：币种 id、精度、工具表、任何余额/流水/债务/市场键。载荷里<b>没有</b>这些字段 ⇒
 * 模型给不出"顺手换身份"的调用；命令侧也只写 {@code currencies} 一张表（不变量 <b>I16</b> / 判据 <b>F1</b> / 负向用例 <b>M5</b>）。
 *
 * <p>★★ <b>权限不得放大（M6）</b>：只有<b>该币种的发行 GOV</b>能改它的显示名 —— 由 ① 身份派生（决策人只能自己的 GOV） ② handler 的 {@code
 * issuable} 判据（不是发行人 ⇒ 具名拒）③ GM 审批链三层共同保证。
 *
 * <p>★ 两面并列、门禁不同（同 {@link GovDefineCurrencyTool}）：GM 桶 {@code GmAutoApproveGate} + 显式 {@code
 * govUnitId}；决策人桶需 GM 审批 + 身份派生。
 */
public final class GovRenameCurrencyTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.renameCurrency";

  /** 本工具提交的唯一命令类型（与 handler 的 {@code TYPE} 同一个拼写点）。 */
  private static final String COMMAND_TYPE = EconomyRenameCurrencyHandler.TYPE;

  /** 世界级词表的写面（没有格路径 ⇒ 命名空间级断言）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 见 {@link GovDefineCurrencyTool} 的同名字段说明。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（{@code preview=false} 时提交一条命令）
   * @param query 只读入口（preview / apply 共用同一份 base 状态解析）
   * @param initiator 落盘时的发起者（{@code <kind>:<id>} 形态）
   */
  public GovRenameCurrencyTool(CoreSimos core, QueryService query, String initiator) {
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
    return "改一个 GOV 所发行币种的显示名（economy.RenameCurrency 的窄封装）：只改 displayName —— "
        + "币种 id / 精度 / 工具表 / 任何账（余额、流水、债务、市场键）都不动（I16）。参数 "
        + "{govUnitId?(GM 必填；决策人省略=自己的 GOV，给了必须等于自己的), currencyId(必填), displayName(必填，新显示名), "
        + "reason?, preview?(缺省 true=只读), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "★ 需 GM 在审批面点头。★ 只有该币种的发行 GOV 能改它的显示名（越权 ⇒ 具名拒）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSupport.prop("string", "该币种的发行 GOV 单位 id（GM 必填；决策人省略 = 自己的 GOV，给出别的 GOV ⇒ 越权拒）"));
    props.put("currencyId", ToolSupport.prop("string", "币种 id（必填；= 不可变身份，本工具改不动它）"));
    props.put("displayName", ToolSupport.prop("string", "新显示名（必填；非空白；不要求唯一）"));
    props.put("reason", ToolSupport.prop("string", "审计原因（可选；给出则必须非空白）"));
    props.put(
        "preview", ToolSupport.prop("boolean", "true（缺省）= 只读预览；false = 提交 economy.RenameCurrency"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("currencyId", "displayName"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：决策人链路走 AutoApproveGate → ConfirmGate → 待批（需 GM 点头）。
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
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + "（只改显示名，不动账；需 GM 审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String currencyId = ToolSupport.requiredText(args, "currencyId");
      String displayName = ToolSupport.requiredText(args, "displayName");
      String reason = ToolSupport.optionalText(args, "reason", null);
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      SimulationState state = GovToolSupport.stateAt(query, preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      GovToolSupport.GovTarget target =
          GovToolSupport.resolveGov(
              context, state, ToolSupport.optionalText(args, "govUnitId", null));
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);

      EconomyData data = economyData(state);
      CurrencyId currency = new CurrencyId(currencyId);
      CurrencyDef current = data.currencies().get(currency);
      if (current == null) {
        throw new IllegalArgumentException("币种 " + currencyId + " 在本世界的词表里没有定义");
      }
      GovernmentId governmentId = GovernmentIds.ofUnit(target.govId().value());
      Government government = data.governments().get(governmentId);
      if (government == null) {
        throw new GovToolSupport.GovRejectedException(
            "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
      }
      if (!government.issuable().contains(currency)) {
        // ★ M6：越权改名 —— 只有发行这种钱的 GOV 能改它（权限判据 ⇒ REJECTED，不是 BAD_REQUEST）。
        throw new GovToolSupport.GovRejectedException(
            "政府 "
                + governmentId.value()
                + " 不发行币种 "
                + currencyId
                + "（issuable="
                + government.issuable()
                + "；只有发行政府能改它的显示名）");
      }
      if (current.displayName().equals(displayName)) {
        throw new IllegalArgumentException("新显示名与现值逐字相同（" + current.displayName() + "），无需改名");
      }
      String payloadJson = payload(target.govId().value(), currencyId, displayName, reason);
      Map<String, Object> view =
          view(preview, target, currencyId, current, displayName, payloadJson);
      if (preview) {
        return ToolSupport.ok(view);
      }
      return GovToolSupport.submitOne(
          core,
          view,
          initiator,
          UUID.randomUUID().toString(),
          COMMAND_TYPE,
          payloadJson,
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
    return ToolSupport.json(payload);
  }

  /** preview / apply 共用的视图：前后显示名对照 + 明确写出"没有动的东西"。 */
  private static Map<String, Object> view(
      boolean preview,
      GovToolSupport.GovTarget target,
      String currencyId,
      CurrencyDef current,
      String displayName,
      String payloadJson) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("currency", currencyId);
    view.put("scale", current.scale());
    view.put("displayNameBefore", current.displayName());
    view.put("displayNameAfter", displayName);
    view.put(
        "unchanged", List.of("id", "scale", "moneyInstruments", "balances", "transfers", "debts"));
    view.put("at", target.at().map(ToolSupport::hexCoord).orElse(null));
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", COMMAND_TYPE);
    command.put("payloadJson", payloadJson);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  /** economy 切片（缺切片/类型不符 = 装配故障，不当成"没有币种"）。 */
  private static EconomyData economyData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障）"));
    if (snapshot instanceof EconomySnapshot economySnapshot) {
      return economySnapshot.data();
    }
    throw new IllegalStateException(
        "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
  }
}
