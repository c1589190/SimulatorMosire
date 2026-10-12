package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.change.EconomyChangeSet;
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
 * ★★ <b>{@code economy.SetMarketZonePriceAlpha}（W2，2026-10-11）</b>：GM 把<b>某个市场区</b>的动态价步长 α （{@link
 * MarketZone#priceAlphaPerMille()}）改成给定值。
 *
 * <pre>{@code
 * {"marketId":"single-region","alphaPerMille":50}
 * }</pre>
 *
 * <p>★★ <b>它为什么存在</b>：约束设计书 §2.1 的 α 是<b>逐区、每市场轮价格最多动多少</b>的步长（缺货涨、滞销跌都以它封顶）。 口径溯源 §4.1 记着用户
 * 2026-09-25 的裁定「凡是行为一律做成 GM 可调的预设」⇒ α <b>不是</b>"默认可调"， 而是<b>必须可调</b>：写死在编译期常量里就违约。本命令就是那个写口。
 *
 * <p>★★ <b>写什么、不写什么（逐条）</b>：
 *
 * <ul>
 *   <li>只写 {@code marketZones} 的<b>一个区</b>的一个字段：其它组件（价表、成员格、法定币、官方汇率、账户、债务）<b>一个数都不动</b>；
 *   <li><b>不设上下限</b>（约束设计书 §2.1）：除 {@code α ≥ 0} 外没有任何夹取 —— α 可以比出厂值大得多（价格一步到位）， 也可以为
 *       0（该区价格从此不随供需变动）。★ 负值具名拒 {@code bad-payload}（"反向步长"没有意义）；
 *   <li>★ <b>α = 0 是合法状态</b>，不是"缺省"：它表达"这个区的价格由 GM 定死"，与"没设过 ⇒ 50‰"是两件事；
 *   <li>与现值相同 ⇒ 具名拒 {@code alpha-unchanged}（照 {@code economy.SetMarketNumeraire} 的幂等口径：没变化就不落
 *       revision）；
 *   <li>只作用于<b>已存在的持久市场区</b>：区不存在 ⇒ 具名拒 {@code zone-missing}。★ <b>派生市场（{@code marketZones}
 *       为空的世界）没有区可挂 α</b> ⇒ 同样具名拒 —— 派生形态一律走出厂缺省 {@value
 *       MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}‰， 不静默建区（建区要同时给成员格与法定币，那是 {@code
 *       economy.DefineMarketZone} 的判据）；
 *   <li>★ <b>生效时点</b>：本命令改的是状态，下一<b>市场轮</b>的自适应写回读新值（{@code MarketSettlement.adaptPrices} 逐区现读）——
 *       轮内已算出的价一个都不改。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>：本命令不打 {@code DmUsableCommand}（照 {@code economy.SetMarketNumeraire}）——
 * 它改的是价格形成机制的强度，属市场制度面，不进决策人令（{@code sd.IssueDirective} / {@code AdjudicateTick} 的 directive 载荷）。
 *
 * <p>★ <b>铁律 5</b>：{@code priceAlphaPerMille} 是 {@code MarketZone} 的持久组件 ⇒ 本写口经 {@code
 * EconomyData.withMarketZones*} → {@code EconomyChangeSet.between}（{@code FieldDelta.diff} 按 record
 * 相等判变化）→ Codec（Jackson record 绑定）三件套齐备；往返不变式由同一批构造器保证。
 */
public final class EconomySetMarketZonePriceAlphaHandler implements CommandHandler {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.SetMarketZonePriceAlpha";

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
    String marketIdText = "";
    String alphaText = "";
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      marketIdText = EconomyCommandPayloads.requireText(TYPE, payload, "marketId");
      long alphaPerMille = EconomyCommandPayloads.requireLong(TYPE, payload, "alphaPerMille");
      alphaText = Long.toString(alphaPerMille);
      MarketZoneId marketId = MarketZoneId.parse(marketIdText);
      if (alphaPerMille < 0L) {
        return reject(
            marketIdText,
            alphaText,
            "bad-payload",
            "alphaPerMille 不得为负（0 = 该区价格不再随供需变动，是合法状态；没有\"反向步长\"）: " + alphaPerMille);
      }
      MarketZone zone = base.marketZones().get(marketId);
      if (zone == null) {
        return reject(
            marketIdText,
            alphaText,
            "zone-missing",
            "该市场区不存在（派生市场没有区可挂 α，一律走出厂缺省 "
                + MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE
                + "‰；要立区请走 economy.DefineMarketZone）: "
                + marketIdText);
      }
      if (zone.priceAlphaPerMille() == alphaPerMille) {
        return reject(marketIdText, alphaText, "alpha-unchanged", "该区动态价步长已经是它，没变化");
      }
      Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(base.marketZones());
      zones.put(marketId, zone.withPriceAlphaPerMille(alphaPerMille));
      // ★ 只碰区表这一个组件：价表/成员格/法定币/汇率/账户/债务一个数都不动（canonical 构造器与全部守卫照跑）。
      EconomyData projected = base.withMarketZones(zones);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MARKET_ZONE_PRICE_ALPHA_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "marketId",
                  marketId.value(),
                  "from",
                  zone.priceAlphaPerMille(),
                  "to",
                  alphaPerMille,
                  "zones",
                  projected.marketZones().size(),
                  "effectiveFrom",
                  "next-market-round",
                  "note",
                  "α = 每市场轮价格最多变动的千分比（缺货涨、滞销跌都以它封顶；不设上下限）"));
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MARKET_ZONE_PRICE_ALPHA_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "marketId",
                    marketId.value(),
                    "previousAlpha",
                    zone.priceAlphaPerMille(),
                    "newAlpha",
                    alphaPerMille,
                    "zone",
                    zone.describe(),
                    "marketTables",
                    projected.marketTables().size(),
                    "why",
                    "alpha-is-a-per-zone-policy-knob-user-2026-09-25-all-behaviour-must-be-gm-tunable"));
      }
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MARKET_ZONE_PRICE_ALPHA_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "marketId",
                  marketIdText,
                  "alphaPerMille",
                  alphaText,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static HandlerOutcome reject(
      String marketId, String alpha, String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MARKET_ZONE_PRICE_ALPHA_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "marketId",
                marketId,
                "alphaPerMille",
                alpha,
                "reason",
                reason,
                "detail",
                detail));
    return new HandlerOutcome.Rejected(reason + ": " + detail);
  }
}
