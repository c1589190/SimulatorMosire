package io.mosire.simos.social.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.spi.SetHouseholdLocationHandler;
import io.mosire.simos.social.tools.SocialToolReads;
import io.mosire.simos.social.tools.SocialUnitPort;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code simos.unit.assignHousehold}（S3a，2026-10-09）：<b>GM 把家户编入单位窄工具</b>——两条命令<b>同批落一条
 * revision</b>（架构 §3.3 的固定批序）：
 *
 * <ol>
 *   <li>{@code social.SetHouseholdLocation(householdId, UNIT(unitId))}（家户位置先动）；
 *   <li>{@code unit.SetUnitHouseholds(unitId, 原列表 + household)}（unit 侧容纳列表随后）；若家户当前在<b>另一个</b>
 *       unit， 再追加一条从旧 unit 列表移除的命令（同一批、同一 revision，保证"家户只能属于一个 unit"）。
 * </ol>
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：{@link HouseholdBook#setLocation} + {@code
 * UnitOperations.setUnitHouseholds}（新旧 unit 两侧）都在 preview 先跑；{@code preview=true（缺省）}一个字节都不写。
 * 目标单位已含该家户且位置一致 ⇒ 幂等 no-op。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；资源声明 social + unit。提交成功后记 INFO {@code
 * event=UNIT_HOUSEHOLD_ASSIGN}（{@code SocialLog.command()} 分类）。
 */
public final class UnitAssignHouseholdTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.unit.assignHousehold";

  /** 单位编制口（跨域事实：家户归属的权威在 unit 侧；social 结构上看不见 unit 切片）。 */
  private final SocialUnitPort units;

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"));

  public UnitAssignHouseholdTool(ToolWritePort writes, ToolReadPort reads, SocialUnitPort units) {
    super(writes, reads);
    this.units = Objects.requireNonNull(units, "units");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 把家户编入单位（组合工具，一批 = 一条 revision）：先 social.SetHouseholdLocation(household, UNIT(unitId))，"
        + "再 unit.SetUnitHouseholds(unitId, 原列表 + household)；若家户当前在另一个 unit，同一批再从旧 unit 列表移除。"
        + "参数 {householdId, unitId, reason, preview?(缺省 true=只算不写), branch?, expectedRevision?(preview=false 必填)}。"
        + "家户与单位都必须存在；家户 id 不得与 unit id 撞名；两侧状态不一致（位置与列表对不上）⇒ 具名拒；"
        + "已在目标单位且两侧一致 ⇒ no-op（submitted:false，零 revision）。"
        + "提交成功后记 UNIT_HOUSEHOLD_ASSIGN；返回 {preview, submitted, householdId, unitId, from, to, "
        + "populationBefore, populationAfter, targetUnit, detachedUnit?, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSchema.prop("string", "家户稳定 id（必填；必须存在）"));
    props.put("unitId", ToolSchema.prop("string", "目标单位 id（必填；必须存在；不得与 householdId 撞名）"));
    props.put("reason", ToolSchema.prop("string", "编入原因（必填非空白；进命令载荷与工具结果）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("householdId", "unitId", "reason"));
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
    SocialData base = SocialToolReads.socialData(request.state());
    Map<String, Object> args = request.args();
    HouseholdId id = HouseholdId.parse(ToolSchema.requiredText(args, "householdId"));
    String rawUnitId = ToolSchema.requiredText(args, "unitId");
    Household household = base.households().get(id);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + id);
    }
    SocialUnitPort.UnitHouseholds target = units.requireUnit(request.state(), rawUnitId, "unitId");
    if (target.unitId().equals(id.value())) {
      throw new IllegalArgumentException("家户 id 不得与 unit id 撞名: " + id.value());
    }
    HouseholdLocation current = household.location();
    SocialUnitPort.UnitHouseholds currentUnit = null;
    if (current instanceof HouseholdLocation.Unit unitLocation) {
      currentUnit = units.requireUnit(request.state(), unitLocation.unitId(), "household.location");
      if (!currentUnit.households().contains(id.value())) {
        throw new IllegalArgumentException(
            "状态不一致：家户 " + id + " 的位置是 " + current + "，但该 unit 的 households 列表不含它");
      }
    }
    boolean alreadyInTarget = target.households().contains(id.value());
    if (alreadyInTarget) {
      if (currentUnit == null || !currentUnit.unitId().equals(target.unitId())) {
        throw new IllegalArgumentException(
            "状态不一致：unit " + target.unitId() + " 的 households 已含家户 " + id + "，但家户位置是 " + current);
      }
      Map<String, Object> view = baseView(base, id, target, current);
      view.put("populationAfter", base.householdPopulation(id));
      view.put("commandsPreview", List.of());
      view.put("reason", request.reason());
      view.put("noop", true);
      if (request.preview()) {
        return preview(view);
      }
      return noop(view, "家户已在目标单位且两侧列表一致: " + id);
    }
    if (currentUnit != null && currentUnit.unitId().equals(target.unitId())) {
      throw new IllegalArgumentException(
          "状态不一致：家户 " + id + " 的位置是 UNIT(" + target.unitId() + ")，但该 unit 的 households 列表不含它");
    }

    SocialData projected =
        HouseholdBook.setLocation(
            base, id, new HouseholdLocation.Unit(rawUnitId), request.reason());
    List<HouseholdId> targetAfter = SocialHouseholdMoveTool.parseHouseholds(target.households());
    targetAfter.add(id);
    units.requireValidHouseholds(request.state(), target.unitId(), householdIds(targetAfter));

    List<PlannedCommand> batch = new ArrayList<>();
    List<Map<String, Object>> commandsPreview = new ArrayList<>();

    // ① 家户位置先动（架构 §3.3 的固定批序）。
    Map<String, Object> locationPayload = new LinkedHashMap<>();
    locationPayload.put("householdId", id.value());
    locationPayload.put("location", locationPayload(new HouseholdLocation.Unit(rawUnitId)));
    locationPayload.put("reason", request.reason());
    batch.add(new PlannedCommand(SetHouseholdLocationHandler.TYPE, locationPayload));
    commandsPreview.add(commandPreview(SetHouseholdLocationHandler.TYPE, locationPayload));

    // ② unit 侧加入目标。
    Map<String, Object> targetPayload =
        SocialHouseholdMoveTool.unitHouseholdsPayload(target, targetAfter, request.reason());
    batch.add(new PlannedCommand(units.unitHouseholdsCommandType(), targetPayload));
    commandsPreview.add(commandPreview(units.unitHouseholdsCommandType(), targetPayload));

    // ③ 家户原先在另一个 unit ⇒ 从旧列表移除（同批；家户只能属于一个 unit）。
    SocialUnitPort.UnitHouseholds detachedUnit = null;
    List<HouseholdId> detachedAfter = null;
    if (currentUnit != null && !currentUnit.unitId().equals(target.unitId())) {
      detachedAfter = SocialHouseholdMoveTool.parseHouseholds(currentUnit.households());
      detachedAfter.remove(id);
      units.requireValidHouseholds(
          request.state(), currentUnit.unitId(), householdIds(detachedAfter));
      Map<String, Object> oldPayload =
          SocialHouseholdMoveTool.unitHouseholdsPayload(
              currentUnit, detachedAfter, request.reason());
      batch.add(new PlannedCommand(units.unitHouseholdsCommandType(), oldPayload));
      commandsPreview.add(commandPreview(units.unitHouseholdsCommandType(), oldPayload));
      detachedUnit = currentUnit;
    }

    Map<String, Object> view = baseView(base, id, target, current);
    view.put("populationAfter", projected.householdPopulation(id));
    view.put("targetUnit", SocialHouseholdMoveTool.unitChange(target, targetAfter));
    if (detachedUnit != null) {
      view.put("detachedUnit", SocialHouseholdMoveTool.unitChange(detachedUnit, detachedAfter));
    }
    view.put("commandsPreview", commandsPreview);
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    final SocialUnitPort.UnitHouseholds detachedForLog = detachedUnit;
    return submitBatch(
        request,
        batch,
        view,
        () ->
            EventLog.channel(SocialLog.command())
                .info(
                    LogEvent.of(
                        "UNIT_HOUSEHOLD_ASSIGN",
                        SocialLogSource.SOCIAL_COMMAND,
                        "unit",
                        rawUnitId,
                        "household",
                        id,
                        "location",
                        "UNIT:" + rawUnitId,
                        "detachedFrom",
                        detachedForLog == null ? "-" : detachedForLog.unitId(),
                        "reasonLength",
                        request.reason() == null ? 0 : request.reason().length())));
  }

  /** preview/apply 共用的公共视图（无 unitChanges；调用方补目标/旧 unit 与人口）。 */
  private static Map<String, Object> baseView(
      SocialData base,
      HouseholdId id,
      SocialUnitPort.UnitHouseholds target,
      HouseholdLocation current) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", id.value());
    view.put("unitId", target.unitId());
    view.put("from", locationView(current));
    view.put("to", locationView(new HouseholdLocation.Unit(target.unitId())));
    view.put("populationBefore", base.householdPopulation(id));
    return view;
  }
}
