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
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.spi.EconomySetOfficialRateHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.setFxRate}（A2a 2026-10-08；约束设计书 §3.3）</b>：定/改一个 GOV 对某个币对的<b>官方汇率</b> ——
 * {@code economy.SetOfficialRate} 的窄封装（preview / apply + expectedRevision）。
 *
 * <p>★★ <b>两面并列、门禁不同</b>（照 {@link GovDefineCurrencyTool}）：GM 桶调用走 {@code GmAutoApproveGate}
 * （直接批准）、必须显式给 {@code govUnitId}；决策人桶调用走 {@code AutoApproveGate → ConfirmGate →
 * PendingApprovals}（<b>需 GM 点头</b>），{@code govUnitId} 由身份派生、给别的 GOV ⇒ 具名 {@code REJECTED} （见 {@link
 * GovToolSupport}）。★ 本工具<b>不标</b> {@code GmOnlyCommand}（那是命令层的标记）：决策人用得了它， 权限由审批链 + 身份派生兜。
 *
 * <p>★ <b>写什么</b>：只写该政府的 {@code officialRates} 里的一条（{@code base|quote} 键）。★ <b>只作用于自己所属 GOV</b> （与
 * {@code gov.defineCurrency} 同一条权限口径）。
 *
 * <p>★★ <b>定这条汇率的后果（工具描述里必须说清，避免误用）</b>：它<b>激活</b>该币对的外汇市场 —— 家户的 FX 挂单规则以 官方报价为锚（{@code
 * FxSettlement}），政府外汇窗口按 {@code bidP = buyPerMille} / {@code askP = sellPerMille} 挂牌， 储备上限 = 该 GOV
 * 对该币种累计发行量的 500‰。★ 没定过汇率的世界<b>完全没有外汇面</b>（逐值退回）。
 */
public final class GovSetFxRateTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.setFxRate";

  /** 本工具提交的唯一命令类型（与 handler 的 {@code TYPE} 同一个拼写点）。 */
  private static final String COMMAND_TYPE = EconomySetOfficialRateHandler.TYPE;

  /** 官方汇率是世界级政府表的一条（没有格路径 ⇒ 命名空间级断言）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 政府表是世界级的：路径用通配（DM 侧该命名空间未表态 ⇒ 取工具缺省策略；GM 侧 unlimited）。 */
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
  public GovSetFxRateTool(CoreSimos core, QueryService query, String initiator) {
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
    return "定/改一个 GOV 的官方汇率（economy.SetOfficialRate 的窄封装）：币对 base/quote + 双边报价 "
        + "buyPerMille/sellPerMille（微刻度定点，per-mille：每 1000 个 base 最小单位应付多少 quote 最小单位；"
        + "1000 = 最小单位 1:1）。参数 {govUnitId?(GM 必填；决策人省略=自己的 GOV，给了必须等于自己的), base(必填), "
        + "quote(必填), buyPerMille(必填 >0), sellPerMille(必填 >0), reason?, preview?(缺省 true=只读), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "★ 后果：该币对的外汇市场被激活 —— 政府外汇窗口按 bidP=buyPerMille / askP=sellPerMille 挂牌，"
        + "储备上限 = 该 GOV 对该币种累计发行量的 500‰；家户以官方报价为锚挂单（实际汇率 = 成交的加权均价，"
        + "是读数、不落盘，未定汇率的世界没有外汇面）。★ 需 GM 在审批面点头（敏感工具）；★ 只能作用于自己所属 GOV。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSupport.prop("string", "报价的 GOV 单位 id（GM 必填；决策人省略 = 自己的 GOV，给出别的 GOV ⇒ 越权拒）"));
    props.put("base", ToolSupport.prop("string", "标的币 id（政府买入/卖出的那一种；必须在世界词表里）"));
    props.put("quote", ToolSupport.prop("string", "计价币 id（必须在世界词表里；不得等于 base）"));
    props.put(
        "buyPerMille",
        ToolSupport.prop(
            "integer", "政府买入 base 的报价（per-mille：每 1000 个 base 最小单位付多少 quote 最小单位；> 0）"));
    props.put(
        "sellPerMille",
        ToolSupport.prop("integer", "政府卖出 base 的报价（同量纲；> 0；与买入价相反（buy ≥ sell）⇒ 窗口两侧都停做）"));
    props.put("reason", ToolSupport.prop("string", "审计原因（可选；给出则必须非空白）"));
    props.put(
        "preview",
        ToolSupport.prop("boolean", "true（缺省）= 只读预览；false = 提交 economy.SetOfficialRate"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("base", "quote", "buyPerMille", "sellPerMille"));
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
        "定官方汇率 "
            + args.get("base")
            + "/"
            + args.get("quote")
            + " buy="
            + args.get("buyPerMille")
            + "‰ sell="
            + args.get("sellPerMille")
            + "‰ govUnitId="
            + args.getOrDefault("govUnitId", "(身份派生)")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + "（激活该币对的外汇市场，需 GM 审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String base = ToolSupport.requiredText(args, "base");
      String quote = ToolSupport.requiredText(args, "quote");
      long buyPerMille = ToolSupport.requiredLong(args, "buyPerMille");
      long sellPerMille = ToolSupport.requiredLong(args, "sellPerMille");
      String reason = ToolSupport.optionalText(args, "reason", null);
      if (buyPerMille <= 0L || sellPerMille <= 0L) {
        throw new IllegalArgumentException(
            "buyPerMille/sellPerMille 必须 > 0（per-mille；0 报价 = 说不出价）: "
                + buyPerMille
                + "/"
                + sellPerMille);
      }
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
      CurrencyId currencyBase = new CurrencyId(base);
      CurrencyId currencyQuote = new CurrencyId(quote);
      // ★ 工具侧先给同一条具名拒（handler 里还会再判一次；工具侧只是更早、更可读）。
      if (currencyBase.equals(currencyQuote)) {
        throw new IllegalArgumentException("base 与 quote 不得是同一种钱（同币对没有汇率）: " + base);
      }
      if (!data.currencies().containsKey(currencyBase)) {
        throw new IllegalArgumentException("base 币种未在世界词表里定义: " + base);
      }
      if (!data.currencies().containsKey(currencyQuote)) {
        throw new IllegalArgumentException("quote 币种未在世界词表里定义: " + quote);
      }
      // ★★ 政府身份必须**从 GOV 单位 id 派生**（{@link GovernmentIds#ofUnit}；与 {@code GovRenameCurrencyTool} /
      //   {@code GovIssueMoneyTool} 同一手法）：{@code governments} 的键是 {@link GovernmentId}，而
      //   {@code target.govId()} 是 {@code UnitId}（GOV **单位**的稳定 id）—— 两者是互不相等的两种记录类型，
      //   直接 get 恒返回 null ⇒ 改前 {@code previousBuy/SellPerMille} 两栏**永远是 null**（币对现值丢读）。
      //   SpotBugs GC_UNRELATED_TYPES（govSetFxRate 那条）报的正是这里：**真类型混淆，不是参数命名问题**。
      GovernmentId governmentId = GovernmentIds.ofUnit(target.govId().value());
      Government government = data.governments().get(governmentId);
      OfficialRate existing =
          government == null
              ? null
              : government.officialRates().get(OfficialRate.keyOf(currencyBase, currencyQuote));
      String payloadJson =
          payload(target.govId().value(), base, quote, buyPerMille, sellPerMille, reason);
      Map<String, Object> view =
          view(preview, target, base, quote, buyPerMille, sellPerMille, existing, payloadJson);
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
          "TOOL_ERROR", "定官方汇率失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 命令载荷（字段名与 handler 的解析契约一致；{@code govUnitId} 取身份派生后的值）。 */
  private static String payload(
      String govUnitId,
      String base,
      String quote,
      long buyPerMille,
      long sellPerMille,
      String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", govUnitId);
    payload.put("base", base);
    payload.put("quote", quote);
    payload.put("buyPerMille", buyPerMille);
    payload.put("sellPerMille", sellPerMille);
    if (reason != null) {
      payload.put("reason", reason);
    }
    return ToolSupport.json(payload);
  }

  /** preview / apply 共用的视图（含币对现值与储备上限口径，便于调用方预判后果）。 */
  private static Map<String, Object> view(
      boolean preview,
      GovToolSupport.GovTarget target,
      String base,
      String quote,
      long buyPerMille,
      long sellPerMille,
      OfficialRate existing,
      String payloadJson) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("pair", base + "|" + quote);
    view.put("base", base);
    view.put("quote", quote);
    view.put("buyPerMille", buyPerMille);
    view.put("sellPerMille", sellPerMille);
    view.put("previousBuyPerMille", existing == null ? null : existing.buyPerMille());
    view.put("previousSellPerMille", existing == null ? null : existing.sellPerMille());
    view.put(
        "windowEffect",
        "政府外汇窗口 bidP=" + buyPerMille + "‰ askP=" + sellPerMille + "‰；储备上限 = 累计发行量 × 500‰");
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

  /** ★ 供测试/探针引用的政府 id 形态（与 handler 的同一拼写点）。 */
  static GovernmentId governmentIdOf(String govUnitId) {
    return GovernmentIds.ofUnit(govUnitId);
  }
}
