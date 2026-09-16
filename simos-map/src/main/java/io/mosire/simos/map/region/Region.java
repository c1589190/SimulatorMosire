package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Set;

/**
 * 权威区域：一组 hex 的**命名**集合，**连同它的边界**。
 *
 * <p>★ 边界是**组件**（用户裁决 U2，理由「要不然数据持久化会出问题」）：落盘、往返、进变更集都自然成立 —— 不需要为它单开字段。 代价是它可能与 {@code hexes}
 * 漂移，故**规范构造器把它钉死**：重算一遍，不等即抛。
 *
 * <p>★ GSimulator 的 {@code Province} 既无 name 字段（名字是 map 的键）也无边界字段，此处都补上。
 *
 * <p>★ {@code hexes} 用 {@link Set#copyOf}（**不保序**）是有意的：它是**集合语义**，迭代序不该被依赖。需要保序的只有 {@code
 * TerrainCatalog}（落盘的是它），两处的理由不同，**不要统一**。这条在 U2 之后多了一层后果：{@code Region.equals} 逐组件比较，故 {@link
 * RegionBoundary#of} 必须是 {@code hexes} 的**纯函数**（与迭代序无关），否则内容相同的两个 Region 会不相等。
 */
public record Region(
    RegionId id, String name, Set<HexCoord> hexes, RegionBoundary boundary, RegionMeta meta) {

  public Region {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    hexes = Set.copyOf(hexes);
    if (boundary == null) {
      throw new IllegalArgumentException("boundary 不得为 null");
    }
    if (meta == null) {
      meta = RegionMeta.empty();
    }
    // ★ U2 的钉子：边界必须与 hexes 一致。这一步让"漂移"在构造期就不可能存在。
    RegionBoundary recomputed = RegionBoundary.of(hexes);
    if (!recomputed.equals(boundary)) {
      throw new IllegalArgumentException(
          "boundary 与 hexes 不一致：hexes 重算得 " + recomputed + "，传入的是 " + boundary);
    }
  }

  /**
   * ★ **正常代码走这个工厂**：边界**由 hexes 算出来**，不手写。
   *
   * <p>直接调构造器只在反序列化（边界已由存档给出、需要被校验）时才合理。
   */
  public static Region of(RegionId id, String name, Set<HexCoord> hexes, RegionMeta meta) {
    return new Region(id, name, hexes, RegionBoundary.of(hexes), meta);
  }

  /** 是否含某格。**O(1)** —— GSimulator 是 {@code List<String>.contains} 线性扫描。 */
  public boolean contains(HexCoord c) {
    return hexes.contains(c);
  }

  /**
   * ★ **必须重算边界** —— U2 落地后这是最容易写错的一处。写成 {@code new Region(id, name, newHexes, boundary, meta)}
   * 会被构造器当场抛掉（这正是钉子生效），但**别指望它**：直接用 {@link #of} 更省事，也让意图明了。
   */
  public Region withHexes(Set<HexCoord> newHexes) {
    return Region.of(id, name, newHexes, meta);
  }

  /** 改名不动内容 ⇒ 边界不变，可直接复用（**这是唯一可以原样传 boundary 的地方**）。 */
  public Region withName(String newName) {
    return new Region(id, newName, hexes, boundary, meta);
  }
}
