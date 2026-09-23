package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
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
 * {@code unit.PlaceAt} 命令的处理器（spec §四）：{@code id, hex{q,r}?}，{@code hex} 缺失或 {@code null} 即撤销位置。
 *
 * <p>位置设置在 base 状态时间戳追加一条 {@code position} 段，并按 M3 语义**顺带清空在途路线**（改了位置，旧路线不再有意义）。
 */
public final class PlaceAtHandler implements CommandHandler, CommandTargets {

  /**
   * ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：**只**声明被移动的那个单位——位置住在 {@code UnitState} 里， {@code
   * hex} 只是新位置的值，**不是**被写的资源。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return "unit.PlaceAt";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      Optional<HexCoord> hex = UnitPayloads.optionalHex(payload, "hex");
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.placeAt(snapshot.state(), id, hex, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
