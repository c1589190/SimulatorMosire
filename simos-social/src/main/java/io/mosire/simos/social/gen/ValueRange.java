package io.mosire.simos.social.gen;

/**
 * 一个标量的**文档值 ± 抖动 + 硬夹紧**区间：由 seed 决定取区间里的哪一点。
 *
 * <p>★ 语义（逐字对应 {@code config/worldgen/v17levant-nations.json} 的 {@code randomization} 块）：
 *
 * <ol>
 *   <li>{@code enabled=false} ⇒ 构造方给的是 {@link #exact(double)}（{@code jitterPct=0}）⇒ {@link
 *       #resolve} 恒返回文档值， 与开启随机化之前逐字节相同。
 *   <li>{@code enabled=true} ⇒ 抽 {@code u ∈ [0,1)}，取 {@code documented × (1 + jitterPct × (2u −
 *       1))}，**再**用 {@code [clampMin, clampMax]} 夹住。
 * </ol>
 *
 * <p>★ **clamp 是硬边界**：抖动不得越过它。当 clamp 不生效时，取值在 {@code documented × (1 ± jitterPct)} 上均匀；clamp
 * 生效时会把越界的点压到边界上（不是重新均匀）。
 *
 * <p>★ **{@link #min()} / {@link #max()} 是"解析界"**：= clamp 后的抖动区间端点。用例可以据此断言"任意 seed
 * 的取值都落在解析界内"——这条能抓住"抖动忘了夹紧 / 加了固定偏移 / 只抖了一半区间"。{@link #isExact()} ⇔ {@code min == max}（{@code
 * jitterPct=0}，或抖动区间被 clamp 压成一点）。
 *
 * <p>★ **无 clamp 的标量**（{@code population}、{@code capitalTargetPopulation}）用无穷端点表示：clamp 缺省即"不夹紧"，
 * 而不是"夹到 0"。
 *
 * <p>★ 哈希与 {@link SettlementGenerator} 同源（SplitMix64，无状态、跨机恒定）：同一 {@code (seed, 盐)} 恒得同一点，**绝不** 用
 * {@code java.util.Random}。
 *
 * <p>★ 本类型不加任何 {@code isXxx()} 实例方法：{@code isExact} 是**静态**谓词（本仓铁律，见 {@link PlannedCity} 类注释）。
 *
 * @param documented 文档值（{@code enabled=false} 时的唯一取值）
 * @param jitterPct 抖动幅度（比例）；{@code 0} ⇒ 恰好取文档值
 * @param clampMin 下硬界（{@code -∞} ⇒ 不夹）
 * @param clampMax 上硬界（{@code +∞} ⇒ 不夹）
 */
public record ValueRange(double documented, double jitterPct, double clampMin, double clampMax) {

  public ValueRange {
    if (!Double.isFinite(documented)) {
      throw new IllegalArgumentException("documented 必须有限: " + documented);
    }
    if (!Double.isFinite(jitterPct) || jitterPct < 0.0) {
      throw new IllegalArgumentException("jitterPct 必须是有限非负数: " + jitterPct);
    }
    if (Double.isNaN(clampMin) || Double.isNaN(clampMax) || clampMin > clampMax) {
      throw new IllegalArgumentException(
          "clamp 必须满足 clampMin <= clampMax 且都不得是 NaN: " + clampMin + "/" + clampMax);
    }
  }

  /** 精确点（不抖动、不夹紧）：{@code enabled=false} 的国家参数走这条。 */
  public static ValueRange exact(double documented) {
    return new ValueRange(documented, 0.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
  }

  /** 解析界下界 = clamp(documented × (1 − jitterPct))。 */
  public double min() {
    return clamp(documented * (1.0 - jitterPct));
  }

  /** 解析界上界 = clamp(documented × (1 + jitterPct))。 */
  public double max() {
    return clamp(documented * (1.0 + jitterPct));
  }

  /** 是否恰好一点（{@code min == max}）。**静态谓词**（本仓不许 {@code isXxx()} 实例方法）。 */
  public static boolean isExact(ValueRange range) {
    return range.min() == range.max();
  }

  /**
   * 按 {@code (seed, 盐)} 取区间里的一点。**纯函数**：同 seed 同盐恒得同一点；异盐互不相关。
   *
   * @param salt 用途盐：同一国家的不同标量必须给互不相同的盐，否则"人口大的变体恰好也是城市化率高的变体"这类伪相关会系统性偏置
   */
  public double resolve(long seed, long salt) {
    double lower = min();
    double upper = max();
    if (lower == upper) {
      return lower; // 恰好一点（含 enabled=false 的文档值）
    }
    double unit = unit(splitmix64(seed ^ salt));
    return clamp(documented * (1.0 + jitterPct * (2.0 * unit - 1.0)));
  }

  private double clamp(double value) {
    if (value < clampMin) {
      return clampMin;
    }
    return value > clampMax ? clampMax : value;
  }

  /** SplitMix64：与 {@link SettlementGenerator} 同一搅动函数（同 seed 同盐必得同一点）。 */
  private static long splitmix64(long z) {
    z += 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** 64 位哈希 ⇒ [0,1) 的均匀量（取高 53 位）。 */
  private static double unit(long hashed) {
    return (hashed >>> 11) * 0x1.0p-53;
  }
}
