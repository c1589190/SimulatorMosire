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
import java.util.Map;
import java.util.Objects;

/**
 * {@code unit.SetStrength} 命令的处理器（spec §四）：{@code id, member, equipment{}}。
 *
 * <p>★ {@code equipment} 是**整份替换**（M3 {@code setStrength} 语义）：载荷里的键值对整体取代旧表，不是增量合并。 人数/装备范围（{@code
 * member ≥ 0}、装备值 {@code ≥ 0}）由 {@code Unit} 构造期判、折成拒绝。
 */
public final class SetStrengthHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

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
      Map<String, Integer> equipment = UnitPayloads.requireEquipment(payload, "equipment");
      UnitState next = UnitOperations.setStrength(snapshot.state(), id, member, equipment);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
