package io.mosire.simos.economy.api.inventory;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;
import java.util.Optional;

/**
 * 统一库存转移请求：按 source lot 的 kind 选择后端适配器。
 *
 * @param transferId 幂等/审计身份
 * @param sourceLotId 源 lot
 * @param quantityMilli 转移数量（最小计量单位，{@code > 0}）
 * @param targetOwner 目标家户
 * @param targetLocation 目标位置；缺省由适配器按源 lot 规则补齐
 * @param reason 业务原因
 */
public record InventoryTransferRequest(
    InventoryTransferId transferId,
    InventoryLotId sourceLotId,
    long quantityMilli,
    HouseholdId targetOwner,
    Optional<HexCoord> targetLocation,
    InventoryTransferReason reason) {

  public InventoryTransferRequest {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sourceLotId, "sourceLotId");
    Objects.requireNonNull(targetOwner, "targetOwner");
    Objects.requireNonNull(targetLocation, "targetLocation");
    Objects.requireNonNull(reason, "reason");
    if (quantityMilli <= 0L) {
      throw new IllegalArgumentException(
          "InventoryTransferRequest.quantityMilli 必须大于 0: " + quantityMilli);
    }
  }
}
