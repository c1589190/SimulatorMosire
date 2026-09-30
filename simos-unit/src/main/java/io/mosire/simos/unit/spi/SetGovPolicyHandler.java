package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
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
 * {@code unit.SetGovPolicy} 命令的处理器（阶段 10b-i，2026-10-01）：{@code unitId, grainPerStaffPerTick?,
 * clothPerStaffPerCycle?, moneyPerStaffPerTick?, retirementPerStaff?, staffCap?}。
 *
 * <pre>{@code
 * {"unitId":"gov-province-1","moneyPerStaffPerTick":4,"staffCap":{"SCRIBE":40}}
 * }</pre>
 *
 * <p>★ <b>部分覆盖</b>：未给的字段保持原值（不是取 {@code OfficePolicy.defaults()}）；{@code staffCap} 给 {@code {}} =
 * 清空上限，缺省 = 保持原表。四个数值 ≥ 0 与 {@code staffCap} 值 ≥ 0 由 {@code OfficePolicy} 构造期拒。
 *
 * <p>★ <b>拒因</b>（由 {@link UnitOperations#setGovPolicy} / {@code OfficePolicy} 给出，边界只折 {@code
 * Rejected}）：单位不存在；单位不是 GOV（{@code ArmyFormation} 或无编制）；任一数值/上限为负。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 unitId 判；本命令只写 unit 命名空间。
 */
public final class SetGovPolicyHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.SetGovPolicy";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      Optional<Long> grain = UnitPayloads.optionalLong(payload, "grainPerStaffPerTick");
      Optional<Long> cloth = UnitPayloads.optionalLong(payload, "clothPerStaffPerCycle");
      Optional<Long> money = UnitPayloads.optionalLong(payload, "moneyPerStaffPerTick");
      Optional<Long> retirement = UnitPayloads.optionalLong(payload, "retirementPerStaff");
      Optional<Map<StaffRole, Long>> staffCap = UnitPayloads.optionalStaffMap(payload, "staffCap");
      UnitState next =
          UnitOperations.setGovPolicy(
              snapshot.state(), id, grain, cloth, money, retirement, staffCap);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
