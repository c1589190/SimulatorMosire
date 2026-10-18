package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS;

import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.MarketTable;
import java.util.Map;
import java.util.Objects;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；P2a 起只管 5 天例行节奏）。 */
final class MarketTriggerBook {

  private MarketTriggerBook() {}

  /**
   * ★★ <b>今天的市场开不开、为什么开</b>（调度判据；见 {@link MarketTrigger} 与 {@link MarketSettlement} 类注）。
   *
   * <p>★ 只看绝对日与当前市场表：没有市场 ⇒ {@link MarketTrigger#NONE}；{@code day % 5 == 0} ⇒ {@link
   * MarketTrigger#PERIODIC}；其余 ⇒ {@link MarketTrigger#NONE}。关账日与低库存都不再是旁路。
   */
  static MarketTrigger triggerFor(long day, Map<MarketZoneId, MarketTable> marketTables) {
    Objects.requireNonNull(marketTables, "marketTables");
    if (marketTables.isEmpty()) {
      return MarketTrigger.NONE; // 世上没有市场 ⇒ 什么都不做
    }
    return day % MARKET_RESTOCK_INTERVAL_DAYS == 0L ? MarketTrigger.PERIODIC : MarketTrigger.NONE;
  }
}
