package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MerchantCapacityPool.GET_READY_SURCHARGE_PER_MILLE;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class MerchantCapacityMath {

  private MerchantCapacityMath() {}

  /**
   * ★ <b>A3：{@link MerchantCapacity} 作业层的**工具维**键</b>（会话商品账口径；V-22：读的是商品账，**不动** {@code
   * AssetKind.SMALL_TOOL} 产权份额）。
   *
   * <p>★ A3 起它<b>不再是门槛</b>（旧 {@code MerchantHaul.TOOL_COMMODITY} 的那套"每趟烧 1,000 毫工具"已删）：
   * 这里读它只为把运力规模（tier / share 的派生依据）算出来。⇒ 收成 {@code private}（不再是对外拼写点）。
   */
  static final CommodityId TOOL_COMMODITY = new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /**
   * 纯状态派生查询（工具维取 0 ⇒ 运力下界，J-2）：该格有没有"选了跑商且有运力"的家户。
   *
   * <p>★★ <b>B2：入参是"当日视图"（不是段首 {@code EconomyData}）</b> —— 本方法同时读阶层归属、家户位置与劳动，
   * 三类都<b>逐日会变</b>；只换一半会拼出"当刻归属 + 段首位置"的混合日视图（接口类注明令禁止）。
   *
   * <p>★★ <b>W7</b>：判据改走 {@link #providerCapacityMilli}（= 决策层与结算层的**同一把尺**，见那个方法的类注）。
   */
  public static boolean hasCapacityAt(EconomyDayView base, HexCoord hex) {
    return hexCapacityMilli(base, hex, Map.of()) > 0L;
  }

  /**
   * ★★ <b>M-D：该格运力总量（纯函数；与 {@link #of} 装配同一条算式）</b>—— 逐户 {@link #providerCapacityMilli} 后求和。
   *
   * @param goods 会话商品账（读 {@code tool} 与 {@code haul} 存量）；没有商品账 ⇒ 传空表（工具项 = 0 ⇒ **运力下界**，J-2）
   */
  public static long hexCapacityMilli(
      EconomyDayView base, HexCoord hex, Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(goods, "goods");
    long total = 0L;
    List<HouseholdId> households = new ArrayList<>(base.classStandings().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      long capacity = memberCapacityMilli(base, household, hex, goods);
      total = Math.addExact(total, capacity);
    }
    return total;
  }

  /**
   * ★★★ <b>W7（2026-10-11）："一个户能交付多少运力"的**唯一派生量** —— 决策层与结算层读同一份</b>。
   *
   * <p>★★ <b>用户派单口径（逐字）</b>：「把 {@code ExpectedProfitBook.merchantProspect} 的判据与结算层（承运/议价权）的判据
   * <b>统一到同一口径</b> —— 以能真交付服务的那一侧为准（或两侧都读同一份派生量）」。
   *
   * <pre>
   * 服务成市的格（{@link HaulService#pricedAt}）：capacity = max(服务货可用量, 劳动 + 工具能产出的量)
   *                                                ↑ 结算层口径（I-H2：手上真的有多少服务可卖）
   *                                                             ↑ 决策层口径（本周期能产出多少服务）
   * 其余格：                                    capacity = 劳动 + 工具能产出的量（逐值退回改前）
   * </pre>
   *
   * <p>★★ <b>为什么取<b>较大者</b>（而不是只取服务货）—— 这是"两把尺"里那处误判的正面修复</b>：
   *
   * <ul>
   *   <li><b>只取服务货 ⇒ 先有鸡还是先有蛋</b>：一间刚开张（或刚迁到新格）的商号<b>本周期还没有任何服务货</b>， 若决策层据此判它"不能跑商"，它就永远当不上主业 ⇒
   *       永远不产出服务 ⇒ 路自己堵死；
   *   <li><b>只取劳动 + 工具 ⇒ 报告实测的那处误判</b>：一个<b>手上有服务货、但劳动/工具为 0</b> 的户（{@code 0_2} 正是） 被判 {@code
   *       no-bargaining-power}，<b>永远当不上主业跑商，而它其实能卖服务</b>（那批服务货随时可交付）；
   *   <li><b>取较大者 = 两条交付路径的并集</b>：现在就能交付的（服务货）与本周期能产出的（劳动 + 工具）都算数 ⇒
   *       上面两种误判<b>同时消失</b>，且判据只有一个名字、一处拼写。
   * </ul>
   *
   * <p>★ <b>方向上单调（不收紧任何既有准入）</b>：服务成市的格里本式 ≥ 改前的 {@code MerchantCapacity.of(labor, tool)} ⇒
   * 决策层只会接受**更多**候选（多出来的每一个仍要过 {@code ExpectedProfitBook} 的诚实预估与"承运资产自备"约束）； 非服务成市的格逐值不变。★
   * 结算层**不受本式影响**：{@link #of} 装配的运力预算仍是**手上的服务货** （I-H2：卖多少就得有多少货，服务不能凭空造）。
   *
   * @param goods 会话商品账（读 {@code tool} 与 {@code haul}）；纯状态读者传 {@code Map.of()} ⇒ 两项都取 0 = 下界（J-2）
   */
  public static long providerCapacityMilli(
      EconomyDayView base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(goods, "goods");
    HouseholdEconomy row = base.classes().get(household);
    if (row == null) {
      return 0L;
    }
    Map<CommodityId, Long> account = goods.getOrDefault(household, Map.of());
    long toolMilli = account.getOrDefault(TOOL_COMMODITY, 0L);
    long productive =
        MerchantCapacity.capacityMilliOf(row.participationAdjustedLaborMilli(), toolMilli);
    if (!HaulService.pricedAt(MarketTables.atOrNull(base, hex))) {
      return productive; // 服务不成市 ⇒ 逐值退回改前（缺省中性）
    }
    long serviceAvailable = Math.max(0L, account.getOrDefault(HaulService.HAUL_COMMODITY, 0L));
    return Math.max(serviceAvailable, productive);
  }

  /**
   * ★★ <b>M-D：一个家户在本格的运力（纯函数；不是池成员 ⇒ 0）</b>—— 成员判据与装配同源（{@link
   * MerchantIdentity#selectsMerchant}），算式同源（{@link #providerCapacityMilli}，W7 起 = 两把尺合一的那一份）。
   */
  static long memberCapacityMilli(
      EconomyDayView base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    HouseholdClassMembership standing = base.classStandings().get(household);
    if (!MerchantIdentity.selectsMerchant(standing, base.classPositions())) {
      return 0L;
    }
    HouseholdEconomy row = base.classes().get(household);
    if (row == null || !row.view().hex().equals(hex)) {
      return 0L;
    }
    return providerCapacityMilli(base, household, hex, goods);
  }

  /**
   * ★★ <b>M-D / §11.4 G-1：一个家户作为运力提供者的**市场议价权**（占比‰）</b>—— 排序输入之一（计划 §7 Q-19： 与 {@code
   * MerchantCapacityPool} 的分配序**同一口径、同一拼写点**，不得两套）。
   *
   * <pre>
   * 该户**已是池成员**（{@code selectsMerchant} 且住在本格）⇒ share = 该户可交付运力 × 1000 ÷ 全池可交付运力
   * 其余（本格候选新进入者 / 邻格准备迁入的候选）        ⇒ share = 该户可交付运力 × 1000 ÷ (全池 + 该户)
   * </pre>
   *
   * <p>★★ <b>W7（2026-10-11）：分母从"本格运力总量"改成"全池可交付运力"</b> —— 与 {@link #assemble} 里 {@code
   * Entry.sharePerMille} 的分母<b>同源</b>（运力池全市场区共享，分配序是一份全局序）。只有一个非空格的世界里两者逐值相同。 ★
   * 原来的"邻格候选必须按新进入者算（分母加自己）"那条理由（"运力池挂在发货格"）已被用户裁定取代，但**算法保留**：
   * 它不是本格语境的残留，而是"**我进去之后占多少**"的正确口径（新进入者不在当前池里）。
   *
   * <p>★ 除法与占比口径的唯一拼写点 = {@link MerchantCapacity#sharePerMilleOf}（本方法只负责"分母是哪一个"）。 ★ 纯状态读者（没有商品账）传
   * {@code Map.of()} ⇒ 工具/服务两项都取 0 ⇒ 占比是**下界**（J-2 的具名偏差，方向 fail-closed）。
   */
  public static long sharePerMilleAsProviderAt(
      EconomyDayView base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(goods, "goods");
    HouseholdEconomy row = base.classes().get(household);
    if (row == null) {
      return 0L;
    }
    long capacity = providerCapacityMilli(base, household, hex, goods);
    if (capacity <= 0L) {
      return 0L; // 既没有可交付的服务货、也产不出运力 ⇒ 占比 0（不猜）
    }
    boolean residentMember =
        row.view().hex().equals(hex)
            && MerchantIdentity.selectsMerchant(
                base.classStandings().get(household), base.classPositions());
    long total = poolCapacityMilli(base, goods);
    if (!residentMember) {
      // 本格候选新进入者 / 邻格准备迁入的候选：把自己算进分母（"我进去之后占多少"）
      total = Math.addExact(total, capacity);
    }
    return MerchantCapacity.sharePerMilleOf(capacity, total);
  }

  /**
   * ★★ <b>W7：全池可交付运力（纯函数；{@link #sharePerMilleAsProviderAt} 与逐格分母的共同来源）</b>—— 逐户按**它自己住的格**取 {@link
   * #providerCapacityMilli} 后求和（家的位置 = {@code HouseholdEconomy.view().hex()}）。
   *
   * <p>★ 键序 = 家户 id 规范串升序（I7：内容的纯函数）。
   */
  public static long poolCapacityMilli(
      EconomyDayView base, Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(goods, "goods");
    long total = 0L;
    List<HouseholdId> households = new ArrayList<>(base.classStandings().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      HouseholdEconomy row = base.classes().get(household);
      if (row == null) {
        continue;
      }
      total = Math.addExact(total, memberCapacityMilli(base, household, row.view().hex(), goods));
    }
    return total;
  }

  /**
   * ★★ <b>A3（2026-10-10）：一个提供者的限价（‰）—— 纯派生、唯一拼写点</b>（旧 {@link CapacityQuote} 的两级入口 {@code
   * askPerMilleOf} / {@code selfQuotedPerMilleOf} 已随自报价簿退役，算式原值保留在此）。
   *
   * <pre>
   * 报价口径（priced = true，生产路径）   限价 = {@link MarketSettlement#carrierCostPerMille}(tier) + {@link #GET_READY_SURCHARGE_PER_MILLE}
   * 缺省口径（priced = false，夹具/读者）限价 = {@link MarketSettlement#carrierCostPerMille}(tier)   ← 逐值等于 M-A1 的派生承运成本
   * </pre>
   *
   * <p>★ 为什么缺省口径**不加**上门附加费：缺省口径下这不是"买运力"（没有服务市场），而是 M-A1 的按议价权分摊 —— 加一笔新费用会让"无报价 ⇒ 逐值不变"当场不成立。
   */
  static long askPerMilleOf(MerchantCapacity capacity, boolean priced) {
    Objects.requireNonNull(capacity, "capacity");
    long cost = MarketSettlement.carrierCostPerMille(capacity.tier());
    return priced ? Math.addExact(cost, GET_READY_SURCHARGE_PER_MILLE) : cost;
  }
}
