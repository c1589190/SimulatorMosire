package io.mosire.simos.economy.api.transfer;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>一笔转移的「制度原因」词表</b>（H2；裁定 D2-A 搬来 {@code Transfer} 形状、本批新造这一维）。
 *
 * <p>★★ <b>为什么转移必须自带原因</b>：同一批货从 A 到 B，可能是"分成"（制度规定的分配）、可能是"取料"（生产投入的
 * 征调）、可能是"借粮"（要还的信用）、将来还可能是"买卖"（市场交换）。<b>没有这一维，四件事在账上是同一件事</b> —— 而它们
 * 的判据完全不同（分成要看制度、取料要看瓶颈、借粮要看债权、买卖要看价格）。★ 本仓明文反对"看起来在记、其实分不出"的账， 故原因是<b>必填</b>的。
 *
 * <p>★★ <b>规范字面量 = {@link #value()} 的小写形式</b>（与 {@code ResidenceKind} 同制：规范串那一段用 {@code value()}， 而
 * {@code parse} 只认它、<b>不做归一</b>）—— 归一是"猜"，写错一个档必须<b>当场炸</b>并看得见全部合法值。
 *
 * <p>★★ <b>本批（H4）真正被写出来的有五档</b>： {@link #RELATION_PAYMENT}（关系实付：实物与<b>货币</b>两族）· {@link
 * #INPUT_REQUISITION}（投入征调）· {@link #LOAN_PRINCIPAL}（同格借粮）· {@link #MARKET_TRADE}（同格市场成交： 一笔买卖 =
 * <b>一对</b>转移，货一条、钱一条 —— 见 {@code Transfer} 的货币腿口径）。 其余两档是<b>留位</b>（如实记，不假装已经用上）：
 *
 * <ul>
 *   <li>{@link #PRODUCTION_OUTPUT} —— "净产入 operator"。★ 今天它<b>不是一条转移</b>：产出是<b>造出来</b>的， 没有对端（{@code
 *       Transfer} 的两端恒为 actor、且不许相等）⇒ 净产仍走 {@code ProductionLedger} 的产出计提读数。
 *       这一档留给"产出的对端是一个与经营者不同的主体"（如独立的生产单位 actor）那种形态；
 *   <li>{@link #LOAN_REPAYMENT} —— 偿还（H5；今天 {@code FlowRow.repaid} 恒 0）。
 * </ul>
 */
public enum TransferReason {

  /** 净产出归经营者（★ 见类注：今天由产出计提读数表达，不走 {@code Transfer}）。 */
  PRODUCTION_OUTPUT("production_output"),

  /** 按 {@code ProductionRelation} 的分成 / 给养 / 地租（制度规定的分配）。 */
  RELATION_PAYMENT("relation_payment"),

  /** 同格按需取材（生产投入的征调：田里的纤维 → 同格织机）。 */
  INPUT_REQUISITION("input_requisition"),

  /** 借粮的本金（要还的信用：债权人 → 债务人）。 */
  LOAN_PRINCIPAL("loan_principal"),

  /** 偿还本金（H5；本批只留档）。 */
  LOAN_REPAYMENT("loan_repayment"),

  /** 同格市场成交（H4：买方付钱、卖方交货 —— 一笔买卖铸<b>一对</b>转移，见 {@code Transfer} 的货币腿口径）。 */
  MARKET_TRADE("market_trade");

  private final String value;

  TransferReason(String value) {
    this.value = value;
  }

  /** 规范字面量（小写）：进规范串的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<TransferReason> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，同 {@code ResidenceKind.parse} 的口径：不归一，归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static TransferReason parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("TransferReason 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (TransferReason reason : values()) {
      if (reason.value.equals(text)) {
        return reason;
      }
      legal.add(reason.value);
    }
    throw new IllegalArgumentException("未登记的转移原因: " + text + "；合法值: " + legal);
  }
}
