package io.mosire.simos.economy.api.id;

/**
 * 政府机构 ID（设计稿 §7）：{@code FiscalGovernment} 的稳定身份，归 {@code government} 切片。
 *
 * <p>政府以本 ID 关联 SDSimos 的国家 ID，但**不由 SD 直接保存财政数据**：国家身份仍属 {@code sd}，税权/税则/ 辖区/执行覆盖属本切片，国库余额属
 * {@code ledger}。裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record GovernmentId(String value) {

  public GovernmentId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("GovernmentId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static GovernmentId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("GovernmentId 不得为空白: " + text);
    }
    return new GovernmentId(text);
  }
}
