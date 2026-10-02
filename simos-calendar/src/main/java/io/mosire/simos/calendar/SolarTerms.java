package io.mosire.simos.calendar;

import java.util.Objects;

/**
 * 二十四节气查询：当天节气 + 黄经跨越日（季界日）。
 *
 * <p><b>边界日定义</b>（设计稿 §5.1/§5.2）：本模块日号是 JDN 整数，{@link SolarLongitude#longitude(long)} 在该整数 JDN
 * 处取值（对应民用日正午）。对目标黄经 {@code target}，二分先找到整数区间下界 {@code d} 满足
 *
 * <pre>
 *   λ(d) &lt; target ≤ λ(d + 1)
 * </pre>
 *
 * 这只是二分的中间事实，不是最终边界日定义。跨越的 JD = d + f，其中
 *
 * <pre>
 *   f = normalize360(target − λ(d)) / normalize360(λ(d + 1) − λ(d))
 * </pre>
 *
 * 设计稿 §5.1 的边界日 = <b>含这次跨越的民用日</b>（当地午夜到午夜）= {@code floor(d + f + 0.5)}：{@code f ≥ 0.5} ⇒ {@code d
 * + 1}，否则 ⇒ {@code d}。季界当天算新季节第 1 天（{@code dayOfSeason = 1}），{@link #termOf(long)} 也按同一口径归属新节气。
 *
 * <p><b>实现</b>：先用平黄经速度迭代跳步到目标黄经附近（4 次迭代，误差 &lt; 1 天），再在预测值 ±3 天内用二分找区间下界； 局部窗口不成立时退回 ±370
 * 天扫描；最后统一做半日取整。全部是纯计算，同一输入结果稳定；等距时取较晚（较大 JDN）的边界日。 所有查询共用 {@link SolarLongitude}
 * 的唯一黄经公式，本类不复制任何节气/季界表。
 *
 * <p><b>已实测锚点</b>（给 C7 当 oracle；dayNumber 均为本模块口径的 JDN 整数）：
 *
 * <ul>
 *   <li>2024 立春 λ=315°：真实跨越 JD≈2460344.847 ⇒ 边界日 <b>2460345</b>（2024-02-04）。
 *   <li>2024 春分/夏至/秋分/冬至：真实跨越 JD≈2460389.625 / 2460482.367 / 2460576.028 / 2460665.886 ⇒ 边界日
 *       <b>2460390 / 2460482 / 2460576 / 2460666</b>（2024-03-20 / 06-20 / 09-22 / 12-21）。
 *   <li>儒略 1445（默认锚点年）：立春 <b>2248869</b>、立夏 <b>2248960</b>、立秋 <b>2249055</b>、秋分 <b>2249101</b>、立冬
 *       <b>2249146</b>、冬至 <b>2249190</b>（儒略历 01-25 / 04-26 / 07-30 / 09-14 / 10-29 / 12-12；标签由
 *       JulianCalendar.dateOf 实测）。
 *   <li>儒略 1445 白露 λ=165°：真实跨越 JD≈2249085.545 ⇒ 边界日 <b>2249086</b>（儒略历 1445-08-30；标签由
 *       JulianCalendar.dateOf 实测）。
 * </ul>
 *
 * <p>★ tick 120（默认锚点 + 节气季界）= 日号 2248965，λ≈49.35°，位于该年立夏（2248960）之后 ⇒ 夏，dayOfSeason=6、
 * daysInSeason=95、progress=5/95（因为 1445 立秋边界日变为 2249055，夏长 2249055−2248960=95）。热带雨季窗口 {@code
 * [45°,165°)} 在 1445 起于 2248960、终于 2249086 ⇒ dayOfSeason=6、daysInSeason=126、progress=5/126。
 */
public final class SolarTerms {

  /** 一个节气跨过的黄经（度）。 */
  private static final double DEGREES_PER_TERM = 15.0;

  /** 平黄经日变化率（度/日）；只用于“近似跳步”，精确值仍来自 {@link SolarLongitude}。 */
  private static final double MEAN_DAILY_MOTION = 0.9856474;

  /** 近似跳步迭代次数（每轮约缩小误差 30 倍；4 轮足够收敛到 ±1 天内）。 */
  private static final int APPROXIMATION_ITERATIONS = 4;

  /** 近似值附近的二分窗口半径（天）。 */
  private static final long LOCAL_SEARCH_RADIUS_DAYS = 3L;

  /** 局部窗口不成立时的回退扫描半径（天）：一年 365/366 天，回退 370 天必能覆盖一个周期。 */
  private static final long FALLBACK_SEARCH_RADIUS_DAYS = 370L;

  /** 找相邻年份同黄经边界日的搭桥步长（天）；相邻跨越日实际间隔 365 或 366 天，±3 天窗口兜住。 */
  private static final long YEAR_BRIDGE_DAYS = 365L;

  private SolarTerms() {}

  /**
   * 当天所在节气：按“含跨越的民用日”归属新节气。先按正午 λ 取候选 {@code base}；若下一个节气的边界民用日正是 {@code dayNumber}，返回下一个节气，否则返回
   * {@code base}。
   *
   * @param dayNumber JDN 整数
   * @return 当天所属节气（边界日归新节气）
   */
  public static SolarTerm termOf(long dayNumber) {
    int index = (int) Math.floor(SolarLongitude.longitude(dayNumber) / DEGREES_PER_TERM);
    SolarTerm base = SolarTerm.values()[index];
    SolarTerm next = SolarTerm.values()[(base.ordinal() + 1) % SolarTerm.values().length];
    return boundaryDayNumberForLongitude(next.longitude(), dayNumber) == dayNumber ? next : base;
  }

  /**
   * {@code nearDayNumber} 附近的该节气边界日（按天数距离最近；等距取较晚的一天）。
   *
   * @param term 目标节气（非空）
   * @param nearDayNumber 目标日附近的 JDN 整数
   * @return 离 nearDayNumber 最近的边界日（边界日 = 含黄经跨越的民用日）
   */
  public static long boundaryDayNumber(SolarTerm term, long nearDayNumber) {
    Objects.requireNonNull(term, "term");
    return boundaryDayNumberForLongitude(term.longitude(), nearDayNumber);
  }

  /**
   * 最近一次 {@code ≤ dayNumber} 的该节气边界日（当天是边界日时返回当天）。
   *
   * @param term 目标节气（非空）
   * @param dayNumber 查询日 JDN 整数
   * @return 最近一次边界日
   */
  public static long previousBoundaryDayNumber(SolarTerm term, long dayNumber) {
    Objects.requireNonNull(term, "term");
    return previousBoundaryDayNumberForLongitude(term.longitude(), dayNumber);
  }

  /**
   * 第一次 {@code > dayNumber} 的该节气边界日。
   *
   * @param term 目标节气（非空）
   * @param dayNumber 查询日 JDN 整数
   * @return 下一次边界日
   */
  public static long nextBoundaryDayNumber(SolarTerm term, long dayNumber) {
    Objects.requireNonNull(term, "term");
    return nextBoundaryDayNumberForLongitude(term.longitude(), dayNumber);
  }

  /**
   * 按黄经查询附近边界日（包内共享；热带雨季窗口按 double 黄经查询走这里，不另建第二套节气名）。
   *
   * @param targetLongitude 目标黄经（度，任意实数；内部归一化到 [0,360)）
   * @param nearDayNumber 目标日附近的 JDN 整数
   * @return 最近的边界日
   */
  static long boundaryDayNumberForLongitude(double targetLongitude, long nearDayNumber) {
    double target = SolarLongitude.normalize360(targetLongitude);
    long approximate = approximateBoundaryDay(target, nearDayNumber);
    long anchor = crossingNear(target, approximate);
    // anchor 一定是一个边界日；相邻年份的两个边界日离 anchor 最多约 366 天，±3 天窗口可找回。
    long next = crossingNear(target, anchor + YEAR_BRIDGE_DAYS);
    long previous = crossingNear(target, anchor - YEAR_BRIDGE_DAYS);
    return nearestTo(nearDayNumber, anchor, next, previous);
  }

  /**
   * 按黄经查询最近一次 ≤ dayNumber 的边界日（包内共享）。
   *
   * @param targetLongitude 目标黄经（度）
   * @param dayNumber 查询日 JDN 整数
   * @return 最近一次边界日
   */
  static long previousBoundaryDayNumberForLongitude(double targetLongitude, long dayNumber) {
    double target = SolarLongitude.normalize360(targetLongitude);
    long nearest = boundaryDayNumberForLongitude(target, dayNumber);
    if (nearest <= dayNumber) {
      return nearest;
    }
    // nearest 是“下一次”边界日 ⇒ 上一次 = 它往前约一年。
    return crossingNear(target, nearest - YEAR_BRIDGE_DAYS);
  }

  /**
   * 按黄经查询第一次 &gt; dayNumber 的边界日（包内共享）。
   *
   * @param targetLongitude 目标黄经（度）
   * @param dayNumber 查询日 JDN 整数
   * @return 下一次边界日
   */
  static long nextBoundaryDayNumberForLongitude(double targetLongitude, long dayNumber) {
    double target = SolarLongitude.normalize360(targetLongitude);
    long nearest = boundaryDayNumberForLongitude(target, dayNumber);
    if (nearest > dayNumber) {
      return nearest;
    }
    // nearest 是“上一次”边界日 ⇒ 下一次 = 它往后约一年。
    return crossingNear(target, nearest + YEAR_BRIDGE_DAYS);
  }

  /**
   * 近似跳步：把当前日按“黄经差 / 平黄经速度”移动，重复几轮后落在目标边界日 ±1 天内。
   *
   * <p>{@link #signedLongitudeOffset} 把黄经差折叠到 (-180,180]，因此本方法收敛到离 nearDayNumber 最近的那一次跨越（按黄经相位）；±1
   * 天的误差由 {@link #crossingNear} 的局部窗口消掉。
   */
  private static long approximateBoundaryDay(double target, long nearDayNumber) {
    long guess = nearDayNumber;
    for (int i = 0; i < APPROXIMATION_ITERATIONS; i++) {
      double offset = signedLongitudeOffset(guess, target);
      long step = Math.round(offset / MEAN_DAILY_MOTION);
      if (step == 0L) {
        break;
      }
      guess -= step;
    }
    return guess;
  }

  /** 找 guess 附近唯一的跨越区间下界，再做半日取整成边界民用日：先在 ±3 天内二分，失败再 ±370 天扫描；都找不到才抛（正常路径不会发生）。 */
  private static long crossingNear(double target, long guess) {
    Long local =
        binaryCrossingWithin(
            target, guess - LOCAL_SEARCH_RADIUS_DAYS, guess + LOCAL_SEARCH_RADIUS_DAYS);
    if (local != null) {
      return civilDayOfCrossing(local, target);
    }
    Long fallback = scanCrossingWithin(target, guess, FALLBACK_SEARCH_RADIUS_DAYS);
    if (fallback != null) {
      return civilDayOfCrossing(fallback, target);
    }
    throw new IllegalStateException(
        "在 ±" + FALLBACK_SEARCH_RADIUS_DAYS + " 天内找不到黄经 " + target + " 的跨越日");
  }

  /**
   * 把二分/扫描得到的跨越区间下界 {@code d}（满足 {@code λ(d) &lt; target ≤ λ(d+1)}）半日取整为边界民用日：
   *
   * <pre>
   *   f = normalize360(target − λ(d)) / normalize360(λ(d + 1) − λ(d))
   * </pre>
   *
   * 返回 {@code f ≥ 0.5 ? d + 1 : d}（等价 {@code floor(d + f + 0.5)}）。target=0° 由 {@link
   * SolarLongitude#normalize360(double)} 自然处理回绕，无需特判。分母是模块日变化率，实测约 0.95~1.02，恒为正。
   */
  private static long civilDayOfCrossing(long lowerNoonDay, double target) {
    double lowerLongitude = SolarLongitude.longitude(lowerNoonDay);
    double deltaTarget = SolarLongitude.normalize360(target - lowerLongitude);
    double deltaStep =
        SolarLongitude.normalize360(SolarLongitude.longitude(lowerNoonDay + 1L) - lowerLongitude);
    double fraction = deltaTarget / deltaStep;
    return fraction >= 0.5 ? lowerNoonDay + 1L : lowerNoonDay;
  }

  /**
   * 在 [low, high] 内二分找跨越区间下界 d：窗口足够窄时 {@link #signedLongitudeOffset} 从负单调升到非负， d 为最后一个 offset &lt;
   * 0 的日。最终边界民用日由 {@link #crossingNear} 统一做半日取整。
   *
   * @return 区间下界 d；窗口不含跨越（低端已非负或高端仍为负）时返回 {@code null}
   */
  private static Long binaryCrossingWithin(double target, long low, long high) {
    if (!(signedLongitudeOffset(low, target) < 0.0) || signedLongitudeOffset(high, target) < 0.0) {
      return null;
    }
    long lo = low;
    long hi = high;
    while (hi - lo > 1L) {
      long mid = lo + (hi - lo) / 2L;
      if (signedLongitudeOffset(mid, target) < 0.0) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    return isBoundaryDay(lo, target) ? lo : null;
  }

  /** 从 center 向两侧由近到远扫描 ±radius 天，返回首个跨越区间下界；等距先查较晚一天（与 {@link #nearestTo} 同口径）。 */
  private static Long scanCrossingWithin(double target, long center, long radius) {
    for (long offset = 0L; offset <= radius; offset++) {
      if (offset == 0L) {
        if (isBoundaryDay(center, target)) {
          return center;
        }
        continue;
      }
      long later = center + offset;
      if (isBoundaryDay(later, target)) {
        return later;
      }
      long earlier = center - offset;
      if (isBoundaryDay(earlier, target)) {
        return earlier;
      }
    }
    return null;
  }

  /** 在三个候选边界日中取离 nearDayNumber 最近者；等距取较晚，保证结果稳定。 */
  private static long nearestTo(long nearDayNumber, long first, long second, long third) {
    long best = closer(first, second, nearDayNumber);
    return closer(best, third, nearDayNumber);
  }

  /** 返回 current/candidate 中离 nearDayNumber 更近者；等距取较晚。 */
  private static long closer(long current, long candidate, long nearDayNumber) {
    long currentDistance = Math.abs(current - nearDayNumber);
    long candidateDistance = Math.abs(candidate - nearDayNumber);
    if (candidateDistance < currentDistance) {
      return candidate;
    }
    if (candidateDistance == currentDistance && candidate > current) {
      return candidate;
    }
    return current;
  }

  /**
   * 二分用判定：目标相对黄经 {@code (λ(day) − target) mod 360} 是否在 day→day+1 之间发生回绕。
   *
   * <p>为 true 只说明 day 是跨越区间下界；最终边界民用日还要由 {@link #civilDayOfCrossing} 做半日取整。回绕恰对应 {@code λ(day) &lt;
   * target ≤ λ(day+1)}，且黄经单调递增时每年恰一次，对 target=0 也无需特判。
   */
  private static boolean isBoundaryDay(long dayNumber, double target) {
    return relativeAngle(dayNumber, target) > relativeAngle(dayNumber + 1L, target);
  }

  /** 相对黄经：{@code (λ(day) − target) mod 360} ∈ [0,360)。 */
  private static double relativeAngle(long dayNumber, double target) {
    return SolarLongitude.normalize360(SolarLongitude.longitude(dayNumber) - target);
  }

  /** 带符号黄经差，折叠到 (-180,180]：+ 表示已过 target，− 表示未到 target。 */
  private static double signedLongitudeOffset(long dayNumber, double target) {
    double wrapped = (SolarLongitude.longitude(dayNumber) - target) % 360.0;
    if (wrapped <= -180.0) {
      wrapped += 360.0;
    } else if (wrapped > 180.0) {
      wrapped -= 360.0;
    }
    return wrapped;
  }
}
