package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
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
 * ★★ <b>{@code economy.SetMarketZoneLoanRateAlpha}（W5.5，2026-10-11，S-12⑤）</b>：GM 把<b>某个市场区</b>的
 * 货币市场利率自适应步长 α（{@link MarketTable#loanRateAlphaPerMille()}）改成给定值。
 *
 * <pre>{@code
 * {"marketId":"single-region","alphaPerMille":50}
 * }</pre>
 *
 * <p>★★ <b>它为什么存在</b>：S-12⑤ 的利率与 §2.1 的商品价走<b>同一套自适应算式</b>（缺钱涨、钱多跌、下限 0），而那个算式的 步长 α
 * 是"每市场轮利率最多动多少"。口径溯源 §4.1 记着用户 2026-09-25 的裁定「凡是行为一律做成 GM 可调的预设」 ⇒ α
 * <b>不是</b>"默认可调"，而是<b>必须可调</b>：写死在编译期常量里就违约。本命令就是那个写口（与 {@code economy.SetMarketZonePriceAlpha}
 * 同形制：逐区、一个字段、下一市场轮生效）。
 *
 * <p>★★ <b>写什么、不写什么（逐条）</b>：
 *
 * <ul>
 *   <li>只写 {@code marketTables} 的<b>一个市场</b>的一个字段：其它组件（价格表、成员格、计价币、区表、官方汇率、价格步长 α、
 *       本轮清算利率、账户、债务）<b>一个数都不动</b>；
 *   <li><b>不设上下限</b>：除 {@code α ≥ 0} 外没有任何夹取 —— α 可以比出厂值大得多（利率一步到位），也可以为 0（该区利率 从此不随供需变动）；★ 负值具名拒
 *       {@code bad-payload}（"反向步长"没有意义）；
 *   <li>★ <b>α = 0 是合法状态</b>，不是"缺省"：它表达"这个区的钱价由 GM 定死"（判据 6 的"α=0 ⇒ 冻结"）；
 *   <li>与现值相同 ⇒ 具名拒 {@code alpha-unchanged}（照 {@code economy.SetMarketZonePriceAlpha} / {@code
 *       SetMarketNumeraire} 的幂等口径：没变化就不落 revision）；
 *   <li>只作用于<b>已存在的持久价表</b>（= 一个真在交易的市场）：该市场没有价表 ⇒ 具名拒 {@code table-missing} （"区在、表不在"是 W1d 起的合法形态
 *       = 那个区不交易任何商品 ⇒ 它的货币腿也不跑，没有可挂 α 的市场）； ★ 反过来说：<b>派生形态的世界（{@code marketZones}
 *       为空、价格表持久）照样有写口</b> —— 这正是把这两个数挂在 价表而不是区记录上的原因（见 {@code MarketTable} 的类注）；
 *   <li>★ <b>生效时点</b>：本命令改的是状态，下一<b>市场轮</b>的货币腿读新值（{@code MoneyMarketSettlement} 逐区现读）——
 *       轮内已算出的利率与已签的合同一个都不改。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>：本命令不打 {@code DmUsableCommand} —— 它改的是<b>利率形成机制的强度</b>，属市场制度面，不进决策人令 （{@code
 * sd.IssueDirective} / {@code AdjudicateTick} 的 directive 载荷）。
 *
 * <p>★ <b>铁律 5</b>：{@code loanRateAlphaPerMille} 是 {@code MarketZone} 的持久组件 ⇒ 本写口经 {@code
 * EconomyData.withMarketZones} → {@code EconomyChangeSet.between}（{@code FieldDelta.diff} 按 record
 * 相等判变化） → Codec（Jackson record 绑定）三件套齐备；往返不变式由同一批构造器保证。
 */
public final class EconomySetMarketZoneLoanRateAlphaHandler implements CommandHandler {

  /** 命令类型（全仓唯一拼写点；{@code Shell} 注册与 catalog 载荷提示都取它）。 */
  public static final String TYPE = "economy.SetMarketZoneLoanRateAlpha";

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
            "alphaPerMille 不得为负（0 = 该区利率不再随供需变动，是合法状态；没有\"反向步长\"）: " + alphaPerMille);
      }
      MarketTable table = base.marketTables().get(marketId);
      if (table == null) {
        return reject(
            marketIdText,
            alphaText,
            "table-missing",
            "该市场没有价表（区在、表不在 = 那个区不交易任何商品 ⇒ 它的货币腿也不跑，没有可挂 α 的市场；"
                + "缺省 "
                + MarketTable.DEFAULT_LOAN_RATE_ALPHA_PER_MILLE
                + "‰ 只在市场真的跑起来时才用得上）: "
                + marketIdText);
      }
      if (table.loanRateAlphaPerMille() == alphaPerMille) {
        return reject(marketIdText, alphaText, "alpha-unchanged", "该市场货币市场利率步长已经是它，没变化");
      }
      Map<MarketZoneId, MarketTable> tables = new LinkedHashMap<>(base.marketTables());
      tables.put(marketId, table.withLoanRateAlphaPerMille(alphaPerMille));
      // ★ 只碰价表这一个组件（且只碰一张表的一个字段）：价格表/成员格/计价币/区表/汇率/账户/债务一个数都不动
      //   （canonical 构造器与全部守卫照跑）。
      EconomyData projected = base.withMarketTables(tables);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MONEY_MARKET_LOAN_RATE_ALPHA_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "marketId",
                  marketId.value(),
                  "from",
                  table.loanRateAlphaPerMille(),
                  "to",
                  alphaPerMille,
                  "loanRatePerMille",
                  table.loanRatePerMille(),
                  "markets",
                  projected.marketTables().size(),
                  "effectiveFrom",
                  "next-market-round",
                  "note",
                  "α = 每市场轮货币市场利率最多变动的千分比（缺钱涨、钱多跌都以它封顶；不设上下限）"));
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MONEY_MARKET_LOAN_RATE_ALPHA_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "marketId",
                    marketId.value(),
                    "previousAlpha",
                    table.loanRateAlphaPerMille(),
                    "newAlpha",
                    alphaPerMille,
                    "market",
                    marketIdText,
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
                  "MONEY_MARKET_LOAN_RATE_ALPHA_REJECTED",
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
                "MONEY_MARKET_LOAN_RATE_ALPHA_REJECTED",
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
