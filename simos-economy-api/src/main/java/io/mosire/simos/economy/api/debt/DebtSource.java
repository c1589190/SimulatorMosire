package io.mosire.simos.economy.api.debt;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>债务来源</b>（D-032 D3 §2.9.1，R-11）。
 *
 * <p>仅凭 {@link DebtTerms} 不能可靠区分工资欠款与普通贷款（普通贷款也可能是 0‰）；本枚举是合同值上的 <b>可持久来源维</b>，参与 codec / ChangeSet
 * / 债务表往返。工资债必须同时带 {@link #WAGE_ARREARS} 与 {@link DebtTerms#wageArrearsDefault()}（0‰）；普通贷款保留各自的
 * {@code terms} 并写 {@link #ORDINARY_LOAN}。
 *
 * <p>★ {@link #LEGACY_UNKNOWN} 只允许出现在历史/诊断读面：当前写路径不得新写它，也不得据此把未知债务冒充成 工资债或自动改成 0‰。
 */
public enum DebtSource {
  /** 未付工资形成；利率必须 0‰。 */
  WAGE_ARREARS,
  /** 生产结算的欠租；保留既有条款，不是工资。 */
  RENT_ARREARS,
  /** 普通借贷；保留各自原 terms。 */
  ORDINARY_LOAN,
  /** 仅历史/诊断；不得据此冒充工资债或自动改 0‰。 */
  LEGACY_UNKNOWN;

  /** 词表（声明序）。 */
  public static List<DebtSource> all() {
    return List.of(values());
  }

  /**
   * 按规范名解析（大小写敏感；不做归一）。
   *
   * @throws IllegalArgumentException 空白或不在词表里（消息列出全部合法值）
   */
  public static DebtSource parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("debt-source-missing: DebtSource 不得为空白");
    }
    List<String> legal = new ArrayList<>(values().length);
    for (DebtSource source : values()) {
      if (source.name().equals(text)) {
        return source;
      }
      legal.add(source.name());
    }
    throw new IllegalArgumentException("未登记的债务来源: " + text + "；合法值: " + legal);
  }
}
