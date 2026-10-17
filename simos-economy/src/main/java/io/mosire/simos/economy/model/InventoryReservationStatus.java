package io.mosire.simos.economy.model;

import java.util.Arrays;

/** D-032 D4 商品库存预留状态（D4 §4.4）：ACTIVE 计入可支付库存扣减，RELEASED 保留审计依据。 */
public enum InventoryReservationStatus {
  ACTIVE,
  RELEASED;

  /** 按词表解析；未知值 fail-closed（不默认、不归一）。 */
  public static InventoryReservationStatus parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("InventoryReservationStatus 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 InventoryReservationStatus: " + text + "；合法值: " + Arrays.toString(values()), e);
    }
  }
}
