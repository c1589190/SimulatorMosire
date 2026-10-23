package io.mosire.simos.app.inventory;

import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryTransferPlan;
import io.mosire.simos.economy.api.inventory.InventoryTransferPort;
import io.mosire.simos.economy.api.inventory.InventoryTransferRequest;
import io.mosire.simos.util.state.ChangeSet;
import java.util.Map;
import java.util.Objects;

/** app 侧 U1b 端口实现：取 lot、按 kind 分派、统一校验计划形状。 */
public final class InventoryTransferPortImpl implements InventoryTransferPort {

  private final InventoryTransferContext context;
  private final InventoryTransferAdapterRegistry registry;

  public InventoryTransferPortImpl(InventoryTransferContext context) {
    this(context, InventoryTransferAdapterRegistry.standard());
  }

  public InventoryTransferPortImpl(
      InventoryTransferContext context, InventoryTransferAdapterRegistry registry) {
    this.context = Objects.requireNonNull(context, "context");
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** 兼容参数顺序：注册表在前、上下文在后。 */
  public InventoryTransferPortImpl(
      InventoryTransferAdapterRegistry registry, InventoryTransferContext context) {
    this(context, registry);
  }

  public static InventoryTransferPortImpl standard(InventoryTransferContext context) {
    return new InventoryTransferPortImpl(context, InventoryTransferAdapterRegistry.standard());
  }

  @Override
  public InventoryTransferPlan plan(InventoryTransferRequest request) {
    return plan(context, request);
  }

  /** 使用显式上下文规划；便于同一端口服务多次载入。 */
  public InventoryTransferPlan plan(
      InventoryTransferContext explicitContext, InventoryTransferRequest request) {
    InventoryTransferContext usedContext = Objects.requireNonNull(explicitContext, "context");
    Objects.requireNonNull(request, "request");
    InventoryLot lot;
    try {
      lot = usedContext.view().require(request.sourceLotId());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          REASON_LOT_MISSING + ": " + request.sourceLotId().value(), e);
    }
    InventoryTransferAdapter adapter = registry.require(lot.itemKind());
    InventoryTransferPlan plan = adapter.plan(usedContext, request);
    validatePlan(request, plan);
    return plan;
  }

  private static void validatePlan(InventoryTransferRequest request, InventoryTransferPlan plan) {
    if (plan == null) {
      throw new IllegalStateException("inventory-transfer-adapter-returned-null-plan");
    }
    if (plan.moduleChanges() == null || plan.moduleChanges().isEmpty()) {
      throw new IllegalArgumentException(REASON_EMPTY_MODULE_CHANGES);
    }
    for (Map.Entry<String, ChangeSet> entry : plan.moduleChanges().entrySet()) {
      if (entry.getKey() == null
          || (!InventoryTransferPlan.ACTOR_MODULE_KEY.equals(entry.getKey())
              && !InventoryTransferPlan.MAP_MODULE_KEY.equals(entry.getKey()))) {
        throw new IllegalStateException(
            "inventory-transfer-unsupported-module-change-key: " + entry.getKey());
      }
      if (entry.getValue() == null) {
        throw new IllegalStateException("inventory-transfer-null-module-change: " + entry.getKey());
      }
    }
    if (!request.transferId().equals(plan.transferId())
        || !request.sourceLotId().equals(plan.sourceLotId())
        || request.quantityMilli() != plan.movedQuantityMilli()) {
      throw new IllegalStateException(
          "inventory-transfer-plan-request-mismatch: request="
              + request.transferId()
              + "/"
              + request.sourceLotId()
              + "/"
              + request.quantityMilli()
              + " plan="
              + plan.transferId()
              + "/"
              + plan.sourceLotId()
              + "/"
              + plan.movedQuantityMilli());
    }
    if (plan.receipt() == null) {
      throw new IllegalStateException("inventory-transfer-receipt-missing");
    }
    if (!plan.transferId().equals(plan.receipt().transferId())
        || !plan.sourceLotId().equals(plan.receipt().sourceLotId())
        || plan.movedQuantityMilli() != plan.receipt().movedQuantityMilli()) {
      throw new IllegalStateException(
          "inventory-transfer-receipt-mismatch: plan="
              + plan.transferId()
              + "/"
              + plan.sourceLotId()
              + "/"
              + plan.movedQuantityMilli()
              + " receipt="
              + plan.receipt().transferId()
              + "/"
              + plan.receipt().sourceLotId()
              + "/"
              + plan.receipt().movedQuantityMilli());
    }
  }
}
