package io.mosire.simos.economy.api.relation;

import java.util.Arrays;

/**
 * ★★ <b>补偿规则的「池怎么算」（裁定 D5-B 拆出的正交字段之一）</b>：一条规则要从<b>哪一层数量</b>里取钱/取物。
 *
 * <p>★★ <b>它为什么从 {@code Basis} 里拆出来</b>：旧的单个 {@code basis} 把两件事揉成了一个词 —— 「<b>池怎么算</b>」（毛产 / 净产 /
 * 经营剩余 / 固定额）与「<b>池在受方之间怎么分</b>」（不分 / 按劳动量 / 平均）。 揉在一起的代价是<b>可读性直接撒谎</b>：{@code LABOR_AMOUNT}
 * 这个名字听起来是"按劳动量算数量"，而它的公式是 {@code 净产 × 率 × 本受方劳动 ÷ Σ劳动} ——
 * 池是<b>净产</b>、劳动只是<b>权重</b>。源码注释里已经发生过一次这个误读 （见 {@code RegimeRelations.feudalRules()} 的那条更正）。⇒ 拆成
 * {@link Pool} × {@link Weight} 两个字段，各只有一处拼写点。
 *
 * <p>★★ <b>旧五档 {@link Basis} 的逐档映射（可查表；{@link Basis#pool()} / {@link Basis#weight()} 是它的可执行形态）</b>：
 *
 * <table border="1">
 *   <caption>旧 basis（五档） → pool × weight</caption>
 *   <tr><th>旧 {@code basis}</th><th>{@link Pool}</th><th>{@link Weight}</th></tr>
 *   <tr><td>{@code GROSS_OUTPUT}</td><td>{@link #GROSS_OUTPUT}</td><td>{@link Weight#NONE}</td></tr>
 *   <tr><td>{@code NET_AFTER_INPUTS}</td><td>{@link #NET_AFTER_INPUTS}</td><td>{@link Weight#NONE}</td></tr>
 *   <tr><td>{@code OPERATOR_SURPLUS}</td><td>{@link #OPERATOR_SURPLUS}</td><td>{@link Weight#NONE}</td></tr>
 *   <tr><td>{@code LABOR_AMOUNT}</td><td>{@link #NET_AFTER_INPUTS}</td><td>{@link Weight#LABOR_AMOUNT}</td></tr>
 *   <tr><td>{@code FIXED_AMOUNT}</td><td>{@link #FIXED_AMOUNT}</td><td>{@link Weight#NONE}（权重不适用）</td></tr>
 * </table>
 *
 * <p>★ <b>第 6 档 {@code ASSET_QUANTITY} 不在这张表里，且不许加回来</b>（H0.5 已整块退役：它读的产权表在真档零写入者 ⇒ 那一档恒
 * 0，属本仓明文反对的"看起来在记、其实永远不被读"）。资产真要进来时，以<b>产业产能</b>表达"用多少"， 而不是复活一个恒 0 的档。
 *
 * <p>★ <b>本类型不做 (type × pool × weight) 的组合守卫</b>（有意为之）：哪些组合有公式，是 {@code ProductionSettlement}
 * 的公式表说了算 —— 在这里先禁掉一批组合，等于把"还没定的设计"写进契约。 唯一的例外是<b>结构性的那一条</b>：{@link #FIXED_AMOUNT} 没有"按什么权重分"这一维（见
 * {@link CompensationRule} 的构造期守卫）。
 *
 * <p>★ <b>fail-closed 的词表</b>：同 {@link RuleType#parse(String)}，词表外即抛并列出合法值。
 */
public enum Pool {

  /** 总产出（毛产）：不扣任何东西。 */
  GROSS_OUTPUT,

  /** 扣投入后的净产出。★ 旧 {@code LABOR_AMOUNT} 的池也是它（劳动只是权重）。 */
  NET_AFTER_INPUTS,

  /** 经营剩余：**已按 priority 序付出去之后**剩下的那一层（⇒ 次序成了数据，见 {@code CompensationRule.priority}）。 */
  OPERATOR_SURPLUS,

  /**
   * ★ <b>固定额</b>：数量 = {@code CompensationRule.fixedAmount}，与产出、劳动<b>都无关</b>。
   *
   * <p>★ 它是 {@code FIXED_IN_KIND_RENT} / {@code FIXED_IN_KIND_PER_LABOR} / {@code FIXED_MONEY_*}
   * 的落点； ★ <b>权重对它不适用</b>（固定额没有"按什么分"这一维）⇒ 只许配 {@link Weight#NONE}。
   */
  FIXED_AMOUNT;

  /** 按词表解析：<b>词表外的输入即抛，消息列出全部合法值</b>（fail-closed，同 {@link RuleType#parse(String)} 的口径）。 */
  public static Pool parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Pool 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("未登记的数量池: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}
