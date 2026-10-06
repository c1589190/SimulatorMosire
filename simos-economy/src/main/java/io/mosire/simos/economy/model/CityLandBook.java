package io.mosire.simos.economy.model;

import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>城市土地/承载的 props 读口（P6）</b>：把 {@code City.props}/{@code SocialCity.props} 里的具名键读成 {@link
 * CityLand}。键名在本类集中为常量，禁止调用方散落字符串。
 *
 * <p>★★ <b>为什么住 economy、由组合根传 props 进来</b>（铁律 3/4）：城市权威在 map/social，economy 不反查 {@code
 * GameMap}/{@code SocialData}。app 组合根同时看得见两边，按“把 props 传给读口”装配：
 *
 * <pre>
 * CityLand land = CityLandBook.fromProps(city.props());          // simos-map 的 City
 * CityLand land = CityLandBook.fromProps(socialCity.props());    // app 组合根拿 SocialCity 的 props
 * </pre>
 *
 * <p>★ <b>缺键语义 = 确定性默认值</b>：全部字段缺省 {@code 0}（{@link #DEFAULTS}）。零承载读作“尚未初始化城市土地”， {@link
 * CityLand#advance()} 对它不凭空扩建；要进入探针扩建/辐射口径，调用方必须显式写入 {@code capacity}/{@code radiusHex} 等键（P9 对拍按
 * fixture 的 {@code capacity=10, radiusHex=4} 落 props）。
 *
 * <p>★ <b>读到就认，读不懂就抛</b>：键缺席 ⇒ 默认值；键在但值不是整数（浮点带小数/空白串/布尔…）⇒ {@link
 * IllegalArgumentException}，不静默截断。值接受 {@link Number} 与非空白十进制 {@link String} （props 经 JSON 往返后 long
 * 可能以字符串保存，见 {@code WorldgenInitializeTool} 的 seed 口径）。
 */
public final class CityLandBook {

  /** 承载键：探针 {@code CityState.capacity}。 */
  public static final String PROP_CAPACITY = "cityLand.capacity";

  /** 已占承载键：探针 {@code CityState.usedCapacity}。 */
  public static final String PROP_USED_CAPACITY = "cityLand.usedCapacity";

  /** 扩建进度键：探针 {@code CityState.expansionProgress}。 */
  public static final String PROP_EXPANSION_PROGRESS = "cityLand.expansionProgress";

  /** 扩建次数键：探针 {@code CityState.expansionCount}。 */
  public static final String PROP_EXPANSION_COUNT = "cityLand.expansionCount";

  /** 城区比例键（‰）：探针 {@code CityState.builtAreaPerMille}。 */
  public static final String PROP_BUILT_AREA_PER_MILLE = "cityLand.builtAreaPerMille";

  /** 本格城区占地键：探针 {@code cityBuiltAreaMu(hex)}。 */
  public static final String PROP_BUILT_AREA_MU = "cityLand.builtAreaMu";

  /** 辐射半径键：探针 {@code CityState.radiusHex}。 */
  public static final String PROP_RADIUS_HEX = "cityLand.radiusHex";

  /** 本轮贸易量键：探针 {@code CityState.lastTradeVolume}。 */
  public static final String PROP_LAST_TRADE_VOLUME = "cityLand.lastTradeVolume";

  /** 累计贸易量键：探针 {@code CityState.cumulativeTradeVolume}。 */
  public static final String PROP_CUMULATIVE_TRADE_VOLUME = "cityLand.cumulativeTradeVolume";

  /**
   * ★ 全部键缺席时的确定性默认值：九个字段全 {@code 0}。
   *
   * <p>零承载 = 尚未初始化城市土地；调用方按 P9 口径显式写入 {@code capacity=10}/{@code radiusHex=4} 后才进入扩建/辐射。
   */
  public static final CityLand DEFAULTS = new CityLand(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

  private CityLandBook() {}

  /**
   * ★★ <b>从城市 props 读一个 {@link CityLand}</b>：逐键用 {@link #DEFAULTS} 兜底，值在但非法 ⇒ 抛。
   *
   * @param props {@code City.props()} 或 {@code SocialCity.props()} 的快照；不得为 null
   */
  public static CityLand fromProps(Map<String, Object> props) {
    Objects.requireNonNull(props, "props");
    return new CityLand(
        longOrDefault(props, PROP_CAPACITY, DEFAULTS.capacity()),
        longOrDefault(props, PROP_USED_CAPACITY, DEFAULTS.usedCapacity()),
        longOrDefault(props, PROP_EXPANSION_PROGRESS, DEFAULTS.expansionProgress()),
        longOrDefault(props, PROP_EXPANSION_COUNT, DEFAULTS.expansionCount()),
        longOrDefault(props, PROP_BUILT_AREA_PER_MILLE, DEFAULTS.builtAreaPerMille()),
        longOrDefault(props, PROP_BUILT_AREA_MU, DEFAULTS.builtAreaMu()),
        longOrDefault(props, PROP_RADIUS_HEX, DEFAULTS.radiusHex()),
        longOrDefault(props, PROP_LAST_TRADE_VOLUME, DEFAULTS.lastTradeVolume()),
        longOrDefault(props, PROP_CUMULATIVE_TRADE_VOLUME, DEFAULTS.cumulativeTradeVolume()));
  }

  /** 读一个具名 long：缺席 ⇒ 默认值；数字（浮点不得带小数）或十进制字符串 ⇒ 该值；其余 ⇒ 具名抛。 */
  private static long longOrDefault(Map<String, Object> props, String key, long defaultValue) {
    Object value = props.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      if (number instanceof Double || number instanceof Float) {
        double asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble) || Double.compare(asDouble, Math.rint(asDouble)) != 0) {
          throw new IllegalArgumentException("城市土地属性 " + key + " 必须是整数: " + value);
        }
      }
      return number.longValue();
    }
    if (value instanceof String text) {
      if (text.isBlank()) {
        throw new IllegalArgumentException("城市土地属性 " + key + " 不得为空白: " + value);
      }
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("城市土地属性 " + key + " 必须是整数: " + value, e);
      }
    }
    throw new IllegalArgumentException("城市土地属性 " + key + " 必须是整数: " + value);
  }
}
