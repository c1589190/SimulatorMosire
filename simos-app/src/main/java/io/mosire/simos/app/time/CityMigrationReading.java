package io.mosire.simos.app.time;

import io.mosire.simos.economy.model.CityLand;
import io.mosire.simos.map.CityId;
import java.util.Objects;

/**
 * ★★ <b>一座城的迁入读数（P8 规划器的纯输入）</b>：把“拉力公式需要什么”与“这些数从哪来”分开。
 *
 * <pre>
 * perCapitaTradeVolumePerMille = ⌊ tradeVolume × 1000 ÷ urbanPopulation ⌋
 * merchantDensityPerMille      = ⌊ merchantCount × 1000 ÷ urbanPopulation ⌋
 * urbanCrowdingPerMille        = capacity &gt; 0 ? ⌊ usedCapacity × 1000 ÷ capacity ⌋ : 0
 * </pre>
 *
 * <p>★★ <b>为什么用‰承载“人均/密度”</b>：整数输出（量纲纪律）、且小比例不会被整除归零。P9 标定权重时按同一套‰口径 反推量级；本类不猜权重。
 *
 * <p>★★ <b>它不读 {@code SocialData}/磁盘/时钟</b>：读数由组合根从市场报告、P6 {@link CityLand} 与城市人口现算后传入。
 * 规划器只消费本记录，因此“读数的来源”将来换了（市场报告 / GM 注入 / 读口缓存），规划算法一字不改。
 *
 * @param cityId 城市稳定身份；不得为 null
 * @param urbanPopulation 该城当前城镇人口（人）；不得为负（0 = 城还没人，人均项按 0）
 * @param tradeVolume 本轮交易量；不得为负
 * @param merchantCount 该城商人/运力当量；不得为负（planner 用它算密度）
 * @param workshopProfit 城市作坊利润（最小币值）；<b>可为负</b>（亏损压低拉力）
 * @param grainPriceOrGap 粮价/粮缺口压力读数（同一量纲内由调用方选定“价”或“缺口”，不并算两份）；不得为负
 * @param urbanCrowdingPerMille 城区拥挤（‰）；不得为负
 */
public record CityMigrationReading(
    CityId cityId,
    long urbanPopulation,
    long tradeVolume,
    long merchantCount,
    long workshopProfit,
    long grainPriceOrGap,
    long urbanCrowdingPerMille) {

  public CityMigrationReading {
    Objects.requireNonNull(cityId, "CityMigrationReading.cityId 不得为 null");
    if (urbanPopulation < 0L) {
      throw new IllegalArgumentException(
          "CityMigrationReading.urbanPopulation 不得为负: " + urbanPopulation);
    }
    if (tradeVolume < 0L) {
      throw new IllegalArgumentException("CityMigrationReading.tradeVolume 不得为负: " + tradeVolume);
    }
    if (merchantCount < 0L) {
      throw new IllegalArgumentException(
          "CityMigrationReading.merchantCount 不得为负: " + merchantCount);
    }
    if (grainPriceOrGap < 0L) {
      throw new IllegalArgumentException(
          "CityMigrationReading.grainPriceOrGap 不得为负: " + grainPriceOrGap);
    }
    if (urbanCrowdingPerMille < 0L) {
      throw new IllegalArgumentException(
          "CityMigrationReading.urbanCrowdingPerMille 不得为负: " + urbanCrowdingPerMille);
    }
  }

  /**
   * ★★ 从 P6 的城市承载读数构造：拥挤项 = {@code capacity &gt; 0 ? ⌊usedCapacity × 1000 ÷ capacity⌋ : 0}。
   *
   * <p>★ {@code capacity == 0} 读作“该城土地尚未初始化” ⇒ 拥挤 <b>0</b>（不是因为不挤，是因为还没有读数）；与 {@link
   * CityLand#advance()} 对零承载不凭空扩建同口径。
   */
  public static CityMigrationReading of(
      CityId cityId,
      long urbanPopulation,
      long tradeVolume,
      long merchantCount,
      long workshopProfit,
      long grainPriceOrGap,
      CityLand cityLand) {
    return new CityMigrationReading(
        cityId,
        urbanPopulation,
        tradeVolume,
        merchantCount,
        workshopProfit,
        grainPriceOrGap,
        crowdingPerMille(cityLand));
  }

  /** 城区拥挤（‰）：0 承载 ⇒ 0（见 {@link #of}）；乘法走 {@link Math#multiplyExact}（溢出 fail-closed）。 */
  public static long crowdingPerMille(CityLand cityLand) {
    Objects.requireNonNull(cityLand, "CityMigrationReading.crowdingPerMille 的 cityLand 不得为 null");
    if (cityLand.capacity() <= 0L) {
      return 0L;
    }
    return Math.multiplyExact(cityLand.usedCapacity(), CityLand.PER_MILLE) / cityLand.capacity();
  }

  /** 人均交易量（‰）：{@code urbanPopulation == 0} ⇒ 0（没有城民就没有“人均”，不是无穷）。 */
  public long perCapitaTradeVolumePerMille() {
    if (urbanPopulation == 0L) {
      return 0L;
    }
    return Math.multiplyExact(tradeVolume, CityLand.PER_MILLE) / urbanPopulation;
  }

  /** 商人密度（每 1000 人的商人数）：{@code urbanPopulation == 0} ⇒ 0。 */
  public long merchantDensityPerMille() {
    if (urbanPopulation == 0L) {
      return 0L;
    }
    return Math.multiplyExact(merchantCount, CityLand.PER_MILLE) / urbanPopulation;
  }
}
