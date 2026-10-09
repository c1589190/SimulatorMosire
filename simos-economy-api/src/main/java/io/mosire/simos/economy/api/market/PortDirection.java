package io.mosire.simos.economy.api.market;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>口岸规则/执行规律的方向维（2026-10-10 追加裁定 3 §12：出入都设规则拦，两侧都要过）</b>。
 *
 * <p>★★ <b>两个方向的语义（别再混）</b>：
 *
 * <ul>
 *   <li>{@link #ENTRY}（<b>入口</b>）：货/钱<b>进入</b>本市场区（<b>本区是目的地</b>）⇒ 用<b>目的区</b>这一侧的规则与效率；
 *   <li>{@link #EXIT}（<b>出口</b>）：货/钱<b>离开</b>本市场区（<b>本区是来源地</b>）⇒ 用<b>源区</b>这一侧的规则与效率。
 * </ul>
 *
 * <p>★★ <b>跨区过境 = 两道闸依次都要过</b>（用户原话「不管是效率漏了走私的，还是通过的，都需要两边都过才能跨区」）：
 *
 * <pre>
 * 可通过比例 = E_源(EXIT) × E_目的(ENTRY) ÷ 1,000,000      （串行 = 相乘，不是取 min；见 §12.3）
 * </pre>
 *
 * ★ <b>只有一侧是市场区</b>（对面三不管／无区）：<b>没有政府的那一侧开放度 = 1000‰</b>（没有口岸可管）， 实际由有规则的那一侧决定（§12.2-4）。
 *
 * <p>★ 字面量一律小写下划线（与 {@code MarketUnfilledReason}/{@code ResidenceKind} 同制）， {@link #parse}
 * <b>不做归一</b>——写错一个档必须当场炸并看得见全部合法值。
 */
public enum PortDirection {

  /** 入口：本区是<b>目的地</b>，用本区（目的区）这一侧的规则与口岸效率。 */
  ENTRY("entry"),

  /** 出口：本区是<b>来源地</b>，用本区（源区）这一侧的规则与口岸效率。 */
  EXIT("exit");

  private final String value;

  PortDirection(String value) {
    this.value = value;
  }

  /** 规范字面量（小写下划线）：进载荷/读数/线格式的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 另一侧（一票货的两道闸 = {@code d} 与 {@code d.opposite()}）。 */
  public PortDirection opposite() {
    return this == ENTRY ? EXIT : ENTRY;
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<PortDirection> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，不归一：归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static PortDirection parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("PortDirection 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (PortDirection direction : values()) {
      if (direction.value.equals(text)) {
        return direction;
      }
      legal.add(direction.value);
    }
    throw new IllegalArgumentException("未登记的口岸方向: " + text + "；合法值: " + legal);
  }
}
