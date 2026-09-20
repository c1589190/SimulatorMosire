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
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetRejoinTarget} 命令的处理器（T7 / spec §四 表 / §二.2）：{@code id, target?}；{@code target} 缺失或
 * {@code null} ⇒ **清除**回归意图（设/清同一条命令，往返闭合）。
 *
 * <p>★ **只设引用、绝不设 hex**（P8 / 裁定 U6）：本命令**不建路线**——`target` 指向的是"往谁靠"，不是某一格； 终点 = 目标单位**当前**的
 * `effectivePosition`，由 {@code UnitTimeParticipant} 每 tick 现算。把终点冻在命令里就是 research §B.7 坑 2
 * 的形态（航点不更新移动母体 ⇒ 飞向错误位置并坠毁）。
 *
 * <p>★ **命令期只判两件事**（spec §四 表的拒绝列）：目标在不在 `units` 里、是不是指自己；外加未知 `id`。" 有没有能力回归"（A\* 可达性 / 状态 /
 * 位置可确定性）是**环境**的函数——目标会移动、地图会改地形 ⇒ **每 tick 重算，不在 命令期冻结成布尔**（P7）。
 *
 * <p>★ 拒绝一律走 {@link HandlerOutcome.Rejected}（理由由 {@link UnitOperations#setRejoinTarget} 给出，与 {@code
 * unit.SetStatus} 同一条折算路径）；**装配故障**（缺 unit 切片）才抛。
 */
public final class SetRejoinTargetHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.SetRejoinTarget";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      Optional<UnitId> target = UnitPayloads.optionalId(payload, "target");
      UnitState next = UnitOperations.setRejoinTarget(snapshot.state(), id, target);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
