package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.TerrainOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Set;

/**
 * {@code map.SetTerrain} 命令的处理器（M8 spec §二）：**一条命令改多个 hex 的地形**（Q5）。
 *
 * <pre>{@code
 * CommandEnvelope{type:"map.SetTerrain", payloadJson:{"hexes":[{"q":1,"r":1},…],"terrain":"plains"}}
 *   → 本类自己反序列化 payload、调 TerrainOperations.setTerrain（规则在 simos-map）
 *   → 返回 HandlerOutcome.Applied(MapChangeSet)
 * }</pre>
 *
 * <p>★ **payload 形态是本模块的私事**（C26）：Core 只转交 {@code payloadJson} 字节串（R11），从不理解它的结构。本类认 {@code
 * {"hexes":[{q,r}…],"terrain":字符串}}，字段缺失或形态不符 ⇒ {@code Rejected}——载荷坏是命令的错，不是装配的错。
 *
 * <p>★ **域规则违反以 {@code Rejected} 面世**：{@link TerrainOperations} 的 {@link IllegalArgumentException}
 * （词表外地形、图外 hex、空集合）在本边界折成拒绝理由。
 *
 * <p>★ **不是时间命令** ⇒ 没有 {@code TimeProposal}、没有读写集。
 */
public final class SetTerrainHandler implements CommandHandler {

  @Override
  public String type() {
    return "map.SetTerrain";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      String terrain = MapPayloads.requireText(payload, "terrain");
      Set<HexCoord> hexes = MapPayloads.requireHexes(payload, "hexes");
      return new HandlerOutcome.Applied(TerrainOperations.setTerrain(map, hexes, terrain));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
