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
 * <p>★★ **两种地形由载荷给定**（2026-09-24 用户裁定，取代此前的固定配方）：用户报「我无法选择两个想要的随机化地形，
 * 不管我在上面点击哪个地形，都只能把这片圈着的区域替换为沙漠和平原」—— 根因就是本类原先把 {@code plains}/{@code desert} 写死。现在 {@code
 * terrainA}/{@code terrainB} 是载荷的**必填**字段（缺 ⇒ 拒，**不给默认值**：静默兜一对地形会把"我选的那两种"
 * 变成"程序替我选的那两种"，正是这次的报障本身）。两条地形可以相同（{@link RegionRandomizer} 显式允许：占比退化 ⇒ 整区同地形）。
 *
 * <p>★ **占比仍固定 {@value #RATIO_A}**：用户本轮只要求"能选两种地形"，没有要求配比 ⇒ 不擅自开放（要开放按同一纪律加 载荷字段、不给默认值）。
 *
 * <p>★ **只改地形、不动高度**：走 {@link RegionRandomizer}（P1 之后"改地形 = 改块 + 整体重切分"，{@code HexCell} 只剩高度）。
 * 变更集只有 {@code terrainBlocks} 可能非 {@code Unchanged}。
 */
public final class RandomizeOperations {

  /** 取 A 的期望占比；0.5 是"均匀抛两种地形"的规范值。★ 未开放给载荷（见类注）。 */
  static final double RATIO_A = 0.5;

  private RandomizeOperations() {}

  /**
   * 对任意选区按给定两种地形随机重分配，返回变更集。
   *
   * <p>校验次序：判空 → {@code hexes} 非空 → 每一格都在图上（**按自然序**报第一个坏格）→ 两个地形 key（由 {@link RegionRandomizer}
   * 的词表校验兜底，未知 key 抛词表自己的 IAE）。选区任意（无需先建 Region，S5），但**必须都在图上**—— 与 {@link RegionRandomizer}
   * 原入口"图外格静默跳过"不同，命令面不静默：选了图外格是载荷的错。
   *
   * @param base 现图（只读）
   * @param hexes 选区；**不得为空**，每一格都必须在 {@code base.hexes()} 里
   * @param terrainA 占比 {@value #RATIO_A} 的地形 key（词表内；**无默认值**）
   * @param terrainB 其余格的地形 key（词表内；**无默认值**；可与 A 相同）
   * @param seed 随机种子；**必须由调用方显式给**（同 seed + 同 base + 同选区 ⇒ 逐字节相同）
   * @return 只有 {@code terrainBlocks} 可能非 {@code Unchanged} 的变更集
   * @throws IllegalArgumentException 空选区 / 图外格 / 词表外地形
   */
  public static MapChangeSet randomize(
      GameMap base, Set<HexCoord> hexes, String terrainA, String terrainB, long seed) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hexes, "hexes");
    Objects.requireNonNull(terrainA, "terrainA");
    Objects.requireNonNull(terrainB, "terrainB");
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
    return RegionRandomizer.randomize(base, hexes, terrainA, terrainB, RATIO_A, seed);
  }
}
