package io.mosire.simos.social.gen;

import io.mosire.simos.map.hex.HexCoord;

/**
 * 生成器产出的一座城。**身份是 {@link #id()}**（稳定可复现的 {@code c-<q>_<r>}），落点、名字、等级、人口、审计量都在这里 —— P3 接线时 它们进
 * {@code SocialCity}（{@code id} 复用同一个身份，见铁律 1）。
 *
 * <p>★ **{@code population} 是城市人口（城镇部分）**，与 {@link SettlementPlan#ruralPopulation()} 的农村人口分开记（与
 * {@code SocialData}/{@code SocialCity} 的分工一致）。
 *
 * @param id 稳定身份，形如 {@code c--56_-65}；**空白即抛**
 * @param name 显示名（首都/文档地名为原名，其余由坐标哈希取词生成）；**空白即抛**
 * @param at 落点
 * @param tier 等级，取 {@link #TIER_MARKET_TOWN} / {@link #TIER_TOWN} / {@link #TIER_CITY} / {@link
 *     #TIER_MAJOR_CITY} 之一
 * @param population 城市人口；&ge; 0
 * @param catchmentHexes 归属该城的腹地格数（含本格）
 * @param localSurplus 腹地农业剩余合计（{@link SurplusEstimate#surplusPotential()} 之和）
 * @param tradeMultiplier 贸易乘数（由交通优势 × 商业整合度给出）
 * @param politicalMultiplier 政治乘数（由中央度给出，**首都吃大头**）
 * @param justification 可读依据（含河流/沿海/稀有惩罚/首都硬目标等，供审计与回归对拍）；**空白即抛**
 */
public record PlannedCity(
    String id,
    String name,
    HexCoord at,
    String tier,
    long population,
    int catchmentHexes,
    double localSurplus,
    double tradeMultiplier,
    double politicalMultiplier,
    String justification) {

  /** 最低等级：市场镇。 */
  public static final String TIER_MARKET_TOWN = "MarketTown";

  /** 第二级：镇。 */
  public static final String TIER_TOWN = "Town";

  /** 第三级：城。 */
  public static final String TIER_CITY = "City";

  /** 最高等级：大城（Zipf 首档，最多一座 —— 通常是首都）。 */
  public static final String TIER_MAJOR_CITY = "MajorCity";

  public PlannedCity {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id 不得为空白");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null");
    }
    if (!isKnownTier(tier)) {
      throw new IllegalArgumentException(
          "未知等级: "
              + tier
              + "（只认 "
              + TIER_MARKET_TOWN
              + "/"
              + TIER_TOWN
              + "/"
              + TIER_CITY
              + "/"
              + TIER_MAJOR_CITY
              + "）");
    }
    if (population < 0) {
      throw new IllegalArgumentException("population 不得为负: " + population);
    }
    if (catchmentHexes < 0) {
      throw new IllegalArgumentException("catchmentHexes 不得为负: " + catchmentHexes);
    }
    if (!Double.isFinite(localSurplus) || localSurplus < 0) {
      throw new IllegalArgumentException("localSurplus 必须是有限非负数: " + localSurplus);
    }
    if (!Double.isFinite(tradeMultiplier) || tradeMultiplier < 0) {
      throw new IllegalArgumentException("tradeMultiplier 必须是有限非负数: " + tradeMultiplier);
    }
    if (!Double.isFinite(politicalMultiplier) || politicalMultiplier < 0) {
      throw new IllegalArgumentException("politicalMultiplier 必须是有限非负数: " + politicalMultiplier);
    }
    if (justification == null || justification.isBlank()) {
      throw new IllegalArgumentException("justification 不得为空白");
    }
  }

  /** 等级序（MarketTown &lt; Town &lt; City &lt; MajorCity）；未知等级抛。 */
  public static int tierRank(String tier) {
    if (TIER_MARKET_TOWN.equals(tier)) {
      return 1;
    }
    if (TIER_TOWN.equals(tier)) {
      return 2;
    }
    if (TIER_CITY.equals(tier)) {
      return 3;
    }
    if (TIER_MAJOR_CITY.equals(tier)) {
      return 4;
    }
    throw new IllegalArgumentException("未知等级: " + tier);
  }

  /** 等级表里有没有这一项。**静态方法**（见 {@link PlannedCity} 类注释：本仓不许 {@code isXxx()} 实例方法）。 */
  public static boolean isKnownTier(String tier) {
    return TIER_MARKET_TOWN.equals(tier)
        || TIER_TOWN.equals(tier)
        || TIER_CITY.equals(tier)
        || TIER_MAJOR_CITY.equals(tier);
  }

  /** 改人口（其余不动）。 */
  public PlannedCity withPopulation(long value) {
    return new PlannedCity(
        id,
        name,
        at,
        tier,
        value,
        catchmentHexes,
        localSurplus,
        tradeMultiplier,
        politicalMultiplier,
        justification);
  }
}
