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

/**
 * {@code unit.MergeFormation} 命令的处理器（T4 / spec §一.3 / §一.5 表）：{@code childId, parentId}。
 *
 * <p>★ 两个前置条件**各自独立**（spec §一.4）：与父**同格**、且本单位**正在移动**（MOVING）——任一不满足即拒绝，两者都不是另一者的 蕴含。判定与理由文案见
 * {@code UnitOperations.mergeFormation}；通过后复用 attach（级联 + 不销毁节点，P9）。
 *
 * <p>★ **不判"已是父"**：拆→合的往返里父本来就是同一个（detach 不碰 `parent`），判了会把它堵死——T3 已裁定的同一条，见 spec §一.5 表的回填注。
 */
public final class MergeFormationHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.MergeFormation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId childId = UnitId.parse(UnitPayloads.requireText(payload, "childId"));
      UnitId parentId = UnitId.parse(UnitPayloads.requireText(payload, "parentId"));
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.mergeFormation(snapshot.state(), childId, parentId, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
