package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.RasterImportOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;

/** GM-only 底图导入。整份载荷先验证，再由完整目标状态派生变更集。 */
public final class ImportRasterHandler implements CommandHandler, CommandTargets {
  public static final String TYPE = "map.ImportRaster";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    return cells(MapPayloads.parse(payloadJson)).stream()
        .map(cell -> ResourcePaths.hex(mapId, cell.hex().q(), cell.hex().r()))
        .toList();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      String mode = MapPayloads.requireText(payload, "mode");
      List<RasterImportOperations.Cell> cells = cells(payload);
      var base = MapSnapshots.of(state).map();
      long added = cells.stream().filter(cell -> !base.hexes().containsKey(cell.hex())).count();
      var applied = RasterImportOperations.apply(base, mode, cells);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_RASTER_IMPORTED",
                  MapLogSource.MAP_EDIT,
                  "mode",
                  mode,
                  "cells",
                  cells.size(),
                  "added",
                  added,
                  "updated",
                  cells.size() - added));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_RASTER_IMPORT_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static List<RasterImportOperations.Cell> cells(JsonNode payload) {
    JsonNode list = payload.get("cells");
    if (list == null
        || !list.isArray()
        || list.isEmpty()
        || list.size() > RasterImportOperations.MAX_CELLS) {
      throw new IllegalArgumentException("cells 必须包含 1..12000 个格");
    }
    List<RasterImportOperations.Cell> out = new ArrayList<>();
    for (JsonNode cell : list) {
      if (!cell.isObject()) throw new IllegalArgumentException("cells 元素必须是对象");
      JsonNode q = cell.get("q");
      JsonNode r = cell.get("r");
      JsonNode h = cell.get("height");
      if (q == null
          || r == null
          || !q.isIntegralNumber()
          || !r.isIntegralNumber()
          || !q.canConvertToInt()
          || !r.canConvertToInt()) {
        throw new IllegalArgumentException("q/r 必须是 int 范围内的整数");
      }
      if (h == null || !h.isNumber()) throw new IllegalArgumentException("height 必须是数值");
      out.add(
          new RasterImportOperations.Cell(
              new HexCoord(q.intValue(), r.intValue()),
              MapPayloads.requireText(cell, "terrain"),
              h.doubleValue()));
    }
    return List.copyOf(out);
  }
}
