package io.mosire.simos.economy.api.id;

/**
 * ★ <b>外部放贷主体账户（{@code PilotModel.Lender}）的稳定身份</b>：不属于农业阶层结构的独立放贷方（模拟 GOV/特殊单位）。身份 = 放贷方 id 的纯函数。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套；规范串不含 {@code "."}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ExternalLenderId(String value) {

  /** {@link #of(String)} 的固定前缀（唯一拼写点）。 */
  public static final String PREFIX = "lender-";

  public ExternalLenderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ExternalLenderId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ExternalLenderId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束。 */
  public static ExternalLenderId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ExternalLenderId 不得为空白: " + text);
    }
    return new ExternalLenderId(text);
  }

  /** 新 id 的唯一拼写点：{@code lender-<lenderId>}（不含 {@code "."}）。 */
  public static ExternalLenderId of(String lenderId) {
    if (lenderId == null || lenderId.isBlank()) {
      throw new IllegalArgumentException("ExternalLenderId.of 的 lenderId 不得为空白");
    }
    String value = PREFIX + lenderId;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ExternalLenderId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ExternalLenderId(value);
  }
}
