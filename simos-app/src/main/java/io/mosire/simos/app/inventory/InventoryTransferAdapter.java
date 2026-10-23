package io.mosire.simos.app.inventory;

import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryTransferPlan;
import io.mosire.simos.economy.api.inventory.InventoryTransferRequest;

/** 按库存 lot kind 分派的领域适配器。 */
public interface InventoryTransferAdapter {

  /** 本适配器负责的 lot 粗分类。 */
  InventoryLotKind kind();

  /** {@link #kind()} 的兼容别名（调用方按 item kind 表达分派时使用）。 */
  default InventoryLotKind itemKind() {
    return kind();
  }

  /** 纯规划；不得修改上下文中的基础状态。 */
  InventoryTransferPlan plan(InventoryTransferContext context, InventoryTransferRequest request);
}
