package io.mosire.simos.economy.model;

/**
 * ★★ <b>城市化迁移拉力（P8 纯值）</b>：把计划里的拉力公式落成一个不可变、无时钟、无随机的参数记录。
 *
 * <pre>
 * urbanPull = w1 × 人均交易量 + w2 × 商人密度 + w3 × 城市作坊利润
 *           − w4 × 粮价/缺口 − w5 × 城区拥挤
 * </pre>
 *
 * <p>★★ <b>本类只做整数算术</b>（不用 double，量纲纪律）：五个权重是具名参数，默认全 {@code 1}（见 {@link
 * #defaults()}）。<b>默认值只是占位</b>——P9 用 7HEX2 探针读数标定；本类不猜“正确量级”。
 *
 * <p>★★ <b>为什么住在 {@code simos-economy/model}</b>：公式读的是经济侧读数（交易量/作坊利润/粮价缺口）与 P6 的城市 承载（{@link
 * CityLand}），而 {@code simos-social} <b>不得依赖 economy 切片</b>；组合根 {@code simos-app} 同时 看得见
 * social/economy/map，故规划器住 app，策略值住 economy。★ 本类不新增 {@code EconomyData} 组件，也不被任何结算读——
 * 不显式调用它，旧路径逐值不变。
 *
 * <p>★★ <b>确定性</b>：{@link #urbanPull} 与 {@link #shouldMigrate} 都是入参的纯函数，不读时钟、不用随机、不遍历 map；{@link
 * #migrationQuota} 只用整数除法向下取整。
 *
 * @param tradeVolumeWeight 人均交易量项权重（w1）；不得为负
 * @param merchantDensityWeight 商人密度项权重（w2）；不得为负
 * @param workshopProfitWeight 城市作坊利润项权重（w3）；不得为负
 * @param grainPressureWeight 粮价/缺口项权重（w4）；不得为负
 * @param urbanCrowdingWeight 城区拥挤项权重（w5）；不得为负
 * @param migrationThreshold 进入迁移的拉力阈值（<b>≥</b>该值才迁）；不得为负
 * @param hysteresisMargin 迟滞余量：已在迁移的城市在 {@code pull ≥ 阈值 − 余量} 时继续；不得为负
 * @param migrationPerMille 每轮从农村池迁出的目标比例（‰）；必须 {@code ∈ [0, 1000]}
 * @param minimumSourceLotCount 每个源批次至少保留的人数（0 = 允许抽空）；不得为负
 */
public record MigrationPolicy(
    long tradeVolumeWeight,
    long merchantDensityWeight,
    long workshopProfitWeight,
    long grainPressureWeight,
    long urbanCrowdingWeight,
    long migrationThreshold,
    long hysteresisMargin,
    long migrationPerMille,
    long minimumSourceLotCount) {

  /** ★ P8 默认权重（探针公式的五个 w 都先给 1；P9 标定，不在本轮猜量级）。 */
  public static final long DEFAULT_TRADE_VOLUME_WEIGHT = 1L;

  /** 默认商人密度权重（w2 = 1，待 P9 标定）。 */
  public static final long DEFAULT_MERCHANT_DENSITY_WEIGHT = 1L;

  /** 默认城市作坊利润权重（w3 = 1，待 P9 标定）。 */
  public static final long DEFAULT_WORKSHOP_PROFIT_WEIGHT = 1L;

  /** 默认粮价/缺口权重（w4 = 1，待 P9 标定）。 */
  public static final long DEFAULT_GRAIN_PRESSURE_WEIGHT = 1L;

  /** 默认城区拥挤权重（w5 = 1，待 P9 标定）。 */
  public static final long DEFAULT_URBAN_CROWDING_WEIGHT = 1L;

  /** 默认进入阈值：{@code pull ≥ 1} 才迁。读数全为 0（没有市场/没有读数）⇒ pull = 0 ⇒ 不迁 —— 这是“默认 no-op”的一半。 */
  public static final long DEFAULT_MIGRATION_THRESHOLD = 1L;

  /** 默认迟滞余量：已在迁移的城市在 {@code pull ≥ 0} 时继续（P9 有跨轮状态后再用）。 */
  public static final long DEFAULT_HYSTERESIS_MARGIN = 1L;

  /** 默认每轮迁出比例（1‰；P9 标定，**不是**探针 golden 值的复现）。 */
  public static final long DEFAULT_MIGRATION_PER_MILLE = 1L;

  /** 默认源批次保留人数：0 = 允许抽空（探针“每户至少留 15 人”是家户口径，P9 再接）。 */
  public static final long DEFAULT_MINIMUM_SOURCE_LOT_COUNT = 0L;

  /** 千分比口径常量（与 {@link CityLand#PER_MILLE} 同值；此处不引用 P6 类型，保持模型层的值自洽）。 */
  public static final long PER_MILLE = 1000L;

  public MigrationPolicy {
    if (tradeVolumeWeight < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.tradeVolumeWeight 不得为负: " + tradeVolumeWeight);
    }
    if (merchantDensityWeight < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.merchantDensityWeight 不得为负: " + merchantDensityWeight);
    }
    if (workshopProfitWeight < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.workshopProfitWeight 不得为负: " + workshopProfitWeight);
    }
    if (grainPressureWeight < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.grainPressureWeight 不得为负: " + grainPressureWeight);
    }
    if (urbanCrowdingWeight < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.urbanCrowdingWeight 不得为负: " + urbanCrowdingWeight);
    }
    if (migrationThreshold < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.migrationThreshold 不得为负: " + migrationThreshold);
    }
    if (hysteresisMargin < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.hysteresisMargin 不得为负: " + hysteresisMargin);
    }
    if (migrationPerMille < 0L || migrationPerMille > PER_MILLE) {
      throw new IllegalArgumentException(
          "MigrationPolicy.migrationPerMille 必须 ∈ [0, 1000]: " + migrationPerMille);
    }
    if (minimumSourceLotCount < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.minimumSourceLotCount 不得为负: " + minimumSourceLotCount);
    }
  }

  /**
   * ★ P8 默认策略：五个权重全 1、阈值 1、迟滞余量 1、每轮迁出 1‰、源批次保留 0 人。
   *
   * <p>★ 全是<b>占位默认值</b>：P9 用 7HEX2 golden 读数标定；本轮只有显式构造/显式调用才会改变行为。
   */
  public static MigrationPolicy defaults() {
    return new MigrationPolicy(
        DEFAULT_TRADE_VOLUME_WEIGHT,
        DEFAULT_MERCHANT_DENSITY_WEIGHT,
        DEFAULT_WORKSHOP_PROFIT_WEIGHT,
        DEFAULT_GRAIN_PRESSURE_WEIGHT,
        DEFAULT_URBAN_CROWDING_WEIGHT,
        DEFAULT_MIGRATION_THRESHOLD,
        DEFAULT_HYSTERESIS_MARGIN,
        DEFAULT_MIGRATION_PER_MILLE,
        DEFAULT_MINIMUM_SOURCE_LOT_COUNT);
  }

  /**
   * ★★ <b>拉力公式（唯一拼写点）</b>：逐项用 {@link Math#multiplyExact}(w, 项) 与 {@link Math#addExact}/{@link
   * Math#subtractExact} 计算，<b>任何溢出当场抛 {@link ArithmeticException}</b>（拒绝静默回绕）。
   *
   * <p>★ 量纲由调用方保证（本类不猜单位）：{@code perCapitaTradeVolume} 是每人交易量（planner 用‰承载整数比）、 {@code
   * merchantDensity} 是商人密度（planner 用“每 1000 人的商人数”）、{@code workshopProfit} 是最小币值、 {@code
   * grainPriceOrGap} 与 {@code urbanCrowding} 由调用方在同一套量纲里给（拥挤用‰）。
   *
   * @param perCapitaTradeVolume 人均交易量项；不得为负
   * @param merchantDensity 商人密度项；不得为负
   * @param workshopProfit 城市作坊利润项；<b>可为负</b>（亏损压低拉力）
   * @param grainPriceOrGap 粮价/粮缺口压力项；不得为负
   * @param urbanCrowding 城区拥挤项；不得为负
   * @return 拉力（可正可负：负值表示净推力向外）
   */
  public long urbanPull(
      long perCapitaTradeVolume,
      long merchantDensity,
      long workshopProfit,
      long grainPriceOrGap,
      long urbanCrowding) {
    if (perCapitaTradeVolume < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.urbanPull 的人均交易量不得为负: " + perCapitaTradeVolume);
    }
    if (merchantDensity < 0L) {
      throw new IllegalArgumentException("MigrationPolicy.urbanPull 的商人密度不得为负: " + merchantDensity);
    }
    if (grainPriceOrGap < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.urbanPull 的粮价/缺口不得为负: " + grainPriceOrGap);
    }
    if (urbanCrowding < 0L) {
      throw new IllegalArgumentException("MigrationPolicy.urbanPull 的城区拥挤不得为负: " + urbanCrowding);
    }
    try {
      long pull = Math.multiplyExact(tradeVolumeWeight, perCapitaTradeVolume);
      pull = Math.addExact(pull, Math.multiplyExact(merchantDensityWeight, merchantDensity));
      pull = Math.addExact(pull, Math.multiplyExact(workshopProfitWeight, workshopProfit));
      pull = Math.subtractExact(pull, Math.multiplyExact(grainPressureWeight, grainPriceOrGap));
      pull = Math.subtractExact(pull, Math.multiplyExact(urbanCrowdingWeight, urbanCrowding));
      return pull;
    } catch (ArithmeticException overflow) {
      throw new ArithmeticException(
          "MigrationPolicy.urbanPull 溢出（fail-closed，拒绝静默回绕）："
              + "trade="
              + perCapitaTradeVolume
              + " merchants="
              + merchantDensity
              + " profit="
              + workshopProfit
              + " grain="
              + grainPriceOrGap
              + " crowding="
              + urbanCrowding);
    }
  }

  /**
   * ★ 无迟滞的单轮判断（规划器默认走它）：{@code pull ≥ migrationThreshold} 才迁。
   *
   * <p>它只读入参；同一份入参恒得同一个布尔值，不依赖时钟/随机/历史。
   */
  public boolean shouldMigrate(long pull) {
    return pull >= migrationThreshold;
  }

  /**
   * ★★ <b>带迟滞的判断（防抖：进入难、退出也难）</b>：未在迁移的城市用进入阈值；已在迁移的城市用 {@code max(0, migrationThreshold −
   * hysteresisMargin)}。
   *
   * <p>{@code currentlyMigrating} 是<b>调用方给的显式状态</b>（例如上一轮已触发迁移的城市集合），本类不存任何状态；
   * 因此同一份入参（含该布尔）恒得同一个结果。
   */
  public boolean shouldMigrate(long pull, boolean currentlyMigrating) {
    return pull >= thresholdFor(currentlyMigrating);
  }

  /** 迟滞判断用的退出阈值：{@code max(0, migrationThreshold − hysteresisMargin)}（防下溢）。 */
  public long exitThreshold() {
    return thresholdFor(true);
  }

  private long thresholdFor(boolean currentlyMigrating) {
    if (!currentlyMigrating) {
      return migrationThreshold;
    }
    return migrationThreshold >= hysteresisMargin ? migrationThreshold - hysteresisMargin : 0L;
  }

  /**
   * ★★ <b>本轮迁出配额（确定性向下取整）</b>：
   *
   * <pre>
   * quota = ⌊ ruralPopulation × migrationPerMille ÷ 1000 ⌋
   * </pre>
   *
   * <p>乘法走 {@link Math#multiplyExact}（溢出 fail-closed），除法是整数 floor。{@code quota ≤ ruralPopulation} 由
   * {@code migrationPerMille ≤ 1000} 保证；调用方仍须按逐批次可用人数再夹一次（见规划器）。
   *
   * @param ruralPopulation 农村人口池（人）；不得为负
   */
  public long migrationQuota(long ruralPopulation) {
    if (ruralPopulation < 0L) {
      throw new IllegalArgumentException(
          "MigrationPolicy.migrationQuota 的农村人口不得为负: " + ruralPopulation);
    }
    if (ruralPopulation == 0L || migrationPerMille == 0L) {
      return 0L;
    }
    return Math.multiplyExact(ruralPopulation, migrationPerMille) / PER_MILLE;
  }
}
