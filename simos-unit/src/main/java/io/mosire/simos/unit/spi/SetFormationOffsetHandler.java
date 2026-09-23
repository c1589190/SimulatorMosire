package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetFormationOffset} 命令的处理器（T3 / spec §一.3 / P2）：{@code id, dq?, dr?}。
 *
 * <p>★ 载荷口径：`dq` 与 `dr` **都**缺失（或为 `null`）⇒ **清偏移**（`offset = 空`）；只给一个 ⇒ 另一个按 **0** 补（部分更新，plan §三
 * T3 第 5 步）。★ **不判是否落在地图内**（P2）：相对偏移允许越界，越界是"相对父的站位"的合法取值。
 */
public final class SetFormationOffsetHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return "unit.SetFormationOffset";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      Optional<Integer> dq = UnitPayloads.optionalInt(payload, "dq");
      Optional<Integer> dr = UnitPayloads.optionalInt(payload, "dr");
      Optional<RelativeOffset> offset =
          dq.isEmpty() && dr.isEmpty()
              ? Optional.empty()
              : Optional.of(new RelativeOffset(dq.orElse(0), dr.orElse(0)));
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.setOffset(snapshot.state(), id, offset, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
