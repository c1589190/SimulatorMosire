package io.mosire.simos.economy.api.stock;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>家户库存扣除的「制度原因」封闭词表</b>（2026-10-09 用户裁定：通用扣除接口 = 传入「家户 + 扣除的库存 + 扣除理由」即可扣除）。
 *
 * <p>★★ <b>为什么 reason 必须是封闭枚举而不是自由文本</b>：同一笔从家户账上扣走的粮/钱，可能是<b>辖区税</b>（有税率、税基、辖区政策）、可能是
 * <b>行政俸禄</b>（付给本 GOV 的编制人员）、可能是<b>军队俸禄</b>（军队走到哪、由决策人决定给下一 tick 的扣除计划）、 还可能是<b>徭役折算</b>。
 * 这四件事在账上是同一条负增量，但判据完全不同（前两者查 gov 政策、军俸查决策令与编制、徭役查社会批次）。
 * <b>没有这一维，四件事在账上分不出来</b>；写成自由字符串则拼错一个档<b>不会报错</b>，只会让统计静默漏掉一类。 新增一档 = 显式代码改动。
 *
 * <p>★★ <b>规范字面量 = {@link #value()} 的小写形式</b>（与 {@code TransferReason} / {@code PriceMode} 同制：规范串用
 * {@code value()}，{@link #parse} 只认它、<b>不做归一</b>）—— 归一是"猜"，写错一个档必须<b>当场炸</b>并看得见全部合法值。
 *
 * <p>★ <b>本批真正有写者的四档</b>：{@link #JURISDICTION_TAX}（{@code JurisdictionDailyTax} 走通用服务）、{@link
 * #ADMIN_UPKEEP}（{@code GovernmentUpkeepOracle} / {@code GovDaily} 走通用服务）、{@link
 * #MILITARY_SALARY}（{@code MilitaryPayRuleBridge} → {@code PeriodicHouseholdAdjustmentExecutor}
 * 的转移路径）、{@link #ADMIN_SALARY}（Z3c 的 {@code GovSalaryRuleBridge}，同样走 P4a 执行器的原子转移）。 {@link #CORVEE}
 * 是<b>留位</b>（如实记： 今天没有生产写者；徭役人口口径随 {@code Unit.manpower} 退役，抽人走社会工单路径）。
 */
public enum DeductionReason {

  /** 军队俸禄（军队走到哪、由决策人给下一 tick 的扣除计划；本批先提供通用扣除路径）。 */
  MILITARY_SALARY("military_salary"),

  /** 辖区日税（税率/税基/辖区/效率是政策层的事，本档只记"这一笔是税"）。 */
  JURISDICTION_TAX("jurisdiction_tax"),

  /**
   * 行政俸禄 / 行政物资（{@code GovDaily} 的编制人员供给与俸禄，付款方 = 政府家户）。★ Z7b 起<b>粮/布腿转给本 GOV 的官吏户</b> （{@code
   * GovernmentUpkeepOracle} 按 {@code GOV_SERVICE} 承诺份额分摊）；<b>银腿仍走
   * sink</b>（非家户的衙门开销），不存在“同一份俸禄再付一次”。
   */
  ADMIN_UPKEEP("admin_upkeep"),

  /**
   * 官吏工资（Z3c，设计书 §9）：{@code hh-gov-<govUnitId>} 国库 → 官吏户的转移，按 {@code GovOfficialSalaryRule}
   * 的每承诺小时粮/银与 {@code GOV_SERVICE} 承诺劳动逐腿 min 支付；与 {@link #ADMIN_UPKEEP}（付给"整编"的 sink）语义分离。
   */
  ADMIN_SALARY("admin_salary"),

  /** 徭役折算（留位：今天没有生产写者；抽人走社会工单路径，不重现 {@code Unit.manpower}）。 */
  CORVEE("corvee");

  private final String value;

  DeductionReason(String value) {
    this.value = value;
  }

  /** 规范字面量（小写下划线）：进载荷、日志与审计的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<DeductionReason> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，同 {@code TransferReason} 的口径：不归一，归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static DeductionReason parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DeductionReason 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (DeductionReason reason : values()) {
      if (reason.value.equals(text)) {
        return reason;
      }
      legal.add(reason.value);
    }
    throw new IllegalArgumentException("未登记的扣除原因: " + text + "；合法值: " + legal);
  }
}
