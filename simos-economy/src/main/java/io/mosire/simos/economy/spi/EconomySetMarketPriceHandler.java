package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
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
 * ★★ {@code economy.SetMarketPrice}（R4-E2）：GM 给某格某商品定价。
 *
 * <pre>{@code
 * {"q":-20,"r":-81,"commodity":"wool","price":12}
 * }</pre>
 *
 * <p>★ <b>语义（逐条对应 E2a 任务书）</b>：
 *
 * <ul>
 *   <li>{@code price > 0}（{@link Market} 的构造期守卫也判死；这里先给出具名拒绝）；
 *   <li>该格<b>没有市场行</b> ⇒ 用 Silver 计价创建一张空市场（只放这一个价；不造商品/货币/账户）；
 *   <li>该格<b>已有市场</b> ⇒ 保留原计价货币，只 upsert 该商品价格；
 *   <li><b>只写 {@code markets}</b>；不动价格以外的任何状态（自适应定价没有第二处改价，见 {@code MarketSettlement}）。
 * </ul>
 *
 * <p>★ <b>不做的事</b>：不校验"该格在图上"（命令层只要求格坐标合法；市场可以在尚未播种的格上先建，等人口/产业后到 —— 那是 GM 的判断，不是状态守卫）；不实现 {@code
 * CommandTargets}（同 {@code economy.MigrateHousehold}：GM {@code simos.command.submit} 可用，directive
 * 内会被 fail-closed 拒）。
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
      int q = EconomyCommandPayloads.optionalInt(COMMAND, payload, "q", Integer.MIN_VALUE);
      int r = EconomyCommandPayloads.optionalInt(COMMAND, payload, "r", Integer.MIN_VALUE);
      if (q == Integer.MIN_VALUE || r == Integer.MIN_VALUE) {
        throw new IllegalArgumentException(COMMAND + " 缺少格坐标字段: q/r");
      }
      HexCoord hex = new HexCoord(q, r);
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
                    "q",
                    q,
                    "r",
                    r,
                    "commodity",
                    commodity.value(),
                    "price",
                    price));
        return new HandlerOutcome.Rejected("price 必须 > 0（'白送'不是一种价格）: " + price);
      }
      Map<HexCoord, Market> markets = new LinkedHashMap<>(base.markets());
      Market existing = markets.get(hex);
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "ECONOMY_SET_MARKET_PRICE_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "hex",
                    hex.toString(),
                    "commodity",
                    commodity.value(),
                    "price",
                    price,
                    "existingMarket",
                    existing != null,
                    "existingPrice",
                    existing != null && existing.hasPrice(commodity)
                        ? existing.prices().get(commodity)
                        : "-"));
      }
      if (existing == null) {
        markets.put(hex, new Market(MoneyVocabulary.SILVER_CURRENCY, Map.of(commodity, price)));
      } else {
        Map<CommodityId, Long> prices = new LinkedHashMap<>(existing.prices());
        prices.put(commodity, price);
        markets.put(hex, new Market(existing.numeraire(), prices));
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_SET_MARKET_PRICE_APPLIED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "q",
                  q,
                  "r",
                  r,
                  "commodity",
                  commodity.value(),
                  "price",
                  price,
                  "marketCreated",
                  existing == null,
                  "prices",
                  markets.get(hex).prices().size(),
                  "markets",
                  markets.size()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base.withMarkets(markets)));
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
