package io.mosire.simos.social.gen;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link TerrainView} 的唯一实现（package-private）：把 {@link GameMap} 的派生读法**一次性物化**成四张常量表。
 *
 * <p>★ **为什么必须物化**：{@code map.terrainAt(c)} 每次重算（O(块数)，真档 782 块），若在 799 格 × 邻居/城市的循环里逐次调用就是 O(n²)
 * 级别的浪费；{@code map.terrainIndex()} 一次 O(格数) 建好后 O(1) 查。河流同理：{@code map.edges()} 只有 240 条 ⇒ 一次扫完
 * 把"河边端点计数"物化成 {@code Map<HexCoord,Integer>}。
 *
 * <p>★ **沿海的定义只查"邻居地形 == ocean"**：邻居不在图上时按"不是海洋"处理（地图边缘之外没有陆海信息）。这与 {@code terrainKey/moveCost}
 * 对越界格**抛异常**的口径不矛盾 —— 前者问的是"本格在图里吗"，后者问的是"邻居是不是海"。
 */
final class GameMapTerrainView implements TerrainView {

  private final Map<HexCoord, String> terrainByHex;
  private final Map<HexCoord, Integer> riverEdgesByHex;
  private final Map<String, Integer> moveCostByKey;
  private final Set<HexCoord> oceanHexes;

  private GameMapTerrainView(
      Map<HexCoord, String> terrainByHex,
      Map<HexCoord, Integer> riverEdgesByHex,
      Map<String, Integer> moveCostByKey,
      Set<HexCoord> oceanHexes) {
    this.terrainByHex = terrainByHex;
    this.riverEdgesByHex = riverEdgesByHex;
    this.moveCostByKey = moveCostByKey;
    this.oceanHexes = oceanHexes;
  }

  /** 物化四张表。河流判定用的是真档里 {@code byPathway} 的裸 key {@code "river"}（另有 {@code "road"}，本档没有）。 */
  static TerrainView of(GameMap map) {
    if (map == null) {
      throw new IllegalArgumentException("map 不得为 null");
    }
    Map<HexCoord, String> terrainByHex = map.terrainIndex();
    Map<String, Integer> moveCostByKey = new LinkedHashMap<>();
    for (Map.Entry<String, TerrainType> entry : map.terrainTypes().entrySet()) {
      moveCostByKey.put(entry.getKey(), entry.getValue().moveCost());
    }
    Map<HexCoord, Integer> riverEdgesByHex = new LinkedHashMap<>();
    for (Map.Entry<EdgeRef, EdgeTags> entry : map.edges().entrySet()) {
      if (!entry.getValue().byPathway().containsKey("river")) {
        continue;
      }
      EdgeRef edge = entry.getKey();
      riverEdgesByHex.merge(edge.a(), 1, Integer::sum);
      riverEdgesByHex.merge(edge.b(), 1, Integer::sum);
    }
    Set<HexCoord> oceanHexes = new HashSet<>();
    for (Map.Entry<HexCoord, String> entry : terrainByHex.entrySet()) {
      if ("ocean".equals(entry.getValue())) {
        oceanHexes.add(entry.getKey());
      }
    }
    return new GameMapTerrainView(terrainByHex, riverEdgesByHex, moveCostByKey, oceanHexes);
  }

  @Override
  public String terrainKey(HexCoord c) {
    String key = terrainByHex.get(requireHex(c));
    if (key == null) {
      // 分割不变式保证每个图上格都有地形；真到这说明不变式被绕过，宁抛不静默。
      throw new IllegalStateException("hex " + c + " 在图里却没有地形（分割不变式被破坏）");
    }
    return key;
  }

  @Override
  public int riverEdgesAt(HexCoord c) {
    requireHex(c);
    return riverEdgesByHex.getOrDefault(c, 0);
  }

  @Override
  public boolean coastal(HexCoord c) {
    requireHex(c);
    for (HexCoord neighbor : c.neighbors()) {
      if (oceanHexes.contains(neighbor)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int moveCost(HexCoord c) {
    String key = terrainKey(c);
    Integer cost = moveCostByKey.get(key);
    if (cost == null) {
      // 地形 key 在 terrainIndex 里却没在 terrainTypes 里 —— 词表与图纸不同源，fail-closed。
      throw new IllegalStateException("terrainTypes 缺少地形 key: " + key);
    }
    return cost;
  }

  /** 越界 = 调用方的错，抛（与 {@code GameMap.terrainAt} 同口径，消息也保持一致）。 */
  private HexCoord requireHex(HexCoord c) {
    if (c == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    if (!terrainByHex.containsKey(c)) {
      throw new IllegalArgumentException("图里没有这个 hex: " + c);
    }
    return c;
  }
}
