package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketFreezeBook.paymentForQuantity;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketTradeMath.inTransit;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.market.MarketTaxLayer;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.List;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketTaxPlanner {

  private MarketTaxPlanner() {}

  // ── P-T1b：三层税（真收款）────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P-T1b：本笔成交的三层税（唯一计税点）</b>—— 出口税（源区）/ 进口税（目的区）/ 区内市场税（本区）； 每层<b>只算一次</b>（I-C3），金额毫、币种 =
   * 买方支付币。
   *
   * <pre>
   * 跨区在途（inTransit） ：出口税 = 源区该商品 EXIT 税率；进口税 = 目的区该商品 ENTRY 税率
   * 区内即时/区内跨格     ：区内市场税 = 卖方的区级 MarketRegulation.tariffPerUnit（**既有钩子**，P-T1b 起真收）
   * </pre>
   *
   * <p>★★ <b>三层各自的收款方</b>：出口税进源区管辖政府国库、进口税进目的区管辖政府国库、区内税进本区管辖政府国库 （多政府共管一个区时按暴露边权重分摊，见 {@link
   * MarketTaxBook}）。
   *
   * <p>★★ <b>缺省语义中性（I-C2）</b>：未注入税表 / 该层税率为 0 / 该区没有政府 ⇒ 本方法返回空表 ⇒ {@code total = payment +
   * freight} 逐值等于改前。
   *
   * <p>★ 调用点<b>只有两处</b>：{@link #executeTrade}（真收）与 {@link #chargesAtMost}（现金腿的精确封顶）——
   * 同一个方法保证"判得起"与"真的扣"不可能漂开（那正是负余额/静默少买这类故障的来源）。
   */
  static List<MarketTaxBook.Charge> taxesFor(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long buyerUnitPrice,
      boolean inTransit,
      boolean logUnvalued) {
    PortTaxInput tax = ctx.round.portTax();
    if (!tax.isActive() || quantity <= 0L) {
      return List.of();
    }
    long payment = paymentForQuantity(quantity, buyerUnitPrice);
    List<MarketTaxBook.Charge> charges = new ArrayList<>(2);
    if (inTransit) {
      // ① 出口税：源区（卖方所在区）管辖政府收；② 进口税：目的区（买方所在区）管辖政府收。
      collectPortLayer(
          ctx,
          charges,
          buy,
          commodity,
          sell.regionId,
          PortDirection.EXIT,
          MarketTaxLayer.PORT_EXIT,
          quantity,
          payment,
          logUnvalued);
      collectPortLayer(
          ctx,
          charges,
          buy,
          commodity,
          buy.regionId,
          PortDirection.ENTRY,
          MarketTaxLayer.PORT_ENTRY,
          quantity,
          payment,
          logUnvalued);
      return charges;
    }
    // ③ 区内市场税：既有 MarketRegulation.tariffPerUnit（毫该区法定币 / 商品单位）—— 照旧只按卖方所在区取；
    //    收款方 = 该区的管辖政府（没有政府 ⇒ 收 0；与"无政府 ⇒ 该侧税 = 0"同源）。
    long inZonePerUnit = ctx.tariffPerUnitOf(sell.regionId, commodity);
    if (inZonePerUnit <= 0L) {
      return charges;
    }
    collectLayer(
        ctx,
        charges,
        buy,
        commodity,
        sell.regionId,
        MarketTaxLayer.IN_ZONE_MARKET,
        inZonePerUnit,
        0L,
        quantity,
        payment,
        logUnvalued);
    return charges;
  }

  /** 口岸某一侧的税（税率从注入表按 (区 × 商品 × 方向) 取）。 */
  static void collectPortLayer(
      MatchContext ctx,
      List<MarketTaxBook.Charge> charges,
      BuySlot buy,
      CommodityId commodity,
      String zoneId,
      PortDirection direction,
      MarketTaxLayer layer,
      long quantity,
      long payment,
      boolean logUnvalued) {
    PortTaxInput tax = ctx.round.portTax();
    collectLayer(
        ctx,
        charges,
        buy,
        commodity,
        zoneId,
        layer,
        tax.perUnitMilli(zoneId, commodity, direction),
        tax.adValoremPerMille(zoneId, commodity, direction),
        quantity,
        payment,
        logUnvalued);
  }

  /** 计一层税并把结果并进 {@code charges}（说不出法定币的价 ⇒ 本层不收 + 具名记录，绝不静默按 1:1 猜）。 */
  static void collectLayer(
      MatchContext ctx,
      List<MarketTaxBook.Charge> charges,
      BuySlot buy,
      CommodityId commodity,
      String zoneId,
      MarketTaxLayer layer,
      long perUnitMilli,
      long adValoremPerMille,
      long quantity,
      long payment,
      boolean logUnvalued) {
    if (perUnitMilli == 0L && adValoremPerMille == 0L) {
      return;
    }
    MarketTaxBook.Assessment assessment =
        MarketTaxBook.assess(
            layer,
            ctx.round.portTax().tableOf(zoneId),
            ctx.currencyValuation(),
            buy.currency,
            buy.regionId,
            buy.buyer.actor(),
            quantity,
            payment,
            perUnitMilli,
            adValoremPerMille,
            zoneId);
    if (assessment.unvalued()) {
      // ★ 业务路径上的具名缺口（INFO，不降级为静默 0）：法定币在买方这一侧既无报价也不流通 ⇒ 说不出价。
      //   ★ `logUnvalued == false` = 可负担预判那条路（{@link #chargesAtMost} 的二分里会被问很多次）⇒
      //     只算不记：日志只由**真的落账**那一处（{@link #executeTrade}）发一条，不刷屏、也不重复。
      if (!logUnvalued) {
        return;
      }
      //   ★ 为什么不是 fail-closed 拒绝成交：设计书 §13.2-4 明写"税不影响能不能过"（那是闸的事）。
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MARKET_TAX_UNVALUED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "layer",
                  layer.value(),
                  "zone",
                  zoneId,
                  "commodity",
                  commodity.value(),
                  "buyerPays",
                  buy.currency.value(),
                  "ratePerUnitMilli",
                  perUnitMilli,
                  "rateAdValoremPerMille",
                  adValoremPerMille,
                  "collected",
                  false,
                  "reason",
                  "legal-tender-has-no-quotation-and-does-not-circulate-locally"));
      return;
    }
    charges.addAll(assessment.charges());
  }

  /** 层 → 转移原因（唯一拼写点；三档各自具名，读账时分得出被抽的是哪一层）。 */
  static TransferReason taxReasonOf(MarketTaxLayer layer) {
    return switch (layer) {
      case PORT_EXIT -> TransferReason.PORT_TAX_EXIT;
      case PORT_ENTRY -> TransferReason.PORT_TAX_ENTRY;
      case IN_ZONE_MARKET -> TransferReason.MARKET_TAX_IN_ZONE;
    };
  }

  /** TRACE 一条：逐笔逐层的税额与币种（§一.9 的逐笔档；默认关，排查"这笔怎么被抽了这么多"用它）。 */
  static void logTaxCharged(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      MarketTaxBook.Charge charge,
      long quantity,
      long payment) {
    if (!MARKET.isTraceEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .trace(
            LogEvent.of(
                "MARKET_TAX_LAYER_CHARGED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "layer",
                charge.layer().value(),
                "zone",
                charge.zone(),
                "government",
                charge.governmentId(),
                "treasury",
                charge.treasury().id(),
                "currency",
                charge.currency().value(),
                "amountMilli",
                charge.amountMilli(),
                "commodity",
                commodity.value(),
                "quantityMilli",
                quantity,
                "paymentMilli",
                payment,
                "buyer",
                buy.buyer.actor().id(),
                "seller",
                sell.seller.actor().id()));
  }

  /**
   * ★ <b>P-T5b：买方支付币说不出价、连运费都算不出来 ⇒ 本笔不成交 + 具名归因（DEBUG）</b>。
   *
   * <p>★ 正常路径上不可达（{@code chargesAtMost} 已先按同一个 {@link BuySlot#payAmountOf} 把这种路线判成"付不起"）；
   * 它在这里是<b>第二道 fail-closed</b>：万一预判被绕过，也绝不把本格计价币的运费当成支付币的运费去铸腿。
   */
  static void logFreightCurrencyUnvalued(
      MatchContext ctx, BuySlot buy, SellSlot sell, RouteContext route) {
    if (buy.blocked == null) {
      buy.blocked = MarketUnfilledReason.PRICE_LIMIT;
    }
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响结算
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_FREIGHT_CURRENCY_UNVALUED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "buyer",
                buy.buyer.actor(),
                "seller",
                sell.seller.actor(),
                "commodity",
                buy.order.commodity().value(),
                "buyerPays",
                buy.currency.value(),
                "lane",
                route.from() + "->" + route.to(),
                "freightPerUnitMilli",
                route.freightPerUnit(),
                "reason",
                "pay-currency-has-no-quotation-and-does-not-circulate-locally"));
  }
}
