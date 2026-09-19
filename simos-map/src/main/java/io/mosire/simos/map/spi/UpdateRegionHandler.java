package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.RegionOperations;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Set;

/**
 * {@code map.UpdateRegion} 命令的处理器（M8 spec §二）：改已有区域，{@code hexes} 与 {@code meta} **至少给一个**。
 *
 * <pre>{@code
 * {"regionId":"r1","hexes":[{"q":1,"r":1},…],"meta":{"tag":"Nation"}}
 * }</pre>
 *
 * <p>★ {@code hexes} 改内容 ⇒ 边界按 {@code hexes} **重算**（经 {@code Region.of}）；{@code meta}
 * 只改元数据、内容与边界不动。 改成与别的区域重叠**不报错**（M8-U1）。目标不存在 / 二者皆未给 / 空 hexes / 图外 hex ⇒ {@code Rejected}。
 */
public final class UpdateRegionHandler implements CommandHandler {

  @Override
  public String type() {
    return "map.UpdateRegion";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map();
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId id = MapPayloads.requireRegionId(payload, "regionId");
      Set<HexCoord> hexes = MapPayloads.optionalHexes(payload, "hexes");
      RegionMeta meta = MapPayloads.optionalMeta(payload, "meta");
      return new HandlerOutcome.Applied(RegionOperations.updateRegion(map, id, hexes, meta));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
