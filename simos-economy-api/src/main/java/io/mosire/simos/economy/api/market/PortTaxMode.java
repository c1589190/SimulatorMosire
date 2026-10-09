package io.mosire.simos.economy.api.market;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>口岸税的计量方式（2026-10-10 追加裁定 5 §14.5：用户原话「我建议是规则可以灵活，从量从价都行」）</b>。
 *
 * <p>★★ <b>每条规则各自指定</b>（不是全局开关）：同一个政府的两个类、同一类的入口税与出口税，都可以各写各的方式； <b>缺省 = {@link #NONE}</b>（不收税）⇒
 * 逐值退回改前行为（I-P8/I-C2）。
 *
 * <p>★ <b>单位写进字面量</b>（本仓惯例）：{@link #PER_UNIT_MILLI} 的量纲是<b>毫（计价货币）/ 商品单位</b>， {@link
 * #AD_VALOREM_PER_MILLE} 的量纲是<b>货值的千分比（‰）</b>——两者都由 {@link PortTaxRule#amount()}
 * 承载，靠本枚举区分量纲，<b>不靠猜</b>。
 *
 * <p>★ 字面量一律小写下划线，{@link #parse} <b>不做归一</b>（写错必须当场炸并看得见全部合法值）。
 */
public enum PortTaxMode {

  /** 不收税（缺省）：{@code amount} 必须为 0（非 0 是非法规则，构造期具名拒）。 */
  NONE("none"),

  /** 从量：{@code amount} = <b>毫（计价货币）/ 商品单位</b>。 */
  PER_UNIT_MILLI("per_unit_milli"),

  /** 从价：{@code amount} = <b>货值的千分比（‰）</b>（只对货值计税；运费不计税，§13.5 T-2）。 */
  AD_VALOREM_PER_MILLE("ad_valorem_per_mille");

  private final String value;

  PortTaxMode(String value) {
    this.value = value;
  }

  /** 规范字面量（小写下划线）：进载荷/读数/线格式的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<PortTaxMode> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，不归一：归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static PortTaxMode parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("PortTaxMode 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (PortTaxMode mode : values()) {
      if (mode.value.equals(text)) {
        return mode;
      }
      legal.add(mode.value);
    }
    throw new IllegalArgumentException("未登记的口岸税计量方式: " + text + "；合法值: " + legal);
  }
}
