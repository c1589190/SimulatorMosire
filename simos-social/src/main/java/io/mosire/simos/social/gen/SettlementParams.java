package io.mosire.simos.social.gen;

import io.mosire.simos.map.terrain.TerrainCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成器的**全部可调参数**，逐键对应冻结输入 {@code config/worldgen/v17levant-nations.json} 的 {@code defaults} 与
 * {@code cityNameStyle}。{@link #defaults()} 把那份 JSON 的数值**照抄成字面量** —— social 模块不能读 {@code
 * config/}（不在 classpath、也不该跨模块 摸文件系统），P3 的装配层读 JSON 后构造本类型覆盖即可。
 *
 * <p>★ 有一处**配置里没有、由本实现定的启发式**（已在字段注释标 {@code _derived}）：河叉阈值 {@code
 * transportBonuses.riverJunctionEdgesAtHex = 3}、山口阈值 {@code mountainPassMoveCost = 3} / {@code
 * mountainPassNeighborMoveCost = 2}。配置只给了"可通航 = 同格 river 边 ≥ 2"。
 *
 * <p>★ **{@code tierRadiiHex} 没有进来**：冻结输入里有它，但用户给的 10 步算法里**市场半径一次都没用到**（腹地是影响力竞争算出来的，不是按半径
 * 圈地）。把它收进来只会是一个没人读的字段 —— 见交付报告"我没做/没验证的"。
 *
 * @param surplusRatePerTerrain 地形 → 农业剩余率（键集必须覆盖 {@link TerrainCatalog#KEYS}；{@code ocean} 为 0 ⇒
 *     海洋不分配农村人口）
 * @param river 河流乘数
 * @param coastalMultiplier 沿海乘数
 * @param noiseAmplitude 逐格噪声幅度（坐标哈希给出，落在 ±本值）
 * @param candidateWeights 建城候选分三项权重
 * @param transportBonuses 交通优势启发式（叠乘）
 * @param minMarketTownDistanceHex 两个市场镇之间的最小 hex 距离（低分让位给高分，贪心）
 * @param influenceExponent 影响力指数 k：{@code Influence = W / cost^k}
 * @param rarityPenalty 稀有惩罚："十万人级城市非常罕见"
 * @param zipfShape Zipf 形状（城市总数上限 + 各等级上限，按国家人口等比缩放）
 * @param tierUpgradeRates 三级抽样升级率（再乘 {@code commercialIntegration × politicalCentralization} 的调制）
 * @param cityNameStyle 城市名词干/后缀表（哈希取词用）
 */
public record SettlementParams(
    Map<String, Double> surplusRatePerTerrain,
    RiverMultipliers river,
    double coastalMultiplier,
    double noiseAmplitude,
    CandidateWeights candidateWeights,
    TransportBonuses transportBonuses,
    int minMarketTownDistanceHex,
    double influenceExponent,
    RarityPenalty rarityPenalty,
    ZipfShape zipfShape,
    TierUpgradeRates tierUpgradeRates,
    NameStyle cityNameStyle) {

  /**
   * 河流乘数：0 条河边 ⇒ 1.0；1 条 ⇒ {@code ordinary}；≥ {@code navigableEdgesAtHex} 条 ⇒ {@code navigable}。
   */
  public record RiverMultipliers(double ordinary, double navigable, int navigableEdgesAtHex) {
    public RiverMultipliers {
      if (!(ordinary >= 1.0) || !Double.isFinite(ordinary) || !(navigable >= ordinary)) {
        throw new IllegalArgumentException(
            "河流乘数必须满足 1 <= ordinary <= navigable: " + ordinary + "/" + navigable);
      }
      if (navigableEdgesAtHex < 2) {
        throw new IllegalArgumentException("navigableEdgesAtHex 必须 >= 2: " + navigableEdgesAtHex);
      }
    }
  }

  /** 建城候选分三项权重；**三项各自先归一到 [0,1]**，否则量纲不同会互相淹没。 */
  public record CandidateWeights(double surplus, double transport, double historical) {
    public CandidateWeights {
      requireNonNegative(surplus, "surplus");
      requireNonNegative(transport, "transport");
      requireNonNegative(historical, "historical");
      if (!(surplus + transport + historical > 0)) {
        throw new IllegalArgumentException("候选权重之和必须 > 0");
      }
    }
  }

  /** 交通优势启发式（各类**叠乘**）。 */
  public record TransportBonuses(
      double riverJunction,
      double navigableRiver,
      double river,
      double coastal,
      double mountainPass,
      double plain,
      double deepMountain,
      double barren,
      int riverJunctionEdgesAtHex,
      int mountainPassMoveCost,
      int mountainPassNeighborMoveCost) {
    public TransportBonuses {
      requirePositive(riverJunction, "riverJunction");
      requirePositive(navigableRiver, "navigableRiver");
      requirePositive(river, "river");
      requirePositive(coastal, "coastal");
      requirePositive(mountainPass, "mountainPass");
      requirePositive(plain, "plain");
      requirePositive(deepMountain, "deepMountain");
      requirePositive(barren, "barren");
      if (riverJunctionEdgesAtHex < 2) {
        throw new IllegalArgumentException(
            "riverJunctionEdgesAtHex 必须 >= 2: " + riverJunctionEdgesAtHex);
      }
      if (mountainPassMoveCost < 1 || mountainPassNeighborMoveCost < 1) {
        throw new IllegalArgumentException("山口阈值必须是正整数");
      }
    }
  }

  /** 稀有惩罚：初算人口 &gt; {@code threshold} 的城，权重 × {@code factor}，再重算一次（只跑一轮）。 */
  public record RarityPenalty(long threshold, double factor) {
    public RarityPenalty {
      if (threshold < 0) {
        throw new IllegalArgumentException("threshold 不得为负: " + threshold);
      }
      if (!(factor >= 0) || factor > 1.0 || !Double.isFinite(factor)) {
        throw new IllegalArgumentException("factor 必须落在 [0,1]: " + factor);
      }
    }
  }

  /**
   * Zipf 形状。{@code totalCityCount} = 各档 count 之和（{@code 1+2+5+15+40+100 =
   * 163}），作为"按国家人口等比缩放后的城市总数上界"； {@code majorCityCap/cityCap/townCap} 是**累计档位**（1 / 1+2=3 /
   * 1+2+5=8），用作各等级数量上限 ⇒ "海量小城、极少大城"。
   */
  public record ZipfShape(
      long samplePopulation, long totalCityCount, long majorCityCap, long cityCap, long townCap) {
    public ZipfShape {
      if (samplePopulation <= 0) {
        throw new IllegalArgumentException("samplePopulation 必须 > 0: " + samplePopulation);
      }
      if (totalCityCount <= 0) {
        throw new IllegalArgumentException("totalCityCount 必须 > 0: " + totalCityCount);
      }
      if (!(1 <= majorCityCap && majorCityCap <= cityCap && cityCap <= townCap)) {
        throw new IllegalArgumentException(
            "等级上限必须满足 1 <= majorCityCap <= cityCap <= townCap: "
                + majorCityCap
                + "/"
                + cityCap
                + "/"
                + townCap);
      }
    }
  }

  /** 三级抽样升级率（基准值；实际再乘中央度 × 商业整合度的调制）。 */
  public record TierUpgradeRates(
      double marketTownToTown, double townToCity, double cityToMajorCity) {
    public TierUpgradeRates {
      requireRate(marketTownToTown, "marketTownToTown");
      requireRate(townToCity, "townToCity");
      requireRate(cityToMajorCity, "cityToMajorCity");
    }
  }

  /** 城市名词表（哈希取词：词干 + 后缀）。 */
  public record NameStyle(List<String> stems, List<String> suffixes) {
    public NameStyle {
      if (stems == null || stems.isEmpty()) {
        throw new IllegalArgumentException("stems 不得为空");
      }
      if (suffixes == null || suffixes.isEmpty()) {
        throw new IllegalArgumentException("suffixes 不得为空");
      }
      stems = Collections.unmodifiableList(List.copyOf(stems));
      suffixes = Collections.unmodifiableList(List.copyOf(suffixes));
    }
  }

  public SettlementParams {
    if (surplusRatePerTerrain == null) {
      throw new IllegalArgumentException("surplusRatePerTerrain 不得为 null");
    }
    if (river == null || candidateWeights == null || transportBonuses == null) {
      throw new IllegalArgumentException("river/candidateWeights/transportBonuses 不得为 null");
    }
    if (rarityPenalty == null || zipfShape == null || tierUpgradeRates == null) {
      throw new IllegalArgumentException("rarityPenalty/zipfShape/tierUpgradeRates 不得为 null");
    }
    if (cityNameStyle == null) {
      throw new IllegalArgumentException("cityNameStyle 不得为 null");
    }
    if (!Double.isFinite(coastalMultiplier) || coastalMultiplier < 1.0) {
      throw new IllegalArgumentException("coastalMultiplier 必须 >= 1: " + coastalMultiplier);
    }
    if (!Double.isFinite(noiseAmplitude) || noiseAmplitude < 0 || noiseAmplitude >= 1.0) {
      throw new IllegalArgumentException("noiseAmplitude 必须落在 [0,1): " + noiseAmplitude);
    }
    if (minMarketTownDistanceHex < 1) {
      throw new IllegalArgumentException(
          "minMarketTownDistanceHex 必须 >= 1: " + minMarketTownDistanceHex);
    }
    if (!Double.isFinite(influenceExponent) || influenceExponent <= 0) {
      throw new IllegalArgumentException("influenceExponent 必须 > 0: " + influenceExponent);
    }
    Map<String, Double> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Double> entry : surplusRatePerTerrain.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("surplusRatePerTerrain 的键与值都不得为 null");
      }
      if (!Double.isFinite(entry.getValue()) || entry.getValue() < 0) {
        throw new IllegalArgumentException("剩余率必须是有限非负数: " + entry);
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    for (String key : TerrainCatalog.KEYS) {
      if (!copy.containsKey(key)) {
        throw new IllegalArgumentException("surplusRatePerTerrain 缺少地形 key: " + key);
      }
    }
    surplusRatePerTerrain = Collections.unmodifiableMap(copy);
  }

  /**
   * 冻结输入的默认值（逐字照抄 {@code config/worldgen/v17levant-nations.json} 的 {@code defaults} 与 {@code
   * cityNameStyle.germanic}）。
   */
  public static SettlementParams defaults() {
    Map<String, Double> rates = new LinkedHashMap<>();
    rates.put("ocean", 0.0);
    rates.put("plains", 1.0);
    rates.put("desert", 0.02);
    rates.put("low_hills", 0.6);
    rates.put("mountains", 0.1);
    rates.put("plateau", 0.3);
    rates.put("plateau_mountains", 0.05);
    return new SettlementParams(
        rates,
        new RiverMultipliers(1.15, 1.5, 2),
        1.2,
        0.15,
        new CandidateWeights(0.45, 0.35, 0.20),
        new TransportBonuses(1.5, 1.35, 1.15, 1.4, 1.3, 1.0, 0.5, 0.35, 3, 3, 2),
        2,
        2.0,
        new RarityPenalty(100_000L, 0.1),
        new ZipfShape(5_000_000L, 163L, 1L, 3L, 8L),
        new TierUpgradeRates(0.22, 0.12, 0.15),
        new NameStyle(
            List.of(
                "Ald", "Bern", "Dank", "Eber", "Falk", "Gern", "Hag", "Ilm", "Kron", "Laut", "Mark",
                "Nord", "Oster", "Raben", "Salz", "Stein", "Tann", "Ulrich", "Wald", "Zell",
                "Bruck", "Dorn", "Eisen", "Furt", "Grimm", "Hoch", "Jagd", "Kalt", "Linden",
                "Moor"),
            List.of(
                "burg", "berg", "stadt", "dorf", "furt", "brück", "hafen", "wald", "heim", "feld",
                "stein", "au", "hofen", "reuth", "münde")));
  }

  /** 换稀有惩罚（用例 6 用它把阈值调小，观察分布被压下去）。 */
  public SettlementParams withRarityPenalty(RarityPenalty value) {
    return new SettlementParams(
        surplusRatePerTerrain,
        river,
        coastalMultiplier,
        noiseAmplitude,
        candidateWeights,
        transportBonuses,
        minMarketTownDistanceHex,
        influenceExponent,
        value,
        zipfShape,
        tierUpgradeRates,
        cityNameStyle);
  }

  private static void requireNonNegative(double value, String field) {
    if (!Double.isFinite(value) || value < 0) {
      throw new IllegalArgumentException(field + " 必须是有限非负数: " + value);
    }
  }

  private static void requirePositive(double value, String field) {
    if (!Double.isFinite(value) || value <= 0) {
      throw new IllegalArgumentException(field + " 必须是有限正数: " + value);
    }
  }

  private static void requireRate(double value, String field) {
    if (!Double.isFinite(value) || value < 0 || value > 1.0) {
      throw new IllegalArgumentException(field + " 必须落在 [0,1]: " + value);
    }
  }
}
