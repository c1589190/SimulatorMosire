package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.TransportPriceAnchor;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C7a：运输市场区锚价的只读读口与 S7-END 纯更新入口</b>。
 *
 * <p>★★ <b>市场区唯一锚价</b>：锚按 {@link MarketZoneId} 持久化；同一市场区的所有 hex、所有承运户看到同一个 {@code
 * pricePerWorkMilli}。市场轮内价格不变；本类只应在市场轮结束、C7b 完成 lane 分配与本地观察之后调用一次， 更新结果下一市场轮生效。
 *
 * <p>★★ <b>缺键 = 0，0 是合法价格</b>：{@link #pricePerWorkMilli} 对缺锚直接返回 0；{@link #anchorOf} 返回只读默认锚，
 * <b>不会创建或写入任何状态</b>。不得用 0 表示"无市场/无锚"的失败。
 *
 * <p>★★ <b>公式不复制</b>：涨价/降价一律调用既有 {@link MarketPricer#adaptiveNextPrice(long, long, long, long)}
 * （全仓唯一自适应价格拼写点）。本类只负责 streak、延迟降价阈值、币种/市场轮校验与写回工作副本。
 *
 * <p>★★ <b>D/S 是市场区汇总值</b>：调用方必须传入 C7b 汇总后的 {@code D_zone}/{@code S_zone}；本地未成交量与本地
 * 未售容量不得先互相抵消。本类不派生需求、不分配 lane、不改商品订单/撮合。
 */
public final class TransportPriceAnchorBook {

  /** 连续满足（D <= S）达到该市场轮数后，才允许调用同一自适应公式降价。 */
  public static final long SATISFIED_ROUNDS_BEFORE_DECREASE = 2L;

  private TransportPriceAnchorBook() {}

  /**
   * 读取该市场区的锚；缺键 ⇒ 只读默认锚（price/streak = 0，lastAdjusted = 0），<b>不写状态</b>。
   *
   * <p>默认锚的 currency 取本区当刻 {@link MarketTable#numeraire()}；因此本方法需要该区市场表存在（用于回答"0 价
   * 按什么币计价"）。若调用方只需要缺锚读价，请用 {@link #pricePerWorkMilli(EconomyDayView, MarketZoneId)}，它不需要 market
   * table。
   *
   * @throws IllegalStateException 缺市场表（契约故障，不降级成伪造币种）
   */
  public static TransportPriceAnchor anchorOf(EconomyDayView view, MarketZoneId zone) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(zone, "zone");
    TransportPriceAnchor anchor = view.transportPriceAnchors().get(zone);
    if (anchor != null) {
      return anchor;
    }
    MarketTable table = view.marketTables().get(zone);
    if (table == null) {
      throw contractFault("transport-price-anchor-market-table-missing", "zone=" + zone.value());
    }
    // 只读默认；lastAdjustedMarketRound 用 0（0 是合法市场轮，缺锚由 map 缺键表达，不引入负 sentinel）。
    return new TransportPriceAnchor(zone, table.numeraire(), 0L, 0L, 0L, 0L);
  }

  /** 该市场区的运输锚价；缺键 ⇒ 0（只读默认，不写状态）。 */
  public static long pricePerWorkMilli(EconomyDayView view, MarketZoneId zone) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(zone, "zone");
    Map<MarketZoneId, TransportPriceAnchor> anchors = view.transportPriceAnchors();
    TransportPriceAnchor anchor = anchors.get(zone);
    return anchor == null ? 0L : anchor.pricePerWorkMilli();
  }

  /**
   * ★★★ <b>S7-END：记录一个市场区的本轮观察并更新下一市场轮锚价</b>。
   *
   * <pre>
   * D &gt; S  : shortageStreak+1、satisfiedStreak=0；price' = adaptiveNextPrice(old, D, S, α)
   * D &lt;= S : shortageStreak=0、satisfiedStreak+1；
   *           satisfiedStreak &lt; 2 ⇒ price 不动；否则 price' = adaptiveNextPrice(old, D, S, α)
   * D = S = 0: 不涨价；仍按满足累计，adaptiveNextPrice 对 D=S 原样返回 old
   * </pre>
   *
   * <p>★ 写回前完成全部校验与完整新值计算；任何失败都不写 sheet。日志只读状态，不参与公式。
   *
   * @return 1 = 锚记录发生变化并已写入；0 = 新旧锚逐值相同（当前实现只在重复记录完全相同的轮次/输入时可能发生）
   *     <p>★ D-032 C10 blocker-D：持久 {@code MarketZone} 缺失但本区在 {@code marketTables} 里时，按派生区处理
   *     （currency = table.numeraire、α = {@link MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}、跳过
   *     legalTender 比对）；两表都无该 zone 仍 fail-closed。
   * @throws IllegalStateException 两表都无该 zone、缺市场表、币种漂移、marketRoundId 倒退等契约故障
   * @throws IllegalArgumentException 负轮次、负 D/S 等非法入参
   */
  public static int recordMarketRound(
      EconomyStateBuilder sheet,
      EconomyDayView view,
      MarketZoneId zone,
      long marketRoundId,
      long demandWorkMilli,
      long supplyWorkMilli) {
    Objects.requireNonNull(sheet, "sheet");
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(zone, "zone");
    if (marketRoundId < 0L) {
      throw new IllegalArgumentException("recordMarketRound marketRoundId 不得为负: " + marketRoundId);
    }
    if (demandWorkMilli < 0L || supplyWorkMilli < 0L) {
      throw new IllegalArgumentException(
          "recordMarketRound 的 D/S 不得为负: demand=" + demandWorkMilli + " supply=" + supplyWorkMilli);
    }

    MarketZone marketZone = view.marketZones().get(zone);
    MarketTable table = view.marketTables().get(zone);
    if (table == null) {
      // ★★★ D-032 C10 blocker-D：派生形态（marketZones 为空、市场区由 marketTables 派生）没有
      //   MarketZone 记录可查；此时市场表就是本市场存在性的唯一权威，缺表仍 fail-closed。
      if (marketZone == null) {
        throw contractFault("transport-price-anchor-zone-missing", "zone=" + zone.value());
      }
      throw contractFault("transport-price-anchor-market-table-missing", "zone=" + zone.value());
    }
    // ★★★ D-032 C10 blocker-D：持久区原路径逐值不变（α 取 MarketZone.priceAlphaPerMille、
    //   且必须比对 legalTender）；派生区没有 MarketZone 记录 ⇒ α 取类型级出厂缺省，
    //   币种唯一权威是 table.numeraire，无法也不得比对 legalTender。
    long priceAlphaPerMille;
    if (marketZone == null) {
      priceAlphaPerMille = MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE;
    } else {
      if (!marketZone.legalTender().equals(table.numeraire())) {
        throw contractFault(
            "transport-price-anchor-legal-tender-mismatch",
            "zone="
                + zone.value()
                + " legalTender="
                + marketZone.legalTender().value()
                + " numeraire="
                + table.numeraire().value());
      }
      priceAlphaPerMille = marketZone.priceAlphaPerMille();
    }

    TransportPriceAnchor oldAnchor = view.transportPriceAnchors().get(zone);
    if (oldAnchor != null) {
      if (!zone.equals(oldAnchor.marketZone())) {
        throw contractFault(
            "transport-price-anchor-zone-key-mismatch",
            "key=" + zone.value() + " value=" + oldAnchor.marketZone().value());
      }
      if (!oldAnchor.currency().equals(table.numeraire())) {
        throw contractFault(
            "transport-price-anchor-currency-mismatch",
            "zone="
                + zone.value()
                + " currency="
                + oldAnchor.currency().value()
                + " numeraire="
                + table.numeraire().value());
      }
      if (oldAnchor.lastAdjustedMarketRound() > marketRoundId) {
        throw contractFault(
            "transport-price-anchor-market-round-regression",
            "zone="
                + zone.value()
                + " lastAdjustedMarketRound="
                + oldAnchor.lastAdjustedMarketRound()
                + " incoming="
                + marketRoundId);
      }
    }

    long oldPrice = oldAnchor == null ? 0L : oldAnchor.pricePerWorkMilli();
    long oldShortageStreak = oldAnchor == null ? 0L : oldAnchor.shortageStreak();
    long oldSatisfiedStreak = oldAnchor == null ? 0L : oldAnchor.satisfiedStreak();

    long shortageStreak;
    long satisfiedStreak;
    long newPrice;
    if (demandWorkMilli > supplyWorkMilli) {
      shortageStreak = addStreak(oldShortageStreak, "shortageStreak", zone);
      satisfiedStreak = 0L;
      newPrice =
          MarketPricer.adaptiveNextPrice(
              oldPrice, demandWorkMilli, supplyWorkMilli, priceAlphaPerMille);
    } else {
      shortageStreak = 0L;
      satisfiedStreak = addStreak(oldSatisfiedStreak, "satisfiedStreak", zone);
      newPrice =
          satisfiedStreak >= SATISFIED_ROUNDS_BEFORE_DECREASE
              ? MarketPricer.adaptiveNextPrice(
                  oldPrice, demandWorkMilli, supplyWorkMilli, priceAlphaPerMille)
              : oldPrice;
    }

    TransportPriceAnchor newAnchor =
        new TransportPriceAnchor(
            zone, table.numeraire(), newPrice, shortageStreak, satisfiedStreak, marketRoundId);

    if (newAnchor.equals(oldAnchor)) {
      return 0;
    }
    // 全部校验与计算完成后才写工作副本；不写半状态。
    sheet.transportPriceAnchors().put(zone, newAnchor);

    EventLog.channel(EconomyLog.market())
        .info(
            LogEvent.of(
                "TRANSPORT_ANCHOR_UPDATED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                marketRoundId,
                "marketRoundId",
                marketRoundId,
                "zone",
                zone.value(),
                "currency",
                newAnchor.currency().value(),
                "demandWorkMilli",
                demandWorkMilli,
                "supplyWorkMilli",
                supplyWorkMilli,
                "oldPricePerWorkMilli",
                oldPrice,
                "newPricePerWorkMilli",
                newAnchor.pricePerWorkMilli(),
                "shortageStreak",
                newAnchor.shortageStreak(),
                "satisfiedStreak",
                newAnchor.satisfiedStreak()));
    return 1;
  }

  private static long addStreak(long current, String name, MarketZoneId zone) {
    try {
      return Math.addExact(current, 1L);
    } catch (ArithmeticException overflow) {
      throw contractFault(
          "transport-price-anchor-streak-overflow", "zone=" + zone.value() + " field=" + name);
    }
  }

  private static IllegalStateException contractFault(String word, String detail) {
    EventLog.channel(EconomyLog.market())
        .error(
            LogEvent.of(
                "TRANSPORT_ANCHOR_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_MARKET,
                "failure",
                word,
                "detail",
                detail == null ? "-" : detail));
    return new IllegalStateException(word + ": " + detail);
  }
}
