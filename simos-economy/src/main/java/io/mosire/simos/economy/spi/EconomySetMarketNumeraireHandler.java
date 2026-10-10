package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.MarketTable;
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
 * ★★ <b>{@code economy.SetMarketNumeraire}（A2b 2026-10-08；★★ W1 起逐区）</b>：GM 把
 * <b>某个市场</b>的计价币（{@link MarketTable#numeraire()}）改成世界词表里的另一种币。
 *
 * <pre>{@code
 * {"marketId":"single-region","numeraire":"copper"}
 * }</pre>
 *
 * <p>★★ <b>它为什么存在</b>：{@code MarketTable.numeraire} 是**买方支付币种**（{@code BuySlot.currency}）与
 * **卖方收款币种**（{@code SellSlot.receiveCurrency}）的来源。A2a 落地了"两者不等 ⇒ 具名拒 {@code
 * currency_mismatch}"（I19）的代码，但当时世界上**没有任何命令**能让两个市场的计价币不同 ⇒ F5 的"买持铜 / 卖只收银"造不出来。
 *
 * <p>★★ <b>W1 的形状变更（逐格 ⇒ 逐区）</b>：价表按市场区一张 ⇒ 载荷从 {@code {q,r}} 改成 {@code {marketId}}。 ★
 * <b>持久形态下本命令同时改两处</b>：{@code MarketZone.legalTender}（本区币的**唯一权威**）与价表的 {@code numeraire}（它的投影）——
 * {@code EconomyData} 的构造期守卫要求两者逐值相等，分开改会当场被拒。
 *
 * <p>★★ <b>写什么、不写什么（逐条）</b>：
 *
 * <ul>
 *   <li>只写 {@code marketTables} 的**一个市场**：{@code numeraire} 换成新币，{@link MarketTable#prices()}
 *       **逐值原样保留** （价格是"每商品单位的毫计价货币" ⇒ 数值按新币读，量纲不变）；持久形态下同一次变更集里把 {@code marketZones} 的 {@code
 *       legalTender} 一起改（两处必须同值）；
 *   <li>★★ <b>不折算任何余额、不折算任何价格</b>（I17：世界上没有汇率，也不许有）—— 该市场此后按新币读价，是 GM 的显式判断，
 *       不是系统偷偷换汇。这一点写进日志与拒因，避免被读成"兑换"；
 *   <li>不动商品、货币、账户、债务、需求、政府、词表：一个数都不动；
 *   <li>只作用于**已存在的市场**：市场不存在 ⇒ 具名拒 {@code market-missing}（说不出要改的是哪张价表；新市场走 {@code
 *       economy.DefineMarketZone}）；
 *   <li>币种必须已进**世界词表**（{@code EconomyData.currencies}，不是进程内门面）⇒ 否则具名拒 {@code unknown-currency}；
 *   <li>与现值相同 ⇒ 具名拒 {@code numeraire-unchanged}（照 {@code economy.SetOfficialRate} 的幂等口径：没变化就不落
 *       revision）。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>：本命令标 {@link GmOnlyCommand}（照 {@code economy.SetMarketPrice}）——
 * 它改的是"这一片按什么钱报价"， 属货币制度面，不进决策人令（{@code sd.IssueDirective} / {@code AdjudicateTick} 的 directive
 * 载荷）。
 *
 * <p>★★ <b>E（2026-10-09）之后"异币"不再是拒因</b>：买方支付币 ≠ 卖方市场计价币时，卖方按**自己对那种钱的估值**判断划算与否 （{@link
 * io.mosire.simos.economy.time.MarketSettlement} 的唯一拼写点；估值 = 世界汇率行情 / 当地实际流通 ⇒ 面值）。 本命令正是 E
 * 判据的现场制造器（"买方持铜 / 卖方按银报价收银"：把某个市场的计价币改成银即可），不再需要"先走外汇"。
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
      String marketIdText = EconomyCommandPayloads.requireText(TYPE, payload, "marketId");
      MarketZoneId marketId = MarketZoneId.parse(marketIdText);
      String numeraireText = EconomyCommandPayloads.requireText(TYPE, payload, "numeraire");
      CurrencyId currency = new CurrencyId(numeraireText);
      MarketTable existing = base.marketTables().get(marketId);
      if (existing == null) {
        return reject(marketId.value(), numeraireText, "market-missing", "该市场不存在（说不出要改哪张价表）");
      }
      if (!base.currencies().containsKey(currency)) {
        return reject(
            marketId.value(),
            numeraireText,
            "unknown-currency",
            "币种不在世界词表里：" + numeraireText + "（词表=" + base.currencies().keySet() + "）");
      }
      if (existing.numeraire().equals(currency)) {
        return reject(marketId.value(), numeraireText, "numeraire-unchanged", "该市场计价币已经是它，没变化");
      }
      MarketZone zone = base.marketZones().get(marketId);
      Map<MarketZoneId, MarketTable> marketTables = new LinkedHashMap<>(base.marketTables());
      // ★★ W1：持久形态下"本区计价币"的权威是 MarketZone.legalTender（双权威合并的唯一幸存者）⇒
      //   同一条命令同一次变更集里把两处一起改（EconomyData 的构造期守卫要求它们逐值相等）。
      marketTables.put(marketId, existing.withNumeraire(currency));
      Map<MarketZoneId, MarketZone> marketZones = base.marketZones();
      if (zone != null) {
        Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(base.marketZones());
        zones.put(marketId, zone.withLegalTender(currency));
        marketZones = zones;
      }
      // ★★ W1d（2026-10-11）：**改法定币与改价表计价币同一次构造**（原子写口）—— 拆成两个 wither 的中间态是
      //   "新币的价表 + 旧法定币的区"，当场撞"价表计价币必须等于该区法定币"。派生市场（zone == null）时
      //   区表原样带过，本写口与 `withMarketTables` 等价（一次构造、同一份 canonical 构造器与全部守卫）。
      EconomyData projected = base.withMarketZonesAndTables(marketZones, marketTables);
      Set<String> distinct = new TreeSet<>();
      for (MarketTable market : projected.marketTables().values()) {
        distinct.add(market.numeraire().value());
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MARKET_NUMERAIRE_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "marketId",
                  marketId.value(),
                  "authority",
                  zone == null ? "derived-market" : "persistent-zone",
                  "from",
                  existing.numeraire().value(),
                  "to",
                  currency.value(),
                  "prices",
                  existing.prices().size(),
                  "worldNumeraires",
                  distinct,
                  "note",
                  "不折算任何余额与价格（世界无汇率）—— 本市场此后按新币报价；买方付什么币、卖方按自己对那种钱的估值"
                      + "判划不划算（E：MarketSettlement.settlementUnitPrice）"));
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MARKET_NUMERAIRE_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "marketId",
                    marketId.value(),
                    "previousNumeraire",
                    existing.numeraire().value(),
                    "newNumeraire",
                    currency.value(),
                    "pricesKept",
                    existing.prices(),
                    "legalTenderUpdated",
                    zone != null,
                    "marketTables",
                    projected.marketTables().size()));
      }
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
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
      String marketId, String numeraire, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MARKET_NUMERAIRE_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "marketId",
                marketId,
                "numeraire",
                numeraire,
                "reason",
                reason,
                "detail",
                detail));
    return new HandlerOutcome.Rejected(reason + ": " + detail);
  }
}
