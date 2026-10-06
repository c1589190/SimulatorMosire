package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
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
import io.mosire.simos.social.spi.SetHouseholdVitalRatesHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.social.household.rates}（S3a，2026-10-09）：<b>GM 设置家户出生/死亡率窄工具</b>—— {@code
 * social.SetHouseholdVitalRates} 的封装（率表<b>整体替换</b>）。
 *
 * <p>★ <b>载荷</b>：{@code rates = [{bracketId, sex, birthRatePerMillionPerTick?,
 * deathRatePerMillionPerTick?}]}；缺失 ⇒ 空表（= 清空率表）；两个率缺省 0（= 这一档按 0 率结算）；负数 / 重复 {@code (bracketId,
 * sex)} 由契约类型具名拒。单位 ppm/tick。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：preview 先调 {@link HouseholdBook#setVitalRates} 并把前后率表折进视图，
 * <b>一个字节都不写</b>；apply 组一条命令走 {@link CoreSimos#submitBatch}。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；只写 social 命名空间。
 */
public final class SocialHouseholdRatesTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.social.household.rates";

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  public SocialHouseholdRatesTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 设置家户出生/死亡率（social.SetHouseholdVitalRates 窄封装，整体替换率表；一条命令 = 一条 revision）："
        + "参数 {householdId, rates:[{bracketId(如 0-14|15-59|60+), sex(MALE|FEMALE), "
        + "birthRatePerMillionPerTick?, deathRatePerMillionPerTick?}], reason, preview?(缺省 true=只算不写), branch?, "
        + "expectedRevision?(preview=false 必填)}。rates 缺失 ⇒ 清空率表；两个率缺省 0；负数/重复 (bracketId,sex) 拒。"
        + "返回 {preview, submitted, householdId, ratesBefore, ratesAfter, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSupport.prop("string", "家户稳定 id（必填；必须存在）"));
    props.put(
        "rates",
        ToolSupport.prop(
            "array",
            "[{bracketId,sex(MALE|FEMALE),birthRatePerMillionPerTick?,deathRatePerMillionPerTick?}]（整体替换；"
                + "缺失=清空；两个率缺省 0；负数/重复 (bracketId,sex) 拒）"));
    props.put("reason", ToolSupport.prop("string", "设率原因（必填非空白；进命令载荷与事件）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("householdId", "reason"));
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
    SocialData base = ToolSupport.socialData(request.state());
    Map<String, Object> args = request.args();
    HouseholdId id = HouseholdId.parse(ToolSupport.requiredText(args, "householdId"));
    Household household = base.households().get(id);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + id);
    }
    List<HouseholdVitalRate> rates = vitalRatesArg(args, "rates");
    HouseholdVitalRates vitalRates = new HouseholdVitalRates(rates);
    SocialData projected = HouseholdBook.setVitalRates(base, id, vitalRates, request.reason());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("householdId", id.value());
    payload.put("rates", ratesPayload(rates));
    payload.put("reason", request.reason());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", id.value());
    view.put("ratesBefore", ratesPayload(household.vitalRates().rates()));
    view.put("ratesAfter", ratesPayload(projected.requireHousehold(id).vitalRates().rates()));
    view.put(
        "commandsPreview", List.of(commandPreview(SetHouseholdVitalRatesHandler.TYPE, payload)));
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, SetHouseholdVitalRatesHandler.TYPE, payload, view);
  }
}
