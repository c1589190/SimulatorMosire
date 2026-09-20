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
 * {@code unit.AttachUnit} 命令的处理器（T3 / spec §一.3 / P3）：{@code id, parent}。
 *
 * <p>★ **级联**（P3 的不对称）：`id` 连同其**全部后代**一起 `attached=true`，只有 `id` 换父。`parent` **必填**—— 没有父的 attach
 * 不是 attach（清根走 {@code unit.ReparentUnit}），故缺失/`null` ⇒ 拒绝。
 *
 * <p>★ `parent` 落在 `id` 的子树内 ⇒ 拒绝（成环，理由由 {@code UnitOperations.attachSubtree} 给出）；两个 id 有任一不存在 ⇒
 * 拒绝。在 base 状态时间戳追加段（`from = at`，M3 口径）。
 */
public final class AttachUnitHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.AttachUnit";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      UnitId parent =
          UnitPayloads.optionalId(payload, "parent")
              .orElseThrow(() -> new IllegalArgumentException("字段 parent 缺失或为 null: " + payload));
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.attachSubtree(snapshot.state(), id, parent, at);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
