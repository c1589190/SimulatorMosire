package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketOrderPlanner.safeAdd;
import static io.mosire.simos.economy.time.MarketRosterBook.ceilDiv;
import static io.mosire.simos.economy.time.MarketRosterBook.frozenGoodsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.frozenMoneyOf;
import static io.mosire.simos.economy.time.MarketRosterBook.setFrozenGoods;
import static io.mosire.simos.economy.time.MarketRosterBook.setFrozenMoney;
import static io.mosire.simos.economy.time.MarketRosterBook.spendableMoneyOf;
import static io.mosire.simos.economy.time.MarketRosterBook.stockOf;
import static io.mosire.simos.economy.time.MarketTradeMath.ceilDivPositive;
import static io.mosire.simos.economy.time.MarketTradeMath.safeMulDiv;

import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketFreezeBook {

  private MarketFreezeBook() {}

  // ── 冻结生命周期（M1.2 的写者）────────────────────────────────────────────────────────

  static void commitFreezes(MatchContext ctx) {
    // 卖：按 (seller, commodity) 轴的**当刻可卖上限**封顶后再冻结（绝对值 = 底值 + 轴累计承诺）。
    //   ★★ DEF-2b（2026-10-20）：S3 生成的卖单到 S7-A 冻结时可能已 stale —— 若每条卖单各自冻结全额，
    //     frozen 会大于当刻 stock，连冻结本身都非法（executeTrade 的 cap 只是第二道防线，救不了冻结读数）。
    //   ⇒ 同一轴先算 axisAvailable = max(0, stock − base)，再按 ctx.sells 的稳定插入序逐条分配：
    //     前一条吃掉的额度，后一条不能再冻结。正常 stock ≥ Σsellable 时逐值不变（分配额 == 请求额）。
    ctx.sellFrozenSums.clear();
    Map<String, Long> sellAxisRemaining = new LinkedHashMap<>();
    for (SellSlot sell : ctx.sells) {
      String axis = sellFrozenAxis(sell);
      long base = frozenGoodsOf(ctx.round, sell.seller, sell.order.commodity());
      sell.baseFrozenGoods = base;
      long axisAvailable =
          Math.max(0L, stockOf(ctx.round, sell.seller, sell.order.commodity()) - base);
      long axisRemaining = sellAxisRemaining.computeIfAbsent(axis, ignored -> axisAvailable);
      long frozenForSell = Math.min(sell.order.sellable(), axisRemaining);
      sell.frozenRemaining = frozenForSell;
      sellAxisRemaining.put(axis, axisRemaining - frozenForSell);
      if (frozenForSell == 0L) {
        // 这个轴已无可卖额度（或本单可卖量就是 0）⇒ 本单不可能成交；remaining 同步归零，不留"空转"余量。
        sell.remaining = 0L;
        sell.blocked = MarketUnfilledReason.ALL_RESERVED;
      } else if (sell.remaining > frozenForSell) {
        // 部分冻结：撮合最多只能卖掉已冻结的这一份，remaining 必须同步，否则撮合会反复尝试超额量。
        sell.remaining = frozenForSell;
      }
      ctx.sellFrozenSums.merge(axis, frozenForSell, Long::sum);
    }
    for (SellSlot sell : ctx.sells) {
      refreshSellFrozen(ctx, sell); // O(1)：读轴累计值（见 MatchContext.sellFrozenSums）
    }
    // 买：逐主体把"最多要花多少钱"按可花余额封顶后冻结（预算独立算，但承诺不许超过钱包）。
    //   ★★ W2（2026-10-11）：**这里不再写"因缺钱被拒"** —— 改动前 {@code total <= 0} 会把整组买槽标成
    //     {@link MarketUnfilledReason#NO_BUDGET} 且冻结 0（= 没钱就出局）。W2 起"没钱"不再是出局条件：
    //     未付价款记为债务 ⇒ 冻结**退化为"承诺/上限"**（把本单能拿出的现金预留出来，钱包是 0 就预留 0），
    //     它仍然是一次真实的状态写（冻结 ≤ 余额的会话不变式照旧），但**不再是成交的前置条件**。
    //   ★ P1.3：先把全部分组的 frozenRemaining 定完，再按 (buyer, currency) 轴一次写冻结表。
    ctx.buyFrozenSums.clear();
    Map<String, List<BuySlot>> byOwner = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      String key =
          buy.buyer.actor().kind() + ":" + buy.buyer.actor().id() + ":" + buy.currency.value();
      byOwner.computeIfAbsent(key, ignored -> new ArrayList<>()).add(buy);
    }
    for (List<BuySlot> group : byOwner.values()) {
      BuySlot first = group.get(0);
      long requested = 0L;
      for (BuySlot buy : group) {
        buy.baseFrozenMoney = frozenMoneyOf(ctx.round, buy.buyer, buy.currency);
        buy.requestedMoney = requestedMoneyOf(buy);
        requested = safeAdd(requested, buy.requestedMoney);
      }
      long spendable = spendableMoneyOf(ctx.round, first.buyer, first.currency);
      long total = Math.min(requested, spendable);
      if (total <= 0L || requested <= 0L) {
        for (BuySlot buy : group) {
          buy.frozenRemaining = 0L; // 钱包里没有可预留的现金 ⇒ 承诺额 0（仍可赊购，见 executeTrade）
        }
      } else if (total == requested) {
        for (BuySlot buy : group) {
          buy.frozenRemaining = buy.requestedMoney;
        }
      } else {
        long[] weights = new long[group.size()];
        for (int i = 0; i < group.size(); i++) {
          weights[i] = group.get(i).requestedMoney;
        }
        long[] parts = ProportionalSplit.byDenominator(total, weights, requested);
        for (int i = 0; i < group.size(); i++) {
          group.get(i).frozenRemaining = parts[i];
        }
      }
    }
    for (BuySlot buy : ctx.buys) {
      ctx.buyFrozenSums.merge(buyFrozenAxis(buy), buy.frozenRemaining, Long::sum);
    }
    for (BuySlot buy : ctx.buys) {
      refreshBuyFrozen(ctx, buy); // O(1)：读轴累计值
    }
  }

  /**
   * 一条买单选多最多会花掉的钱：{@code min(预算, ⌈数量 × 限价 ÷ 1000⌉)}（限价 = 货款上限，运费另计）。
   *
   * <p>★★ <b>P-T5b：整个算式在<b>买方支付币</b>上做</b> —— 冻结轴是 {@code (buyer, buy.currency)}，预算也是那个币的可花额 ⇒
   * 限价必须先折成同一个币（{@link BuySlot#limitInPayCurrency}，建槽位时已折一次）。★ 同币时它与 {@link
   * BuyOrder#maxLandedPrice()} 逐值相同 ⇒ 旧世界逐值不变（I-C2）。 ★ 限价折不出来（{@code -1}）⇒ 本单选不出可冻结额，按 0
   * 处理（订单生成侧本就不会产出这种单）。
   *
   * <p>★★ <b>D-032 RF1：价格接受者需求（{@link BuyOrder#maxLandedPrice()} 为 empty）</b>没有可乘的限价 ⇒ 冻结承诺只取
   * {@code budget + 借入补额}（现金预留读数）。这是冻结/承诺，不是数量门也不是成交门；实际付款腿仍读当刻可花额。
   */
  static long requestedMoneyOf(BuySlot buy) {
    if (buy.order.maxLandedPrice().isEmpty()) {
      return Math.addExact(buy.order.budget().amountMilli(), buy.borrowedBudgetTopUpMilli);
    }
    if (buy.limitInPayCurrency.isEmpty()) {
      return 0L;
    }
    long limitInPayCurrency = buy.limitInPayCurrency.getAsLong();
    if (limitInPayCurrency < 0L) {
      return 0L;
    }
    long goods =
        ceilDiv(buy.order.quantity() * limitInPayCurrency, EconomySettlement.MILLI_PER_GRAIN);
    // ★★ W5.5：订单预算 + **本槽从货币市场借到的那一笔**（见 BuySlot#borrowedBudgetTopUpMilli 的注：
    //   借钱按用户裁定的次序发生在下单之后，不补这一笔 ⇒ 借来的钱在冻结/现金腿上不可用）。
    return Math.min(
        Math.addExact(buy.order.budget().amountMilli(), buy.borrowedBudgetTopUpMilli), goods);
  }

  static void releaseAllFreezes(MatchContext ctx) {
    for (BuySlot buy : ctx.buys) {
      buy.frozenRemaining = 0L;
      setFrozenMoney(ctx.round, buy.buyer, buy.currency, buy.baseFrozenMoney);
    }
    for (SellSlot sell : ctx.sells) {
      sell.frozenRemaining = 0L;
      setFrozenGoods(ctx.round, sell.seller, sell.order.commodity(), sell.baseFrozenGoods);
    }
    ctx.buyFrozenSums.clear();
    ctx.sellFrozenSums.clear();
  }

  /**
   * ★ P1.3：卖单冻结的轴键 = {@code (seller actor, commodity)}。一个主体的全部卖单必在同一市场区（账户在唯一格），因此 worker
   * 的本区副本上的轴累计值与全局轴累计值一致。
   */
  static String sellFrozenAxis(SellSlot sell) {
    return sell.seller.actor().kind()
        + ":"
        + sell.seller.actor().id()
        + ":"
        + sell.order.commodity().value();
  }

  /** ★ P1.3：买单冻结的轴键 = {@code (buyer actor, currency)}（与 commitFreezes 的分组键逐字同源）。 */
  static String buyFrozenAxis(BuySlot buy) {
    return buy.buyer.actor().kind() + ":" + buy.buyer.actor().id() + ":" + buy.currency.value();
  }

  /** 刷新卖方商品冻结为绝对值：{@code base + 该轴剩余承诺}`（轴累计值由 commitFreezes/executeTrade 增量维护）。 */
  static void refreshSellFrozen(MatchContext ctx, SellSlot sell) {
    long sum = ctx.sellFrozenSums.getOrDefault(sellFrozenAxis(sell), 0L);
    setFrozenGoods(ctx.round, sell.seller, sell.order.commodity(), sell.baseFrozenGoods + sum);
  }

  /** 刷新买方货币冻结为绝对值：{@code base + 该轴剩余承诺}`（轴累计值由 commitFreezes/executeTrade 增量维护）。 */
  static void refreshBuyFrozen(MatchContext ctx, BuySlot buy) {
    long sum = ctx.buyFrozenSums.getOrDefault(buyFrozenAxis(buy), 0L);
    setFrozenMoney(ctx.round, buy.buyer, buy.currency, buy.baseFrozenMoney + sum);
  }

  /** 从卖冻结轴累计值里扣掉已释放量（成交释放冻结时调用；负值 = 轴账与槽位漂开 ⇒ fail-closed）。 */
  static void releaseSellFrozenSum(MatchContext ctx, SellSlot sell, long released) {
    if (released == 0L) {
      return;
    }
    String axis = sellFrozenAxis(sell);
    long sum = ctx.sellFrozenSums.getOrDefault(axis, 0L) - released;
    if (sum < 0L) {
      throw new IllegalStateException("卖冻结轴累计值被扣成负数（槽位与轴账漂开）：轴=" + axis + " 释放=" + released);
    }
    ctx.sellFrozenSums.put(axis, sum);
  }

  /** 从买冻结轴累计值里扣掉已释放量（成交释放冻结时调用；负值 = 轴账与槽位漂开 ⇒ fail-closed）。 */
  static void releaseBuyFrozenSum(MatchContext ctx, BuySlot buy, long released) {
    if (released == 0L) {
      return;
    }
    String axis = buyFrozenAxis(buy);
    long sum = ctx.buyFrozenSums.getOrDefault(axis, 0L) - released;
    if (sum < 0L) {
      throw new IllegalStateException("买冻结轴累计值被扣成负数（槽位与轴账漂开）：轴=" + axis + " 释放=" + released);
    }
    ctx.buyFrozenSums.put(axis, sum);
  }

  // ── W2 债务式清算：未付价款 ⇒ 对该卖方的债务（{@link #recordDeferredPaymentDebt}）──────────────

  /**
   * ★★ <b>W2：未付价款记债的条款</b>（利率 = 出厂借款利率常量、legacy 维；★ W5.5 起货币腿的借款合同**不再**用它 ——
   * 那种合同的利率是<b>逐区逐市场轮清算出的</b>动态利率，见 {@code MoneyMarketSettlement}。身份 ⇒ 同一 (债务人, 债权人, 标的, 条款)
   * 的欠款自动累加成一条未偿额 —— D-023 §3"不做分别核算"）。
   *
   * <p>★ W2-fix4 前它是市场信用撮合的条款（名字 {@code MARKET_CREDIT_TERMS}）；信用撮合退役后它只服务 {@link
   * #recordDeferredPaymentDebt}（{@code executeTrade} 的未付价款腿），**值一字未改**。
   */
  static final DebtTerms DEFERRED_PAYMENT_TERMS =
      DebtTerms.legacyDefault(EconomySettlement.BORROW_RATE_PER_MILLE_PER_CYCLE);

  //   ★★★ W2-fix4（2026-10-11）：这里原有 D-030 的市场信用撮合（{@code creditRound}：借钱买货 → 借实物）——
  //     **整段退役**（用户 0–8 第 6–7 条的债务式清算取代它：家户一股脑全拿下，付不出的部分直接欠卖方）。
  //     退役理由（真实 world 实测，见 W2-fix4 账本）：那两条腿**绕过**了现金腿的全部约束（运费/运力池/三层税），
  //     于是能在"现金撮合明确拒绝"的跨格车道上把货直接搬走 ⇒ 卖方的实物余量被一次性搬空、市场在 day 8 起
  //     再无供给（day 30 成交 0 笔）。⇒ 唯一信用通道 = 未付价款记债（本段下面的 {@link #recordDeferredPaymentDebt}）。
  /** 与 {@code executeTrade} 的货款算式同源：{@code ⌈数量 × 单价 ÷ 1000⌉}（毫计价货币）。 */
  static long paymentForQuantity(long quantity, long unitPrice) {
    if (quantity <= 0L || unitPrice <= 0L) {
      return 0L;
    }
    return ceilDivPositive(safeMulDiv(quantity, unitPrice, 1L), EconomySettlement.MILLI_PER_GRAIN);
  }
}
