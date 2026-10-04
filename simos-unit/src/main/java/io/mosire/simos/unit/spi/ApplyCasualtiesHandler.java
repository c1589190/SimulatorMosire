package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.CompositionDelta;
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
import java.util.Objects;

/**
 * {@code unit.ApplyCasualties} 命令的处理器（T8 / spec §四；D3a 改为双轨 delta 有序条目列表）： {@code id,
 * equipment[{type,amount≤0}], equipment[{type,amount≤0}]}。
 *
 * <p>★ {@code equipment} 的值是 **≤ 0 的增量**（装备战损只减；人力战损落 Social 家户命令），与 {@code unit.SetComposition} 的
 * "整表复写"是两种语义：只扣**提及**的 type，未提及的 type 保持不变；提及了**不存在**的 type ⇒ 拒（P14，不视作 0）。
 *
 * <p>★ **差分不另造路径**：本条命令与其余 handler 一样，产出的变更集是 {@link UnitChangeSet#between}（**绝对值**：目标状态
 * 的新值），不是"增量"——故回退到战损前那一 revision 读回的就是战前值（时间线恢复）。
 */
public final class ApplyCasualtiesHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}，也是 catalog / 窄工具引用的唯一拼写点）。 */
  public static final String TYPE = "unit.ApplyCasualties";

  /** ★ 目标资源（{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      UnitPayloads.rejectRetiredManpower(payload);
      List<CompositionDelta> equipment = UnitPayloads.requireCompositionDelta(payload, "equipment");
      UnitState next = UnitOperations.applyCasualties(snapshot.state(), id, equipment);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
