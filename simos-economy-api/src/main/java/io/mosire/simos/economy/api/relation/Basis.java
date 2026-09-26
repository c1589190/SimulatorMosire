package io.mosire.simos.economy.api.relation;

import java.util.Arrays;

/**
 * ★★ <b>旧档词表：{@code basis} 的五档（H2 起<b>只活在 codec 边缘</b>）</b>——裁定 D5-B 把它拆成了 {@link Pool} × {@link
 * Weight} 两个正交字段，本枚举保留的唯一职责是<b>让旧档读得回来</b>。
 *
 * <p>★★ <b>它为什么还在</b>（"不许让真档播种当场抛"）：
 *
 * <ul>
 *   <li>**JSON 载荷**里的关系规则写的是 {@code "basis":"GROSS_OUTPUT"}（真档播种的载荷就是这一形状）—— 载入边缘要么认它，要么整个真档播不出来；
 *   <li>**已落盘的 revision** 里同样存着 {@code "basis"} 字面量（关系表住在 {@code EconomyData} 里）—— 读旧档要么认它，要么历史
 *       revision 全部读不回来。
 * </ul>
 *
 * <p>⇒ 旧字面量在**读侧**（载荷边缘 {@code EconomyPayloads} 与 codec 边缘 {@code EconomyCodec}）经 {@link #pool()} /
 * {@link #weight()} 无损翻译成新形状；<b>写侧一律写新形状</b>（{@code pool} + {@code weight}）， 本枚举<b>不再被任何生产代码读</b>。
 *
 * <p>★★ <b>五档 → {@code pool × weight} 的逐档映射（判据就是这张表）</b>：
 *
 * <table border="1">
 *   <caption>旧 basis → pool × weight（★ 逐档可核：{@link #pool()} / {@link #weight()}）</caption>
 *   <tr><th>本枚举</th><th>{@link Pool}</th><th>{@link Weight}</th><th>为什么</th></tr>
 *   <tr><td>{@link #GROSS_OUTPUT}</td><td>{@link Pool#GROSS_OUTPUT}</td><td>{@link Weight#NONE}</td>
 *       <td>池 = 毛产、不分权重</td></tr>
 *   <tr><td>{@link #NET_AFTER_INPUTS}</td><td>{@link Pool#NET_AFTER_INPUTS}</td><td>{@link Weight#NONE}</td>
 *       <td>池 = 净产、不分权重</td></tr>
 *   <tr><td>{@link #OPERATOR_SURPLUS}</td><td>{@link Pool#OPERATOR_SURPLUS}</td><td>{@link Weight#NONE}</td>
 *       <td>池 = 经营剩余、不分权重</td></tr>
 *   <tr><td>{@link #LABOR_AMOUNT}</td><td>{@link Pool#NET_AFTER_INPUTS}</td><td>{@link Weight#LABOR_AMOUNT}</td>
 *       <td>★ 池是<b>净产</b>（{@code net × rate}），劳动只是<b>权重</b>（{@code × 本受方劳动 ÷ Σ劳动}）
 *           —— 旧名字听起来像"池是劳动"，那正是拆分的理由</td></tr>
 *   <tr><td>{@link #FIXED_AMOUNT}</td><td>{@link Pool#FIXED_AMOUNT}</td><td>{@link Weight#NONE}</td>
 *       <td>数量 = {@code fixedAmount}；<b>权重不适用</b>（固定额没有"按什么分"这一维）</td></tr>
 * </table>
 *
 * <p>★★ <b>原第 6 档 {@code ASSET_QUANTITY}（"按资产量"）已整块退役</b>（2026-09-27，H0.5 / 裁定 S3，上文一字不改地留在 git
 * 历史里）：它读的 {@code AssetHolding} 在真档<b>零写入者</b>、结算侧更是硬编码空表 ⇒ 这一档<b>恒 0</b>，
 * 属本仓明文反对的"看起来在记、其实永远不被读"。资产（土地 / 工具 / 牲畜）推迟到真需要时再加：届时以<b>产业产能</b> （{@code
 * Industry.capacity}）表达"用多少"，以一条规则表达"谁拿收益"。⇒ 词表从六档变<b>五档</b>。
 *
 * <p>★ <b>fail-closed 的词表</b>：同 {@link RuleType#parse(String)}，词表外即抛并列出五档。
 */
public enum Basis {

  /** 总产出（毛产）：不扣任何东西。→ {@code GROSS_OUTPUT × NONE}。 */
  GROSS_OUTPUT(Pool.GROSS_OUTPUT, Weight.NONE),

  /** 扣投入后的净产出。→ {@code NET_AFTER_INPUTS × NONE}。 */
  NET_AFTER_INPUTS(Pool.NET_AFTER_INPUTS, Weight.NONE),

  /** 经营剩余：**已按 priority 序付出去之后**剩下的那一层。→ {@code OPERATOR_SURPLUS × NONE}。 */
  OPERATOR_SURPLUS(Pool.OPERATOR_SURPLUS, Weight.NONE),

  /** 按劳动量分（★ 池是<b>净产</b>）。→ {@code NET_AFTER_INPUTS × LABOR_AMOUNT}。 */
  LABOR_AMOUNT(Pool.NET_AFTER_INPUTS, Weight.LABOR_AMOUNT),

  /**
   * ★ <b>固定额（第 5 档，裁定 E5）</b>：数量 = {@code CompensationRule.fixedAmount}，与产出、劳动<b>都无关</b>。 → {@code
   * FIXED_AMOUNT}（权重不适用）。
   */
  FIXED_AMOUNT(Pool.FIXED_AMOUNT, Weight.NONE);

  private final Pool pool;
  private final Weight weight;

  Basis(Pool pool, Weight weight) {
    this.pool = pool;
    this.weight = weight;
  }

  /** 这一档旧字面量对应的<b>池</b>（见类注的映射表）。 */
  public Pool pool() {
    return pool;
  }

  /** 这一档旧字面量对应的<b>权重</b>（见类注的映射表）。 */
  public Weight weight() {
    return weight;
  }

  /** 按词表解析：<b>词表外的输入即抛，消息列出全部合法值</b>（fail-closed，同 {@link RuleType#parse(String)} 的口径）。 */
  public static Basis parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Basis 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未登记的数量基准: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}
