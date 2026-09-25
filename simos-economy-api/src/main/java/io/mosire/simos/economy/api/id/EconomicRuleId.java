package io.mosire.simos.economy.api.id;

/**
 * 经济规则 ID（设计稿 §3/§7）：版本化的 {@code EconomicRule}（税则、地租上限、最低工资、禁运等）的稳定身份， 归 {@code government} 切片。
 *
 * <p>规则存版本化值，政策改动产生新规则而非改旧值；取用时取事件地点与日期适用的那一版。 裸值 {@code toString()} + {@code static parse} 三件套（铁律
 * 1）。
 */
public record EconomicRuleId(String value) {

  public EconomicRuleId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("EconomicRuleId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static EconomicRuleId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("EconomicRuleId 不得为空白: " + text);
    }
    return new EconomicRuleId(text);
  }
}
