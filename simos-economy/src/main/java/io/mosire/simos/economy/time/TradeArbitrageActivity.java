package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>§4.1/§4.3 的"套利"作为<u>一种家户活动</u></b>（本阶段 = 单币种、实物、<b>保留价 vs 市价</b>的价差）。
 *
 * <p>★★ <b>2026-10-08 基线实测后的口径（设计书 §7.0 / §12 阶段 1 第 3 条，控制方实测）</b>：
 *
 * <pre>
 * 单市场区内<b>逐格价表恒同价</b>（自适应定价每轮把同一份价表写进该区全部成员格，
 * MarketSettlement.java:1181 的 updated.put(member, new Market(...))）⇒ <b>不存在"买低格卖高格"</b>；
 * 跨格货物流向已由既有机制承担（卖方按 unitCostEstimate + freightPerUnit 升序被选中，MarketSettlement.java:2928/3000）。
 * </pre>
 *
 * <p>⇒ 本阶段套利的<b>正确维度</b>是：<b>家户自有价目表（保留价）</b>{@code vs}<b>市场价</b>的价差 —— 市场价低于该户保留价 ⇒ 买 / 多生产；高于 ⇒ 卖
 * / 少生产。这才是"按家户自己掌握的资源算"的落点（用户 §1.4）。
 *
 * <p>★★ <b>§4.1 的"换出比率"在本阶段长什么样</b>（边 = 拿计价货币换商品）：
 *
 * <pre>
 * ratio(货币 → 商品 c) 的收益 = 该户对 c 的保留价 − 市场成交价（本格参考价）    // 每 1 商品单位，单位 = 微 numeraire
 * </pre>
 *
 * 保留价就是 §3.3 的"对买入是愿付的上限"，市场价就是 {@code Market.priceOf}（区价 = 成交价，见 {@code Market} 类注
 * "成交仍按参考价"）。两者之差为正 ⇒ 这笔兑换创造价值 ⇒ 值得花时间去做。
 *
 * <p>★★ <b>为什么用"微"（1 毫 = 1000 微）而不直接用毫</b>：真档粮价 = <b>1 毫/单位</b>，而保留价的压力项 （±{@link
 * HouseholdValuationBook#NECESSITY_PREMIUM_PER_MILLE}‰ … −{@link
 * HouseholdValuationBook#SATURATION_DISCOUNT_PER_MILLE}‰）在毫网格上会被整数除法<b>抹平</b> （{@code 1 +
 * 1×500÷1000 = 1} ⇒ 价差恒 0 ⇒ 套利永远是死分支）。故本类内部的价差算式一律在<b>微</b>刻度上做， 只在对外契约（{@link
 * DebtValuation.HouseholdPriceTable}）处按毫回答 —— 两处同源（{@code 微 ÷ 1000}）。
 *
 * <p>★★ <b>四个硬边界（缺一即"套利吃光一切"）</b>：
 *
 * <ol>
 *   <li><b>生活保留优先（I15）</b>：买盘只是<b>在既有买目标之上加量</b>，绝不减少生活保留/需求目标；本类不碰 {@code MarketSettlement} 的
 *       {@code life}/{@code demandParts} 算式。
 *   <li><b>生产投入不被侵占（I14）</b>：本活动的快照取"当日劳动分配时刻"的可动余额，而默认预设 （{@code
 *       EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION = true}）下周期性投入<u>已经扣走</u> ⇒
 *       套利的营运库存里本来就没有那批料；此外本活动只<b>加买</b>，从不加卖。
 *   <li><b>吃深度</b>：① 机会上限 = 该户 {@value HouseholdValuationBook#HOLD_DAYS} 天目标保有量的 {@value
 *       #OPPORTUNITY_CAP_PER_MILLE}‰；② 现金上限 = 可动计价货币的 {@value #MONEY_CAP_PER_MILLE}‰； ③ 价格冲击上限 =
 *       自适应定价系数 {@value #DEPTH_IMPACT_PER_MILLE}‰（下一轮价格上行会侵蚀价差）。
 *   <li><b>每户每轮至多一次</b>（§4.1 第 ④ 步）：{@link #evaluate} 只返回<b>收益最大</b>的那一个商品。
 * </ol>
 *
 * <p>★★ <b>收益率的归一化（§4.3.3 的"最紧的那一项"，实现方必须写明）</b>：本类用<b>朴素版 + 劳动归一</b> —— 净收益 ÷ 该活动消耗的劳动（与既有 {@code
 * LaborQueueBook} 的排序键<u>同一把尺</u>： {@code ⌊net × 1_000_000 ÷
 * labor⌋}）。理由：本活动的现金与库存上限已在<b>规模</b>里封死（不是"要多少给多少"），
 * 故"每单位劳动能赚多少"就是与生产可比的那把尺；若按"货币"归一，两个活动的分母不同就不可比。
 *
 * <p>★ <b>边际递减</b>：{@link #depthImpactMicro} 按选定量 {@code q} 摊薄价格冲击（{@code q} 越大，平均每单位的 价差越小）——
 * 因此"同一个活动被分到更多资源时收益率下降"这条要求成立。 ★ 如实边界：市场<u>订单簿深度</u>在此时不可见（订单还没生成），故价格冲击用自适应系数 α 作上界；
 * 真正让"套利吃不满"的是上面三个上限与下一轮的价格上行（E1 的"吃深度"）。
 *
 * <p>★★ <b>本阶段只评估"买入"方向（HOLD 方向为何不评估，给出证明而不是"没做"）</b>：市场现有的卖出口径已经是 {@code sellable = max(0, 库存 − 冻结
 * − 必要投入 − 生活保留 − 需求目标)}，而套利<b>不得</b>侵占生活保留（I15） 与必要投入批次（I14）⇒ 套利能"留货不卖"的量只可能落在 {@code [生活保留, 生活保留
 * + 必要投入 + 需求目标]} 这一段里，而那一段按定义已经在 `sellable` 之外 ⇒ 任何 HOLD
 * 量都只能从"本来就不会卖的部分"里扣，等价于不产生任何变更。故本阶段<b>不评估</b> HOLD （方向词与落账路径保留：阶段 2 的外汇/跨区价差需要它）。{@link
 * HouseholdActivity.Direction#HOLD} 因此在本阶段 只出现在契约与日志词表里。
 */
final class TradeArbitrageActivity implements HouseholdActivity {

  /**
   * ★★ <b>套利活动的稳定 id</b>（{@code HouseholdLaborCommitment.activity} 的值）。
   *
   * <p>★ <b>为什么不能以 {@code "unit-"} 开头</b>：{@code EconomyData} 的构造期守卫把"以 {@code unit-} 开头但 查不到
   * unit"的劳动配额判为悬空引用（具名抛）。本 id 是"非 unit 的活动词"，与"自由家户劳动"同待遇： 合法、进守恒与读口、不喂任何生产 unit。
   */
  static final String ACTIVITY_ID = "activity:arbitrage";

  /**
   * ★★ <b>本活动的"活动键"（{@code Offer.unitId} / 排序 tie-break / 配额表 activity）</b>： {@code
   * activity:arbitrage:<家户 id>}。
   *
   * <p>★ <b>为什么必须逐户唯一而不是一个全局常量</b>：既有的 {@code LaborQueueSettlement} 阶段 2 按 {@code Offer.unitId} 做"逐
   * unit 全局封顶"（同一 unit 被多户共享时要按想要量比例缩）。若全部家户共用同一个活动键， 它们会被误当成<b>一条共享的生产活动</b> ⇒
   * 全体套利劳动被"某一户的可吸收量"封顶（静默错）。 逐户一个键 ⇒ 每个家户的活动各自封顶，语义与"每户每轮至多一次"（§4.1 第 ④ 步）一致。
   *
   * <p>★ <b>不以 {@code unit-} 开头</b>：见 {@link #ACTIVITY_ID} 的注释（{@code EconomyData} 的悬空引用守卫）。
   */
  static String activityKeyOf(HouseholdId household) {
    Objects.requireNonNull(household, "activityKeyOf 的家户不得为 null");
    return ACTIVITY_ID + ':' + household.value();
  }

  /** 这个 activity 词是不是本活动的（读口/守卫用；前缀判据的唯一拼写点）。 */
  static boolean isArbitrageActivity(String activity) {
    return activity != null
        && (activity.equals(ACTIVITY_ID) || activity.startsWith(ACTIVITY_ID + ':'));
  }

  /** 1 毫 = 1000 微（内部价差刻度；见类注）。 */
  static final long MICRO_PER_MILLI = 1000L;

  /**
   * ★ <b>交易劳动（毫小时 / 商品单位）</b>：{@code 1} —— 每交易 <b>1 商品单位</b>（= 1000 毫商品）花 1 毫小时。
   *
   * <p>★ <b>标定依据（照本仓"标定值要写来源"的纪律）</b>：务农的每单位劳动产出由配方给出 —— 每亩 143 毫小时产 34 粮（{@code
   * EconomySeeder.LABOR_MILLI_PER_MU ÷ GRAIN_OUTPUT_PER_MU}） ⇒ 每粮约 <b>4.2 毫小时</b>。交易 1 粮花 1 毫小时 ⇒
   * <b>约为直接务农的 1/4</b>，与用户 §1.5 的 「交易套利花费的劳动力在封建时期不少，但是相对直接务农还是少了很多」同侧。 ★ 经济模块**不许** import app 的
   * {@code EconomySeeder}（模块边界），故这里写的是同一条标定的<b>结论值</b>。
   */
  static final long TRADE_LABOR_MILLI_PER_GOOD_UNIT = 1L;

  /**
   * ★ <b>单轮机会上限（‰ 的 30 天目标保有量）</b>：{@code 250} ⇒ 一户一轮最多为自己目标保有量的 25% 吃货。
   *
   * <p>★ 依据：E1 要求"价差收窄，且<b>不是被某户一次吃光后长期不动</b>" ⇒ 单轮上限必须显著小于全部需求； 同时要大到"看得见效果"。
   */
  static final long OPPORTUNITY_CAP_PER_MILLE = 250L;

  /**
   * ★ <b>单轮现金上限（‰ 的可动计价货币）</b>：{@code 200} ⇒ 一户一轮最多动用两成现金。
   *
   * <p>★ 依据：现金还要留给生产投入的市价采购与<b>债务偿还</b>（真档基线 tick210→360 债务已飙升 6,171 →
   * 100,529,398）——套利是**投机**用途，不许把还债的钱吃干。买盘在订单生成里也排在<u>最后优先级</u> （只吃剩余现金，见 {@code
   * MarketSettlement.ordersFor}）。
   */
  static final long MONEY_CAP_PER_MILLE = 200L;

  /**
   * ★ <b>最小价差门槛（‰ 的市价）</b>：{@code 100} ⇒ 保留价必须比市价高至少 10% 才算"有套利机会"。
   *
   * <p>★ 依据两条：① 毫网格上"1 毫的价差"在真档（粮价 = 1）就是 100% 的价差，不设门槛会让噪声当信号； ② 买方限价 {@code ask} 本身就在参考价之上约
   * 10‰（{@code Market.ASK_PER_MILLE = 1010}）⇒ 门槛低于它时， 家户可能在"限价高于自己保留价"的区间里下单，那笔兑换对它就<b>不</b>创造价值（与
   * §3.3 的保留价语义矛盾）。
   */
  static final long MIN_EDGE_PER_MILLE = 100L;

  /**
   * ★ <b>价格冲击上限（‰；与 {@code MarketSettlement.MARKET_ADAPTIVE_ALPHA_PER_MILLE} 同值同尺）</b>： {@code 50}
   * ⇒ 一轮内自家买盘对价格的平均冲击最多 5%。
   *
   * <p>★ 依据：自适应定价每轮按供需把价最多挪 α = 5%；本活动用同一个系数当"自己这一口吃下去会贵多少"的上界。
   * 两处必须是同一个数（否则"我算的冲击"与"价格真的怎么动"会各说各话）。
   */
  static final long DEPTH_IMPACT_PER_MILLE = 50L;

  /**
   * 评估出来的一个机会（逐项都是当轮快照的纯函数结果；字段只读）。
   *
   * <p>★★ <b>量纲表（2026-10-08 诊断缺陷修复后：字段名 = 真实量纲，不再靠"Milli"后缀蒙）</b>：
   *
   * <pre>
   * quantityMilli        毫商品
   * laborMilli           毫小时          （状态/配额用的整数刻度；≥ 1）
   * laborMicro           微小时          （收益率分母用的精确刻度；= 毫 × 1000，不 floor）
   * reservationMicro     **微** numeraire / 商品单位   （1 毫 = 1000 微）
   * marketMicro          **微** numeraire / 商品单位
   * edgeMicro            **微** numeraire / 商品单位   （价格冲击摊薄后的**平均**单位价差）
   * netMicro             微 numeraire
   * netPerLaborScaled    微 numeraire ÷ 微小时 × 1e6   （与 LaborQueueBook 同一把尺）
   * </pre>
   *
   * <p>★ <b>为什么价格一律用微</b>：真档粮价 = 1 毫，而保留价/价差常在毫的分数上（1.5 毫 / 0.49 毫）； 毫刻度的取数会把它们显示成 1 / 1（甚至
   * 0），日志因此**看起来像零价差** —— 那正是本次修的缺陷。
   */
  record Opportunity(
      HouseholdId household,
      HouseholdActivity.Direction direction,
      CommodityId commodity,
      long quantityMilli,
      long reservationMicro,
      long marketMicro,
      long edgeMicro,
      long laborMilli,
      long laborMicro,
      long netMicro,
      long netPerLaborScaled,
      long shortfallPerMille,
      long saturationPerMille) {

    Opportunity {
      Objects.requireNonNull(household, "Opportunity.household 不得为 null");
      Objects.requireNonNull(direction, "Opportunity.direction 不得为 null");
      Objects.requireNonNull(commodity, "Opportunity.commodity 不得为 null");
      if (quantityMilli <= 0L || laborMilli <= 0L || laborMicro <= 0L || edgeMicro <= 0L) {
        throw new IllegalArgumentException(
            "Opportunity 的量/劳动（毫与微两刻度）/价差必须 > 0: "
                + quantityMilli
                + "/"
                + laborMilli
                + "/"
                + laborMicro
                + "/"
                + edgeMicro);
      }
    }

    /**
     * ★ <b>毫刻度的市价</b>（毫 numeraire / 商品单位；= {@code marketMicro ÷ 1000}）。
     *
     * <p>★★ <b>它是整除、无地板、无损失</b>：{@code marketMicro} 的构造处恒为"本格参考价（毫，≥ 1）× 1000" （见 {@link #evaluate}
     * 的 {@code baseMicro}），故除以 {@value #MICRO_PER_MILLI} 不丢余数。 ★ 需要"至少
     * 1"的调用点因此不需要地板——<b>地板是取数里最会骗人的东西</b>（它把"0.6 毫"显示成"1 毫"， 让 360 tick 读数看起来"有价差"）。
     */
    long marketPriceMilli() {
      return marketMicro / MICRO_PER_MILLI;
    }

    /**
     * ★★ <b>净收益（毫 numeraire）</b>（= {@code netMicro ÷ 1000}）。
     *
     * <p>★ <b>无地板、且不必有</b>：{@link #evaluate} 在构造 {@code Opportunity} 之前已具名跳过 {@code netMilli <=
     * 0}（{@code reason = "NO_EDGE"} 一条不落）⇒ 走到这里的 {@code netMicro ≥ 1000} ⇒ 本除法恒 ≥ 1。<b>去掉 {@code
     * Math.max(1, …)} 不是放松约束，而是把约束留在判据里</b> （判据在 evaluate，取数只做换算）。
     */
    long netMilli() {
      return netMicro / MICRO_PER_MILLI;
    }
  }

  /** 一次评估的结果：有机会就是活动本身，没有就是<b>具名 reason</b>（绝不静默）。 */
  record Evaluation(Optional<TradeArbitrageActivity> activity, String reason) {

    Evaluation {
      Objects.requireNonNull(activity, "Evaluation.activity 不得为 null（没有就给 Optional.empty()）");
      Objects.requireNonNull(reason, "Evaluation.reason 不得为 null（没有就写 CLEAR）");
    }

    static Evaluation hit(TradeArbitrageActivity activity) {
      return new Evaluation(Optional.of(activity), "CLEAR");
    }

    static Evaluation none(String reason) {
      return new Evaluation(Optional.empty(), reason);
    }

    boolean hasOpportunity() {
      return activity.isPresent();
    }
  }

  private final HouseholdResourceSnapshot snapshot;
  private final Opportunity opportunity;

  private TradeArbitrageActivity(HouseholdResourceSnapshot snapshot, Opportunity opportunity) {
    this.snapshot = snapshot;
    this.opportunity = opportunity;
  }

  /**
   * ★★ <b>§4.3.1 第 ② 步：用同一份资源快照给本户算"套利"这个活动的收益率与规模上限</b>（纯函数；不改任何状态）。
   *
   * <pre>
   * 对本户居住格价表里每一个"有定价"的商品 c（按商品 id 升序；禁 HashMap 迭代序）：
   *   baseMicro   = market.priceOf(c) × 1000                       // 区价（成交价）→ 微
   *   rMicro      = 家户价目表的保留价（微；见 HouseholdValuationBook.reservationMicroOf）
   *   edge        = rMicro − baseMicro                             // 每 1 商品单位
   *   edge ≥ baseMicro × MIN_EDGE_PER_MILLE ÷ 1000 才成立（最小价差门槛）
   *   target      = row.expectedNeedMilli(c, HOLD_DAYS)             // 30 天目标保有量（Social 权威的当日物化视图）
   *   qMax        = min( target × OPPORTUNITY_CAP_PER_MILLE ÷ 1000,
   *                      (可动计价货币 × MONEY_CAP_PER_MILLE ÷ 1000) × 1000 ÷ base )   // 毫商品
   *   qDepth      = 使"平均每单位价差 ≥ 0"的最大 q（价格冲击 ≤ edge；见 depthQuantityLimit）
   *   q           = min(qMax, qDepth)；q ≤ 0 ⇒ 该商品没有机会（具名 reason，不静默）
   *   labor       = max(1, ⌊q × TRADE_LABOR_MILLI_PER_GOOD_UNIT ÷ 1000⌋)（毫小时）
   *   net         = q × 平均价差 ÷ 1000（微 → ÷1000 得毫）
   *   yield       = ⌊netMilli × 1_000_000 ÷ labor⌋                  // 与 LaborQueueBook 同一把尺
   * 取 yield 最大的那一个商品 ⇒ 一个 Opportunity（每户每轮至多一次，§4.1 第 ④ 步）
   * </pre>
   *
   * <p>★ <b>没有机会时的具名 reason</b>（全部进日志，绝不静默）：{@code NO_MARKET}（本户无市场）、 {@code
   * NO_PRICED_COMMODITY}（本格没有任何有价商品）、{@code NO_NEED}（本户对全部有价商品都没有目标保有量 ⇒ 没有"缺"可言）、{@code
   * NO_EDGE}（有目标但价差都在门槛之下）、{@code NO_FUNDS}（有价差但可动现金为 0）、 {@code ZERO_PRICE:<商品>}（明确 0 价 ⇒
   * 免费商品无价差可言）、{@code UNPRICED:<商品>}（家户表缺项且市场默认也缺 ⇒ N7 具名跳过，不猜价）。
   *
   * @param household 家户
   * @param row 该户人口/需求行（{@code expectedNeedMilli} 是"目标保有量"的唯一来源）
   * @param prices 逐 tick 派生的家户价目表（保留价）
   * @param snapshot 本 tick 的资源快照（§4.3.5：排序与规模都用这一份）
   */
  static Evaluation evaluate(
      HouseholdId household,
      HouseholdEconomy row,
      HouseholdValuationBook prices,
      HouseholdResourceSnapshot snapshot) {
    Objects.requireNonNull(household, "evaluate 的家户不得为 null");
    Objects.requireNonNull(row, "evaluate 的家户行不得为 null");
    Objects.requireNonNull(prices, "evaluate 的家户价目表不得为 null");
    Objects.requireNonNull(snapshot, "evaluate 的资源快照不得为 null");
    Market market = snapshot.market();
    if (market == null) {
      return Evaluation.none("NO_MARKET");
    }
    CurrencyId numeraire = market.numeraire();
    long moneyBudget = Math.multiplyExact(snapshot.moneyOf(numeraire), MONEY_CAP_PER_MILLE) / 1000L;
    Opportunity best = null;
    String bestReason = "NO_PRICED_COMMODITY";
    for (CommodityId commodity : snapshot.pricedCommodities()) {
      if (!market.hasPrice(commodity)) {
        continue;
      }
      long baseMicro = Math.multiplyExact(market.priceOf(commodity), MICRO_PER_MILLI);
      if (baseMicro <= 0L) {
        bestReason = "ZERO_PRICE:" + commodity.value(); // 明确 0 价（免费）：无价差可言，具名跳过
        continue;
      }
      long target = row.expectedNeedMilli(commodity, HouseholdValuationBook.HOLD_DAYS);
      if (target <= 0L) {
        if (bestReason.startsWith("NO_PRICED")) {
          bestReason = "NO_NEED";
        }
        continue; // 本户对这一种没有"缺" ⇒ 保留价没有向上压力 ⇒ 不构成机会（I15 的另一面）
      }
      long reservationMicro = prices.reservationMicroOf(household, commodity);
      if (reservationMicro <= 0L) {
        bestReason = "UNPRICED:" + commodity.value(); // N7：家户表缺项且市场默认也缺 ⇒ 具名跳过，不猜价
        continue;
      }
      long edgeMicro = reservationMicro - baseMicro;
      long minEdgeMicro = Math.multiplyExact(baseMicro, MIN_EDGE_PER_MILLE) / 1000L;
      if (edgeMicro < minEdgeMicro) {
        if (!bestReason.startsWith("ZERO_PRICE")
            && !bestReason.startsWith("UNPRICED")
            && !bestReason.startsWith("NO_FUNDS")) {
          bestReason = "NO_EDGE";
        }
        continue;
      }
      long opportunityCap = Math.multiplyExact(target, OPPORTUNITY_CAP_PER_MILLE) / 1000L;
      // ★★ 现金买得起多少**毫商品**（量纲唯一拼写点，别写错 1000 倍）：
      //     现金预算 = moneyBudget 毫钱；价格 = base 毫钱 / **商品单位**（1 商品单位 = 1000 毫商品）
      //     ⇒ 买得起 = moneyBudget × 1000（毫商品/商品单位）÷ base（毫钱/商品单位）   [毫商品]
      long cashAffordable =
          moneyBudget <= 0L
              ? 0L
              : Math.multiplyExact(moneyBudget, EconomySettlement.MILLI_PER_GRAIN)
                  / (baseMicro / MICRO_PER_MILLI);
      long quantity =
          Math.min(
              Math.min(opportunityCap, cashAffordable),
              depthQuantityLimit(edgeMicro, baseMicro, target));
      if (quantity <= 0L) {
        bestReason = moneyBudget <= 0L ? "NO_FUNDS" : "NO_DEPTH";
        continue;
      }
      long laborMilli = laborFor(quantity);
      long laborMicro = laborMicroFor(quantity);
      long averageEdgeMicro = edgeMicro - depthImpactMicro(quantity, baseMicro, target);
      if (averageEdgeMicro <= 0L) {
        bestReason = "NO_DEPTH";
        continue;
      }
      long netMicro =
          Math.multiplyExact(quantity, averageEdgeMicro) / EconomySettlement.MILLI_PER_GRAIN;
      long netMilli = netMicro / MICRO_PER_MILLI;
      if (netMilli <= 0L) {
        continue; // 净收益在毫刻度上为 0 ⇒ 不值得占劳动（不静默按 0 执行）
      }
      long stock = snapshot.goodsOf(commodity);
      Opportunity candidate =
          new Opportunity(
              household,
              HouseholdActivity.Direction.BUY,
              commodity,
              quantity,
              reservationMicro,
              baseMicro,
              averageEdgeMicro,
              laborMilli,
              laborMicro,
              netMicro,
              scaledNetPerLaborMicro(netMicro, laborMicro),
              perMilleOf(target, target - stock),
              perMilleOf(target, stock - target));
      // ★★ 选哪一个商品：<b>绝对收益最大</b>（§4.1 第 ② 步原文"取收益最大的那一环"），同收益按商品 id 升序。
      //   ★ 为什么不是"收益率最大"：收益率（每单位劳动）是**队列排序**用的（§4.3.1 ③），而"做哪一笔"看的是
      //     这一笔一共赚多少。两者混用会让家户去做"高价商品上的小买卖"（收益率看起来高、总量可忽略）而放弃
      //     "粮食上的大买卖" —— 一次性数值探针实测：布（价 5 毫、30 天目标 3000 毫）净收益 1 毫钱 vs
      //     粮（价 1 毫、30 天目标 42 万毫）净收益 52 毫钱；按收益率选会选布，整套机制在 360 tick 读数上等于没做。
      if (best == null
          || candidate.netMicro() > best.netMicro()
          || (candidate.netMicro() == best.netMicro()
              && candidate.commodity().value().compareTo(best.commodity().value()) < 0)) {
        best = candidate;
      }
    }
    return best == null
        ? Evaluation.none(bestReason)
        : Evaluation.hit(new TradeArbitrageActivity(snapshot, best));
  }

  /** 这个活动命中的机会（只读；日志/读数用）。 */
  Opportunity opportunity() {
    return opportunity;
  }

  /** 该活动在本 tick 的资源快照（§4.3.5 的"同一份"）。 */
  HouseholdResourceSnapshot snapshot() {
    return snapshot;
  }

  // ── HouseholdActivity 契约 ──────────────────────────────────────────────────────────────

  @Override
  public String activityId() {
    return ACTIVITY_ID;
  }

  @Override
  public Kind kind() {
    return Kind.ARBITRAGE;
  }

  @Override
  public long yieldScaled(HouseholdResourceSnapshot ignored) {
    // ★ §4.3.5：收益率在 evaluate 那一刻按同一份快照算好并冻结 —— 排序期间资源不变，故不重算（也不允许重排）。
    return opportunity.netPerLaborScaled();
  }

  @Override
  public Need resourceNeed(HouseholdResourceSnapshot ignored) {
    // 量纲：量（毫商品）× 市价（毫 numeraire / **商品单位**）÷ 1000（毫商品/商品单位）= 毫 numeraire。
    // ★ marketPriceMilli() 是**整除无损**的毫刻度（见其 javadoc）⇒ 本式与改前逐值相同。
    long moneyMilli =
        Math.multiplyExact(opportunity.quantityMilli(), opportunity.marketPriceMilli())
            / EconomySettlement.MILLI_PER_GRAIN;
    return new Need(opportunity.laborMilli(), moneyMilli, Map.of());
  }

  @Override
  public Optional<Intent> execute(Allocation allocation) {
    Objects.requireNonNull(allocation, "TradeArbitrageActivity.execute 的分配结果不得为 null");
    if (allocation.isEmpty() || allocation.laborMilli() <= 0L) {
      return Optional.empty(); // 没分到劳动 ⇒ 这一轮这个家户不去市场（不猜、不静默按 0 成交）
    }
    long affordableByLabor =
        Math.multiplyExact(allocation.laborMilli(), EconomySettlement.MILLI_PER_GRAIN)
            / TRADE_LABOR_MILLI_PER_GOOD_UNIT;
    // 量纲：钱（毫 numeraire）× 1000（毫商品/商品单位）÷ 市价（毫 numeraire/商品单位）= 毫商品。
    // ★ 同上：marketPriceMilli() 整除无损 ⇒ 与改前逐值相同。
    long affordableByMoney =
        Math.multiplyExact(allocation.moneyMilli(), EconomySettlement.MILLI_PER_GRAIN)
            / opportunity.marketPriceMilli();
    long quantity =
        Math.min(opportunity.quantityMilli(), Math.min(affordableByLabor, affordableByMoney));
    if (quantity <= 0L) {
      return Optional.empty();
    }
    // ★★ 三个价格一律用**微**刻度原样交回（不再经"÷1000 + max(1,…)"那道会撒谎的转换）：
    //   微刻度下三者天然 > 0（保留价 = applyPressure 的 max(1,…) ≥ 1；市价 = 参考价×1000 ≥ 1000；
    //   平均价差在 evaluate 里已具名跳过 ≤ 0 的分支）⇒ Trade 的 "> 0" 不变式不需要任何地板就成立。
    return Optional.of(
        new Trade(
            ACTIVITY_ID,
            opportunity.direction(),
            opportunity.commodity(),
            quantity,
            opportunity.reservationMicro(),
            opportunity.marketMicro(),
            opportunity.edgeMicro()));
  }

  // ── 规模/收益算式（本类的唯一拼写点）──────────────────────────────────────────────────────

  /**
   * 价格冲击（微/商品单位）：{@code baseMicro × DEPTH_IMPACT_PER_MILLE × q ÷ (1000 × (q + D))}，{@code D =
   * target}。
   *
   * <p>★ {@code q → ∞} 时冲击 → {@code baseMicro × 5%}（上界）；{@code q = D} 时约一半。
   */
  private static long depthImpactMicro(long quantity, long baseMicro, long depthScale) {
    if (quantity <= 0L) {
      return 0L;
    }
    long scale = Math.max(1L, depthScale);
    long numerator =
        Math.multiplyExact(Math.multiplyExact(baseMicro, DEPTH_IMPACT_PER_MILLE), quantity);
    long denominator = Math.multiplyExact(1000L, Math.addExact(quantity, scale));
    return numerator / denominator;
  }

  /**
   * ★ <b>价格冲击不超过价差时的最大量</b>（整数闭式解；不做二分搜索 ⇒ 无迭代、无浮点、可复现）。
   *
   * <pre>
   * impact(q) ≤ edge
   * ⇒ baseMicro × IMPACT × q ≤ edge × 1000 × (q + D)
   * ⇒ q × (baseMicro × IMPACT − edge × 1000) ≤ edge × 1000 × D
   * 令 L = edge × 1000、R = baseMicro × IMPACT：
   *   R ≤ L        ⇒ 任意 q 都满足（冲击上界 ≤ 5% ≤ 价差）⇒ 返回 Long.MAX_VALUE
   *   R >  L        ⇒ q ≤ ⌊L × D ÷ (R − L)⌋
   * </pre>
   */
  private static long depthQuantityLimit(long edgeMicro, long baseMicro, long depthScale) {
    long left = Math.multiplyExact(edgeMicro, 1000L);
    long right = Math.multiplyExact(baseMicro, DEPTH_IMPACT_PER_MILLE);
    if (right <= left) {
      return Long.MAX_VALUE;
    }
    long scale = Math.max(1L, depthScale);
    return Math.multiplyExact(left, scale) / (right - left);
  }

  /** 交易量 → 劳动（毫小时；恒 ≥ 1 —— 有量的活动必须占至少 1 毫小时的排序分母）。 */
  private static long laborFor(long quantityMilliGoods) {
    long labor =
        Math.multiplyExact(quantityMilliGoods, TRADE_LABOR_MILLI_PER_GOOD_UNIT)
            / EconomySettlement.MILLI_PER_GRAIN;
    return Math.max(1L, labor);
  }

  /**
   * ★★ <b>收益率 = {@code netMicro × 1_000_000 ÷ laborMicro}</b>（分子分母都用<b>微</b>刻度 ⇒ 比例精确）。
   *
   * <p>★ <b>为什么不用 {@code netMilli ÷ laborMilli}</b>：状态里的劳动是<b>毫小时整数</b>且至少 1，而很小的 交易量（例如 750
   * 毫商品）折成毫小时会 floor 到 0 ⇒ 只能钳到 1 ⇒ 收益率被<b>凭空放大 1000 倍</b>
   * （一次性数值探针实测：布的小额机会因此压过粮食的大额机会）。微刻度的比值与毫刻度的真实比值<b>恒等</b> （{@code (net/1000)÷(lab/1000) ==
   * net÷lab}），故它只是"不丢精度"，不是第二把尺： 与 {@code LaborQueueBook.scaledNetPerLabor}（毫钱 ÷ 毫小时）同量纲、同刻度。
   */
  private static long scaledNetPerLaborMicro(long netMicro, long laborMicro) {
    return Math.multiplyExact(netMicro, ActivitySelector.PER_LABOR_SCALE)
        / Math.max(1L, laborMicro);
  }

  /**
   * 交易量（毫商品）→ 劳动（<b>微小时</b>；精确刻度，不 floor）。
   *
   * <p>★ 换算：每 1 <b>商品单位</b>（= 1000 毫商品）花 {@code TRADE_LABOR_MILLI_PER_GOOD_UNIT} <b>毫小时</b> ⇒ 每 1
   * 毫商品花 {@code RATE ÷ 1000} 毫小时 = {@code RATE} 微小时（1 毫小时 = 1000 微小时） ⇒ {@code laborMicro = q ×
   * RATE}。与 {@link #laborFor} 的毫刻度值同源（后者 = 本值 ÷ 1000，且至少 1）。
   */
  private static long laborMicroFor(long quantityMilliGoods) {
    return Math.multiplyExact(quantityMilliGoods, TRADE_LABOR_MILLI_PER_GOOD_UNIT);
  }

  /** {@code share × 1000 ÷ total} 夹到 {@code [0, 1000]}（{@code total ≤ 0} ⇒ 0）。 */
  private static long perMilleOf(long total, long share) {
    if (total <= 0L || share <= 0L) {
      return 0L;
    }
    return Math.min(1000L, Math.multiplyExact(share, 1000L) / total);
  }
}
