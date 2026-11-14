package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 已确认底图的原子地图导入；图像识别不属于持久状态写入。 */
public final class RasterImportOperations {
  public static final int MAX_CELLS = 12000;

  private RasterImportOperations() {}

  /** 一格已经确认的地形与归一化高度。 */
  public record Cell(HexCoord hex, String terrain, double height) {}

  /** 完整校验后构造 target；不删除任何图外数据。 */
  public static MapChangeSet apply(GameMap base, String mode, List<Cell> cells) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(cells, "cells");
    if (!"patch".equals(mode) && !"merge".equals(mode)) {
      throw new IllegalArgumentException("mode 必须是 patch 或 merge");
    }
    if (cells.isEmpty() || cells.size() > MAX_CELLS) {
      throw new IllegalArgumentException("cells 数量必须在 1.." + MAX_CELLS);
    }
    Set<HexCoord> seen = new HashSet<>();
    for (Cell cell : cells) {
      if (cell == null || cell.hex() == null || cell.terrain() == null) {
        throw new IllegalArgumentException("cells 不得包含空格或空地形");
      }
      if (!seen.add(cell.hex())) {
        throw new IllegalArgumentException("重复的导入坐标: " + cell.hex());
      }
      if (!Double.isFinite(cell.height()) || cell.height() < 0 || cell.height() > 1) {
        throw new IllegalArgumentException("height 必须是 0..1 的有限数");
      }
      TerrainCatalog.of(cell.terrain());
      if (!base.hexes().containsKey(cell.hex())) {
        if ("patch".equals(mode)) {
          throw new IllegalArgumentException("patch 不允许图外格: " + cell.hex());
        }
        if (base.wrap().wrapX()) {
          throw new IllegalArgumentException("环绕地图不支持扩展图幅；请套用当前格网或使用空白地图");
        }
      }
    }
    Map<HexCoord, HexCell> nextHexes = new LinkedHashMap<>(base.hexes());
    Map<HexCoord, String> nextTerrain = new LinkedHashMap<>(base.terrainIndex());
    Map<String, TerrainType> types = new LinkedHashMap<>(base.terrainTypes());
    cells.stream()
        .sorted(java.util.Comparator.comparing(Cell::hex))
        .forEach(
            cell -> {
              HexCell old = base.hexes().get(cell.hex());
              nextHexes.put(
                  cell.hex(),
                  old == null ? new HexCell(cell.height()) : old.withHeight(cell.height()));
              nextTerrain.put(cell.hex(), cell.terrain());
              types.putIfAbsent(cell.terrain(), TerrainCatalog.of(cell.terrain()));
            });
    GameMap target =
        new GameMap(
            nextHexes,
            TerrainBlocks.split(nextTerrain, base.wrap()),
            base.regions(),
            base.cities(),
            types,
            base.pathways(),
            base.pathwayGroups(),
            base.edges(),
            base.spec());
    return MapChangeSet.between(base, target);
  }
}
