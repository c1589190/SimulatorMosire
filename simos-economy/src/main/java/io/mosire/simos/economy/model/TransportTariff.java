package io.mosire.simos.economy.model;

/**
 * ★★ <b>区域间运输费率的千分纯函数</b>（P4）：把探针已验证的"基础距离费 + 辐射 + 道路折扣"逐值搬进正式运行时。
 *
 * <p>公式（千分口径，{@code ‰} 的分子）：
 *
 * <pre>
 * rate = max(0,
 *            basePerMille
 *          + perHexPerMille × distanceHex
 *          + {@link #RADIAL_COST_PER_HEX_PER_MILLE} × radialDistanceHex
 *          − {@link #ROAD_DISCOUNT_PER_LEVEL_PER_MILLE} × roadBottleneckLevel
 *          − cityDiscountPerMille
 *          + ruralPenaltyPerMille)
 * </pre>
 *
 * <p>★★ <b>逐项对照探针</b>（{@code ProbeEconomy.transportPerMille}）：
 *
 * <ul>
 *   <li>{@code basePerMille + perHexPerMille × distanceHex} = 探针的 {@code
 *       params.baseTransportPerMille() + params.perHexTransportPerMille() * distance}；
 *   <li>{@code RADIAL_COST_PER_HEX_PER_MILLE × radialDistanceHex} = 探针的离最近城市辐射成本（探针常量 20‰/hex）；
 *   <li>{@code ROAD_DISCOUNT_PER_LEVEL_PER_MILLE × roadBottleneckLevel} = 探针 {@code
 *       roadDiscountPerMille}（路径瓶颈等级 × 50‰；等级由道路图读取器给出，见 {@code RoadNetwork}）；
 *   <li>{@code cityDiscountPerMille} / {@code ruralPenaltyPerMille} = 探针的城市商人折价 / 农村商人累积成本。 ★ P4
 *       一律传 0：商人 tier/服务半径/农村累积成本归 P6；
 *   <li>末尾 {@code max(0, …)} 与探针一致：基础费 + 辐射不足以覆盖折扣时费率归零，不出现负费率。
 * </ul>
 *
 * <p>★★ <b>纯函数、无状态</b>：不读任何静态全局、不读时钟、不随机；同一个入参必然给出同一个费率。溢出用 {@link Math#addExact}/{@link
 * Math#multiplyExact}/{@link Math#subtractExact} 判死（{@link ArithmeticException}），不静默回绕。
 *
 * <p>★★ <b>量纲</b>：三个距离/等级参数是"hex 数 / 正整数等级"；两个折扣/惩罚参数与返回值都是"货款价值的千分比"（例如 {@code 150} =
 * 15%）。把它乘到货款上得到运费时，除以 {@code 1000} 的那一步由调用方负责（{@code MarketSettlement}）。
 *
 * <p>★ <b>P4 的费率值</b>：正式默认取 7HEX2 城市探针的出厂参数（基础 5‰ + 每 hex 5‰），见 {@link #probeDefaults()}。探针另一个运输队场景
 * T1 用的是 50‰ + 50‰/hex；那是探针输入参数、不是本类常量，P9 对拍时用 {@code new TransportTariff(50L, 50L)} 显式构造。
 *
 * <p>★★ <b>商品维不在这里（2026-10-09 用户裁定「甲」后的纠正）</b>：商品的运费差异是<b>面值维</b>（每件每程的基础运费）， 唯一权威 = 状态表 {@code
 * EconomyData.commodityFreightBaseMilli}（GM 可改），唯一读取口 = {@code
 * MarketSettlement.freightUnitMilli}（那里才是唯一算式）。本类只算<b>距离 / 辐射 / 道路 / 城乡</b>这一维，<b>没有</b>商品入参。 ★
 * 历史留痕：F 批（{@code f3366545}）曾在此加过第 6 个入参「商品运费系数（‰，乘在整条费率上）」，已随本轮**撤销**—— 它与早已存在的 {@link
 * CommodityFreightBase} 分档构成**两个商品维相乘**，方向相反（费率维 tool &lt; grain， 面值维 tool &gt; grain）。
 *
 * @param basePerMille 基础运费率（‰）；必须 ≥ 0
 * @param perHexPerMille 每 hex 距离费率（‰）；必须 ≥ 0
 */
public record TransportTariff(long basePerMille, long perHexPerMille) {

  /** 辐射成本：离最近城市每远 1 hex，费率增加 20‰（与探针 {@code RADIAL_COST_PER_HEX_PER_MILLE} 逐值一致）。 */
  public static final long RADIAL_COST_PER_HEX_PER_MILLE = 20L;

  /** 道路折扣：路径瓶颈每 1 级，费率降低 50‰（与探针 {@code ROAD_DISCOUNT_PER_LEVEL_PER_MILLE} 逐值一致）。 */
  public static final long ROAD_DISCOUNT_PER_LEVEL_PER_MILLE = 50L;

  /** 7HEX2 城市探针的基础费率（‰）：{@code baseTransportPerMille = 5}。 */
  private static final long PROBE_BASE_PER_MILLE = 5L;

  /** 7HEX2 城市探针的每 hex 费率（‰）：{@code perHexTransportPerMille = 5}。 */
  private static final long PROBE_PER_HEX_PER_MILLE = 5L;

  public TransportTariff {
    if (basePerMille < 0L) {
      throw new IllegalArgumentException("TransportTariff.basePerMille 不得为负: " + basePerMille);
    }
    if (perHexPerMille < 0L) {
      throw new IllegalArgumentException("TransportTariff.perHexPerMille 不得为负: " + perHexPerMille);
    }
  }

  /**
   * ★ 7HEX2 城市探针（{@code SevenHexCityMerchantProbeTest}）的运输费率出厂值：基础 5‰ + 每 hex 5‰。
   *
   * <p>两个具名常量（辐射 20‰/hex、道路 50‰/级）在探针里是常量；base/perHex 在探针里是 {@code Params} 输入参数，不同场景不同（城市 5/5、 运输队
   * 50/50）。正式默认取城市场景，因为辐射 + 道路这两项只有该场景验证过。
   */
  public static TransportTariff probeDefaults() {
    return new TransportTariff(PROBE_BASE_PER_MILLE, PROBE_PER_HEX_PER_MILLE);
  }

  /**
   * 算一条运输 lane 的费率（‰）：见类注公式与逐项对照。
   *
   * @param distanceHex 两格之间的运输距离（hex）；必须 ≥ 0
   * @param radialDistanceHex 两端到最近城市距离的较小者（hex）；必须 ≥ 0（没有城市节点按 0）
   * @param roadBottleneckLevel from→to 道路路径的瓶颈等级；必须 ≥ 0（没有可达道路按 0）
   * @param cityDiscountPerMille 城市商人折价（‰）；P4 传 0，P6 接入
   * @param ruralPenaltyPerMille 农村商人累积成本（‰）；P4 传 0，P6 接入
   * @return 非负费率（‰）
   * @throws IllegalArgumentException 距离/辐射距离/道路等级出现负数时
   * @throws ArithmeticException 中间量超出 {@code long} 时（不静默回绕）
   */
  public long perMille(
      long distanceHex,
      long radialDistanceHex,
      long roadBottleneckLevel,
      long cityDiscountPerMille,
      long ruralPenaltyPerMille) {
    if (distanceHex < 0L) {
      throw new IllegalArgumentException("TransportTariff.distanceHex 不得为负: " + distanceHex);
    }
    if (radialDistanceHex < 0L) {
      throw new IllegalArgumentException(
          "TransportTariff.radialDistanceHex 不得为负: " + radialDistanceHex);
    }
    if (roadBottleneckLevel < 0L) {
      throw new IllegalArgumentException(
          "TransportTariff.roadBottleneckLevel 不得为负: " + roadBottleneckLevel);
    }
    // cityDiscount/ruralPenalty 是 P6 才接入的读数参数：P4 一律传 0。探针公式不对它们加守卫，
    // 这里同样只逐值复刻公式（对这两个参数不额外判负）。
    long rate = basePerMille;
    rate = Math.addExact(rate, Math.multiplyExact(perHexPerMille, distanceHex));
    rate =
        Math.addExact(rate, Math.multiplyExact(RADIAL_COST_PER_HEX_PER_MILLE, radialDistanceHex));
    rate =
        Math.subtractExact(
            rate, Math.multiplyExact(ROAD_DISCOUNT_PER_LEVEL_PER_MILLE, roadBottleneckLevel));
    rate = Math.subtractExact(rate, cityDiscountPerMille);
    rate = Math.addExact(rate, ruralPenaltyPerMille);
    return Math.max(0L, rate);
  }
}
