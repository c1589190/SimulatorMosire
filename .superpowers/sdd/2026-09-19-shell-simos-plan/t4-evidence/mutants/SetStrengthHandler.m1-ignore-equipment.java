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
 * 变异体 m1（T4 变异自证）：SetStrength **忽略载荷 equipment**（保留旧表）。
 *
 * <p>与原件唯一差异：解析出 equipment 后不采用，改用该单位既有装备表。仍保留 {@code requireEquipment} 调用（形状校验路径不变），
 * 因此坏载荷 / 查无此人 / 负人数三条拒绝路径行为不变，只有"整份替换"这一条被保护行为被破坏。
 */
public final class SetStrengthHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.SetStrength";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      int member = UnitPayloads.requireInt(payload, "member");
      UnitPayloads.requireEquipment(payload, "equipment");
      UnitState base = snapshot.state();
      Map<String, Integer> equipment =
          base.units().containsKey(id)
              ? base.units().get(id).equipment()
              : Map.<String, Integer>of();
      UnitState next = UnitOperations.setStrength(base, id, member, equipment);
      return new HandlerOutcome.Applied(UnitChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
