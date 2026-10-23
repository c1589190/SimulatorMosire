package io.mosire.simos.economy.api.inventory;

/**
 * 库存语义上的统一规划口：只返回 {@link InventoryTransferPlan}，不直接写 actor/map/economy 状态。
 *
 * <p>具体写口仍由各领域 {@code ChangeSet} 承担；app 负责把计划中的域级变更集原子提交。
 */
public interface InventoryTransferPort {

  String REASON_LOT_MISSING = "inventory-transfer-lot-missing";
  String REASON_KIND_UNSUPPORTED = "inventory-transfer-kind-unsupported";
  String REASON_SOURCE_OWNER_MISMATCH = "inventory-transfer-source-owner-mismatch";
  String REASON_TARGET_OWNER_SAME = "inventory-transfer-target-owner-same";
  String REASON_QUANTITY_EXCEEDS_AVAILABLE = "inventory-transfer-quantity-exceeds-available";
  String REASON_FROZEN_SHORTFALL = "inventory-transfer-frozen-shortfall";
  String REASON_LOCATION_MISSING = "inventory-transfer-location-missing";
  String REASON_TARGET_LOCATION_MISMATCH = "inventory-transfer-target-location-mismatch";
  String REASON_EMPTY_MODULE_CHANGES = "inventory-transfer-empty-module-changes";

  /** 规划一次转移；任何拒绝都必须零 plan。 */
  InventoryTransferPlan plan(InventoryTransferRequest request);
}
