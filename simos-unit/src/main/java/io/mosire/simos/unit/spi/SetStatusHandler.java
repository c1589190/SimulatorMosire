package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.SetStatus} 命令的处理器（T2 / spec §三.2）：{@code id, status}。
 *
 * <p>★ 未知 {@code status} 串 ⇒ 拒绝（`UnitStatus.valueOf` 失败由 {@link UnitPayloads#requireStatus} 折成
 * {@link IllegalArgumentException}，handler 在命令边界折成 {@code Rejected}）；单位不存在 ⇒ 拒绝。改状态**只影响此后新下达的路线**，
 * **在途不回溯**（P6：`Movement.speedAtDeparture` 出发时已冻结）。
 */
public final class SetStatusHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return "unit.SetStatus";
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
      UnitStatus status = UnitPayloads.requireStatus(payload, "status");
      UnitState next = UnitOperations.setStatus(snapshot.state(), id, status);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_STATUS_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "unit",
                  id.value(),
                  "status",
                  status));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_STATUS_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
