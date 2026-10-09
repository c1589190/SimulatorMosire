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
 *   <li>{@link #LOAN_REPAYMENT} —— 偿还（★ H5 起真的有写者：周期末按本期所得的比例先偿债， {@code FlowRow.repaid} 因此第一次有了非 0
 *       值）。
 * </ul>
 */
public enum TransferReason {

  /** 净产出归经营者（★ 见类注：今天由产出计提读数表达，不走 {@code Transfer}）。 */
  PRODUCTION_OUTPUT("production_output"),

  /** 按 {@code ProductionRules} 的分成 / 给养 / 地租（制度规定的分配）。 */
  RELATION_PAYMENT("relation_payment"),

  /** 同格按需取材（生产投入的征调：田里的纤维 → 同格织机）。 */
  INPUT_REQUISITION("input_requisition"),

  /** 借粮的本金（要还的信用：债权人 → 债务人）。 */
  LOAN_PRINCIPAL("loan_principal"),

  /** ★★ 偿还本金（H5 起**真的有写者**：{@code 旧结算引擎（R3a 已删除）.repayDebts} 铸 {@code 债务人 → 债权人} 的粮腿）。 */
  LOAN_REPAYMENT("loan_repayment"),

  /** 同格市场成交（H4：买方付钱、卖方交货 —— 一笔买卖铸<b>一对</b>转移，见 {@code Transfer} 的货币腿口径）。 */
  MARKET_TRADE("market_trade"),

  /**
   * ★★ <b>运费</b>（M2.4/M2.5：买方 → 承运主体的货币腿；{@code ActorKind.ORGANIZATION}）。
   *
   * <p>★ <b>为什么单独一档</b>：运费不是"货款的一部分"——它是运输这件事的对价，收款方是承运人而不是卖方。混进 {@code MARKET_TRADE}
   * 会让读账时分不出"货卖了多少钱"与"路花了多少钱"（M2.4 的判据要求运费总额可读）。★ <b>世界里没有承运 actor 时不铸这条腿</b>（没有收款方就不收，禁钱凭空消失）。
   */
  CARRIER_FEE("carrier_fee"),

  /**
   * ★★ <b>阶段 2-A2a：家户 ↔ 家户的外汇成交腿</b>（约束设计书 §3.2）—— 一笔 FX 成交 = <b>两条</b> {@code FX_TRADE}：base 腿（卖方
   * → 买方）与 quote 腿（买方 → 卖方）。
   *
   * <p>★ <b>为什么不复用 {@code MARKET_TRADE}</b>：读账时"这笔钱是买货付的还是换汇付的"必须分得开（I20 的逐币种守恒按腿核）。
   */
  FX_TRADE("fx_trade"),

  /**
   * ★★ <b>阶段 2-A2a：政府外汇窗口的成交腿</b>（约束设计书 §3.4）—— 与 {@link #FX_TRADE} 同形（两条腿），只是其中一端是政府国库。
   *
   * <p>★ 单列一档是为了让"官方汇率拉动了多少量"这条读数可核（§4.5：官方汇率只在窗口有量时才拉动市场价）。
   */
  GOV_FX_WINDOW("gov_fx_window"),

  /**
   * ★★ <b>P-T1b：跨区过境的<b>出口税</b></b>（口岸设计书 §13；全链计划 §2.2）—— 买方 → <b>源区</b>管辖政府的国库户。
   *
   * <p>★★ <b>为什么单列一档</b>：读账时必须分得开"货卖了多少钱"（{@link #MARKET_TRADE}）、"路花了多少钱" （{@link
   * #CARRIER_FEE}）、"被抽了哪一层税"（本档 / {@link #PORT_TAX_ENTRY} / {@link #MARKET_TAX_IN_ZONE}）——
   * 三层税的收款方各不相同（源区 / 目的区 / 本区），混进货款会让"这笔货被抽了多少"再也读不出来。
   *
   * <p>★ <b>不是发行</b>：买方多付、卖方仍收原价，差额<b>转移</b>进国库 ⇒ 货币守恒（不销毁、不铸币）。
   */
  PORT_TAX_EXIT("port_tax_exit"),

  /**
   * ★★ <b>P-T1b：跨区过境的<b>进口税</b></b>（口岸设计书 §13）—— 买方 → <b>目的区</b>管辖政府的国库户。
   *
   * <p>★ 与 {@link #PORT_TAX_EXIT} 分开的理由同它：同一票货的两道税进的是<b>两个不同政府</b>的国库。
   */
  PORT_TAX_ENTRY("port_tax_entry"),

  /**
   * ★★ <b>P-T1b：<b>区内市场税</b></b>（全链计划 §2.2 的第三层；既有 {@code MarketRegulation.tariffPerUnit}） —— 买方 →
   * <b>该区</b>管辖政府的国库户。
   *
   * <p>★ <b>与口岸税的区别</b>：口岸税是"跨过区界"这件事的对价（源区 + 目的区各一道）；本档是"在这个区的市场上成交"
   * 这件事的对价（该区一道）。跨区成交不再叠加本档（三层各收一次，不重复收）。
   *
   * <p>★ <b>P-T1b 之前的语义</b>：这条税<b>只累计读数、不搬钱</b>（{@code MarketReport.regulatedTariffByCurrency()}）；
   * 本档是它"真收款"之后的具名原因。
   */
  MARKET_TAX_IN_ZONE("market_tax_in_zone");

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
