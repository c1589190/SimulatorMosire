package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ {@code economy.SetMarketPrice}（R4-E2；★★ W1 起**逐区**）：GM 给某个<b>市场</b>的某商品定价。
 *
 * <pre>{@code
 * {"marketId":"single-region","commodity":"wool","price":12}
 * }</pre>
 *
 * <p>★★ <b>W1 的形状变更（逐格 ⇒ 逐区）</b>：价表按市场区一张（{@code EconomyData.marketTables} 键 = {@code
 * MarketZoneId}）⇒ 本命令的载荷从 {@code {q,r}} 改成 {@code {marketId}}，写的就是那一张表。区内任何格此后
 * 读到同一个数（"区内一价"由形状保证，不再需要逐成员格对齐）。
 *
 * <p>★ <b>语义（逐条）</b>：
 *
 * <ul>
 *   <li>{@code price > 0}（{@link MarketTable} 的构造期守卫也判死；这里先给出具名拒绝）；
 *   <li>市场<b>必须已存在</b>（持久区走 {@code economy.DefineMarketZone}；派生市场由创世播种给出）——
 *       "凭空建一张市场"必须同时给出成员格与计价币，那是立区命令的判据，不是定价命令能替的（fail-closed 具名拒）；
 *   <li>保留该市场的计价币与成员格，只 upsert 该商品价格；
 *   <li><b>只写 {@code marketTables}</b>；不动价格以外的任何状态（自适应定价没有第二处改价，见 {@code MarketSettlement}）。
 * </ul>
 *
 * <p>★ <b>不做的事</b>：不实现 {@code CommandTargets}（同 {@code economy.MigrateHousehold}：GM {@code
 * simos.command.submit} 可用，directive 内会被 fail-closed 拒）。
 */
public final class EconomySetMarketPriceHandler implements CommandHandler {

  private static final String COMMAND = "economy.SetMarketPrice";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return COMMAND;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
      String marketIdText = EconomyCommandPayloads.requireText(COMMAND, payload, "marketId");
      MarketZoneId marketId = MarketZoneId.parse(marketIdText);
      CommodityId commodity =
          CommodityId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "commodity"));
      long price = EconomyCommandPayloads.requireLong(COMMAND, payload, "price");
      if (price <= 0L) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ECONOMY_SET_MARKET_PRICE_REJECTED",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "reason",
                    "non-positive-price",
                    "marketId",
                    marketId.value(),
                    "commodity",
                    commodity.value(),
                    "price",
                    price));
        return new HandlerOutcome.Rejected("price 必须 > 0（'白送'不是一种价格）: " + price);
      }
      MarketTable existing = base.marketTables().get(marketId);
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "ECONOMY_SET_MARKET_PRICE_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "marketId",
                    marketId.value(),
                    "commodity",
                    commodity.value(),
                    "price",
                    price,
                    "existingMarket",
                    existing != null,
                    "existingPrice",
                    existing != null && existing.hasPrice(commodity)
                        ? existing.prices().get(commodity)
                        : "-",
                    "numeraire",
                    existing == null ? "-" : existing.numeraire().value(),
                    "hexes",
                    existing == null ? "-" : existing.hexes().size(),
                    "authority",
                    base.marketZones().containsKey(marketId)
                        ? "persistent-zone"
                        : "derived-market"));
      }
      // ★★ W1（逐格 → 逐区）：定价只作用于**已存在的市场**（持久区 / 派生市场）—— 新形状下"凭空建一张市场"
      //   必须同时给出成员格与计价币（那是 economy.DefineMarketZone 的判据），不是定价命令能替的。
      if (existing == null) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ECONOMY_SET_MARKET_PRICE_REJECTED",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "reason",
                    "market-missing",
                    "marketId",
                    marketId.value(),
                    "commodity",
                    commodity.value(),
                    "knownMarkets",
                    base.marketTables().size()));
        return new HandlerOutcome.Rejected(
            "市场不存在（说不出要改哪张价表；新市场走 economy.DefineMarketZone）: " + marketId.value());
      }
      Map<MarketZoneId, MarketTable> marketTables = new LinkedHashMap<>(base.marketTables());
      marketTables.put(marketId, existing.withPrice(commodity, price));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_SET_MARKET_PRICE_APPLIED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "marketId",
                  marketId.value(),
                  "authority",
                  base.marketZones().containsKey(marketId) ? "persistent-zone" : "derived-market",
                  "numeraire",
                  existing.numeraire().value(),
                  "commodity",
                  commodity.value(),
                  "from",
                  existing.hasPrice(commodity) ? existing.prices().get(commodity) : "-",
                  "to",
                  price,
                  "prices",
                  marketTables.get(marketId).prices().size(),
                  "marketTables",
                  marketTables.size(),
                  "note",
                  "价表按市场区一张：区内任何格此后读到同一个数（W1 逐格价表已退役）"));
      return new HandlerOutcome.Applied(
          EconomyChangeSet.between(base, base.withMarketTables(marketTables)));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_SET_MARKET_PRICE_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
