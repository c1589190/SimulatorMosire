package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetGovFormation} 命令的处理器（阶段 10a，控制方修订：编制字段在 {@code Unit.module}，故这是 unit 域命令）。
 *
 * <pre>{@code
 * {"unitId":"gov-central","level":"CENTRAL",
 *  "superiorGov":"gov-province-1","staff":{"SCRIBE":12,"YAMEN":8},
 *  "households":["hh-hindu-001","hh-han-001"],
 *  "policy":{"grainPerStaffPerTick":300,"staffCap":{"SCRIBE":40}}}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code level} 必填词表（CENTRAL|PROVINCE）；{@code superiorGov} 可缺省（中央应为空）；{@code
 * staff} 缺省空表、{@code policy} 缺省 {@link OfficePolicy#defaults()}（也可给部分字段，缺省字段取 defaults）。
 * ★ <b>S3a 的 {@code households}</b>（第 18 组件）：缺席 ⇒ <b>保持既有 GOV 的下辖家户</b>（不是清空——旧调用点没有这个字段，
 * 清空会静默丢家户）；给了（含空数组）⇒ 整体替换。
 *
 * <p>★ <b>拒因</b>（全部由 {@link UnitOperations#setGovFormation} 给出，边界只折 {@code Rejected}）：单位不存在；单位已带
 * {@code ArmyFormation}（一单位至多一个标签，不静默替换）；{@code superiorGov} 不存在 / 不是 GOV / 指向自身。同类型重复设置 =
 * 整体替换（文档见操作面）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 unitId 判（与既有 unit 命令同制）。本命令只写 unit 命名空间。
 */
public final class SetGovFormationHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.SetGovFormation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      GovLevel level = UnitPayloads.requireGovLevel(payload, "level");
      Optional<UnitId> superiorGov = UnitPayloads.optionalId(payload, "superiorGov");
      Map<StaffRole, Long> staff = UnitPayloads.optionalStaffMap(payload, "staff").orElse(Map.of());
      OfficePolicy policy = UnitPayloads.optionalPolicy(payload, "policy");
      // ★ S3a：households 新组件——载荷缺席 ⇒ 保持既有 GOV 的下辖家户（不是清空）；给了（含空数组）⇒ 整体替换。
      //   理由：本命令对 staff/policy 是"同类型整体替换"（缺省回落空表/defaults），而 households 对第 18 组件落地前
      //   的既有调用点没有来源——缺省清空会静默丢掉刚刚容纳的家户（本仓最贵教训的形态）。
      List<HouseholdId> households =
          UnitPayloads
              .optionalHouseholdIds(payload, "households")
              .orElseGet(() -> existingGovHouseholds(snapshot.state(), id));
      GovFormation formation = new GovFormation(staff, households, policy, superiorGov, level);
      UnitState next = UnitOperations.setGovFormation(snapshot.state(), id, formation);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * 既有 GOV 的下辖家户（载荷未给 {@code households} 时的保持值）：单位不存在 / 不是 GOV / 尚无编制 ⇒ 空表。
   * ★ 这是 S3a 新组件的旧调用点兼容口径：第 18 组件落地前的 {@code unit.SetGovFormation} 载荷没有这个字段，
   * 重新设编制不得顺手清掉容纳的家户。
   */
  private static List<HouseholdId> existingGovHouseholds(UnitState state, UnitId id) {
    Unit unit = state.units().get(id);
    if (unit != null && unit.module().orElse(null) instanceof GovFormation gov) {
      return gov.households();
    }
    return List.of();
  }
}
