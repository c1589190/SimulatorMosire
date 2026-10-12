package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.SplitFormation} 命令的处理器（T4 / spec §一.3 / §一.5 表）：{@code rootId, subUnitIds[]}。
 *
 * <p>★ `subUnitIds` 走 {@link UnitPayloads#requireTextArray}（形状与类型），**空数组合法**——"拆不了任何东西"由领域层判
 * （`subUnitIds 不得为空`），两处不重复实现（本模块的分工：payload 只管形状，域规则归 {@code UnitOperations}）。
 *
 * <p>★ 每个目标必须**存在**且在 `rootId` 于该时刻的**子树内**，否则拒绝；通过后逐个 detach（**只节点**，P3），全程走 op、无第二套语义。拒绝不留
 * revision（与其余 handler 同一条路径）。
 */
public final class SplitFormationHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：根 + **每一个**子单位（逐条判，不是只判第一条）。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    List<String> paths = new ArrayList<>(List.of(UnitPayloads.requireText(payload, "rootId")));
    paths.addAll(UnitPayloads.requireTextArray(payload, "subUnitIds"));
    return List.copyOf(paths);
  }

  @Override
  public String type() {
    return "unit.SplitFormation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    String rootForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId rootId = UnitId.parse(UnitPayloads.requireText(payload, "rootId"));
      rootForLog = rootId.value();
      List<UnitId> subUnitIds = new ArrayList<>();
      for (String text : UnitPayloads.requireTextArray(payload, "subUnitIds")) {
        subUnitIds.add(UnitId.parse(text));
      }
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.splitFormation(snapshot.state(), rootId, subUnitIds, at);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SPLIT_FORMATION_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "root",
                  rootId.value(),
                  "subUnits",
                  subUnitIds.size()));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SPLIT_FORMATION_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "root",
                  rootForLog == null ? "-" : rootForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
