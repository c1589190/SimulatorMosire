package io.mosire.simos.calendar;

/**
 * 太阳视黄经（Meeus 低精度近似）：24 节气与天文季界共用的唯一黄经公式（设计稿 §5.1）。
 *
 * <p>公式（输入 JDN 整数，{@code n = JDN − 2451545.0}）：
 *
 * <pre>
 *   L = 280.460 + 0.9856474 × n          （平黄经，度）
 *   g = 357.528 + 0.9856003 × n          （平近点角，度）
 *   λ = L + 1.915 sin g + 0.020 sin 2g   （视黄经，度）
 * </pre>
 *
 * <p>★ <b>g 的单位是度，进 {@link Math#sin(double)} 之前必须 {@link Math#toRadians(double)}</b>——
 * 这是本类唯一容易写错的地方（直接把度当弧度会让立春日期差出好几天）。
 *
 * <p>输出归一化到 {@code [0,360)}。精度只够日粒度季界：2024 立春（λ=315°）算得 JD≈2460344.85， 即 2024-02-04 08:24
 * 前后，与真实立春同小时级；不用于天文导航。
 *
 * <p>本类纯函数、无状态；季界求解统一走 {@link SolarTerms}，不得在别处复制本公式（设计稿 §九）。
 */
public final class SolarLongitude {

  /** J2000.0 的 JDN（2000-01-01），公式零点。 */
  private static final double J2000_DAY_NUMBER = 2451545.0;

  /** 平黄经 L 的 J2000 常数项（度）。 */
  private static final double MEAN_LONGITUDE_AT_J2000 = 280.460;

  /** 平黄经 L 的日变化率（度/日）。 */
  private static final double MEAN_LONGITUDE_RATE = 0.9856474;

  /** 平近点角 g 的 J2000 常数项（度）。 */
  private static final double MEAN_ANOMALY_AT_J2000 = 357.528;

  /** 平近点角 g 的日变化率（度/日）。 */
  private static final double MEAN_ANOMALY_RATE = 0.9856003;

  /** 中心差一阶项系数（度）：1.915 sin g。 */
  private static final double EQUATION_OF_CENTER_FIRST = 1.915;

  /** 中心差二阶项系数（度）：0.020 sin 2g。 */
  private static final double EQUATION_OF_CENTER_SECOND = 0.020;

  private SolarLongitude() {}

  /**
   * 该 JDN 整数对应的太阳视黄经，单位度，范围 {@code [0,360)}。
   *
   * @param dayNumber JDN 整数（民用日；1 tick = 1 天）
   * @return 黄经 λ ∈ [0,360)
   */
  public static double longitude(long dayNumber) {
    double n = dayNumber - J2000_DAY_NUMBER;
    double meanLongitude = MEAN_LONGITUDE_AT_J2000 + MEAN_LONGITUDE_RATE * n;
    double meanAnomalyDegrees = MEAN_ANOMALY_AT_J2000 + MEAN_ANOMALY_RATE * n;
    // ★ g 是角度：sin 的入参必须是弧度，所以先 toRadians 再进两个 sin 项。
    double meanAnomalyRadians = Math.toRadians(meanAnomalyDegrees);
    double longitude =
        meanLongitude
            + EQUATION_OF_CENTER_FIRST * Math.sin(meanAnomalyRadians)
            + EQUATION_OF_CENTER_SECOND * Math.sin(2.0 * meanAnomalyRadians);
    return normalize360(longitude);
  }

  /**
   * 把任意角度归一化到 {@code [0,360)}（公式内部与季界查询共用）。
   *
   * @param degrees 任意角度（调用方保证有限）
   * @return 归一化角度
   */
  static double normalize360(double degrees) {
    return (degrees % 360.0 + 360.0) % 360.0;
  }
}
