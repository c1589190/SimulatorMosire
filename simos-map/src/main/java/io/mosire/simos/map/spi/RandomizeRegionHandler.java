package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.RandomizeOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code map.RandomizeRegion} 命令的处理器（M8 spec §二）：**任意选区随机化地形**（S5），{@code seed} 由调用方给。
 *
 * <pre>{@code
 * CommandEnvelope{type:"map.RandomizeRegion", payloadJson:{"hexes":[{"q":1,"r":1},…],"seed":7}}
 *   → 本类自己反序列化 payload、调 RandomizeOperations.randomize（规则在 simos-map）
 *   → 返回 HandlerOutcome.Applied(MapChangeSet)
 * }</pre>
 *
 * <p>★ **{@code seed} 没有默认值**：缺字段 ⇒ {@code requireLong} 当场拒。静默兜 0 会把"两次随机化不可复现"
 * 从一条被拒的载荷变成一次合法但不可解释的写。
 *
 * <p>★ **域规则违反以 {@code Rejected} 面世**：{@link RandomizeOperations} 的 {@link
 * IllegalArgumentException}（空选区、图外格）在本边界折成拒绝理由。
 */
public final class RandomizeRegionHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：{@code hexes[]} 里**每一个**格（逐条判）。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = MapPayloads.parse(payloadJson);
    return MapPayloads.requireHexes(payload, "hexes").stream()
        .map(coord -> ResourcePaths.hex(mapId, coord.q(), coord.r()))
        .toList();
  }

  @Override
  public String type() {
    return "map.RandomizeRegion";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      Set<HexCoord> hexes = MapPayloads.requireHexes(payload, "hexes");
      long seed = MapPayloads.requireLong(payload, "seed");
      return new HandlerOutcome.Applied(RandomizeOperations.randomize(map, hexes, seed));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
