package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
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
 *  "policy":{"grainPerStaffPerTick":300,"staffCap":{"SCRIBE":40}}}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code level} 必填词表（CENTRAL|PROVINCE）；{@code superiorGov} 可缺省（中央应为空）；{@code
 * staff} 缺省空表、{@code policy} 缺省 {@link OfficePolicy#defaults()}（也可给部分字段，缺省字段取 defaults）。
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
      GovFormation formation = new GovFormation(staff, policy, superiorGov, level);
      UnitState next = UnitOperations.setGovFormation(snapshot.state(), id, formation);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
