package io.mosire.simos.economy.time;

import io.mosire.simos.economy.model.MerchantPolicy;
import java.util.Objects;

/**
 * ★★ <b>M-A2：运力提供者的自报价（限价）—— 全仓唯一拼写点</b>。
 *
 * <p>★★ <b>它是什么</b>：一个"选了跑商"的家户（运力提供者，见 {@link MerchantCapacity}）对它本轮运力服务挂出的
 * <b>限价</b>（‰）。买方按<b>最低价优先</b>买运力（设计书 §15.2 K-C），因此限价就是买方在一条 lane 上的选择键。
 * 与"公式价"的区别：<b>每户各报各的</b>（按其成本与规模），不是全市场一个统一价（K-6）。
 *
 * <p>★★ <b>算式（本批冻结；成本维 × 规模维 + 上门腿）</b>：
 *
 * <pre>
 * 自报价_户（‰） = {@link MarketSettlement#carrierCostPerMille}（= 派生 tier 的城区当量 × 25‰/档）
 *                  ↑ 成本维：承运"要吃饭"的粗估（算式逐字沿用 M-A1）
 *                  ↑ 规模维：tier 由该户本轮运力（{@link MerchantCapacity#tierOf(long)}）派生
 *                            ⇒ 规模已经体现在成本里，不另设第二项
 * 含上门限价（‰） = 自报价_户 + {@link #GET_READY_SURCHARGE_PER_MILLE}
 * </pre>
 *
 * <p>★★ <b>"运力过来同样需要运力" ⇒ 深度 1 + 固定附加费（Q-24 默认）</b>：用户原话「注意"运力"过来同样需要运力」 若无终止规则就是无限回归（设计书 §15.4
 * K-1）。本批按冻结口径落地为：<b>运力服务含"上门"</b>——把承运者/批次带到 <b>发货格</b>这一腿按 {@link #GET_READY_SURCHARGE_PER_MILLE}
 * 这个<b>具名固定附加费</b>计入限价， <b>不再递归展开</b>（不问"把承运者带过来还需要多少运力"）。"固定"= 只与运力单位挂钩，<b>不随距离/批次/递归深度变化</b>。
 *
 * <p>★★ <b>具名缺省（为什么是这个值）</b>：报价表（{@link CapacityQuoteBook}）里没有该家户的运力单时，取 {@link
 * #selfQuotedPerMilleOf} —— 它<b>就是 M-A1 的派生承运成本</b>。理由：该算式是运力服务在本仓的唯一既有定价 口径（{@code
 * MarketSettlement.carrierCostPerMille}），缺省取它 ⇒ <b>无报价的世界逐值等于 M-A1</b>（I-C2 缺省语义中性）， 不需要发明第二套定价。
 *
 * <p>★ 量纲：与既有承运成本同量纲（‰，货款价值的千分比）⇒ <b>不新造量纲</b>（K-5）；它最终经 {@code MarketSettlement.freightUnitMilli}
 * 折成"毫钱 / 商品单位"的单位运费。
 */
public final class CapacityQuote {

  private CapacityQuote() {}

  /**
   * ★★ <b>"运力过来"这一腿的具名固定附加费（‰）</b>：<b>25</b>。
   *
   * <p>取值理由：与既有承运成本的一档步长同值（{@link
   * MarketSettlement#MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP} =
   * 25‰/档）——"一趟上门"按一个档次的运营成本计价，是粗估、可由 GM 后续调；<b>只改这一个常量就改上门腿</b>。
   *
   * <p>★ <b>深度 1</b>：它<b>只加一次</b>，不参与递归（没有"上门的上门"）；也不随 lane 距离变化（K-1 的"固定"）。
   */
  public static final long GET_READY_SURCHARGE_PER_MILLE = 25L;

  /**
   * ★★ <b>自报价（限价，‰）</b>：{@code 派生承运成本(tier)} = 城区当量 × 25‰。
   *
   * <p>"按其成本与规模"：成本维 = 该算式本身；规模维 = {@code tier} 由该户本轮运力派生（{@link
   * MerchantCapacity#tierOf(long)}，脚夫/个体户/老板三档 1:2:4）。
   */
  public static long selfQuotedPerMilleOf(MerchantPolicy.MerchantTier tier) {
    Objects.requireNonNull(tier, "tier 不得为 null");
    return MarketSettlement.carrierCostPerMille(tier);
  }

  /**
   * ★★ <b>给一个自报价加上"上门"腿（‰）</b> = {@code 自报价 + }{@link #GET_READY_SURCHARGE_PER_MILLE}（深度 1，只加一次）。
   *
   * <p>它是"运力服务含上门"这一口径的<b>唯一拼写点</b>：{@link CapacityQuoteBook} 把逐户挂单的限价接进来。
   */
  public static long withGetReadyPerMille(long quotedPerMille) {
    if (quotedPerMille < 0L) {
      throw new IllegalArgumentException("运力限价不得为负: " + quotedPerMille);
    }
    return Math.addExact(quotedPerMille, GET_READY_SURCHARGE_PER_MILLE);
  }

  /**
   * ★★ <b>具名缺省限价（‰）</b>：家户没挂运力单时用的值 —— <b>与 {@link #selfQuotedPerMilleOf} 同式</b>。
   *
   * <p>保留成本方法而不是让调用方直接写算式，是为了让"缺省"这件事有一个可被引用、可被日志具名的名字（ "缺省 = 既有派生承运成本，理由 = M-A1 同源 ⇒ 无报价世界逐值不变"）。
   */
  public static long defaultQuotedPerMilleOf(MerchantPolicy.MerchantTier tier) {
    return selfQuotedPerMilleOf(tier);
  }
}
