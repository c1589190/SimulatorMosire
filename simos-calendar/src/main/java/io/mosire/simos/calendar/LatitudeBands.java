package io.mosire.simos.calendar;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * 纬度分带配置与判定：按六角坐标的南北轴 {@code r} 分北温带 / 热带 / 南温带（设计稿 §5.3，D-019）。
 *
 * <p><b>坐标约定</b>：地图 {@code hexToPixel.y = 1.5r}，<b>负 r = 北</b>、{@code r=0} 为赤道； {@link
 * CalendarDefaults#NORTH_IS_NEGATIVE} 记录本默认。构造参数 {@code northMax}/{@code southMin}
 * <b>一律按“北负”约定表达</b>：期望 {@code northMax < 0 < southMin}（本类只强制校验 {@code northMax < southMin}）。{@code
 * northIsNegative} 描述的是<b>原始坐标</b>的轴向：
 *
 * <pre>
 *   signed = northIsNegative ? c : −c;
 *   signed ≤ northMax  ⇒ NORTH_TEMPERATE
 *   signed ≥ southMin  ⇒ SOUTH_TEMPERATE
 *   其余               ⇒ TROPICS
 * </pre>
 *
 * <p>例：{@code of(-40, 40, true)} ⇒ {@code r=-100} 北、{@code r=0} 热带、{@code r=100} 南； {@code of(-40,
 * 40, false)} 用于“原始坐标南正”的世界，{@code c=100} 取反后得北温带。
 *
 * <p><b>未配置降级</b>：{@link #unconfigured()} 的 {@code configured() == false}， {@link #zoneOf(long)} 恒
 * NORTH_TEMPERATE（等于“全球北半球四季”）；三个数值访问器在未配置时 <b>抛 {@link
 * IllegalStateException}</b>——没有可诚实返回的带宽，调用方必须先用 {@link #configured()} 判别（读口据此输出 {@code zoneSource:
 * fallback}）。
 */
public final class LatitudeBands {

  /** 未配置占位值：不参与任何判定，访问器不会把它返回给调用方。 */
  private static final long UNCONFIGURED_BOUND = 0L;

  private final boolean configured;
  private final long northMax;
  private final long southMin;
  private final boolean northIsNegative;

  private LatitudeBands(boolean configured, long northMax, long southMin, boolean northIsNegative) {
    this.configured = configured;
    this.northMax = northMax;
    this.southMin = southMin;
    this.northIsNegative = northIsNegative;
  }

  /**
   * 未配置分带：{@code configured() == false}，{@link #zoneOf(long)} 恒 NORTH_TEMPERATE。
   *
   * <p>这是 D-018 补裁的降级口径：全球按北半球四季处理，读口标 {@code zoneSource: fallback}。
   */
  public static LatitudeBands unconfigured() {
    return new LatitudeBands(false, UNCONFIGURED_BOUND, UNCONFIGURED_BOUND, true);
  }

  /**
   * 配置分带：{@code northMax}/{@code southMin} 按北负约定（期望 {@code northMax < 0 < southMin}）。
   *
   * @param northMax 北温带与热带的分界（含）：{@code signed ≤ northMax} ⇒ 北温带
   * @param southMin 热带与南温带的分界（含）：{@code signed ≥ southMin} ⇒ 南温带
   * @param northIsNegative 原始坐标是否负数为北（本默认 true，见 {@link CalendarDefaults#NORTH_IS_NEGATIVE}）
   * @throws IllegalArgumentException {@code northMax ≥ southMin}
   */
  public static LatitudeBands of(long northMax, long southMin, boolean northIsNegative) {
    if (northMax >= southMin) {
      throw new IllegalArgumentException(
          "northMax 必须小于 southMin（按北负约定，" + northMax + " / " + southMin + "）");
    }
    return new LatitudeBands(true, northMax, southMin, northIsNegative);
  }

  /** 是否已配置分带；false = fallback（全球北半球四季，不假装已分带）。 */
  public boolean configured() {
    return configured;
  }

  /**
   * 北温带与热带的分界（北负约定，含）。
   *
   * @throws IllegalStateException 未配置分带
   */
  public long northMax() {
    requireConfigured();
    return northMax;
  }

  /**
   * 热带与南温带的分界（北负约定，含）。
   *
   * @throws IllegalStateException 未配置分带
   */
  public long southMin() {
    requireConfigured();
    return southMin;
  }

  /**
   * 原始坐标的轴向：true = 负数为北。
   *
   * @throws IllegalStateException 未配置分带
   */
  public boolean northIsNegative() {
    requireConfigured();
    return northIsNegative;
  }

  /**
   * 单个南北坐标（{@code r}）的纬度带：先按 {@code northIsNegative} 折算成北负约定，再与两条界比较。
   *
   * <p>未配置时恒返回 NORTH_TEMPERATE（fallback）。
   */
  public LatitudeZone zoneOf(long northSouthCoord) {
    if (!configured) {
      return LatitudeZone.NORTH_TEMPERATE;
    }
    long signed = signedCoordinate(northSouthCoord);
    if (signed <= northMax) {
      return LatitudeZone.NORTH_TEMPERATE;
    }
    if (signed >= southMin) {
      return LatitudeZone.SOUTH_TEMPERATE;
    }
    return LatitudeZone.TROPICS;
  }

  /** {@code zoneOf(at.r())}；季节 API 直接收 {@link HexCoord}（D-019），本批不读地形。 */
  public LatitudeZone zoneOf(HexCoord at) {
    Objects.requireNonNull(at, "at");
    return zoneOf(at.r());
  }

  /** 把原始南北坐标折算到本类的“北负”约定（负 = 北）：包内供热带雨旱相位判断使用。 */
  long signedCoordinate(long northSouthCoord) {
    return northIsNegative ? northSouthCoord : -northSouthCoord;
  }

  private void requireConfigured() {
    if (!configured) {
      throw new IllegalStateException(
          "分带未配置（zoneSource: fallback）：northMax/southMin/northIsNegative 没有已配置的值");
    }
  }
}
