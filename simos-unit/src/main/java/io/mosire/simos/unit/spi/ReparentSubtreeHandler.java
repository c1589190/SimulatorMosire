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
import java.util.Optional;

/**
 * {@code unit.ReparentSubtree} 命令的处理器（T4 / spec §一.3 / §一.5 表）：{@code rootId, parent?}。
 *
 * <p>★ **整树迁移**：`rootId` 连同其全部后代在同一刻被重新挂载（后代的值不变、都在 `at` 落段），语义与裁定依据见 {@code
 * UnitOperations.reparentSubtree} 的类注。
 *
 * <p>★ `parent` **可选**：缺失或 `null` ⇒ **提升为根**（P4），故这里**不**照抄 {@link AttachUnitHandler} 的
 * `orElseThrow`——那会把这唯一的降根路径堵死。`parent` 落在 `rootId` 的子树内 ⇒ 拒绝（成环）；两个 id 有任一不存在 ⇒ 拒绝。
 */
public final class ReparentSubtreeHandler implements CommandHandler, CommandTargets {

  /**
   * ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：子树根 + 新父。
   *
   * <p>★ **诚实边界**：整棵后代都会被迁移，但载荷只给根 id ⇒ 后代**不在声明里**（见契约的级联口径）。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(
        UnitPayloads.requireText(payload, "rootId"), UnitPayloads.requireText(payload, "parent"));
  }

  @Override
  public String type() {
    return "unit.ReparentSubtree";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId rootId = UnitId.parse(UnitPayloads.requireText(payload, "rootId"));
      Optional<UnitId> parent = UnitPayloads.optionalId(payload, "parent");
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.reparentSubtree(snapshot.state(), rootId, parent, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
