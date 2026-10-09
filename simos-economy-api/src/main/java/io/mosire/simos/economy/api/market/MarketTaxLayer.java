package io.mosire.simos.economy.api.market;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>三层税的"层"（2026-10-10 P-T1b；口岸设计书 §13 + 全链计划 §2.2/§3 I-C3）</b>—— 读口的"层"列就是它。
 *
 * <pre>
 * PORT_EXIT       出口税：源区管辖政府收（货离开源区时收一次）
 * PORT_ENTRY      进口税：目的区管辖政府收（货进目的区时收一次）
 * IN_ZONE_MARKET  区内市场税：该区管辖政府收（既有 MarketRegulation.tariffPerUnit，P-T1b 起真收款）
 * </pre>
 *
 * <p>★★ <b>每层只收一次（I-C3）</b>：一笔成交的这三层<b>各自</b>只算一次、只铸一条钱腿（多政府共管一个区时， 一层的总额再按暴露边权重分摊给各政府国库 ——
 * 分摊不产生第二层，见 {@code PortTaxInput.GovernmentShare}）。
 *
 * <p>★ <b>为什么层要具名而不是"三条腿"</b>：读口必须能回答"这笔货被抽了哪些税、各多少、进哪个国库、什么币" （计划
 * §2.5）；用序号/位置表达，读账时分不出"这是出口税还是进口税"，而那正是要能分开的两件事。
 *
 * <p>★ 字面量一律小写下划线；{@link #parse} <b>不做归一</b>（写错必须当场炸并看得见全部合法值）。
 */
public enum MarketTaxLayer {

  /** 出口税：源区管辖政府收（跨区过境的<b>第一道</b>税）。 */
  PORT_EXIT("port_exit"),

  /** 进口税：目的区管辖政府收（跨区过境的<b>第二道</b>税）。 */
  PORT_ENTRY("port_entry"),

  /** 区内市场税：本区管辖政府收（{@code MarketRegulation.tariffPerUnit}，区内成交抽一次）。 */
  IN_ZONE_MARKET("in_zone_market");

  private final String value;

  MarketTaxLayer(String value) {
    this.value = value;
  }

  /** 规范字面量（小写下划线）：进读数/日志/线格式的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<MarketTaxLayer> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，不归一：归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static MarketTaxLayer parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MarketTaxLayer 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (MarketTaxLayer layer : values()) {
      if (layer.value.equals(text)) {
        return layer;
      }
      legal.add(layer.value);
    }
    throw new IllegalArgumentException("未登记的税层: " + text + "；合法值: " + legal);
  }
}
