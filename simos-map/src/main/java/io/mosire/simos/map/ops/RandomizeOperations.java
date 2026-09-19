package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.RegionRandomizer;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 任意选区随机化的操作面（M8 spec §二 {@code map.RandomizeRegion}，S5）。**规则放领域模块**，Core 只转发信封（ADR-1 / 铁律 4）。
 *
 * <p>★ **固定配方**：spec §二 的载荷只有 {@code hexes} 与 {@code seed}（没有两种地形与占比），故本类把它们固定为 {@link
 * #TERRAIN_A}/{@link #TERRAIN_B}/{@link #RATIO_A}——命令的可复现性由**调用方的 seed** 承担，与 {@link
 * RegionRandomizer} 的两地形语义一致。若将来要把配方开放给调用方，应作为载荷字段显式加入（**不给默认值**）。
 *
 * <p>★ **只改地形、不动高度**：走 {@link RegionRandomizer}（P1 之后"改地形 = 改块 + 整体重切分"，{@code HexCell} 只剩高度）。
 * 变更集只有 {@code terrainBlocks} 可能非 {@code Unchanged}。
 */
public final class RandomizeOperations {

  /** 取 A 的地形：与 B 不同即可（词表内）。 */
  static final String TERRAIN_A = "plains";

  /** 其余格的地形：与 A 不同即可（词表内）。 */
  static final String TERRAIN_B = "desert";

  /** 取 A 的期望占比；0.5 是"均匀抛两种地形"的规范值。 */
  static final double RATIO_A = 0.5;

  private RandomizeOperations() {}

  /**
   * 对任意选区按固定配方随机重分配地形，返回变更集。
   *
   * <p>校验次序：判空 → {@code hexes} 非空 → 每一格都在图上（**按自然序**报第一个坏格）。选区任意（无需先建 Region， S5），但**必须都在图上**—— 与
   * {@link RegionRandomizer} 原入口"图外格静默跳过"不同，命令面不静默：选了图外格是载荷的错。
   *
   * @param base 现图（只读）
   * @param hexes 选区；**不得为空**，每一格都必须在 {@code base.hexes()} 里
   * @param seed 随机种子；**必须由调用方显式给**（同 seed + 同 base + 同选区 ⇒ 逐字节相同）
   * @return 只有 {@code terrainBlocks} 可能非 {@code Unchanged} 的变更集
   * @throws IllegalArgumentException 空选区 / 图外格
   */
  public static MapChangeSet randomize(GameMap base, Set<HexCoord> hexes, long seed) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hexes, "hexes");
    if (hexes.isEmpty()) {
      throw new IllegalArgumentException("hexes 不得为空：一条 map.RandomizeRegion 至少要选一格");
    }
    List<HexCoord> ordered = new ArrayList<>(hexes);
    ordered.sort(Comparator.naturalOrder());
    for (HexCoord hex : ordered) {
      if (!base.hexes().containsKey(hex)) {
        throw new IllegalArgumentException("hex 不在图上: " + hex);
      }
    }
    return RegionRandomizer.randomize(base, hexes, TERRAIN_A, TERRAIN_B, RATIO_A, seed);
  }
}
