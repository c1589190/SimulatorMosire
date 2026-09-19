package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.Unit;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * v1 的成本实现（M3 spec §4.3）：只吃地形与单位机动性。**无状态**（{@code INSTANCE} 单例，没有可变的局面）。
 *
 * <p>公式（顺序冻结，见 C6）：{@code scale(地形(to).moveCost × 1000, 单位的 mobilityPerMille)}， 其中 {@code scale(v,
 * ‰) = Math.floorDiv(v × ‰ + 500, 1000)}（即 {@code floor(x + 0.5)}，U4 的定点舍入）。
 */
public final class TerrainMovementCost implements MovementCost {

  public static final TerrainMovementCost INSTANCE = new TerrainMovementCost();

  private TerrainMovementCost() {}

  @Override
  public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(map, "map");
    if (!map.hexes().containsKey(to)) { // ★ R-8-c：一次查表兼判"图外"
      return OptionalLong.empty();
    }
    if (from.equals(to) || from.distanceTo(to) != 1) {
      throw new IllegalArgumentException("相邻性是调用方的前提（不是\"没有候选\"）: " + from + " → " + to);
    }
    return costOf(terrainOf(map, map.terrainAt(to)), unit.mobilityPerMille());
  }

  @Override
  public long minStepCostMillis(Unit unit, GameMap map) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(map, "map");
    long best = Long.MAX_VALUE;
    // ★ P1：地形是权威块，整表走派生访问器一次建好（不逐格扫块）。
    for (String key : map.terrainIndex().values()) {
      TerrainType type = terrainOf(map, key); // ★ R-8-c：一次遍历里同时判断与取最小，不查两遍表
      if (type.moveCost() < TerrainType.IMPASSABLE_MOVE_COST) {
        best = Math.min(best, scale(type.moveCost() * 1000L, unit.mobilityPerMille()));
      }
    }
    return best == Long.MAX_VALUE ? 0L : best; // 无任何可通行格 ⇒ 0 仍是合法下界
  }

  /**
   * {@code moveCost >= IMPASSABLE_MOVE_COST} ⇒ 空（**单一来源**：unit 侧不复制第二份哨兵，C5）。
   *
   * @return 进入 {@code to} 那一格的毫 MP 成本；不可通行 ⇒ 空
   */
  private static OptionalLong costOf(TerrainType type, int mobilityPerMille) {
    if (type.moveCost() >= TerrainType.IMPASSABLE_MOVE_COST) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(scale(type.moveCost() * 1000L, mobilityPerMille));
  }

  /** 缺 key ⇒ 抛（与 {@code TerrainCatalog.of} 的"不兜底"同口径）。 */
  private static TerrainType terrainOf(GameMap map, String key) {
    TerrainType type = map.terrainTypes().get(key);
    if (type == null) {
      throw new IllegalArgumentException("地形 key 不在词表里: " + key);
    }
    return type;
  }

  /**
   * {@code floor(v × ‰ / 1000 + 0.5)}（U4）。
   *
   * <p>★ 舍入项 {@code +500} 在本设计里**永不进位**：调用方传入的 {@code v} 恒为 {@code moveCost × 1000}（1000 的整倍数）， 故
   * {@code v × ‰} 已被 1000 整除，{@code +500} 不改变商——缩放是**精确乘法**（R-8-a 的实测结论，见 task-8 报告）。
   *
   * <p>{@code v ≥ 0}、{@code ‰ ≥ 1} ⇒ 不会溢出（{@code moveCost} 最大 998，{@code ‰} 是 int ⇒ 乘积 ≤ 约
   * 2.1×10<sup>15</sup>）。
   */
  static long scale(long millis, int perMille) {
    return Math.floorDiv(millis * perMille + 500, 1000);
  }
}
