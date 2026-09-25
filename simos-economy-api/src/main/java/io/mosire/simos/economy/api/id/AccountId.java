package io.mosire.simos.economy.api.id;

/**
 * 账本账户 ID（设计稿 §2）：各主体的商品与货币账户的稳定身份，归 {@code ledger} 切片；政府国库也是 ledger 中的账户。
 *
 * <p>库存与货币余额**始终以 ledger 为准**，市场/生产只引用账户 ID。裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record AccountId(String value) {

  public AccountId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AccountId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static AccountId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AccountId 不得为空白: " + text);
    }
    return new AccountId(text);
  }
}
