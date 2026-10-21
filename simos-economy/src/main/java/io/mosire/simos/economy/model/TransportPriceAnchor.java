package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C7a：运输市场区的持久价格锚</b>。
 *
 * <p>每个 {@link MarketZoneId} 最多一个锚；同区所有 hex、所有承运户看到同一个价格。市场轮内价格不变， 只在市场轮末由 {@code
 * TransportPriceAnchorBook#recordMarketRound(...)} 更新，下一市场轮生效。
 *
 * <p>★ <b>0 价合法</b>：{@code pricePerWorkMilli = 0} 是明确的免费价格，不是缺价。缺锚由 map 缺键表达； {@code
 * EconomyData#transportPriceAnchors()} 空表 = 从未有锚，读取价按 0 处理，但不会写状态。
 *
 * <p>★ <b>货币归属</b>：锚币种必须等于本区 {@code MarketTable.numeraire()} 且（持久区形态下）等于 {@code
 * MarketZone.legalTender()}；跨表一致性由 {@code EconomyData} 构造期与写入入口共同 fail-closed。
 *
 * @param marketZone 本锚所属市场区；不得为 null，且必须等于该 map 的键
 * @param currency 本区计价币；不得为 null
 * @param pricePerWorkMilli 每毫运输工作的价格（毫计价货币 / 毫工作）；不得为负，0 合法
 * @param shortageStreak 连续供不应求的市场轮数；不得为负
 * @param satisfiedStreak 连续满足（D <= S）的市场轮数；不得为负
 * @param lastAdjustedMarketRound 最近一次写入锚的市场轮 id（绝对 tick）；不得为负
 */
public record TransportPriceAnchor(
    MarketZoneId marketZone,
    CurrencyId currency,
    long pricePerWorkMilli,
    long shortageStreak,
    long satisfiedStreak,
    long lastAdjustedMarketRound) {

  public TransportPriceAnchor {
    Objects.requireNonNull(marketZone, "TransportPriceAnchor.marketZone 不得为 null");
    Objects.requireNonNull(currency, "TransportPriceAnchor.currency 不得为 null");
    if (pricePerWorkMilli < 0L) {
      throw new IllegalArgumentException(
          "TransportPriceAnchor.pricePerWorkMilli 不得为负（0 是合法免费价）: " + pricePerWorkMilli);
    }
    if (shortageStreak < 0L || satisfiedStreak < 0L) {
      throw new IllegalArgumentException(
          "TransportPriceAnchor 的 streak 不得为负: shortage="
              + shortageStreak
              + " satisfied="
              + satisfiedStreak);
    }
    if (lastAdjustedMarketRound < 0L) {
      throw new IllegalArgumentException(
          "TransportPriceAnchor.lastAdjustedMarketRound 不得为负: " + lastAdjustedMarketRound);
    }
  }
}
