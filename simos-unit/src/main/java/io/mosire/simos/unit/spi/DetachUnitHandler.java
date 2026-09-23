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
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.DetachUnit} 命令的处理器（T3 / spec §一.3 / P3）：{@code id}。
 *
 * <p>★ **只节点**（P3 的不对称）：只把 `id` 标成 `attached=false`，子节点**不动**（子树整体的分离是 SplitFormation， T4）。`id` 已是根
 * ⇒ 拒绝（没有可脱离的父）；`id` 不存在 ⇒ 拒绝。在 base 状态时间戳追加段。
 */
public final class DetachUnitHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return "unit.DetachUnit";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.detachUnit(snapshot.state(), id, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
