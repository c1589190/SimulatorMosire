package io.mosire.simos.social.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
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
import java.util.Set;

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
 * <p>★★ <b>位置关联硬化（2026-10-19 计划 §4）</b>：家户只要出现在任一 {@code Unit.households()} 里（看真实成员列表，不看 {@code
 * household.location()}），就只允许目标恰为 {@code UNIT(同一个且唯一的 owner unitId)}；目标是 HEX、别的 UNIT、或多重归属 ⇒
 * 具名拒，要求先走 {@code simos.unit.detachHousehold} 的合法脱离路径（它同时改位置与列表）。非在编家户（HEX ↔ UNIT 移动）与 no-op 语义不变。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：{@link HouseholdBook#setLocation} + {@code
 * UnitOperations.setUnitHouseholds} 在 preview 先跑；{@code preview=true（缺省）}一个字节都不写。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；资源声明 social + unit。
 */
public final class SocialHouseholdMoveTool extends AbstractHouseholdGmTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.social.household.move";

  /** 单位编制口（跨域事实：家户归属的权威在 unit 侧；social 结构上看不见 unit 切片）。 */
  private final SocialUnitPort units;

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"));

  public SocialHouseholdMoveTool(ToolWritePort writes, ToolReadPort reads, SocialUnitPort units) {
    super(writes, reads);
    this.units = Objects.requireNonNull(units, "units");
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
        + "★ 在编家户（出现在任一 unit.households 里，按真实成员列表判）只允许目标为 UNIT(同一个且唯一的 owner unitId)："
        + "目标是 HEX、别的 UNIT、或多重归属 ⇒ BAD_REQUEST 具名拒，先走 simos.unit.detachHousehold 合法脱离。"
        + "返回 {preview, submitted, householdId, from, to, populationBefore, populationAfter, unitChanges, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSchema.prop("string", "家户稳定 id（必填；必须存在）"));
    props.put(
        "location",
        ToolSchema.prop(
            "object",
            "目标位置 {type:HEX|UNIT, hex:{q,r}（HEX 必填）| unitId（UNIT 必填）}；HEX 必须在本世界地图上；"
                + "在编家户只允许 UNIT(同一个且唯一的 owner unitId)，HEX/别的 UNIT 先 simos.unit.detachHousehold"));
    props.put("reason", ToolSchema.prop("string", "移动原因（必填非空白；进命令载荷与工具结果）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("householdId", "location", "reason"));
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
    Household household = base.households().get(id);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + id);
    }
    HouseholdLocation target = locationArg(args, "location");
    HouseholdLocation current = household.location();

    // ★★ 位置关联硬化（2026-10-19 计划 §4）：判定"在编"只看 unit 侧 households 列表的真实成员关系，
    //   不看 household.location()（坏状态里两者可能已经不一致）。在编家户的唯一合法目标是
    //   UNIT(同一个且唯一的 owner unitId)；HEX、别的 UNIT、多重归属都以具名拒拦下，要求先走
    //   simos.unit.detachHousehold 的合法脱离路径（它同时改位置与列表；Unit.assignHousehold 之外不另开写口）。
    List<String> owningUnitIds = units.householdOwners(request.state(), id.value());
    if (!owningUnitIds.isEmpty()) {
      boolean sameSoleOwner =
          target instanceof HouseholdLocation.Unit targetUnitLocation
              && owningUnitIds.size() == 1
              && owningUnitIds.get(0).equals(targetUnitLocation.unitId());
      if (!sameSoleOwner) {
        String owners = String.join(", ", owningUnitIds);
        String multiOwnerNote = owningUnitIds.size() > 1 ? "（坏状态：多个单位同时持有同一家户，先修掉重复归属再移动）" : "";
        throw new IllegalArgumentException(
            "在编家户 "
                + id
                + " 当前出现在 unit.households 列表里（owner unit："
                + owners
                + "）；在编家户不得直接钉到 HEX/别的 UNIT（目标 "
                + target
                + "）。"
                + "先调用 simos.unit.detachHousehold 让它脱离单位（该命令会同时改位置与列表），再移动；"
                + multiOwnerNote);
      }
    }

    SocialUnitPort.UnitHouseholds targetUnit = null;
    if (target instanceof HouseholdLocation.Unit unitLocation) {
      targetUnit = units.requireUnit(request.state(), unitLocation.unitId(), "location.unitId");
      if (targetUnit.unitId().equals(id.value())) {
        throw new IllegalArgumentException("家户 id 不得与 unit id 撞名: " + id.value());
      }
    } else {
      requireHexOnMap(request.state(), ((HouseholdLocation.Hex) target).hex());
    }

    SocialUnitPort.UnitHouseholds currentUnit = null;
    if (current instanceof HouseholdLocation.Unit unitLocation) {
      currentUnit = units.requireUnit(request.state(), unitLocation.unitId(), "household.location");
      if (!currentUnit.households().contains(id.value())) {
        throw new IllegalArgumentException(
            "状态不一致：家户 "
                + id
                + " 的位置是 UNIT("
                + currentUnit.unitId()
                + ")，但该 unit 的 households 列表不含它（拒绝在坏状态上继续）");
      }
    }
    if (targetUnit != null && targetUnit.households().contains(id.value())) {
      if (currentUnit == null || !currentUnit.unitId().equals(targetUnit.unitId())) {
        throw new IllegalArgumentException(
            "状态不一致：unit "
                + targetUnit.unitId()
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
    List<PlannedCommand> batch = new ArrayList<>();
    List<Map<String, Object>> commandsPreview = new ArrayList<>();
    List<Map<String, Object>> unitChanges = new ArrayList<>();

    if (!noop) {
      Map<String, Object> socialPayload = new LinkedHashMap<>();
      socialPayload.put("householdId", id.value());
      socialPayload.put("location", locationPayload(target));
      socialPayload.put("reason", request.reason());
      batch.add(new PlannedCommand(SetHouseholdLocationHandler.TYPE, socialPayload));
      commandsPreview.add(commandPreview(SetHouseholdLocationHandler.TYPE, socialPayload));

      if (targetUnit != null && !targetUnit.households().contains(id.value())) {
        List<HouseholdId> nextHouseholds = parseHouseholds(targetUnit.households());
        nextHouseholds.add(id);
        units.requireValidHouseholds(
            request.state(), targetUnit.unitId(), householdIds(nextHouseholds));
        Map<String, Object> payload =
            unitHouseholdsPayload(targetUnit, nextHouseholds, request.reason());
        batch.add(new PlannedCommand(units.unitHouseholdsCommandType(), payload));
        commandsPreview.add(commandPreview(units.unitHouseholdsCommandType(), payload));
        unitChanges.add(unitChange(targetUnit, nextHouseholds));
      }
      if (currentUnit != null
          && (targetUnit == null || !currentUnit.unitId().equals(targetUnit.unitId()))) {
        List<HouseholdId> nextHouseholds = parseHouseholds(currentUnit.households());
        nextHouseholds.remove(id);
        units.requireValidHouseholds(
            request.state(), currentUnit.unitId(), householdIds(nextHouseholds));
        Map<String, Object> payload =
            unitHouseholdsPayload(currentUnit, nextHouseholds, request.reason());
        batch.add(new PlannedCommand(units.unitHouseholdsCommandType(), payload));
        commandsPreview.add(commandPreview(units.unitHouseholdsCommandType(), payload));
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
    final SocialUnitPort.UnitHouseholds assignTarget = targetUnit;
    final SocialUnitPort.UnitHouseholds detachSource = currentUnit;
    final HouseholdLocation finalTarget = target;
    return submitBatch(
        request,
        batch,
        view,
        () -> {
          if (assignTarget != null
              && (detachSource == null || !detachSource.unitId().equals(assignTarget.unitId()))) {
            EventLog.channel(SocialLog.command())
                .info(
                    LogEvent.of(
                        "UNIT_HOUSEHOLD_ASSIGN",
                        SocialLogSource.SOCIAL_COMMAND,
                        "unit",
                        assignTarget.unitId(),
                        "household",
                        id,
                        "location",
                        "UNIT:" + assignTarget.unitId(),
                        "reasonLength",
                        request.reason() == null ? 0 : request.reason().length()));
          }
          if (detachSource != null && finalTarget instanceof HouseholdLocation.Hex hex) {
            EventLog.channel(SocialLog.command())
                .info(
                    LogEvent.of(
                        "UNIT_HOUSEHOLD_DETACH",
                        SocialLogSource.SOCIAL_COMMAND,
                        "unit",
                        detachSource.unitId(),
                        "household",
                        id,
                        "location",
                        "HEX:" + hex.hex(),
                        "reasonLength",
                        request.reason() == null ? 0 : request.reason().length()));
          }
        });
  }

  /**
   * unit 侧一条 {@code unit.SetUnitHouseholds} 的载荷（命令类型见 {@link
   * SocialUnitPort#unitHouseholdsCommandType()}）。
   */
  static Map<String, Object> unitHouseholdsPayload(
      SocialUnitPort.UnitHouseholds unit, List<HouseholdId> households, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unit.unitId());
    payload.put("households", householdIds(households));
    payload.put("reason", reason);
    return payload;
  }

  /** unit 变更前后视图。 */
  static Map<String, Object> unitChange(
      SocialUnitPort.UnitHouseholds unit, List<HouseholdId> after) {
    Map<String, Object> change = new LinkedHashMap<>();
    change.put("unitId", unit.unitId());
    change.put("householdsBefore", unit.households());
    change.put("householdsAfter", householdIds(after));
    return change;
  }

  /** 单位编制口给的 households（字符串稳定序）⇒ 家户 id 列表。 */
  static List<HouseholdId> parseHouseholds(List<String> households) {
    List<HouseholdId> out = new ArrayList<>(households.size());
    for (String household : households) {
      out.add(HouseholdId.parse(household));
    }
    return out;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
