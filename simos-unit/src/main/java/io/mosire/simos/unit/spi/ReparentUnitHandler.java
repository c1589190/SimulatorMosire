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
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.ReparentUnit} 命令的处理器（spec §四）：{@code id, parent?}，{@code parent} 缺失或 {@code null}
 * 即清根。
 *
 * <p>改编在 base 状态时间戳追加一条 {@code parent} 段（{@code from = at}，M3 口径）——同刻已有段由严格升序校验拒绝； 环由 {@code
 * UnitState} 构造期拒绝（{@code UnitOperations} 不重复实现）。
 */
public final class ReparentUnitHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.ReparentUnit";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      Optional<UnitId> parent = UnitPayloads.optionalId(payload, "parent");
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.reparent(snapshot.state(), id, parent, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
