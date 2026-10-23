package io.mosire.simos.economy.api.inventory;

/** 库存转移的业务原因；只用于审计/调用方分流，不参与适配器选择。 */
public enum InventoryTransferReason {
  MARKET_TRADE,
  DEBT,
  GIFT,
  OTHER
}
