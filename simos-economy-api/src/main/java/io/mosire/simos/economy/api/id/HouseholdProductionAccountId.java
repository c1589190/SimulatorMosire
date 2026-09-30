package io.mosire.simos.economy.api.id;

/**
 * ★ <b>家户生产子账户（{@code HouseholdProductionAccount}）的稳定身份</b>：池内一个家户账户的身份。身份 = {@code (poolId,
 * householdId)} 的纯函数 —— 同一池同一家户恒得同一个 id。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套；规范串不含 {@code "."}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record HouseholdProductionAccountId(String value) {

  /** {@link #idOf(ClassPoolId, String)} 的固定前缀（唯一拼写点）。 */
  public static final String PREFIX = "hpa-";

  public HouseholdProductionAccountId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("HouseholdProductionAccountId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "HouseholdProductionAccountId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束。 */
  public static HouseholdProductionAccountId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("HouseholdProductionAccountId 不得为空白: " + text);
    }
    return new HouseholdProductionAccountId(text);
  }

  /** 新 id 的唯一拼写点：{@code hpa-<poolId>:<householdId>}（不含 {@code "."}）。 */
  public static HouseholdProductionAccountId idOf(ClassPoolId poolId, String householdId) {
    if (poolId == null) {
      throw new IllegalArgumentException("HouseholdProductionAccountId.idOf 的 poolId 不得为 null");
    }
    if (householdId == null || householdId.isBlank()) {
      throw new IllegalArgumentException("HouseholdProductionAccountId.idOf 的 householdId 不得为空白");
    }
    String value = PREFIX + poolId.value() + ":" + householdId;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "HouseholdProductionAccountId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new HouseholdProductionAccountId(value);
  }
}
