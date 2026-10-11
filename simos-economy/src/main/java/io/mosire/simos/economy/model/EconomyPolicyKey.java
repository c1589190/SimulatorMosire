package io.mosire.simos.economy.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★★ <b>W10（2026-10-11，用户裁定口径「凡"行为"一律做成 GM 可调的预设」）：经济侧行为值的政策键表</b>。
 *
 * <p>★★ <b>它解决什么</b>：这些数过去是<b>编译期常量</b>（{@code public static final long}）—— 要改一个数就得重编译、重播种，
 * 而它们恰恰是"跑完真实世界再标定"的旋钮（W6 标定批给的五个数）。本表把每个键登记成<b>一个可运行期写的政策预设</b>：
 *
 * <pre>
 * 读：{@code EconomyData.economyPolicyOf(key)} —— 表里有键 ⇒ 状态值唯一权威；表里没有 ⇒ {@link #defaultValue()}（= <b>现值</b>）
 * 写：GM 窄工具 {@code simos.economy.setEconomyPolicy} → 命令 {@code economy.SetEconomyPolicy}
 * </pre>
 *
 * <p>★★ <b>"缺省中性"是硬判据</b>：{@link #defaultValue()} 一律等于<b>改造前的那个常量值</b> ⇒ 一个从没被 GM 写过的世界 逐值等于改动前（本仓
 * I-P8 / I-C2 那一族的口径）。★ 命令层拒绝"写入与现值逐字相同"的空操作。
 *
 * <p>★ <b>有效域</b>：每个键带 {@link #minValue()} / {@link #maxValue()}，越域在<b>命令边界</b>（具名拒）与
 * <b>构造期</b>（{@link EconomyMeta} 的守卫）两处 fail-closed —— 一个越域的政策值不是"调得激进"，是坏数据。
 *
 * <p>★ <b>没有"未设"这一档</b>：缺键就是缺省（不写"等于现值的冗余行"）；政策表为空 = 全部走缺省。
 *
 * @see EconomyMeta#economyPolicies()
 */
public enum EconomyPolicyKey {

  /**
   * 饥荒死亡率上限（ppm/tick）：断顿比例 1000‰ 时的日饥荒死亡率。现值 = {@code FamineMortality.FAMINE_MAX_PPM_PER_TICK} =
   * 1000。
   */
  FAMINE_MAX_PPM_PER_TICK("饥荒死亡率上限（ppm/tick）", 1_000L, 0L, 1_000_000L),

  /**
   * 阶层滑落速度斜率（‰/比值）：{@code speed = ⌊ratio × 本值 ÷ 1000⌋}。现值 = {@code
   * ClassSlideSettlement.SLIDE_SPEED_PER_MILLE_PER_RATIO} = 100。
   */
  SLIDE_SPEED_PER_MILLE_PER_RATIO("阶层滑落速度斜率（‰/比值）", 100L, 0L, 1_000L),

  /** 阶层每滑落一级的"成本"（比值·天）：{@code requiredDays = ⌈轮天数 × 本值 ÷ speed⌉}。现值 = 1000。 */
  SLIDE_COST_PER_LEVEL("阶层滑落每级成本（比值·天）", 1_000L, 0L, 1_000_000_000L),

  /**
   * 阶层滑落的"市场轮天数"（天）：{@code requiredDays} 的分母侧。现值 = {@code
   * MarketSettlement.MARKET_RESTOCK_INTERVAL_DAYS} = 5。
   */
  SLIDE_ROUND_DAYS("阶层滑落的市场轮天数（天）", 5L, 1L, 1_000L),

  /**
   * 铸币产出率（毫/规模·周期）—— ★ 覆盖 {@code Industry.moneyOutputPerUnit} 的量价（见 {@code MintSettlement}）。现值 =
   * 500。
   */
  MINT_MILLI_PER_FURNACE_CYCLE("铸币产出率（毫/规模·周期）", 500L, 0L, Long.MAX_VALUE / 1_000L),

  /** 铸币劳动份额（‰ 城镇池日劳动）—— ★ <b>仍只在创世播种时消费</b>（运行期改它要重写劳动配额，本批不做，见实现账本"未完成项"）。现值 = 30。 */
  MINT_LABOR_SHARE_PER_MILLE("铸币劳动份额（‰ 城镇池日劳动）", 30L, 0L, 1_000L),

  /** 借贷<b>基准</b>利率（‰/周期）：市场区没有自己的价表时的起始利率（α 已有独立写口，不在本表）。现值 = 20。 */
  LOAN_BASE_RATE_PER_MILLE("借贷基准利率（‰/周期）", 20L, 0L, 1_000_000L),

  /** 转业速度上限（‰ 每市场轮每户）：{@code ≤ max(1, ⌊人口 × 本值 ÷ 1000⌋)}。现值 = 100。 */
  MIGRATION_SPEED_CAP_PER_MILLE("转业速度上限（‰/轮·户）", 100L, 0L, 1_000L),

  /** 转业速度斜率（‰/收益差比）：收益差驱动的转业速度斜率。现值 = 100。 */
  PROFIT_GAP_SPEED_SLOPE_PER_MILLE("转业速度斜率（‰/收益差比）", 100L, 0L, 1_000L);

  private final String description;
  private final long defaultValue;
  private final long minValue;
  private final long maxValue;

  EconomyPolicyKey(String description, long defaultValue, long minValue, long maxValue) {
    this.description = description;
    this.defaultValue = defaultValue;
    this.minValue = minValue;
    this.maxValue = maxValue;
  }

  /** 中文说明（catalog / 日志用；**不是**稳定键 —— 稳定键是本枚举的 {@code name()}）。 */
  public String description() {
    return description;
  }

  /** ★★ <b>缺省值 = 改造前的那个常量值</b>（"缺省中性"的唯一落点）。 */
  public long defaultValue() {
    return defaultValue;
  }

  /** 有效域下界（含）。 */
  public long minValue() {
    return minValue;
  }

  /** 有效域上界（含）。 */
  public long maxValue() {
    return maxValue;
  }

  /** 该值是否在有效域内（命令边界与 {@link EconomyMeta} 构造期共用这一处判据）。 */
  public boolean accepts(long value) {
    return value >= minValue && value <= maxValue;
  }

  /** ★ <b>政策表的规范形</b>：按枚举声明序排序的保序不可变表（缺省项不入表）—— 同一份配置必然给出同一串字节。 */
  public static Map<EconomyPolicyKey, Long> normalize(Map<EconomyPolicyKey, Long> configured) {
    if (configured == null || configured.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<EconomyPolicyKey, Long> ordered = new LinkedHashMap<>();
    for (EconomyPolicyKey key : values()) { // 声明序 = 规范序（不用 Map.copyOf：它不是内容的纯函数）
      Long value = configured.get(key);
      if (value != null) {
        ordered.put(key, value);
      }
    }
    return java.util.Collections.unmodifiableMap(ordered);
  }

  /** 全部键的缺省值表（读口/文档用；保序）。 */
  public static Map<EconomyPolicyKey, Long> defaults() {
    LinkedHashMap<EconomyPolicyKey, Long> all = new LinkedHashMap<>();
    for (EconomyPolicyKey key : values()) {
      all.put(key, key.defaultValue);
    }
    return java.util.Collections.unmodifiableMap(all);
  }
}
