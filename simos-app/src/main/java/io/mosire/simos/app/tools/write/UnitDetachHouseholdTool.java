package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.spi.SetHouseholdLocationHandler;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ★★ {@code simos.unit.detachHousehold}（S3a，2026-10-09）：<b>GM 把家户移出单位窄工具</b>——两条命令<b>同批落一条
 * revision</b>（架构 §3.3）：
 *
 * <ol>
 *   <li>{@code social.SetHouseholdLocation(householdId, HEX(hex))}（家户位置先动）；
 *   <li>{@code unit.SetUnitHouseholds(unitId, 原列表 − household)}（unit 侧容纳列表随后）。
 * </ol>
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：{@link HouseholdBook#setLocation} + {@link
 * UnitOperations#setUnitHouseholds} 都在 preview 先跑；{@code preview=true（缺省）}一个字节都不写。任一失败 ⇒ 整批不落。
 * 提交成功后记 INFO {@code event=UNIT_HOUSEHOLD_DETACH}（{@link AppLog} 的 tool 分类）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；资源声明 social + unit。
 */
public final class UnitDetachHouseholdTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.unit.detachHousehold";

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"));

  public UnitDetachHouseholdTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 把家户移出单位（组合工具，一批 = 一条 revision）：先 social.SetHouseholdLocation(household, HEX(hex))，"
        + "再 unit.SetUnitHouseholds(unitId, 原列表 − household)。参数 {householdId, unitId, hex:{q,r}, reason, "
        + "preview?(缺省 true=只算不写), branch?, expectedRevision?(preview=false 必填)}。"
        + "要求：家户与单位都存在、家户位置确为 UNIT(unitId)、该 unit 的 households 确含家户、hex 在本世界地图上；"
        + "任一不一致 ⇒ 具名拒（零 revision）。提交成功后记 UNIT_HOUSEHOLD_DETACH；"
        + "返回 {preview, submitted, householdId, unitId, from, to, populationBefore, populationAfter, unitChange, "
        + "commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSupport.prop("string", "家户稳定 id（必填；必须存在且位置为 UNIT(unitId)）"));
    props.put("unitId", ToolSupport.prop("string", "当前单位 id（必填；households 必须含该家户）"));
    props.put("hex", ToolSupport.prop("object", "移出后的落点 {q,r}（必填；必须在本世界地图上）"));
    props.put("reason", ToolSupport.prop("string", "移出原因（必填非空白；进命令载荷与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("householdId", "unitId", "hex", "reason"));
  }

  @Override
  protected ResourceManifest resourceManifest() {
    return SOCIAL_AND_UNIT_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources() {
    return WRITE_RESOURCES;
  }

  @Override
  protected ToolResult run(Request request) {
    SocialData base = ToolSupport.socialData(request.state());
    UnitState units = ToolSupport.unitState(request.state());
    Map<String, Object> args = request.args();
    HouseholdId id = HouseholdId.parse(ToolSupport.requiredText(args, "householdId"));
    String rawUnitId = ToolSupport.requiredText(args, "unitId");
    HexCoord hex = hexArg(args.get("hex"), "hex");
    requireHexOnMap(request.state(), hex);

    Household household = base.households().get(id);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + id);
    }
    Unit unit = requireUnit(units, rawUnitId, "unitId");
    HouseholdLocation current = household.location();
    if (!(current instanceof HouseholdLocation.Unit currentUnit)
        || !currentUnit.unitId().equals(rawUnitId)) {
      throw new IllegalArgumentException(
          "状态不一致：家户 " + id + " 的位置是 " + current + "，不是 UNIT(" + rawUnitId + ")；拒绝移出");
    }
    if (!unit.households().contains(id)) {
      throw new IllegalArgumentException(
          "状态不一致：unit " + rawUnitId + " 的 households 列表不含家户 " + id + "；拒绝移出");
    }

    SocialData projected =
        HouseholdBook.setLocation(base, id, new HouseholdLocation.Hex(hex), request.reason());
    List<HouseholdId> after = new ArrayList<>(unit.households());
    after.remove(id);
    UnitOperations.setUnitHouseholds(units, unit.id(), after);

    String batchId = UUID.randomUUID().toString();
    List<CommandEnvelope> batch = new ArrayList<>(2);
    List<Map<String, Object>> commandsPreview = new ArrayList<>(2);

    Map<String, Object> socialPayload = new LinkedHashMap<>();
    socialPayload.put("householdId", id.value());
    socialPayload.put("location", locationPayload(new HouseholdLocation.Hex(hex)));
    socialPayload.put("reason", request.reason());
    batch.add(envelope(request, batchId, SetHouseholdLocationHandler.TYPE, socialPayload));
    commandsPreview.add(commandPreview(SetHouseholdLocationHandler.TYPE, socialPayload));

    Map<String, Object> unitPayload =
        SocialHouseholdMoveTool.unitHouseholdsPayload(unit, after, request.reason());
    batch.add(envelope(request, batchId, SetUnitHouseholdsHandler.TYPE, unitPayload));
    commandsPreview.add(commandPreview(SetUnitHouseholdsHandler.TYPE, unitPayload));

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", id.value());
    view.put("unitId", rawUnitId);
    view.put("from", locationView(current));
    view.put("to", locationView(new HouseholdLocation.Hex(hex)));
    view.put("populationBefore", base.householdPopulation(id));
    view.put("populationAfter", projected.householdPopulation(id));
    view.put("unitChange", SocialHouseholdMoveTool.unitChange(unit, after));
    view.put("commandsPreview", commandsPreview);
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    return submitBatch(
        request,
        batch,
        view,
        () ->
            EventLog.channel(AppLog.tool())
                .info(
                    LogEvent.of(
                        "UNIT_HOUSEHOLD_DETACH",
                        AppLogSource.TOOL_CALL,
                        "unit",
                        rawUnitId,
                        "household",
                        id,
                        "location",
                        "HEX:" + hex,
                        "reasonLength",
                        request.reason() == null ? 0 : request.reason().length())));
  }
}
