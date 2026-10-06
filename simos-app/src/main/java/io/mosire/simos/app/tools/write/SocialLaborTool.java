package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.provisioning.LaborCoefficient;
import io.mosire.simos.social.provisioning.SocialProvisioning;
import io.mosire.simos.social.provisioning.SocialProvisioningEdits;
import io.mosire.simos.social.spi.SetLaborCoefficientHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ {@code simos.social.labor}（2026-10-09 家户结构修复计划 Batch 4）：<b>GM 改/清 social 劳动系数窄工具</b>—— {@code
 * social.SetLaborCoefficient} 的封装（全局默认或单家户覆盖，键 {@code (年龄档, 性别)}，值 {@code milliHoursPerTick}）。
 *
 * <p>★ <b>参数语义</b>（与命令同源）：{@code householdId} 缺席 = 改全局默认；给了 = 改该家户覆盖（家户必须存在）； {@code
 * milliHoursPerTick} 给了 = upsert，缺席 = 清除该家户覆盖键并回落全局（无 householdId ⇒ 命令具名拒， 全局删键不允许）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：preview 与 apply 都先经 {@link SocialProvisioningEdits} 调
 * {@link SocialProvisioning} 的不可变 copy-with 算出目标 provisioning（零状态写入、零 revision）；若 {@code
 * preview=false} 才把同一份载荷提交给 {@link CoreSimos#submitBatch}。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；命令本身标 {@code GmOnlyCommand}，工具名不是命令类型 ⇒
 * 不进 catalog / {@code PAYLOAD_HINTS}。只写 social 命名空间。
 *
 * <p>★ <b>旧档作废、不迁移</b>：只认带第 6 组件 {@code provisioning} 的新档；旧档在状态构造期即具名拒。
 */
public final class SocialLaborTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.social.labor";

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  public SocialLaborTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 改/清社会劳动系数（social.SetLaborCoefficient 窄封装，一条命令 = 一条 revision）："
        + "参数 {householdId?, ageBracket(0-14|15-59|60+), sex(MALE|FEMALE), milliHoursPerTick?, reason, "
        + "preview?(缺省 true=只算不写), branch?, expectedRevision?(preview=false 必填)}。"
        + "householdId 缺席=改全局默认（必须给 milliHoursPerTick）；给了=改家户覆盖；"
        + "milliHoursPerTick 缺席=清除该家户覆盖键并回落全局（全局不允许删键）。"
        + "返回 {preview, submitted, scope, householdId?, key{ageBracket,sex}, before, after, "
        + "commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSupport.prop("string", "家户稳定 id；缺席 = 改全局默认，给了 = 改该家户覆盖（家户必须存在）"));
    props.put("ageBracket", ToolSupport.prop("string", "年龄档 0-14 | 15-59 | 60+（必填）"));
    props.put("sex", ToolSupport.prop("string", "MALE | FEMALE（必填）"));
    props.put(
        "milliHoursPerTick",
        ToolSupport.prop(
            "integer", "每人每 tick 的毫小时预算（≥ 0）；给了 = upsert，缺席 = 清除家户覆盖键（无 householdId ⇒ 拒）"));
    props.put("reason", ToolSupport.prop("string", "改动原因（必填非空白；进命令载荷与事件）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("ageBracket", "sex", "reason"));
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
    String householdText = ToolSupport.optionalText(args, "householdId", null);
    HouseholdId householdId = householdText == null ? null : HouseholdId.parse(householdText);
    AgeBracket ageBracket = ageBracketArg(args, "ageBracket");
    Sex sex = sexArg(args, "sex");
    Long milliHoursPerTick = ToolSupport.optionalLong(args, "milliHoursPerTick");
    SocialData projected =
        milliHoursPerTick == null
            ? SocialProvisioningEdits.clearLabor(base, householdId, ageBracket, sex)
            : SocialProvisioningEdits.setLabor(
                base, householdId, ageBracket, sex, milliHoursPerTick);

    Map<String, Object> payload = new LinkedHashMap<>();
    if (householdId != null) {
      payload.put("householdId", householdId.value());
    }
    payload.put("ageBracket", ageBracket.key());
    payload.put("sex", sex.name());
    if (milliHoursPerTick != null) {
      payload.put("milliHoursPerTick", milliHoursPerTick);
    }
    payload.put("reason", request.reason());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", householdId == null ? "global" : "household");
    if (householdId != null) {
      view.put("householdId", householdId.value());
    }
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("ageBracket", ageBracket.key());
    key.put("sex", sex.name());
    view.put("key", key);
    view.put(
        "before",
        coefficientView(effectiveLabor(base.provisioning(), householdId, ageBracket, sex)));
    view.put(
        "after",
        coefficientView(effectiveLabor(projected.provisioning(), householdId, ageBracket, sex)));
    view.put("commandsPreview", List.of(commandPreview(SetLaborCoefficientHandler.TYPE, payload)));
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, SetLaborCoefficientHandler.TYPE, payload, view);
  }

  /** 有效劳动系数：家户覆盖优先、全局默认兜底；都没有 ⇒ 空（清除覆盖后的 after 可以是这种形态）。 */
  private static Optional<LaborCoefficient> effectiveLabor(
      SocialProvisioning provisioning, HouseholdId householdId, AgeBracket ageBracket, Sex sex) {
    return householdId == null
        ? provisioning.globalLabor(ageBracket, sex)
        : provisioning.findLaborOptionally(householdId, ageBracket, sex);
  }

  /** 系数视图：存在 ⇒ 值；不存在 ⇒ {@code {present:false}}（不填 0，避免把"没有这条键"伪装成 0 劳动）。 */
  private static Map<String, Object> coefficientView(Optional<LaborCoefficient> coefficient) {
    Map<String, Object> view = new LinkedHashMap<>();
    if (coefficient.isEmpty()) {
      view.put("present", false);
      return view;
    }
    LaborCoefficient value = coefficient.get();
    view.put("present", true);
    view.put("milliHoursPerTick", value.milliHoursPerTick());
    return view;
  }
}
