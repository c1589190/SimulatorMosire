package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.EdgeOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;

/**
 * {@code map.ConnectHexes}：GM 按有序格链直接增/删既有 {@code EdgeRef/EdgeTags} 的某个 kind。
 *
 * <pre>{@code
 * {"kind":"river","hexes":[{"q":0,"r":0},{"q":1,"r":0},{"q":1,"r":1}],"op":"merge"}
 * }</pre>
 *
 * <p>★ <b>整链先校验后一次提交</b>：2..12001 点、每点存在、相邻两格才成边、无自环/重复无向边；闭环允许起点再出现。跨格跳跃整批拒并指出 第几段，不自动寻路。
 *
 * <p>★ <b>只动 edges</b>：{@link EdgeOperations#connectChain} 只换 {@code edges}
 * 组件；地形/土地/通路实体/其他组件保持。GM-only：不实现 {@code DmUsableCommand}。
 */
public final class ConnectHexesHandler implements CommandHandler, CommandTargets {

  public static final String TYPE = "map.ConnectHexes";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    return MapPayloads.requireHexSequence(MapPayloads.parse(payloadJson), "hexes").stream()
        .map(hex -> ResourcePaths.hex(mapId, hex.q(), hex.r()))
        .toList();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    String kindForLog = null;
    String opForLog = null;
    Integer pointsForLog = null;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      String kind = MapPayloads.requireText(payload, "kind");
      kindForLog = kind;
      List<HexCoord> hexes = MapPayloads.requireHexSequence(payload, "hexes");
      pointsForLog = hexes.size();
      String op = MapPayloads.requireText(payload, "op");
      opForLog = op;
      GameMap base = MapSnapshots.of(state).map();
      var applied = EdgeOperations.connectChain(base, kind, hexes, op);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_CONNECT_HEXES_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "kind",
                  kind,
                  "op",
                  op,
                  "points",
                  hexes.size(),
                  "edges",
                  Math.max(0, hexes.size() - 1)));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_CONNECT_HEXES_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "kind",
                  kindForLog == null ? "-" : kindForLog,
                  "op",
                  opForLog == null ? "-" : opForLog,
                  "points",
                  pointsForLog == null ? "-" : pointsForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
