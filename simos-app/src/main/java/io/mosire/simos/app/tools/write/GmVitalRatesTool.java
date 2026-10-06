package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.population.SocialVitalRates;
import io.mosire.simos.social.spi.SetGlobalVitalRatesHandler;
import io.mosire.simos.social.spi.SetHouseholdVitalRatesHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ★★ {@code simos.gm.vitalRates}（D4，2026-10-22）：<b>Social 生死率的 GM 查看/设置窄工具</b>。
 *
 * <p>★ <b>动作</b>：
 *
 * <ul>
 *   <li>{@code view}（缺省）：{@code householdId?}。不带 ⇒ 读全局默认 {@code
 *       SocialData.vitalRates().globalDefaults()}；带 ⇒ 读该家户覆盖 + 逐键生效值（家户覆盖 ?? 全局默认， 与 {@code
 *       SocialData.findVitalRate} 同口径）；
 *   <li>{@code setGlobal}：整体替换全局默认表——preview 纯构造 {@link SocialVitalRates}，apply 提交新命令 {@code
 *       social.SetGlobalVitalRates}；
 *   <li>{@code setHousehold}：复用既有 {@code social.SetHouseholdVitalRates} 的载荷语义（整体替换家户覆盖）。
 * </ul>
 *
 * <p>★ <b>率载荷</b>：{@code rates = [{bracketId, sex(MALE|FEMALE), birthRatePerMillionPerTick?,
 * deathRatePerMillionPerTick?}]}；缺失 ⇒ 空表；两个率缺省 0；负数 / 重复 {@code (bracketId, sex)} 由契约类型具名拒； 单位
 * ppm/tick。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；只写 social 命名空间（声明 social UNRESTRICTED）。
 * 工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。
 */
public final class GmVitalRatesTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.vitalRates";

  /** 本工具只写 social 命名空间（GM 侧 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest SOCIAL_WRITE =
      ResourceManifest.of(ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  public GmVitalRatesTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  /** setGlobal/setHousehold 的 reason 属于命令载荷，由各自动作解析；view 不需要。 */
  @Override
  protected boolean requiresReason() {
    return false;
  }

  @Override
  public String description() {
    return "GM 查看/设置 Social 出生/死亡率（ppm/tick）：action=view(缺省)/setGlobal/setHousehold。"
        + "view：householdId? 不带 ⇒ 全局默认表；带 ⇒ 家户覆盖 + 逐键生效值（覆盖 ?? 全局）。"
        + "setGlobal：{rates:[{bracketId(0-14|15-59|60+), sex(MALE|FEMALE), birthRatePerMillionPerTick?,"
        + " deathRatePerMillionPerTick?}], reason} 整体替换全局默认表（social.SetGlobalVitalRates）。"
        + "setHousehold：{householdId, rates:[…], reason} 整体替换该家户覆盖（social.SetHouseholdVitalRates）。"
        + "rates 缺失=清空；两个率缺省 0；负数/重复 (bracketId,sex) 具名拒。preview(缺省 true)=只算不写；"
        + "preview=false 提交一条命令=一条 revision。返回 {preview, submitted, action, scope/householdId,"
        + " ratesBefore/ratesAfter, effective?, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("action", ToolSupport.prop("string", "view（缺省）| setGlobal | setHousehold"));
    props.put("householdId", ToolSupport.prop("string", "view 可选 / setHousehold 必填：家户稳定 id"));
    props.put(
        "rates",
        ToolSupport.prop(
            "array",
            "[{bracketId,sex(MALE|FEMALE),birthRatePerMillionPerTick?,deathRatePerMillionPerTick?}]"
                + "（setGlobal/setHousehold 的整表；缺失=清空；两个率缺省 0；负数/重复 (bracketId,sex) 拒）"));
    props.put("reason", ToolSupport.prop("string", "setGlobal/setHousehold 必填：设率原因（非空白）"));
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
    return SOCIAL_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources() {
    return WRITE_RESOURCES;
  }

  @Override
  protected ToolResult run(Request request) {
    Map<String, Object> args = request.args();
    String action = ToolSupport.optionalText(args, "action", "view").toLowerCase(Locale.ROOT);
    SocialData base = ToolSupport.socialData(request.state());
    return switch (action) {
      case "view" -> runView(base, args);
      case "setglobal" -> runSetGlobal(request, base);
      case "sethousehold" -> runSetHousehold(request, base);
      default ->
          throw new IllegalArgumentException(
              "参数 action 只认 view | setGlobal | setHousehold: " + action);
    };
  }

  private static ToolResult runView(SocialData base, Map<String, Object> args) {
    String householdText = ToolSupport.optionalText(args, "householdId", null);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "view");
    if (householdText == null) {
      view.put("scope", "global");
      view.put("rates", ratesPayload(base.vitalRates().globalDefaults().rates()));
      return preview(view);
    }
    HouseholdId id = HouseholdId.parse(householdText);
    Household household = base.households().get(id);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + id.value());
    }
    view.put("scope", "household");
    view.put("householdId", id.value());
    view.put("rates", ratesPayload(household.vitalRates().rates()));
    view.put(
        "effective", effectiveRates(household.vitalRates(), base.vitalRates().globalDefaults()));
    return preview(view);
  }

  private ToolResult runSetGlobal(Request request, SocialData base) {
    Map<String, Object> args = request.args();
    List<HouseholdVitalRate> rates = vitalRatesArg(args, "rates");
    String reason = ToolSupport.requiredText(args, "reason");
    HouseholdVitalRates table = new HouseholdVitalRates(rates);
    SocialData projected = base.withVitalRates(new SocialVitalRates(table));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rates", ratesPayload(rates));
    payload.put("reason", reason);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "setGlobal");
    view.put("scope", "global");
    view.put("ratesBefore", ratesPayload(base.vitalRates().globalDefaults().rates()));
    view.put("ratesAfter", ratesPayload(projected.vitalRates().globalDefaults().rates()));
    view.put("reason", reason);
    return finishWrite(request, SetGlobalVitalRatesHandler.TYPE, payload, view);
  }

  private ToolResult runSetHousehold(Request request, SocialData base) {
    Map<String, Object> args = request.args();
    HouseholdId id = HouseholdId.parse(ToolSupport.requiredText(args, "householdId"));
    Household household = base.households().get(id);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + id.value());
    }
    List<HouseholdVitalRate> rates = vitalRatesArg(args, "rates");
    String reason = ToolSupport.requiredText(args, "reason");
    HouseholdVitalRates table = new HouseholdVitalRates(rates);
    SocialData projected = HouseholdBook.setVitalRates(base, id, table, reason);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("householdId", id.value());
    payload.put("rates", ratesPayload(rates));
    payload.put("reason", reason);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "setHousehold");
    view.put("householdId", id.value());
    view.put("ratesBefore", ratesPayload(household.vitalRates().rates()));
    view.put("ratesAfter", ratesPayload(projected.requireHousehold(id).vitalRates().rates()));
    view.put("reason", reason);
    return finishWrite(request, SetHouseholdVitalRatesHandler.TYPE, payload, view);
  }

  /** setGlobal/setHousehold 的共同收口：planOnly / preview / submit 一条命令。 */
  private ToolResult finishWrite(
      Request request, String type, Map<String, Object> payload, Map<String, Object> view) {
    if (request.planOnly()) {
      return planOnly(request, type, payload);
    }
    view.put("commandsPreview", List.of(commandPreview(type, payload)));
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, type, payload, view);
  }

  /**
   * 逐键生效值 = 家户覆盖 ?? 全局默认（与 {@code SocialData.findVitalRate} 同口径）：先按覆盖表顺序，再补全局独有的键； 每行显式给 {@code
   * source=household|global}。
   */
  private static List<Map<String, Object>> effectiveRates(
      HouseholdVitalRates override, HouseholdVitalRates global) {
    Map<String, HouseholdVitalRate> byKey = new LinkedHashMap<>();
    for (HouseholdVitalRate rate : override.rates()) {
      byKey.putIfAbsent(keyOf(rate), rate);
    }
    for (HouseholdVitalRate rate : global.rates()) {
      byKey.putIfAbsent(keyOf(rate), rate);
    }
    List<Map<String, Object>> out = new ArrayList<>(byKey.size());
    for (Map.Entry<String, HouseholdVitalRate> entry : byKey.entrySet()) {
      HouseholdVitalRate rate = entry.getValue();
      Map<String, Object> row = rateView(rate);
      row.put(
          "source",
          override.find(rate.bracketId(), rate.sex()).isPresent() ? "household" : "global");
      out.add(row);
    }
    return List.copyOf(out);
  }

  private static String keyOf(HouseholdVitalRate rate) {
    return rate.bracketId() + "/" + rate.sex().name();
  }

  private static Map<String, Object> rateView(HouseholdVitalRate rate) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("bracketId", rate.bracketId());
    row.put("sex", rate.sex().name());
    row.put("birthRatePerMillionPerTick", rate.birthRatePerMillionPerTick());
    row.put("deathRatePerMillionPerTick", rate.deathRatePerMillionPerTick());
    return row;
  }
}
