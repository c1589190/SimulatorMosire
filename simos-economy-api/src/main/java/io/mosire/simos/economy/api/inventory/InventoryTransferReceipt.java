package io.mosire.simos.economy.api.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 库存转移计划的不可变回执。
 *
 * @param transferId 转移身份
 * @param sourceLotId 源 lot
 * @param movedQuantityMilli 实际规划转移数量
 * @param targetLotIds 转移后目标侧的 lot 身份（商品为聚合行，土地为 component lot）
 * @param backendKind 实际后端
 * @param statePreserved 是否保留领域状态
 */
public record InventoryTransferReceipt(
    InventoryTransferId transferId,
    InventoryLotId sourceLotId,
    long movedQuantityMilli,
    List<InventoryLotId> targetLotIds,
    InventoryBackendKind backendKind,
    boolean statePreserved) {

  public InventoryTransferReceipt {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sourceLotId, "sourceLotId");
    Objects.requireNonNull(targetLotIds, "targetLotIds");
    Objects.requireNonNull(backendKind, "backendKind");
    if (movedQuantityMilli <= 0L) {
      throw new IllegalArgumentException(
          "InventoryTransferReceipt.movedQuantityMilli 必须大于 0: " + movedQuantityMilli);
    }
    List<InventoryLotId> copy = new ArrayList<>(targetLotIds.size());
    for (InventoryLotId targetLotId : targetLotIds) {
      copy.add(Objects.requireNonNull(targetLotId, "targetLotIds 不得含 null"));
    }
    targetLotIds = Collections.unmodifiableList(copy); // 保序不可变
  }
}
