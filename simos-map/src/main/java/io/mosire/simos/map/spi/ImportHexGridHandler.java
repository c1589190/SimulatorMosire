package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainHeights;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code map.ImportHexGrid}：从底图采样得到的逐格地形创建一张空白地图的六角网格。
 *
 * <p>只允许在**空地图**上执行；命令载荷是前端生成的 {@code [{q,r,terrain}…]}。本命令不处理 PNG 像素，
 * 也不做连通性/环绕推断——像素采样在浏览器侧完成，地图域只验证坐标/地形并原子替换网格。
 */
public final class ImportHexGridHandler implements CommandHandler, CommandTargets {

  public static final String TYPE = "map.ImportHexGrid";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = MapPayloads.parse(payloadJson);
    return MapPayloads.requireTerrainHexes(payload, "hexes").keySet().stream()
        .map(coord -> ResourcePaths.hex(mapId, coord.q(), coord.r()))
        .toList();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap base = MapSnapshots.of(state).map();
    if (!base.hexes().isEmpty()
        || !base.regions().isEmpty()
        || !base.cities().isEmpty()
        || !base.pathways().isEmpty()
        || !base.edges().isEmpty()) {
      return new HandlerOutcome.Rejected("只有空白地图可以导入底图网格");
    }
    try {
      Map<HexCoord, String> terrainByHex =
          MapPayloads.requireTerrainHexes(MapPayloads.parse(payloadJson), "hexes");
      if (terrainByHex.isEmpty()) {
        return new HandlerOutcome.Rejected("导入网格不得为空");
      }
      Map<String, TerrainType> terrainTypes =
          base.terrainTypes().isEmpty() ? TerrainCatalog.defaults() : base.terrainTypes();
      Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
      for (Map.Entry<HexCoord, String> entry : terrainByHex.entrySet()) {
        TerrainType type = TerrainCatalog.of(entry.getValue());
        if (!terrainTypes.containsKey(type.key())) {
          terrainTypes = new LinkedHashMap<>(terrainTypes);
          terrainTypes.put(type.key(), type);
        }
        hexes.put(entry.getKey(), new HexCell(TerrainHeights.paintHeight(type)));
      }
      Map<BlockId, TerrainBlock> blocks = TerrainBlocks.split(terrainByHex);
      // 走变更集而不是直接 new GameMap：地图构造点只允许 MapGenerator / MapChangeSet 两处。
      MapChangeSet changes =
          new MapChangeSet(
              FieldDelta.diff(base.hexes(), hexes),
              FieldDelta.diff(base.terrainBlocks(), blocks),
              new FieldDelta.Unchanged<>(),
              new FieldDelta.Unchanged<>(),
              FieldDelta.diff(base.terrainTypes(), terrainTypes),
              new FieldDelta.Unchanged<>(),
              new FieldDelta.Unchanged<>(),
              new FieldDelta.Unchanged<>());
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_IMPORT_HEX_GRID_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "hexes",
                  hexes.size(),
                  "blocks",
                  blocks.size()));
      return new HandlerOutcome.Applied(changes);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_IMPORT_HEX_GRID_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
