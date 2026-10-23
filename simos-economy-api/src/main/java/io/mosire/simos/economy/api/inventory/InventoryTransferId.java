package io.mosire.simos.economy.api.inventory;

/**
 * 一次库存转移的稳定幂等身份。
 *
 * <p>规范串只在本类型内解释；构造与 {@link #parse(String)} 都只校验非空白，不猜业务格式。
 *
 * @param value 非空白规范串
 */
public record InventoryTransferId(String value) {

  public InventoryTransferId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("InventoryTransferId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 线格式 → 转移身份。 */
  public static InventoryTransferId parse(String text) {
    return new InventoryTransferId(text);
  }
}
