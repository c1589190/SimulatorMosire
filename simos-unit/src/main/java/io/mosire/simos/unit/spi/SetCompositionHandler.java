package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.CompositionEntry;
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
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.SetComposition} 命令的处理器（阶段 D3a，2026-10-02；原 {@code unit.SetStrength} 的 rename）： {@code
 * id, equipment[{type,amount}], equipment[{type,amount}]}。
 *
 * <p>★ **整表复写**：载荷里的两张表**整体取代**旧表，不是增量合并；**未知 type 不是错误**——给什么就是什么。人数/装备范围 （{@code amount ≥ 0}、同表
 * type 不重复）由 {@code Unit} 构造期判、折成拒绝。
 *
 * <p>★ <b>命令类型名改了</b>：旧 {@code unit.SetStrength} 与 {member, equipment-map} 载荷按 D-011/R4 **不留兼容层**；
 * 旧调用方读不出/提交即被拒是预期行为（世界替换在后续阶段 D6）。
 */
public final class SetCompositionHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}，也是 catalog / 窄工具引用的唯一拼写点）。 */
  public static final String TYPE = "unit.SetComposition";

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
    String unitForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      unitForLog = id.value();
      UnitPayloads.rejectRetiredManpower(payload);
      List<CompositionEntry> equipment = UnitPayloads.requireComposition(payload, "equipment");
      UnitState next = UnitOperations.setComposition(snapshot.state(), id, equipment);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_COMPOSITION_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "unit",
                  id.value(),
                  "equipment",
                  equipment.size()));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_COMPOSITION_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
