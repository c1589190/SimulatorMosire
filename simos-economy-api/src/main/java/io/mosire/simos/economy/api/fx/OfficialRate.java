package io.mosire.simos.economy.api.fx;

import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>官方汇率</b>（阶段 2-A2a；约束设计书 §3.3）：一个 GOV 对某一<b>币对</b>给出的买卖双边报价。
 *
 * <pre>
 * OfficialRate(base, quote, buyPerMille, sellPerMille)
 *   buyPerMille  = 政府<b>买入</b> base 时付的 quote（per-mille：每 1000 个 base 最小单位付多少 quote 最小单位）
 *   sellPerMille = 政府<b>卖出</b> base 时收的 quote（同上量纲）
 * </pre>
 *
 * <p>★★ <b>它和实际汇率是两件事（I18）</b>：
 *
 * <ul>
 *   <li>官方汇率是<b>报价</b> —— 由政府（DM 工具 + GM 审批）定，<b>持久状态</b>（A 阶段挂在 {@code Government} 上， 随 {@code
 *       EconomyData.governments} 进 ChangeSet/Codec；B 阶段随市场区迁移）；
 *   <li>实际汇率是<b>读数</b> —— 由市场成交现算（{@link FxMarketRate}），<b>不落盘</b>（I17）；
 *   <li>★ 官方汇率<b>只在窗口有量时才拉动市场价</b>；无 FX 成交时实际汇率必须"无读数"，<b>不得</b>回落到官方汇率冒 充市场价。
 * </ul>
 *
 * <p>★ <b>量纲与 {@link FxOrder#limitPrice()} 逐字相同</b>（per-mille）：这是"报价"与"成交价"能直接比较的唯一前提 （{@code bidP
 * = officialBuy} / {@code askP = officialSell} 是直接赋值，见设计书 §3.4）。
 *
 * <p>★ <b>为什么 {@code buyPerMille} 与 {@code sellPerMille} 都必须 &gt; 0</b>：0 报价不是"免费"而是"说不出价" —— 本仓
 * 不让"没定价"伪装成一个数（需要"停做某一侧"就给 0 容量／停窗口，那是窗口的约束，不是报价）。
 *
 * @param base 标的币（政府买入/卖出的那一种）
 * @param quote 计价币（政府付出/收进的那一种）
 * @param buyPerMille 政府买入 base 的报价（per-mille；&gt; 0）
 * @param sellPerMille 政府卖出 base 的报价（per-mille；&gt; 0）
 */
public record OfficialRate(CurrencyId base, CurrencyId quote, long buyPerMille, long sellPerMille) {

  public OfficialRate {
    Objects.requireNonNull(base, "OfficialRate.base 不得为 null");
    Objects.requireNonNull(quote, "OfficialRate.quote 不得为 null");
    if (base.equals(quote)) {
      throw new IllegalArgumentException(
          "OfficialRate 的 base 与 quote 不得是同一种钱（同币对没有汇率）: " + base.value());
    }
    if (buyPerMille <= 0L) {
      throw new IllegalArgumentException(
          "OfficialRate.buyPerMille 必须 > 0（0 报价 = 说不出价，停做一侧请给 0 容量）: " + buyPerMille);
    }
    if (sellPerMille <= 0L) {
      throw new IllegalArgumentException(
          "OfficialRate.sellPerMille 必须 > 0（0 报价 = 说不出价，停做一侧请给 0 容量）: " + sellPerMille);
    }
    requireNoSeparator(base);
    requireNoSeparator(quote);
  }

  /** 币对键（{@code base|quote}）：{@code Government.officialRates} 的键，也是逐对查找的唯一拼写点。 */
  public String key() {
    return keyOf(base, quote);
  }

  /**
   * 币对键的唯一拼写点（{@link #key()} 与 {@link FxOrder#pairKey()} 都取它）。
   *
   * <p>★ 分隔符 {@code '|'} 在币种 id 里被构造期禁掉（{@link #requireNoSeparator}）⇒ 键到币对的映射是<b>双射</b>， 不会出现
   * {@code a|b} 与 {@code a} + {@code |b} 撞键。
   */
  public static String keyOf(CurrencyId base, CurrencyId quote) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    return base.value() + "|" + quote.value();
  }

  /** 反向解析（读口/旧档自解释用）；形状不对 ⇒ 返回空（不猜）。 */
  public static Optional<OfficialRate> parseEntry(String key, long buyPerMille, long sellPerMille) {
    if (key == null) {
      return Optional.empty();
    }
    int split = key.indexOf('|');
    if (split <= 0 || split == key.length() - 1) {
      return Optional.empty();
    }
    CurrencyId base = CurrencyId.parse(key.substring(0, split));
    CurrencyId quote = CurrencyId.parse(key.substring(split + 1));
    return Optional.of(new OfficialRate(base, quote, buyPerMille, sellPerMille));
  }

  /**
   * 按 {@code (base, quote)} 从"政府持有的官方汇率表"里取一条（键 = {@link #keyOf}）。
   *
   * <p>★ 缺键 ⇒ {@link Optional#empty()}（"这个币对没有官方汇率"是合法状态，不是故障）—— 调用方据此判"窗口不存在"。
   */
  public static Optional<OfficialRate> find(
      Map<String, OfficialRate> rates, CurrencyId base, CurrencyId quote) {
    if (rates == null || rates.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(rates.get(keyOf(base, quote)));
  }

  /** 官方的<b>中间价</b>（读数比较用：{@code (buy + sell) / 2}，向下取整）。 */
  public long midPerMille() {
    return Math.addExact(buyPerMille, sellPerMille) / 2L;
  }

  /** 保序不可变拷贝（键序 = 调用方给的顺序；不用 {@code Map.copyOf}——不承诺保序）。 */
  public static Map<String, OfficialRate> freeze(Map<String, OfficialRate> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, OfficialRate> copy = new LinkedHashMap<>();
    for (Map.Entry<String, OfficialRate> entry : rates.entrySet()) {
      OfficialRate rate = Objects.requireNonNull(entry.getValue(), "officialRates 的值");
      copy.put(entry.getKey(), rate);
    }
    return java.util.Collections.unmodifiableMap(copy);
  }

  private static void requireNoSeparator(CurrencyId currency) {
    if (currency.value().indexOf('|') >= 0) {
      throw new IllegalArgumentException("币种 id 不得含 '|'（它同时是官方汇率的币对键分隔符）: " + currency.value());
    }
  }
}
