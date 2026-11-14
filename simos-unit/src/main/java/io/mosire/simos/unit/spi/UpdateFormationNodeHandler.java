package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.unit.ops.UnitOperations.FormationNodePatch;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.UpdateFormationNode}：只改编制节点给定字段（name / position / attached / parent / speed /
 * mobilityPerMille / equipment），其余 {@link io.mosire.simos.unit.Unit} 组件原样保留。
 *
 * <pre>{@code
 * {"id":"bn-1","position":{"q":10,"r":5},"attached":false,"name":"第1营"}
 * }</pre>
 *
 * <p>★★ <b>为什么需要它</b>：既有 {@code unit.PlaceAt} 只改一个单位的位置（不带动 attached 后代），{@code unit.Reparent*} 只改
 * parent、{@code unit.Attach/Detach} 只改 attached；一次整树编辑要在同一条命令里把"位置 / 跟随位 / 归属 / 基本参数"一致地落下来，
 * 且不能复制单位对象漏字段。本命令是那个窄写口，语义实现住 {@link UnitOperations#updateFormationNode}。
 *
 * <p>★ <b>字段缺席 = 保持</b>；{@code position:null} = 清空位置（仅当没有 attached 后代）；{@code parent:null} 或空串 =
 * 提升为根。 {@code attached=true} 且给了与父不同格的坐标 ⇒ 具名拒（不制造 attached 跨格）。
 */
public final class UpdateFormationNodeHandler implements CommandHandler, CommandTargets {

  public static final String TYPE = "unit.UpdateFormationNode";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
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
      String name =
          payload.has("name") && !payload.get("name").isNull()
              ? UnitPayloads.requireText(payload, "name")
              : null;
      Optional<HexCoord> position = null;
      if (payload.has("position")) {
        position = UnitPayloads.optionalHex(payload, "position");
      }
      Boolean attached = UnitPayloads.optionalBoolean(payload, "attached").orElse(null);
      Optional<UnitId> parent = null;
      if (payload.has("parent")) {
        JsonNode node = payload.get("parent");
        if (node == null || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
          parent = Optional.empty();
        } else {
          parent = UnitPayloads.optionalId(payload, "parent");
        }
      }
      Integer speed =
          payload.has("speed") && !payload.get("speed").isNull()
              ? UnitPayloads.requireInt(payload, "speed")
              : null;
      Integer mobility =
          payload.has("mobilityPerMille") && !payload.get("mobilityPerMille").isNull()
              ? UnitPayloads.requireInt(payload, "mobilityPerMille")
              : null;
      List<CompositionEntry> equipment = null;
      if (payload.has("equipment")) {
        equipment = UnitPayloads.requireComposition(payload, "equipment");
      }
      UnitState next =
          UnitOperations.updateFormationNode(
              snapshot.state(),
              id,
              new FormationNodePatch(name, position, attached, parent, speed, mobility, equipment),
              state.meta().timestamp());
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_UPDATE_FORMATION_NODE_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "unit",
                  id.value(),
                  "position",
                  next.effectivePosition(id, state.meta().timestamp())
                      .map(HexCoord::toString)
                      .orElse("-"),
                  "attached",
                  next.units().get(id).attached().valueAt(state.meta().timestamp())));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_UPDATE_FORMATION_NODE_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
