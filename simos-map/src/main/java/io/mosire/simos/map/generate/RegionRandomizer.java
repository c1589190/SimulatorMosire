package io.mosire.simos.map.generate;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.change.FieldDelta;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.terrain.TerrainCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * 框选随机化：把一个区域内的格按占比随机重分配给两种地形。
 *
 * <p>★ GSimulator 没有这个功能（最接近的 {@code LassoProcessor} 只做几何求内侧，全文零随机数调用）。
 *
 * <p>★ 确定性：随机源从 {@code (seed, RegionId)} 派生 ⇒ 同种子同结果，可复现、可往返测试。{@link Random} 的算法与 {@link
 * String#hashCode()} 都由 Java 规范钉死 ⇒ 跨 JVM 稳；不同区域各派各的流，互不串。
 *
 * <p>★ 返回变更集（铁律 2）：7 个组件里**只有 {@code hexes} 可能非 {@code Unchanged}**，其余 6 个一律 {@code
 * Unchanged}——改格的地形不等于改区域，**不 upsert region 本身**（区域内容没变）。空/未知 region 或目标格全在图外 ⇒ 7 个全 {@code
 * Unchanged}（{@code Upsert} 构造期拒空）。
 *
 * <p>★ **高度一律不动**：重分配 = {@code new HexCell(新地形, 原 height)}。海拔是落盘的一等公民（L7）， 也是 {@link RiverBuilder}
 * 的输入——顺手"重算高度"会改掉水系。
 */
public final class RegionRandomizer {

  private RegionRandomizer() {}

  /**
   * 把 {@code region} 内的格逐格 Bernoulli 重分配给两种地形，返回新的 {@link MapChangeSet}。
   *
   * <p>★ 随机源**一个 region 一个**（{@link #rngFor}：{@code new Random(seed * 31L +
   * region.value().hashCode())}）， **不是每格一个**——与 {@link RiverBuilder} 的逐格 {@code rngFor}
   * 形态不同，别照抄那边。目标格 = region 的 hexes 里**同时在图纸上**的格，按 {@link HexCoord} 自然序逐格消费一次 {@code
   * nextDouble()}（ {@code < ratioA} ⇒ 取 A，否则取 B）；不在图纸的格**跳过且不消费随机数**——图外的格不影响图内格的结果。 {@code
   * nextDouble()} ∈ [0,1) ⇒ {@code ratioA = 1} 恒取 A、{@code ratioA = 0} 恒取 B，无需为边界特判。
   *
   * <p>★ 校验顺序：先 {@code map}/{@code region} 的 null 守卫，再 {@link TerrainCatalog#of} 验两个地形 key（未知 key
   * 抛**它自己的** IAE——不包不吞、不改写消息），最后 {@code ratioA} 的范围。**先校验完再算** ⇒ 参数错时一定听得见，与区域空不空无关。范围判据写成 {@code
   * !(ratioA >= 0.0 && ratioA <= 1.0)}：NaN 与任何数比较全是 false，"或"形态会把它静默漏过。**不为** {@code
   * terrainA.equals(terrainB)} 加守卫—— 那是合法输入（两种地形相同 ⇒ 全图同地形，占比退化）。
   *
   * <p>★ 不改入参：只读 {@code map}，产出全部走变更集；upsert 的插入序 = 处理序（{@code HexCoord} 自然序）。
   *
   * @param map 现图（只读；目标格与原高度取自它）
   * @param region 目标区域的身份；未知 id ⇒ 空变更集，不抛
   * @param terrainA 占比为 {@code ratioA} 的地形 key
   * @param terrainB 其余格的地形 key
   * @param ratioA 取 A 的期望占比，含边界 [0,1]
   * @param seed 随机种子；与 {@code region} 一起派生随机源
   * @return 只有 {@code hexes} 可能非 {@code Unchanged} 的变更集；无目标格 ⇒ 7 个全 {@code Unchanged}
   */
  public static MapChangeSet randomize(
      GameMap map, RegionId region, String terrainA, String terrainB, double ratioA, long seed) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(region, "region");
    // 调用只为校验：未知 key 由词表自己抛（R-12-h 不包不吞）；格上存的是 key 本身，类型定义已在词表里。
    TerrainCatalog.of(terrainA);
    TerrainCatalog.of(terrainB);
    if (!(ratioA >= 0.0 && ratioA <= 1.0)) {
      // ★ 非"或"形态：NaN 与任何数比较全是 false，`ratioA < 0 || ratioA > 1` 会把 NaN 静默漏过。
      throw new IllegalArgumentException("ratioA 必须在 [0,1]: " + ratioA);
    }

    Region target = map.regions().get(region);
    Map<String, HexCell> upserts = new LinkedHashMap<>();
    if (target != null) {
      Random rng = rngFor(region, seed);
      for (HexCoord at : target.hexes().stream().sorted().toList()) {
        HexCell cell = map.hexes().get(at);
        if (cell == null) {
          continue; // 不在图纸：跳过且不消费随机数（图外的格不影响图内格的结果）
        }
        String terrain = rng.nextDouble() < ratioA ? terrainA : terrainB;
        // ★ 组件顺序 (terrain, height)；高度不动（L7：它是 RiverBuilder 的输入）。
        upserts.put(at.toString(), new HexCell(terrain, cell.height()));
      }
    }
    return new MapChangeSet(
        upserts.isEmpty() ? new FieldDelta.Unchanged<>() : new FieldDelta.Upsert<>(upserts),
        new FieldDelta.Unchanged<>(), // regions：区域内容没变，不 upsert region 本身
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>());
  }

  /**
   * 一个区域的专属随机源：{@code (seed, RegionId)} 的纯函数（**一个 region 一个 RNG**，不是每格一个——与 {@link RiverBuilder}
   * 的逐格 {@code rngFor} 形态不同）。
   *
   * <p>{@link Random} 的 LCG 算法与 {@link String#hashCode()} 都由 Java 规范钉死 ⇒ 跨 JVM 同种子同序列（不用 {@code
   * ThreadLocalRandom}——无 seed、非确定用途）。
   *
   * <p>★ **构造与消费分处两个方法是有意的**：SpotBugs 的 {@code DMI_RANDOM_USED_ONLY_ONCE} 是**过程内**检测（只数本方法内的
   * 调用点、不看循环），RNG 与唯一的 {@code nextDouble()} 同在一个方法里会被误报成"建了就只用一次"。抽成 helper 后误报消失， **语义一字不变**（同一个
   * region 的同一个种子仍派生出同一条流、消费顺序不变）；**别为了省一个方法把这行内联回去**。
   */
  private static Random rngFor(RegionId region, long seed) {
    return new Random(seed * 31L + region.value().hashCode());
  }
}
