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
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.DisbandUnit} 命令的处理器（spec §四）：{@code id}。在 base 状态时间戳执行 M3 的解散语义——{@code at} 时刻仍有 下属 ⇒
 * 拒绝（先改编、再解散）。
 */
public final class DisbandUnitHandler implements CommandHandler, CommandTargets {

  /**
   * ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：只声明载荷点名的**根单位**。
   *
   * <p>★ **诚实边界**：本命令会解散整棵子树，但载荷只给根 id ⇒ 后代**不在声明里**（见契约的级联口径）。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return "unit.DisbandUnit";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    String unitForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      unitForLog = id.value();
      SimosTimestamp at = state.meta().timestamp();
      UnitState next = UnitOperations.disband(snapshot.state(), id, at);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_DISBAND_UNIT_APPLIED", UnitLogSource.UNIT_COMMAND, "unit", id.value()));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_DISBAND_UNIT_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
