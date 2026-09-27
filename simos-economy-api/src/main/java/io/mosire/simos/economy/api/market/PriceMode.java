package io.mosire.simos.economy.api.market;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>市场报价模式</b>（M2.6 的第一版与可选自适应）—— 它是<b>读数契约</b>的一部分：报表必须能让读的人分清"这是固定价，还是
 * 结算按供需调过价"，否则同一列数字在两种模式下的含义完全不同。
 *
 * <p>★★ <b>模式只有一个开关</b>：{@code MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED}（默认 {@code false} =
 * {@link #FIXED}）。本枚举是那个开关的稳定读侧拼法，GUI / MCP 都读它，不各自解释一个布尔。
 *
 * <ul>
 *   <li>{@link #FIXED}（{@code fixed}）—— 本轮固定报价：价格表是 GM 数据，结算只按它过滤/成交，不改它；
 *   <li>{@link #ADAPTIVE}（{@code adaptive}）—— 可选自适应：每轮结算后按 {@code z = clamp((有预算且合限价的需求 − 可出售供给) /
 *       max(需求 + 供给, ε), −1, 1)}、{@code p_next = max(p_min, round(p × (1 + α·z)))} 写回价格表。
 * </ul>
 *
 * <p>★ 字面量一律小写下划线（与 {@link MarketUnfilledReason}/{@code TransferReason} 同制）；{@link #parse} 不做归一 ——
 * 写错一个档必须当场炸并看得见全部合法值。
 */
public enum PriceMode {

  /** 固定报价：价格表是数据，结算不改价（M2.6 第一版；默认）。 */
  FIXED("fixed"),

  /** 自适应报价：按供需 z 每轮调价（M2.6 可选；默认关；α ≤ 5%/轮、定点整数）。 */
  ADAPTIVE("adaptive");

  private final String value;

  PriceMode(String value) {
    this.value = value;
  }

  /** 规范字面量（小写下划线）：进读数/线格式的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<PriceMode> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，不归一：归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static PriceMode parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("PriceMode 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (PriceMode mode : values()) {
      if (mode.value.equals(text)) {
        return mode;
      }
      legal.add(mode.value);
    }
    throw new IllegalArgumentException("未登记的报价模式: " + text + "；合法值: " + legal);
  }
}
