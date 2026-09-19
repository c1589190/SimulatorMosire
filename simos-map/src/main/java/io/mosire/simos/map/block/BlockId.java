package io.mosire.simos.map.block;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Collection;
import java.util.Comparator;

/**
 * 地形块的**身份**（P5，用户裁定：确定性）。
 *
 * <p>★ **由内容派生，不带序号、不带随机、不带时钟**：{@code <terrain>@<最小 hex>}（如 {@code plains@-5_-59}， {@link
 * HexCoord#toString()} 的 {@code "q_r"} 形式）。同一 hex 集合、同一地形 key ⇒ **永远同一个 {@code BlockId}** ——
 * 两次重建、跨进程、跨机器都逐字节相同。若用自增序号，同一状态两次运行会得到不同 id，往返/重放/断言全不成立。
 *
 * <p>★ **最小 hex** = 集合在 {@link HexCoord} 自然序（q 升、r 升）下的最小者。故 id 也是"这一块从哪开始"的可读线索。
 *
 * <p>★ {@link #toString()} 与 {@link #parse(String)} 是它作为 JSON Map 键的**唯一一对**序列化形式（铁律 1：串是
 * 定位方式，类型才是身份）。地形 key 里**不得含 {@code '@'}**（词表的 7 个 key 都不含）——否则 {@code parse} 切分会有歧义，故在构造期就拒。
 *
 * @param terrain 地形 key（{@code GameMap.terrainTypes} 的键）；**空白或含 {@code '@'} 即抛**
 * @param minHex 块内自然序最小的 hex；**不得为 null**
 */
public record BlockId(String terrain, HexCoord minHex) implements Comparable<BlockId> {

  /** JSON 边界的分隔符：{@code terrain@q_r}。地形 key 不含它（构造期守卫），故切分无歧义。 */
  public static final char SEPARATOR = '@';

  public BlockId {
    if (terrain == null || terrain.isBlank()) {
      throw new IllegalArgumentException("terrain 不得为空白");
    }
    if (terrain.indexOf(SEPARATOR) >= 0) {
      throw new IllegalArgumentException("terrain 不得含 '" + SEPARATOR + "': " + terrain);
    }
    if (minHex == null) {
      throw new IllegalArgumentException("minHex 不得为 null");
    }
  }

  /**
   * 由地形与 hex 集合派生 id。**纯函数**：与入参集合的迭代序无关（取自然序最小者）。
   *
   * @throws IllegalArgumentException hex 集合为空（空块没有最小 hex，也没有身份）
   */
  public static BlockId of(String terrain, Collection<HexCoord> hexes) {
    if (hexes == null || hexes.isEmpty()) {
      throw new IllegalArgumentException("空 hex 集合没有 BlockId: " + terrain);
    }
    return new BlockId(terrain, hexes.stream().min(Comparator.naturalOrder()).orElseThrow());
  }

  /** 规范串：{@code terrain@q_r}（如 {@code plains@-5_-59}）。 */
  @Override
  public String toString() {
    return terrain + SEPARATOR + minHex;
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * <p>非法输入一律抛 {@link IllegalArgumentException}：{@code null}/无分隔符/任一侧为空由本方法显式判形抛出； hex 一侧的形态错由
   * {@link HexCoord#parse(String)} 抛（{@link NumberFormatException} 是其子类）。
   */
  public static BlockId parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("非法 BlockId: null");
    }
    int i = text.indexOf(SEPARATOR);
    if (i <= 0 || i == text.length() - 1) {
      throw new IllegalArgumentException("非法 BlockId: " + text);
    }
    return new BlockId(text.substring(0, i), HexCoord.parse(text.substring(i + 1)));
  }

  /** 先按地形 key、再按最小 hex（与 {@link HexCoord#compareTo} 同形）——块表排序用，可逐字节复现。 */
  @Override
  public int compareTo(BlockId o) {
    int c = terrain.compareTo(o.terrain);
    return c != 0 ? c : minHex.compareTo(o.minHex);
  }
}
