package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketRosterBook.spendableMoneyOf;
import static io.mosire.simos.economy.time.MarketSettlement.HouseholdMarketRole;
import static io.mosire.simos.economy.time.MarketTaxPlanner.taxesFor;

import io.mosire.simos.economy.api.id.CommodityId;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketTradeMath {

  private MarketTradeMath() {}

  /**
   * ★★ <b>W2：买方在一条路线上按“现金腿（名义运费）”折算的成交上限</b> —— <b>货款不参与</b>（它可以进债务）。
   *
   * <pre>
   * unitFreight = route == null ? 0 : buy.payAmountOf(route.freightPerUnit)      // 买方支付币
   * unitFreight &lt; 0   ⇒ 0        （说不出这种钱的价 ⇒ 付不起，与结算侧同一条 fail-closed）
   * unitFreight == 0  ⇒ 无上限   （区内同格 / 免运费 lane：现金腿为 0，钱不是任何约束）
   * 否则              ⇒ ⌊可付额 × 1000 ÷ unitFreight⌋
   * </pre>
   *
   * <p>★★ <b>它与 {@link #exactTotalCostAffordableUpTo} 的分工</b>：后者是"货款 + 运费 + 税 ≤ 可付"的<b>总价</b>上限 （W2
   * 起由 {@link #chargesAtMost} 承接）；本方法是 W2 的配给口径 —— 只把<b>现金腿</b>当约束。 税费不在这里折（它依赖卖方/方向，逐笔在 {@link
   * #exactChargeAffordableUpTo} 精确判），故本值是**上界**： 需求权重宁可高估，逐笔成交仍会被 {@link #pairUp} 的精确二分削平（与 {@link
   * #chargeAffordableQuantity} 的"保守少买"同一取向）。
   */
  static long chargeAffordableQuantity(MatchContext ctx, BuySlot buy, RouteContext route) {
    // ★★★ D-032 C7b2：活动运输计划路径允许运费赊账，故本上界不再按运费现金折回；真实税费现金约束由
    //   {@link #exactTaxAffordableUpTo} 在逐笔成交前精确判。
    if (ctx.transportRoundPlan != null) {
      return Long.MAX_VALUE;
    }
    long unitFreight = route == null ? 0L : buy.payAmountOf(route.freightPerUnit());
    if (unitFreight < 0L) {
      return 0L;
    }
    if (unitFreight == 0L) {
      return Long.MAX_VALUE; // 没有现金腿 ⇒ 数量只受需求/供给/运力/口岸约束
    }
    long money = payableMoneyOf(ctx, buy);
    if (money <= 0L) {
      return 0L;
    }
    return safeMulDiv(money, EconomySettlement.MILLI_PER_GRAIN, unitFreight);
  }

  /**
   * 这一张买单当前还能动用的钱：{@code min(可花 + 本单剩余冻结, 订单预算余额 + 本单剩余冻结)}。
   *
   * <p>★★ <b>W2 改义</b>：它不再回答"买得起多少货"（那个概念已随"未付价款进债务"消失），只回答 <b>这一笔能拿出多少现金</b>（现金腿 = 运费 + 税
   * 从这里扣，货款优先用它、不足的部分进债务）。
   *
   * <p>★★ <b>D-032 RF1：价格接受者需求没有预算门</b>（{@link
   * io.mosire.simos.economy.api.market.BuyOrder#maxLandedPrice()} 为 empty）——
   * 现金腿只取<b>当刻实际可花额</b>（{@code spendable + 本槽剩余冻结}），不再被下单当刻的 budget 快照封顶； 商品数量更不由它折回（货款不足进既有普通货币债）。
   */
  static long payableMoneyOf(MatchContext ctx, BuySlot buy) {
    long money = spendableMoneyOf(ctx.round, buy.buyer, buy.currency) + buy.frozenRemaining;
    if (buy.order.maxLandedPrice().isEmpty()) {
      return money;
    }
    // ★★ W5.5：预算余额 = 订单预算 + **本槽借到的那一笔**（同 BuySlot#borrowedBudgetTopUpMilli 的注）− 已花。
    long budgetLeft =
        Math.max(
            0L,
            Math.addExact(buy.order.budget().amountMilli(), buy.borrowedBudgetTopUpMilli)
                - buy.spentMilli);
    return Math.min(money, budgetLeft + buy.frozenRemaining);
  }

  /**
   * ★★ <b>W2：这一对买卖能不能"赊购"</b>（未付价款记为债务）—— 唯一拼写点。
   *
   * <pre>
   * ① 本轮有债务工作副本（{@code ctx.debts != null} ⇔ 市场轮注入了信用/债务能力）
   * ② 买方是家户（{@code DebtContract} 的一端只能是家户）
   * ③ 卖方是家户（同上；经营者单位的卖单不能成为债权人）
   * ④ 买方不是"只按授权下单"的国库户（R1：**明确授权 ≠ 加杠杆**，授权买盘只花自己的钱）
   * </pre>
   *
   * <p>★ 四条全真 ⇒ 数量不按货款封顶（只按现金腿封顶）、未付部分进债务；任一条假 ⇒ 退回旧口径 （{@code exactTotalCostAffordableUpTo}：货款 +
   * 运费 + 税 ≤ 可付），因为那种成交<b>没有地方记那笔欠款</b>。
   */
  static boolean deferralPossible(MatchContext ctx, BuySlot buy, SellSlot sell) {
    return ctx.debts != null
        && buy.buyer.household() != null
        && sell.seller.household() != null
        && ctx.round.householdMarketRole(buy.buyer.household())
            != HouseholdMarketRole.AUTHORIZATION_ONLY;
  }

  /**
   * ★★ <b>W2：旧口径的回退封顶</b>（没有赊购能力时用）：最大 {@code q ≤ upper} 使 {@code 货款(q) + 现金腿(q) ≤ cashBudget} ——
   * 与改动前的 {@code exactAffordableUpTo} 逐字同式 （同一个 {@link #chargesAtMost}，只是把货款也算进预算里）。
   *
   * <p>★ 单调性：货款、名义运费、从量/从价税都是数量 q 的非降函数 ⇒ 判据关于 q 单调，二分成立。
   */
  static long exactTotalCostAffordableUpTo(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long upper,
      long cashBudget,
      long unitPrice,
      RouteContext route) {
    if (upper <= 0L) {
      return 0L;
    }
    if (totalCostAtMost(ctx, buy, sell, commodity, upper, cashBudget, unitPrice, route)) {
      return upper;
    }
    long low = 0L;
    long high = upper;
    while (low < high) {
      long mid = low + (high - low + 1L) / 2L;
      if (totalCostAtMost(ctx, buy, sell, commodity, mid, cashBudget, unitPrice, route)) {
        low = mid;
      } else {
        high = mid - 1L;
      }
    }
    return low;
  }

  /** 一笔（货款 + 现金腿）是否 ≤ 现金：{@code 货款 ≤ cash && 现金腿 ≤ cash − 货款}（与 {@link #chargesAtMost} 同一算式）。 */
  static boolean totalCostAtMost(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long cashBudget,
      long unitPrice,
      RouteContext route) {
    long payment;
    try {
      payment =
          ceilDivPositive(
              Math.multiplyExact(quantity, unitPrice), EconomySettlement.MILLI_PER_GRAIN);
    } catch (ArithmeticException overflow) {
      return false;
    }
    if (payment > cashBudget) {
      return false;
    }
    return chargesAtMost(
        ctx, buy, sell, commodity, quantity, cashBudget - payment, unitPrice, route);
  }

  /**
   * ★★ <b>W2：这一笔的“现金腿”是否付得起</b> —— 现金腿 = <b>名义运费 + P-T1b 的三层税</b>；<b>货款不计</b> （它可以进债务，见 {@link
   * #executeTrade}）。
   *
   * <p>★★ <b>为什么运费/税仍必须是现金</b>：它们的收款人是<b>承运家户</b>与<b>政府国库户</b>（两条独立的钱腿）， 不是卖方 ——
   * "先消费、价款记债"这条通道只覆盖<b>卖方那一腿</b>。让它们也走债务就要为每个承运人/政府各开一条 债务，那超出 W2 的范围（且改变了 A2
   * 的服务成交口径）。故口径定为：<b>买不起货款照买（记债），付不起运费/税则不成交</b>。
   *
   * <p>★ <b>同格即时成交</b>（route == null）且无区内税 ⇒ 现金腿恒 0 ⇒ <b>结构上永不因缺钱被拒</b> （判据 W2-①
   * 的"现金为零仍拿得到货"正是这一支）。
   *
   * <p>★ 与 {@link #executeTrade} 走<b>同一个</b> {@code taxesFor} 与 {@code payAmountOf} ⇒
   * "判得起"与"真的扣"不可能漂开。
   */
  static boolean chargesAtMost(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long cashBudget,
      long buyerUnitPrice,
      RouteContext route) {
    if (quantity <= 0L) {
      return true;
    }
    long outlay = 0L;
    // ★★ P-T5b：运费腿按**买方支付币**折算（route.freightPerUnit 是本格计价币的单位运费）。
    //   ★ 说不出这种钱的价 ⇒ 付不起（false），绝不按 1:1 顶上。
    long unitFreight = route == null ? 0L : buy.payAmountOf(route.freightPerUnit());
    if (unitFreight < 0L) {
      return false;
    }
    if (unitFreight > 0L) {
      long freight;
      try {
        freight = ceilDivPositive(Math.multiplyExact(quantity, unitFreight), 1000L);
      } catch (ArithmeticException overflow) {
        return false;
      }
      try {
        outlay = Math.addExact(outlay, freight);
      } catch (ArithmeticException overflow) {
        return false;
      }
    }
    try {
      for (MarketTaxBook.Charge charge :
          taxesFor(ctx, buy, sell, commodity, quantity, buyerUnitPrice, inTransit(route), false)) {
        outlay = Math.addExact(outlay, charge.amountMilli());
      }
    } catch (ArithmeticException overflow) {
      return false;
    }
    return outlay <= cashBudget;
  }

  /**
   * ★★★ <b>D-032 C7b2：活动运输计划路径的现金腿判据 —— 只要求税费现金付得起</b>。
   *
   * <p>运费现金不足部分按本责任区口径进入普通 0‰ 货币债；因此配给不能因运费不足把成交量折回 0。 货款的现金封顶仍由 {@link #executeTrade}
   * 的“先税、再运费、最后货款”拆分处理；未付货款继续记卖方债。
   */
  static boolean taxesAtMost(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long cashBudget,
      long buyerUnitPrice,
      RouteContext route) {
    if (quantity <= 0L) {
      return true;
    }
    long outlay = 0L;
    try {
      for (MarketTaxBook.Charge charge :
          taxesFor(ctx, buy, sell, commodity, quantity, buyerUnitPrice, inTransit(route), false)) {
        outlay = Math.addExact(outlay, charge.amountMilli());
      }
    } catch (ArithmeticException overflow) {
      return false;
    }
    return outlay <= cashBudget;
  }

  /** C7b2：最大 {@code q} 使税费现金腿 ≤ cashBudget（运费可赊，不参与二分判据）。 */
  static long exactTaxAffordableUpTo(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long upper,
      long cashBudget,
      long unitPrice,
      RouteContext route) {
    if (upper <= 0L) {
      return 0L;
    }
    if (taxesAtMost(ctx, buy, sell, commodity, upper, cashBudget, unitPrice, route)) {
      return upper;
    }
    long low = 0L;
    long high = upper;
    while (low < high) {
      long mid = low + (high - low + 1L) / 2L;
      if (taxesAtMost(ctx, buy, sell, commodity, mid, cashBudget, unitPrice, route)) {
        low = mid;
      } else {
        high = mid - 1L;
      }
    }
    return low;
  }

  /**
   * ★★ <b>W2：把成交数量按“现金腿付得起”精确封顶</b>：最大 {@code q ≤ upper} 使 {@link #chargesAtMost}(q) 成立（{@code
   * cargo} 不封顶 —— 那部分进债务）。
   *
   * <p>★ <b>单调性</b>（二分的前提）：名义运费与从量/从价税都是数量 q 的非降函数 ⇒ {@code chargesAtMost} 关于 q 单调。 ★ 形状照 {@link
   * #exactTotalCostAffordableUpTo}（同一个二分骨架），只是判据换成"只算现金腿"。
   */
  static long exactChargeAffordableUpTo(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long upper,
      long cashBudget,
      long unitPrice,
      RouteContext route) {
    if (upper <= 0L) {
      return 0L;
    }
    if (chargesAtMost(ctx, buy, sell, commodity, upper, cashBudget, unitPrice, route)) {
      return upper;
    }
    long low = 0L;
    long high = upper;
    while (low < high) {
      long mid = low + (high - low + 1L) / 2L;
      if (chargesAtMost(ctx, buy, sell, commodity, mid, cashBudget, unitPrice, route)) {
        low = mid;
      } else {
        high = mid - 1L;
      }
    }
    return low;
  }

  /** 跨区在途判据（{@link #executeTrade} 与可负担判据的唯一拼写点）。 */
  static boolean inTransit(RouteContext route) {
    return route != null && !route.immediate();
  }

  /** 向下取整的 {@code value × multiplier ÷ divisor}；乘法溢出时保守回退成 `Long.MAX_VALUE`。 */
  static long safeMulDiv(long value, long multiplier, long divisor) {
    if (divisor <= 0L) {
      return Long.MAX_VALUE;
    }
    try {
      return Math.multiplyExact(value, multiplier) / divisor;
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /** 正整数的向上取整除法（不靠 {@code numerator + divisor - 1}，避免那一处溢出）。 */
  static long ceilDivPositive(long numerator, long divisor) {
    long quotient = numerator / divisor;
    return numerator % divisor == 0L ? quotient : quotient + 1L;
  }
}
