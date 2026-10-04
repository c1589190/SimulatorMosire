package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>市场总调控</b>（D-027 的第二层：市场区这一层的聚合规则，按区施加一次）。它是<b>纯值类型</b>——
 * 不落盘、不进 {@code EconomyData}/Codec/ChangeSet，本批只作为逐轮瞬态传入
 * {@link MarketSettlement.MarketRound}；GM 命令面/落盘是后续批次。
 *
 * <p>★★ <b>与单 hex 贸易成本的分层</b>：本类回答"这一区本轮按什么参考价/限价撮合、最多成交多少、开不开市、税费读数是多少"，
 * <b>不</b>表达逐格物流成本（那是 {@link HexTradeCost} 的职责）。参考价/限价/配额是<b>区级</b>的，不得实现成逐 hex extra
 * cost；单 hex 损耗也不得承担价格/配额职能。
 *
 * <p>★★ <b>默认实例 = 逐值现状</b>（{@link #defaults}）：
 *
 * <ul>
 *   <li>{@code referencePrices} 空 ⇒ 沿用各 hex {@link Market#prices()}；
 *   <li>{@code bidPerMille}/{@code askPerMille} = 0 ⇒ 沿用 {@link Market#BID_PER_MILLE}/{@link Market#ASK_PER_MILLE}；
 *   <li>{@code quotaPerWindow} 空 ⇒ 无配额；
 *   <li>{@code tariffPerUnit} 空/0 ⇒ 无税费（本批只记读数，不搬钱）；
 *   <li>{@code open = true} ⇒ 正常撮合；{@code rules} 空 ⇒ 没有制度标签。
 * </ul>
 *
 * <p>★ <b>覆盖口径（唯一拼写点 = {@link #referencePriceOf} / {@link #bidPriceOf} / {@link #askPriceOf}）</b>：
 * 参考价只覆盖该 hex <b>已经定价</b>的商品（缺价 = 本格不交易它，不凭空造一行）；限价按覆盖后的参考价现算，
 * 未覆盖的一侧沿用 {@link Market} 的两个常量。
 *
 * @param anchor 区级参考价锚格；单区 = 规范序第一个有市场的 hex；不得为 null
 * @param referencePrices 商品 → 区级参考价（毫计价货币/商品单位；空 = 沿用各 hex {@code Market.prices}；值必须 &gt; 0）
 * @param bidPerMille 卖方挂牌底价（‰；0 = 沿用 {@link Market#BID_PER_MILLE}）
 * @param askPerMille 买方挂牌限价（‰；0 = 沿用 {@link Market#ASK_PER_MILLE}）
 * @param quotaPerWindow 商品 → 本区本轮卖方成交量上限（毫商品；空 = 无配额；0 = 配额用尽）
 * @param tariffPerUnit 商品 → 单位税费读数（毫计价货币/商品单位；空/0 = 无税费；本批只累计读数，不搬钱）
 * @param open false = 该区本轮不撮合（返回空 {@code MarketReport}，不抛）
 * @param rules 只读制度标签（保序；空 = 没有标签）
 */
public record MarketRegulation(
    HexCoord anchor,
    Map<CommodityId, Long> referencePrices,
    long bidPerMille,
    long askPerMille,
    Map<CommodityId, Long> quotaPerWindow,
    Map<CommodityId, Long> tariffPerUnit,
    boolean open,
    List<String> rules) {

  /** {@link #none()} 的单例（不可变 record，可安全共享；避免热路径反复分配）。 */
  private static final MarketRegulation NONE = defaults(new HexCoord(0, 0));

  public MarketRegulation {
    Objects.requireNonNull(anchor, "anchor");
    referencePrices = freeze(referencePrices, "referencePrices");
    quotaPerWindow = freeze(quotaPerWindow, "quotaPerWindow");
    tariffPerUnit = freeze(tariffPerUnit, "tariffPerUnit");
    rules = rules == null ? List.of() : List.copyOf(rules);
    if (bidPerMille < 0L || askPerMille < 0L) {
      throw new IllegalArgumentException(
          "MarketRegulation 的 bid/ask（‰）不得为负（0 = 沿用 Market 常量）: "
              + bidPerMille
              + "/"
              + askPerMille);
    }
  }

  /**
   * ★★ <b>出厂默认</b>：与现状逐值相同。{@code referencePrices}/{@code quotaPerWindow}/
   * {@code tariffPerUnit} 空、{@code bidPerMille}/{@code askPerMille} = 0、{@code open = true}、{@code rules} 空。
   */
  public static MarketRegulation defaults(HexCoord anchor) {
    return new MarketRegulation(anchor, Map.of(), 0L, 0L, Map.of(), Map.of(), true, List.of());
  }

  /**
   * ★★ <b>单区默认锚格</b>：取 {@code markets} 规范序（q, r 升序）第一个 hex；空 markets ⇒ {@link #none()}。
   *
   * <p>★ 它只做"锚格在哪"这一件事：单区语义（全部市场格归一个区）由 {@link MarketTopology#singleRegion} 承担；
   * 本方法不猜币种、不拆区。
   */
  public static MarketRegulation defaultsFor(Map<HexCoord, Market> markets) {
    Objects.requireNonNull(markets, "markets");
    HexCoord anchor = canonicalFirst(markets.keySet());
    return anchor == null ? none() : defaults(anchor);
  }

  /**
   * ★ <b>没有调控</b>：占位锚格（{@link HexCoord#HexCoord(int, int) (0, 0)}）上的默认实例，且
   * {@link #defined()} 为 {@code false}。调用方（{@code MarketSettlement} 的旧入口 / {@code MarketReadout}）
   * 用它表达"这一轮没有区级调控"——此时 {@link MarketSettlement} 逐值走原路径。
   */
  public static MarketRegulation none() {
    return NONE;
  }

  /** 这一轮有没有真要施加的调控（默认实例 ⇒ false）。 */
  public boolean defined() {
    return !referencePrices.isEmpty()
        || !quotaPerWindow.isEmpty()
        || !tariffPerUnit.isEmpty()
        || bidPerMille != 0L
        || askPerMille != 0L
        || !open
        || !rules.isEmpty();
  }

  /**
   * ★ <b>该 hex 的商品参考价</b>：{@link #referencePrices} 覆盖优先；缺项回退该 hex
   * {@link Market#priceOf(CommodityId)}（0 = 本格不交易它，不凭空造一行）。
   */
  public long referencePriceOf(Market market, CommodityId commodity) {
    Objects.requireNonNull(market, "market");
    Objects.requireNonNull(commodity, "commodity");
    Long regulated = referencePrices.get(commodity);
    return regulated != null ? regulated : market.priceOf(commodity);
  }

  /**
   * ★ <b>该 hex 商品的卖方挂牌底价</b>：{@code max(1, ⌊有效参考价 × bid‰ ÷ 1000⌋)}；{@code bidPerMille == 0} 时
   * 与 {@link Market#bidPriceOf} 逐值同源。
   */
  public long bidPriceOf(Market market, CommodityId commodity) {
    long price = referencePriceOf(market, commodity);
    if (price <= 0L) {
      return 0L;
    }
    long rate = bidPerMille != 0L ? bidPerMille : Market.BID_PER_MILLE;
    return Math.max(1L, price * rate / 1000L);
  }

  /**
   * ★ <b>该 hex 商品的买方挂牌限价</b>：{@code max(bid + 1, ⌈有效参考价 × ask‰ ÷ 1000⌉)}；{@code askPerMille == 0}
   * 时与 {@link Market#askPriceOf} 逐值同源。
   */
  public long askPriceOf(Market market, CommodityId commodity) {
    long price = referencePriceOf(market, commodity);
    if (price <= 0L) {
      return 0L;
    }
    long rate = askPerMille != 0L ? askPerMille : Market.ASK_PER_MILLE;
    long ask = (price * rate + 999L) / 1000L;
    return Math.max(bidPriceOf(market, commodity) + 1L, ask);
  }

  /** 冻结一张商品 → 长整数表（键/值不得为 null、值不得为负；保序不可变）。 */
  private static Map<CommodityId, Long> freeze(Map<CommodityId, Long> table, String field) {
    if (table == null) {
      return Map.of();
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "MarketRegulation." + field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "MarketRegulation."
                + field
                + " 的值不得为负: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  /** 规范序（q, r 升序）第一个格；空集/全 null ⇒ null。 */
  private static HexCoord canonicalFirst(Iterable<HexCoord> hexes) {
    List<HexCoord> sorted = new ArrayList<>();
    for (HexCoord hex : hexes) {
      if (hex != null) {
        sorted.add(hex);
      }
    }
    if (sorted.isEmpty()) {
      return null;
    }
    sorted.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    return sorted.get(0);
  }
}
