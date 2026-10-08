package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code economy.SetMarketNumeraire}（A2b 2026-10-08；约束设计书 §3.5/§3.6 的最小前置命令面）</b>： GM
 * 把**某一格市场**的计价币（{@link Market#numeraire()}）改成世界词表里的另一种币。
 *
 * <pre>{@code
 * {"q":-2,"r":0,"numeraire":"copper"}
 * }</pre>
 *
 * <p>★★ <b>它为什么存在</b>：{@code Market.numeraire} 是**买方支付币种**（{@code BuySlot.currency}）与
 * **卖方收款币种**（{@code SellSlot.receiveCurrency}）的来源。A2a 落地了"两者不等 ⇒ 具名拒 {@code
 * currency_mismatch}"（I19）的代码，但当时世界上**没有任何命令**能让两格市场的计价币不同： {@code economy.SetMarketPrice}
 * 保留原计价币、{@code economy.Seed} 拒绝已占用的格 ⇒ F5 的"买持铜 / 卖只收银"在既有多币 世界里**造不出来**（A2a 账本 §6
 * BLOCKED-1）。本命令补上这一维，同时也正是 B2（市场区持久化：区→法定币）要面对的命令面。
 *
 * <p>★★ <b>写什么、不写什么（逐条）</b>：
 *
 * <ul>
 *   <li>只写 {@code markets} 这**一个组件的一格**：{@code numeraire} 换成新币，{@link Market#prices()} **逐值原样保留**
 *       （价格是"每商品单位的毫计价货币" ⇒ 数值按新币读，量纲不变）；
 *   <li>★★ <b>不折算任何余额、不折算任何价格</b>（I17：世界上没有汇率，也不许有）—— 该格此后按新币读价，是 GM 的显式判断，
 *       不是系统偷偷换汇。这一点写进日志与拒因，避免被读成"兑换"；
 *   <li>不动商品、货币、账户、债务、需求、政府、词表：一个数都不动；
 *   <li>只作用于**已有市场行**的格：该格没有市场 ⇒ 具名拒 {@code market-missing}（说不出要改的是哪张价格表；要新建市场走 {@code
 *       economy.SetMarketPrice} / {@code economy.Seed}）；
 *   <li>币种必须已进**世界词表**（{@code EconomyData.currencies}，不是进程内门面）⇒ 否则具名拒 {@code unknown-currency}；
 *   <li>与现值相同 ⇒ 具名拒 {@code numeraire-unchanged}（照 {@code economy.SetOfficialRate} 的幂等口径：没变化就不落
 *       revision）。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>：本命令标 {@link GmOnlyCommand}（照 {@code economy.SetMarketPrice}）——
 * 它改的是"这一格按什么钱报价"， 属货币制度面，不进决策人令（{@code sd.IssueDirective} / {@code AdjudicateTick} 的 directive
 * 载荷）。
 *
 * <p>★ <b>已知后果（写在命令面上，不是缺陷）</b>：改了某一格的计价币后，世界里出现**两种计价币** ⇒ {@code MarketTopologyBook.from}
 * 的"同币即同区"判据（D-027）不再成立，市场拓扑退回"城市节点 + tier 半径"装配； 且**任何**买方支付币 ≠ 卖方收款币的成交尝试一律具名拒（I19）——
 * 家户要用异币买东西必须先走外汇（市场 FX 或政府窗口）。
 */
public final class EconomySetMarketNumeraireHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.SetMarketNumeraire";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      int q = EconomyCommandPayloads.requireInt(TYPE, payload, "q");
      int r = EconomyCommandPayloads.requireInt(TYPE, payload, "r");
      String numeraireText = EconomyCommandPayloads.requireText(TYPE, payload, "numeraire");
      HexCoord hex = new HexCoord(q, r);
      CurrencyId currency = new CurrencyId(numeraireText);
      Market existing = base.markets().get(hex);
      if (existing == null) {
        return reject(q, r, numeraireText, "market-missing", "该格没有市场行（说不出要改哪张价格表）");
      }
      if (!base.currencies().containsKey(currency)) {
        return reject(
            q,
            r,
            numeraireText,
            "unknown-currency",
            "币种不在世界词表里：" + numeraireText + "（词表=" + base.currencies().keySet() + "）");
      }
      if (existing.numeraire().equals(currency)) {
        return reject(q, r, numeraireText, "numeraire-unchanged", "该格计价币已经是它，没变化");
      }
      Map<HexCoord, Market> markets = new LinkedHashMap<>(base.markets());
      markets.put(hex, new Market(currency, existing.prices()));
      Set<String> distinct = new TreeSet<>();
      for (Market market : markets.values()) {
        distinct.add(market.numeraire().value());
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MARKET_NUMERAIRE_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "q",
                  q,
                  "r",
                  r,
                  "from",
                  existing.numeraire().value(),
                  "to",
                  currency.value(),
                  "prices",
                  existing.prices().size(),
                  "worldNumeraires",
                  distinct,
                  "note",
                  "不折算任何余额与价格（世界无汇率）；异币成交将具名拒 currency_mismatch"));
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MARKET_NUMERAIRE_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "q",
                    q,
                    "r",
                    r,
                    "previousNumeraire",
                    existing.numeraire().value(),
                    "newNumeraire",
                    currency.value(),
                    "pricesKept",
                    existing.prices(),
                    "markets",
                    markets.size()));
      }
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base.withMarkets(markets)));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MARKET_NUMERAIRE_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static HandlerOutcome reject(
      int q, int r, String numeraire, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MARKET_NUMERAIRE_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "q",
                q,
                "r",
                r,
                "numeraire",
                numeraire,
                "reason",
                reason,
                "detail",
                detail));
    return new HandlerOutcome.Rejected(reason + ": " + detail);
  }
}
