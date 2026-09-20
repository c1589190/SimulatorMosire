package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Objects;

/**
 * {@code unit.ApplyCasualties} 命令的处理器（T8 / spec §四）：{@code id, personnel, equipment{}}。
 *
 * <p>★ {@code personnel} 与 {@code equipment} 的值都是 **≤ 0 的增量**（战损只减员），与 {@code unit.SetStrength} 的
 * "整份替换"是两种语义：装备只扣**提及**的键，未提及的键保持不变；提及了**不存在**的键 ⇒ 拒（P14，不视作 0）。
 *
 * <p>★ **差分不另造路径**：本条命令与其余 handler 一样，产出的变更集是 {@link UnitChangeSet#between}（**绝对值**：目标状态
 * 的新值），不是"增量"——故回退到战损前那一 revision 读回的就是战前值（时间线恢复）。
 */
public final class ApplyCasualtiesHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.ApplyCasualties";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      int personnel = UnitPayloads.requireInt(payload, "personnel");
      Map<String, Integer> equipment = UnitPayloads.requireEquipment(payload, "equipment");
      UnitState next = UnitOperations.applyCasualties(snapshot.state(), id, personnel, equipment);
      return new HandlerOutcome.Applied(
          UnitChangeSet.between(snapshot.state(), snapshot.state())); // 变异 m6：战损不落 revision
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
