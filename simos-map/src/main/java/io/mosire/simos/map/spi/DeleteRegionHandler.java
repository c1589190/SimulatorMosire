package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.ops.RegionOperations;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;

/**
 * {@code map.DeleteRegion} 命令的处理器（M8 spec §二）：删一个区域，{@code regionId} 由调用方给。
 *
 * <pre>{@code
 * {"regionId":"r1"}
 * }</pre>
 *
 * <p>★ 目标不存在 ⇒ {@code Rejected}（**不做静默幂等**）。删除后 {@code RegionIndex} 里每个 hex 的从属**少一个**（只属它 ⇒
 * 变成无从属），**不悬空**。
 */
public final class DeleteRegionHandler implements CommandHandler {

  @Override
  public String type() {
    return "map.DeleteRegion";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map();
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId id = MapPayloads.requireRegionId(payload, "regionId");
      return new HandlerOutcome.Applied(RegionOperations.deleteRegion(map, id));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
