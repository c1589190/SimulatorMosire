package io.mosire.simos.economy.api.relation;

import java.util.Arrays;

/**
 * ★★ <b>补偿规则的「数量取自哪一层」（六档词表）</b>：spec §2.4 明文要求 {@code basis} <b>必须显式</b> —— 否则「地租 30%」是总收成的 30%
 * 还是扣种子后的 30%，迟早出争议（spec 原文）。{@link RuleType} 说「怎么算」，本类型说「算的是哪一层」。
 *
 * <p>★★ <b>第 6 档 {@link #FIXED_AMOUNT} 是本计划的补档（裁定 E5）</b>：spec §2.4 只列了五个 {@code basis}，它们说的全是
 * 「<b>每单位什么</b>」（毛产 / 净产 / 经营剩余 / 劳动量 / 资产量），而 {@code FIXED_IN_KIND_RENT} 与 {@code FIXED_MONEY_*}
 * 的「数量从哪来」<b>没有落点</b> —— <b>固定额没有单位</b>，它不按任何比例算。⇒ 与「{@code CohortKey} 的落点」同款修正： <b>spec
 * 的枚举不全，按结算公式表补一档</b>。
 *
 * <p>★ <b>本类型不做 type × basis 的组合守卫</b>（有意为之）：哪些组合有公式，是 {@code ProductionSettlement}（Task 3）的 公式表说了算
 * —— 在这里先禁掉一批组合，等于把「还没定的设计」写进契约（本仓禁的正是这个）。
 *
 * <p>★ <b>fail-closed 的词表</b>：同 {@link RuleType#parse(String)}，词表外即抛并列出六档。
 */
public enum Basis {

  /** 总产出（毛产）：不扣任何东西。 */
  GROSS_OUTPUT,

  /** 扣投入后的净产出。 */
  NET_AFTER_INPUTS,

  /** 经营剩余：**已按 priority 序付出去之后**剩下的那一层（⇒ 次序成了数据，见 {@code CompensationRule.priority}）。 */
  OPERATOR_SURPLUS,

  /** 按劳动量（这一档的受方出了多少劳动）。 */
  LABOR_AMOUNT,

  /** 按资产量（这一档的受方持有多少资产）。 */
  ASSET_QUANTITY,

  /**
   * ★ <b>固定额（第 6 档，裁定 E5）</b>：数量 = {@code CompensationRule.fixedAmount}，与产出、劳动、资产<b>都无关</b>。
   *
   * <p>★ 它是 {@code FIXED_IN_KIND_RENT} / {@code FIXED_IN_KIND_PER_LABOR} / {@code FIXED_MONEY_*}
   * 的落点。
   */
  FIXED_AMOUNT;

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
