package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.util.economy.EconomyVocabulary;

/**
 * ★★ <b>A2：运输服务（{@code haul}）作为商品的**口径唯一拼写点**</b>—— 约束设计书 {@code
 * docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md}（v1.1）§3.3 需求/§5 I-H2/I-H5。
 *
 * <p>★★ <b>用户原话（2026-10-10，逐字，设计书 §1）</b>：
 *
 * <blockquote>
 *
 * 「运输服务我不是说算商品吗，只是这个商品的需求需要额外通过其他已有商品的购买来计算，要我选的话我肯定选A」<br>
 * 「运力作为特殊商品不可储存转卖，只能计算后在生产环节统一兑现、自动算收益」
 *
 * </blockquote>
 *
 * <p>★★ <b>本类是什么</b>：① 服务商品的 {@link #HAUL_COMMODITY} 与它的消耗落点 {@link #SERVICE_CONSUMED_ACCOUNT}
 * 两处具名常量；② 把**既有运费口径**折算成服务量的两个纯函数（{@link #serviceDemandMilli} = 需求量、 {@link #unitFreightMilli} =
 * 单位运费），它们是"一笔跨格货单派生多少运输服务需求、买方按什么单价买"的唯一算式。
 *
 * <p>★★ <b>数量口径（★ 与既有运费/运力口径的可核对关系，一行算式）</b>：
 *
 * <pre>
 * 服务需求(毫服务) = {@link CapacityDemand#workMilliOf}(货量(毫商品), {@link CapacityDemand#workPerGoodPerMille}(基础运费, 路线费率‰))
 *                  = ⌈ 货量 × max(1, 基础运费 × (1000 + 路线费率‰)) ÷ 1000 ⌉        ← 与 {@code CapacityDemand} **同一个**函数
 * 单位运费(毫钱/商品单位) = {@link #unitFreightMilli}(服务耗用‰, 服务牌价) = max(1, ⌈ 服务耗用‰ × 服务牌价 ÷ 1000 ⌉)
 * 本笔运费(毫钱)         = ⌈ 货量 × 单位运费 ÷ 1000 ⌉                              ← 与既有 {@code freightOf} 逐字同形
 * </pre>
 *
 * <p>★★ <b>它与改前"承运成本口径"的关系（为什么这不是新造量纲）</b>：既有单位运费是 {@code max(1, ⌈ 基础运费 × (1000+路线费率‰) ×
 * (1000+承运成本‰) ÷ 10^6 ⌉)}；本类的单位运费把 <b>第三个因子 {@code (1000+承运成本‰)} 换成 {@code 服务牌价 × 1000}</b> ——
 * 同一量纲（毫计价货币/商品单位）、同一算式骨架，换掉的只是 "谁定价"：改前由运力池派生的 tier 成本定价，改后由**市场上运输服务的牌价**定价（设计书 §3.3 的"走市场自然议价"）。
 * 两条独立口径在 {@code 服务牌价 = (1000+承运成本‰)/1000}（脚夫档 25‰ ⇒ 1.025 毫）处逐值重合 ⇒ <b>本类不含任何新量纲</b>。
 *
 * <p>★ <b>服务量的单位锚（A1 冻结）</b>：{@code 1 商品单位 haul = 1,000 毫服务 = 1,000 毫商品·程} （{@link CapacityDemand}
 * / {@link MerchantCapacity} 的既有运力量纲）⇒ 服务商品账上的"毫"与运力预算的"毫商品·程"<b>1:1</b> ⇒ 需求量可以直接当运力预算扣，不需要第二次换算。
 *
 * <p>★★ <b>I-H2（货物守恒）与"不可储存转卖"</b>：服务在**成交那一刻**被消耗（卖方账户减 + 既有损耗落点加， Σ余额 + losses 守恒），买方**不接手**库存 ⇒
 * 一份运力不可能被卖两轮、也不可能被转卖 —— 这正是用户原话 「运力作为特殊商品不可储存转卖」的字面执行。★ 与设计书 §3.1 的"可储存（作为普通商品）"<b>不一致</b>， 按
 * §一.8.1（用户原话优先）取"成交即消耗"，理由已记进实现账本 D-A2-3。
 *
 * <p>★★★ <b>A5（2026-10-10）+ A7："不可储存"的另一半 —— 没卖出去的那一份到周期边界<b>作废</b></b>。A2 只做到"成交即消耗" ⇒
 * 未卖出的服务会**跨周期累积**、下一周期照样被当运力卖（A4 真实 world 实测：Σ现货 = Σ净产 − Σ交付 恰好相等， A 227,997 / B 307,988 毫服务）。A5
 * 按同一条用户裁定补上：产出运输服务的 unit 关账时，把它账户主体家户手上**上一周期 未卖出的**服务货作废（{@link
 * #SERVICE_EXPIRED_ACCOUNT}，在收获入账之前）⇒ 任何时刻池里能卖的服务货**只可能**是本周期产出的。 ★★ <b>A7 扩域</b>：A6 实测到逃逸口 ——
 * 服务货会经实物还债等转移**离开**产它的家户（B 世界 day 240 的 5,249 毫落在 "已退出商家名册的持货户"，跨 day 360/480
 * 两次周期边界都没被销毁）；一旦该户被选回跑商，陈货立刻变成可卖运力 ⇒ 清扫范围从"关账 unit 的主体家户"扩到 <b>任何持有 {@code haul}
 * 的家户</b>（时点与落点不变：关账日、收获入账之前； 数量 = 每户 {@code max(0, 现货 − 冻结)}）。 两半合起来才是「不可储存转卖」：既不能卖两轮，也不能存到下个周期再卖。
 */
public final class HaulService {

  private HaulService() {}

  /** 服务商品（{@code haul}；A1 起在商品词表里，字面量唯一拼写点仍是 {@link EconomyVocabulary}）。 */
  public static final CommodityId HAUL_COMMODITY =
      new CommodityId(EconomyVocabulary.HAUL_COMMODITY_ID);

  /**
   * ★★ <b>服务被消耗后记进哪个损耗账</b>（{@code ProductionLedger.Accumulator.addLoss} 的键）。
   *
   * <p>★ 与既有两条分开，读数不混：{@code MarketSettlement.TRANSPORT_LOSS_ACCOUNT}（{@code market-transport} =
   * 货损）、本项（{@code market-haul-service} = 运输服务被消耗掉）。★ 守恒式不变：账户余额减、损耗账加 ⇒ Σ余额 + losses 逐值守恒（I-H2）。
   *
   * <p>★★ <b>A3（2026-10-10）</b>：原来还有第三条 {@code MerchantHaul.TOOL_BURN_ACCOUNT}（{@code
   * market-merchant-haul} = 跑商"每趟烧 1,000 毫工具"的磨损账）—— 该族已退役：工具消耗单套化到 {@code trade} 产业的 周期投入（{@code
   * cycleInputPerUnit}），它走既有生产投入的现扣与损耗落点，不再有跑商私有的损耗账。
   */
  public static final IndustryId SERVICE_CONSUMED_ACCOUNT = new IndustryId("market-haul-service");

  /**
   * ★★★ <b>A5（2026-10-10）：周期边界"未卖出的运输服务作废"记进哪个损耗账</b>（{@code ProductionLedger.Accumulator.addLoss}
   * 的键）。
   *
   * <pre>
   * 触发点 = 产出运输服务的那个 {@code trade} unit 的**周期关账**（{@code progressed >= industry.cycleDays()}），
   *          在**本周期新产出入账之前**：上一周期没被卖掉的服务货当场作废（账户减 + 本损耗账加，Σ余额 + losses 守恒）。
   * 作用域 = ★ A7 起 = **任何持有 {@code haul} 的家户**（会话里全部已登记家户的 haul 键；清扫顺序 = 家户 id 规范串升序）——
   *          A5 原来是"关账 unit 的账户主体家户"（{@code HouseholdRouting.subjectOf(...).all()}），A6 实测到
   *          "货已随实物还债转出产它的家户"这一逃逸口 ⇒ 扩域。别的商品一个字节都不动。
   * </pre>
   *
   * <p>★★ <b>为什么必须与 {@link #SERVICE_CONSUMED_ACCOUNT} 分开</b>：两者是两件事，读数不许混 —— 前者（{@code
   * market-haul-service}）是"**卖出去**的那一份在成交那一刻被消耗"（有对价、有买方）， 本项（{@code
   * market-haul-service-expired}）是"**没卖出去**的那一份在周期边界作废"（无对价、无买方）。 合并成一个键会让"到底卖了多少、烂了多少"在账上再也分不开（用户
   * 2026-10-10 裁定的"不可储存转卖"正是后者）。
   *
   * <p>★★ <b>为什么这不是"静默销毁"（I-H2）</b>：走的是既有的**非换手损耗唯一写口** {@code
   * EconomySettlement.consumeForLoss}（"账户减 + 损耗账加"在同一句话里做完）⇒ Σ家户余额 + Σ损耗账逐值守恒， 与货损（{@code
   * market-transport}）、服务成交消耗同一条守恒式。
   */
  public static final IndustryId SERVICE_EXPIRED_ACCOUNT =
      new IndustryId("market-haul-service-expired");

  /** 千分比口径（与 {@code CapacityDemand.PER_MILLE} / {@code Market.BID_PER_MILLE} 同值）。 */
  public static final long PER_MILLE = 1000L;

  /**
   * ★★ <b>具名归因：这一格是"服务货口径"却一件服务货都没有</b>（{@code MerchantCapacityPool.laneBlockedReason} 用）——
   * 跨格货单因此买不到运力、**该笔不成交**（设计书 §6 T-H2 的 fail-closed）。
   *
   * <p>★ 与池里既有的三支归因（{@code tool-short} / {@code out-of-derived-radius} / {@code
   * capacity-exhausted}）分开： 那三支说的是"提供了但给不了"，本支说的是"<b>根本没有服务可买</b>"—— 前者要补货/退单，后者要等下一轮产出。
   */
  public static final String NO_SERVICE_SUPPLY_REASON = "no-haul-service-in-shipping-hex";

  /**
   * ★★ <b>本格的服务市场成不成市</b>（= 本格市场给 {@code haul} 定过价）。
   *
   * <pre>
   * 市场不存在 / 没给 haul 定价  ⇒ false ⇒ **逐值退回改前**（运力池按劳动+工具、运费走既有 CARRIER_FEE 腿）
   * 给 haul 定了价（含明确 0 价）⇒ true  ⇒ 运费只走"服务商品成交"这一条（I-H5），运力 = 商家户手上的服务货
   * </pre>
   *
   * <p>★ 这是本批**缺省中性（I-H3）的结构性开关**：没有牌价 ⇒ 一行判据都不变；它同时是"未定价 ⇒ 不交易"这条既有 {@code Market.hasPrice}
   * 语义的直接复用（不另造一个开关维度）。
   */
  public static boolean pricedAt(Market market) {
    return market != null && market.hasPrice(HAUL_COMMODITY);
  }

  // ★ 派生需求量的算式**不在本类重写**：它就是既有的 {@link CapacityDemand#workMilliOf}（唯一拼写点），
  //   由 {@code CapacityDemandBook.record}（{@code :108-109} 的 demandWork/servedWork）与
  //   {@code MerchantCapacityPool.select}（{@code workConsumedBy}）两处读同一份算式 ⇒ 本类只引用、不复制。

  /**
   * ★★ <b>单位运费（毫计价货币 / 商品单位）</b>：{@code max(1, ⌈ 服务耗用‰ × 服务牌价 ÷ 1000 ⌉)} —— 算式骨架与既有 {@code
   * MarketSettlement.freightUnitMilli} 逐字同形，只把第三因子换成服务牌价（类注）。
   *
   * <p>★ <b>{@code 0} 牌价仍算 1 毫</b>：沿用既有语义（"免费服务"不等于"不需要结算"，末尾 {@code max(1, …)}）—— 与 {@code
   * freightUnitMilli} 的 {@code max(1, …)} 同款，逐条可核对。
   *
   * @param workPerGoodPerMille 本 lane 的服务耗用（‰；{@link CapacityDemand#workPerGoodPerMille}）
   * @param servicePriceMilli 本格运输服务的牌价（毫计价货币 / 商品单位服务）
   */
  public static long unitFreightMilli(long workPerGoodPerMille, long servicePriceMilli) {
    if (workPerGoodPerMille <= 0L) {
      throw new IllegalArgumentException("服务耗用系数必须为正: " + workPerGoodPerMille);
    }
    if (servicePriceMilli < 0L) {
      throw new IllegalArgumentException("服务牌价不得为负（0 = 明确免费服务）: " + servicePriceMilli);
    }
    // ★ 溢出口径与既有 freightUnitMilli 同款（那里也是 multiplyExact 直乘）：乘法越界 ⇒ 当场抛，
    //   绝不静默落一个"便宜"的数（"贵到买不起"与"算错了"必须分得开）。
    return Math.max(
        1L, ceilDiv(Math.multiplyExact(workPerGoodPerMille, servicePriceMilli), PER_MILLE));
  }

  private static long ceilDiv(long numerator, long denominator) {
    return -Math.floorDiv(-numerator, denominator);
  }
}
