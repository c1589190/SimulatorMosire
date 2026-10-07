package io.mosire.simos.gov;

/**
 * 政府预算支出类别词表（Z2，设计书 §9 / §14.4）。
 *
 * <p>★★ <b>默认顺序（优先级从高到低）</b>：{@link #ADMIN_STIPEND 行政俸禄} → {@link #MILITARY_STIPEND 军俸} → {@link
 * #ADMIN_SALARY 行政工资} → {@link #DEBT_SERVICE 债务} → {@link #OTHER 其他}。顺序本身存在 {@link
 * GovBudgetPolicy#orderedCategories()} 的<b>列表序</b>里；本枚举只固定类别词表，不强制五类齐全、也不重排调用方的序。
 *
 * <p>★ <b>为什么是枚举</b>：设计书把类别词表先冻结为这五类；闭枚举让线格式（Jackson 写常量名）与命令载荷拒绝口径都
 * fail-closed——未知类别当场拒，不做字符串模糊匹配。
 */
public enum GovBudgetCategory {
  /** 行政俸禄（国库 → 官吏户的俸禄/赏赐类；默认优先级最高）。 */
  ADMIN_STIPEND("行政俸禄"),

  /** 军俸（与 P4b 军俸链的类别口径对齐；默认第二）。 */
  MILITARY_STIPEND("军俸"),

  /** 行政工资（按承诺小时计的官吏工资；默认第三；执行归 Z3）。 */
  ADMIN_SALARY("行政工资"),

  /** 债务（本 GOV 的债务偿付；默认第四）。 */
  DEBT_SERVICE("债务"),

  /** 其他（兜底类别；默认最后）。 */
  OTHER("其他");

  private final String label;

  GovBudgetCategory(String label) {
    this.label = label;
  }

  /** 类别的中文名（设计书默认词表原文；只供读口/日志，不是 id）。 */
  public String label() {
    return label;
  }
}
