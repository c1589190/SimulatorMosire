package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>D-032 RA1b2c2：标量土地订单/成交的公共口径（唯一拼写点）。</b>
 *
 * <p>★★ <b>为什么单独一个簿</b>：土地订单与商品订单一同进入 {@code MarketRound}，但土地有三条与商品不同的结构性事实：
 *
 * <ol>
 *   <li><b>按 hex 的标量、可部分成交</b>：订单只带 {@code (hex, quantityMilliMu)}，不引用 {@code AssetShareId}/块 id；
 *   <li><b>无运费、不进库存</b>：价值公式是 {@code floor(quantityMilliMu × landPricePerMu ÷ 1000)}，产权腿只走 {@link
 *       io.mosire.simos.economy.land.LandLedgerState#transfer}（内部 {@link
 *       io.mosire.simos.economy.land.LandBook#apply}）；
 *   <li><b>区级价格</b>：同一市场区一个 {@code landPrice}（empty = 未定价，0 = 合法免费），禁止 hex 级价格表。
 * </ol>
 *
 * <p>★ 本类只做纯派生（不写状态）；成交写口在 {@link MarketTradeExecutor#executeLandTrade}，撮合在 {@link
 * MarketMatcher#matchAll} 内部的土地私有分支。
 */
final class MarketLandBook {

  private MarketLandBook() {}

  /** 土地价值量纲的分母：{@code 1 整亩 = 1000 千分亩}（与 {@code quantityMilliMu} 的定义同源）。 */
  static final long MILLI_PER_LAND_UNIT = 1000L;

  /**
   * ★★ <b>一块地的价值</b>（毫计价货币）：{@code floor(quantityMilliMu × pricePerMu ÷ 1000)}。
   *
   * <p>★ 0 价（合法免费）⇒ 0；负/零输入 ⇒ 0（不抛：定价为 0 是状态，不是故障）。★ 溢出走 {@link
   * DebtValuation#convertedContractUnits} 的饱和口径（与 P2e 的抵债估值同一个算式，不另写一套）。
   */
  static long landValueMilli(long quantityMilliMu, long pricePerMu) {
    if (quantityMilliMu <= 0L || pricePerMu <= 0L) {
      return 0L;
    }
    return DebtValuation.convertedContractUnits(quantityMilliMu, pricePerMu, MILLI_PER_LAND_UNIT);
  }

  /**
   * ★★ <b>现金不足时的最大可成交数量</b>：最大的 {@code q ≤ upper}，使 {@code floor(q × pricePerMu ÷ 1000) ≤
   * cashMilli}。
   *
   * <p>★ 0 价 ⇒ 数量不受现金限制；无现金且正价 ⇒ 0。整数反解走 {@link DebtValuation#convertedContractUnits}，不引入浮点。
   */
  static long cashAffordableQuantity(long cashMilli, long pricePerMu, long upper) {
    if (upper <= 0L) {
      return 0L;
    }
    if (pricePerMu <= 0L) {
      return upper;
    }
    if (cashMilli <= 0L) {
      return 0L;
    }
    long byCash = DebtValuation.convertedContractUnits(cashMilli, MILLI_PER_LAND_UNIT, pricePerMu);
    return Math.min(upper, byCash);
  }

  /** 标量土地取得的稳定键：{@code (hex, owner)}（本单唯一取得标记形状）。 */
  static LandHoldingKey acquisitionKey(HexCoord hex, HouseholdId owner) {
    return new LandHoldingKey(hex, owner);
  }

  /** 本格所属市场区的价目表；不在任何市场区 / 该区没有价表 ⇒ {@code null}（合法状态，不是故障）。 */
  static MarketTable tableFor(MatchContext ctx, HexCoord hex) {
    if (hex == null || ctx.topology == null || !ctx.topology.contains(hex)) {
      return null;
    }
    return ctx.marketTables.get(new MarketZoneId(ctx.topology.regionOf(hex).node().nodeId()));
  }

  /** 本格所属市场区 id；不在任何市场区 ⇒ {@code null}。 */
  static MarketZoneId zoneIdOf(MatchContext ctx, HexCoord hex) {
    if (hex == null || ctx.topology == null || !ctx.topology.contains(hex)) {
      return null;
    }
    return new MarketZoneId(ctx.topology.regionOf(hex).node().nodeId());
  }

  /** 主体 canonical 串（排序/日志的稳定破平；不参与任何判据的算术）。 */
  static String actorKey(ActorRef actor) {
    return actor.kind() + ":" + actor.id();
  }

  /**
   * ★★ <b>一条土地成交的只读读数</b>（本单的交账形状；不落盘、不进 ChangeSet）。
   *
   * <p>★ 与商品 {@code MarketReport.Fill} 分开：土地无运费/无税/不进库存，硬塞进商品成交会迫使读者区分"这条 fill 到底有没有货"。
   * 本记录只活在轮内（{@code MatchContext.landFills}）并随 INFO/TRACE 日志逐笔可核。★ <b>不含 shareId</b>：成交只按 hex +
   * 数量读。
   */
  record LandFill(
      long day,
      MarketZoneId zoneId,
      HexCoord hex,
      ActorRef buyer,
      ActorRef seller,
      long quantityMilliMu,
      long unitPricePerMu,
      CurrencyId currency,
      long paymentMilli,
      long paidCashMilli,
      long deferredMilli) {

    LandFill {
      Objects.requireNonNull(zoneId, "LandFill.zoneId 不得为 null");
      Objects.requireNonNull(hex, "LandFill.hex 不得为 null");
      Objects.requireNonNull(buyer, "LandFill.buyer 不得为 null");
      Objects.requireNonNull(seller, "LandFill.seller 不得为 null");
      Objects.requireNonNull(currency, "LandFill.currency 不得为 null");
      if (quantityMilliMu <= 0L) {
        throw new IllegalArgumentException("LandFill.quantityMilliMu 必须 > 0: " + quantityMilliMu);
      }
      if (unitPricePerMu < 0L || paymentMilli < 0L || paidCashMilli < 0L || deferredMilli < 0L) {
        throw new IllegalArgumentException(
            "LandFill 金额/价格不得为负: price="
                + unitPricePerMu
                + " payment="
                + paymentMilli
                + " cash="
                + paidCashMilli
                + " deferred="
                + deferredMilli);
      }
      if (paidCashMilli + deferredMilli != paymentMilli) {
        throw new IllegalArgumentException(
            "LandFill 现金 + 赊购必须等于成交价款: cash="
                + paidCashMilli
                + " deferred="
                + deferredMilli
                + " payment="
                + paymentMilli);
      }
    }

    /** 是否发生了赊购（现金不足、余款记为普通货币债务）。 */
    boolean deferred() {
      return deferredMilli > 0L;
    }
  }

  /**
   * ★★ <b>土地订单的具名拒因</b>（业务拒绝 = INFO，§一.9）：每一条都对应 {@link MarketMatcher#matchAll} 内部土地分支/{@link
   * MarketOrderPlanner} 里一个真实的跳过分支，不许"静默不成交"。
   */
  enum LandRejectReason {
    /** 该 hex 没有市场区价表。 */
    NO_MARKET,
    /** 该市场区没有土地价（empty）—— 不生成订单、不改价、不当免费。 */
    LAND_PRICE_EMPTY,
    /** 卖方在该 hex 没有可转移的标量持有（或已被前一笔消耗完）。 */
    NO_LAND_HOLDING,
    /** 卖单数量超过卖方该 hex 的当前持有量（不得伪造土地）。 */
    SELL_EXCEEDS_HOLDING,
    /** 买卖两端落在同一主体（不铸自转移）。 */
    SELF_TRADE,
    /** 支付/收款币不一致（钱腿只铸买方支付币，不做隐形折算）。 */
    CURRENCY_MISMATCH,
    /** 成交价越过买方限价 / 卖方限价。 */
    PRICE_LIMIT,
    /** 买方现金不足且没有可用的普通货币债通道。 */
    CASH_ONLY_UNPAYABLE,
    /** 挂单主体/对手方不是已登记活家户（账户主体只有家户）。 */
    ACTOR_NOT_HOUSEHOLD,
    /** 同一卖方同一 hex 出现了多张卖单且合计超出持有量（多余部分具名拒绝）。 */
    DUPLICATE_SELL,
    /** 该卖单本轮没有可成交的买方。 */
    NO_BUYER,
    /** 该买单本轮没有可成交的卖方。 */
    NO_SELLER
  }

  /**
   * ★ <b>具名业务拒绝（INFO）</b>—— 每条带 day / hex / 方向 / 主体 / 原因 / 判据数值；逐笔明细的补充在 DEBUG/TRACE。
   *
   * <p>★ 关闭日志不得影响撮合：先判 {@code MARKET.isInfoEnabled()}。
   */
  static void logRejected(
      MatchContext ctx, LandOrder order, LandRejectReason reason, String detail) {
    if (!MARKET.isInfoEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "LAND_ORDER_REJECTED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "hex",
                order == null ? "" : order.hex().q() + "," + order.hex().r(),
                "side",
                order == null ? "" : order.side().name(),
                "actor",
                order == null ? "" : order.actor().id(),
                "reason",
                reason.name(),
                "detail",
                detail));
  }

  /** ★ 轮级汇总的具名映射：{@code 原因 → 条数}（保序；无拒绝 ⇒ 空表）。 */
  static Map<String, Long> rejectCounts(Map<LandRejectReason, Long> counts) {
    Map<String, Long> ordered = new LinkedHashMap<>();
    for (Map.Entry<LandRejectReason, Long> entry : counts.entrySet()) {
      ordered.put(entry.getKey().name(), entry.getValue());
    }
    return ordered;
  }
}
