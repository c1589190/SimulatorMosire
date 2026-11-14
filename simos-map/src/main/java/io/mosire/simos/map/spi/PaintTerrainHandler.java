package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.RasterImportOperations;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainHeights;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code map.PaintTerrain}：GM 直接把一批**已存在**格改成可用地形（原子一条命令）。
 *
 * <p>★ <b>直接生效，没有提案态</b>：payload {@code {cells:[{q,r,terrain,height?}]}}；全部校验通过后一次提交，没有“先标不确定 /
 * 再复核”的中间状态，也不需要先识别底图。
 *
 * <p>★ <b>高度缺省</b>：{@code height} 缺给 ⇒ {@link
 * TerrainHeights#paintHeight(String)}（该地形标准编辑高度中值；沙漠按用户口径 只比平原高 0.005）。显式给的 height 必须是 0..1 的有限数。
 *
 * <p>★ <b>复用原子导入</b>：目标图仍由 {@link RasterImportOperations#apply} 完整校验（1..12000、唯一坐标、已存在格、词表）后从完整
 * {@link GameMap} 派生 {@code MapChangeSet}——保留土地树、区域、城市、通路、边与图外格。GM-only：不实现 {@code
 * DmUsableCommand}。
 */
public final class PaintTerrainHandler implements CommandHandler, CommandTargets {

  public static final String TYPE = "map.PaintTerrain";

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
    int cellsForLog = 0;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      List<RasterImportOperations.Cell> cells = cells(payload);
      cellsForLog = cells.size();
      GameMap base = MapSnapshots.of(state).map();
      var applied = RasterImportOperations.apply(base, "patch", cells);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_PAINT_TERRAIN_APPLIED", MapLogSource.MAP_EDIT, "cells", cells.size()));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_PAINT_TERRAIN_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "cells",
                  cellsForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static List<RasterImportOperations.Cell> cells(JsonNode payload) {
    JsonNode list = payload.get("cells");
    if (list == null
        || !list.isArray()
        || list.isEmpty()
        || list.size() > RasterImportOperations.MAX_CELLS) {
      throw new IllegalArgumentException(
          "cells 必须包含 1.." + RasterImportOperations.MAX_CELLS + " 个格");
    }
    List<RasterImportOperations.Cell> out = new ArrayList<>(list.size());
    for (JsonNode cell : list) {
      if (!cell.isObject()) {
        throw new IllegalArgumentException("cells 元素必须是对象");
      }
      JsonNode q = cell.get("q");
      JsonNode r = cell.get("r");
      if (q == null
          || r == null
          || !q.isIntegralNumber()
          || !r.isIntegralNumber()
          || !q.canConvertToInt()
          || !r.canConvertToInt()) {
        throw new IllegalArgumentException("q/r 必须是 int 范围内的整数");
      }
      String terrain = MapPayloads.requireText(cell, "terrain");
      TerrainCatalog.of(terrain); // 未知地形在这里就拒；高度缺省值也依赖词表
      JsonNode heightNode = cell.get("height");
      double height;
      if (heightNode == null) {
        // 只有字段缺失才取标准地形中值；显式 null 是“给了非法值”，不能静默当缺省。
        height = TerrainHeights.paintHeight(terrain);
      } else {
        if (heightNode.isNull() || !heightNode.isNumber()) {
          throw new IllegalArgumentException("height 必须是 0..1 的有限数；显式 null 非法，缺省请省略该字段");
        }
        height = heightNode.doubleValue();
      }
      out.add(
          new RasterImportOperations.Cell(
              new HexCoord(q.intValue(), r.intValue()), terrain, height));
    }
    return List.copyOf(out);
  }
}
