package io.mosire.simos.economy.api.inventory;

/** 库存 lot 的粗分类；{@code ordinal} 同时是统一视图的稳定首排序键。 */
public enum InventoryLotKind {
  COMMODITY,
  LAND,
  ASSET
}
