package io.mosire.simos.economy.model;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>商人政策（P6）：层级、服务半径、运力与农村累积成本</b>——把探针 {@code ProbeEconomy.TransportTeam} + {@code
 * MerchantTier} 的口径翻成一个不可变值类型，供 P9/P7 在组合根装配后注入运输费率。
 *
 * <p>★★ <b>本批只落形状与纯函数，不接状态</b>（计划 P6 的边界）：不新增 {@code EconomyData} 组件、不改 {@code MarketSettlement}
 * 的承运人选择；本类也<b>不是</b>买低卖高的交易者，仍是“承运人”政策。默认注入是全 0（见 {@code
 * MarketTopology.NO_CITY_DISCOUNT_PER_MILLE}/{@code NO_RURAL_PENALTY_PER_MILLE}），因此旧行为逐值不变。
 *
 * <p>★★ <b>三档商人（逐值照探针 {@code MerchantTier}）</b>：
 *
 * <pre>
 * tier           districtUse（城区当量）   transportDiscountPerMille（城市折扣上限）
 * PORTER                  1                            10
 * SELF_EMPLOYED           2                            25
 * BOSS                    4                            50
 * </pre>
 *
 * <p>★★ <b>城市折扣按离 home 距离衰减（逐值照探针 {@code transportPerMille}）</b>：
 *
 * <pre>
 * d = max(dist(home, from), dist(home, to))          // 两端里离 home 更远的那端
 * 服务半径外（d &gt; serviceRadiusHex 或 serviceRadiusHex ≤ 0）⇒ 0
 * decay = (serviceRadiusHex − d + 1) × 1000 / (serviceRadiusHex + 1)     // 整数向下取整
 * cityDiscountPerMille(d) = tier.transportDiscountPerMille × decay / 1000 // 再向下取整
 * </pre>
 *
 * <p>★★ <b>农村惩罚（逐值照探针 {@code updateCitiesAndRuralMerchants}）</b>：{@code homeIsCity == false} 且本队本轮
 * {@code lastUnitsMoved > 0} ⇒ 每轮 {@code +}{@link #RURAL_PENALTY_STEP_PER_ROUND}‰，封顶 {@link
 * #RURAL_PENALTY_CAP_PER_MILLE}‰。
 *
 * <p>★★ <b>城市运力自增长（逐值照探针）</b>：{@code withProfit(profit)} 对城市商人按 {@code profit > 0 && capacity <
 * 100000 ⇒ +5}、{@code profit < 0 && capacity > 5 ⇒ −5}。{@code profit} 由调用方按探针口径算：{@code 本轮运费收入 −
 * tier.districtUse × }{@link #UPKEEP_PER_DISTRICT_USE} （{@link #upkeepPerRound()} 是后一项的唯一拼写点）。
 *
 * <p>★ <b>显式 lane 与通配</b>：{@code laneFromHex}/{@code laneToHex} 都为 {@code null} = 通配服务半径 （探针的
 * {@code "*"}）；都非 null = 只服务该显式 lane。{@code homeHex} 对显式 lane 通常等于 {@code
 * laneFromHex}（探针农村脚夫的构造），对通配城市池 = 城市格。
 *
 * <p>★ <b>单位</b>：{@code capacityPerRound}/{@code remainingCapacity}/{@code lastUnitsMoved}
 * 是“运量”同一单位 （探针口径为商品单位；正式运行时接线时按 {@code EconomyVocabulary} 换算）；折扣/惩罚是货款价值的千分比。
 *
 * @param tier 商人层级；不得为 null
 * @param homeHex 商人所在/服务原点的 hex；不得为 null
 * @param homeIsCity 是否城市商人（只有城市商人吃折扣并自增长运力；农村商人累积惩罚）
 * @param capacityPerRound 每轮运力（容量）；不得为负
 * @param remainingCapacity 本轮剩余运力；不得为负（轮初由 {@link #withRoundCapacityReset()} 归位）
 * @param laneFromHex 显式 lane 起点；{@code null} = 通配（通配时 {@code laneToHex} 也必须是 null）
 * @param laneToHex 显式 lane 终点；{@code null} = 通配（通配时 {@code laneFromHex} 也必须是 null）
 * @param serviceRadiusHex 服务半径（hex）；不得为负；{@code ≤ 0} 表示不按半径服务（只有显式 lane 能命中）
 * @param ruralTradeCostPenaltyPerMille 农村商人累积成本（‰）；必须 ∈ [0, {@link #RURAL_PENALTY_CAP_PER_MILLE}]
 * @param lastUnitsMoved 本队本轮实际承运量；不得为负（农村惩罚只在 {@code > 0} 时累积）
 */
public record MerchantPolicy(
    MerchantTier tier,
    HexCoord homeHex,
    boolean homeIsCity,
    long capacityPerRound,
    long remainingCapacity,
    HexCoord laneFromHex,
    HexCoord laneToHex,
    long serviceRadiusHex,
    long ruralTradeCostPenaltyPerMille,
    long lastUnitsMoved) {

  /** 千分比口径常量（探针 {@code PER_MILLE}）。 */
  public static final long PER_MILLE = 1000L;

  /** 城市商人盈利/亏损时每轮的运力增减（探针常量 5）。 */
  public static final long CAPACITY_STEP_PER_ROUND = 5L;

  /** 城市商人运力增长上限（探针常量 100_000）：达到后盈利不再增容。 */
  public static final long CITY_CAPACITY_CEILING = 100_000L;

  /** 城市商人缩编下限（探针条件 {@code capacityPerRound > 5}）：到 5 后亏损不再缩编。 */
  public static final long CITY_CAPACITY_SHRINK_FLOOR = 5L;

  /** 农村商人每做一轮贸易的累积成本增量（‰；探针常量 2）。 */
  public static final long RURAL_PENALTY_STEP_PER_ROUND = 2L;

  /** 农村商人累积成本上限（‰；探针常量 100）。 */
  public static final long RURAL_PENALTY_CAP_PER_MILLE = 100L;

  /** 商人每点城区当量每轮的固定开销（探针常量 100；用于算 {@code profit}）。 */
  public static final long UPKEEP_PER_DISTRICT_USE = 100L;

  public MerchantPolicy {
    Objects.requireNonNull(tier, "MerchantPolicy.tier 不得为 null");
    Objects.requireNonNull(homeHex, "MerchantPolicy.homeHex 不得为 null");
    if (capacityPerRound < 0L) {
      throw new IllegalArgumentException(
          "MerchantPolicy.capacityPerRound 不得为负: " + capacityPerRound);
    }
    if (remainingCapacity < 0L) {
      throw new IllegalArgumentException(
          "MerchantPolicy.remainingCapacity 不得为负: " + remainingCapacity);
    }
    if ((laneFromHex == null) != (laneToHex == null)) {
      throw new IllegalArgumentException(
          "MerchantPolicy 的显式 lane 两端必须同时给或同时缺省（null = 通配）: from="
              + laneFromHex
              + " to="
              + laneToHex);
    }
    if (serviceRadiusHex < 0L) {
      throw new IllegalArgumentException(
          "MerchantPolicy.serviceRadiusHex 不得为负: " + serviceRadiusHex);
    }
    if (ruralTradeCostPenaltyPerMille < 0L
        || ruralTradeCostPenaltyPerMille > RURAL_PENALTY_CAP_PER_MILLE) {
      throw new IllegalArgumentException(
          "MerchantPolicy.ruralTradeCostPenaltyPerMille 必须 ∈ [0, "
              + RURAL_PENALTY_CAP_PER_MILLE
              + "]: "
              + ruralTradeCostPenaltyPerMille);
    }
    if (lastUnitsMoved < 0L) {
      throw new IllegalArgumentException("MerchantPolicy.lastUnitsMoved 不得为负: " + lastUnitsMoved);
    }
  }

  /**
   * ★ 通配服务半径的便捷构造（探针城市商人总池的形状）：显式 lane 为空，{@code remainingCapacity = capacityPerRound}，惩罚/本轮承运量为 0。
   */
  public MerchantPolicy(
      MerchantTier tier,
      HexCoord homeHex,
      boolean homeIsCity,
      long capacityPerRound,
      long serviceRadiusHex) {
    this(
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        capacityPerRound,
        null,
        null,
        serviceRadiusHex,
        0L,
        0L);
  }

  /** 本队每轮的固定开销 = {@code tier.districtUse() × }{@link #UPKEEP_PER_DISTRICT_USE}（探针口径）。 */
  public long upkeepPerRound() {
    return Math.multiplyExact((long) tier.districtUse(), UPKEEP_PER_DISTRICT_USE);
  }

  /**
   * ★★ <b>城市折扣随离 home 距离衰减（‰）</b>：半径外/非城市/{@code serviceRadiusHex ≤ 0} ⇒ 0；半径内逐值照探针： {@code decay =
   * (radius − d + 1) × 1000 / (radius + 1)}，再乘 tier 折扣并向下取整。
   *
   * @param distanceToHomeHex 目标 lane 两端里离 home 更远的那端距离（0 表示就在 home）；不得为负
   */
  public long cityDiscountPerMille(long distanceToHomeHex) {
    if (distanceToHomeHex < 0L) {
      throw new IllegalArgumentException(
          "MerchantPolicy.cityDiscountPerMille 距离不得为负: " + distanceToHomeHex);
    }
    if (!homeIsCity || serviceRadiusHex <= 0L || distanceToHomeHex > serviceRadiusHex) {
      return 0L;
    }
    long decay =
        Math.multiplyExact(serviceRadiusHex - distanceToHomeHex + 1L, PER_MILLE)
            / (serviceRadiusHex + 1L);
    return Math.multiplyExact(tier.transportDiscountPerMille(), decay) / PER_MILLE;
  }

  /**
   * ★★ <b>本队能不能服务一条 lane</b>（逐值照探针 {@code servesLane}）：显式 lane 精确匹配优先（即使半径不覆盖）； 否则通配 lane 要求两端都在
   * home 的 {@code serviceRadiusHex} 半径内（{@code serviceRadiusHex ≤ 0} ⇒ 不按半径服务）。
   */
  public boolean servesLane(HexCoord from, HexCoord to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (laneFromHex != null && laneFromHex.equals(from) && laneToHex.equals(to)) {
      return true;
    }
    if (serviceRadiusHex <= 0L) {
      return false;
    }
    return homeHex.distanceTo(from) <= serviceRadiusHex
        && homeHex.distanceTo(to) <= serviceRadiusHex;
  }

  /** ★ 探针 {@code transportPerMille} 的城市折扣分支：本队服务这条 lane 且是城市商人 ⇒ 折扣（用两端更远距离）。 */
  public long cityDiscountForLane(HexCoord from, HexCoord to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (!homeIsCity || !servesLane(from, to)) {
      return 0L;
    }
    return cityDiscountPerMille(Math.max(homeHex.distanceTo(from), homeHex.distanceTo(to)));
  }

  /** ★ 探针 {@code transportPerMille} 的农村惩罚分支：本队服务这条 lane 且是农村商人 ⇒ 当前累积惩罚。 */
  public long ruralPenaltyForLane(HexCoord from, HexCoord to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (homeIsCity || !servesLane(from, to)) {
      return 0L;
    }
    return ruralTradeCostPenaltyPerMille;
  }

  /**
   * ★★ <b>一轮结束后的政策推进（逐值照探针 {@code updateCitiesAndRuralMerchants}）</b>：
   *
   * <ul>
   *   <li>城市商人：{@code profit > 0 && capacity < 100000 ⇒ capacity += 5}； {@code profit < 0 &&
   *       capacity > 5 ⇒ capacity −= 5}；
   *   <li>农村商人：{@code lastUnitsMoved > 0 ⇒ penalty = min(100, penalty + 2)}；
   *   <li>无变化 ⇒ 原样返回（值类型不制造 equal-but-new 对象）。
   * </ul>
   *
   * <p>★ 探针另在每轮开始重置 {@code remainingCapacity/lastUnitsMoved}，由 {@link #withRoundCapacityReset()}
   * 承担。
   *
   * @param profit 本轮净利 = 运费收入 − {@link #upkeepPerRound()}（探针 {@code lastTradeProfit}）
   */
  public MerchantPolicy withProfit(long profit) {
    if (homeIsCity) {
      long nextCapacity = capacityPerRound;
      if (profit > 0L && nextCapacity < CITY_CAPACITY_CEILING) {
        nextCapacity = Math.addExact(nextCapacity, CAPACITY_STEP_PER_ROUND);
      } else if (profit < 0L && nextCapacity > CITY_CAPACITY_SHRINK_FLOOR) {
        nextCapacity = Math.subtractExact(nextCapacity, CAPACITY_STEP_PER_ROUND);
      }
      if (nextCapacity == capacityPerRound) {
        return this;
      }
      return new MerchantPolicy(
          tier,
          homeHex,
          true,
          nextCapacity,
          remainingCapacity,
          laneFromHex,
          laneToHex,
          serviceRadiusHex,
          ruralTradeCostPenaltyPerMille,
          lastUnitsMoved);
    }
    if (lastUnitsMoved <= 0L) {
      return this;
    }
    long nextPenalty =
        Math.min(
            RURAL_PENALTY_CAP_PER_MILLE,
            Math.addExact(ruralTradeCostPenaltyPerMille, RURAL_PENALTY_STEP_PER_ROUND));
    if (nextPenalty == ruralTradeCostPenaltyPerMille) {
      return this;
    }
    return new MerchantPolicy(
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        remainingCapacity,
        laneFromHex,
        laneToHex,
        serviceRadiusHex,
        nextPenalty,
        lastUnitsMoved);
  }

  /**
   * ★ 轮初归位（探针每轮开始把 {@code remainingCapacity = capacityPerRound}、{@code lastUnitsMoved = 0}）：
   * 容量按上一轮结束后的 {@code capacityPerRound} 重置。
   */
  public MerchantPolicy withRoundCapacityReset() {
    if (remainingCapacity == capacityPerRound && lastUnitsMoved == 0L) {
      return this;
    }
    return new MerchantPolicy(
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        capacityPerRound,
        laneFromHex,
        laneToHex,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        0L);
  }

  /** 换每轮运力（其余不动）。 */
  public MerchantPolicy withCapacityPerRound(long value) {
    return new MerchantPolicy(
        tier,
        homeHex,
        homeIsCity,
        value,
        remainingCapacity,
        laneFromHex,
        laneToHex,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        lastUnitsMoved);
  }

  /** 换本轮剩余运力（其余不动）。 */
  public MerchantPolicy withRemainingCapacity(long value) {
    return new MerchantPolicy(
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        value,
        laneFromHex,
        laneToHex,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        lastUnitsMoved);
  }

  /** 换累积惩罚（其余不动）。 */
  public MerchantPolicy withRuralTradeCostPenaltyPerMille(long value) {
    return new MerchantPolicy(
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        remainingCapacity,
        laneFromHex,
        laneToHex,
        serviceRadiusHex,
        value,
        lastUnitsMoved);
  }

  /** 换本轮实际承运量（其余不动）。 */
  public MerchantPolicy withLastUnitsMoved(long value) {
    return new MerchantPolicy(
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        remainingCapacity,
        laneFromHex,
        laneToHex,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        value);
  }

  /** ★ <b>商人层级</b>：脚夫/个体户/老板；数值逐值照探针 {@code ProbeEconomy.MerchantTier}。 */
  public enum MerchantTier {
    /** 脚夫：城区当量 1、城市折扣上限 10‰。 */
    PORTER(1, 10L),
    /** 个体户：城区当量 2、城市折扣上限 25‰。 */
    SELF_EMPLOYED(2, 25L),
    /** 老板：城区当量 4、城市折扣上限 50‰。 */
    BOSS(4, 50L);

    private final int districtUse;
    private final long transportDiscountPerMille;

    MerchantTier(int districtUse, long transportDiscountPerMille) {
      this.districtUse = districtUse;
      this.transportDiscountPerMille = transportDiscountPerMille;
    }

    /** 占用城市区的当量（探针 {@code districtUse()}：脚夫 1、个体户 2、老板 4）。 */
    public int districtUse() {
      return districtUse;
    }

    /** 城市商人能压低的运输费率（‰；探针 {@code transportDiscountPerMille()}：10/25/50）。 */
    public long transportDiscountPerMille() {
      return transportDiscountPerMille;
    }
  }
}
