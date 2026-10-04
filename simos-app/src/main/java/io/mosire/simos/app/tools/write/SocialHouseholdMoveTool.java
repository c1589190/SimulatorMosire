package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.spi.SetHouseholdLocationHandler;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ★★ {@code simos.social.household.move}（S3a，2026-10-09）：<b>GM 移动家户窄工具</b>（{@code HEX ↔ UNIT}）——
 * {@code social.SetHouseholdLocation} 的封装，并在跨切片时把 unit 侧容纳列表一并改动（同批 = 一条 revision）：
 *
 * <ul>
 *   <li>目标 {@code HEX}：若家户当前在某个 unit，同批从那个 unit 的 {@code households} 列表移除（位置 → 格）；
 *   <li>目标 {@code UNIT}：同批把家户加入目标 unit 的 {@code households}；若家户当前在另一个 unit，同时从旧 unit 移除；
 *   <li>目标与当前位置相同（且两侧列表一致）⇒ 幂等 no-op（不组命令、不落 revision）。
 * </ul>
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：{@link HouseholdBook#setLocation} + {@link
 * UnitOperations#setUnitHouseholds} 在 preview 先跑；{@code preview=true（缺省）}一个字节都不写。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；资源声明 social + unit。
 */
public final class SocialHouseholdMoveTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.social.household.move";

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolSupport.UNIT_NAMESPACE, "*"));

  public SocialHouseholdMoveTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 移动家户（social.SetHouseholdLocation 窄封装，跨 unit 时同批改 unit.households，一条 revision）："
        + "参数 {householdId, location:{type:HEX|UNIT, hex:{q,r}|unitId}, reason, preview?(缺省 true=只算不写), "
        + "branch?, expectedRevision?(preview=false 必填)}。"
        + "目标 HEX ⇒ 从当前所属 unit 的 households 移除；目标 UNIT ⇒ 加入目标 unit.households（当前在另一 unit 时同时从旧的移除）；"
        + "已在目标位置且两侧一致 ⇒ no-op（submitted:false，零 revision）。"
        + "返回 {preview, submitted, householdId, from, to, populationBefore, populationAfter, unitChanges, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSupport.prop("string", "家户稳定 id（必填；必须存在）"));
    props.put(
        "location",
        ToolSupport.prop(
            "object",
            "目标位置 {type:HEX|UNIT, hex:{q,r}（HEX 必填）| unitId（UNIT 必填）}；HEX 必须在本世界地图上"));
    props.put("reason", ToolSupport.prop("string", "移动原因（必填非空白；进命令载荷与工具结果）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop("integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("householdId", "location", "reason"));
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
    Household household = base.households().get(id);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + id);
    }
    HouseholdLocation target = locationArg(args, "location");
    HouseholdLocation current = household.location();

    Unit targetUnit = null;
    if (target instanceof HouseholdLocation.Unit unitLocation) {
      targetUnit = requireUnit(units, unitLocation.unitId(), "location.unitId");
      if (targetUnit.id().value().equals(id.value())) {
        throw new IllegalArgumentException("家户 id 不得与 unit id 撞名: " + id.value());
      }
    } else {
      requireHexOnMap(request.state(), ((HouseholdLocation.Hex) target).hex());
    }

    Unit currentUnit = null;
    if (current instanceof HouseholdLocation.Unit unitLocation) {
      currentUnit = requireUnit(units, unitLocation.unitId(), "household.location");
      if (!currentUnit.households().contains(id)) {
        throw new IllegalArgumentException(
            "状态不一致：家户 "
                + id
                + " 的位置是 UNIT("
                + currentUnit.id()
                + ")，但该 unit 的 households 列表不含它（拒绝在坏状态上继续）");
      }
    }
    if (targetUnit != null && targetUnit.households().contains(id)) {
      if (currentUnit == null || !currentUnit.id().equals(targetUnit.id())) {
        throw new IllegalArgumentException(
            "状态不一致：unit "
                + targetUnit.id()
                + " 的 households 已含家户 "
                + id
                + "，但家户位置是 "
                + current
                + "（拒绝在坏状态上继续）");
      }
    }

    // ★ preview 与 apply 共用同一份社会侧纯推导（家户存在性/位置形状在域层再判一遍）。
    SocialData projected = HouseholdBook.setLocation(base, id, target, request.reason());

    boolean noop = target.equals(current);
    List<CommandEnvelope> batch = new ArrayList<>();
    List<Map<String, Object>> commandsPreview = new ArrayList<>();
    List<Map<String, Object>> unitChanges = new ArrayList<>();
    String batchId = UUID.randomUUID().toString();

    if (!noop) {
      Map<String, Object> socialPayload = new LinkedHashMap<>();
      socialPayload.put("householdId", id.value());
      socialPayload.put("location", locationPayload(target));
      socialPayload.put("reason", request.reason());
      batch.add(envelope(request, batchId, SetHouseholdLocationHandler.TYPE, socialPayload));
      commandsPreview.add(commandPreview(SetHouseholdLocationHandler.TYPE, socialPayload));

      if (targetUnit != null && !targetUnit.households().contains(id)) {
        List<HouseholdId> nextHouseholds = new ArrayList<>(targetUnit.households());
        nextHouseholds.add(id);
        UnitOperations.setUnitHouseholds(units, targetUnit.id(), nextHouseholds);
        Map<String, Object> payload = unitHouseholdsPayload(targetUnit, nextHouseholds, request.reason());
        batch.add(envelope(request, batchId, SetUnitHouseholdsHandler.TYPE, payload));
        commandsPreview.add(commandPreview(SetUnitHouseholdsHandler.TYPE, payload));
        unitChanges.add(unitChange(targetUnit, nextHouseholds));
      }
      if (currentUnit != null && (targetUnit == null || !currentUnit.id().equals(targetUnit.id()))) {
        List<HouseholdId> nextHouseholds = new ArrayList<>(currentUnit.households());
        nextHouseholds.remove(id);
        UnitOperations.setUnitHouseholds(units, currentUnit.id(), nextHouseholds);
        Map<String, Object> payload = unitHouseholdsPayload(currentUnit, nextHouseholds, request.reason());
        batch.add(envelope(request, batchId, SetUnitHouseholdsHandler.TYPE, payload));
        commandsPreview.add(commandPreview(SetUnitHouseholdsHandler.TYPE, payload));
        unitChanges.add(unitChange(currentUnit, nextHouseholds));
      }
    }

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", id.value());
    view.put("from", locationView(current));
    view.put("to", locationView(target));
    view.put("populationBefore", base.householdPopulation(id));
    view.put("populationAfter", projected.householdPopulation(id));
    view.put("unitChanges", unitChanges);
    view.put("commandsPreview", commandsPreview);
    view.put("reason", request.reason());
    if (noop) {
      view.put("noop", true);
      if (request.preview()) {
        return preview(view);
      }
      return noop(view, "家户已在目标位置且两侧列表一致: " + id);
    }
    if (request.preview()) {
      return preview(view);
    }
    if (batch.size() == 1) {
      // ★ 纯 HEX→HEX 移动只有一条命令：走单条提交，落盘的 command_type 就是 social.SetHouseholdLocation。
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("householdId", id.value());
      payload.put("location", locationPayload(target));
      payload.put("reason", request.reason());
      return submitCommand(request, SetHouseholdLocationHandler.TYPE, payload, view);
    }
    final Unit assignTarget = targetUnit;
    final Unit detachSource = currentUnit;
    final HouseholdLocation finalTarget = target;
    return submitBatch(
        request,
        batch,
        view,
        () -> {
          if (assignTarget != null && (detachSource == null || !detachSource.id().equals(assignTarget.id()))) {
            UnitLog.household()
                .info(
                    "event=UNIT_HOUSEHOLD_ASSIGN "
                        + UnitLog.kv(
                            "unit",
                            assignTarget.id(),
                            "household",
                            id,
                            "location",
                            "UNIT:" + assignTarget.id(),
                            "reason",
                            request.reason()));
          }
          if (detachSource != null && finalTarget instanceof HouseholdLocation.Hex hex) {
            UnitLog.household()
                .info(
                    "event=UNIT_HOUSEHOLD_DETACH "
                        + UnitLog.kv(
                            "unit",
                            detachSource.id(),
                            "household",
                            id,
                            "location",
                            "HEX:" + hex.hex(),
                            "reason",
                            request.reason()));
          }
        });
  }

  /** unit 侧一条 {@code unit.SetUnitHouseholds} 的载荷。 */
  static Map<String, Object> unitHouseholdsPayload(
      Unit unit, List<HouseholdId> households, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unit.id().value());
    payload.put("households", householdIds(households));
    payload.put("reason", reason);
    return payload;
  }

  /** unit 变更前后视图。 */
  static Map<String, Object> unitChange(Unit unit, List<HouseholdId> after) {
    Map<String, Object> change = new LinkedHashMap<>();
    change.put("unitId", unit.id().value());
    change.put("householdsBefore", householdIds(unit.households()));
    change.put("householdsAfter", householdIds(after));
    return change;
  }
}
