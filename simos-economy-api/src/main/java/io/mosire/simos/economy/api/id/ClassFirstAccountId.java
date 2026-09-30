package io.mosire.simos.economy.api.id;

/**
 * ★ <b>阶层池双边滚动账户（{@code ClassFirstAccount}）的稳定身份</b>：一条 {@code (owner, counterparty, unit)}
 * 账户的身份。身份 = 三元组的纯函数 —— 同一对主体同一单位恒得同一个 id（利息/催收靠它跨 tick 滚动）。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套；规范串不含 {@code "."}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ClassFirstAccountId(String value) {

  /** {@link #idOf(String, String, String)} 的固定前缀（唯一拼写点）。 */
  public static final String PREFIX = "acct-";

  public ClassFirstAccountId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccountId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassFirstAccountId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束。 */
  public static ClassFirstAccountId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccountId 不得为空白: " + text);
    }
    return new ClassFirstAccountId(text);
  }

  /** 新 id 的唯一拼写点：{@code acct-<owner>|<counterparty>|<unit>}（不含 {@code "."}）。 */
  public static ClassFirstAccountId idOf(String ownerId, String counterpartyId, String unit) {
    if (ownerId == null || ownerId.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccountId.idOf 的 ownerId 不得为空白");
    }
    if (counterpartyId == null || counterpartyId.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccountId.idOf 的 counterpartyId 不得为空白");
    }
    if (unit == null || unit.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccountId.idOf 的 unit 不得为空白");
    }
    String value = PREFIX + ownerId + "|" + counterpartyId + "|" + unit;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassFirstAccountId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ClassFirstAccountId(value);
  }
}
