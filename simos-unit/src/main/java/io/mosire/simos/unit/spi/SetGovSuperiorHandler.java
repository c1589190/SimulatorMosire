package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetGovSuperior} 命令的处理器（阶段 10b-i，2026-10-01）：{@code unitId, superiorGov?}。
 *
 * <pre>{@code
 * {"unitId":"gov-province-1","superiorGov":"gov-central"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code superiorGov} 缺省或 JSON {@code null} ⇒ {@code Optional.empty()} = 中央
 * （无上级）。非空上级必须存在、带 {@code GovFormation}、不得指向自身，且<b>不得成环</b>（从新上级沿 {@code superiorGov} 上溯，命中自己即拒；用
 * seen + 最多 64 层兜底）。
 *
 * <p>★ <b>拒因</b>（由 {@link UnitOperations#setGovSuperior} 给出，边界只折 {@code Rejected}）：单位不存在； 单位不是
 * GOV；上级不存在 / 不是 GOV / 自指 / 成环 / 链超过 64 层。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 unitId 判；本命令只写 unit 命名空间。
 */
public final class SetGovSuperiorHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.SetGovSuperior";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      Optional<UnitId> superiorGov = UnitPayloads.optionalId(payload, "superiorGov");
      UnitState next = UnitOperations.setGovSuperior(snapshot.state(), id, superiorGov);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
