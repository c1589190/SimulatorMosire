package io.mosire.simos.economy.api.relation;

import java.util.Arrays;

/**
 * ★★ <b>补偿规则的「池在受方之间怎么分」（裁定 D5-B 拆出的正交字段之二）</b>。
 *
 * <p>★ <b>与 {@link Pool} 正交</b>：池说"从哪一层数量取"，权重说"取出来的这一份在多个受方之间怎么摊"。两者各自只有一处拼写点 —— 旧的那个单 {@code
 * basis} 把两件事揉在一起，于是 {@code LABOR_AMOUNT} 这个名字既像"池是劳动"、又像"按劳动分"。
 *
 * <p>★★ <b>本批的成员与它们的状态（如实记，不假装已经用上）</b>：
 *
 * <ul>
 *   <li>{@link #NONE} —— <b>不分</b>：这条规则自己请求整份池（{@code 池 × rate}），不按任何权重摊。今天四条搬运路径里的 绝大多数规则都是它；
 *   <li>{@link #LABOR_AMOUNT} —— 按<b>本受方劳动 ÷ Σ劳动</b> 摊（{@code LABOR_AMOUNT} 那一族的唯一实现）。 ★ 它只与 {@link
 *       Pool#NET_AFTER_INPUTS} 组合有公式（旧 {@code LABOR_AMOUNT} 的映射，见 {@link Pool} 的类注）；
 *   <li>{@link #EQUAL} —— <b>平均分（留位）</b>：今天<b>没有任何规则用它</b>，公式表里也没有它的落点（组合一写出来就当场抛）。
 *       留着是因为它是"权重"这一维上唯一还没落地的常见形态（平分公产、均分救济），删掉会让"权重"看起来只有一档。
 * </ul>
 *
 * <p>★ <b>{@code ASSET_QUANTITY} 不在这里，且不许加回来</b>：它随产权表在 H0.5 整块退役（真档零写入者 ⇒ 恒 0）。
 *
 * <p>★ <b>fail-closed 的词表</b>：词表外即抛并列出合法值（同 {@link RuleType#parse(String)} 的口径）。
 */
public enum Weight {

  /** 不分：请求整份池（不按任何权重摊）。 */
  NONE,

  /** 按受方的劳动量摊（{@code 本受方劳动 ÷ Σ劳动}）。 */
  LABOR_AMOUNT,

  /** 平均分（★ <b>留位</b>：今天没有规则用它，公式表里也没有落点）。 */
  EQUAL;

  /** 按词表解析：<b>词表外的输入即抛，消息列出全部合法值</b>（fail-closed）。 */
  public static Weight parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Weight 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("未登记的权重: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}
