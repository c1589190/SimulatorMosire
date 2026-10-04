package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>商号持久状态（P10.1，7 hex 全链路架构 §3.2 的第 31 个组件值类型）</b>。
 *
 * <p>★★ <b>它是什么</b>：一个真实运转的承运商号（脚夫/个体户/老板三档）的不可变读数。商号本体仍然是 {@code ProductionOrganization(modeId =
 * merchant)}，本类型只是挂在它旁边的一行财务/运力/服务半径状态；<b>不另造商人实体</b> （身份 = {@link #organizationId()}，与 {@code
 * EconomyData.merchantFirms} 的键逐字相等）。
 *
 * <p>★★ <b>本批只落形状，不跑公式</b>：运力增减（+5/−5、上限 100000、下限 5）、农村累积惩罚（+2/活跃轮、封顶 100‰）、城区/船畜维护、以及承运选择都在后续的
 * {@code MerchantSettlement}；本类不写状态表、不读时钟、不产生副作用。 {@link #withCapacityDelta(long)} / {@link
 * #withRoundReset()} / {@link #withTradeResult(long, long, long)} 只是纯 copy-with，把"怎么变"留给结算层。
 *
 * <p>★★ <b>tier 的词表复用 P6 {@link MerchantPolicy.MerchantTier}</b>（同包、相邻模型类）：三档 PORTER /
 * SELF_EMPLOYED / BOSS 的城区当量与城市折扣在 {@code MerchantPolicy} 已有一个拼写点，本类不复制第二份枚举。 服务半径默认值（PORTER=2 /
 * SELF_EMPLOYED=4 / BOSS=8）由 {@link #defaultServiceRadiusHex(MerchantPolicy.MerchantTier)} 给出；GM
 * 可在显式构造时覆盖（{@code serviceRadiusHex} 是状态字段，不是只读派生）。
 *
 * <p>★ <b>构造期守卫（fail-closed）</b>：
 *
 * <ul>
 *   <li>{@code organizationId}/{@code tier}/{@code homeHex} 非 null；
 *   <li>{@code capacityPerRound > 0}（0 运力 = 不存在这条商号；缩编下限由结算层按 §10 取 5）；
 *   <li>{@code capacityUsedThisRound ≥ 0}；{@code serviceRadiusHex ≥ 0}（0 表示不按半径服务，语义同 {@code
 *       MerchantPolicy.serviceRadiusHex}）；
 *   <li>{@code ruralTradeCostPenaltyPerMille ∈ [0, 100]}（与 P6 探针的封顶一致）；
 *   <li>{@code lastFeeEarnedMilli ≥ 0}、{@code lastUpkeepMilli ≥ 0}；<b>只有 {@code lastProfitMilli}
 *       允许为负</b>（上一周期净收益可亏）。
 * </ul>
 *
 * <p>★ 没有 {@code capacityUsedThisRound ≤ capacityPerRound} 的守卫：运力可以在"本轮已用满"之后被结算层缩编，
 * 那个中间态必须能构造出来；上界判据留给结算步（选中要求 {@code used < capacity}）。
 *
 * @param organizationId 商号对应的生产组织（{@code EconomyData.merchantFirms} 的键 == 本值）；不得为 null
 * @param tier 商人层级（复用 P6 {@link MerchantPolicy.MerchantTier}）；不得为 null
 * @param homeHex 商号所在格（城市商号的服务中心）；不得为 null
 * @param homeIsCity 是否城市商号（§3.2 暂定新档为 true；农村商号的累积惩罚由后续结算用本布尔分支）
 * @param capacityPerRound 本周期可用运力（毫单位）；必须 &gt; 0
 * @param capacityUsedThisRound 本周期已用运力（轮初由 {@link #withRoundReset()} 清零）；不得为负
 * @param serviceRadiusHex 服务半径（hex）；不得为负；PORTER=2 / SELF_EMPLOYED=4 / BOSS=8
 * @param ruralTradeCostPenaltyPerMille 农村商号累积成本（‰）；必须 ∈ [0, 100]
 * @param lastFeeEarnedMilli 上一周期运费实收；不得为负
 * @param lastUpkeepMilli 上一周期城区/船畜维护支出；不得为负
 * @param lastProfitMilli 上一周期净收益（可负）
 */
public record MerchantFirm(
    ProductionOrganizationId organizationId,
    MerchantPolicy.MerchantTier tier,
    HexCoord homeHex,
    boolean homeIsCity,
    long capacityPerRound,
    long capacityUsedThisRound,
    long serviceRadiusHex,
    long ruralTradeCostPenaltyPerMille,
    long lastFeeEarnedMilli,
    long lastUpkeepMilli,
    long lastProfitMilli) {

  /** PORTER（脚夫）的默认服务半径（hex；架构 §10）。 */
  public static final long PORTER_SERVICE_RADIUS_HEX = 2L;

  /** SELF_EMPLOYED（个体户）的默认服务半径（hex；架构 §10）。 */
  public static final long SELF_EMPLOYED_SERVICE_RADIUS_HEX = 4L;

  /** BOSS（老板）的默认服务半径（hex；架构 §10）。 */
  public static final long BOSS_SERVICE_RADIUS_HEX = 8L;

  /** 农村累积惩罚上限（‰）：与 P6 {@code MerchantPolicy.RURAL_PENALTY_CAP_PER_MILLE} 同值、复用同一拼写点。 */
  public static final long RURAL_TRADE_COST_PENALTY_CAP_PER_MILLE =
      MerchantPolicy.RURAL_PENALTY_CAP_PER_MILLE;

  public MerchantFirm {
    Objects.requireNonNull(organizationId, "MerchantFirm.organizationId 不得为 null");
    Objects.requireNonNull(tier, "MerchantFirm.tier 不得为 null");
    Objects.requireNonNull(homeHex, "MerchantFirm.homeHex 不得为 null");
    if (capacityPerRound <= 0L) {
      throw new IllegalArgumentException(
          "MerchantFirm.capacityPerRound 必须 > 0: " + capacityPerRound);
    }
    if (capacityUsedThisRound < 0L) {
      throw new IllegalArgumentException(
          "MerchantFirm.capacityUsedThisRound 不得为负: " + capacityUsedThisRound);
    }
    if (serviceRadiusHex < 0L) {
      throw new IllegalArgumentException("MerchantFirm.serviceRadiusHex 不得为负: " + serviceRadiusHex);
    }
    if (ruralTradeCostPenaltyPerMille < 0L
        || ruralTradeCostPenaltyPerMille > RURAL_TRADE_COST_PENALTY_CAP_PER_MILLE) {
      throw new IllegalArgumentException(
          "MerchantFirm.ruralTradeCostPenaltyPerMille 必须 ∈ [0, "
              + RURAL_TRADE_COST_PENALTY_CAP_PER_MILLE
              + "]: "
              + ruralTradeCostPenaltyPerMille);
    }
    if (lastFeeEarnedMilli < 0L) {
      throw new IllegalArgumentException(
          "MerchantFirm.lastFeeEarnedMilli 不得为负: " + lastFeeEarnedMilli);
    }
    if (lastUpkeepMilli < 0L) {
      throw new IllegalArgumentException("MerchantFirm.lastUpkeepMilli 不得为负: " + lastUpkeepMilli);
    }
    // ★ lastProfitMilli 允许为负（亏损是正常状态）；无守卫。
  }

  /** 便利构造：城市商号（{@code homeIsCity = true}，§3.2 的暂定口径）+ 显式服务半径。字段与规范构造器同序，只是省掉布尔位。 */
  public MerchantFirm(
      ProductionOrganizationId organizationId,
      MerchantPolicy.MerchantTier tier,
      HexCoord homeHex,
      long capacityPerRound,
      long capacityUsedThisRound,
      long serviceRadiusHex,
      long ruralTradeCostPenaltyPerMille,
      long lastFeeEarnedMilli,
      long lastUpkeepMilli,
      long lastProfitMilli) {
    this(
        organizationId,
        tier,
        homeHex,
        true,
        capacityPerRound,
        capacityUsedThisRound,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /** 便利构造：显式城市/农村位，但服务半径按 tier 取默认值（避免每个调用点自己查 2/4/8）。 */
  public MerchantFirm(
      ProductionOrganizationId organizationId,
      MerchantPolicy.MerchantTier tier,
      HexCoord homeHex,
      boolean homeIsCity,
      long capacityPerRound,
      long capacityUsedThisRound,
      long ruralTradeCostPenaltyPerMille,
      long lastFeeEarnedMilli,
      long lastUpkeepMilli,
      long lastProfitMilli) {
    this(
        organizationId,
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        capacityUsedThisRound,
        defaultServiceRadiusHex(tier),
        ruralTradeCostPenaltyPerMille,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /**
   * ★ 按 tier 推导默认服务半径（PORTER=2 / SELF_EMPLOYED=4 / BOSS=8；架构 §10 的具名默认）。
   *
   * <p>它只服务"构造默认值"这一处；GM 显式给 {@code serviceRadiusHex} 时以显式值为准（值类型不重算）。
   */
  public static long defaultServiceRadiusHex(MerchantPolicy.MerchantTier tier) {
    Objects.requireNonNull(tier, "tier 不得为 null");
    return switch (tier) {
      case PORTER -> PORTER_SERVICE_RADIUS_HEX;
      case SELF_EMPLOYED -> SELF_EMPLOYED_SERVICE_RADIUS_HEX;
      case BOSS -> BOSS_SERVICE_RADIUS_HEX;
    };
  }

  /**
   * ★ 静态工厂：字段与规范构造器同序，唯一差别是 {@code serviceRadiusHex} 按 tier 取默认值 —— 避免每个调用点各写一遍
   * 2/4/8（那是同一个默认值的第二处拼写）。
   */
  public static MerchantFirm of(
      ProductionOrganizationId organizationId,
      MerchantPolicy.MerchantTier tier,
      HexCoord homeHex,
      boolean homeIsCity,
      long capacityPerRound,
      long capacityUsedThisRound,
      long ruralTradeCostPenaltyPerMille,
      long lastFeeEarnedMilli,
      long lastUpkeepMilli,
      long lastProfitMilli) {
    return new MerchantFirm(
        organizationId,
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        capacityUsedThisRound,
        defaultServiceRadiusHex(tier),
        ruralTradeCostPenaltyPerMille,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /** 上一条的重载：城市商号（{@code homeIsCity = true}）+ 半径按 tier 默认 —— 与 P10.1 载荷字段清单同形（无布尔位）。 */
  public static MerchantFirm of(
      ProductionOrganizationId organizationId,
      MerchantPolicy.MerchantTier tier,
      HexCoord homeHex,
      long capacityPerRound,
      long capacityUsedThisRound,
      long ruralTradeCostPenaltyPerMille,
      long lastFeeEarnedMilli,
      long lastUpkeepMilli,
      long lastProfitMilli) {
    return new MerchantFirm(
        organizationId,
        tier,
        homeHex,
        capacityPerRound,
        capacityUsedThisRound,
        defaultServiceRadiusHex(tier),
        ruralTradeCostPenaltyPerMille,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /** 换每周期运力（{@code delta} 可正可负；新值由规范构造器判 {@code > 0}，越界/溢出当场抛）。 */
  public MerchantFirm withCapacityDelta(long delta) {
    return new MerchantFirm(
        organizationId,
        tier,
        homeHex,
        homeIsCity,
        Math.addExact(capacityPerRound, delta),
        capacityUsedThisRound,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /** 换本周期已用运力（其余不动；语义只是数据，不在这里归零或封顶）。 */
  public MerchantFirm withCapacityUsedThisRound(long value) {
    if (capacityUsedThisRound == value) {
      return this;
    }
    return new MerchantFirm(
        organizationId,
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        value,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /** 轮初归位：把本周期已用运力清零（架构 §3.2 的"轮初清零"；容量不动）。 */
  public MerchantFirm withRoundReset() {
    if (capacityUsedThisRound == 0L) {
      return this;
    }
    return withCapacityUsedThisRound(0L);
  }

  /** 换服务半径（GM 可调；负值由规范构造器拒）。 */
  public MerchantFirm withServiceRadiusHex(long value) {
    if (serviceRadiusHex == value) {
      return this;
    }
    return new MerchantFirm(
        organizationId,
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        capacityUsedThisRound,
        value,
        ruralTradeCostPenaltyPerMille,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /** 换农村累积成本（‰；范围由规范构造器判 [0, 100]）。 */
  public MerchantFirm withRuralTradeCostPenaltyPerMille(long value) {
    if (ruralTradeCostPenaltyPerMille == value) {
      return this;
    }
    return new MerchantFirm(
        organizationId,
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        capacityUsedThisRound,
        serviceRadiusHex,
        value,
        lastFeeEarnedMilli,
        lastUpkeepMilli,
        lastProfitMilli);
  }

  /**
   * 写入上一周期贸易结果：运费实收 / 维护支出 / 净收益（可负）。<b>不改运力</b> —— 运力增减由 {@link #withCapacityDelta(long)}
   * 显式表达（policy 与结果分离，便于后续按 §10 的 +5/−5、上限/下限取值）。
   */
  public MerchantFirm withTradeResult(long feeEarnedMilli, long upkeepMilli, long profitMilli) {
    if (lastFeeEarnedMilli == feeEarnedMilli
        && lastUpkeepMilli == upkeepMilli
        && lastProfitMilli == profitMilli) {
      return this;
    }
    return new MerchantFirm(
        organizationId,
        tier,
        homeHex,
        homeIsCity,
        capacityPerRound,
        capacityUsedThisRound,
        serviceRadiusHex,
        ruralTradeCostPenaltyPerMille,
        feeEarnedMilli,
        upkeepMilli,
        profitMilli);
  }
}
