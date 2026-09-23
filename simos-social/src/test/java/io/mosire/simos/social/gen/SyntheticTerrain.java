package io.mosire.simos.social.gen;

import io.mosire.simos.map.hex.HexCoord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 合成 {@link TerrainView}：几十行的可控世界，用来把每条启发式**单独**照亮（河流乘数、沿海乘数、海洋缺席、地形单调、山口……）。
 *
 * <p>越界口径与真实现一致（不在图上的格抛 {@link IllegalArgumentException}），这样生成器里"邻格越界 = 不是山口"的分支也能被走到。
 */
final class SyntheticTerrain implements TerrainView {

  private static final Map<String, Integer> MOVE_COST =
      Map.of(
          "plains", 1,
          "low_hills", 2,
          "desert", 3,
          "plateau", 4,
          "mountains", 6,
          "plateau_mountains", 12,
          "ocean", 999);

  private final Map<HexCoord, String> terrain = new HashMap<>();
  private final Map<HexCoord, Integer> riverEdges = new HashMap<>();
  private final Set<HexCoord> coastal = new HashSet<>();

  /** 放一格地形（重复放同一格即覆盖）。 */
  SyntheticTerrain put(HexCoord c, String terrainKey) {
    if (!MOVE_COST.containsKey(terrainKey)) {
      throw new IllegalArgumentException("合成视图未定义的地形: " + terrainKey);
    }
    terrain.put(c, terrainKey);
    return this;
  }

  /** 设该格触到的 river 边条数。 */
  SyntheticTerrain river(HexCoord c, int edges) {
    riverEdges.put(c, edges);
    return this;
  }

  /** 标该格沿海。 */
  SyntheticTerrain coast(HexCoord c) {
    coastal.add(c);
    return this;
  }

  @Override
  public String terrainKey(HexCoord c) {
    return require(c);
  }

  @Override
  public int riverEdgesAt(HexCoord c) {
    require(c);
    return riverEdges.getOrDefault(c, 0);
  }

  @Override
  public boolean coastal(HexCoord c) {
    require(c);
    return coastal.contains(c);
  }

  @Override
  public int moveCost(HexCoord c) {
    return MOVE_COST.get(require(c));
  }

  private String require(HexCoord c) {
    String key = terrain.get(c);
    if (key == null) {
      throw new IllegalArgumentException("合成视图里没有这个 hex: " + c);
    }
    return key;
  }
}
