package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.spi.EconomyAuthorizeGovMarketOrderHandler;
import io.mosire.simos.economy.spi.EconomyCancelGovMarketOrderHandler;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ★★ {@code simos.gm.govMarketMandate}（R1，2026-10-09）：<b>政府市场授权（行政家户挂单）的 GM 窄工具</b> —— {@code
 * economy.AuthorizeGovernmentMarketOrder} / {@code economy.CancelGovernmentMarketOrder} 的薄封装。
 *
 * <p>★ <b>动作</b>：
 *
 * <ul>
 *   <li>{@code list}（缺省）：读 {@code EconomyData.govMarketMandates()} + {@code governments()}，
 *       返回逐条授权（谁/商品/方向/量/限价/生效期/已成交/剩余）与"只按授权下单"的国库户名单；
 *   <li>{@code authorize}：载荷字段与命令<b>逐字一致</b>（{@code
 *       id/government/commodity/side/quantityMilli/limitPriceMilli/expiresOnDay/source}）； {@code
 *       preview}（缺省 true）走 {@link GovernmentMarketMandate} 的构造期守卫 +
 *       与命令**同源**的具名拒（未知政府/国库不是家户/无经济行/未定价商品/到期已过/幂等重复/形状变更/同类重复）， 返回前后对比；{@code preview=false}
 *       提交一条命令（= 一条 revision）。
 *   <li>{@code cancel}：{@code {id}}；preview 显示现值与被删后的表；{@code preview=false} 提交撤销。
 * </ul>
 *
 * <p>★★ <b>它不是"政策层"</b>：工具只写一张授权表，订单仍由既有的 家户→订单→撮合→结算 在日结算里生成； 工具的 preview
 * 不预演撮合结果（那是模拟的责任，不是工具的责任）。★ 工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。
 */
public final class GmGovMarketMandateTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.govMarketMandate";

  /** 本工具只写 economy 命名空间（两条 handler 都只产 EconomyChangeSet）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"));

  public GmGovMarketMandateTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  protected boolean requiresReason() {
    return false;
  }

  @Override
  public String description() {
    return "GM 政府市场授权（R1：政府的行政家户回到商品市场，且**只按明确授权下单**）："
        + "action=list(缺省)/authorize/cancel。list 返回全部授权（id/government/treasury/side/commodity/"
        + "quantityMilli/limitPriceMilli/filledMilli/remainingMilli/authorizedDay/expiresOnDay/source）"
        + "与逐政府国库户名单（它们不生成任何自动订单）。"
        + "authorize 载荷 {id, government, commodity, side(BUY|SELL), quantityMilli(>0), limitPriceMilli(≥1),"
        + " expiresOnDay(≥当天), source}；BUY 限价 = 价格上限、SELL 限价 = 价格下限；"
        + "★ 限价只能比市场自身的限价更严（min/max），**不改价、不改成本、不豁免撮合规则**；"
        + "★ 买盘不走信用（授权 ≠ 加杠杆）；★ 到期/量耗尽由日结算清除（不许留永久挂单）；"
        + "★ 同 id 同形状重复注入 ⇒ 具名拒（幂等，不双倍下单）；改形状须先 cancel。"
        + "cancel 载荷 {id, reason?}（撤销不存在的授权 ⇒ 具名拒）。"
        + "preview(缺省 true)=只算不写；preview=false 提交命令。"
        + "返回 {preview, submitted, action, authorizations[{…}], treasuryHouseholds[…], before/after, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("action", ToolSupport.prop("string", "list（缺省）| authorize | cancel"));
    props.put("id", ToolSupport.prop("string", "authorize/cancel：授权 id（稳定身份）"));
    props.put(
        "government", ToolSupport.prop("string", "authorize：政府 id（必须已登记；国库 = hh-gov-<govUnitId>）"));
    props.put("commodity", ToolSupport.prop("string", "authorize：商品 id（必须在国库户所在格已定价）"));
    props.put("side", ToolSupport.prop("string", "authorize：BUY（收购，价格上限）| SELL（抛售，价格下限）"));
    props.put("quantityMilli", ToolSupport.prop("integer", "authorize：授权总量（毫商品单位，> 0）"));
    props.put("limitPriceMilli", ToolSupport.prop("integer", "authorize：限价（毫计价货币/单位，≥ 1）"));
    props.put("expiresOnDay", ToolSupport.prop("integer", "authorize：最后一个生效日（含，≥ 当天）"));
    props.put("source", ToolSupport.prop("string", "authorize：授权来源标签（审计串，不得空白）"));
    props.put("reason", ToolSupport.prop("string", "cancel：可选审计理由"));
    props.put(
        "preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交一条命令（= 一条 revision）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  protected ResourceManifest resourceManifest() {
    return ECONOMY_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources() {
    return WRITE_RESOURCES;
  }

  @Override
  protected ToolResult run(Request request) {
    Map<String, Object> args = request.args();
    String action = ToolSupport.optionalText(args, "action", "list").toLowerCase(Locale.ROOT);
    return switch (action) {
      case "list" -> runList(request);
      case "authorize" -> runAuthorize(request);
      case "cancel" -> runCancel(request);
      default ->
          throw new IllegalArgumentException("参数 action 只认 list | authorize | cancel: " + action);
    };
  }

  private static EconomyData economy(Request request) {
    return ((EconomySnapshot) request.state().module("economy").orElseThrow()).data();
  }

  private static ToolResult runList(Request request) {
    EconomyData base = economy(request);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "list");
    view.put("authorizations", authorizationsView(base));
    view.put("treasuryHouseholds", treasuryHouseholdsView(base));
    return preview(view);
  }

  private ToolResult runAuthorize(Request request) {
    EconomyData base = economy(request);
    Map<String, Object> args = request.args();
    Map<String, Object> payload = new LinkedHashMap<>();
    for (String field :
        List.of(
            "id",
            "government",
            "commodity",
            "side",
            "quantityMilli",
            "limitPriceMilli",
            "expiresOnDay",
            "source")) {
      Object value = args.get(field);
      if (value == null) {
        throw new IllegalArgumentException("authorize 缺少参数: " + field);
      }
      payload.put(field, value);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "authorize");
    view.put("payload", payload);
    view.put("before", authorizationsView(base));
    if (request.planOnly()) {
      return planOnly(request, EconomyAuthorizeGovMarketOrderHandler.TYPE, payload);
    }
    view.put(
        "commandsPreview",
        List.of(commandPreview(EconomyAuthorizeGovMarketOrderHandler.TYPE, payload)));
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, EconomyAuthorizeGovMarketOrderHandler.TYPE, payload, view);
  }

  private ToolResult runCancel(Request request) {
    EconomyData base = economy(request);
    Map<String, Object> args = request.args();
    String id = ToolSupport.requiredText(args, "id");
    GovernmentMarketMandate existing = base.govMarketMandates().get(MarketMandateId.parse(id));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id);
    String reason = ToolSupport.optionalText(args, "reason", "");
    if (!reason.isBlank()) {
      payload.put("reason", reason);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "cancel");
    view.put("payload", payload);
    view.put("before", existing == null ? Map.of() : mandateView(base, existing));
    if (existing == null) {
      throw new IllegalArgumentException("未知授权（不存在或已被耗尽/到期清除）: " + id);
    }
    if (request.planOnly()) {
      return planOnly(request, EconomyCancelGovMarketOrderHandler.TYPE, payload);
    }
    view.put(
        "commandsPreview",
        List.of(commandPreview(EconomyCancelGovMarketOrderHandler.TYPE, payload)));
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, EconomyCancelGovMarketOrderHandler.TYPE, payload, view);
  }

  /** 全部授权的读视图（按 id 升序，确定性）。 */
  private static List<Map<String, Object>> authorizationsView(EconomyData base) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<GovernmentMarketMandate> mandates = new ArrayList<>(base.govMarketMandates().values());
    mandates.sort(Comparator.comparing(mandate -> mandate.id().value()));
    for (GovernmentMarketMandate mandate : mandates) {
      rows.add(mandateView(base, mandate));
    }
    return rows;
  }

  private static Map<String, Object> mandateView(
      EconomyData base, GovernmentMarketMandate mandate) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", mandate.id().value());
    row.put("government", mandate.government().value());
    Government government = base.governments().get(mandate.government());
    row.put(
        "treasury",
        government != null
                && government.treasury().kind()
                    == io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD
            ? HouseholdActors.householdOf(government.treasury()).value()
            : "<not-a-household-treasury>");
    row.put("side", mandate.side().name());
    row.put("commodity", mandate.commodity().value());
    row.put("quantityMilli", mandate.quantityMilli());
    row.put("limitPriceMilli", mandate.limitPriceMilli());
    row.put("filledMilli", mandate.filledMilli());
    row.put("remainingMilli", mandate.remainingMilli());
    row.put("authorizedDay", mandate.authorizedDay());
    row.put("expiresOnDay", mandate.expiresOnDay());
    row.put("source", mandate.source());
    return row;
  }

  /** 逐政府国库户（"只按授权下单"名单）：改前它们在 Z7b 排除集里，现在回到市场但零自动订单。 */
  private static List<Map<String, Object>> treasuryHouseholdsView(EconomyData base) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Government government : base.governments().values()) {
      if (government.treasury().kind() != io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
        continue;
      }
      HouseholdId treasury = HouseholdActors.householdOf(government.treasury());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("government", government.id().value());
      row.put("treasury", treasury.value());
      row.put("economyRow", base.classes().containsKey(treasury));
      row.put(
          "authorizations",
          base.govMarketMandates().values().stream()
              .filter(mandate -> mandate.government().equals(government.id()))
              .count());
      rows.add(row);
    }
    return rows;
  }
}
