package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.Objects;

/**
 * ★★ <b>D-032 D4 具名业务拒绝</b>（D4 §5.4/§7.5）：业务拒绝是 INFO，不是契约故障。
 *
 * @param unitId 被拒的生产实例
 * @param reason D4 稳定拒绝词（如 {@code asset-short:LAND}）
 */
public record AssetOccupancyRejection(ProductionUnitId unitId, String reason) {

  public AssetOccupancyRejection {
    Objects.requireNonNull(unitId, "AssetOccupancyRejection.unitId 不得为 null");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("AssetOccupancyRejection.reason 不得为空白");
    }
  }
}
