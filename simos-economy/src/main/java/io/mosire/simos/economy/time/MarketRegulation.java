package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * ★★ <b>区内市场规则（设计书 §15/§16 降级后）</b>：只保留<b>收税</b>一类条目 —— 逐区施加一次。它是<b>纯值类型</b>—— 不落盘、不进 {@code
 * EconomyData}/Codec/ChangeSet，本批只作为逐轮瞬态传入 {@link MarketSettlement.MarketRound}。
 *
 * <p>★★ <b>2026-10-10 大删（用户裁定，设计书 §16）</b>：本类原有七项条目，只留下"收税"：
 *
 * <ul>
 *   <li>{@code referencePrices}（区级参考价覆盖）—— <b>删</b>：定价只看逐格 {@link Market#prices()}（"有没有定价" = {@link
 *       Market#hasPrice}），不再有区级统一定价这一层；
 *   <li>{@code open}（关市）—— <b>删</b>：用户原话"市场你说关就关？没有这种事"；
 *   <li>{@code rules}（制度标签）—— <b>删</b>：只读标签，零行为；
 *   <li>{@code quotaPerWindow}（配额）—— <b>删</b>：用户原话"配额也删了"，连同 {@link MarketSettlement.MatchContext}
 *       的逐区配额表与扣减点一并撤；
 *   <li>{@code bidPerMille}/{@code askPerMille}（区级限价覆盖）—— <b>删</b>：用户原话"限价让政府决策人自己操控政府账户高价买低价卖，
 *       低价政府不可能直接限制住的"。★ 逐格价表自己的限价（{@link Market#BID_PER_MILLE}/{@link Market#ASK_PER_MILLE} 与
 *       {@link Market#bidPriceOf}/{@link Market#askPriceOf}）<b>保留不动</b>—— 那是市场自身的价差，不是区级管制。
 * </ul>
 *
 * <p>★★ <b>缺省语义中性（I-C2）</b>：{@link #tariffPerUnit} 空 ⇒ {@link #defined()} 为 false ⇒ 不产生任何税费读数 ⇒
 * 没有政策的世界与改前逐值相同。<b>这不是旧档兼容位</b>（§一.11：旧档直接重建，不写归一/兼容/忽略）， 而是新契约的缺省语义。
 *
 * <p>★ <b>后续批次</b>：政府采购优先级参数（§16.3，P-T1d）将加在本类；三层税真收款（§13，P-T1b）落在此处的 {@code tariffPerUnit} 上。
 *
 * @param anchor 区级锚格；单区 = 规范序第一个有市场的 hex；不得为 null
 * @param tariffPerUnit 商品 → 单位税费（毫计价货币/商品单位；空 = 无税费；值必须 ≥ 0）
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "compact constructor 经 freeze(...) 防御性拷贝并冻结（Collections.unmodifiableMap）；SpotBugs 不跨辅助方法识别")
public record MarketRegulation(HexCoord anchor, Map<CommodityId, Long> tariffPerUnit) {

  /** {@link #none()} 的单例（不可变 record，可安全共享；避免热路径反复分配）。 */
  private static final MarketRegulation NONE = defaults(new HexCoord(0, 0));

  public MarketRegulation {
    Objects.requireNonNull(anchor, "anchor");
    tariffPerUnit = freeze(tariffPerUnit, "tariffPerUnit");
  }

  /** ★★ <b>出厂默认</b>：没有税费（{@code tariffPerUnit} 空）。 */
  public static MarketRegulation defaults(HexCoord anchor) {
    return new MarketRegulation(anchor, Map.of());
  }

  /**
   * ★★ <b>单区默认锚格</b>：取 {@code markets} 规范序（q, r 升序）第一个 hex；空 markets ⇒ {@link #none()}。
   *
   * <p>★ 它只做"锚格在哪"这一件事：单区语义（全部市场格归一个区）由 {@link MarketTopology#singleRegion} 承担； 本方法不猜币种、不拆区。
   */
  public static MarketRegulation defaultsFor(Map<HexCoord, Market> markets) {
    Objects.requireNonNull(markets, "markets");
    HexCoord anchor = canonicalFirst(markets.keySet());
    return anchor == null ? none() : defaults(anchor);
  }

  /**
   * ★ <b>没有规则</b>：占位锚格（{@link HexCoord#HexCoord(int, int) (0, 0)}）上的默认实例，且 {@link #defined()} 为
   * {@code false}。调用方用它表达"这一轮没有区内市场规则"——此时不产生任何税费读数。
   */
  public static MarketRegulation none() {
    return NONE;
  }

  /** 这一轮有没有真要施加的条目（默认实例 ⇒ false）。 */
  public boolean defined() {
    return !tariffPerUnit.isEmpty();
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
            "MarketRegulation." + field + " 的值不得为负: " + entry.getKey() + " = " + entry.getValue());
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
