package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.RegionOperations;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code map.CreateRegion} 命令的处理器（M8 spec §二）：新建一个区域，{@code regionId} 由调用方给（Q3）。
 *
 * <pre>{@code
 * {"regionId":"r1","name":"区域甲","hexes":[{"q":1,"r":1},…],"meta":{"color":"#fff","tag":"Nation"}}
 * }</pre>
 *
 * <p>★ **重叠允许**（M8-U1）：hex 与别的区域相交**不报错**——规则在 {@link RegionOperations#createRegion}，本类只解析载荷。
 *
 * <p>★ payload 形态是本模块的私事（C26）：Core 只转交 {@code payloadJson} 字节串。字段坏、或域规则违反（重复 id / 空 hexes / 图外
 * hex）以 {@code Rejected} 面世，**不留 revision**。
 */
public final class CreateRegionHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：载荷点名的**新**区域（建之后它才存在）+ {@code hexes[]} 里**每一个**格。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = MapPayloads.parse(payloadJson);
    List<String> paths = new ArrayList<>();
    paths.add(
        ResourcePaths.region(mapId, MapPayloads.requireRegionId(payload, "regionId").value()));
    for (var coord : MapPayloads.requireHexes(payload, "hexes")) {
      paths.add(ResourcePaths.hex(mapId, coord.q(), coord.r()));
    }
    return List.copyOf(paths);
  }

  @Override
  public String type() {
    return "map.CreateRegion";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map();
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId id = MapPayloads.requireRegionId(payload, "regionId");
      String name = MapPayloads.requireText(payload, "name");
      Set<HexCoord> hexes = MapPayloads.requireHexes(payload, "hexes");
      RegionMeta meta = MapPayloads.optionalMeta(payload, "meta");
      return new HandlerOutcome.Applied(RegionOperations.createRegion(map, id, name, hexes, meta));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
