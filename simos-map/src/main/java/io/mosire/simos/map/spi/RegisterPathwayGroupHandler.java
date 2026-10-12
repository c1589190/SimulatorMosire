package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.ops.PathwayGroupOperations;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;

/**
 * {@code map.RegisterPathwayGroup} 命令的处理器（WebUI 阶段修复 T3，spec §三.6）：**给连通性词表注册一个组**（默认 {@code
 * river}/{@code road} 之外的任意自定义通路）。
 *
 * <pre>{@code
 * CommandEnvelope{type:"map.RegisterPathwayGroup",
 *   payloadJson:{"id":"canal","name":"运河","color":"#3A7BD5","description":"人工水道"}}
 *   → 本类自己反序列化 payload、调 PathwayGroupOperations.register（规则在 simos-map）
 *   → 返回 HandlerOutcome.Applied(MapChangeSet)
 * }</pre>
 *
 * <p>★ **它改的是组定义、不是边**（与 {@code map.SetEdge} 分开，spec §三.5）：注册后 {@code map.SetEdge{kind:"canal"}}
 * 才成立；未注册的 {@code kind} 仍被 {@link io.mosire.simos.map.ops.EdgeOperations} fail-closed 拒绝。
 *
 * <p>★ **payload 形态是本模块的私事**（C26）：Core 只转交 {@code payloadJson} 字节串。字段缺失 / 类型不符 / 组 id 已存在 ⇒ {@code
 * Rejected}。
 */
public final class RegisterPathwayGroupHandler implements CommandHandler {

  @Override
  public String type() {
    return "map.RegisterPathwayGroup";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map(); // 装配故障当场炸，不走拒绝路径
    String groupForLog = "-";
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      JsonNode idNode = payload.get("id");
      if (idNode != null && idNode.isTextual()) {
        groupForLog = idNode.asText();
      }
      PathwayGroup group = MapPayloads.requirePathwayGroup(payload);
      var applied = PathwayGroupOperations.register(map, group);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_REGISTER_PATHWAY_GROUP_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "group",
                  group.id(),
                  "properties",
                  group.properties().size()));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_REGISTER_PATHWAY_GROUP_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "group",
                  groupForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
