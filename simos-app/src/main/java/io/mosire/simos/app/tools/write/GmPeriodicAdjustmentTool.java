package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.PeriodicHouseholdAdjustmentExecutor;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.spi.EconomyRemovePeriodicAdjustmentHandler;
import io.mosire.simos.economy.spi.EconomyUpsertPeriodicAdjustmentHandler;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ {@code simos.gm.periodicAdjustment}（D4，2026-10-22）：<b>P4a 周期家户库存扣增规则表的 GM 窄工具</b> —— {@code
 * economy.UpsertHouseholdPeriodicAdjustment} / {@code economy.RemoveHouseholdPeriodicAdjustment}
 * 两条命令的只读 + 预览 + 提交入口。
 *
 * <p>★ <b>动作</b>：
 *
 * <ul>
 *   <li>{@code list}（缺省）：按 id 升序读 {@code EconomyData.periodicAdjustments()}；可用 {@code
 *       payer?}/{@code payee?}/{@code reason?} 过滤；
 *   <li>{@code due}：按<b>当前 tick</b> 读将到期规则——到期判据复用 {@link
 *       PeriodicHouseholdAdjustmentExecutor#isDue}（唯一执行语义，本工具不另写第二份公式）；同样支持三个过滤；
 *   <li>{@code upsert} / {@code remove}：载荷字段与两个命令 handler <b>逐字一致</b>；{@code preview}（缺省 true）
 *       只做形状/引用/到期窗口校验并返回前后对比与 {@code commandsPreview}；{@code preview=false} 提交对应命令 （一条命令 = 一条
 *       revision）。
 * </ul>
 *
 * <p>★★ <b>preview 的 {@link HouseholdPeriodicAdjustment} 构造就是全部形状/到期窗口校验</b>（id/payer/payee
 * 非空、payer≠payee、逐值 &gt; 0、至少一腿、periodDays/phaseDay/startsOnDay/expires 不变量）；引用校验 = upsert 的
 * payer/payee 家户在 Social 切片里必须存在（"未知家户"具名拒，零 revision）；{@code remove} 的目标不存在 ⇒ 具名拒。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；只写 economy 命名空间（GM 侧 unlimited ⇒ 声明
 * {@code economy} UNRESTRICTED）。工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。
 */
public final class GmPeriodicAdjustmentTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.periodicAdjustment";

  /** 本工具只写 economy 命名空间（GM 侧 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"));

  public GmPeriodicAdjustmentTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  /** 本工具的 reason 属于各自载荷（upsert 必填、remove 可选），没有统一的工具级审计 reason。 */
  @Override
  protected boolean requiresReason() {
    return false;
  }

  @Override
  public String description() {
    return "GM 周期家户库存扣增规则（P4a；economy.Upsert/RemoveHouseholdPeriodicAdjustment 的窄封装）："
        + "action=list(缺省)/due/upsert/remove。list/due 支持 payer?/payee?/reason? 过滤，按 id 升序；"
        + "due 按当前 tick、复用 PeriodicHouseholdAdjustmentExecutor.isDue 的到期口径。"
        + "upsert 载荷 {id, payer, payee?, goodsPerCycle?{商品:整数>0}, moneyPerCycle?{币种:整数>0} 至少一腿,"
        + " reason(military_salary|jurisdiction_tax|admin_upkeep|corvee), periodDays(>0),"
        + " phaseDay([0,periodDays)), startsOnDay(≥0), expiresOnDay?(缺省=永久且必须 ≥ startsOnDay), policySource}；"
        + "remove 载荷 {id, reason?}，不存在 ⇒ 具名拒。preview(缺省 true)=只算不写；preview=false 提交一条命令=一条 revision。"
        + "返回 {preview, submitted, action, rule(s), ruleBefore/ruleAfter, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("action", ToolSupport.prop("string", "list（缺省）| due | upsert | remove"));
    props.put("payer", ToolSupport.prop("string", "list/due 过滤 + upsert 必填：被扣方家户 id"));
    props.put("payee", ToolSupport.prop("string", "list/due 过滤 + upsert 可选：收款家户 id（缺省=明确 sink）"));
    props.put(
        "goodsPerCycle",
        ToolSupport.prop("object", "upsert 可选：{商品 id: 每周期请求量（整数 > 0）}，与 moneyPerCycle 至少一腿非空"));
    props.put(
        "moneyPerCycle",
        ToolSupport.prop("object", "upsert 可选：{币种 id: 每周期请求量（整数 > 0）}，与 goodsPerCycle 至少一腿非空"));
    props.put(
        "reason",
        ToolSupport.prop(
            "string",
            "list/due 过滤 + upsert 必填 DeductionReason（military_salary|jurisdiction_tax|admin_upkeep|corvee）"
                + " + remove 可选：删除原因（只进日志）"));
    props.put("id", ToolSupport.prop("string", "upsert/remove 必填：规则稳定 id"));
    props.put("periodDays", ToolSupport.prop("integer", "upsert 必填：周期天数 > 0"));
    props.put("phaseDay", ToolSupport.prop("integer", "upsert 必填：相位日 ∈ [0, periodDays)"));
    props.put("startsOnDay", ToolSupport.prop("integer", "upsert 必填：起始绝对世界日 ≥ 0"));
    props.put(
        "expiresOnDay", ToolSupport.prop("integer", "upsert 可选：到期绝对世界日（缺省=永久；必须 ≥ startsOnDay）"));
    props.put("policySource", ToolSupport.prop("string", "upsert 必填：审计来源串（非空白，如 gm:p4a）"));
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
    EconomyData base = ToolSupport.economyData(request.state());
    return switch (action) {
      case "list" -> runList(base, args);
      case "due" -> runDue(base, request);
      case "upsert" -> runUpsert(request, base);
      case "remove" -> runRemove(request, base);
      default ->
          throw new IllegalArgumentException(
              "参数 action 只认 list | due | upsert | remove: " + action);
    };
  }

  /** list/due 共用的过滤 + 排序（按 id 升序，读数可复现）。 */
  private static List<HouseholdPeriodicAdjustment> filteredRules(
      EconomyData base, Map<String, Object> args) {
    HouseholdId payer = optionalHouseholdArg(args, "payer");
    HouseholdId payee = optionalHouseholdArg(args, "payee");
    DeductionReason reason = optionalReasonArg(args);
    List<HouseholdPeriodicAdjustment> rules = new ArrayList<>();
    for (HouseholdPeriodicAdjustment rule : base.periodicAdjustments().values()) {
      if (payer != null && !payer.equals(rule.payer())) {
        continue;
      }
      if (payee != null && !rule.payee().filter(payee::equals).isPresent()) {
        continue;
      }
      if (reason != null && reason != rule.reason()) {
        continue;
      }
      rules.add(rule);
    }
    rules.sort(Comparator.comparing(rule -> rule.id().value()));
    return List.copyOf(rules);
  }

  private static ToolResult runList(EconomyData base, Map<String, Object> args) {
    List<HouseholdPeriodicAdjustment> rules = filteredRules(base, args);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "list");
    view.put("count", rules.size());
    view.put("rules", ruleViews(rules));
    return preview(view);
  }

  private static ToolResult runDue(EconomyData base, Request request) {
    long day = request.state().meta().timestamp().tick();
    List<HouseholdPeriodicAdjustment> rules = filteredRules(base, request.args());
    List<HouseholdPeriodicAdjustment> due = new ArrayList<>();
    for (HouseholdPeriodicAdjustment rule : rules) {
      // ★ 到期判据复用唯一实现（PeriodicHouseholdAdjustmentExecutor.isDue），不另写第二份公式。
      if (PeriodicHouseholdAdjustmentExecutor.isDue(rule, day)) {
        due.add(rule);
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "due");
    view.put("day", day);
    view.put("count", due.size());
    view.put("rules", ruleViews(due));
    return preview(view);
  }

  private ToolResult runUpsert(Request request, EconomyData base) {
    Map<String, Object> args = request.args();
    HouseholdPeriodicAdjustment rule = parseRule(args);
    // ★ 引用校验：规则点名的家户必须在 Social 切片里存在（"未知家户"具名拒、零 revision）。
    SocialData social = ToolSupport.socialData(request.state());
    requireHousehold(social, rule.payer());
    if (rule.payee().isPresent()) {
      requireHousehold(social, rule.payee().get());
    }
    HouseholdPeriodicAdjustment before = base.periodicAdjustments().get(rule.id());
    Map<String, Object> payload = rulePayload(rule);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "upsert");
    view.put("id", rule.id().value());
    Map<String, Object> beforeView = before == null ? null : ruleView(before);
    Map<String, Object> afterView = ruleView(rule);
    view.put("ruleBefore", beforeView);
    view.put("ruleAfter", afterView);
    // ★ 契约 §2.1 的视图名 before/after（与 ruleBefore/ruleAfter 同值，避免调用方两套读法）。
    view.put("before", beforeView);
    view.put("after", afterView);
    return submitOrPreview(request, EconomyUpsertPeriodicAdjustmentHandler.TYPE, payload, view);
  }

  private ToolResult runRemove(Request request, EconomyData base) {
    Map<String, Object> args = request.args();
    PeriodicHouseholdAdjustmentId id =
        PeriodicHouseholdAdjustmentId.parse(ToolSupport.requiredText(args, "id"));
    HouseholdPeriodicAdjustment before = base.periodicAdjustments().get(id);
    if (before == null) {
      // ★ 不存在 ⇒ 具名拒（不静默成功；"删了"与"本来就没有"是两件事）。
      throw new IllegalArgumentException("周期家户扣增规则不存在: id=" + id.value());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id.value());
    String reason = ToolSupport.optionalText(args, "reason", null);
    if (reason != null) {
      payload.put("reason", reason);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "remove");
    view.put("id", id.value());
    Map<String, Object> beforeView = ruleView(before);
    view.put("ruleBefore", beforeView);
    view.put("ruleAfter", null);
    // ★ 契约 §2.1 的视图名 before/after（与 ruleBefore/ruleAfter 同值）。
    view.put("before", beforeView);
    view.put("after", null);
    if (reason != null) {
      view.put("reason", reason);
    }
    return submitOrPreview(request, EconomyRemovePeriodicAdjustmentHandler.TYPE, payload, view);
  }

  /** preview / apply / planOnly 的共同收口：同一条载荷；preview 不提交、planOnly 只返回待提交命令批（D3 执行器内部用）。 */
  private ToolResult submitOrPreview(
      Request request, String type, Map<String, Object> payload, Map<String, Object> view) {
    if (request.planOnly()) {
      return planOnly(request, type, payload);
    }
    Map<String, Object> withCommands = new LinkedHashMap<>(view);
    withCommands.put("commandsPreview", List.of(commandPreview(type, payload)));
    if (request.preview()) {
      return preview(withCommands);
    }
    return submitCommand(request, type, payload, withCommands);
  }

  /** parseRule 的唯一输出：形状与到期窗口不变量全部由 {@link HouseholdPeriodicAdjustment} 构造期把守。 */
  private static HouseholdPeriodicAdjustment parseRule(Map<String, Object> args) {
    PeriodicHouseholdAdjustmentId id =
        PeriodicHouseholdAdjustmentId.parse(ToolSupport.requiredText(args, "id"));
    HouseholdId payer = HouseholdId.parse(ToolSupport.requiredText(args, "payer"));
    String payeeText = ToolSupport.optionalText(args, "payee", null);
    Optional<HouseholdId> payee =
        payeeText == null ? Optional.empty() : Optional.of(HouseholdId.parse(payeeText));
    Map<CommodityId, Long> goods = commodityAmountsArg(args, "goodsPerCycle");
    Map<CurrencyId, Long> money = currencyAmountsArg(args, "moneyPerCycle");
    DeductionReason reason = DeductionReason.parse(ToolSupport.requiredText(args, "reason"));
    long periodDays = ToolSupport.requiredLong(args, "periodDays");
    long phaseDay = ToolSupport.requiredLong(args, "phaseDay");
    long startsOnDay = ToolSupport.requiredLong(args, "startsOnDay");
    Long expiresArg = ToolSupport.optionalLong(args, "expiresOnDay");
    OptionalLong expiresOnDay =
        expiresArg == null ? OptionalLong.empty() : OptionalLong.of(expiresArg);
    String policySource = ToolSupport.requiredText(args, "policySource");
    return new HouseholdPeriodicAdjustment(
        id,
        payer,
        payee,
        goods,
        money,
        reason,
        periodDays,
        phaseDay,
        startsOnDay,
        expiresOnDay,
        policySource);
  }

  private static HouseholdId optionalHouseholdArg(Map<String, Object> args, String name) {
    String text = ToolSupport.optionalText(args, name, null);
    return text == null ? null : HouseholdId.parse(text);
  }

  private static DeductionReason optionalReasonArg(Map<String, Object> args) {
    String text = ToolSupport.optionalText(args, "reason", null);
    return text == null ? null : DeductionReason.parse(text);
  }

  /** {@code {商品 id:整数}} 参数 ⇒ 有序表；值非整数 ⇒ 抛（>0 由规则构造期判）。 */
  private static Map<CommodityId, Long> commodityAmountsArg(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是 {\"商品 id\":整数} 对象");
    }
    Map<CommodityId, Long> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的键必须是非空商品 id");
      }
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalArgumentException("参数 " + name + " 的值必须是整数: " + key);
      }
      out.put(CommodityId.parse(key), number.longValue());
    }
    return out;
  }

  /** {@code {币种 id:整数}} 参数 ⇒ 有序表；值非整数 ⇒ 抛（>0 由规则构造期判）。 */
  private static Map<CurrencyId, Long> currencyAmountsArg(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是 {\"币种 id\":整数} 对象");
    }
    Map<CurrencyId, Long> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的键必须是非空币种 id");
      }
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalArgumentException("参数 " + name + " 的值必须是整数: " + key);
      }
      out.put(CurrencyId.parse(key), number.longValue());
    }
    return out;
  }

  /** 规则 ⇒ 命令载荷（字段与两个 handler 的解析逐字对齐）。 */
  private static Map<String, Object> rulePayload(HouseholdPeriodicAdjustment rule) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", rule.id().value());
    payload.put("payer", rule.payer().value());
    rule.payee().ifPresent(payee -> payload.put("payee", payee.value()));
    payload.put("goodsPerCycle", commodityPayload(rule.goodsPerCycle()));
    payload.put("moneyPerCycle", currencyPayload(rule.moneyPerCycle()));
    payload.put("reason", rule.reason().value());
    payload.put("periodDays", rule.periodDays());
    payload.put("phaseDay", rule.phaseDay());
    payload.put("startsOnDay", rule.startsOnDay());
    rule.expiresOnDay().ifPresent(value -> payload.put("expiresOnDay", value));
    payload.put("policySource", rule.policySource());
    return payload;
  }

  /** 规则 ⇒ 只读视图（字段同载荷；sink 的 payee/永久规则的 expiresOnDay 显式 null）。 */
  private static Map<String, Object> ruleView(HouseholdPeriodicAdjustment rule) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", rule.id().value());
    view.put("payer", rule.payer().value());
    view.put("payee", rule.payee().map(HouseholdId::value).orElse(null));
    view.put("goodsPerCycle", commodityPayload(rule.goodsPerCycle()));
    view.put("moneyPerCycle", currencyPayload(rule.moneyPerCycle()));
    view.put("reason", rule.reason().value());
    view.put("periodDays", rule.periodDays());
    view.put("phaseDay", rule.phaseDay());
    view.put("startsOnDay", rule.startsOnDay());
    view.put(
        "expiresOnDay", rule.expiresOnDay().isPresent() ? rule.expiresOnDay().getAsLong() : null);
    view.put("policySource", rule.policySource());
    return view;
  }

  private static List<Map<String, Object>> ruleViews(List<HouseholdPeriodicAdjustment> rules) {
    List<Map<String, Object>> out = new ArrayList<>(rules.size());
    for (HouseholdPeriodicAdjustment rule : rules) {
      out.add(ruleView(rule));
    }
    return List.copyOf(out);
  }

  private static Map<String, Object> commodityPayload(Map<CommodityId, Long> amounts) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : amounts.entrySet()) {
      out.put(entry.getKey().value(), entry.getValue());
    }
    return out;
  }

  private static Map<String, Object> currencyPayload(Map<CurrencyId, Long> amounts) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : amounts.entrySet()) {
      out.put(entry.getKey().value(), entry.getValue());
    }
    return out;
  }
}
