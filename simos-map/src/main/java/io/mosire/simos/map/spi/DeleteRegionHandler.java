package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.ops.RegionOperations;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
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
public final class DeleteRegionHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：被删的那个区域。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = MapPayloads.parse(payloadJson);
    return List.of(
        ResourcePaths.region(mapId, MapPayloads.requireRegionId(payload, "regionId").value()));
  }

  @Override
  public String type() {
    return "map.DeleteRegion";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map();
    String regionForLog = null;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId id = MapPayloads.requireRegionId(payload, "regionId");
      regionForLog = id.value();
      var applied = RegionOperations.deleteRegion(map, id);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_DELETE_REGION_APPLIED", MapLogSource.MAP_EDIT, "region", id.value()));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_DELETE_REGION_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "region",
                  regionForLog == null ? "-" : regionForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
